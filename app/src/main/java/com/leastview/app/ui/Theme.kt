package com.leastview.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Drive-style palette: near-black canvas in dark mode, white pages on top of it.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF2B4A80),
    onPrimaryContainer = Color(0xFFD3E3FD),
    background = Color(0xFF131416),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131416),
    onSurface = Color(0xFFE3E3E3),
    onSurfaceVariant = Color(0xFF9AA0A6),
    surfaceVariant = Color(0xFF28292C),
    outlineVariant = Color(0xFF3C4043),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    background = Color(0xFFF8F9FA),
    surface = Color(0xFFF8F9FA),
    surfaceVariant = Color(0xFFE8EAED),
)

val PdfRed = Color(0xFFEA4335)
val DocBlue = Color(0xFF4285F4)
val SheetGreen = Color(0xFF0F9D58)
val SlideOrange = Color(0xFFF4B400)

@Composable
fun LeastViewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
