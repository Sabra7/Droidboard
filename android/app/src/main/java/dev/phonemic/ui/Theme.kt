package dev.phonemic.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Pearl = Color(0xFFF5F2EC)
val PureBlack = Color(0xFF000000)

val LocalIsDark = staticCompositionLocalOf { true }

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF3A5BA9), tertiary = Color(0xFF7B4FA0),
    onBackground = Color(0xFF1B1B1F), onSurface = Color(0xFF1B1B1F),
)
private val FallbackDark = darkColorScheme(
    primary = Color(0xFFAFC6FF), tertiary = Color(0xFFDDB8FF),
    onBackground = Color(0xFFEDEDF2), onSurface = Color(0xFFEDEDF2),
)

/**
 * Follows the system light/dark setting live. Accent colors come from Material You
 * (Android 12+); the base is pure black in dark mode and pearl white in light mode.
 */
@Composable
fun DroidboardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base: ColorScheme = if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) FallbackDark else FallbackLight
    }
    val scheme = base.copy(
        background = if (dark) PureBlack else Pearl,
        surface = if (dark) Color(0xFF1C1C1E) else Color.White,
        onBackground = if (dark) Color(0xFFF2F2F5) else Color(0xFF1B1B1F),
        onSurface = if (dark) Color(0xFFF2F2F5) else Color(0xFF1B1B1F),
    )
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The state colours every screen shares: live (and the dark text on it), a problem or muted, waiting. */
object StateColors {
    val Live = Color(0xFF39D353)
    val OnLive = Color(0xFF03210B)
    val Red = Color(0xFFFF6B5E)
    val Wait = Color(0xFFFFB020)
}
