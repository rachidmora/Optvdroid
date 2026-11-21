package com.atvplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Palette inspirée du skin OpenATV MetrixHD : panneaux noirs plats, sélection bleue unie. */
object Pal {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF161616)
    val SurfaceHi = Color(0xFF2B2B2B)
    val Accent = Color(0xFF1E88C7)
    val Accent2 = Accent
    val Text = Color(0xFFFFFFFF)
    val Muted = Color(0xFF9FB4C4)
    val Panel = Color(0xC7000000)       // noir ~78 %
    val PanelDark = Color(0xE0000000)   // noir ~88 %
    val Glass = Color(0xE6000000)
    val Divider = Color(0x14FFFFFF)
    val AccentBrush = Brush.horizontalGradient(listOf(Accent, Accent))
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
