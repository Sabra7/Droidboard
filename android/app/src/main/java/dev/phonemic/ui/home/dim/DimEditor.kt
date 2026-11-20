package dev.phonemic.ui.home.dim

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.VectorConverter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.phonemic.audio.MicState
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.GlassBottomSheet
import dev.phonemic.ui.GlassPill
import dev.phonemic.ui.Hint
import dev.phonemic.ui.Ui
import dev.phonemic.ui.home.ImmersiveEffect
import dev.phonemic.ui.home.isLandscape
import dev.phonemic.ui.home.loadDimLayout
import kotlin.math.roundToInt

/** A finger on a widget: moving it, or pulling its corner. [dx] and [dy] are the finger's way so far, in pixels. */
private data class Drag(val id: String, val resize: Boolean, val start: DimLayout, val dx: Float = 0f, val dy: Float = 0f)

/**
 * Full-screen editor of the Dim layout (of the orientation the screen is in). The canvas is the real Dim canvas, a
 * little smaller so the bar fits above it. Drag a widget to move it, pull its corner to resize, tap it for its
 * options, Add widget for a new one. Everything is saved as you go; Done closes.
 */
@Composable
fun DimEditor(reduceMotion: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val landscape = isLandscape()
    val grid = DimGrid.of(landscape)
    var layout by remember(landscape) { mutableStateOf(loadDimLayout(landscape)) }
    var drag by remember(landscape) { mutableStateOf<Drag?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    val sheetHaze = remember { HazeState() }
    val sheetOpen = selected != null || adding

    fun commit(next: DimLayout) {
        layout = next
        val text = DimConfig.encode(next)
        if (landscape) MicState.dimLayoutLandscape = text else MicState.dimLayoutPortrait = text
        MicState.save(ctx)
    }

    ImmersiveEffect(keepOn = false, brightness = null)
    BackHandler(!sheetOpen, onDone)

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val compact = maxWidth < 420.dp
        val reserved = 88.dp
        val scale = ((maxHeight - reserved - 12.dp) / maxHeight).coerceIn(0.5f, 1f)
        // the canvas, exactly as Dim lays it out, scaled down and anchored to the bottom
        Box(
            Modifier.fillMaxSize().padding(bottom = 12.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0.5f, 1f) }
        ) {
            val ink = MaterialTheme.colorScheme.onSurface
            Box(Modifier.fillMaxSize().border(1.dp, ink.copy(0.16f), RoundedCornerShape(28.dp)))
            BoxWithConstraints(Modifier.fillMaxSize().displayCutoutPadding()) {
                val density = LocalDensity.current
                val cw = with(density) { (maxWidth / grid.cols).toPx() }
                val ch = with(density) { (maxHeight / grid.rows).toPx() }
                fun resolve(d: Drag): DimLayout {
                    val s = d.start.widgets.first { it.id == d.id }
                    val moved = if (d.resize) DimConfig.resize(d.start, grid, d.id, s.w + (d.dx / cw).roundToInt(), s.h + (d.dy / ch).roundToInt())
                    else DimConfig.move(d.start, grid, d.id, s.col + (d.dx / cw).roundToInt(), s.row + (d.dy / ch).roundToInt())
                    return moved ?: d.start
                }
                val shown = drag?.let { resolve(it) } ?: layout

                // grid hints
                val dot = ink.copy(0.12f)
                Canvas(Modifier.fillMaxSize()) {
                    for (c in 0..grid.cols) for (r in 0..grid.rows) drawCircle(dot, 1.5.dp.toPx(), Offset(c * cw, r * ch))
                }
                val inset = with(density) { 6.dp.toPx() }
                val env = DimEnv(live = true, muted = false, onMute = {}, preview = true)

                // where the dragged widget will land
                drag?.let { d ->
                    shown.widgets.firstOrNull { it.id == d.id }?.let { t ->
                        Box(Modifier.offset { IntOffset((t.col * cw + inset).roundToInt(), (t.row * ch + inset).roundToInt()) }
                            .size(with(density) { (t.w * cw - 2 * inset).toDp() }, with(density) { (t.h * ch - 2 * inset).toDp() })
                            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(0.7f), RoundedCornerShape(20.dp)))
                    }
                }

                shown.widgets.forEach { w ->
                    androidx.compose.runtime.key(w.id) {
                        val d = drag?.takeIf { it.id == w.id }
                        val start = d?.start?.widgets?.firstOrNull { it.id == w.id }
                        // while a finger is on it the widget follows the finger freely; the rest spring to their cells
                        val rawOffset = if (d != null && !d.resize && start != null) Offset(
                            (start.col * cw + d.dx).coerceIn(0f, (grid.cols - start.w) * cw),
                            (start.row * ch + d.dy).coerceIn(0f, (grid.rows - start.h) * ch)) else null
                        val rawSize = if (d != null && d.resize && start != null) Size(
                            (start.w * cw + d.dx).coerceIn(w.type.minW * cw, (grid.cols - start.col) * cw),
                            (start.h * ch + d.dy).coerceIn(w.type.minH * ch, (grid.rows - start.row) * ch)) else null
                        EditableWidget(
                            w = w, env = env, cw = cw, ch = ch, inset = inset, reduceMotion = reduceMotion,
                            selected = selected == w.id, rawOffset = rawOffset, rawSize = rawSize,
                            onTap = { selected = w.id },
                            onDragStart = { resize -> drag = Drag(w.id, resize, layout) },
                            onDrag = { delta -> drag = drag?.let { it.copy(dx = it.dx + delta.x, dy = it.dy + delta.y) } },
                            onDragEnd = { drag?.let { commit(resolve(it)) }; drag = null },
                        )
                    }
                }
            }
        }

        // the bar
        Row(
            Modifier.fillMaxWidth().displayCutoutPadding().padding(horizontal = Ui.Pad).padding(top = 8.dp).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Editing ${if (landscape) "landscape" else "portrait"} layout", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (!compact) Text("Drag to move, pull a corner to resize, tap for options.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(0.75f), maxLines = 1)
            }
            Spacer(Modifier.size(12.dp))
            GlassPill(onClick = { full = false; adding = true }, onClickLabel = "Add a widget") {
                DeckIcon("plus", MaterialTheme.colorScheme.onSurface, 18.dp)
                Spacer(Modifier.size(6.dp))
                Text(if (compact) "Add" else "Add widget", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
            Spacer(Modifier.size(8.dp))
            GlassPill(onClick = onDone, onClickLabel = "Finish editing") {
                Text("Done", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        // options of the tapped widget
        val current = layout.widgets.firstOrNull { it.id == selected }
        GlassBottomSheet(current != null, sheetHaze, current?.type?.label ?: "", onDismiss = { selected = null }) {
            current?.let { w ->
                DimWidgetOptions(
                    widget = w, landscape = landscape,
                    onChange = { commit(DimConfig.update(layout, it)) },
                    onResize = { cols, rows -> DimConfig.resize(layout, grid, w.id, cols, rows)?.let { commit(it) } },
                    onRemove = { commit(DimConfig.remove(layout, w.id)); selected = null },
                )
            }
        }

        GlassBottomSheet(adding, sheetHaze, "Add widget", onDismiss = { adding = false }) {
            DimWidgetType.entries.forEach { type ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) {
                            val next = DimConfig.add(layout, grid, type, landscape)
                            if (next == null) full = true else { commit(next); adding = false }
                        }
                        .heightIn(min = 56.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeckIcon(type.icon, MaterialTheme.colorScheme.onSurface, 24.dp)
                    Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(type.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(when (type) {
                            DimWidgetType.MUTE -> "Mutes the mic with one tap"
                            DimWidgetType.CLOCK -> "The time, and the date"
                            DimWidgetType.STATS -> "CPU, RAM and GPU of the laptop"
                            DimWidgetType.CAMERA -> "This device's own camera"
                        }, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.75f))
                    }
                }
            }
            if (full) Hint("No room left. Move or shrink something first.")
        }
    }
}

/** One widget in the editor: a framed picture of it that moves, resizes and springs into its cell. */
@Composable
private fun EditableWidget(
    w: DimWidget,
    env: DimEnv,
    cw: Float, ch: Float, inset: Float,
    reduceMotion: Boolean,
    selected: Boolean,
    rawOffset: Offset?,
    rawSize: Size?,
    onTap: () -> Unit,
    onDragStart: (resize: Boolean) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val tap by rememberUpdatedState(onTap)
    val dragStart by rememberUpdatedState(onDragStart)
    val dragMove by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val target = Offset(w.col * cw + inset, w.row * ch + inset)
    val targetSize = Size(w.w * cw - 2 * inset, w.h * ch - 2 * inset)
    val spec = spring<Offset>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    val specSize = spring<Size>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    val pos = remember { Animatable(target, Offset.VectorConverter) }
    val size = remember { Animatable(targetSize, Size.VectorConverter) }
    val appear = remember { Animatable(if (reduceMotion) 1f else 0.88f) }
    LaunchedEffect(target, rawOffset) {
        when {
            rawOffset != null -> pos.snapTo(rawOffset + Offset(inset, inset))
            reduceMotion -> pos.snapTo(target)
            else -> pos.animateTo(target, spec)
        }
    }
    LaunchedEffect(targetSize, rawSize) {
        when {
            rawSize != null -> size.snapTo(Size(rawSize.width - 2 * inset, rawSize.height - 2 * inset))
            reduceMotion -> size.snapTo(targetSize)
            else -> size.animateTo(targetSize, specSize)
        }
    }
    LaunchedEffect(Unit) { if (!reduceMotion) appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }

    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(20.dp)
    val wDp = with(density) { size.value.width.coerceAtLeast(1f).toDp() }
    val hDp = with(density) { size.value.height.coerceAtLeast(1f).toDp() }
    Box(
        Modifier
            .offset { IntOffset(pos.value.x.roundToInt(), pos.value.y.roundToInt()) }
            .size(wDp, hDp)
            .graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = appear.value.coerceIn(0f, 1f) }
            .clip(shape)
            .background(ink.copy(0.05f))
            .border(if (selected) 2.dp else 1.dp, if (selected) primary else ink.copy(0.22f), shape)
            .pointerInput(w.id) { detectTapGestures(onTap = { tap() }) }
            .pointerInput(w.id) {
                detectDragGestures(
                    onDragStart = { dragStart(false) },
                    onDrag = { change, delta -> change.consume(); dragMove(delta) },
                    onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() },
                )
            },
    ) {
        DimWidgetView(w, env, Modifier.fillMaxSize())
        // the corner you pull to resize: a 48 dp touch target around a small ring
        Box(
            Modifier.align(Alignment.BottomEnd).size(48.dp)
                .pointerInput(w.id) {
                    detectDragGestures(
                        onDragStart = { dragStart(true) },
                        onDrag = { change, delta -> change.consume(); dragMove(delta) },
                        onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() },
                    )
                },
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(Modifier.padding(6.dp).size(14.dp).background(Color.Black, CircleShape).border(2.dp, if (selected) primary else ink.copy(0.6f), CircleShape))
        }
    }
}
