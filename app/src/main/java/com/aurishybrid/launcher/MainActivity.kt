package com.aurishybrid.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aurishybrid.launcher.apps.ResolvedSlot
import com.aurishybrid.launcher.apps.SlotId
import com.aurishybrid.launcher.data.VehicleData
import com.aurishybrid.launcher.ui.HomeScreen
import com.aurishybrid.launcher.ui.SettingsScreen
import com.aurishybrid.launcher.ui.theme.AurisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val app: LauncherApp get() = application as LauncherApp

    private val countdown = mutableStateOf<Int?>(null)
    private val slotsVersion = mutableIntStateOf(0)
    @Volatile private var cancelRequested = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            app.vehicle.reloadConfig()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        app.syncObdService()

        // Данные читаются только когда лаунчер виден → экономия ресурсов и свободный ELM327.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.vehicle.run() }
        }
        // Отложенный автозапуск выполняется только в состоянии RESUMED (лаунчер на экране).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.autostartSignal.collectLatest { runPendingAutostart() }
            }
        }

        setContent {
            AurisTheme {
                var screen by rememberSaveable { mutableStateOf("home") }
                val version = slotsVersion.intValue
                val slots by produceState(initialValue = emptyList<ResolvedSlot>(), version) {
                    value = withContext(Dispatchers.Default) { app.apps.resolveAll() }
                }
                val data by app.vehicle.data.collectAsStateWithLifecycle(initialValue = VehicleData())

                if (screen == "settings") {
                    SettingsScreen(
                        app = app, data = data, slots = slots,
                        onSlotsChanged = { slotsVersion.intValue++ },
                        onRequestPermissions = { requestNeededPermissions() },
                        onBack = { screen = "home" }
                    )
                } else {
                    BackHandler { /* HOME: «Назад» не закрывает лаунчер */ }
                    HomeScreen(
                        data = data,
                        slots = slots,
                        autostartCountdown = countdown.value,
                        onCancelAutostart = { cancelRequested = true; countdown.value = null },
                        onSlotClick = { slot ->
                            val target = slot.target
                            if (target == null) {
                                screen = "settings"
                            } else if (!app.apps.launch(this@MainActivity, target)) {
                                Toast.makeText(this@MainActivity, "Не удалось запустить ${target.label}", Toast.LENGTH_SHORT).show()
                                slotsVersion.intValue++
                            }
                        },
                        onOpenSettings = { screen = "settings" }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Приложения могли быть установлены/удалены, пока лаунчер был в фоне.
        slotsVersion.intValue++
    }

    private suspend fun runPendingAutostart() {
        val prefs = app.prefs
        val at = prefs.pendingAutostartAt
        if (at == 0L) return
        val now = SystemClock.elapsedRealtime()
        prefs.pendingAutostartAt = 0L
        if (!prefs.autostartHa || now < at || now - at > PENDING_WINDOW_MS) return

        val target = withContext(Dispatchers.Default) { app.apps.resolve(SlotId.HYBRID) }.target
        if (target == null) {
            Toast.makeText(this, "Hybrid Assistant не найден — выберите его в настройках", Toast.LENGTH_LONG).show()
            return
        }
        cancelRequested = false
        try {
            for (s in prefs.autostartDelaySec downTo 1) {
                if (cancelRequested) return
                countdown.value = s
                delay(1000)
            }
            if (!cancelRequested) app.apps.launch(this, target)
        } finally {
            countdown.value = null
        }
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    private companion object {
        /** Запрос автозапуска действителен 2 минуты: позже он уже не ожидается пользователем. */
        const val PENDING_WINDOW_MS = 120_000L
    }
}
