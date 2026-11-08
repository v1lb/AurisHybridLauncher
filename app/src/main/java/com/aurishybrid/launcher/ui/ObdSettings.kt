package com.aurishybrid.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurishybrid.launcher.LauncherApp
import com.aurishybrid.launcher.core.Prefs
import com.aurishybrid.launcher.data.ShareMode
import com.aurishybrid.launcher.data.VehicleData
import com.aurishybrid.launcher.data.obd.ObdParser
import com.aurishybrid.launcher.data.toyota.ToyotaProfileLoader
import com.aurishybrid.launcher.ui.theme.AurisColors
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ObdSettingsSection(app: LauncherApp, data: VehicleData, onRequestPermissions: () -> Unit) {
    val prefs = app.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.obdEnabled) }
    var transport by remember { mutableStateOf(prefs.obdTransport) }
    var host by remember { mutableStateOf(prefs.wifiHost) }
    var port by remember { mutableStateOf(prefs.wifiPort.toString()) }
    var btAddress by remember { mutableStateOf(prefs.obdDeviceAddress) }
    var mode by remember { mutableStateOf(prefs.shareMode) }
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var profileVersion by remember { mutableIntStateOf(0) }
    val profile = remember(profileVersion) { app.obd.profile }
    val scanProgress by app.obd.scanProgress.collectAsStateWithLifecycle()
    val bonded = remember(transport, data.obd.state) { bondedDevices(context) }

    fun apply() {
        app.vehicle.reloadConfig()
        app.syncObdService()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val msg = try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalArgumentException("файл не читается")
            val n = ToyotaProfileLoader.import(context, text)
            app.vehicle.reloadConfig(); profileVersion++
            "Профиль загружен: $n параметров"
        } catch (e: Exception) {
            "Ошибка профиля: ${e.message}"
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    Section("OBD2 адаптер") {
        SwitchRow("Читать данные OBD2", enabled) { enabled = it; prefs.obdEnabled = it; apply() }
        Hint("Статус: ${data.obd.statusText()}${data.obd.message?.let { " — $it" } ?: ""}")
        data.obd.proxyMessage?.let {
            Hint(it + if (data.obd.proxyClientConnected) " · Hybrid Assistant подключён" else " · клиент не подключён")
        }
        if (data.obd.supportedPids.isNotEmpty()) {
            Hint("PID mode 01: " + data.obd.supportedPids.sorted().joinToString(" ") { ObdParser.hex2(it) })
        }

        Text("Подключение", color = AurisColors.Fg, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        RadioRow("Wi-Fi (TCP)", transport == Prefs.TRANSPORT_WIFI) {
            transport = Prefs.TRANSPORT_WIFI; prefs.obdTransport = transport; apply()
        }
        RadioRow("Bluetooth", transport == Prefs.TRANSPORT_BT) {
            transport = Prefs.TRANSPORT_BT; prefs.obdTransport = transport; apply()
        }
        if (transport == Prefs.TRANSPORT_WIFI) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, singleLine = true,
                    label = { Text("IP") }, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = port, onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) }, singleLine = true,
                    label = { Text("Порт") }, modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                TextButton(onClick = {
                    prefs.wifiHost = host.trim(); prefs.wifiPort = port.toIntOrNull() ?: 35000; apply()
                }) { Text("Сохранить", color = AurisColors.Cyan) }
            }
            Hint(
                "WIFI_OBDII обычно 192.168.0.10:35000. Магнитола должна быть подключена к Wi-Fi сети адаптера; " +
                    "интернет по Wi-Fi в это время недоступен (нужна мобильная сеть/модем магнитолы)."
            )
        } else {
            when {
                bonded == null -> TextButton(onClick = onRequestPermissions) {
                    Text("Дать разрешение Bluetooth", color = AurisColors.Cyan)
                }
                bonded.isEmpty() -> Hint("Нет сопряжённых устройств — сначала сопрягите ELM327 в настройках Bluetooth.")
                else -> bonded.forEach { (name, address) ->
                    RadioRow("$name · $address", btAddress == address) {
                        btAddress = address; prefs.obdDeviceAddress = address; apply()
                    }
                }
            }
        }

        Text("Совместная работа с Hybrid Assistant", color = AurisColors.Fg, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        RadioRow("Только когда лаунчер на экране", mode == ShareMode.VISIBLE_ONLY) {
            mode = ShareMode.VISIBLE_ONLY; prefs.shareMode = mode; apply()
        }
        RadioRow("Прокси: Hybrid Assistant → 127.0.0.1:${prefs.proxyPort}", mode == ShareMode.PROXY) {
            mode = ShareMode.PROXY; prefs.shareMode = mode; apply()
        }
        Hint(
            if (mode == ShareMode.PROXY)
                "Лаунчер держит единственное подключение к адаптеру и передаёт команды Hybrid Assistant по очереди. " +
                    "В Hybrid Assistant укажите Wi-Fi адаптер с IP 127.0.0.1 и портом ${prefs.proxyPort}. " +
                    "Пока Hybrid Assistant опрашивает, лаунчер свои запросы не шлёт и читает его ответы."
            else
                "Лаунчер подключается к адаптеру только пока он на экране и сразу отключается при уходе. " +
                    "Hybrid Assistant не должен держать подключение в фоне, иначе подключение лаунчера будет отклонено."
        )

        Row {
            TextButton(enabled = busy == null, onClick = {
                scope.launch {
                    busy = "Проверка адаптера…"
                    report = app.obd.probe().report
                    busy = null
                }
            }) { Text("Проверить адаптер", color = AurisColors.Cyan) }
        }
        busy?.let { Hint(it) }
    }

    Section("Toyota: параметры гибридной системы") {
        Hint(
            "Адреса и формулы для Auris E150 не опубликованы надёжно. Встроенные записи — кандидаты из списков Prius Gen2. " +
                "Отметьте «проверено» только если значение совпадает с Hybrid Assistant — тогда оно появится на главном экране."
        )
        profile.forEach { e ->
            val r = data.obd.toyota[e.id]
            val valueText = when {
                r == null -> "—"
                r.value != null -> String.format(Locale.US, "%.1f", r.value) + " " + e.unit
                else -> r.status
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${e.name}: $valueText", color = AurisColors.Fg, fontSize = 14.sp)
                    Hint(
                        "${e.header} ${ObdParser.hex2(e.mode)}${ObdParser.hex2(e.pid)} · ${e.expr?.text ?: "без формулы"}" +
                            (e.role?.let { " · роль $it" } ?: "") + (r?.rawHex?.let { " · [$it]" } ?: "")
                    )
                    if (e.source.isNotBlank()) Hint(e.source)
                }
                if (e.expr != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(checked = e.id in data.verified, onCheckedChange = { app.vehicle.setVerified(e.id, it) })
                        Text("проверено", color = AurisColors.FgDim, fontSize = 11.sp)
                    }
                }
            }
        }
        Row {
            TextButton(enabled = busy == null, onClick = {
                scope.launch {
                    busy = "Сканирование ЭБУ (несколько минут, зажигание READY)…"
                    report = app.obd.scanToyota()
                    busy = null
                }
            }) { Text("Сканировать ЭБУ", color = AurisColors.Cyan) }
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                Text("Импорт профиля", color = AurisColors.Cyan)
            }
            TextButton(onClick = {
                ToyotaProfileLoader.resetToBuiltIn(context); app.vehicle.reloadConfig(); profileVersion++
            }) { Text("Встроенный", color = AurisColors.FgDim) }
        }
        scanProgress?.let { Hint("Скан: $it") }
    }

    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Отчёт") },
            text = {
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AurisColors.Fg)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { context.startActivity(Intent.createChooser(send, "Отправить отчёт")) }
                }) { Text("Поделиться") }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Закрыть") } },
            containerColor = AurisColors.Graphite
        )
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, color = AurisColors.Fg, fontSize = 14.sp)
    }
}

/** null = нет разрешения BLUETOOTH_CONNECT. */
@SuppressLint("MissingPermission")
private fun bondedDevices(ctx: Context): List<Pair<String, String>>? {
    if (Build.VERSION.SDK_INT >= 31 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    ) return null
    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return try {
        adapter.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address }.sortedBy { it.first }
    } catch (e: SecurityException) {
        null
    }
}
