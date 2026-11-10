package com.aurishybrid.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurishybrid.launcher.apps.ResolvedSlot
import com.aurishybrid.launcher.data.MinuteBar
import com.aurishybrid.launcher.data.TripState
import com.aurishybrid.launcher.data.TripStats
import com.aurishybrid.launcher.data.VehicleData
import com.aurishybrid.launcher.media.MediaState
import com.aurishybrid.launcher.ui.theme.AurisColors
import java.util.Locale

fun fmtKm(km: Double): String = String.format(Locale.US, if (km < 100) "%.1f" else "%.0f", km)

fun fmtDuration(sec: Double): String {
    val m = (sec / 60).toInt()
    return if (m < 60) "$m мин" else "${m / 60} ч ${m % 60} мин"
}

@Composable
fun HomeScreen(
    data: VehicleData,
    trip: TripState,
    media: MediaState,
    mediaActions: MediaActions,
    shortcuts: List<ResolvedSlot>,
    autostartCountdown: Int?,
    onCancelAutostart: () -> Unit,
    onSlotClick: (ResolvedSlot) -> Unit,
    onOpenEnergy: () -> Unit,
    onOpenTrip: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EnergyMonitor(data, Modifier.weight(0.60f).fillMaxHeight().card(onOpenEnergy))
                Column(Modifier.weight(0.40f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MediaCard(media, mediaActions, Modifier.fillMaxWidth().height(118.dp))
                    TripCard(trip.current, Modifier.fillMaxWidth().weight(1f).card(onOpenTrip))
                }
            }
            Row(Modifier.fillMaxWidth().height(94.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Скорость", fmt(data.speedKmh, 0), "км/ч", data.speedSource, Modifier.weight(1f))
                MetricTile("Расход", fmt(data.fuelL100, 1), "л/100", fuelNote(data), Modifier.weight(1f))
                MetricTile(
                    "ДВС", data.obd.coolantC?.toString() ?: NA, "°C",
                    if (data.obd.coolantC != null) "OBD2 PID 05" else null, Modifier.weight(1f)
                )
                MetricTile(
                    "HV-батарея", fmt(data.hvBatteryTempC, 0), "°C",
                    data.hvBatteryTempSource ?: "нет проверенного PID", Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                shortcuts.forEach { slot -> ShortcutTile(slot, Modifier.weight(1f)) { onSlotClick(slot) } }
            }
        }
        if (autostartCountdown != null) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 108.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(AurisColors.Graphite)
                    .border(1.dp, AurisColors.Blue, RoundedCornerShape(28.dp))
                    .padding(start = 22.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hybrid Assistant запустится через $autostartCountdown с", color = AurisColors.Fg, fontSize = 16.sp)
                TextButton(onClick = onCancelAutostart) { Text("Отмена", color = AurisColors.Cyan, fontSize = 16.sp) }
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
fun MetricTile(label: String, value: String, unit: String, source: String?, modifier: Modifier) {
    val na = value == NA
    Column(
        modifier.fillMaxHeight().card().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        CardTitle(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, color = if (na) AurisColors.FgDim else AurisColors.Fg,
                fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1
            )
            if (!na) {
                Spacer(Modifier.width(6.dp))
                Text(unit, color = AurisColors.Cyan, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Text(
            source ?: "нет данных", color = AurisColors.FgDim.copy(alpha = .75f),
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ShortcutTile(slot: ResolvedSlot, modifier: Modifier, onClick: () -> Unit) {
    val available = slot.target != null
    Row(
        modifier.fillMaxHeight().card(onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphIcon(slot.id.glyph(), if (available) AurisColors.Cyan else AurisColors.FgDim, Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                slot.id.title, color = if (available) AurisColors.Fg else AurisColors.FgDim,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!available) Text("выбрать приложение", color = AurisColors.FgDim, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TripCard(t: TripStats, modifier: Modifier) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        CardTitle("Текущая поездка")
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            val avg = t.avgL100
            Text(
                fmt(avg, 1), color = if (avg == null) AurisColors.FgDim else AurisColors.Fg,
                fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 1
            )
            Spacer(Modifier.width(8.dp))
            Text("л/100 км", color = AurisColors.Cyan, fontSize = 15.sp, modifier = Modifier.padding(bottom = 7.dp))
        }
        StatRow("Пробег", if (t.hasData) fmtKm(t.distanceKm) + " км" else NA)
        StatRow("EV-режим", t.evRatioPct?.let { fmt(it, 0) + " %" } ?: NA)
        Spacer(Modifier.height(6.dp))
        ConsumptionBars(t.minutes, Modifier.fillMaxWidth().weight(1f), showAxis = false)
    }
}

@Composable
fun StatRow(label: String, value: String, big: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = if (big) 5.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AurisColors.FgDim, fontSize = if (big) 16.sp else 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(
            value, color = if (value == NA) AurisColors.FgDim else AurisColors.Fg,
            fontSize = if (big) 20.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
        )
    }
}

const val BAR_SLOTS = 15
const val BAR_MAX_L100 = 10f

/**
 * Расход по минутам за последние 15 минут (справа — самая свежая минута).
 * Зелёный столбик-отметка — минута целиком на электротяге; пустое место — стоянка или нет данных.
 */
@Composable
fun ConsumptionBars(minutes: List<MinuteBar>, modifier: Modifier, showAxis: Boolean) {
    Canvas(modifier) {
        val n = BAR_SLOTS
        val gap = 4.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val h = size.height
        // фоновые ячейки
        for (i in 0 until n) {
            drawRoundRect(
                AurisColors.Line.copy(alpha = .45f), Offset(i * (bw + gap), 0f), Size(bw, h), CornerRadius(3.dp.toPx())
            )
        }
        if (showAxis) {
            for (k in 1..3) {
                val y = h - h * k / 4f
                drawLine(AurisColors.FgDim.copy(alpha = .25f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
        }
        val offset = n - minutes.size
        minutes.forEachIndexed { idx, m ->
            val x = (offset + idx) * (bw + gap)
            val v = m.l100
            when {
                !m.moved -> Unit
                v == null -> Unit
                v < 0.3f -> drawRoundRect(AurisColors.Green, Offset(x, h - 5.dp.toPx()), Size(bw, 5.dp.toPx()), CornerRadius(2.dp.toPx()))
                else -> {
                    val bh = (v / BAR_MAX_L100).coerceIn(0.04f, 1f) * h
                    val color = if (v > BAR_MAX_L100) AurisColors.Amber else AurisColors.Blue
                    drawRoundRect(color, Offset(x, h - bh), Size(bw, bh), CornerRadius(3.dp.toPx()))
                }
            }
        }
    }
}
