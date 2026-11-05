package dev.phonemic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import dev.phonemic.deck.DeckIconSet

/** One icon of the shared set (shared/icons.json), drawn as strokes on its 24 x 24 grid. Unknown names draw nothing. */
@Composable
fun DeckIcon(name: String, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val paths = remember(name) {
        DeckIconSet.get(ctx).paths[name].orEmpty().map { PathParser().parsePathString(it).toPath() }
    }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            paths.forEach { drawPath(it, tint, style = stroke) }
        }
    }
}
