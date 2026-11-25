package com.atvplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Palette « app DVB pro » : fond nuit, verre sombre, accent cyan → indigo. */
object Pal {
    val Bg = Color(0xFF06080E)
    val Surface = Color(0xFF0F1320)
    val SurfaceHi = Color(0xFF1A2236)
    val Accent = Color(0xFF22D3EE)
    val Accent2 = Color(0xFF6366F1)
    val Amber = Color(0xFFFBBF24)
    val Live = Color(0xFFEF4444)
    val Text = Color(0xFFF4F7FF)
    val Muted = Color(0xFF8B95AD)
    val Panel = Color(0xB80C101C)
    val PanelDark = Color(0xE006080E)
    val Glass = Color(0xD00C101C)
    val Divider = Color(0x14FFFFFF)
    val AccentBrush = Brush.horizontalGradient(listOf(Accent, Accent2))
    val TileBrush = Brush.linearGradient(listOf(Color(0xFF1E2A48), Color(0xFF0F1626)))
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
