package com.aurishybrid.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.aurishybrid.launcher.apps.SlotId
import kotlin.math.cos
import kotlin.math.sin

/** Собственные минималистичные линейные иконки (без чужих логотипов и без тяжёлых icon-библиотек). */
enum class Glyph { HYBRID, NAV, PIN, RADIO, BLUETOOTH, MUSIC, GEAR }

fun SlotId.glyph(): Glyph = when (this) {
    SlotId.HYBRID -> Glyph.HYBRID
    SlotId.WAZE -> Glyph.NAV
    SlotId.MAPS -> Glyph.PIN
    SlotId.RADIO -> Glyph.RADIO
    SlotId.BLUETOOTH -> Glyph.BLUETOOTH
    SlotId.MUSIC -> Glyph.MUSIC
    SlotId.SETTINGS -> Glyph.GEAR
}

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.07f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * w, y * h)
        fun line(a: Offset, b: Offset, k: Float = 1f) =
            drawLine(color = color, start = a, end = b, strokeWidth = sw * k, cap = StrokeCap.Round)

        when (glyph) {
            Glyph.HYBRID -> {
                drawCircle(color = color, radius = w * 0.42f, center = center, style = stroke)
                line(p(.37f, .30f), p(.37f, .70f))
                line(p(.63f, .30f), p(.63f, .70f))
                line(p(.37f, .50f), p(.63f, .50f))
            }
            Glyph.NAV -> {
                val path = Path().apply {
                    moveTo(.5f * w, .1f * h); lineTo(.85f * w, .9f * h)
                    lineTo(.5f * w, .7f * h); lineTo(.15f * w, .9f * h); close()
                }
                drawPath(path = path, color = color, style = stroke)
            }
            Glyph.PIN -> {
                drawCircle(color = color, radius = w * .13f, center = p(.5f, .38f), style = stroke)
                val path = Path().apply {
                    moveTo(.5f * w, .92f * h)
                    cubicTo(.12f * w, .55f * h, .2f * w, .08f * h, .5f * w, .08f * h)
                    cubicTo(.8f * w, .08f * h, .88f * w, .55f * h, .5f * w, .92f * h)
                    close()
                }
                drawPath(path = path, color = color, style = stroke)
            }
            Glyph.RADIO -> {
                drawRoundRect(
                    color = color, topLeft = p(.1f, .36f), size = Size(.8f * w, .52f * h),
                    cornerRadius = CornerRadius(w * .08f), style = stroke
                )
                line(p(.25f, .36f), p(.75f, .12f))
                drawCircle(color = color, radius = w * .11f, center = p(.34f, .62f), style = stroke)
                line(p(.58f, .54f), p(.78f, .54f))
                line(p(.58f, .70f), p(.78f, .70f))
            }
            Glyph.BLUETOOTH -> {
                val path = Path().apply {
                    moveTo(.3f * w, .3f * h); lineTo(.7f * w, .68f * h); lineTo(.5f * w, .9f * h)
                    lineTo(.5f * w, .1f * h); lineTo(.7f * w, .32f * h); lineTo(.3f * w, .7f * h)
                }
                drawPath(path = path, color = color, style = stroke)
            }
            Glyph.MUSIC -> {
                drawCircle(color = color, radius = w * .12f, center = p(.30f, .76f))
                drawCircle(color = color, radius = w * .12f, center = p(.72f, .66f))
                line(p(.42f, .76f), p(.42f, .20f))
                line(p(.84f, .66f), p(.84f, .12f))
                line(p(.42f, .20f), p(.84f, .12f), 1.5f)
            }
            Glyph.GEAR -> {
                drawCircle(color = color, radius = w * .15f, center = center, style = stroke)
                drawCircle(color = color, radius = w * .29f, center = center, style = stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = cos(a).toFloat()
                    val s = sin(a).toFloat()
                    line(
                        Offset(center.x + c * w * .29f, center.y + s * w * .29f),
                        Offset(center.x + c * w * .43f, center.y + s * w * .43f),
                        1.6f
                    )
                }
            }
        }
    }
}
