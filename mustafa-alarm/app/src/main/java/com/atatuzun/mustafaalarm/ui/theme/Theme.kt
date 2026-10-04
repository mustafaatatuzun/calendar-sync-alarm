package com.atatuzun.mustafaalarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF0B1A33),
    secondary = Color(0xFF8AB4F8), onSecondary = Color(0xFF0B1A33),
    background = Color(0xFF0D1117), onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF0D1117), onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF4A5670), onSurfaceVariant = Color(0xFFE6E8EB),
    surfaceContainer = Color(0xFF1B2230),
    error = Color(0xFFB00000),
)

private val LightColors = lightColorScheme(primary = Color(0xFF1A56C4), secondary = Color(0xFF1A56C4))

@Composable
fun MustafaAlarmTheme(dark: Boolean = true, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
