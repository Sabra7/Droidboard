package dev.phonemic.audio

/**
 * The Automatic settings, as plain rules (pure, so they can be tested). Every one is off by default:
 * a new install behaves like before until the user turns them on.
 */
object AutoRules {
    /** Dim the screen while streaming after this long without a touch (0 = never). */
    val DIM_SECONDS = listOf(0, 30, 60)
    val DIM_NAMES = listOf("Off", "30 s", "1 min")
    const val LOW_BATTERY = 15

    /** Start streaming on Wi-Fi as soon as the laptop is found (not right after the user pressed Stop). */
    fun autoStart(enabled: Boolean, transport: Int, running: Boolean, codeReady: Boolean, heldByUser: Boolean) =
        enabled && transport == Transport.WIFI && !running && codeReady && !heldByUser

    /** AudioManager modes of a phone or VoIP call (in call, in communication, screening, redirects). */
    fun inCall(audioMode: Int) = audioMode in 2..6

    /** Stop below [LOW_BATTERY] %, never while charging; an unknown level (-1) never stops. */
    fun stopForBattery(enabled: Boolean, percent: Int, charging: Boolean) =
        enabled && !charging && percent in 0 until LOW_BATTERY

    fun shouldDim(seconds: Int, running: Boolean, idleMs: Long) = seconds > 0 && running && idleMs >= seconds * 1000L
}

/** A laptop the phone has streamed to over Wi-Fi, with the code that worked. */
data class SavedLaptop(val name: String, val code: String, val lastSeen: Long)

/** The saved laptops, kept in one preference as "name<TAB>code<TAB>time" lines (pure). */
object SavedLaptops {
    private const val MAX = 8

    fun encode(list: List<SavedLaptop>): String =
        list.joinToString("\n") { "${clean(it.name)}\t${it.code}\t${it.lastSeen}" }

    fun decode(text: String): List<SavedLaptop> = text.lines().mapNotNull { line ->
        val fields = line.split("\t")
        if (fields.size != 3 || fields[0].isBlank() || fields[1].length != 6 || !fields[1].all(Char::isDigit)) null
        else SavedLaptop(fields[0], fields[1], fields[2].toLongOrNull() ?: 0L)
    }

    /** Remember (or refresh) a laptop; the newest first, at most [MAX]. */
    fun remember(list: List<SavedLaptop>, name: String, code: String, now: Long): List<SavedLaptop> {
        if (name.isBlank() || code.length != 6) return list
        return (listOf(SavedLaptop(clean(name), code, now)) + list.filter { it.name != clean(name) }).take(MAX)
    }

    fun forget(list: List<SavedLaptop>, name: String) = list.filter { it.name != name }

    fun codeFor(list: List<SavedLaptop>, name: String): String? = list.firstOrNull { it.name == clean(name) }?.code

    private fun clean(name: String) = name.replace('\t', ' ').replace('\n', ' ').trim()
}
