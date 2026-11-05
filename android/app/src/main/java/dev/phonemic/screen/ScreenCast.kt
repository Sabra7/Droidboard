package dev.phonemic.screen

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import dev.phonemic.audio.MicState
import dev.phonemic.camera.LaptopAddress
import dev.phonemic.connection.DeviceProfiles
import dev.phonemic.connection.sleepQuietly
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The second-screen wire format (see windows/screen/cast.py). */
object ScreenPacket {
    const val VIDEO_HEADER_SIZE = 14
    const val TOUCH_SIZE = 7
    const val FLAG_KEY = 1
    const val KIND_TOUCH = 1
    const val KIND_KEYFRAME = 2
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val CANCEL = 3

    /** type(1) flags(1) length(4) time in microseconds(8). */
    class VideoHeader(val type: Int, val key: Boolean, val length: Int, val ptsUs: Long)

    fun parseVideoHeader(b: ByteArray): VideoHeader {
        val buf = ByteBuffer.wrap(b)
        val type = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        val length = buf.getInt()
        val pts = buf.getLong()
        require(length in 0..MAX_VIDEO) { "bad video packet" }
        return VideoHeader(type, flags and FLAG_KEY != 0, length, pts)
    }

    /** kind(1) action(1) finger(1) x(2) y(2); x and y are fractions of the picture. */
    fun touch(action: Int, finger: Int, x: Float, y: Float): ByteArray = ByteBuffer.allocate(TOUCH_SIZE)
        .put(KIND_TOUCH.toByte()).put(action.toByte()).put(finger.toByte())
        .putShort(scale(x).toShort()).putShort(scale(y).toShort()).array()

    fun keyframeRequest(): ByteArray = ByteBuffer.allocate(TOUCH_SIZE).put(KIND_KEYFRAME.toByte()).array()

    private fun scale(v: Float) = (v.coerceIn(0f, 1f) * 65535f).toInt()
    private const val MAX_VIDEO = 16 * 1024 * 1024
}

/** What the Screen tab and the full-screen view show. */
object ScreenState {
    @Volatile var active = false          // the user pressed Start
    @Volatile var online = false          // the laptop answered and sends video
    @Volatile var status = "Not casting"
    @Volatile var videoWidth = 16
    @Volatile var videoHeight = 9
    @Volatile var encoder = ""
    @Volatile var packets = 0L
    @Volatile var extend = true           // what the user picked: a real extra monitor (true) or a copy of the main screen
    @Volatile var shownMode = ""          // what the laptop really sends: "extend" or "mirror"
    @Volatile var note = ""               // why it is not what was picked, e.g. the driver is missing

    private const val PREFS = "screen"

    fun load(ctx: Context) {
        extend = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("extend", true)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("extend", extend).apply()
    }

    fun mode() = if (extend) "extend" else "mirror"
}

/** Decodes the H.264 video straight onto a [Surface], with the low-latency options on. */
class H264Decoder(surface: Surface, width: Int, height: Int) {
    private class Packet(val data: ByteArray, val ptsUs: Long)

    private val codec = MediaCodec.createDecoderByType("video/avc")
    private val queue = LinkedBlockingQueue<Packet>(6)
    @Volatile private var running = true
    private val thread: Thread

    init {
        val format = MediaFormat.createVideoFormat("video/avc", width, height)
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)                 // real time
        codec.configure(format, surface, null, 0)
        codec.start()
        thread = Thread({ run() }, "screen-decoder").apply { isDaemon = true; start() }
    }

    /** False when the decoder is behind: the caller asks the laptop for a fresh key frame. */
    fun submit(data: ByteArray, ptsUs: Long): Boolean = queue.offer(Packet(data, ptsUs))

    fun clear() = queue.clear()

    private fun run() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val p = queue.poll(5, TimeUnit.MILLISECONDS)
                if (p != null) {
                    val i = codec.dequeueInputBuffer(20_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        buf.put(p.data)
                        codec.queueInputBuffer(i, 0, p.data.size, p.ptsUs, 0)
                    }
                }
                while (true) {
                    val o = codec.dequeueOutputBuffer(info, 0)
                    if (o >= 0) codec.releaseOutputBuffer(o, true)      // true: show it now
                    else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break
                }
            }
        } catch (_: Exception) {
            // released while working: the end
        }
    }

    fun release() {
        running = false
        runCatching { thread.join(300) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/** Receives the laptop's screen (port 50510) and sends touch back. One connection while [ScreenState.active]. */
object ScreenClient {
    const val PORT = 50510
    @Volatile private var generation = 0
    @Volatile private var socket: Socket? = null
    private val lock = Any()
    private var surface: Surface? = null
    private var decoder: H264Decoder? = null
    private var width = 0
    private var height = 0
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "screen-send").apply { isDaemon = true } }
    private var metrics = intArrayOf(0, 0)
    private var appContext: Context? = null

    fun start(ctx: Context) {
        appContext = ctx.applicationContext
        metrics = screenSize(ctx)
        val mine = ++generation
        ScreenState.active = true
        Thread({ loop(mine) }, "screen-client").apply { isDaemon = true; start() }
    }

    /** The whole screen in pixels (long side first), not the app window: that one is without the system bars,
     *  and the laptop sizes its virtual monitor from this. */
    fun screenSize(ctx: Context): IntArray {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
        return intArrayOf(maxOf(w, h), minOf(w, h))
    }

    fun stop() {
        generation++
        ScreenState.active = false
        ScreenState.online = false
        ScreenState.status = "Not casting"
        ScreenState.note = ""
        runCatching { socket?.close() }
        synchronized(lock) { releaseDecoder() }
    }

    /** The full-screen view got (or lost) its surface. */
    fun attach(s: Surface?) = synchronized(lock) {
        surface = s
        releaseDecoder()
        if (s != null) openDecoder()
    }

    fun sendTouch(action: Int, finger: Int, x: Float, y: Float) = send(ScreenPacket.touch(action, finger, x, y))

    private fun send(bytes: ByteArray) {
        val s = socket ?: return
        sender.execute { runCatching { synchronized(s) { s.getOutputStream().apply { write(bytes); flush() } } } }
    }

    private fun openDecoder() {
        val s = surface ?: return
        if (width == 0 || decoder != null) return
        decoder = runCatching { H264Decoder(s, width, height) }.getOrNull()
        if (decoder == null) ScreenState.status = "This tablet could not start the H.264 decoder"
        else send(ScreenPacket.keyframeRequest())          // a decoder starts clean on a key frame
    }

    private fun releaseDecoder() {
        decoder?.release()
        decoder = null
    }

    private fun loop(mine: Int) {
        while (generation == mine) {
            if (!LaptopAddress.ready()) {
                ScreenState.status = "Set the laptop address and pairing code in Connection"
                sleepQuietly(800); continue
            }
            val host = LaptopAddress.host()
            ScreenState.status = "Connecting to $host…"
            try {
                session(mine, host)
            } catch (e: Exception) {
                if (generation == mine) ScreenState.status = "Can't reach the laptop"
            }
            ScreenState.online = false
            synchronized(lock) { width = 0; releaseDecoder() }
            if (generation == mine) sleepQuietly(1500)
        }
    }

    private fun session(mine: Int, host: String) {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, PORT), 3000)
            s.tcpNoDelay = true
            socket = s
            val out: OutputStream = s.getOutputStream()
            val hello = JSONObject().put("t", "hello").put("code", MicState.pairCode)
                .put("w", metrics[0]).put("h", metrics[1]).put("mode", ScreenState.mode())
            appContext?.let { hello.put("device", DeviceProfiles.get(it).toJson()) }
            out.write((hello.toString() + "\n").toByteArray())
            out.flush()
            val input = DataInputStream(s.getInputStream().buffered(1 shl 16))
            val ready = JSONObject(readLine(input))
            if (ready.optString("t") != "ready") {
                ScreenState.status = ready.optString("msg", "The laptop refused")
                sleepQuietly(2000)
                return
            }
            synchronized(lock) {
                width = ready.getInt("w"); height = ready.getInt("h")
                ScreenState.videoWidth = width; ScreenState.videoHeight = height
                ScreenState.encoder = ready.optString("encoder")
                ScreenState.shownMode = ready.optString("mode")
                ScreenState.note = ready.optString("note")
                openDecoder()
            }
            ScreenState.online = true
            ScreenState.status = if (ScreenState.shownMode == "extend") "Extended screen" else "Mirroring the main screen"
            val header = ByteArray(ScreenPacket.VIDEO_HEADER_SIZE)
            var waitingForKey = false
            while (generation == mine) {
                input.readFully(header)
                val h = ScreenPacket.parseVideoHeader(header)
                val data = ByteArray(h.length)
                input.readFully(data)
                if (h.type != 0) continue
                ScreenState.packets++
                val d = synchronized(lock) { decoder } ?: continue
                if (waitingForKey && !h.key) continue
                if (!d.submit(data, h.ptsUs)) {            // behind: skip to the next key frame instead of lagging
                    d.clear()
                    waitingForKey = true
                    send(ScreenPacket.keyframeRequest())
                } else if (h.key) waitingForKey = false
            }
        }
        socket = null
    }

    private fun readLine(input: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("the laptop closed the connection")
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > 4096) throw java.io.IOException("bad answer")
        }
    }

}
