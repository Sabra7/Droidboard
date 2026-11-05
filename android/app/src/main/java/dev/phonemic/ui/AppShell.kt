package dev.phonemic.ui

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The app's pages. A phone has all four; a tablet shows the mic and the deck together on [HOME]. */
enum class AppTab(val label: String, val icon: String) {
    HOME("Mic", "mic"),
    DECK("Deck", "layout-grid"),
    CAMERA("Camera", "camera"),
    SCREEN("Screen", "monitor");

    fun labelOn(wide: Boolean) = if (wide && this == HOME) "Home" else label
    fun iconOn(wide: Boolean) = if (wide && this == HOME) "house" else icon

    companion object {
        val PHONE = listOf(HOME, DECK, CAMERA, SCREEN)
        val TABLET = listOf(HOME, CAMERA, SCREEN)       // a new feature gets its tab here and in PHONE
    }
}

/** Room the floating tab bar takes at the bottom of a phone screen; content keeps clear of it. */
val TAB_BAR_SPACE = 84.dp
/** Room the rail takes at the left of a tablet screen. */
val RAIL_SPACE = 104.dp

/** Tab changes: 300 ms, fast start and a soft landing (ease-out), never linear. */
private const val TAB_MS = 300
private val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Where the tabs are: [current] is the page on screen, [target] the one being moved to, and
 * [progress] how far (0 = still at current, 1 = arrived). The bar's indicator and the pages both
 * read it, so a finger dragging the page drags the indicator too.
 */
@Stable
class TabMotion(start: AppTab) {
    var current by mutableStateOf(start)
        private set
    var target by mutableStateOf(start)
        private set
    val progress = Animatable(0f)

    /** The tab that reads as selected: whichever side of the halfway point we are on. */
    val shown get() = if (progress.value >= 0.5f) target else current
    val settled get() = target == current

    /** A tap. A far tab is reached directly; the page in between is not shown. */
    suspend fun go(to: AppTab, animate: Boolean) {
        if (to == target && settled) return
        if (!settled) current = target          // a tap during a move: start from where we were heading
        target = to
        progress.snapTo(0f)
        if (animate) progress.animateTo(1f, tween(TAB_MS, easing = EaseOut))
        land()
    }

    /** The finger moved the page: [fraction] of a page towards [to] (null = no page that way). */
    suspend fun drag(to: AppTab?, fraction: Float) {
        if (to == null) { target = current; progress.snapTo(0f); return }
        target = to
        progress.snapTo(fraction.coerceIn(0f, 1f))
    }

    /** The finger lifted: finish the move or fall back. */
    suspend fun release(commit: Boolean, animate: Boolean) {
        if (settled) return
        val end = if (commit) 1f else 0f
        if (animate) {
            val left = abs(end - progress.value)
            progress.animateTo(end, tween((TAB_MS * left).roundToInt().coerceAtLeast(80), easing = EaseOut))
        }
        if (commit) land() else { target = current; progress.snapTo(0f) }
    }

    suspend fun snap(to: AppTab) { current = to; target = to; progress.snapTo(0f) }

    private suspend fun land() { current = target; progress.snapTo(0f) }
}

@Composable
fun rememberTabMotion(): TabMotion {
    var saved by rememberSaveable { mutableStateOf(AppTab.HOME) }
    val motion = remember { TabMotion(saved) }
    LaunchedEffect(motion.current) { saved = motion.current }     // survives rotation
    val owner = LocalLifecycleOwner.current
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    DisposableEffect(owner) {                                    // the app left the screen: come back on Home
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) {
                saved = AppTab.HOME
                scope.launch { motion.snap(AppTab.HOME) }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return motion
}

/**
 * The pages. The new page slides in from the side of its tab (from the end on a phone, from below
 * on the tablet rail) while the old one slides out and fades a little. [swipe] lets a finger drag
 * between neighbours. [content] gets `settled = false` while its page is moving, so the camera can
 * wait until its page has landed.
 */
@Composable
fun TabPages(
    motion: TabMotion,
    tabs: List<AppTab>,
    vertical: Boolean,
    swipe: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (tab: AppTab, settled: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.clipToBounds()) {
        val span = with(LocalDensity.current) { (if (vertical) maxHeight else maxWidth).toPx() }
        var dragged by remember { mutableStateOf(0f) }
        val drag = if (!swipe) Modifier else Modifier.draggable(
            orientation = if (vertical) Orientation.Vertical else Orientation.Horizontal,
            state = rememberDraggableState { d ->
                dragged += d
                val i = tabs.indexOf(motion.current)
                val next = tabs.getOrNull(if (dragged < 0) i + 1 else i - 1)   // drag left: the page on the right
                scope.launch { motion.drag(next, abs(dragged) / span) }
            },
            onDragStarted = { dragged = 0f },
            onDragStopped = { velocity ->
                val fling = abs(velocity) > 1200f && (velocity < 0) == (dragged < 0)
                val commit = abs(dragged) > span * 0.35f || fling
                dragged = 0f
                scope.launch { motion.release(commit, animate) }
            },
        )
        val from = tabs.indexOf(motion.current)
        val to = tabs.indexOf(motion.target)
        val dir = if (to > from) 1f else -1f           // +1: the new page comes from the end / below
        val p = motion.progress.value
        Box(Modifier.fillMaxSize().then(drag)) {
            listOf(motion.current, motion.target).distinct().forEach { tab ->
                key(tab) {      // the same key when the target becomes current: the page keeps its state
                    val incoming = tab != motion.current
                    val shift = if (incoming) dir * (1f - p) * span else -dir * p * span
                    Box(
                        Modifier.fillMaxSize()
                            .offset { if (vertical) IntOffset(0, shift.roundToInt()) else IntOffset(shift.roundToInt(), 0) }
                            .graphicsLayer { alpha = if (incoming) 0.5f + 0.5f * p else 1f - 0.5f * p }
                    ) { content(tab, motion.settled) }
                }
            }
        }
    }
}

/** Phone: a floating glass bar at the bottom centre, like the One UI Quick panel buttons. */
@Composable
fun GlassTabBar(hazeState: HazeState, tabs: List<AppTab>, motion: TabMotion, animate: Boolean,
                live: Set<AppTab> = emptySet(),
                onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val bounds = remember(tabs) { mutableStateListOf(*Array(tabs.size) { Rect.Zero }) }
    GlassCapsule(hazeState, modifier) {
        Box {
            RubberIndicator(bounds, tabs, motion, vertical = false)
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tabs.forEachIndexed { i, t ->
                    // four tabs do not fit side by side with their names: only the open one keeps its name
                    TabItem(t.icon, t.label, t == motion.shown, horizontal = true, animate = animate, live = t in live,
                        showLabel = tabs.size <= 3 || t == motion.shown,
                        modifier = Modifier.onPlaced { bounds[i] = it.boundsInParent().translate(it.parentOffset()) }) { onSelect(t) }
                }
            }
        }
    }
}

/** Tablet: the same glass, standing on the left edge; the indicator moves up and down. */
@Composable
fun GlassNavRail(hazeState: HazeState, tabs: List<AppTab>, motion: TabMotion, animate: Boolean,
                 live: Set<AppTab> = emptySet(),
                 onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val bounds = remember(tabs) { mutableStateListOf(*Array(tabs.size) { Rect.Zero }) }
    GlassCapsule(hazeState, modifier) {
        Box {
            RubberIndicator(bounds, tabs, motion, vertical = true)
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEachIndexed { i, t ->
                    TabItem(t.iconOn(true), t.labelOn(true), t == motion.shown, horizontal = false, animate = animate, live = t in live,
                        modifier = Modifier.onPlaced { bounds[i] = it.boundsInParent().translate(it.parentOffset()) }) { onSelect(t) }
                }
            }
        }
    }
}

/** The padding of the Row/Column the items sit in, so item bounds line up with the indicator's box. */
private fun androidx.compose.ui.layout.LayoutCoordinates.parentOffset() =
    parentLayoutCoordinates?.boundsInParent()?.topLeft ?: androidx.compose.ui.geometry.Offset.Zero

/**
 * The selected-tab highlight. Moving to another tab its leading edge sets off first and the
 * trailing edge follows, so it stretches between the two tabs and snaps back to size, like rubber.
 */
@Composable
private fun RubberIndicator(bounds: List<Rect>, tabs: List<AppTab>, motion: TabMotion, vertical: Boolean) {
    val a = bounds.getOrNull(tabs.indexOf(motion.current)) ?: return
    val b = bounds.getOrNull(tabs.indexOf(motion.target)) ?: return
    if (a == Rect.Zero) return
    val p = motion.progress.value
    val lead = (p * 1.5f).coerceIn(0f, 1f)
    val trail = ((p - 1f / 3f) * 1.5f).coerceIn(0f, 1f)
    val forward = if (vertical) b.top > a.top else b.left > a.left
    fun edge(x: Float, y: Float, t: Float) = lerp(x, y, t)
    val r = if (vertical) {
        val top = edge(a.top, b.top, if (forward) trail else lead)
        val bottom = edge(a.bottom, b.bottom, if (forward) lead else trail)
        Rect(a.left, top, a.right, bottom)
    } else {
        val left = edge(a.left, b.left, if (forward) trail else lead)
        val right = edge(a.right, b.right, if (forward) lead else trail)
        Rect(left, a.top, right, a.bottom)
    }
    val density = LocalDensity.current
    with(density) {
        Box(
            Modifier
                .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                .size(r.width.toDp(), r.height.toDp())
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
        )
    }
}

@Composable
private fun GlassCapsule(hazeState: HazeState, modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(36.dp)
    Box(modifier.clip(shape).glassSurface(hazeState, shape)) { content() }
}

@Composable
private fun TabItem(icon: String, label: String, selected: Boolean, horizontal: Boolean, animate: Boolean,
                    live: Boolean = false, showLabel: Boolean = true, modifier: Modifier = Modifier,
                    onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ink by animateColorAsState(
        if (selected) c.primary else c.onSurface.copy(alpha = 0.7f),
        if (animate) tween(TAB_MS, easing = EaseOut) else tween(0), label = "tab ink",
    )
    val base = modifier
        .clip(RoundedCornerShape(30.dp))
        .semantics { this.selected = selected }
        .clickable(role = Role.Tab, onClick = onClick)
    if (horizontal) {
        Row(base.heightIn(min = 52.dp).padding(horizontal = if (showLabel) 20.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TabIcon(icon, ink, 20.dp, live)
            if (showLabel) {
                Box(Modifier.width(8.dp))
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
            }
        }
    } else {
        Column(base.width(76.dp).heightIn(min = 68.dp).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            TabIcon(icon, ink, 22.dp, live)
            Box(Modifier.size(4.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink)
        }
    }
}

/** The tab icon, with a small green dot while that tab's feature is running (the camera keeps sending off its page). */
@Composable
private fun TabIcon(name: String, ink: Color, size: Dp, live: Boolean) {
    Box {
        DeckIcon(name, ink, size)
        if (live) Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(StateColors.Live, CircleShape))
    }
}
