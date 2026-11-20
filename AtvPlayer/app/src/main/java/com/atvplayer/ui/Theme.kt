package com.atvplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Pal {
    val Bg = Color(0xFF0B0F1A)
    val Surface = Color(0xFF141B2D)
    val SurfaceHi = Color(0xFF1C2540)
    val Accent = Color(0xFF3DA9FC)
    val Accent2 = Color(0xFF7C5CFF)
    val Text = Color(0xFFEAF0FF)
    val Muted = Color(0xFF8E9BB8)
    val Glass = Color(0xE6101728)
    val AccentBrush = Brush.horizontalGradient(listOf(Accent, Accent2))
}

@Composable
fun AtvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Accent, background = Pal.Bg, surface = Pal.Surface,
            onSurface = Pal.Text, onBackground = Pal.Text,
        ),
        content = content,
    )
}
