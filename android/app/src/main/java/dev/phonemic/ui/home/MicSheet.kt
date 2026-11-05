package dev.phonemic.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.phonemic.audio.ChannelMode
import dev.phonemic.audio.MicCatalog
import dev.phonemic.audio.MicOption
import dev.phonemic.audio.MicSlot
import dev.phonemic.audio.MicState
import dev.phonemic.ui.GlassCard
import dev.phonemic.ui.Hint
import dev.phonemic.ui.StateColors
import kotlinx.coroutines.delay
/** A thin horizontal level meter with a letter in front ("L" / "R"). */
@Composable
private fun LevelBar(label: String, level: Float) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface.copy(0.8f),
            modifier = Modifier.width(20.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(c.onSurface.copy(0.12f))) {
            Box(Modifier.fillMaxWidth(levelToFraction(level)).fillMaxHeight().background(
                if (level > 0.95f) StateColors.Red else StateColors.Live, CircleShape))
        }
    }
}

/** Glass sheet in the same style as [ConnectionSheet]: pick which microphone of the phone is used. */
@Composable
fun MicSheet(
    hazeState: HazeState,
    choice: MicSlot,
    running: Boolean,
    activeMic: String,
    note: String,
    channelMode: Int,
    onSelect: (MicSlot) -> Unit,
    onChannelMode: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    var options by remember { mutableStateOf(MicCatalog.options(ctx)) }
    var levels by remember { mutableStateOf(0f to 0f) }
    var inUse by remember { mutableIntStateOf(MicState.channelsInUse) }
    var stereoNote by remember { mutableStateOf(MicState.stereoNote) }
    LaunchedEffect(Unit) {      // a headset can be plugged in while the sheet is open
        var n = 0
        while (true) {
            if (n++ % 10 == 0) options = MicCatalog.options(ctx)
            levels = MicState.levelL to MicState.levelR
            inUse = MicState.channelsInUse; stereoNote = MicState.stereoNote
            delay(150)
        }
    }

    GlassCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // a saved pick can disappear (headset unplugged): still show it, marked
            val shown = if (options.any { it.slot == choice }) options
            else options + MicOption(choice, "not available right now")
            for (o in shown) {
                val selected = o.slot == choice
                val missing = options.none { it.slot == o.slot }
                Row(
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(0.16f) else Color.Transparent)
                        .clickable(role = Role.RadioButton, onClick = { onSelect(o.slot) })
                        .heightIn(min = 56.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(o.slot.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(o.detail, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(if (missing) 0.5f else 0.75f))
                    }
                }
            }
        }
    }

    GlassCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("In use now", fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(0.75f))
            Text(if (running && activeMic.isNotEmpty()) activeMic else if (running) "Checking…" else "Not streaming",
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            if (running && note.isNotEmpty()) {
                Text(note, fontSize = 14.sp, color = StateColors.Red)
            }
        }
    }
    GlassCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Channels", fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(0.75f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelMode.NAMES.forEachIndexed { mode, name ->
                    val selected = mode == channelMode
                    Box(
                        Modifier.weight(1f)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(0.16f)
                            else MaterialTheme.colorScheme.onSurface.copy(0.06f))
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(if (selected) 0.4f else 0.14f), CircleShape)
                            .clickable(role = Role.RadioButton, onClick = { onChannelMode(mode) })
                            .heightIn(min = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (running && inUse == 2) {
                LevelBar("L", levels.first)
                LevelBar("R", levels.second)
            }
            if (running && stereoNote.isNotEmpty()) Text(stereoNote, fontSize = 14.sp, color = StateColors.Red)
            when (channelMode) {
                ChannelMode.MONO -> Hint("One channel, like before.")
                ChannelMode.SWAP -> Hint("Stereo with left and right exchanged.")
            }
        }
    }
}
