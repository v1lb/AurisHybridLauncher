package com.aurishybrid.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurishybrid.launcher.data.BatteryFlow
import com.aurishybrid.launcher.data.TripRecord
import com.aurishybrid.launcher.data.TripState
import com.aurishybrid.launcher.data.VehicleData
import com.aurishybrid.launcher.ui.theme.AurisColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VehicleTab(val title: String) { ENERGY("Монитор энергии"), TRIP("Поездка"), HISTORY("История") }

/** Раздел «Автомобиль»: монитор энергии, данные поездки, история — как в штатном меню гибрида. */
@Composable
fun VehicleScreen(
    data: VehicleData,
    trip: TripState,
    tab: VehicleTab,
    onTab: (VehicleTab) -> Unit,
    onResetTrip: () -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(AurisColors.Panel).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            VehicleTab.entries.forEach { t ->
                val sel = t == tab
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) AurisColors.Blue else AurisColors.Panel)
                        .clickable { onTab(t) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t.title, color = if (sel) AurisColors.Fg else AurisColors.FgDim,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            VehicleTab.ENERGY -> EnergyTab(data)
            VehicleTab.TRIP -> TripTab(trip, onResetTrip)
            VehicleTab.HISTORY -> HistoryTab(trip, onClearHistory)
        }
    }
}

@Composable
private fun EnergyTab(data: VehicleData) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EnergyMonitor(data, Modifier.weight(0.68f).fillMaxHeight().card())
        Column(Modifier.weight(0.32f).fillMaxHeight().card().padding(horizontal = 16.dp, vertical = 12.dp)) {
            CardTitle("Гибридная система")
            Spacer(Modifier.height(6.dp))
            StatRow("Заряд HV", data.socPct?.let { fmt(it, 0) + " %" } ?: NA, big = true)
            StatRow(
                "Ток HV",
                data.hvCurrentA?.let {
                    String.format(Locale.US, "%.1f А", it) + when (data.batteryFlow) {
                        BatteryFlow.CHARGE -> " · заряд"
                        BatteryFlow.DISCHARGE -> " · разряд"
                        else -> ""
                    }
                } ?: NA,
                big = true
            )
            StatRow("t° HV-батареи", data.hvBatteryTempC?.let { fmt(it, 0) + " °C" } ?: NA, big = true)
            StatRow("Обороты ДВС", data.obd.rpm?.let { "$it об/мин" } ?: NA, big = true)
            StatRow("t° ДВС", data.obd.coolantC?.let { "$it °C" } ?: NA, big = true)
            StatRow("Скорость", data.speedKmh?.let { fmt(it, 0) + " км/ч" } ?: NA, big = true)
            Spacer(Modifier.weight(1f))
            Text(
                "N/A — параметр не читается стандартным OBD2 или Toyota-PID ещё не проверен (Настройки → OBD2).",
                color = AurisColors.FgDim.copy(alpha = .8f), fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TripTab(trip: TripState, onReset: () -> Unit) {
    val t = trip.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(0.38f).fillMaxHeight().card().padding(horizontal = 16.dp, vertical = 12.dp)) {
            CardTitle("Средний расход")
            Row(verticalAlignment = Alignment.Bottom) {
                val avg = t.avgL100
                Text(
                    fmt(avg, 1), color = if (avg == null) AurisColors.FgDim else AurisColors.Fg,
                    fontSize = 56.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Text("л/100 км", color = AurisColors.Cyan, fontSize = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            StatRow("Пробег", if (t.hasData) fmtKm(t.distanceKm) + " км" else NA, big = true)
            StatRow("Время", if (t.hasData) fmtDuration(t.coveredSec) else NA, big = true)
            StatRow("Средняя скорость", t.avgSpeedKmh?.let { fmt(it, 0) + " км/ч" } ?: NA, big = true)
            StatRow("EV-режим (по времени)", t.evRatioPct?.let { fmt(it, 0) + " %" } ?: NA, big = true)
            StatRow("Топливо", if (t.fuelDistKm > 0.0) String.format(Locale.US, "%.2f л", t.fuelL) else NA, big = true)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("Сбросить поездку", color = AurisColors.Cyan, fontSize = 15.sp) }
        }
        Column(Modifier.weight(0.62f).fillMaxHeight().card().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle("Расход по минутам, л/100 км")
                Spacer(Modifier.weight(1f))
                Text("шкала 0–${BAR_MAX_L100.toInt()}", color = AurisColors.FgDim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            ConsumptionBars(t.minutes, Modifier.fillMaxWidth().weight(1f), showAxis = true)
            Spacer(Modifier.height(6.dp))
            Row {
                Text("15 мин назад", color = AurisColors.FgDim, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text("сейчас", color = AurisColors.FgDim, fontSize = 11.sp)
            }
            Text(
                "Синий — расход за минуту, зелёная отметка — минута на электротяге, пусто — стоянка или нет данных. " +
                    "Считается только пока идёт опрос OBD2.",
                color = AurisColors.FgDim.copy(alpha = .8f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private val RECORD_FMT = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

@Composable
private fun HistoryTab(trip: TripState, onClear: () -> Unit) {
    val best = trip.best
    Column(Modifier.fillMaxSize().card().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle("Прошлые поездки")
            Spacer(Modifier.weight(1f))
            Text(
                "Лучший расход: " + (best?.avgL100?.let { fmt(it, 1) + " л/100 км" } ?: NA),
                color = if (best != null) AurisColors.Green else AurisColors.FgDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
            )
            if (trip.history.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClear) { Text("Очистить", color = AurisColors.Cyan, fontSize = 14.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (trip.history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Истории пока нет. Поездка попадает сюда после перерыва в данных OBD2 более 20 минут или после сброса.",
                    color = AurisColors.FgDim, fontSize = 15.sp
                )
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                HeaderCell("Начало", 1.4f); HeaderCell("Пробег", 1f); HeaderCell("Расход", 1f)
                HeaderCell("EV-режим", 1f); HeaderCell("Время", 1f)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(trip.history) { r -> RecordRow(r, r == best) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(text, color = AurisColors.FgDim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(weight))
}

@Composable
private fun RecordRow(r: TripRecord, best: Boolean) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AurisColors.Line))
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            val c = if (best) AurisColors.Green else AurisColors.Fg
            Cell(RECORD_FMT.format(Date(r.startedAt)), 1.4f, AurisColors.Fg)
            Cell(fmtKm(r.distanceKm) + " км", 1f, AurisColors.Fg)
            Cell(r.avgL100?.let { fmt(it, 1) + " л/100" } ?: NA, 1f, c)
            Cell(r.evRatioPct?.let { fmt(it, 0) + " %" } ?: NA, 1f, AurisColors.Fg)
            Cell(fmtDuration(r.coveredSec), 1f, AurisColors.Fg)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = if (text == NA) AurisColors.FgDim else color, fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(weight)
    )
}
