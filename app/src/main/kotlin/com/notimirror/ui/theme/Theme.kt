package com.notimirror.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6EC6FF),
    onPrimary = Color(0xFF003E5C),
    primaryContainer = Color(0xFF004B70),
    secondary = Color(0xFFB1CBE0),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF1F2937),
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
    outline = Color(0xFF30363D),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0078A0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE5F5),
    secondary = Color(0xFF4A6E85),
    background = Color(0xFFF6F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F4F8),
    onBackground = Color(0xFF1C2128),
    onSurface = Color(0xFF1C2128),
    outline = Color(0xFFD0D7DE),
)

@Composable
fun NotiMirrorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
