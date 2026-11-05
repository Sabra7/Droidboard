package dev.phonemic.deck

import dev.phonemic.audio.MicState
import dev.phonemic.camera.LaptopAddress
import dev.phonemic.connection.DeviceProfiles
import dev.phonemic.connection.sleepQuietly
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** What the deck screen shows. Read by the UI every few hundred ms, like [MicState]. */
object DeckState {
    @Volatile var deck: Deck? = null
    @Volatile var status = "Not connected"
    @Volatile var online = false
    @Volatile var revision = 0                 // bumped when the laptop sends a new layout
    @Volatile var result = ""                  // last press that failed, empty when fine
    @Volatile var resultAt = 0L
}

/**
 * Talks to the deck server on the laptop (windows/deck/server.py, port 50508): fetches the layout,
 * sends presses and edits. Reconnects by itself while the deck screen is visible.
 * Uses the Wi-Fi address and pairing code from the Connection sheet.
 */
object DeckClient {
    const val PORT = 50508
    private const val PING_MS = 10_000L
    private const val READ_TIMEOUT_MS = 25_000

    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "deck-send").apply { isDaemon = true } }
    @Volatile private var generation = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var refused = ""          // host+code the laptop refused: don't hammer it until they change

    fun start() {
        val mine = ++generation
        Thread({ loop(mine) }, "deck-client").apply { isDaemon = true }.start()
    }

    fun stop() {
        generation++
        runCatching { socket?.close() }
        DeckState.online = false
    }

    fun press(id: String) = send(JSONObject().put("t", "press").put("id", id))

    fun save(deck: Deck) {
        DeckState.deck = deck                 // show the edit at once; the laptop confirms with the saved layout
        send(JSONObject().put("t", "save").put("deck", DeckJson.toJson(deck)))
    }

    private fun send(msg: JSONObject) {
        val s = socket ?: return
        sender.execute {
            runCatching { synchronized(s) { s.getOutputStream().apply { write((msg.toString() + "\n").toByteArray()); flush() } } }
        }
    }

    private fun loop(mine: Int) {
        while (generation == mine) {
            val host = LaptopAddress.host()
            val code = MicState.pairCode
            if (!LaptopAddress.ready()) {
                DeckState.status = "Enter the laptop address and pairing code in Connection > Wi-Fi"
                sleepQuietly(1000); continue
            }
            if (refused == host + code) { sleepQuietly(1000); continue }
            DeckState.status = "Connecting to $host…"
            try {
                connectOnce(mine, host, code)
            } catch (e: Exception) {
                if (generation == mine) DeckState.status = "Can't reach the laptop"
            }
            DeckState.online = false
            socket = null
            sleepQuietly(2000)
        }
    }

    private fun connectOnce(mine: Int, host: String, code: String) {
        val s = Socket()
        s.connect(InetSocketAddress(host, PORT), 3000)
        s.soTimeout = READ_TIMEOUT_MS
        s.tcpNoDelay = true
        socket = s
        s.use {
            send(JSONObject().put("t", "hello").put("code", code).put("device", DeviceProfiles.hello()))
            val pinger = Thread({
                while (generation == mine && !s.isClosed) {
                    sleepQuietly(PING_MS)
                    if (generation == mine && !s.isClosed) send(JSONObject().put("t", "get"))
                }
            }, "deck-ping").apply { isDaemon = true; start() }
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (generation == mine) {
                    val line = reader.readLine() ?: break
                    handle(JSONObject(line))
                }
            } finally {
                pinger.interrupt()
            }
        }
        if (generation == mine && DeckState.status.startsWith("Connected")) DeckState.status = "Disconnected"
    }

    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "deck" -> {
                DeckState.deck = DeckJson.parse(m.getJSONObject("deck"))
                DeckState.online = true
                DeckState.status = "Connected"
                DeckState.revision++
            }
            "result" -> if (!m.optBoolean("ok")) {
                DeckState.result = m.optString("msg").ifBlank { "Failed" }
                DeckState.resultAt = android.os.SystemClock.elapsedRealtime()
            }
            "error" -> {
                if (m.optBoolean("fatal")) refused = LaptopAddress.host() + MicState.pairCode
                DeckState.status = m.optString("msg", "Error")
                DeckState.resultAt = android.os.SystemClock.elapsedRealtime()
                DeckState.result = DeckState.status
            }
        }
    }

}
