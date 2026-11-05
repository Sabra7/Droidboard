package dev.phonemic.ui.home

import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.phonemic.audio.AutoRules
import dev.phonemic.audio.MicSlot
import dev.phonemic.audio.MicState
import dev.phonemic.audio.VoiceFilter
import dev.phonemic.camera.CameraState
import dev.phonemic.screen.ScreenClient
import dev.phonemic.ui.AppTab
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.GlassBackground
import dev.phonemic.ui.GlassBottomSheet
import dev.phonemic.ui.GlassCard
import dev.phonemic.ui.GlassIconButton
import dev.phonemic.ui.GlassNavRail
import dev.phonemic.ui.GlassPill
import dev.phonemic.ui.GlassTabBar
import dev.phonemic.ui.RAIL_SPACE
import dev.phonemic.ui.StateColors
import dev.phonemic.ui.TAB_BAR_SPACE
import dev.phonemic.ui.TabPages
import dev.phonemic.ui.Ui
import dev.phonemic.ui.camera.CameraKeeper
import dev.phonemic.ui.camera.CameraPlaceholder
import dev.phonemic.ui.camera.CameraTab
import dev.phonemic.ui.deck.DeckPanel
import dev.phonemic.ui.rememberTabMotion
import dev.phonemic.ui.screen.ScreenCastOverlay
import dev.phonemic.ui.screen.ScreenTab
import dev.phonemic.ui.screen.ScreenUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


/** Screens at least this wide (tablets, landscape) use the left half; content is never wider than MAX_WIDTH. */
private val WIDE_BREAKPOINT = 840.dp
private val MAX_WIDTH = 1100.dp
/** A phone is too narrow for the tablet's deck: three buttons a row keeps the labels readable. */
private const val PHONE_DECK_COLUMNS = 3
/** Below this height the main screen scrolls. */
private val COMPACT_HEIGHT = 600.dp

/**
 * Reads MicState every 150 ms (MicState itself is a plain shared object).
 * [onTransportChanged] is called after the user picks another way to connect.
 */
@Composable
fun MainScreen(onToggle: () -> Unit, onTransportChanged: () -> Unit) {
    val hazeState = remember { HazeState() }
    val sheetHaze = remember { HazeState() }      // what a glass sheet blurs: the whole page behind it
    val ctx = LocalContext.current
    val reduceMotion = Settings.Global.getFloat(
        ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f

    var running by remember { mutableStateOf(MicState.running) }
    var muted by remember { mutableStateOf(MicState.muted) }
    var status by remember { mutableStateOf(MicState.status) }
    var history by remember { mutableStateOf(FloatArray(GRID_COLS)) }
    var gain by remember { mutableFloatStateOf(MicState.gain) }
    var isolation by remember { mutableIntStateOf(MicState.isolation) }
    var transport by remember { mutableIntStateOf(MicState.transport) }
    var active by remember { mutableIntStateOf(MicState.active) }
    var clipping by remember { mutableStateOf(false) }
    var showLinks by remember { mutableStateOf(false) }
    var showMics by remember { mutableStateOf(false) }
    val motion = rememberTabMotion()
    val scope = rememberCoroutineScope()
    var micChoice by remember { mutableStateOf(MicState.micChoice) }
    var channelMode by remember { mutableIntStateOf(MicState.channelMode) }
    var activeMic by remember { mutableStateOf(MicState.activeMic) }
    var micNote by remember { mutableStateOf(MicState.micNote) }
    var codecNote by remember { mutableStateOf(MicState.codecNote) }
    var channelsInUse by remember { mutableIntStateOf(MicState.channelsInUse) }
    var dimmed by remember { mutableStateOf(false) }
    var callMuted by remember { mutableStateOf(MicState.callMuted) }
    var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }   // for Settings > Dim
    var cameraOn by remember { mutableStateOf(CameraState.sending) }

    CameraKeeper()      // owns the camera: it keeps sending when the Camera page is left

    LaunchedEffect(Unit) {
        while (true) {
            cameraOn = CameraState.sending
            running = MicState.running; muted = MicState.muted; status = MicState.status
            callMuted = MicState.callMuted
            active = MicState.active
            activeMic = MicState.activeMic; micNote = MicState.micNote; codecNote = MicState.codecNote
            channelsInUse = MicState.channelsInUse
            if (!dimmed && !showLinks && !showMics &&
                AutoRules.shouldDim(MicState.dimSeconds, running, SystemClock.elapsedRealtime() - lastTouch)) {
                dimmed = true
            }
            if (dimmed) {       // nothing but the mute button is on screen: poll slowly
                delay(500)
                continue
            }
            clipping = SystemClock.elapsedRealtime() < MicState.clipUntil
            history = history.copyOfRange(1, GRID_COLS) + levelToFraction(MicState.level)
            delay(150)
        }
    }
    val connected = running && status.startsWith("Connected")

    BackHandler(motion.target != AppTab.HOME) {      // the sheets handle Back themselves
        scope.launch { motion.go(AppTab.HOME, animate = !reduceMotion) }
    }
    BackHandler(dimmed) { dimmed = false }

    GlassBackground(hazeState) {
        // status in a Now Bar-like capsule (shape A); the mic pick and Dim sit under it
        val statusCard: @Composable () -> Unit = {
            StatusCapsuleCard(
                hazeState,
                info = HomeStatus.info(running, muted || callMuted, status, if (running) active else transport,
                    codecNote, channelsInUse),
                reduceMotion = reduceMotion,
                onAction = { if (running) { MicState.muted = !MicState.muted; muted = MicState.muted } else onToggle() },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill(onClick = { showMics = true }, onClickLabel = "Choose the phone microphone") {
                        DeckIcon("mic", MaterialTheme.colorScheme.onSurface, 18.dp)
                        Spacer(Modifier.size(6.dp))
                        Text(when {
                            running && activeMic.isNotEmpty() ->
                                if (activeMic.contains("mic", ignoreCase = true)) activeMic else "$activeMic mic"
                            micChoice == MicSlot.AUTO -> "Auto"
                            else -> micChoice.label
                        }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.weight(1f))
                    GlassIconButton("moon", "Dim the screen", onClick = { dimmed = true })
                }
            }
        }

        val knobCards: @Composable (Dp) -> Unit = { knobSize ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassCard(hazeState, Modifier.weight(1f)) {
                    KnobBlock("Mic level","${(gain * 100).toInt()}%", badge = if (clipping) "CLIP" else null) {
                        Knob(
                            fraction = gain / 4f,
                            onFraction = { gain = it * 4f; MicState.gain = gain },
                            label = "Mic level", valueText = "${(gain * 100).toInt()} percent",
                            size = knobSize,
                            mark = 0.25f,                                  // 100 %
                            onReset = { gain = 1f; MicState.gain = 1f },   // double-tap
                            onFinished = { MicState.save(ctx) },           // save when the finger lifts
                        )
                    }
                }
                GlassCard(hazeState, Modifier.weight(1f)) {
                    val name = VoiceFilter.NAMES[isolation]
                    KnobBlock("Isolation", name) {
                        Knob(
                            fraction = isolation / (VoiceFilter.NAMES.size - 1f),
                            onFraction = {
                                isolation = (it * (VoiceFilter.NAMES.size - 1)).toInt()
                                MicState.isolation = isolation
                            },
                            steps = VoiceFilter.NAMES.size,
                            size = knobSize,
                            onFinished = { MicState.save(ctx) },
                            label = "Isolation", valueText = name,
                        )
                    }
                }
            }
        }

        // the live mic level (one column per 150 ms, newest on the right); on a tablet it fills the room under the knobs
        val levelCard: @Composable (Modifier, Boolean) -> Unit = { modifier, fill ->
            GlassCard(hazeState, modifier) {
                Column(if (fill) Modifier.fillMaxHeight() else Modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Level", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.75f))
                    if (fill) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ActivityGrid(history, Modifier.aspectRatio(GRID_RATIO))
                        }
                    } else {
                        ActivityGrid(history)
                    }
                }
            }
        }

        val buttons: @Composable () -> Unit = {
            // Mute lives in the status capsule now (shape A); this row is only Start / Stop
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (running) {
                    // outlined and red: harder to hit by mistake in the middle of a call
                    OutlinedButton(
                        onClick = onToggle,
                        border = BorderStroke(1.5.dp, StateColors.Red),
                        modifier = Modifier.weight(2f).heightIn(min = 60.dp),
                    ) { Text("Stop", fontSize = 18.sp, color = StateColors.Red) }
                } else {
                    Button(
                        onClick = onToggle,
                        colors = ButtonDefaults.buttonColors(containerColor = StateColors.Live, contentColor = StateColors.OnLive),
                        modifier = Modifier.weight(2f).heightIn(min = 60.dp),
                    ) { Text("Start", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }

        // Phone: one column per tab, a glass tab bar at the bottom.
        // Tablet (wide): a glass rail on the left; Home shows the mic and the deck side by side.
        // While a sheet is open the page is also the source of its blur; closed, nothing extra is drawn.
        val sheetOpen = showLinks || showMics
        Box(Modifier.fillMaxSize().then(if (sheetOpen && Build.VERSION.SDK_INT >= 31) Modifier.haze(sheetHaze) else Modifier)
            .pointerInput(Unit) {       // any touch counts as use for Settings > Dim (watched, never consumed)
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.elapsedRealtime()
                    }
                }
            }) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_BREAKPOINT
            val short = maxHeight < COMPACT_HEIGHT
            val tabs = if (wide) AppTab.TABLET else AppTab.PHONE
            LaunchedEffect(wide) {      // a phone turned into a tablet layout has no Deck tab
                if (motion.current !in tabs || motion.target !in tabs) motion.snap(AppTab.HOME)
            }
            val select: (AppTab) -> Unit = { t -> scope.launch { motion.go(t, animate = !reduceMotion) } }
            Column(
                Modifier.align(Alignment.TopCenter).widthIn(max = MAX_WIDTH).fillMaxSize()
                    .statusBarsPadding().navigationBarsPadding()
                    .padding(start = if (wide) RAIL_SPACE else Ui.Pad, end = Ui.Pad, top = Ui.Pad,
                        bottom = if (wide) Ui.Pad else TAB_BAR_SPACE),
                verticalArrangement = Arrangement.spacedBy(Ui.Gap),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Droidboard", fontSize = Ui.Title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onBackground)
                    GlassIconButton("settings", "Settings", onClick = { showLinks = true })
                }

                // phone: slides sideways and follows a swipe; tablet: slides up and down with the rail, no swipe
                TabPages(motion, tabs, vertical = wide, swipe = !wide, animate = !reduceMotion,
                    modifier = Modifier.weight(1f).fillMaxWidth()) { page, settled ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Ui.Gap)) {
                        when {
                            page == AppTab.DECK -> DeckPanel(hazeState, onSetup = { showLinks = true },
                                modifier = Modifier.weight(1f).fillMaxWidth(), columns = PHONE_DECK_COLUMNS)
                            page == AppTab.SCREEN -> ScreenTab(hazeState, Modifier.weight(1f).fillMaxWidth())
                            // the camera only opens once its page has landed, never while a swipe passes by
                            page == AppTab.CAMERA -> if (settled) CameraTab(hazeState, Modifier.weight(1f).fillMaxWidth())
                                else CameraPlaceholder(hazeState, Modifier.weight(1f).fillMaxWidth())
                            short -> {
                                // a short window (split screen, freeform window, phone on its side): scroll instead of cutting off the buttons
                                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(Ui.Gap)) {
                                    statusCard()
                                    knobCards(120.dp)
                                    if (wide) {
                                        levelCard(Modifier, false)
                                    }
                                    buttons()
                                }
                            }
                            wide -> {
                                // the left half is the mic; the right half is the button deck
                                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Ui.Gap)) {
                                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Ui.Gap)) {
                                        statusCard()
                                        knobCards(150.dp)
                                        levelCard(Modifier.weight(1f), true)
                                        buttons()
                                    }
                                    DeckPanel(hazeState, onSetup = { showLinks = true },
                                        modifier = Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                            else -> {
                                statusCard()
                                knobCards(132.dp)
                                Spacer(Modifier.weight(1f))
                                buttons()
                            }
                        }
                    }
                }
            }
            if (wide) {
                GlassNavRail(hazeState, tabs, motion, animate = !reduceMotion, onSelect = select,
                    live = if (cameraOn) setOf(AppTab.CAMERA) else emptySet(),
                    modifier = Modifier.align(Alignment.CenterStart).statusBarsPadding().navigationBarsPadding()
                        .padding(start = Ui.Pad))
            } else {
                GlassTabBar(hazeState, tabs, motion, animate = !reduceMotion, onSelect = select,
                    live = if (cameraOn) setOf(AppTab.CAMERA) else emptySet(),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
            }
        }

        }

        GlassBottomSheet(showLinks, hazeState, "Settings", onDismiss = { showLinks = false }, backdrop = sheetHaze) {
            SettingsSheet(
                // MicState.active is only meaningful while streaming; stopped, it can still hold an old route
                hazeState = hazeState, transport = transport, active = if (running) active else transport, live = connected,
                reduceMotion = reduceMotion,
                onSelect = {
                    transport = it
                    MicState.transport = it
                    MicState.save(ctx)
                    onTransportChanged()
                },
            )
        }

        GlassBottomSheet(showMics, hazeState, "Microphone", onDismiss = { showMics = false }, backdrop = sheetHaze) {
            MicSheet(
                hazeState = hazeState, choice = micChoice, running = running,
                activeMic = activeMic, note = micNote, channelMode = channelMode,
                onChannelMode = {
                    channelMode = it
                    MicState.channelMode = it
                    MicState.save(ctx)
                    MicState.micRevision++          // reopen the recorder with one or two channels
                },
                onSelect = {
                    micChoice = it
                    MicState.micChoice = it
                    MicState.micNote = ""
                    MicState.save(ctx)
                    MicState.micRevision++          // a running service reopens its recorder
                },
            )
        }

        if (ScreenUi.visible) {      // the second screen: the laptop picture over everything
            ScreenCastOverlay(onExit = { ScreenUi.visible = false; ScreenClient.stop() })
        }

        if (dimmed) {
            DimScreen(
                live = connected, muted = muted,
                onMute = { MicState.muted = !MicState.muted; muted = MicState.muted },
                onExit = { dimmed = false; lastTouch = SystemClock.elapsedRealtime() },
            )
        }
    }
}


@Composable
private fun KnobBlock(title: String, value: String, badge: String? = null, knob: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(0.75f))
        knob()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            if (badge != null) {    // e.g. CLIP: the sound is being cut off, turn the level down
                Spacer(Modifier.size(8.dp))
                Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A0402),
                    modifier = Modifier.background(StateColors.Red, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}
