package dev.phonemic.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phonemic.audio.Transport
import kotlin.math.abs

private val LaneColors = listOf(Color(0xFF39D353), Color(0xFF58A6FF), Color(0xFFBC8CFF))
private val LaneHints = listOf("cable", "same network", "paired")

fun laneColor(transport: Int) = LaneColors[transport.coerceIn(0, 2)]

/**
 * Git-network-style graph: the phone is the root commit, the laptop is where the
 * branches merge. Each branch is one way to connect. Tap a branch to switch to it;
 * the active one glows, and packets travel along it while the stream is live.
 */
@Composable
fun ConnectionGraph(
    selected: Int,
    live: Boolean,
    reduceMotion: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    active: Int = selected,                 // the branch really carrying audio (differs on a USB fallback)
    hints: List<String> = LaneHints,
    laptopName: String? = null,
    deviceName: String = "Phone",           // "Tablet" on a tablet
) {
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val text = MaterialTheme.colorScheme.onSurface
    val dim = text.copy(alpha = 0.28f)        // inactive lines
    val dimText = text.copy(alpha = 0.68f)    // inactive labels, >= 4.5:1
    val dotFill = MaterialTheme.colorScheme.background

    val amounts = List(3) { i ->
        animateFloatAsState(
            if (i == selected) 1f else 0f,
            tween(if (reduceMotion) 0 else 350), label = "lane$i",
        ).value
    }
    val flow by rememberInfiniteTransition(label = "flow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "flowT",
    )

    Canvas(
        modifier
            .fillMaxWidth()
            .height(256.dp)
            .pointerInput(selected) {
                detectTapGestures { pos ->
                    val ys = laneYs(size.height.toFloat())
                    val best = ys.indices.minBy { abs(ys[it] - pos.y) }
                    if (best != selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(best)
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val ys = laneYs(h)
        val phone = Offset(w * 0.11f, h / 2)
        val laptop = Offset(w * 0.89f, h / 2)

        // inactive branches first, the active one on top
        val order = (0..2).sortedBy { amounts[it] }
        for (i in order) {
            val a = amounts[i]
            val color = lerp(dim, LaneColors[i], a)
            val path = lanePath(phone, laptop, w, ys[i])
            drawPath(
                path, color,
                style = Stroke(
                    (2.5f + 2.5f * a).dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (a < 0.5f) PathEffect.dashPathEffect(floatArrayOf(14f, 12f)) else null,
                ),
            )
            // the tap target in the middle
            val mid = Offset(w / 2, ys[i])
            drawCircle(dotFill, (11 + 2 * a).dp.toPx(), mid)   // hides the line behind the dot
            drawCircle(color, (11 + 2 * a).dp.toPx(), mid, style = Stroke(3.dp.toPx()))
            if (a > 0.01f) drawCircle(color.copy(alpha = a), 6.dp.toPx() * a, mid)

            val label = measurer.measure(
                Transport.NAMES[i],
                TextStyle(color = lerp(dimText, text, a), fontSize = 15.sp,
                    fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium),
            )
            drawText(label, topLeft = Offset(mid.x - label.size.width / 2f, mid.y - 24.dp.toPx() - label.size.height))
            if (live && i == active) {      // "this one is carrying your voice"
                drawCircle(LaneColors[i], 4.dp.toPx(),
                    Offset(mid.x + label.size.width / 2f + 10.dp.toPx(),
                        mid.y - 24.dp.toPx() - label.size.height / 2f))
            }
            val hint = measurer.measure(
                hints[i], TextStyle(color = lerp(dimText, text.copy(0.8f), a), fontSize = 12.sp),
            )
            drawText(hint, topLeft = Offset(mid.x - hint.size.width / 2f, mid.y + 17.dp.toPx()))
        }

        // packets flowing phone -> laptop along the active branch
        if (live && !reduceMotion) {
            val pm = PathMeasure().apply { setPath(lanePath(phone, laptop, w, ys[active]), false) }
            for (k in 0 until 4) {
                val t = easeInOut((flow + k / 4f) % 1f)      // each packet speeds up and settles, never a flat glide
                val p = pm.getPosition(pm.length * t)
                drawCircle(Color.White.copy(alpha = 0.95f), 3.5.dp.toPx(), p)
                drawCircle(LaneColors[active].copy(alpha = 0.5f), 7.dp.toPx(), p)
            }
        }

        val laptopLabel = laptopName?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 9) it.take(8) + "…" else it } ?: "Laptop"
        drawEndpoint(phone, deviceName, text, dotFill, measurer)
        drawEndpoint(laptop, laptopLabel, text, dotFill, measurer)
    }
}

/** Lane heights: room above the top lane for its name, below the bottom one for its hint. */
private fun Density.laneYs(h: Float) = listOf(46.dp.toPx(), h * 0.5f, h - 34.dp.toPx())

private fun easeInOut(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2

private fun lanePath(phone: Offset, laptop: Offset, w: Float, laneY: Float): Path {
    val x1 = w * 0.32f
    val x2 = w * 0.68f
    return Path().apply {
        moveTo(phone.x, phone.y)
        cubicTo(w * 0.21f, phone.y, w * 0.21f, laneY, x1, laneY)
        lineTo(x2, laneY)
        cubicTo(w * 0.79f, laneY, w * 0.79f, laptop.y, laptop.x, laptop.y)
    }
}

private fun DrawScope.drawEndpoint(
    c: Offset, name: String, text: Color, fill: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val s = 40.dp.toPx()
    drawRoundRect(fill, Offset(c.x - s / 2, c.y - s / 2), Size(s, s), CornerRadius(12.dp.toPx()))   // opaque, hides lines behind
    drawRoundRect(text.copy(alpha = 0.12f), Offset(c.x - s / 2, c.y - s / 2), Size(s, s), CornerRadius(12.dp.toPx()))
    drawRoundRect(text.copy(alpha = 0.55f), Offset(c.x - s / 2, c.y - s / 2), Size(s, s), CornerRadius(12.dp.toPx()),
        style = Stroke(2.dp.toPx()))
    drawCircle(text, 6.dp.toPx(), c)
    val label = measurer.measure(name, TextStyle(color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
    drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y + s / 2 + 6.dp.toPx()))
}
