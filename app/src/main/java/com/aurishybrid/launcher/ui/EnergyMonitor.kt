package com.aurishybrid.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurishybrid.launcher.data.BatteryFlow
import com.aurishybrid.launcher.data.VehicleData
import com.aurishybrid.launcher.ui.theme.AurisColors
import kotlin.math.min

/**
 * Режим определяется ТОЛЬКО по реальным RPM (OBD2) и скорости (OBD2/GPS).
 * Направление тока HV-батареи (заряд/разряд) стандартными PID не доступно,
 * поэтому стрелки заряда не рисуются — без имитации.
 */
enum class FlowMode(val title: String) {
    NO_DATA("Нет данных OBD2"),
    STOPPED("Стоянка · ДВС выключен"),
    ENGINE_IDLE("Стоянка · ДВС работает"),
    EV("Движение · ДВС выключен (EV / выбег)"),
    ENGINE("Движение · ДВС работает"),
}

fun inferFlowMode(rpm: Int?, speedKmh: Float?): FlowMode = when {
    rpm == null || speedKmh == null -> FlowMode.NO_DATA
    speedKmh < 1f && rpm < 100 -> FlowMode.STOPPED
    speedKmh < 1f -> FlowMode.ENGINE_IDLE
    rpm < 100 -> FlowMode.EV
    else -> FlowMode.ENGINE
}

@Composable
fun EnergyMonitor(data: VehicleData, modifier: Modifier = Modifier) {
    val mode = inferFlowMode(data.obd.rpm, data.speedKmh)
    val battery = data.batteryFlow
    val transition = rememberInfiniteTransition(label = "energy")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "phase"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse"
    )
    val measurer = rememberTextMeasurer()
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(AurisColors.Navy, AurisColors.Panel)))
            .border(1.dp, AurisColors.Line, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ENERGY MONITOR", color = AurisColors.FgDim, fontSize = 12.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(
                mode.title,
                color = if (mode == FlowMode.NO_DATA) AurisColors.FgDim else AurisColors.Cyan,
                fontSize = 13.sp
            )
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val w = size.width
            val h = size.height
            val r = min(w, h) * 0.11f
            val engine = Offset(w * .17f, h * .27f)
            val batteryPos = Offset(w * .17f, h * .76f)
            val hub = Offset(w * .48f, h * .52f)
            val wheels = Offset(w * .80f, h * .55f)

            val moving = mode == FlowMode.EV || mode == FlowMode.ENGINE
            val engineOn = mode == FlowMode.ENGINE || mode == FlowMode.ENGINE_IDLE

            // Базовые (неактивные) линии
            listOf(engine to hub, batteryPos to hub, hub to wheels).forEach { (a, b) ->
                drawLine(AurisColors.Line, a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            val dash = 14.dp.toPx()
            val gap = 10.dp.toPx()
            fun flow(a: Offset, b: Offset) = drawLine(
                color = AurisColors.Cyan, start = a, end = b,
                strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), (1f - phase) * (dash + gap))
            )
            fun glow(a: Offset, b: Offset) = drawLine(
                color = AurisColors.Blue.copy(alpha = pulse), start = a, end = b,
                strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round
            )
            when (mode) {
                FlowMode.ENGINE -> { flow(engine, hub); flow(hub, wheels) }
                FlowMode.EV -> if (battery == BatteryFlow.UNKNOWN) glow(hub, wheels) else flow(hub, wheels)
                FlowMode.ENGINE_IDLE -> glow(engine, hub)
                else -> Unit
            }
            // Направление тока HV-батареи — только по проверенному Toyota-параметру HV_CURRENT.
            when (battery) {
                BatteryFlow.DISCHARGE -> flow(batteryPos, hub)
                BatteryFlow.CHARGE -> flow(hub, batteryPos)
                BatteryFlow.UNKNOWN -> if (mode == FlowMode.EV) glow(batteryPos, hub)
                BatteryFlow.IDLE -> Unit
            }

            val label = TextStyle(color = AurisColors.FgDim, fontSize = 12.sp)
            fun caption(text: String, at: Offset) {
                val layout = measurer.measure(text, label)
                drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y))
            }

            node(engine, r, engineOn) { c, col -> engineGlyph(c, r, col) }
            caption("ДВС", Offset(engine.x, engine.y + r + 6.dp.toPx()))
            node(batteryPos, r, mode == FlowMode.EV || battery == BatteryFlow.CHARGE || battery == BatteryFlow.DISCHARGE) { c, col -> batteryGlyph(c, r, col) }
            caption("HV-БАТАРЕЯ", Offset(batteryPos.x, batteryPos.y + r + 6.dp.toPx()))
            node(hub, r * 0.8f, moving) { c, col ->
                drawCircle(color = col, radius = r * .35f, center = c, style = Stroke(2.dp.toPx()))
            }
            caption("МОТОР / PSD", Offset(hub.x, hub.y + r * .8f + 6.dp.toPx()))
            carGlyph(wheels, r, if (moving) AurisColors.Cyan else AurisColors.FgDim)
            caption("КОЛЁСА", Offset(wheels.x, wheels.y + r * .9f))
        }
        Text(
            "RPM: ${data.obd.rpm ?: "N/A"}   ·   ток HV: " +
                (data.hvCurrentA?.let { String.format(java.util.Locale.US, "%.1f A", it) } ?: "нет проверенного PID") +
                when (battery) {
                    BatteryFlow.CHARGE -> " (заряд)"
                    BatteryFlow.DISCHARGE -> " (разряд)"
                    else -> ""
                },
            color = AurisColors.FgDim.copy(alpha = .7f), fontSize = 10.sp, maxLines = 1
        )
    }
}

private fun DrawScope.node(c: Offset, r: Float, active: Boolean, inner: DrawScope.(Offset, Color) -> Unit) {
    val col = if (active) AurisColors.Cyan else AurisColors.FgDim
    drawCircle(color = AurisColors.Night, radius = r, center = c)
    drawCircle(color = if (active) AurisColors.Blue else AurisColors.Line, radius = r, center = c, style = Stroke(2.dp.toPx()))
    inner(c, col)
}

private fun DrawScope.engineGlyph(c: Offset, r: Float, col: Color) {
    val s = Stroke(2.dp.toPx())
    drawRoundRect(col, Offset(c.x - r * .5f, c.y - r * .2f), Size(r, r * .6f), CornerRadius(r * .08f), style = s)
    for (i in 0 until 3) {
        val x = c.x - r * .3f + i * r * .3f
        drawLine(col, Offset(x, c.y - r * .2f), Offset(x, c.y - r * .45f), strokeWidth = 2.dp.toPx())
    }
}

private fun DrawScope.batteryGlyph(c: Offset, r: Float, col: Color) {
    val s = Stroke(2.dp.toPx())
    drawRoundRect(col, Offset(c.x - r * .55f, c.y - r * .3f), Size(r * 1.1f, r * .65f), CornerRadius(r * .06f), style = s)
    drawLine(col, Offset(c.x - r * .3f, c.y - r * .3f), Offset(c.x - r * .3f, c.y - r * .42f), strokeWidth = 3.dp.toPx())
    drawLine(col, Offset(c.x + r * .3f, c.y - r * .3f), Offset(c.x + r * .3f, c.y - r * .42f), strokeWidth = 3.dp.toPx())
}

private fun DrawScope.carGlyph(c: Offset, r: Float, col: Color) {
    val s = Stroke(2.dp.toPx())
    val body = Path().apply {
        moveTo(c.x - r * 1.5f, c.y + r * .25f)
        lineTo(c.x - r * 1.5f, c.y - r * .1f)
        lineTo(c.x - r * .9f, c.y - r * .2f)
        lineTo(c.x - r * .45f, c.y - r * .65f)
        lineTo(c.x + r * .55f, c.y - r * .65f)
        lineTo(c.x + r * 1.0f, c.y - r * .15f)
        lineTo(c.x + r * 1.5f, c.y - r * .05f)
        lineTo(c.x + r * 1.5f, c.y + r * .25f)
        close()
    }
    drawPath(body, col, style = s)
    drawCircle(color = AurisColors.Night, radius = r * .3f, center = Offset(c.x - r * .85f, c.y + r * .3f))
    drawCircle(color = col, radius = r * .3f, center = Offset(c.x - r * .85f, c.y + r * .3f), style = s)
    drawCircle(color = AurisColors.Night, radius = r * .3f, center = Offset(c.x + r * .85f, c.y + r * .3f))
    drawCircle(color = col, radius = r * .3f, center = Offset(c.x + r * .85f, c.y + r * .3f), style = s)
}
