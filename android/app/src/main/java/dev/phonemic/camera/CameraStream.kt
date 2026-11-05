package dev.phonemic.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import dev.phonemic.audio.MicState
import dev.phonemic.audio.Transport
import dev.phonemic.connection.DeviceProfiles
import dev.phonemic.connection.sleepQuietly
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Where the laptop is: over the cable the adb tunnel ends on this device's own localhost. */
object LaptopAddress {
    /** The host to connect to, or "" when it is not known yet (Wi-Fi without an address). */
    fun host(transport: Int = MicState.transport, wifiHost: String = MicState.wifiHost): String =
        if (transport == Transport.USB) "127.0.0.1" else wifiHost.trim()

    /** True when the laptop would accept us: the cable needs no code, Wi-Fi does. */
    fun ready(transport: Int = MicState.transport, wifiHost: String = MicState.wifiHost, code: String = MicState.pairCode) =
        host(transport, wifiHost).isNotEmpty() && (transport == Transport.USB || code.length == 6)
}

/** The camera picture is sent as one JPEG per frame (see windows/camera/server.py). */
object CameraPacket {
    const val FLAG_MIRROR = 1
    const val HEADER_SIZE = 7

    /** 4 bytes JPEG length, 2 bytes rotation (0/90/180/270), 1 byte flags, then the JPEG itself. */
    fun header(length: Int, rotation: Int, mirror: Boolean): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).putInt(length).putShort(rotation.toShort())
            .put((if (mirror) FLAG_MIRROR else 0).toByte()).array()

    /** CameraX gives any multiple of 90; the laptop accepts exactly 0, 90, 180 and 270. */
    fun normalizeRotation(degrees: Int) = ((degrees % 360) + 360) % 360 / 90 * 90
}

/** The sizes the camera sheet offers. */
enum class CameraQuality(val label: String, val width: Int, val height: Int) {
    HD("720p", 1280, 720), FULL_HD("1080p", 1920, 1080)
}

/** What the camera sheet shows. Read by the UI every few hundred ms, like [MicState]. */
object CameraState {
    @Volatile var sending = false          // the user pressed Start
    @Volatile var online = false           // the laptop is connected and has answered
    @Volatile var status = "Not sending"
    @Volatile var virtualName = ""         // "OBS Virtual Camera" once Windows has a webcam for it
    @Volatile var note = ""                // why Windows has none, e.g. OBS is not installed
    @Volatile var frames = 0L
    @Volatile var mirror = false
    @Volatile var front = false            // front camera instead of the back one
    @Volatile var quality = CameraQuality.HD

    private const val PREFS = "camera"     // its own file: MicState.save never touches it

    /** The user's last picks come back when the Camera page opens again. */
    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        front = p.getBoolean("front", false)
        mirror = p.getBoolean("mirror", false)
        quality = CameraQuality.values().firstOrNull { it.name == p.getString("quality", null) } ?: CameraQuality.HD
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("front", front)
            .putBoolean("mirror", mirror)
            .putString("quality", quality.name)
            .apply()
    }
}

/** Sends camera frames to the laptop (port 50509). One connection while [CameraState.sending] is true. */
object CameraStreamer {
    const val PORT = 50509
    private class Frame(val jpeg: ByteArray, val rotation: Int, val mirror: Boolean)

    private val queue = LinkedBlockingQueue<Frame>(1)       // only the newest picture matters
    @Volatile private var generation = 0
    @Volatile private var worker: Thread? = null

    fun start() {
        if (worker?.isAlive == true) return
        val mine = ++generation
        CameraState.sending = true
        worker = Thread({ loop(mine) }, "camera-stream").apply { isDaemon = true; start() }
    }

    fun stop() {
        generation++
        CameraState.sending = false
        CameraState.online = false
        CameraState.status = "Not sending"
        queue.clear()
        worker?.interrupt()
    }

    /** Called for every camera frame; drops it when the laptop is not ready for more. */
    fun submit(jpeg: ByteArray, rotation: Int, mirror: Boolean) {
        if (!CameraState.sending) return
        queue.clear()
        queue.offer(Frame(jpeg, CameraPacket.normalizeRotation(rotation), mirror))
    }

    private fun loop(mine: Int) {
        while (generation == mine) {
            if (!LaptopAddress.ready()) {
                CameraState.status = "Set the laptop address and pairing code in Connection"
                sleepQuietly(800); continue
            }
            val host = LaptopAddress.host()
            CameraState.status = "Connecting to $host…"
            try {
                session(mine, host)
            } catch (e: Exception) {
                if (generation == mine) CameraState.status = "Can't reach the laptop"
            }
            CameraState.online = false
            if (generation == mine) sleepQuietly(1500)
        }
    }

    private fun session(mine: Int, host: String) {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, PORT), 3000)
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            out.write((JSONObject().put("t", "hello").put("code", MicState.pairCode)
                .put("device", DeviceProfiles.hello()).toString() + "\n").toByteArray())
            out.flush()
            val reader = Thread({ readAnswers(mine, s) }, "camera-answers").apply { isDaemon = true; start() }
            try {
                queue.clear()
                while (generation == mine && !s.isClosed) {
                    val f = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    write(out, f)
                    CameraState.frames++
                }
            } finally {
                reader.interrupt()
            }
        }
    }

    private fun write(out: OutputStream, f: Frame) {
        out.write(CameraPacket.header(f.jpeg.size, f.rotation, f.mirror))
        out.write(f.jpeg)
        out.flush()
    }

    private fun readAnswers(mine: Int, s: Socket) {
        try {
            val r = s.getInputStream().bufferedReader()
            while (generation == mine) {
                val m = JSONObject(r.readLine() ?: break)
                when (m.optString("t")) {
                    "ready" -> {
                        CameraState.online = true
                        CameraState.virtualName = m.optString("virtual")
                        CameraState.note = m.optString("note")
                        CameraState.status = if (CameraState.virtualName.isNotEmpty())
                            "Sending as ${CameraState.virtualName}" else "Sending to the laptop"
                    }
                    "error" -> { CameraState.status = m.optString("msg", "Error"); CameraState.online = false }
                }
            }
        } catch (_: Exception) {
        }
        runCatching { s.close() }     // the laptop hung up: the sender loop reconnects
    }

}

/** Turns a CameraX YUV frame into a JPEG. */
object YuvJpeg {
    fun encode(image: ImageProxy, quality: Int = 80): ByteArray {
        val w = image.width
        val h = image.height
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = image.planes[0]
        copyPlane(y.buffer, y.rowStride, y.pixelStride, w, h, nv21, 0, 1)
        val u = image.planes[1]
        val v = image.planes[2]
        val cw = w / 2
        val ch = h / 2
        // NV21 stores V then U, interleaved
        copyPlane(v.buffer, v.rowStride, v.pixelStride, cw, ch, nv21, w * h, 2)
        copyPlane(u.buffer, u.rowStride, u.pixelStride, cw, ch, nv21, w * h + 1, 2)
        val out = ByteArrayOutputStream(w * h / 8)
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)
        return out.toByteArray()
    }

    /** Copies a plane that may have row padding and pixel gaps into [dst], [step] bytes apart. */
    private fun copyPlane(src: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int,
                          dst: ByteArray, offset: Int, step: Int) {
        src.rewind()
        var o = offset
        val row = ByteArray(rowStride)
        for (r in 0 until h) {
            val n = minOf(rowStride, src.remaining())
            src.get(row, 0, n)
            for (c in 0 until w) {
                dst[o] = row[c * pixelStride]
                o += step
            }
        }
    }
}
