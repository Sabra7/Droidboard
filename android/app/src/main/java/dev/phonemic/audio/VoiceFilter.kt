package dev.phonemic.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Software part of the isolation levels: a 2-stage high-pass filter plus a soft
 * noise gate (with hold, so word endings are not chopped). Runs per 10 ms frame,
 * before the gain, so the gate thresholds do not depend on the gain setting.
 *
 * Levels (MicState.isolation): 0 Off, 1 Light, 2 Medium, 3 Strong, 4 Max.
 */
class VoiceFilter(private val rate: Int) {

    companion object {
        val NAMES = listOf("Off", "Light", "Medium", "Strong", "Max")
        private val CUTOFF_HZ = floatArrayOf(0f, 80f, 120f, 150f, 200f)
        private val GATE_DB = floatArrayOf(0f, 0f, -50f, -44f, -38f)  // 0 = gate disabled
        private const val CLOSED_GAIN = 0.12f   // about -18 dB, not full silence
        private const val HOLD_FRAMES = 15      // 150 ms
    }

    private var x1a = 0f; private var y1a = 0f
    private var x1b = 0f; private var y1b = 0f
    private var gateGain = 1f
    private var hold = 0

    private fun reset() {
        x1a = 0f; y1a = 0f; x1b = 0f; y1b = 0f
        gateGain = 1f; hold = 0
    }

    fun process(frame: ShortArray, level: Int) {
        if (level <= 0) { reset(); return }
        val lv = level.coerceAtMost(CUTOFF_HZ.size - 1)

        val rc = 1f / (2f * PI.toFloat() * CUTOFF_HZ[lv])
        val a = rc / (rc + 1f / rate)
        var sumSq = 0.0
        for (i in frame.indices) {
            val x = frame[i].toFloat()
            val ya = a * (y1a + x - x1a); x1a = x; y1a = ya
            val yb = a * (y1b + ya - x1b); x1b = ya; y1b = yb
            frame[i] = yb.toInt().coerceIn(-32768, 32767).toShort()
            sumSq += yb.toDouble() * yb
        }

        val gate = GATE_DB[lv]
        if (gate == 0f) return
        val rms = sqrt(sumSq / frame.size) / 32768.0
        val db = 20.0 * log10(max(rms, 1e-9))
        if (db > gate) hold = HOLD_FRAMES else if (hold > 0) hold--
        val target = if (hold > 0) 1f else CLOSED_GAIN
        val prev = gateGain
        gateGain = if (target > prev) target else max(target, prev * 0.85f)

        val step = (gateGain - prev) / frame.size   // ramp across the frame: no clicks
        var g = prev
        for (i in frame.indices) {
            g += step
            frame[i] = (frame[i] * g).toInt().toShort()
        }
    }
}
