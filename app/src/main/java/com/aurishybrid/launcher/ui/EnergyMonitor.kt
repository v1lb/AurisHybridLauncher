package com.aurishybrid.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurishybrid.launcher.data.BatteryFlow
import com.aurishybrid.launcher.data.VehicleData
import com.aurishybrid.launcher.ui.theme.AurisColors
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * Режим определяется только по реальным данным: обороты ДВС и скорость (OBD2/GPS),
 * направление тока HV-батареи — только по проверенному Toyota-параметру HV_CURRENT.
 * Если направление тока неизвестно, стрелки батареи не рисуются (без имитации).
 */
enum class FlowMode(val title: String) {
    NO_DATA("Нет данных OBD2"),
    STOPPED("Стоянка"),
    ENGINE_IDLE("Стоянка · ДВС работает"),
    EV("EV-режим"),
    REGEN("Рекуперация"),
    ENGINE("Движение на ДВС"),
    HYBRID("Гибридный режим"),
    ENGINE_CHARGE("ДВС · заряд батареи"),
}

fun inferFlowMode(rpm: Int?, speedKmh: Float?, battery: BatteryFlow = BatteryFlow.UNKNOWN): FlowMode {
    if (rpm == null || speedKmh == null) return FlowMode.NO_DATA
    val engineOn = rpm >= 100
    val moving = speedKmh >= 1f
    return when {
        !moving && !engineOn -> FlowMode.STOPPED
        !moving -> if (battery == BatteryFlow.CHARGE) FlowMode.ENGINE_CHARGE else FlowMode.ENGINE_IDLE
        !engineOn -> if (battery == BatteryFlow.CHARGE) FlowMode.REGEN else FlowMode.EV
        battery == BatteryFlow.DISCHARGE -> FlowMode.HYBRID
        battery == BatteryFlow.CHARGE -> FlowMode.ENGINE_CHARGE
        else -> FlowMode.ENGINE
    }
}

private fun modeColor(mode: FlowMode): Color = when (mode) {
    FlowMode.NO_DATA, FlowMode.STOPPED -> AurisColors.FgDim
    FlowMode.EV -> AurisColors.Cyan
    FlowMode.REGEN -> AurisColors.Green
    else -> AurisColors.Amber
}

/** Монитор энергии: вид автомобиля сверху (передний привод), ДВС и мотор спереди, HV-батарея сзади. */
@Composable
fun EnergyMonitor(data: VehicleData, modifier: Modifier = Modifier, showFooter: Boolean = true) {
    val battery = data.batteryFlow
    val mode = inferFlowMode(data.obd.rpm, data.speedKmh, battery)
    val soc = data.socPct
    val transition = rememberInfiniteTransition(label = "energy")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
        label = "phase"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse"
    )
    val measurer = rememberTextMeasurer()

    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Монитор энергии")
            Spacer(Modifier.weight(1f))
            Text(mode.title, color = modeColor(mode), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp)) {
            // Единица вёрстки: машина занимает прямоугольник 2.2 × 1.0.
            val u = min(size.width / 2.2f, size.height / 1.0f)
            val ox = (size.width - 2.2f * u) / 2f
            val oy = (size.height - u) / 2f
            fun p(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
            fun sz(w: Float, h: Float) = Size(w * u, h * u)
            val thin = 2.dp.toPx()

            val engineOn = mode in setOf(FlowMode.ENGINE, FlowMode.HYBRID, FlowMode.ENGINE_CHARGE, FlowMode.ENGINE_IDLE)
            val moving = mode in setOf(FlowMode.EV, FlowMode.REGEN, FlowMode.ENGINE, FlowMode.HYBRID) ||
                (mode == FlowMode.ENGINE_CHARGE && (data.speedKmh ?: 0f) >= 1f)

            // --- кузов и колёса
            drawRoundRect(AurisColors.Navy, p(.10f, .14f), sz(2.0f, .72f), CornerRadius(.30f * u))
            drawRoundRect(AurisColors.Line, p(.10f, .14f), sz(2.0f, .72f), CornerRadius(.30f * u), style = Stroke(thin))
            // капот и стёкла — декоративные линии
            drawLine(AurisColors.Line, p(.62f, .20f), p(.62f, .80f), strokeWidth = thin)
            drawLine(AurisColors.Line, p(1.36f, .22f), p(1.36f, .78f), strokeWidth = thin)
            val wheelColor = if (moving) AurisColors.Fg else AurisColors.FgDim
            for (wx in listOf(.66f, 1.57f)) for (wy in listOf(.02f, .86f)) {
                drawRoundRect(AurisColors.Graphite, p(wx, wy), sz(.28f, .12f), CornerRadius(.04f * u))
                drawRoundRect(wheelColor, p(wx, wy), sz(.28f, .12f), CornerRadius(.04f * u), style = Stroke(thin))
            }

            val engineOut = p(.54f, .50f)
            val motorC = p(.80f, .50f)
            val motorR = .12f * u
            val motorL = Offset(motorC.x - motorR, motorC.y)
            val motorRgt = Offset(motorC.x + motorR, motorC.y)
            val battIn = p(1.48f, .50f)
            val wheelTop = p(.80f, .14f)
            val wheelBot = p(.80f, .86f)
            val motorTop = Offset(motorC.x, motorC.y - motorR)
            val motorBot = Offset(motorC.x, motorC.y + motorR)

            // --- неактивные магистрали
            val base = 5.dp.toPx()
            for ((a, b) in listOf(engineOut to motorL, motorRgt to battIn, motorTop to wheelTop, motorBot to wheelBot)) {
                drawLine(AurisColors.Line, a, b, strokeWidth = base, cap = StrokeCap.Round)
            }
            val dash = 16.dp.toPx()
            val gap = 11.dp.toPx()
            /** Бегущие штрихи от a к b. */
            fun flow(a: Offset, b: Offset, color: Color) = drawLine(
                color = color, start = a, end = b, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), (1f - phase) * (dash + gap))
            )
            /** Свечение без направления: энергия идёт, но направление не измерено. */
            fun glow(a: Offset, b: Offset, color: Color) = drawLine(
                color = color.copy(alpha = pulse), start = a, end = b, strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round
            )

            // --- ДВС → мотор-генератор
            when {
                engineOn && moving -> flow(engineOut, motorL, AurisColors.Amber)
                engineOn -> glow(engineOut, motorL, AurisColors.Amber)
            }
            // --- мотор ↔ колёса
            when (mode) {
                FlowMode.REGEN -> { flow(wheelTop, motorTop, AurisColors.Green); flow(wheelBot, motorBot, AurisColors.Green) }
                FlowMode.EV -> if (battery == BatteryFlow.DISCHARGE) {
                    flow(motorTop, wheelTop, AurisColors.Blue); flow(motorBot, wheelBot, AurisColors.Blue)
                } else {
                    glow(motorTop, wheelTop, AurisColors.Blue); glow(motorBot, wheelBot, AurisColors.Blue)
                }
                FlowMode.ENGINE, FlowMode.HYBRID -> { flow(motorTop, wheelTop, AurisColors.Amber); flow(motorBot, wheelBot, AurisColors.Amber) }
                FlowMode.ENGINE_CHARGE -> if (moving) { flow(motorTop, wheelTop, AurisColors.Amber); flow(motorBot, wheelBot, AurisColors.Amber) }
                else -> Unit
            }
            // --- мотор ↔ HV-батарея (направление только по проверенному току)
            when (battery) {
                BatteryFlow.DISCHARGE -> flow(battIn, motorRgt, AurisColors.Blue)
                BatteryFlow.CHARGE -> flow(motorRgt, battIn, AurisColors.Green)
                BatteryFlow.UNKNOWN -> if (mode == FlowMode.EV) glow(motorRgt, battIn, AurisColors.Blue)
                BatteryFlow.IDLE -> Unit
            }

            // --- ДВС
            val engColor = if (engineOn) AurisColors.Amber else AurisColors.FgDim
            drawRoundRect(AurisColors.Graphite, p(.20f, .33f), sz(.34f, .34f), CornerRadius(.05f * u))
            drawRoundRect(engColor, p(.20f, .33f), sz(.34f, .34f), CornerRadius(.05f * u), style = Stroke(3.dp.toPx()))
            for (i in 0 until 4) {
                val y = .39f + i * .073f
                drawLine(engColor.copy(alpha = .7f), p(.26f, y), p(.48f, y), strokeWidth = thin, cap = StrokeCap.Round)
            }
            // --- мотор-генератор
            val motorActive = moving || battery == BatteryFlow.CHARGE || battery == BatteryFlow.DISCHARGE
            val motorColor = if (motorActive) AurisColors.Cyan else AurisColors.FgDim
            drawCircle(AurisColors.Graphite, motorR, motorC)
            drawCircle(motorColor, motorR, motorC, style = Stroke(3.dp.toPx()))
            drawCircle(motorColor, motorR * .4f, motorC, style = Stroke(thin))
            // --- HV-батарея с уровнем заряда
            val battColor = when (battery) {
                BatteryFlow.CHARGE -> AurisColors.Green
                BatteryFlow.DISCHARGE -> AurisColors.Blue
                else -> if (soc != null) AurisColors.Cyan else AurisColors.FgDim
            }
            drawRoundRect(AurisColors.Graphite, p(1.48f, .32f), sz(.46f, .36f), CornerRadius(.05f * u))
            drawRoundRect(battColor, p(1.48f, .32f), sz(.46f, .36f), CornerRadius(.05f * u), style = Stroke(3.dp.toPx()))
            val lit = soc?.let { ceil(it / 12.5f).toInt().coerceIn(0, 8) } ?: 0
            for (i in 0 until 8) {
                val x = 1.515f + i * .0495f
                drawRoundRect(
                    if (i < lit) battColor else AurisColors.Line,
                    p(x, .37f), sz(.036f, .26f), CornerRadius(.008f * u)
                )
            }

            // --- подписи
            val label = TextStyle(color = AurisColors.FgDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            fun caption(text: String, x: Float, y: Float, style: TextStyle = label) {
                val layout = measurer.measure(text, style)
                val at = p(x, y)
                drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y))
            }
            caption("ДВС", .37f, .69f)
            caption("МОТОР", 1.03f, .56f)
            caption(
                soc?.let { "HV " + String.format(Locale.US, "%.0f", it) + " %" } ?: "HV-БАТАРЕЯ",
                1.71f, .70f,
                if (soc != null) label.copy(color = AurisColors.Fg, fontSize = 14.sp) else label
            )
        }
        if (showFooter) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend(AurisColors.Amber, "ДВС")
                Spacer(Modifier.width(14.dp))
                Legend(AurisColors.Blue, "Электротяга")
                Spacer(Modifier.width(14.dp))
                Legend(AurisColors.Green, "Заряд")
                Spacer(Modifier.weight(1f))
                Text(
                    if (soc == null) "SOC: нет данных" else if (data.hvCurrentA == null) "ток HV: нет проверенного PID" else "",
                    color = AurisColors.FgDim.copy(alpha = .8f), fontSize = 11.sp, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp, 6.dp)) {
            drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
        Spacer(Modifier.width(6.dp))
        Text(text, color = AurisColors.FgDim, fontSize = 11.sp, maxLines = 1)
    }
}
