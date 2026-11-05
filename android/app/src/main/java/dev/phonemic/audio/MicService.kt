package dev.phonemic.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.phonemic.MainActivity
import dev.phonemic.R
import dev.phonemic.connection.LaptopFinder
import dev.phonemic.connection.Protocol
import dev.phonemic.connection.TransportPolicy
import dev.phonemic.connection.isUsbPlugged
import dev.phonemic.connection.sleepQuietly
import dev.phonemic.tiles.DroidboardTile
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Foreground service: captures the mic and streams 10 ms PCM frames over TCP to
 * 127.0.0.1:PORT on the phone, which `adb reverse` forwards to the laptop over USB.
 */
class MicService : Service() {

    companion object {
        const val SAMPLE_RATE = 48000
        const val FRAME_SAMPLES = SAMPLE_RATE / 100   // 10 ms
        const val PORT = 50505
        private const val CHANNEL_MONO = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_STEREO = AudioFormat.CHANNEL_IN_STEREO
        private const val CHANNEL_ID = "phone_mic"
        private const val NOTIFICATION_ID = 1
        private const val BATTERY_NOTIFICATION_ID = 2
        private const val LIVE_GREEN = 0xFF00E65A.toInt()
        private const val ACTION_TOGGLE_MUTE = "dev.phonemic.TOGGLE_MUTE"
        // must match SERVICE_UUID in windows/connection/bluetooth.py
        private val BT_UUID = java.util.UUID.fromString("6c3a0f7e-5b1d-4a7e-9d54-0a2f7f5e1c01")
    }

    private var worker: Thread? = null
    private val filter = VoiceFilter(SAMPLE_RATE)
    private val filterR = VoiceFilter(SAMPLE_RATE)      // right channel (stereo): its own filter state
    private var clipHit = false
    private var suppressor: NoiseSuppressor? = null
    private var suppressorOn = false
    private var wakeLock: PowerManager.WakeLock? = null

    /** No point refreshing the notification while the screen is off. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ui.removeCallbacks(refreshNotification)
            if (intent.action == Intent.ACTION_SCREEN_ON) ui.post(refreshNotification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE_MUTE) {   // from the notification button
            MicState.muted = !MicState.muted
            DroidboardTile.refresh(this)
            if (worker != null) {
                postIfChanged()
            } else {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        startInForeground()
        if (worker == null) {
            MicState.running = true
            DroidboardTile.refresh(this)
            MicState.active = MicState.transport
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droidboard:stream")
                .apply { acquire() }   // keeps the CPU awake while the screen is off
            val session = ++MicState.session
            if (!finding) { finding = true; LaptopFinder.start(this) }   // keeps the Wi-Fi address current while streaming
            worker = Thread({ runLoop(session) }, "mic-stream").also { it.start() }
            ui.postDelayed(refreshNotification, 1000)
            ui.post(watch)
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)      // only the system sends these
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Swiped away from Recents means quit: stop everything and take the process down with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown()
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    /** Releases everything the service holds. Safe to call twice. */
    private fun shutdown() {
        ui.removeCallbacks(refreshNotification)
        ui.removeCallbacks(watch)
        MicState.callMuted = false
        MicState.autoStartHeld = true      // stopped: no auto start until the app is opened again
        try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (finding) { finding = false; LaptopFinder.stop() }
        closeBtServer()
        MicState.running = false
        worker?.interrupt()
        worker = null
        MicState.status = "Stopped"
        MicState.level = 0f
        MicState.active = MicState.transport
        DroidboardTile.refresh(this)
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Droidboard", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Ongoing notification with live status and a Mute / Unmute button. */
    private fun buildNotification(): Notification {
        val muted = MicState.muted
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, MicService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val via = if (MicState.active != MicState.transport) " · via ${Transport.NAMES[MicState.active]}" else ""
        val detail = "Isolation: ${VoiceFilter.NAMES[MicState.isolation.coerceIn(0, 4)]}$via"
        val shown = NowBarModel.info(MicState.status, MicState.silenced)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // one-color vector: the old ic_btn_speak_now is a multi-colored bitmap, and One UI
            // draws the Now Bar / status bar chip from the small icon
            .setSmallIcon(R.drawable.ic_tile_mic)
            .setContentTitle(shown.title)
            .setContentText(detail)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)                 // no clock to redraw
            .setColor(LIVE_GREEN)               // accent only: a colorized notification is not promoted
            .addAction(0, if (muted) "Unmute" else "Mute", toggle)
        // Android 16 Live Update (status bar chip, and the Samsung Now Bar where the phone allows it).
        // Older versions ignore both. No Samsung "automation" extra on purpose: see NowBarMode.
        if (MicState.nowBar == NowBarMode.CLEAN) {
            builder.setRequestPromotedOngoing(true)
                .setShortCriticalText(shown.chip)
        }
        return builder.build()
    }

    private val ui = Handler(Looper.getMainLooper())
    /**
     * Re-posting an unchanged notification makes the Now Bar redraw (and animate) for nothing,
     * so only post when the visible text changes. Checking is cheap; it runs every 2 s.
     */
    private var lastShown = ""

    private fun postIfChanged() {
        val shown = "${NowBarModel.info(MicState.status, MicState.silenced)}|${MicState.isolation}|${MicState.active}|" +
            MicState.nowBar
        if (shown != lastShown) {
            lastShown = shown
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /**
     * Settings > Automatic, checked every 2 s even with the screen off (a call is taken with the screen off):
     * silence during a call, and stop when the battery runs low off the charger.
     */
    private val watch = object : Runnable {
        override fun run() {
            val inCall = MicState.muteOnCall && AutoRules.inCall(getSystemService(AudioManager::class.java).mode)
            if (inCall != MicState.callMuted) {
                MicState.callMuted = inCall
                postIfChanged()
            }
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            if (AutoRules.stopForBattery(MicState.lowBatteryStop, percent, charging)) {
                stopForLowBattery(percent)
                return
            }
            ui.postDelayed(this, 2000)
        }
    }

    private fun stopForLowBattery(percent: Int) {
        getSystemService(NotificationManager::class.java).notify(BATTERY_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile_mic)
                .setContentTitle("Droidboard stopped")
                .setContentText("Battery at $percent%. Turn it back on from the app or plug in the charger.")
                .setAutoCancel(true)
                .build())
        shutdown()
        stopSelf()
    }

    private val refreshNotification = object : Runnable {
        override fun run() {
            postIfChanged()
            ui.postDelayed(this, 2000)
        }
    }

    // ------------------------------------------------------------ streaming

    private fun alive(session: Int) = MicState.running && MicState.session == session

    private fun runLoop(session: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(FRAME_SAMPLES)
        val stereoRaw = ShortArray(FRAME_SAMPLES * 2)       // L R L R ... as recorded
        val stereoOut = ShortArray(FRAME_SAMPLES * 2)       // L R L R ... as sent
        val left = ShortArray(FRAME_SAMPLES)
        val right = ShortArray(FRAME_SAMPLES)
        val packet = ByteBuffer.allocate(Protocol.HEADER_SIZE + FRAME_SAMPLES * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        var seq = 0L
        var ticks = 0

        while (alive(session)) {
            val link = connectOrNull() ?: continue
            // What can the laptop decode? It says so first thing; silence means an older laptop app: PCM.
            val choice = CodecPolicy.pick(
                link.transport, MicState.compressWifi, OpusEncoder.isAvailable(),
                Protocol.capsHaveOpus(link.readCaps(if (link.transport == Transport.BLUETOOTH) 1500 else 600)),
            )
            MicState.codecNote = choice.note
            val decim = choice.decim                    // PCM over Bluetooth is sent at 16 kHz instead of 48 kHz
            var record: AudioRecord = openRecorder(wantsStereo(decim)) ?: run {
                MicState.status = "Could not open the microphone"
                link.close()
                MicState.running = false
                stopSelf()
                return
            }
            var held = true     // false while no recorder is open (mid-swap), so it is never released twice
            MicState.status = "Connected, streaming"
            if (MicState.active == Transport.WIFI) MicState.rememberLaptop(this)   // its code worked: keep it
            var channels = announceChannels(record, decim)
            val check = StereoCheck()
            var encoder: OpusEncoder? = null
            val small = ShortArray(FRAME_SAMPLES / decim)
            var micRevision = MicState.micRevision
            var sinceOpen = 0
            try {
                if (choice.opus) {
                    encoder = OpusEncoder.create(channels, CodecPolicy.bitrate(channels))
                        ?: throw IllegalStateException("could not start the Opus encoder")
                }
                record.startRecording()
                val out = BufferedOutputStream(link.out, packet.capacity())
                while (alive(session)) {
                    if (MicState.micRevision != micRevision) {
                        // the user picked another mic: swap the recorder, keep the connection
                        micRevision = MicState.micRevision
                        releaseRecorder(record)
                        held = false
                        record = openRecorder(wantsStereo(decim))
                            ?: throw IllegalStateException("could not open the chosen mic")
                        held = true
                        record.startRecording()
                        sinceOpen = 0
                        channels = announceChannels(record, decim)
                        check.reset()
                        if (choice.opus) {      // one or two channels now: a new encoder
                            encoder?.close()
                            encoder = OpusEncoder.create(channels, CodecPolicy.bitrate(channels))
                                ?: throw IllegalStateException("could not start the Opus encoder")
                        }
                    }
                    val muted: Boolean
                    val payload: ShortArray
                    if (channels == 2) {
                        readFrame(record, stereoRaw)
                        StereoMath.split(stereoRaw, left, right, MicState.channelMode == ChannelMode.SWAP)
                        check.feed(left, right)?.let {
                            MicState.stereoNote =
                                if (it) "Both sides carry the same sound: the phone gave one mic" else ""
                        }
                        muted = applyGainAndMute(left, right)
                        StereoMath.join(left, right, stereoOut)
                        payload = stereoOut
                    } else {
                        readFrame(record, frame)
                        muted = applyGainAndMute(frame, null)
                        payload = if (decim == 1) frame else {
                            for (i in small.indices) {
                                var sum = 0
                                for (j in 0 until decim) sum += frame[i * decim + j]
                                small[i] = (sum / decim).toShort()
                            }
                            small
                        }
                    }
                    // give Android a moment to settle, then ask which mic it really picked
                    if (++sinceOpen == 20) MicCatalog.reportActive(this, record, MicState.micChoice)
                    val flags = if (muted) Protocol.FLAG_MUTED else 0
                    val enc = encoder
                    if (enc != null) {
                        // Opus: the codec makes a packet every 20 ms, so some frames give none
                        enc.encode(payload) { pkt ->
                            packet.clear()
                            Protocol.writeHeader(
                                packet, seq, flags, SystemClock.elapsedRealtimeNanos(), SAMPLE_RATE,
                                pkt.size, channels, Protocol.CODEC_OPUS,
                            )
                            packet.put(pkt)
                            out.write(packet.array(), 0, packet.position())
                            seq = (seq + 1) and 0xFFFFFFFFL
                            MicState.framesSent++
                        }
                    } else {
                        packet.clear()
                        Protocol.writeHeader(
                            packet, seq, flags,
                            SystemClock.elapsedRealtimeNanos(), SAMPLE_RATE / decim, payload.size * 2,
                            channels,
                        )
                        for (s in payload) packet.putShort(s)
                        out.write(packet.array(), 0, packet.position())
                        seq = (seq + 1) and 0xFFFFFFFFL
                        MicState.framesSent++
                    }
                    out.flush()
                    // about once a second: has the cable been pulled out, or put back?
                    if (++ticks % 100 == 0 && wantedTransport() != MicState.active) {
                        MicState.status = "Switching connection…"
                        break
                    }
                }
            } catch (e: IOException) {
                if (alive(session)) MicState.status = "Connection lost, retrying…"
            } catch (e: IllegalStateException) {
                if (alive(session)) MicState.status = "Mic error: ${e.message}"
            } finally {
                if (held) releaseRecorder(record)
                encoder?.close()
                link.close()
                MicState.level = 0f
                MicState.activeMic = ""
                MicState.micNote = ""
                MicState.levelL = 0f; MicState.levelR = 0f
                MicState.channelsInUse = 1
                MicState.stereoNote = ""
                MicState.codecNote = ""
            }
        }
    }

    private class Link(
        val out: OutputStream,
        val input: InputStream?,
        val transport: Int,                 // Transport.* this link goes over
        val close: () -> Unit,
    ) {
        /** The laptop's capabilities message (sent first thing), or null if none comes in time. */
        fun readCaps(timeoutMs: Long): ByteArray? {
            val ins = input ?: return null
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            try {
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (ins.available() >= Protocol.CAPS_SIZE) {
                        val b = ByteArray(Protocol.CAPS_SIZE)
                        var n = 0
                        while (n < b.size) {
                            val r = ins.read(b, n, b.size - n)
                            if (r < 0) return null
                            n += r
                        }
                        return b
                    }
                    Thread.sleep(20)
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
            return null
        }
    }

    private var btServer: BluetoothServerSocket? = null
    private var finding = false          // this service holds a LaptopFinder user

    /** Tries to reach the laptop over the wanted transport; waits between attempts. */
    private fun connectOrNull(): Link? {
        val t = wantedTransport()
        MicState.active = t
        if (t != Transport.BLUETOOTH) closeBtServer()
        return when (t) {
            Transport.WIFI -> connectTcp(MicState.wifiHost.trim(), withHello = true)
            Transport.BLUETOOTH -> acceptBluetooth()
            else -> connectTcp("127.0.0.1", withHello = false)   // USB: adb reverse
        }
    }

    // ---- automatic fallback when the USB cable is pulled (see TransportPolicy)

    private var unplugSince = 0L     // worker thread only

    private fun wantedTransport(): Int {
        val now = SystemClock.elapsedRealtime()
        val plugged = isUsbPlugged()
        if (plugged) unplugSince = 0 else if (unplugSince == 0L) unplugSince = now
        return TransportPolicy.pick(
            MicState.transport, MicState.active, plugged,
            if (unplugSince == 0L) 0 else now - unplugSince,
            wifiReady(), bluetoothReady(),
        )
    }

    private fun wifiReady() = MicState.wifiHost.isNotBlank() && MicState.pairCode.trim().length == 6

    private fun bluetoothReady(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        return getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }

    private fun closeBtServer() {
        try { btServer?.close() } catch (_: IOException) {}
        btServer = null
    }

    private fun connectTcp(host: String, withHello: Boolean): Link? {
        val code = MicState.pairCode.trim()
        if (withHello && (host.isEmpty() || code.length != 6)) {
            MicState.status = "Enter the laptop address and code"
            sleepQuietly(1000)
            return null
        }
        MicState.status = if (withHello) "Connecting over Wi-Fi…" else "Waiting for the laptop app…"
        val s = Socket()
        return try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, PORT), 2000)
            if (withHello) s.getOutputStream().apply {
                write("PMHI".toByteArray() + code.toByteArray())
                flush()
            }
            Link(s.getOutputStream(), s.getInputStream(),
                if (withHello) Transport.WIFI else Transport.USB) { closeQuietly(s) }
        } catch (e: IOException) {
            closeQuietly(s)
            sleepQuietly(1000)
            null
        }
    }

    /** Bluetooth: the phone listens (SDP record) and the laptop connects to it. */
    @SuppressLint("MissingPermission") // checked below
    private fun acceptBluetooth(): Link? {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            MicState.status = "Bluetooth permission needed"
            sleepQuietly(1000)
            return null
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            MicState.status = "Turn Bluetooth on"
            sleepQuietly(1000)
            return null
        }
        MicState.status = "Waiting for the laptop (Bluetooth)…"
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val server = btServer ?: adapter
                .listenUsingRfcommWithServiceRecord("Droidboard", BT_UUID)
                .also { btServer = it }
            val sock = server.accept(1500)    // times out so the loop can check `running`
            Link(sock.outputStream, sock.inputStream, Transport.BLUETOOTH) {
                try { sock.close() } catch (_: IOException) {}
            }
        } catch (e: IOException) {
            // timeout (nobody connected yet) or the server socket died: recreate next time
            val timedOut = SystemClock.elapsedRealtime() - t0 >= 1300
            if (MicState.running && btServer != null && !timedOut) {
                try { btServer?.close() } catch (_: IOException) {}
                btServer = null
                sleepQuietly(500)
            }
            null
        }
    }

    /** A recorder with the user's mic choice applied, plus the noise suppressor attached to it. */
    private fun openRecorder(stereo: Boolean): AudioRecord? {
        val r = createRecorder(stereo) ?: return null
        MicCatalog.applyChoice(this, r, MicState.micChoice)
        suppressor = if (NoiseSuppressor.isAvailable()) {
            try { NoiseSuppressor.create(r.audioSessionId) } catch (_: Exception) { null }
        } else null
        suppressorOn = false
        return r
    }

    private fun releaseRecorder(r: AudioRecord) {
        try { r.stop() } catch (_: IllegalStateException) {}
        suppressor?.release()
        suppressor = null
        r.release()
    }

    @SuppressLint("MissingPermission") // checked in MainActivity before starting
    private fun createRecorder(stereo: Boolean): AudioRecord? {
        if (stereo) createRecorder(CHANNEL_STEREO)?.let { return it }   // no stereo here: fall back to mono
        return createRecorder(CHANNEL_MONO)
    }

    /** Stereo only when the user asked for it and the link can carry it (Bluetooth cannot). */
    private fun wantsStereo(decim: Int) = ChannelMode.isStereo(MicState.channelMode) && decim == 1

    /** Publishes what is really being sent, and why it differs from what the user asked for. */
    private fun announceChannels(record: AudioRecord, decim: Int): Int {
        val ch = record.channelCount.coerceIn(1, 2)
        MicState.channelsInUse = ch
        MicState.stereoNote = when {
            !ChannelMode.isStereo(MicState.channelMode) || ch == 2 -> ""
            decim != 1 -> "Bluetooth carries one channel: sending mono"
            else -> "This phone gave only one channel: sending mono"
        }
        return ch
    }

    @SuppressLint("MissingPermission") // checked in MainActivity before starting
    private fun createRecorder(channelMask: Int): AudioRecord? {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val unprocessed =
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        // Stereo: CAMCORDER is the source phones record their two mics with (video); the voice sources give one
        val stereo = channelMask == CHANNEL_STEREO
        val source = when {
            stereo -> MediaRecorder.AudioSource.CAMCORDER
            unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        MicState.source = when {
            stereo -> "CAMCORDER"
            unprocessed -> "UNPROCESSED"
            else -> "VOICE_RECOGNITION"
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        val perFrame = if (channelMask == CHANNEL_STEREO) 2 else 1
        val size = max(minBuf, FRAME_SAMPLES * 2 * 4 * perFrame)
        return try {
            val r = AudioRecord(
                source, SAMPLE_RATE, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, size
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) {
            null
        }
    }

    private fun readFrame(record: AudioRecord, frame: ShortArray) {
        var off = 0
        while (off < frame.size) {
            val n = record.read(frame, off, frame.size - off)
            if (n < 0) throw IllegalStateException("AudioRecord.read = $n")
            off += n
        }
    }

    /**
     * Applies filter and gain with hard clipping, or silence when muted. [second] is the right
     * channel when streaming stereo. Returns the mute flag.
     */
    private fun applyGainAndMute(frame: ShortArray, second: ShortArray?): Boolean {
        val muted = MicState.silenced      // the user's mute, or a call (Settings > Mute during calls)
        if (muted) {
            frame.fill(0)
            second?.fill(0)
            MicState.level = 0f; MicState.levelL = 0f; MicState.levelR = 0f
            return true
        }
        val iso = MicState.isolation
        val wantNs = iso > 0
        if (wantNs != suppressorOn) {
            suppressor?.enabled = wantNs
            suppressorOn = wantNs
        }
        filter.process(frame, iso)
        second?.let { filterR.process(it, iso) }
        val g = MicState.gain
        clipHit = false
        val peakL = scaleAndPeak(frame, g)
        val peakR = if (second != null) scaleAndPeak(second, g) else peakL
        MicState.level = max(peakL, peakR) / 32768f
        MicState.levelL = peakL / 32768f
        MicState.levelR = peakR / 32768f
        if (clipHit) MicState.clipUntil = SystemClock.elapsedRealtime() + 600
        return false
    }

    /** Multiplies by the gain (hard clip) and returns the peak of the result. */
    private fun scaleAndPeak(frame: ShortArray, g: Float): Int {
        var peak = 0
        for (i in frame.indices) {
            var v = (frame[i] * g).toInt()
            if (v > 32767) { v = 32767; clipHit = true } else if (v < -32768) { v = -32768; clipHit = true }
            frame[i] = v.toShort()
            val a = abs(v)
            if (a > peak) peak = a
        }
        return peak
    }
    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (_: IOException) {}
    }
}
