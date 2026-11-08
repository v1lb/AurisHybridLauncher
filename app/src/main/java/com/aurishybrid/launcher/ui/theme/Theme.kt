package com.aurishybrid.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Палитра в духе заводских систем Toyota конца 2000-х: navy/графит, синие и голубые акценты. */
object AurisColors {
    val Night = Color(0xFF050B16)
    val Navy = Color(0xFF0B1C38)
    val Graphite = Color(0xFF1A2029)
    val Panel = Color(0xFF0D1828)
    val Line = Color(0xFF243652)
    val Blue = Color(0xFF1F6FEB)
    val Cyan = Color(0xFF52C7F5)
    val Fg = Color(0xFFF2F5FA)
    val FgDim = Color(0xFF8593AA)
    val Amber = Color(0xFFF2B33D)
    val Green = Color(0xFF5BD68A)
    val Red = Color(0xFFE5604F)
}

@Composable
fun AurisTheme(content: @Composable () -> Unit) {
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
        ),
        content = content
    )
}
