package de.missionleben.portal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF1D1726)
val Coral = Color(0xFFFD4B5A)
val CoralDark = Color(0xFFD62E43)
val Lavender = Color(0xFFF5EFF8)
val Canvas = Color(0xFFFCF9FD)
val Success = Color(0xFF188A62)

private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDADD),
    onPrimaryContainer = Color(0xFF5A0713),
    secondary = Ink,
    onSecondary = Color.White,
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Lavender,
    onSurfaceVariant = Color(0xFF62586B),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8B92),
    onPrimary = Color(0xFF630A18),
    secondary = Color(0xFFE9DDF0),
    background = Color(0xFF17121D),
    surface = Color(0xFF211A29),
    surfaceVariant = Color(0xFF30263A),
)

@Composable
fun MissionLebenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
