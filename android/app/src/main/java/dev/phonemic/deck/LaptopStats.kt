package dev.phonemic.deck

/**
 * Live readings of the laptop (CPU, RAM, GPU, VRAM, GPU temperature), pushed by Windows over the Deck
 * connection while somebody is watching (the Dim screen's Stats widget). A value is null when the laptop
 * cannot read it (no NVIDIA GPU, for example). Every value is a whole percent, except [temp] in °C.
 *
 * Contract between the Windows/DeckClient side and the Dim UI:
 *  - the UI calls [watch] (true when a Stats widget appears, false when it goes away); calls are counted,
 *  - the Deck connection sets [hook] to send {"t":"watch","on":...} and calls [update] for every {"t":"stats"} line,
 *  - the UI reads the fields and checks [isFresh] before showing numbers (otherwise it shows "—").
 */
object LaptopStats {
    const val STALE_MS = 6000L

    @Volatile var cpu: Int? = null
    @Volatile var ram: Int? = null
    @Volatile var gpu: Int? = null
    @Volatile var vram: Int? = null
    @Volatile var temp: Int? = null
    @Volatile var updatedAt = 0L          // SystemClock.elapsedRealtime() of the last update, 0 = never

    /** Set by the Deck connection: called with true when the first watcher appears, false when the last one leaves. */
    @Volatile var hook: ((Boolean) -> Unit)? = { on -> DeckClient.onWatch(on) }   // tests replace it

    private var watchers = 0

    @Synchronized
    fun watch(on: Boolean) {
        val before = watchers
        watchers = (watchers + if (on) 1 else -1).coerceAtLeast(0)
        if (before == 0 && watchers == 1) hook?.invoke(true)
        if (before == 1 && watchers == 0) { hook?.invoke(false); clear() }
    }

    @Synchronized
    fun watching() = watchers > 0

    fun update(now: Long, cpu: Int?, ram: Int?, gpu: Int?, vram: Int?, temp: Int?) {
        this.cpu = cpu; this.ram = ram; this.gpu = gpu; this.vram = vram; this.temp = temp
        updatedAt = now
    }

    fun isFresh(now: Long) = updatedAt != 0L && now - updatedAt <= STALE_MS

    fun clear() {
        cpu = null; ram = null; gpu = null; vram = null; temp = null; updatedAt = 0L
    }
}
