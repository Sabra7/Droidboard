package dev.phonemic.connection

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import dev.phonemic.audio.AutoRules
import dev.phonemic.audio.MicService
import dev.phonemic.audio.MicState
import dev.phonemic.audio.Transport
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException

/** A laptop that answered on the LAN (see windows/connection/discovery.py). [host] is an address that works. */
data class FoundLaptop(val host: String, val name: String)

/** A beacon or an answer from the laptop: its name and every address it has. */
data class Beacon(val name: String, val addresses: List<String>)

/** The pure part of finding the laptop: reading its packets and picking the address to use. */
object LaptopPick {
    const val QUESTION = "PMDQ"

    /** "PMDS|port|name|addr,addr" (older laptops send no addresses). */
    fun parse(text: String): Beacon? {
        if (!text.startsWith("PMDS|")) return null
        val parts = text.split("|", limit = 4)
        if (parts.size < 3 || parts[1].toIntOrNull() == null) return null
        val addresses = parts.getOrNull(3)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return Beacon(parts[2].trim(), addresses)
    }

    /**
     * Lower is better: IPv4 from the network, then IPv6, then link-local IPv6, then 169.254 (what Windows
     * makes up when its Wi-Fi got no address; it rarely works).
     */
    fun rank(host: String): Int = when {
        host.startsWith("169.254.") -> 3
        ':' !in host -> 0
        host.lowercase().startsWith("fe80") -> 2
        else -> 1
    }

    /** Of the addresses that answered, the one to use: the current one while it still answers, else the best. */
    fun choose(answered: Collection<String>, current: String): String? =
        if (current in answered) current else answered.minByOrNull(::rank)

    /** Every other address of a /24 around [ip] (asking each one finds the laptop where broadcasts are dropped). */
    fun sweep(ip: String): List<String> {
        val p = ip.split(".")
        if (p.size != 4 || ip.startsWith("169.254.")) return emptyList()
        val base = "${p[0]}.${p[1]}.${p[2]}."
        return (1..254).map { base + it }.filter { it != ip }
    }
}

/**
 * Finds the laptop by itself, so the user only types the pairing code:
 * - listens for its beacon (IPv4 broadcast and IPv6 multicast), which lists every address the laptop has;
 * - asks those addresses directly and keeps one that answers (an address that answers is one that works,
 *   which matters when the laptop's Wi-Fi got no IPv4 address and only IPv6 reaches it);
 * - with no beacon, asks every address of this network until the laptop answers.
 * Call stop() when it is no longer needed.
 */
class Discovery(ctx: Context, private val onFound: (FoundLaptop) -> Unit) {
    @Volatile private var listener: DatagramSocket? = null
    @Volatile private var asker: DatagramSocket? = null
    @Volatile private var stopped = false
    @Volatile private var lastBeacon: Beacon? = null
    @Volatile private var lastBeaconAt = 0L
    private val answered = mutableMapOf<String, String>()      // address -> laptop name, this round
    private val lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("droidboard-discovery").apply { setReferenceCounted(false) }

    fun start() {
        runCatching { lock.acquire() }      // some phones drop broadcasts without it
        thread("discovery-listen") { listen() }
        thread("discovery-ask") { ask() }
    }

    fun stop() {
        stopped = true
        listener?.close()
        asker?.close()
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread({ try { body() } catch (_: Exception) {} }, name).apply { isDaemon = true }.start()

    /** Beacons on port 50506: remember what the laptop says about itself. */
    private fun listen() {
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        listener = s
        val buf = ByteArray(1024)
        try {
            while (!stopped) {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val beacon = LaptopPick.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                val source = packet.address.hostAddress ?: continue
                lastBeacon = beacon.copy(addresses = (beacon.addresses + source).distinct())
                lastBeaconAt = System.currentTimeMillis()
            }
        } catch (_: SocketException) {
            // closed by stop()
        }
    }

    /** Rounds of questions: to the beacon's addresses, or to the whole network when no beacon comes. */
    private fun ask() {
        val s = DatagramSocket()
        asker = s
        s.soTimeout = 200
        val question = LaptopPick.QUESTION.toByteArray()
        var round = 0
        while (!stopped) {
            val beacon = lastBeacon?.takeIf { System.currentTimeMillis() - lastBeaconAt < 6000 }
            val targets = beacon?.addresses
                ?: if (round % 3 == 0) ownIpv4().flatMap(LaptopPick::sweep) + "ff02::1" else emptyList()
            synchronized(answered) { answered.clear() }
            for (t in targets) {
                runCatching { s.send(DatagramPacket(question, question.size, InetAddress.getByName(t), PORT)) }
            }
            val until = System.currentTimeMillis() + 1000
            while (!stopped && System.currentTimeMillis() < until) receiveAnswer(s)
            val pick = synchronized(answered) {
                LaptopPick.choose(answered.keys, MicState.wifiHost)?.let { FoundLaptop(it, answered[it].orEmpty()) }
            }
            if (pick != null) onFound(pick)
            round++
            sleepUnlessStopped(if (pick != null) 3000 else 1000)
        }
    }

    private fun receiveAnswer(s: DatagramSocket) {
        val buf = ByteArray(1024)
        val packet = DatagramPacket(buf, buf.size)
        try {
            s.receive(packet)
        } catch (_: SocketTimeoutException) {
            return
        }
        val answer = LaptopPick.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: return
        val source = packet.address.hostAddress ?: return
        synchronized(answered) { answered[source] = answer.name }
    }

    private fun sleepUnlessStopped(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (!stopped && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    /** This device's IPv4 addresses on the LAN (not mobile data, not loopback). */
    private fun ownIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    companion object { const val PORT = 50506 }
}

/**
 * Keeps [MicState.wifiHost] pointing at the laptop while the app is on screen or streaming (and starts
 * streaming when Settings > Start when the laptop is found is on): when the
 * Wi-Fi changes (home, hotspot, university) the laptop gets a new address and finding it again is automatic.
 * Over the cable the address is always localhost, so there is nothing to find.
 */
object LaptopFinder {
    private var users = 0
    private var discovery: Discovery? = null

    @Synchronized fun start(ctx: Context) {
        if (users++ > 0) return
        val app = ctx.applicationContext
        discovery = Discovery(app) { found ->
            if (MicState.transport == Transport.USB) return@Discovery
            MicState.laptopFound(app, found.host, found.name)
            // Settings > Start when the laptop is found. Only reached with the app on screen: a running
            // service is the other user of this finder, and Android 14 lets only a visible app start the mic.
            if (AutoRules.autoStart(MicState.autoStart, MicState.transport, MicState.running,
                    codeReady = MicState.pairCode.length == 6, heldByUser = MicState.autoStartHeld) &&
                app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                app.startForegroundService(Intent(app, MicService::class.java))
            }
        }.also { it.start() }
    }

    @Synchronized fun stop() {
        if (users > 0 && --users == 0) {
            discovery?.stop()
            discovery = null
        }
    }
}
