package dev.phonemic.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.phonemic.audio.MicState
import dev.phonemic.audio.NowBarMode
import dev.phonemic.audio.Transport
import dev.phonemic.connection.Discovery
import dev.phonemic.connection.FoundLaptop
import dev.phonemic.connection.isUsbPlugged
import dev.phonemic.ui.GlassCard
import dev.phonemic.ui.GlassIconButton
import dev.phonemic.ui.Hint
import kotlinx.coroutines.delay
/** Full-screen "branches" view: pick USB / Wi-Fi / Bluetooth by tapping a branch. */
@Composable
fun ConnectionSheet(
    hazeState: HazeState,
    transport: Int,
    active: Int,
    live: Boolean,
    reduceMotion: Boolean,
    onSelect: (Int) -> Unit,
) {
    var code by remember { mutableStateOf(MicState.pairCode) }
    val ctx = LocalContext.current
    // streaming over Wi-Fi right now: the laptop is known already, no need to wait for its next answer
    var found by remember {
        mutableStateOf(
            if (MicState.running && MicState.active == Transport.WIFI && MicState.status.startsWith("Connected") &&
                MicState.wifiName.isNotBlank()) FoundLaptop(MicState.wifiHost, MicState.wifiName) else null
        )
    }
    var plugged by remember { mutableStateOf(ctx.isUsbPlugged()) }
    val codeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {      // is the cable in? (shown under the USB branch)
        while (true) {
            plugged = ctx.isUsbPlugged()
            delay(1000)
        }
    }
    LaunchedEffect(found?.host) {   // laptop found: the code is the only thing left to type
        if (found != null && code.length != 6) {
            delay(300)
            runCatching { codeFocus.requestFocus() }
        }
    }
    // In Wi-Fi mode, listen for the laptop's beacon: its address is never typed, only the pairing code.
    DisposableEffect(transport == Transport.WIFI) {
        var discovery: Discovery? = null
        if (transport == Transport.WIFI) {
            discovery = Discovery(ctx) { f ->
                found = f
                MicState.laptopFound(ctx, f.host, f.name)
                code = MicState.pairCode          // a laptop seen before brings its saved code
            }.also { it.start() }
        }
        onDispose { discovery?.stop() }
    }

    GlassCard(hazeState) {
        ConnectionGraph(
            transport, live, reduceMotion, onSelect,
            active = active,
            hints = listOf(
                if (plugged) "cable connected" else "no cable",
                if (found != null) "laptop found" else "same network",
                "paired",
            ),
            laptopName = found?.name,
            deviceName = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "Tablet" else "Phone",
        )
    }

    GlassCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Transport.NAMES[transport], fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                color = laneColor(transport))
            if (transport == Transport.USB && active != transport) {      // the fallback only ever leaves USB
                Hint("The cable is out, so your voice goes over ${Transport.NAMES[active]} " +
                    "until you plug it back in.")
            }
            when (transport) {
                Transport.USB -> Hint("Cable in, USB debugging on.")
                Transport.WIFI -> {
                    Hint(found?.let {
                        val name = it.name.ifBlank { "your laptop" }
                        if (code.length == 6) "Found $name." else "Found $name. Type the code from the laptop app."
                    } ?: "Looking for the laptop on this network…")
                    OutlinedTextField(
                        value = code, singleLine = true, label = { Text("Pairing code") },
                        onValueChange = {
                            val v = it.filter(Char::isDigit).take(6)
                            code = v; MicState.pairCode = v; MicState.save(ctx)
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text(if (code.length == 6) "Code set" else "${code.length} of 6 digits") },
                        modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                    )
                    var compress by remember { mutableStateOf(MicState.compressWifi) }
                    SettingSwitch("Compress (Opus)", "Uses far less data. Needs libopus on the laptop (see README).",
                        compress) {
                        compress = it
                        MicState.compressWifi = it
                        MicState.save(ctx)
                        onSelect(Transport.WIFI)      // apply now: a running mic reconnects with the new codec
                    }
                }
                else -> Hint("Pair it in Windows Bluetooth settings.")
            }
        }
    }
    GlassCard(hazeState) {
        var allowed by remember { mutableStateOf(MicState.allowRemoteStart) }
        SettingSwitch("Allow laptop to start streaming", "Starts the mic when the cable goes in.", allowed) {
            allowed = it
            MicState.allowRemoteStart = it
            MicState.save(ctx)
        }
    }
}

/** Samsung Now Bar: Clean / Off. The notification follows within 2 s (MicService refresh). */
@Composable
fun NowBarCard(hazeState: HazeState) {
    val ctx = LocalContext.current
    val devOption = remember { MicState.nowBarDevOption(ctx) }
    var mode by remember { mutableStateOf(MicState.nowBar) }
    var about by remember { mutableStateOf(false) }
    if (about) {
        // the explanation lives behind the (!) next to the title, so the card stays a plain choice
        AlertDialog(
            onDismissRequest = { about = false },
            confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
            title = { Text("Now Bar", fontWeight = FontWeight.SemiBold) },
            text = {
                Text("Samsung shows it in the Now Bar only with Developer options > " +
                    "Live notifications for all apps turned on." + when (devOption) {
                        true -> " It is on."
                        false -> " It is off now, so you get a normal notification."
                        null -> ""
                    } + "\n\nOff: only the status bar icon and the notification.", fontSize = 15.sp)
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
    GlassCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Now Bar", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface)
                GlassIconButton("circle-alert", "About the Now Bar", onClick = { about = true })
            }
            ChoicePills(NowBarMode.NAMES, mode) { m ->
                mode = m
                MicState.nowBar = m
                MicState.save(ctx)
            }
        }
    }
}
