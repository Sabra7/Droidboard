package dev.phonemic.audio

/** The "Channels" setting of the Mic sheet. */
object ChannelMode {
    const val MONO = 0      // one channel, as before
    const val STEREO = 1    // two mics: one left, one right
    const val SWAP = 2      // stereo with left and right exchanged
    val NAMES = listOf("Mono", "Stereo", "Swap L/R")
    fun isStereo(mode: Int) = mode == STEREO || mode == SWAP
}

/** Pure helpers for two-channel audio (kept free of Android so they can be unit tested). */
object StereoMath {
    /** Splits interleaved L R L R ... into two arrays; [swap] exchanges the sides. */
    fun split(src: ShortArray, left: ShortArray, right: ShortArray, swap: Boolean) {
        val a = if (swap) right else left
        val b = if (swap) left else right
        for (i in a.indices) {
            a[i] = src[2 * i]
            b[i] = src[2 * i + 1]
        }
    }

    /** Joins two arrays back into interleaved L R L R ... */
    fun join(left: ShortArray, right: ShortArray, dst: ShortArray) {
        for (i in left.indices) {
            dst[2 * i] = left[i]
            dst[2 * i + 1] = right[i]
        }
    }
}

/**
 * Notices "stereo" that is really one mic copied to both sides: the difference between the
 * channels is tiny next to their sum. Looks at about one second of 10 ms frames at a time.
 */
class StereoCheck {
    companion object {
        const val WINDOW_FRAMES = 100
        const val MIN_RMS = 100.0          // quieter than this proves nothing
        const val MAX_SIDE_TO_MID = 0.01   // difference below -20 dB of the sum = the same signal
    }

    var fake = false
        private set
    private var frames = 0
    private var side = 0.0
    private var mid = 0.0
    private var count = 0L

    fun reset() {
        fake = false
        frames = 0; side = 0.0; mid = 0.0; count = 0
    }

    /** Adds one frame. Returns true/false when a verdict for the last second is ready, else null. */
    fun feed(left: ShortArray, right: ShortArray): Boolean? {
        for (i in left.indices) {
            val d = (left[i] - right[i]).toDouble()
            val s = (left[i] + right[i]).toDouble()
            side += d * d
            mid += s * s
        }
        count += left.size
        if (++frames < WINDOW_FRAMES) return null
        val rms = Math.sqrt(mid / maxOf(count, 1L) / 4.0)      // rms of (L+R)/2
        var verdict: Boolean? = null
        if (rms >= MIN_RMS) {
            verdict = side <= MAX_SIDE_TO_MID * mid
            fake = verdict
        }
        frames = 0; side = 0.0; mid = 0.0; count = 0
        return verdict
    }
}
