package dev.phonemic.audio

import android.content.Context

/** How the phone reaches the laptop. */
object Transport {
    const val USB = 0
    const val WIFI = 1
    const val BLUETOOTH = 2
    val NAMES = listOf("USB", "Wi-Fi", "Bluetooth")
}

/** State shared between the screen and the streaming service. */
object MicState {
    @Volatile var running = false
    @Volatile var muted = false
    @Volatile var gain = 1.0f          // 1.0 = 100 %
    @Volatile var isolation = 2        // 0 Off, 1 Light, 2 Medium, 3 Strong, 4 Max
    @Volatile var status = "Stopped"
    @Volatile var level = 0f           // peak of the last frame, 0..1
    @Volatile var source = ""          // which Android audio source is used
    @Volatile var framesSent = 0L
    @Volatile var session = 0          // bumped on each start so an old worker thread exits
    @Volatile var transport = Transport.USB   // the way the user picked
    @Volatile var active = Transport.USB      // the way really in use (differs during a USB fallback)
    @Volatile var clipUntil = 0L              // elapsedRealtime until which the "clipping" light is on
    @Volatile var wifiHost = ""        // laptop address for Wi-Fi mode
    @Volatile var pairCode = ""        // 6-digit code shown by the laptop app
    @Volatile var micChoice = MicSlot.AUTO     // which phone mic the user picked
    @Volatile var micRevision = 0              // bumped when the pick changes: the service reopens the recorder
    @Volatile var activeMic = ""               // the mic Android says is really recording, e.g. "Bottom"
    @Volatile var micNote = ""                 // e.g. "the phone chose a different mic", empty when all is well
    @Volatile var channelMode = ChannelMode.MONO   // Mono / Stereo / Swap L/R (what the user picked)
    @Volatile var channelsInUse = 1            // what is really being sent (Bluetooth or a phone without stereo: 1)
    @Volatile var levelL = 0f                  // per-channel peaks while streaming stereo, 0..1
    @Volatile var levelR = 0f
    @Volatile var allowRemoteStart = true       // the laptop may start streaming (adb "autostart")
    @Volatile var compressWifi = false          // Wi-Fi: send Opus instead of PCM
    @Volatile var codecNote = ""                // "Opus", "PCM", or why Opus was not used
    @Volatile var stereoNote = ""              // e.g. both channels carry the same sound
    @Volatile var nowBar = NowBarMode.CLEAN     // Live Update / Now Bar: Clean or Off (see NowBarMode)
    @Volatile var wifiName = ""                 // name of the laptop found on Wi-Fi, e.g. "MSI"
    // Automatic (Settings), all off by default; see AutoRules
    @Volatile var autoStart = false             // start streaming when the laptop is found on Wi-Fi
    @Volatile var muteOnCall = false            // the laptop hears silence during a phone or VoIP call
    @Volatile var dimSeconds = 0                // dim the screen while streaming after this long untouched
    @Volatile var lowBatteryStop = false        // stop below AutoRules.LOW_BATTERY %, off the charger
    @Volatile var callMuted = false             // set by the service while a call is on (never saved)
    @Volatile var autoStartHeld = false         // the user pressed Stop: no auto start until the app comes back
    @Volatile var laptops: List<SavedLaptop> = emptyList()

    /** Silence goes out: the user muted, or a call is on. */
    val silenced get() = muted || callMuted

    private const val PREFS = "phonemic"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        transport = p.getInt("transport", Transport.USB).coerceIn(0, 2)
        wifiHost = p.getString("wifiHost", "") ?: ""
        pairCode = p.getString("pairCode", "") ?: ""
        gain = p.getFloat("gain", 1.0f).coerceIn(0f, 4f)
        isolation = p.getInt("isolation", 2).coerceIn(0, 4)
        micChoice = MicSlot.fromKey(p.getString("mic", MicSlot.AUTO.key))
        allowRemoteStart = p.getBoolean("allowRemoteStart", true)
        compressWifi = p.getBoolean("compressWifi", false)
        channelMode = p.getInt("channels", ChannelMode.MONO).coerceIn(0, ChannelMode.NAMES.size - 1)
        nowBar = NowBarModel.mode(p.getInt("nowBarMode", NowBarMode.CLEAN))
        autoStart = p.getBoolean("autoStart", false)
        muteOnCall = p.getBoolean("muteOnCall", false)
        dimSeconds = p.getInt("dimSeconds", 0).takeIf { it in AutoRules.DIM_SECONDS } ?: 0
        lowBatteryStop = p.getBoolean("lowBatteryStop", false)
        laptops = SavedLaptops.decode(p.getString("laptops", "") ?: "")
        wifiName = p.getString("wifiName", "") ?: ""
    }

    /**
     * The laptop answered on Wi-Fi: keep its address and name, and its saved code if this phone has
     * streamed to it before. Saves only when something changed.
     */
    fun laptopFound(ctx: Context, host: String, name: String) {
        val code = SavedLaptops.codeFor(laptops, name)
        val changed = host != wifiHost || name != wifiName || (code != null && code != pairCode)
        if (!changed || host.isEmpty()) return
        wifiHost = host
        wifiName = name
        if (code != null) pairCode = code
        save(ctx)
    }

    /** Streaming over Wi-Fi works: remember this laptop and its code. */
    fun rememberLaptop(ctx: Context) {
        val updated = SavedLaptops.remember(laptops, wifiName, pairCode, System.currentTimeMillis())
        if (updated == laptops) return
        laptops = updated
        save(ctx)
    }

    /** Everything back to how a new install starts (the screen reloads afterwards). */
    fun reset(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        muted = false
        load(ctx)
        wifiName = ""
    }

    /**
     * Developer options > "Live notifications for all apps" (Settings.Secure enable_notification_nowbar_test).
     * Null when it cannot be read: not Samsung, or Android refuses the hidden key.
     */
    fun nowBarDevOption(ctx: Context): Boolean? = try {
        android.provider.Settings.Secure.getInt(ctx.contentResolver, "enable_notification_nowbar_test") == 1
    } catch (_: Exception) {
        null
    }


    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("transport", transport)
            .putString("wifiHost", wifiHost)
            .putString("pairCode", pairCode)
            .putFloat("gain", gain)
            .putInt("isolation", isolation)
            .putString("mic", micChoice.key)
            .putInt("channels", channelMode)
            .putBoolean("allowRemoteStart", allowRemoteStart)
            .putBoolean("compressWifi", compressWifi)
            .putInt("nowBarMode", nowBar)
            .putBoolean("autoStart", autoStart)
            .putBoolean("muteOnCall", muteOnCall)
            .putInt("dimSeconds", dimSeconds)
            .putBoolean("lowBatteryStop", lowBatteryStop)
            .putString("laptops", SavedLaptops.encode(laptops))
            .putString("wifiName", wifiName)
            .apply()
    }
}
