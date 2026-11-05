package dev.phonemic.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_DEG = 135f   // bottom-left (0 deg = 3 o'clock, clockwise)
private const val SWEEP_DEG = 270f

/** Touch rules of the knob, without UI. */
internal object KnobMath {
    /** A touch [dist] from the centre grabs the knob when it is within [band] of the ring's [radius]. */
    fun onRing(dist: Float, radius: Float, band: Float): Boolean = kotlin.math.abs(dist - radius) <= band

    /** The shortest turn from angle [from] to [to] (degrees), so crossing 180 / -180 is not a jump. */
    fun angleDelta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** The value after turning [deltaDeg] from [raw]: it moves from where it was, never jumps. */
    fun turn(raw: Float, deltaDeg: Float): Float = (raw + deltaDeg / SWEEP_DEG).coerceIn(0f, 1f)
}

/**
 * Rotary knob like a car-stereo dial. Drag along its ring to turn it from its current value, or tap
 * to set the value at that spot. A drag that starts in the middle of the knob is left alone, so a
 * swipe across the knob changes tab instead. [fraction] is 0..1. With [steps] >= 2 the knob snaps
 * to that many detents and ticks on every change.
 */
@Composable
fun Knob(
    fraction: Float,
    onFraction: (Float) -> Unit,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    size: Dp = 132.dp,
    mark: Float? = null,                // draws a reference tick (e.g. 100 % on the gain knob)
    onReset: (() -> Unit)? = null,      // double-tap action (e.g. back to 100 %)
    onFinished: (() -> Unit)? = null,   // the user let go (end of drag, tap, reset): good moment to save
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(onFraction)
    val reset by rememberUpdatedState(onReset)
    val finished by rememberUpdatedState(onFinished)
    val currentFraction by rememberUpdatedState(fraction)
    val c = MaterialTheme.colorScheme
    val track = c.onSurface.copy(alpha = 0.14f)
    val accent = c.primary
    val face = c.onSurface.copy(alpha = 0.08f)

    fun snap(f: Float): Float =
        if (steps >= 2) (f * (steps - 1)).roundToInt() / (steps - 1f) else f

    fun update(pos: Offset, boxSize: androidx.compose.ui.unit.IntSize) {
        val dx = pos.x - boxSize.width / 2f
        val dy = pos.y - boxSize.height / 2f
        val deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val rel = ((deg - START_DEG) % 360f + 360f) % 360f
        val raw = when {
            rel <= SWEEP_DEG -> rel / SWEEP_DEG
            rel < SWEEP_DEG + (360f - SWEEP_DEG) / 2f -> 1f   // dead zone: nearest end
            else -> 0f
        }
        val next = snap(raw)
        if (next != currentFraction) {
            if (steps >= 2) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            current(next)
        }
    }

    Canvas(
        modifier
            .size(size)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, maxOf(steps - 2, 0))
                setProgress { v -> current(snap(v.coerceIn(0f, 1f))); finished?.invoke(); true }
            }
            .pointerInput(steps, onReset != null) {
                // Taps and turns in one handler that never takes the first touch: a drag on the ring turns
                // the knob, any other drag is left for the tab swipe, and a tap (anywhere) sets the value there.
                val stroke = 10.dp.toPx()
                val radius = this.size.width / 2f - (stroke / 2 + 6.dp.toPx())
                val band = 22.dp.toPx()
                fun angle(p: Offset) = Math.toDegrees(
                    atan2(p.y - this.size.height / 2f, p.x - this.size.width / 2f).toDouble()).toFloat()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val ring = KnobMath.onRing((down.position - c).getDistance(), radius, band)
                    var raw = currentFraction
                    var last = angle(down.position)
                    var turned = false
                    var swiped = false
                    var travel = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        travel += (change.position - change.previousPosition).getDistance()
                        if (!turned && travel < viewConfiguration.touchSlop) continue   // a tap, so far
                        if (!ring || change.isConsumed) { swiped = true; continue }      // the tabs' swipe
                        turned = true
                        change.consume()
                        val now = angle(change.position)
                        raw = KnobMath.turn(raw, KnobMath.angleDelta(last, now))
                        last = now
                        val next = snap(raw)
                        if (next != currentFraction) {
                            if (steps >= 2) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            current(next)
                        }
                    }
                    if (turned) { finished?.invoke(); return@awaitEachGesture }
                    if (swiped) return@awaitEachGesture
                    // a tap. With a reset action a second tap may follow (double-tap), so wait a moment first
                    if (reset != null) {
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (second != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            reset?.invoke()
                            finished?.invoke()
                            waitForUpOrCancellation()
                            return@awaitEachGesture
                        }
                    }
                    update(down.position, this.size)
                    finished?.invoke()
                }
            },
    ) {
        val stroke = 10.dp.toPx()
        val pad = stroke / 2 + 6.dp.toPx()
        val arcSize = Size(this.size.width - pad * 2, this.size.height - pad * 2)
        val topLeft = Offset(pad, pad)
        val center = Offset(this.size.width / 2, this.size.height / 2)

        drawArc(track, START_DEG, SWEEP_DEG, false, topLeft, arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
        if (fraction > 0f) {
            drawArc(accent, START_DEG, SWEEP_DEG * fraction, false, topLeft, arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // face of the knob
        val faceR = arcSize.width / 2 - stroke - 6.dp.toPx()
        drawCircle(face, faceR, center)

        // reference tick across the track
        if (mark != null) {
            val m = Math.toRadians((START_DEG + SWEEP_DEG * mark).toDouble())
            val r0 = arcSize.width / 2 - stroke / 2 - 2.dp.toPx()
            val r1 = arcSize.width / 2 + stroke / 2 + 2.dp.toPx()
            drawLine(
                c.onSurface.copy(alpha = 0.75f),
                Offset(center.x + (r0 * cos(m)).toFloat(), center.y + (r0 * sin(m)).toFloat()),
                Offset(center.x + (r1 * cos(m)).toFloat(), center.y + (r1 * sin(m)).toFloat()),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }

        // detent ticks
        if (steps >= 2) {
            val r1 = arcSize.width / 2 + stroke / 2 + 1.dp.toPx()
            for (i in 0 until steps) {
                val a = Math.toRadians((START_DEG + SWEEP_DEG * i / (steps - 1f)).toDouble())
                val p = Offset(center.x + (r1 * cos(a)).toFloat(), center.y + (r1 * sin(a)).toFloat())
                drawCircle(if (i / (steps - 1f) <= fraction + 0.001f) accent else track, 1.8.dp.toPx(), p)
            }
        }
        // pointer notch
        val a = Math.toRadians((START_DEG + SWEEP_DEG * fraction).toDouble())
        val from = Offset(center.x + (faceR * 0.45f * cos(a)).toFloat(), center.y + (faceR * 0.45f * sin(a)).toFloat())
        val to = Offset(center.x + (faceR * 0.9f * cos(a)).toFloat(), center.y + (faceR * 0.9f * sin(a)).toFloat())
        drawLine(c.onSurface, from, to, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
    }
}
