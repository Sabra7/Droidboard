package dev.phonemic.ui.home

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.phonemic.audio.MicService
import dev.phonemic.audio.MicState
import dev.phonemic.ui.DeckIcon
import dev.phonemic.ui.GlassCard
import dev.phonemic.ui.StateColors
import dev.phonemic.ui.findActivity

/** What the app is built with, and under which license (shown from Settings > About). */
private val LICENSES = listOf(
    "Jetpack Compose, AndroidX, CameraX" to "Apache License 2.0 · Google",
    "Haze (glass blur)" to "Apache License 2.0 · Chris Banes",
    "Kotlin" to "Apache License 2.0 · JetBrains",
    "Lucide icons" to "ISC License · Lucide contributors",
)

/** Settings > About: the version, the licenses, and putting every setting back to how it started. */
@Composable
fun AboutSection(hazeState: HazeState) {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
    var showLicenses by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("OK") } },
            title = { Text("Open-source licenses", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LICENSES.forEach { (what, license) -> SettingText(what, license) }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    if (MicState.running) {
                        MicState.running = false
                        ctx.stopService(Intent(ctx, MicService::class.java))
                    }
                    MicState.reset(ctx)
                    ctx.findActivity()?.recreate()      // every screen reads the settings again
                }) { Text("Reset", color = StateColors.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
            title = { Text("Reset all settings?", fontWeight = FontWeight.SemiBold) },
            text = { Text("Mic level, isolation, the way to connect, the pairing code, saved laptops and " +
                "everything in Settings go back to how they were on a new install.", fontSize = 15.sp) },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    GlassCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AboutRow("info", "Droidboard", if (version.isNotEmpty()) "Version $version" else null)
            SettingsDivider()
            AboutRow("file-text", "Open-source licenses", null, onClick = { showLicenses = true })
            SettingsDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Reset all settings") { confirmReset = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Reset all settings", fontSize = 16.sp, color = StateColors.Red)
            }
        }
    }
}

@Composable
private fun AboutRow(icon: String, title: String, subtitle: String?, onClick: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeckIcon(icon, c.onSurface.copy(0.75f), 20.dp)
        Spacer(Modifier.size(12.dp))
        if (subtitle != null) SettingText(title, subtitle, Modifier.weight(1f))
        else Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface, modifier = Modifier.weight(1f))
        if (onClick != null) Text("›", fontSize = 20.sp, color = c.onSurface.copy(0.5f))
    }
}
