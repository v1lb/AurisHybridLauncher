package com.aurishybrid.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import kotlin.math.min

/**
 * Палитра в духе современных мультимедийных систем: почти чёрный фон, тёмно-серые карточки,
 * белый текст, синий акцент. Потоки энергии: ДВС — янтарный, электротяга — синий, заряд — зелёный.
 */
object AurisColors {
    val Night = Color(0xFF07080A)
    val Navy = Color(0xFF12151B)
    val Graphite = Color(0xFF23262D)
    val Panel = Color(0xFF16181D)
    val Line = Color(0xFF30343D)
    val Blue = Color(0xFF1E88FF)
    val Cyan = Color(0xFF5BB8FF)
    val Fg = Color(0xFFFFFFFF)
    val FgDim = Color(0xFFA0A7B4)
    val Amber = Color(0xFFFFA726)
    val Green = Color(0xFF3DDC84)
    val Red = Color(0xFFFF5449)
}

/** Интерфейс свёрстан под «виртуальный» экран высотой 600 dp — независимо от DPI магнитолы. */
private const val VIRTUAL_HEIGHT_DP = 600f
private const val FONT_SCALE = 1.1f

@Composable
fun AurisTheme(uiScale: Float = 1f, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val cfg = LocalConfiguration.current
    val shortSidePx = min(cfg.screenWidthDp, cfg.screenHeightDp) * base.density
    val density = (shortSidePx / VIRTUAL_HEIGHT_DP * uiScale).coerceIn(0.5f, 6f)
    CompositionLocalProvider(LocalDensity provides Density(density, FONT_SCALE)) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = AurisColors.Blue,
                onPrimary = Color.White,
                secondary = AurisColors.Cyan,
                background = AurisColors.Night,
                onBackground = AurisColors.Fg,
                surface = AurisColors.Panel,
                onSurface = AurisColors.Fg,
                surfaceVariant = AurisColors.Graphite,
                onSurfaceVariant = AurisColors.FgDim,
                outline = AurisColors.Line,
            )
        ) {
            // MaterialTheme сам задаёт bodyLarge (Normal), поэтому вес переопределяется внутри него.
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.Medium), content)
        }
    }
}
