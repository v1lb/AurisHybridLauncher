package com.aurishybrid.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurishybrid.launcher.apps.ResolvedSlot
import com.aurishybrid.launcher.data.ObdSnapshot
import com.aurishybrid.launcher.data.ObdState
import com.aurishybrid.launcher.data.VehicleData
import com.aurishybrid.launcher.ui.theme.AurisColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMMM", Locale.getDefault())

fun fmt(v: Float?, decimals: Int): String =
    v?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "N/A"

@Composable
fun HomeScreen(
    data: VehicleData,
    slots: List<ResolvedSlot>,
    autostartCountdown: Int?,
    onCancelAutostart: () -> Unit,
    onSlotClick: (ResolvedSlot) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(AurisColors.Navy, AurisColors.Night, AurisColors.Night)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            TopBar(data, onOpenSettings)
            HairLine()
            Row(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(0.24f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricTile("Скорость", fmt(data.speedKmh, 0), "км/ч", data.speedSource, Modifier.weight(1.2f), big = true)
                    MetricTile("Расход", fmt(data.fuelL100, 1), "л/100 км", fuelNote(data), Modifier.weight(1f))
                }
                EnergyMonitor(data, Modifier.weight(0.52f).fillMaxHeight())
                Column(Modifier.weight(0.24f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SocTile(data.socPct, data.socSource, Modifier.weight(1.1f))
                    MetricTile(
                        "Двигатель", data.obd.coolantC?.toString() ?: "N/A", "°C",
                        if (data.obd.coolantC != null) "OBD2 PID 05" else null, Modifier.weight(1f)
                    )
                    MetricTile(
                        "HV-батарея t°", fmt(data.hvBatteryTempC, 0), "°C",
                        data.hvBatteryTempSource ?: "нет проверенного Toyota-PID", Modifier.weight(1f)
                    )
                }
            }
            HairLine()
            Dock(slots, onSlotClick)
        }
        if (autostartCountdown != null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AurisColors.Graphite)
                    .border(1.dp, AurisColors.Blue, RoundedCornerShape(24.dp))
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hybrid Assistant запустится через $autostartCountdown с", color = AurisColors.Fg, fontSize = 15.sp)
                TextButton(onClick = onCancelAutostart) { Text("Отмена", color = AurisColors.Cyan) }
            }
        }
    }
}

private fun fuelNote(d: VehicleData): String? = when {
    d.obd.fuelRateLh == null -> null
    d.fuelL100 == null -> "стоянка: ${fmt(d.obd.fuelRateLh, 1)} л/ч"
    else -> d.obd.fuelRateSource
}

@Composable
private fun TopBar(data: VehicleData, onOpenSettings: () -> Unit) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(now.format(TIME_FMT), color = AurisColors.Fg, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(14.dp))
        Text(
            now.format(DATE_FMT).replaceFirstChar { it.titlecase(Locale.getDefault()) },
            color = AurisColors.FgDim, fontSize = 15.sp
        )
        Spacer(Modifier.weight(1f))
        Text("12V ", color = AurisColors.FgDim, fontSize = 13.sp)
        Text(
            data.voltage12?.let { fmt(it, 1) + " V" } ?: "N/A",
            color = voltageColor(data.voltage12), fontSize = 17.sp
        )
        Spacer(Modifier.width(20.dp))
        ObdIndicator(data.obd)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center
        ) { GlyphIcon(Glyph.GEAR, AurisColors.FgDim, Modifier.size(24.dp)) }
    }
}

private fun voltageColor(v: Float?): Color = when {
    v == null -> AurisColors.FgDim
    v < 11.8f || v > 15.0f -> AurisColors.Amber
    else -> AurisColors.Fg
}

fun ObdSnapshot.statusText(): String = when (state) {
    ObdState.DISABLED -> "OBD2 выкл."
    ObdState.IDLE -> "OBD2: не активен"
    ObdState.PAUSED -> "OBD2: проверка адаптера"
    ObdState.NO_PERMISSION -> "OBD2: нет разрешения"
    ObdState.NO_DEVICE -> "OBD2: адаптер не выбран"
    ObdState.CONNECTING -> "OBD2: подключение…"
    ObdState.RECONNECTING -> "OBD2: переподключение"
    ObdState.CONNECTED -> if (proxyClientActive) "OBD2 · общий с HA" else "OBD2 подключён"
    ObdState.ERROR -> "OBD2: ошибка"
}

@Composable
private fun ObdIndicator(obd: ObdSnapshot) {
    val c = when (obd.state) {
        ObdState.CONNECTED -> AurisColors.Green
        ObdState.CONNECTING, ObdState.RECONNECTING -> AurisColors.Amber
        ObdState.ERROR -> AurisColors.Red
        else -> AurisColors.FgDim
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(7.dp))
        Text(obd.statusText(), color = AurisColors.FgDim, fontSize = 13.sp)
    }
}

@Composable
private fun HairLine() {
    Box(
        Modifier.fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(listOf(Color.Transparent, AurisColors.Blue.copy(alpha = .7f), Color.Transparent))
        )
    )
}

@Composable
fun MetricTile(label: String, value: String, unit: String, source: String?, modifier: Modifier, big: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    val na = value == "N/A"
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AurisColors.Panel.copy(alpha = .9f))
            .border(1.dp, AurisColors.Line, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label.uppercase(), color = AurisColors.FgDim, fontSize = 11.sp, letterSpacing = 1.5.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, color = if (na) AurisColors.FgDim else AurisColors.Fg,
                fontSize = if (big) 54.sp else 30.sp, fontWeight = FontWeight.Light, maxLines = 1
            )
            if (!na) {
                Spacer(Modifier.width(6.dp))
                Text(unit, color = AurisColors.Cyan, fontSize = 14.sp, modifier = Modifier.padding(bottom = if (big) 10.dp else 5.dp))
            }
        }
        Text(
            source ?: "нет данных", color = AurisColors.FgDim.copy(alpha = .7f),
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** SOC в стиле заводского индикатора: 8 сегментов. */
@Composable
private fun SocTile(soc: Float?, source: String?, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val lit = soc?.let { ceil(it / 12.5f).toInt().coerceIn(0, 8) } ?: 0
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AurisColors.Panel.copy(alpha = .9f))
            .border(1.dp, AurisColors.Line, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("HV SOC", color = AurisColors.FgDim, fontSize = 11.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.weight(1f))
            Text(
                soc?.let { fmt(it, 0) + " %" } ?: "N/A",
                color = if (soc == null) AurisColors.FgDim else AurisColors.Fg, fontSize = 22.sp, fontWeight = FontWeight.Light
            )
        }
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until 8) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < lit) AurisColors.Cyan else AurisColors.Line)
                )
            }
        }
        Text(
            source ?: "PID 5B не поддерживается / нет связи",
            color = AurisColors.FgDim.copy(alpha = .7f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Dock(slots: List<ResolvedSlot>, onSlotClick: (ResolvedSlot) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(86.dp).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        slots.forEach { slot ->
            val available = slot.target != null
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(AurisColors.Graphite.copy(alpha = if (available) .9f else .4f))
                    .border(1.dp, if (available) AurisColors.Line else AurisColors.Line.copy(alpha = .4f), shape)
                    .clickable { onSlotClick(slot) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                GlyphIcon(slot.id.glyph(), if (available) AurisColors.Cyan else AurisColors.FgDim, Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (available) slot.id.title else "${slot.id.title}: выбрать",
                    color = if (available) AurisColors.Fg else AurisColors.FgDim,
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
