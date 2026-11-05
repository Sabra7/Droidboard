package dev.phonemic.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.GlassCard
import dev.phonemic.ui.GlassPill
import dev.phonemic.ui.StateColors


/**
 * The Home status card: a Now Bar-like capsule (state icon, title, the way in use, Mute or Start), and a row
 * under it with the mic pick and Dim. The icon sits in a quiet ring of the state colour; while the voice
 * reaches the laptop an ON AIR lamp lights next to the title (the app's one live signal, see [LiveLamp]).
 */
@Composable
fun StatusCapsuleCard(
    hazeState: HazeState,
    info: CapsuleInfo,
    reduceMotion: Boolean,
    onAction: () -> Unit,
    details: @Composable () -> Unit,
) {
    val state = info.state
    val ink = when (state) {
        CapsuleState.LIVE -> StateColors.Live
        CapsuleState.MUTED -> StateColors.Red
        CapsuleState.WAIT -> StateColors.Wait
        CapsuleState.OFF -> MaterialTheme.colorScheme.onSurface.copy(0.5f)
    }
    val iconColor by animateColorAsState(ink,
        if (reduceMotion) snap() else spring(stiffness = Spring.StiffnessMediumLow), label = "capsule icon")
    GlassCard(hazeState, accent = if (state == CapsuleState.MUTED) StateColors.Red else Color.Unspecified) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(iconColor.copy(0.16f), CircleShape)
                        .border(1.5.dp, iconColor.copy(0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    DeckIcon(if (state == CapsuleState.MUTED) "mic-off" else "mic", iconColor, 22.dp)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(info.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false))
                        if (state == CapsuleState.LIVE) {
                            Spacer(Modifier.size(8.dp))
                            LiveLamp("ON AIR")
                        }
                    }
                    Text(info.subtitle, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.75f))
                }
                Spacer(Modifier.size(12.dp))
                GlassPill(onClick = onAction, onClickLabel = actionLabel(state)) {
                    Text(actionLabel(state), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        color = if (state == CapsuleState.MUTED) StateColors.Red else MaterialTheme.colorScheme.onSurface)
                }
            }
            details()
        }
    }
}

private fun actionLabel(state: CapsuleState) = when (state) {
    CapsuleState.OFF -> "Start"
    CapsuleState.MUTED -> "Unmute"
    else -> "Mute"
}

/** The small green lamp of something live: ON AIR on the mic, LIVE on the deck. */
@Composable
fun LiveLamp(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
        letterSpacing = 0.4.sp, color = StateColors.Live, maxLines = 1,
        modifier = Modifier.border(1.dp, StateColors.Live.copy(0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp))
}
