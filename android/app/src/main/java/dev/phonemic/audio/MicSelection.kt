package dev.phonemic.audio

import kotlin.math.abs

/** Which microphone the user wants. [key] is what gets saved. */
enum class MicSlot(val key: String, val label: String) {
    AUTO("auto", "Auto"),
    BOTTOM("bottom", "Bottom"),
    TOP("top", "Top"),
    BACK("back", "Back"),
    EXTERNAL("external", "External");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** What Android says about one built-in mic (a plain copy of MicrophoneInfo, so it can be tested). */
data class BuiltInMic(
    val id: String,
    val description: String,
    val x: Float, val y: Float, val z: Float,           // position in metres, device frame
    val ox: Float, val oy: Float, val oz: Float,        // direction the mic faces
)

/** Pure helpers for the "Mic" sheet: where each mic is, and what we ask Android for. */
object MicSelection {
    // AudioRecord.MIC_DIRECTION_* (API 29)
    const val DIRECTION_UNSPECIFIED = 0
    const val DIRECTION_TOWARDS_USER = 1
    const val DIRECTION_AWAY_FROM_USER = 2

    private const val MIN_SPREAD_Y = 0.02f   // 2 cm: below this two mics are not "top" and "bottom"
    private const val MIN_SPREAD_Z = 0.003f   // 3 mm of depth is enough to tell the back from the front

    private fun known(v: Float) = !v.isNaN() && abs(v) < 1e30f
    private fun hasPosition(m: BuiltInMic): Boolean {
        if (!known(m.x) || !known(m.y) || !known(m.z)) return false
        return !(m.x == -1f && m.y == -1f && m.z == -1f)       // Android's "unknown" value
    }
    private fun hasOrientation(m: BuiltInMic) =
        known(m.ox) && known(m.oy) && known(m.oz) && (m.ox != 0f || m.oy != 0f || m.oz != 0f)

    /**
     * Gives each built-in mic a slot (Bottom / Top / Back) when that can be told. Mics that
     * cannot be placed are left out, so the sheet never claims something it does not know.
     */
    fun classify(mics: List<BuiltInMic>): Map<String, MicSlot> {
        val result = mutableMapOf<String, MicSlot>()
        // 1. the name, when the phone is kind enough to say it
        for (m in mics) {
            val d = m.description.lowercase()
            val slot = when {
                "back" in d || "rear" in d -> MicSlot.BACK
                "bottom" in d -> MicSlot.BOTTOM
                "top" in d -> MicSlot.TOP
                else -> null
            }
            if (slot != null) result[m.id] = slot
        }
        // 2. the geometry, for the rest
        val rest = mics.filter { it.id !in result && hasPosition(it) }
        val minZ = rest.minOfOrNull { it.z }
        val maxZ = rest.maxOfOrNull { it.z }
        val front = mutableListOf<BuiltInMic>()
        for (m in rest) {
            val back = if (hasOrientation(m)) m.oz < -0.5f
            else minZ != null && maxZ != null && maxZ - minZ >= MIN_SPREAD_Z && m.z == minZ
            if (back) result[m.id] = MicSlot.BACK else front += m
        }
        val low = front.minOfOrNull { it.y }
        val high = front.maxOfOrNull { it.y }
        if (low != null && high != null && high - low >= MIN_SPREAD_Y) {
            for (m in front) {
                if (m.y == low) result[m.id] = MicSlot.BOTTOM
                else if (m.y == high) result[m.id] = MicSlot.TOP
            }
        }
        return result
    }

    /** The AudioRecord direction hint for a built-in slot (UNSPECIFIED = leave it to the phone). */
    fun directionFor(slot: MicSlot): Int = when (slot) {
        MicSlot.BOTTOM, MicSlot.TOP -> DIRECTION_TOWARDS_USER
        MicSlot.BACK -> DIRECTION_AWAY_FROM_USER
        else -> DIRECTION_UNSPECIFIED
    }

    /**
     * Compares what the user asked for with what Android says is really recording.
     * Returns null when all is well, or the short note to show.
     */
    fun mismatchNote(choice: MicSlot, activeSlots: Set<MicSlot>, usingExternal: Boolean): String? = when {
        choice == MicSlot.AUTO -> null
        choice == MicSlot.EXTERNAL -> if (usingExternal) null else "the phone chose a different mic"
        activeSlots.isEmpty() -> null                 // nothing reported: do not guess
        choice in activeSlots -> null
        else -> "the phone chose a different mic"
    }
}
