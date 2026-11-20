package dev.phonemic.ui.screen

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.chrisbanes.haze.HazeState
import dev.phonemic.connection.LaptopFinder
import dev.phonemic.screen.ScreenClient
import dev.phonemic.screen.ScreenPacket
import dev.phonemic.screen.ScreenState
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.GlassCard
import dev.phonemic.ui.RunOnce
import dev.phonemic.ui.StateColors
import dev.phonemic.ui.Ui
import dev.phonemic.ui.deck.Chip
import dev.phonemic.ui.home.LiveDot
import dev.phonemic.ui.findActivity
import kotlinx.coroutines.delay


/** Whether the full-screen cast view is on screen. Compose state, so MainScreen can show and hide it. */
object ScreenUi {
    var visible by mutableStateOf(false)
}

/**
 * The Screen tab: the tablet as a second screen for the laptop. Once started, the laptop
 * [picture] sits inside the tab under the settings; the header's Full screen button moves the
 * same picture over the whole screen ([ScreenCastOverlay]), and leaving full screen keeps casting.
 */
@Composable
fun ScreenTab(hazeState: HazeState, picture: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ink = MaterialTheme.colorScheme.onSurface
    RunOnce { ScreenState.load(ctx) }
    var extend by remember { mutableStateOf(ScreenState.extend) }
    var status by remember { mutableStateOf(ScreenState.status) }
    var online by remember { mutableStateOf(ScreenState.online) }
    var active by remember { mutableStateOf(ScreenState.active) }
    var note by remember { mutableStateOf(ScreenState.note) }
    LaunchedEffect(Unit) {
        while (true) {
            status = ScreenState.status; online = ScreenState.online; note = ScreenState.note; active = ScreenState.active
            delay(300)
        }
    }
    DisposableEffect(Unit) {
        LaptopFinder.start(ctx)
        onDispose { LaptopFinder.stop() }
    }

    val showPicture = active && !ScreenUi.visible
    // with room, the picture card fills the rest of the tab; in a short or narrow window the page scrolls
    // instead and the card keeps a 16:9 shape (a weight inside a scrolling column would get 0 height).
    // The window size comes from the configuration, not BoxWithConstraints: that would subcompose the
    // picture during measure and break its move into the full-screen overlay.
    val window = LocalConfiguration.current
    val roomy = showPicture && window.screenHeightDp >= 560 && window.screenWidthDp >= 600
    Column(modifier.then(if (roomy) Modifier else Modifier.verticalScroll(rememberScrollState())),
        verticalArrangement = Arrangement.spacedBy(Ui.Gap)) {
        GlassCard(hazeState) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeckIcon("monitor", ink, 22.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Second screen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = ink)
                    Spacer(Modifier.size(8.dp))
                    LiveDot(online, "Live")
                    Spacer(Modifier.size(8.dp))
                    Text(status, fontSize = 13.sp, color = ink.copy(0.75f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Extend", extend) { extend = true; ScreenState.extend = true; ScreenState.save(ctx) }
                    Chip("Mirror", !extend) { extend = false; ScreenState.extend = false; ScreenState.save(ctx) }
                }
                if (!extend) Text("A copy of your laptop's main screen on the tablet.", fontSize = 15.sp, color = ink.copy(0.85f))
                if (note.isNotEmpty()) Text(note, fontSize = 14.sp, color = StateColors.Red)
                if (active) {
                    OutlinedButton(
                        onClick = { ScreenClient.stop() },
                        border = BorderStroke(1.5.dp, StateColors.Red),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Stop second screen", fontSize = 17.sp, color = StateColors.Red) }
                } else {
                    Button(
                        onClick = { ScreenClient.start(ctx) },
                        colors = ButtonDefaults.buttonColors(containerColor = StateColors.Live, contentColor = StateColors.OnLive),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) { Text("Start second screen", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        if (showPicture) {
            GlassCard(hazeState, if (roomy) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                picture(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
            }
        }
    }
}

/**
 * The laptop picture with touch going back to the laptop. One instance is moved between the tab
 * and the full-screen overlay (movableContentOf in MainScreen): the move re-creates the surface,
 * so the decoder restarts on a key frame (a short black moment), but the connection stays.
 */
@Composable
fun ScreenPicture(modifier: Modifier = Modifier) {
    var online by remember { mutableStateOf(ScreenState.online) }
    var status by remember { mutableStateOf(ScreenState.status) }
    var ratio by remember { mutableStateOf(16f / 9f) }
    LaunchedEffect(Unit) {
        while (true) {
            online = ScreenState.online; status = ScreenState.status
            ratio = ScreenState.videoWidth.toFloat() / ScreenState.videoHeight.coerceAtLeast(1)
            delay(300)
        }
    }

    Box(modifier.background(Color.Black)) {
        Box(Modifier.align(Alignment.Center).aspectRatio(ratio)) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        keepScreenOn = true             // watching the laptop: the tablet must not doze off
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = ScreenClient.attach(h.surface)
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) = ScreenClient.detach(h.surface)
                        })
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
            // transparent layer on top: the SurfaceView would swallow the touches
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                val fingers = HashMap<Long, Int>()          // pointer id -> small number the laptop uses
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        for (c in event.changes) {
                            val id = c.id.value
                            val x = c.position.x / size.width
                            val y = c.position.y / size.height
                            when {
                                c.changedToDown() -> {
                                    val slot = (0..9).first { it !in fingers.values }
                                    fingers[id] = slot
                                    ScreenClient.sendTouch(ScreenPacket.DOWN, slot, x, y)
                                }
                                c.changedToUp() -> fingers.remove(id)?.let {
                                    ScreenClient.sendTouch(ScreenPacket.UP, it, x, y)
                                }
                                c.pressed && c.positionChanged() -> fingers[id]?.let {
                                    ScreenClient.sendTouch(ScreenPacket.MOVE, it, x, y)
                                }
                            }
                            c.consume()
                        }
                    }
                }
            })
        }
        if (!online) {
            Text(status, fontSize = 16.sp, color = Color.White.copy(0.8f), modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
    }
}

/** Full screen: [content] (the laptop picture) over the whole tablet, system bars hidden, landscape. The minimize button or Back leaves it; casting goes on. */
@Composable
fun ScreenCastOverlay(onExit: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    val window = activity?.window
    DisposableEffect(window) {
        // the laptop picture is wide: a phone held upright would show it as a thin strip
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            bars?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = before ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    BackHandler(true) { onExit() }

    // the root takes the touches on the black bars; otherwise they fall through to the tab underneath (its chips, Stop, the gear)
    Box(Modifier.fillMaxSize().background(Color.Black)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
        content(Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.TopStart).padding(10.dp).size(44.dp).clip(CircleShape)
                .background(Color.White.copy(0.14f)).clickable(onClickLabel = "Leave full screen", onClick = onExit),
            contentAlignment = Alignment.Center,
        ) { DeckIcon("minimize", Color.White.copy(0.75f), 20.dp) }
    }
}
