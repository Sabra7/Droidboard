package dev.phonemic.ui.camera

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.chrisbanes.haze.HazeState
import dev.phonemic.camera.CameraState
import dev.phonemic.camera.CameraStreamer
import dev.phonemic.connection.DeviceProfiles
import dev.phonemic.connection.LaptopFinder
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.GlassCard
import dev.phonemic.ui.RunOnce
import dev.phonemic.ui.StateColors
import dev.phonemic.ui.Ui
import dev.phonemic.ui.deck.Chip
import kotlinx.coroutines.delay


/** What the Camera page shows while it slides in or out: the camera itself only opens once the page has landed. */
@Composable
fun CameraPlaceholder(hazeState: HazeState, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Ui.Gap)) {
        GlassCard(hazeState) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                DeckIcon("camera", ink.copy(0.35f), 40.dp)
            }
        }
    }
}

/** From this width the camera card has two columns (picture | controls). */
private val WIDE_CAMERA = 700.dp

/**
 * The Camera tab: the tablet as a webcam for the laptop (live), and later as a second screen.
 * The camera permission is asked for as soon as the tab opens. The camera itself belongs to
 * [CameraKeeper], so the stream keeps going when you leave this tab; it stops with the Stop button
 * or when the app leaves the screen.
 */
@Composable
fun CameraTab(hazeState: HazeState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ink = MaterialTheme.colorScheme.onSurface
    val granted = CameraUi.granted
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { CameraUi.granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    RunOnce { CameraState.load(ctx) }      // back/front, quality and mirror as the user left them
    val profile = remember { DeviceProfiles.get(ctx) }
    var front by remember { mutableStateOf(CameraState.front) }
    var quality by remember { mutableStateOf(CameraState.quality) }
    var mirror by remember { mutableStateOf(CameraState.mirror) }
    fun keep() {
        CameraState.front = front; CameraState.quality = quality; CameraState.mirror = mirror; CameraState.save(ctx)
        CameraUi.revision++                  // the keeper rebinds with the new camera or quality
    }
    var sending by remember { mutableStateOf(CameraState.sending) }
    var online by remember { mutableStateOf(CameraState.online) }
    var status by remember { mutableStateOf(CameraState.status) }
    var virtual by remember { mutableStateOf(CameraState.virtualName) }
    var note by remember { mutableStateOf(CameraState.note) }

    LaunchedEffect(Unit) {
        while (true) {
            sending = CameraState.sending; online = CameraState.online; status = CameraState.status
            virtual = CameraState.virtualName; note = CameraState.note
            delay(300)
        }
    }
    DisposableEffect(Unit) {
        LaptopFinder.start(ctx)
        onDispose { LaptopFinder.stop() }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Ui.Gap),
    ) {
    GlassCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Camera", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Spacer(Modifier.size(8.dp))
                Box(Modifier.size(8.dp).background(if (sending && online) StateColors.Live else ink.copy(0.35f), CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(status, fontSize = 13.sp, color = ink.copy(0.75f))
            }
            // a wide screen (tablet) puts the picture on the left and the controls on the right, so
            // Send is reachable without scrolling; a phone keeps one column
            val picture: @Composable () -> Unit = {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(22.dp))
                .background(Color.Black).border(1.dp, ink.copy(0.14f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center) {
                if (granted) CameraPicture(Modifier.fillMaxSize())
                else Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The camera needs your permission", fontSize = 14.sp, color = Color.White.copy(0.85f))
                    OutlinedButton(onClick = { ask.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow camera", color = Color.White) }
                }
            }
            }
            val controls: @Composable () -> Unit = {
            // only what this device has: a tablet without a back camera shows no Back chip, an old camera no 1080p
            val side = if (front) "front" else "back"
            val sizes = profile.qualities(side)
            if (profile.cameras.isEmpty()) {
                Text("This device has no camera the app can use.", fontSize = 14.sp, color = StateColors.Red)
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.hasCamera("back")) Chip("Back camera", !front) { front = false; keep() }
                if (profile.hasCamera("front")) Chip("Front camera", front) { front = true; keep() }
                sizes.forEach { q -> Chip(q.label, q == quality) { quality = q; keep() } }
            }
            LaunchedEffect(side, sizes) {                      // a saved choice this device cannot do
                if (profile.cameras.isNotEmpty()) {
                    if (!profile.hasCamera(side)) { front = !front; keep() }
                    else if (quality !in sizes) { quality = sizes.last(); keep() }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mirror the picture", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
                    Text("Flips left and right, like a mirror.", fontSize = 13.sp, color = ink.copy(0.75f))
                }
                Switch(checked = mirror, onCheckedChange = { mirror = it; keep() })
            }
            if (sending) {
                OutlinedButton(onClick = { CameraStreamer.stop() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Stop sending", fontSize = 17.sp, color = StateColors.Red)
                }
            } else {
                Button(onClick = { CameraStreamer.start() }, enabled = granted,
                    colors = ButtonDefaults.buttonColors(containerColor = StateColors.Live, contentColor = StateColors.OnLive),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Send to the laptop", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            }
            val notes: @Composable () -> Unit = {
            when {
                sending && online && virtual.isNotEmpty() ->
                    Text("In Discord, Zoom or Teams pick \"$virtual\" as the camera.", fontSize = 14.sp, color = ink.copy(0.85f))
                sending && online && note.isNotEmpty() ->
                    Text("$note. The picture is shown in a window on the laptop meanwhile.", fontSize = 14.sp, color = StateColors.Red)
            }
            }
            BoxWithConstraints {
                if (maxWidth >= WIDE_CAMERA) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            picture()
                            notes()
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { controls() }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        picture()
                        controls()
                        notes()
                    }
                }
            }
        }
    }
    }
}

/** The live picture. It only shows what [CameraKeeper] is capturing: while this is on screen the keeper adds the preview. */
@Composable
private fun CameraPicture(modifier: Modifier) {
    val ctx = LocalContext.current
    // TextureView (COMPATIBLE), not SurfaceView: a SurfaceView draws outside the app's layer, so a glass sheet
    // could neither blur nor dim it and the live picture showed sharp through the sheet
    val view = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(view) {
        CameraUi.previewView = view
        onDispose { if (CameraUi.previewView === view) CameraUi.previewView = null }
    }
    AndroidView({ view }, modifier)
}
