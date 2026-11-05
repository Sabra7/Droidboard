package dev.phonemic.ui.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.phonemic.audio.AutoRules
import dev.phonemic.audio.MicState
import dev.phonemic.audio.SavedLaptops
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.GlassCard
import dev.phonemic.ui.Hint

/**
 * What the gear on Home opens: Connection first (the way, Wi-Fi, the laptop starting the mic), then
 * Notification, Automatic, Laptops and About. Every new option starts off, so nothing changes until it is used.
 */
@Composable
fun SettingsSheet(
    hazeState: HazeState,
    transport: Int,
    active: Int,
    live: Boolean,
    reduceMotion: Boolean,
    onSelect: (Int) -> Unit,
) {
    SectionLabel("Connection")
    ConnectionSheet(hazeState, transport, active, live, reduceMotion, onSelect)

    if (Build.VERSION.SDK_INT >= 36 && Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        SectionLabel("Notification")
        NowBarCard(hazeState)
    }

    SectionLabel("Automatic")
    AutomaticCard(hazeState)

    SectionLabel("Laptops")
    LaptopsCard(hazeState)

    SectionLabel("About")
    AboutSection(hazeState)
}

@Composable
private fun AutomaticCard(hazeState: HazeState) {
    val ctx = LocalContext.current
    var autoStart by remember { mutableStateOf(MicState.autoStart) }
    var muteOnCall by remember { mutableStateOf(MicState.muteOnCall) }
    var dim by remember { mutableIntStateOf(AutoRules.DIM_SECONDS.indexOf(MicState.dimSeconds).coerceAtLeast(0)) }
    var lowBattery by remember { mutableStateOf(MicState.lowBatteryStop) }
    GlassCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingSwitch("Start when the laptop is found", "On Wi-Fi, the mic starts by itself.", autoStart) {
                autoStart = it; MicState.autoStart = it; MicState.save(ctx)
            }
            SettingsDivider()
            SettingSwitch("Mute during calls", "The laptop hears silence until the call ends.", muteOnCall) {
                muteOnCall = it; MicState.muteOnCall = it; MicState.save(ctx)
            }
            SettingsDivider()
            SettingText("Dim the screen while streaming", "Saves battery. Long-press the screen to wake it.")
            ChoicePills(AutoRules.DIM_NAMES, dim) {
                dim = it; MicState.dimSeconds = AutoRules.DIM_SECONDS[it]; MicState.save(ctx)
            }
            SettingsDivider()
            SettingSwitch("Stop at low battery",
                "Below ${AutoRules.LOW_BATTERY}%, unless the phone is charging.", lowBattery) {
                lowBattery = it; MicState.lowBatteryStop = it; MicState.save(ctx)
            }
        }
    }
}

@Composable
private fun LaptopsCard(hazeState: HazeState) {
    val ctx = LocalContext.current
    var laptops by remember { mutableStateOf(MicState.laptops) }
    GlassCard(hazeState) {
        if (laptops.isEmpty()) {
            Hint("Laptops you stream to over Wi-Fi show up here, with their code.")
            return@GlassCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            laptops.forEachIndexed { i, laptop ->
                if (i > 0) SettingsDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeckIcon("laptop", MaterialTheme.colorScheme.onSurface.copy(0.75f), 20.dp)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(laptop.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(if (laptop.name == MicState.wifiName) "Code saved · this network" else "Code saved",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.75f))
                    }
                    TextButton(onClick = {
                        laptops = SavedLaptops.forget(laptops, laptop.name)
                        MicState.laptops = laptops
                        MicState.save(ctx)
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Forget") }
                }
            }
        }
    }
}

/** A small grey heading above a group of cards in Settings. */
@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface.copy(0.75f),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 0.dp))
}

/** A title and a grey line under it. */
@Composable
fun SettingText(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.75f))
    }
}

/** A setting that is on or off: the text on the left, the switch on the right. */
@Composable
fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One choice out of a few, as a row of pills (Now Bar Clean / Off, dim after Off / 30 s / 1 min). */
@Composable
fun ChoicePills(names: List<String>, selected: Int, height: Dp = 48.dp, onSelect: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, name ->
            val on = i == selected
            Box(
                Modifier.weight(1f)
                    .clip(CircleShape)
                    .background(if (on) c.primary.copy(0.16f) else c.onSurface.copy(0.06f))
                    .border(1.dp, c.onSurface.copy(if (on) 0.4f else 0.14f), CircleShape)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(i) })
                    .heightIn(min = height),
                contentAlignment = Alignment.Center,
            ) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = c.onSurface)
            }
        }
    }
}

/** The thin line between rows of one Settings card. */
@Composable
fun SettingsDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(0.1f))
