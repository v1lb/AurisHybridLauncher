package com.aurishybrid.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aurishybrid.launcher.LauncherApp
import com.aurishybrid.launcher.apps.ResolvedSlot
import com.aurishybrid.launcher.apps.SlotId
import com.aurishybrid.launcher.data.VehicleData
import com.aurishybrid.launcher.ui.theme.AurisColors
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    app: LauncherApp,
    data: VehicleData,
    slots: List<ResolvedSlot>,
    onSlotsChanged: () -> Unit,
    onRequestPermissions: () -> Unit,
    onBack: () -> Unit,
) {
    val prefs = app.prefs
    val context = LocalContext.current
    var autostart by remember { mutableStateOf(prefs.autostartHa) }
    var delaySec by remember { mutableFloatStateOf(prefs.autostartDelaySec.toFloat()) }
    var onWake by remember { mutableStateOf(prefs.autostartOnWake) }
    var pickerFor by remember { mutableStateOf<SlotId?>(null) }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(AurisColors.Night)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Назад", color = AurisColors.Cyan, fontSize = 16.sp) }
            Spacer(Modifier.width(12.dp))
            Text("Настройки лаунчера", color = AurisColors.Fg, fontSize = 20.sp, fontWeight = FontWeight.Light)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // ---- Левая колонка
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                ObdSettingsSection(app, data, onRequestPermissions)
            }
            // ---- Правая колонка
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Section("Автозапуск Hybrid Assistant") {
                    SwitchRow("Запускать после загрузки Android", autostart) {
                        autostart = it; prefs.autostartHa = it
                    }
                    SwitchRow("Запускать также после пробуждения (экран включён)", onWake) {
                        onWake = it; prefs.autostartOnWake = it
                    }
                    Text("Задержка: ${delaySec.roundToInt()} с", color = AurisColors.Fg, fontSize = 14.sp)
                    Slider(
                        value = delaySec, onValueChange = { delaySec = it },
                        onValueChangeFinished = { prefs.autostartDelaySec = delaySec.roundToInt() },
                        valueRange = 3f..30f
                    )
                    Hint(
                        "Запуск выполняет сам лаунчер, когда он на экране (требование Android 10+). " +
                            "Во время отсчёта его можно отменить. Наличие API данных у Hybrid Assistant не подтверждено, " +
                            "поэтому лаунчер только запускает приложение и не читает его данные."
                    )
                }
                Section("Лаунчер по умолчанию") {
                    TextButton(onClick = { openHomeSettings(context) }) {
                        Text("Открыть выбор главного экрана (Home app)", color = AurisColors.Cyan)
                    }
                }
                Section("Быстрый доступ") {
                    slots.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(s.id.glyph(), AurisColors.Cyan)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.id.title + (s.target?.let { " → ${it.label}" } ?: ""), color = AurisColors.Fg, fontSize = 14.sp)
                                Hint(s.how)
                            }
                            if (s.id != SlotId.SETTINGS) {
                                TextButton(onClick = { pickerFor = s.id }) { Text("Выбрать", color = AurisColors.Cyan) }
                                if (prefs.slotPackage(s.id) != null) {
                                    TextButton(onClick = { prefs.setSlotPackage(s.id, null); onSlotsChanged() }) {
                                        Text("Авто", color = AurisColors.FgDim)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pickerFor?.let { id ->
        val apps = remember(id) { app.apps.launchableApps() }
        AlertDialog(
            onDismissRequest = { pickerFor = null },
            confirmButton = { TextButton(onClick = { pickerFor = null }) { Text("Закрыть") } },
            title = { Text("Приложение для «${id.title}»") },
            text = {
                Box(Modifier.heightIn(max = 340.dp)) {
                    LazyColumn {
                        items(apps, key = { it.packageName }) { a ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    prefs.setSlotPackage(id, a.packageName); pickerFor = null; onSlotsChanged()
                                }.padding(vertical = 8.dp)
                            ) {
                                Text(a.label, color = AurisColors.Fg, fontSize = 15.sp)
                                Text(a.packageName, color = AurisColors.FgDim, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            containerColor = AurisColors.Graphite
        )
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .background(AurisColors.Panel)
            .border(1.dp, AurisColors.Line, shape)
            .padding(12.dp)
    ) {
        Text(title.uppercase(), color = AurisColors.Cyan, fontSize = 12.sp, letterSpacing = 1.5.sp)
        content()
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AurisColors.Fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = AurisColors.FgDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
}

private fun openHomeSettings(ctx: Context) {
    val intents = listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (e: Exception) {
            // пробуем следующий вариант
        }
    }
}
