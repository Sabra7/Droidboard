package dev.phonemic.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.phonemic.ui.LocalIsDark
import kotlin.math.log10
import kotlin.math.roundToInt

const val GRID_COLS = 28
/** Width to height of the grid with square dots (28 x 7 plus gaps): a card that is taller keeps it centred. */
const val GRID_RATIO = 4f
private const val GRID_ROWS = 7

private val GreensDark = listOf(Color(0xFF0E4429), Color(0xFF006D32), Color(0xFF26A641), Color(0xFF39D353))
private val GreensLight = listOf(Color(0xFF9BE9A8), Color(0xFF40C463), Color(0xFF30A14E), Color(0xFF216E39))
private val EmptyDark = Color(0xFF262D38)    // visible on the dark card even when nothing is playing
private val EmptyLight = Color(0xFFD9D4C8)

/** Peak (0..1 linear) to 0..1 on a -50..0 dB scale, so quiet detail is visible. */
fun levelToFraction(level: Float): Float {
    if (level <= 0.0032f) return 0f
    return ((20f * log10(level) + 50f) / 50f).coerceIn(0f, 1f)
}

/**
 * GitHub-contribution-style dots. Each column is one moment (newest on the right);
 * the louder it was, the higher it stacks, and higher dots are brighter green.
 */
@Composable
fun ActivityGrid(history: FloatArray, modifier: Modifier = Modifier) {
    val dark = LocalIsDark.current
    val greens = if (dark) GreensDark else GreensLight
    val empty = if (dark) EmptyDark else EmptyLight
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val gap = 3.dp.toPx()
        // Dots stay square: on a wide card they stop growing (so rows do not overlap into bars)
        // and the extra width becomes space between the columns.
        val cell = minOf((size.width - gap * (GRID_COLS - 1)) / GRID_COLS,
            (size.height - gap * (GRID_ROWS - 1)) / GRID_ROWS)
        val gapX = (size.width - cell * GRID_COLS) / (GRID_COLS - 1)
        val rowStep = (size.height - cell) / (GRID_ROWS - 1)
        val radius = CornerRadius(cell * 0.28f)
        val start = history.size - GRID_COLS
        for (col in 0 until GRID_COLS) {
            val v = history.getOrElse(start + col) { 0f }
            val lit = (v * GRID_ROWS).roundToInt()
            for (row in 0 until GRID_ROWS) {           // row 0 = bottom
                val color = if (row < lit) greens[(row * greens.size / GRID_ROWS).coerceAtMost(greens.lastIndex)] else empty
                drawRoundRect(
                    color,
                    Offset(col * (cell + gapX), size.height - cell - row * rowStep),
                    Size(cell, cell), radius,
                )
            }
        }
    }
}
