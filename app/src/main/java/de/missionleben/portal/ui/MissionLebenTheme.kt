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
    primary = Color(0xFFFF8B93),
    onPrimary = Color(0xFF48000B),
    primaryContainer = Color(0xFF651321),
    onPrimaryContainer = Color(0xFFFFD9DC),
    secondary = Color(0xFFDCC4E7),
    onSecondary = Color(0xFF3B2D42),
    secondaryContainer = Color(0xFF53445B),
    onSecondaryContainer = Color(0xFFF9E8FF),
    background = Color(0xFF141117),
    onBackground = Color(0xFFF0EAF1),
    surface = Color(0xFF1E1922),
    onSurface = Color(0xFFF0EAF1),
    surfaceVariant = Color(0xFF2B2430),
    onSurfaceVariant = Color(0xFFD0C5D3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF9A8F9D),
)

@Composable
fun MissionLebenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
