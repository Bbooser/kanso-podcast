package com.kanso.podcast.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

val Paper = Color(0xFFF5F1E8)
val Ink = Color(0xFF26342F)
val Moss = Color(0xFF657B6B)
val Clay = Color(0xFFCC6B4E)
val Sand = Color(0xFFE8E0D2)

enum class KansoPalette(val label: String, val accent: Color, val secondary: Color) {
    Tierra("Tierra", Color(0xFFCC6B4E), Color(0xFF657B6B)),
    Bosque("Bosque", Color(0xFF557565), Color(0xFFB4825D)),
    Oceano("Océano", Color(0xFF4A7292), Color(0xFF9A685B)),
    Ciruela("Ciruela", Color(0xFF76576C), Color(0xFF9A744F))
}

@Composable fun KansoTheme(dark: Boolean, palette: KansoPalette, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFE9E2D5), onPrimary = Color(0xFF202925), secondary = palette.accent,
        background = Color(0xFF171D1A), onBackground = Color(0xFFE9E2D5),
        surface = Color(0xFF171D1A), onSurface = Color(0xFFE9E2D5),
        surfaceVariant = Color(0xFF29312D), onSurfaceVariant = Color(0xFFD8D1C5), outline = Color(0xFF626A65)
    ) else lightColorScheme(
        primary = Ink, onPrimary = Paper, secondary = palette.accent,
        background = Paper, onBackground = Ink, surface = Paper,
        onSurface = Ink, surfaceVariant = Sand, outline = Ink.copy(alpha = .2f)
    )
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            displaySmall = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Serif),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif)
        ),
        content = content
    )
}
