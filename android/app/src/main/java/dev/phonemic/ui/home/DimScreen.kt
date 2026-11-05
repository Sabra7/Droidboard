package dev.phonemic.ui.home

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.findActivity
import kotlinx.coroutines.delay
/**
 * Black screen for leaving the mic running: lowest brightness, screen kept on, system bars
 * hidden, a single big mute button. No animation. Long-press anywhere (or Back) to leave.
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
    val view = LocalView.current
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.attributes = it.attributes.apply { screenBrightness = 0.01f }
        }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window?.let {
                it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.attributes = it.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { taps++ }, onLongPress = { onExit() }) },
        contentAlignment = Alignment.Center,
    ) {
        // tiny status light (below the camera hole): dim green = streaming, grey = not connected
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 72.dp).size(6.dp)
                .background(if (live && !muted) Color(0xFF138A3A) else if (muted) Color(0xFF8A3A3A) else Color(0xFF444444), CircleShape)
        )
        val fill = if (muted) Color(0xFF3A1212) else Color(0xFF141414)
        val edge = if (muted) Color(0xFF8A3A3A) else Color(0xFF3A3A3A)
        val text = if (muted) Color(0xFFFF9A90) else Color(0xFFC8C8C8)
        Box(
            Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, edge, CircleShape)
                .clickable(role = Role.Button, onClick = onMute),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DeckIcon(if (muted) "mic-off" else "mic", text, 40.dp)
                Text(if (muted) "Unmute" else "Mute", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = text)
            }
        }
        Text(
            when { muted -> "Muted"; live -> "Streaming"; else -> "Not connected" },
            fontSize = 14.sp, color = Color(0xFF6A6A6A),
            modifier = Modifier.align(Alignment.Center).padding(top = 240.dp),
        )
        if (hint) {
            Text(
                "Hold anywhere to wake", fontSize = 13.sp, color = Color(0xFF8A8A8A),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            )
        }
    }
}
