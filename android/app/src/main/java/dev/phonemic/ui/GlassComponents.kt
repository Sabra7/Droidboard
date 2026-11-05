package dev.phonemic.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val GlassShape = RoundedCornerShape(32.dp)
private val SectionShape = RoundedCornerShape(24.dp)
private val SheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

/** One set of sizes for every screen, so cards, gaps and type line up. */
object Ui {
    val Gap = 16.dp            // between cards
    val Pad = 20.dp            // inside a card, and around the screen
    val Title = 28.sp          // screen and sheet titles
    val SheetWidth = 640.dp    // sheets never get wider than this (tablets)
}

/** Cards inside a sheet are drawn as plain sections: glass on glass would only blur the sheet itself. */
private val LocalInSheet = staticCompositionLocalOf { false }

/**
 * Pure black in dark mode. Pearl in light mode, with a very faint iridescent
 * wash so the glass has something to blur.
 */
@Composable
fun GlassBackground(hazeState: HazeState, content: @Composable () -> Unit) {
    val dark = LocalIsDark.current
    val bg = if (dark) Modifier.background(PureBlack) else Modifier.background(
        Brush.linearGradient(
            listOf(Color(0xFFFBF9F4), Color(0xFFF1ECF3), Color(0xFFEAF1F0), Color(0xFFF7F0E8)),
        )
    )
    Box(Modifier.fillMaxSize().then(bg).haze(hazeState)) {}
    content()
}

/** The thin light edge every glass surface has. */
@Composable
fun glassBorder(): BorderStroke {
    val dark = LocalIsDark.current
    return BorderStroke(
        1.dp,
        Brush.linearGradient(
            if (dark) listOf(Color.White.copy(0.22f), Color.White.copy(0.04f))
            else listOf(Color.White, Color.Black.copy(0.06f)),
        ),
    )
}

/** Frosted surface: blur of what is behind (API 31+), a tint that keeps text contrast >= 4.5:1, and the edge. */
@Composable
fun Modifier.glassSurface(hazeState: HazeState, shape: androidx.compose.ui.graphics.Shape, opaque: Float = 0.72f): Modifier {
    val blurred = Build.VERSION.SDK_INT >= 31
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = if (blurred) opaque else 0.94f)
    return this
        .clip(shape)
        .let { if (blurred) it.hazeChild(state = hazeState, shape = shape) else it }
        .background(tint)
        .border(glassBorder(), shape)
}

/**
 * One UI-style frosted card. Inside a [GlassBottomSheet] it becomes a quiet section instead,
 * so the sheet's own glass is the only blur.
 */
@Composable
fun GlassCard(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified,   // washes the glass with a colour, e.g. red while muted
    content: @Composable () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val base = if (LocalInSheet.current) {
        modifier.fillMaxWidth().clip(SectionShape)
            .background(c.onSurface.copy(alpha = 0.05f))
            .border(1.dp, c.onSurface.copy(alpha = 0.08f), SectionShape)
    } else {
        modifier.fillMaxWidth().glassSurface(hazeState, GlassShape)
    }
    Box(
        base
            .let { if (accent.isSpecified) it.background(accent.copy(alpha = 0.16f)) else it }
            .padding(Ui.Pad),
    ) { content() }
}

/** Rounded header button (the connection pill, the mic pick). */
@Composable
fun GlassPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(CircleShape)
            .background(c.onSurface.copy(alpha = 0.08f))
            .border(1.dp, c.onSurface.copy(alpha = 0.16f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Small round icon button, e.g. Dim in the corner of the status card. 48 dp to touch. */
@Composable
fun GlassIconButton(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier
            .size(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClickLabel = label, onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(c.onSurface.copy(alpha = 0.08f))
                .border(1.dp, c.onSurface.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { DeckIcon(icon, c.onSurface, 20.dp) }
    }
}

/** The sheet's own glass: a wide blur of the screen behind it, no grain (it costs a pass for nothing at this size). */
private val SheetGlass = HazeStyle(tint = Color.Transparent, blurRadius = 28.dp, noiseFactor = 0f)

/**
 * Glass sheet that rises from the bottom, One UI style: a dimmed backdrop, a drag handle, a title
 * with Done. Drag the top down, tap the backdrop or press Back to close. On a tablet it is centred
 * and no wider than [Ui.SheetWidth]. [content] is only composed while the sheet is open.
 *
 * With a [backdrop] (Android 12+) the sheet is see-through: the screen behind it shows blurred, like One UI 9,
 * and the backdrop dims less. The blur runs on the GPU and only while a sheet is open (see MainScreen).
 */
@Composable
fun GlassBottomSheet(
    visible: Boolean,
    hazeState: HazeState,
    title: String,
    onDismiss: () -> Unit,
    backdrop: HazeState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = backdrop != null && Build.VERSION.SDK_INT >= 31
    BackHandler(visible, onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (glass) 0.22f else 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = "Close", onClick = onDismiss,
                )
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val closeAt = with(LocalDensity.current) { 120.dp.toPx() }
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp), Alignment.BottomCenter) {
            Column(
                Modifier
                    .widthIn(max = Ui.SheetWidth)
                    .fillMaxWidth()
                    .offset { IntOffset(0, drag.value.roundToInt()) }
                    .let {
                        if (glass) it.clip(SheetShape).hazeChild(backdrop!!, SheetShape, SheetGlass)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f))
                            .border(glassBorder(), SheetShape)
                        else it.glassSurface(hazeState, SheetShape, opaque = 1f)   // no blur here: text must not fight the screen
                    }
                    // swallow taps so they don't reach the backdrop
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                // handle + title: the part you drag
                Column(
                    Modifier.fillMaxWidth().draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d ->
                            scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) }
                        },
                        onDragStopped = {
                            if (drag.value > closeAt) onDismiss() else scope.launch { drag.animateTo(0f) }
                        },
                    ).padding(horizontal = Ui.Pad),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.width(40.dp).height(5.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Done", fontSize = 16.sp)
                        }
                    }
                }
                CompositionLocalProvider(LocalInSheet provides true) {
                    Column(     // weight without fill: a short sheet fits its content, a long one scrolls
                        Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(start = Ui.Pad, end = Ui.Pad, top = 8.dp, bottom = Ui.Pad),
                        verticalArrangement = Arrangement.spacedBy(Ui.Gap),
                        content = content,
                    )
                }
            }
        }
    }
}

/** A grey line of help under a choice in a sheet. */
@Composable
fun Hint(text: String) {
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
}
