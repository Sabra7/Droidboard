package dev.phonemic.ui.home

import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.phonemic.audio.MicState
import dev.phonemic.ui.findActivity
import dev.phonemic.ui.home.dim.DimConfig
import dev.phonemic.ui.home.dim.DimEnv
import dev.phonemic.ui.home.dim.DimGrid
import dev.phonemic.ui.home.dim.DimLayout
import dev.phonemic.ui.home.dim.DimWidget
import dev.phonemic.ui.home.dim.DimWidgetView
import kotlinx.coroutines.delay

/**
 * Hides the system bars while this is composed. With [keepOn] the screen stays lit, and with [brightness]
 * the window gets that brightness (null leaves it alone). Everything is put back when it leaves.
 */
@Composable
fun ImmersiveEffect(keepOn: Boolean, brightness: Float?) {
    val view = LocalView.current
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(window, keepOn) {
        if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (keepOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(window, brightness) {
        if (brightness != null) window?.let { it.attributes = it.attributes.apply { screenBrightness = brightness } }
        onDispose {
            if (brightness != null) window?.let {
                it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
}

/** True while the screen is wider than tall; the Dim layout is kept per orientation. */
@Composable
fun isLandscape(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/** The saved Dim layout of this orientation (today's look until the user changes it). */
fun loadDimLayout(landscape: Boolean): DimLayout =
    DimConfig.decode(if (landscape) MicState.dimLayoutLandscape else MicState.dimLayoutPortrait, landscape)

/**
 * The Dim canvas: the widgets of a layout on their grid, filling the screen (inside the camera cutout).
 * The editor uses the very same canvas, so what you arrange is what Dim shows.
 */
@Composable
fun DimCanvas(
    layout: DimLayout,
    grid: DimGrid,
    modifier: Modifier = Modifier,
    inset: Dp = 6.dp,
    widget: @Composable (DimWidget, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val cw = maxWidth / grid.cols
        val ch = maxHeight / grid.rows
        layout.widgets.forEach { w ->
            key(w.id) {
                widget(w, Modifier.offset(cw * w.col, ch * w.row).size(cw * w.w, ch * w.h).padding(inset))
            }
        }
    }
}

/**
 * Black screen for leaving the mic running: brightness from Settings (1 % unless changed), screen kept on,
 * system bars hidden, and the widgets the user arranged (by default one big mute button). No animation.
 * Long-press anywhere (or Back) to leave.
 */
@Composable
fun DimScreen(live: Boolean, muted: Boolean, onMute: () -> Unit, onExit: () -> Unit) {
    // The hint disappears after 3 s so the screen is fully black; a tap on the background shows it again.
    var taps by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(taps) {
        hint = true
        delay(3000)
        hint = false
    }
    ImmersiveEffect(keepOn = true, brightness = MicState.dimBrightness)
    val landscape = isLandscape()
    val layout = remember(landscape) { loadDimLayout(landscape) }
    val env = DimEnv(live, muted, onMute)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { taps++ }, onLongPress = { onExit() }) },
        contentAlignment = Alignment.Center,
    ) {
        DimCanvas(layout, DimGrid.of(landscape), Modifier.fillMaxSize().displayCutoutPadding()) { w, m ->
            DimWidgetView(w, env, m)
        }
        if (hint) {
            Text(
                "Hold anywhere to wake", fontSize = 13.sp, color = Color(0xFF8A8A8A),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            )
        }
    }
}
