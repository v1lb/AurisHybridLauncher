package com.aurishybrid.launcher

import android.Manifest
import android.content.Intent
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
import com.aurishybrid.launcher.ui.AppsScreen
import com.aurishybrid.launcher.ui.Glyph
import com.aurishybrid.launcher.ui.HomeScreen
import com.aurishybrid.launcher.ui.NavItem
import com.aurishybrid.launcher.ui.SettingsScreen
import com.aurishybrid.launcher.ui.Shell
import com.aurishybrid.launcher.ui.VehicleScreen
import com.aurishybrid.launcher.ui.VehicleTab
import com.aurishybrid.launcher.ui.theme.AurisTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val app: LauncherApp get() = application as LauncherApp

    private val countdown = mutableStateOf<Int?>(null)
    private val screen = mutableStateOf(SCREEN_HOME)
    private val slotsVersion = mutableIntStateOf(0)
    @Volatile private var cancelRequested = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            app.vehicle.reloadConfig()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(STATE_SCREEN)?.let { screen.value = it }
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
            AurisTheme(uiScale = app.uiScale.floatValue) {
                val version = slotsVersion.intValue
                val slots by produceState(initialValue = emptyList<ResolvedSlot>(), version) {
                    value = withContext(Dispatchers.Default) { app.apps.resolveAll() }
                }
                val data by app.vehicle.data.collectAsStateWithLifecycle(initialValue = VehicleData())
                val trip by app.trip.state.collectAsStateWithLifecycle()
                var vehicleTab by rememberSaveable { mutableStateOf(VehicleTab.ENERGY) }
                val current = screen.value

                fun slot(id: SlotId): ResolvedSlot? = slots.firstOrNull { it.id == id }
                fun open(slot: ResolvedSlot?) {
                    val target = slot?.target
                    if (target == null) {
                        screen.value = SCREEN_SETTINGS
                    } else if (!app.apps.launch(this@MainActivity, target)) {
                        Toast.makeText(this@MainActivity, "Не удалось запустить ${target.label}", Toast.LENGTH_SHORT).show()
                        slotsVersion.intValue++
                    }
                }

                // Навигация: Waze, а если его нет — Карты.
                val navSlot = slot(SlotId.WAZE)?.takeIf { it.target != null } ?: slot(SlotId.MAPS)
                val items = listOf(
                    NavItem(SCREEN_HOME, Glyph.HOME, "Главная"),
                    NavItem(KEY_NAV, Glyph.NAV, "Навигация", navSlot?.target != null),
                    NavItem(KEY_AUDIO, Glyph.MUSIC, "Аудио", slot(SlotId.MUSIC)?.target != null),
                    NavItem(KEY_PHONE, Glyph.PHONE, "Телефон", slot(SlotId.BLUETOOTH)?.target != null),
                    NavItem(SCREEN_VEHICLE, Glyph.CAR, "Автомобиль"),
                    NavItem(SCREEN_APPS, Glyph.GRID, "Приложения"),
                    NavItem(SCREEN_SETTINGS, Glyph.GEAR, "Настройки"),
                )
                val title = when (current) {
                    SCREEN_VEHICLE -> "Автомобиль"
                    SCREEN_APPS -> "Приложения"
                    SCREEN_SETTINGS -> "Настройки"
                    else -> "Auris Hybrid"
                }

                // HOME-лаунчер: «Назад» возвращает на главный экран, но не закрывает приложение.
                BackHandler { screen.value = SCREEN_HOME }

                Shell(
                    items = items, selected = current, title = title, data = data,
                    onSelect = { item ->
                        when (item.key) {
                            KEY_NAV -> open(navSlot ?: slot(SlotId.WAZE))
                            KEY_AUDIO -> open(slot(SlotId.MUSIC))
                            KEY_PHONE -> open(slot(SlotId.BLUETOOTH))
                            else -> screen.value = item.key
                        }
                    }
                ) {
                    when (current) {
                        SCREEN_VEHICLE -> VehicleScreen(
                            data = data, trip = trip, tab = vehicleTab, onTab = { vehicleTab = it },
                            onResetTrip = { app.trip.reset() }, onClearHistory = { app.trip.clearHistory() }
                        )
                        SCREEN_APPS -> AppsScreen(app.apps, version) { entry ->
                            if (!app.apps.launchPackage(this@MainActivity, entry.packageName)) {
                                Toast.makeText(this@MainActivity, "Не удалось запустить ${entry.label}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        SCREEN_SETTINGS -> SettingsScreen(
                            app = app, data = data, slots = slots,
                            onSlotsChanged = { slotsVersion.intValue++ },
                            onRequestPermissions = { requestNeededPermissions() },
                        )
                        else -> HomeScreen(
                            data = data,
                            trip = trip,
                            shortcuts = HOME_SHORTCUTS.mapNotNull { slot(it) },
                            autostartCountdown = countdown.value,
                            onCancelAutostart = { cancelRequested = true; countdown.value = null },
                            onSlotClick = { open(it) },
                            onOpenEnergy = { vehicleTab = VehicleTab.ENERGY; screen.value = SCREEN_VEHICLE },
                            onOpenTrip = { vehicleTab = VehicleTab.TRIP; screen.value = SCREEN_VEHICLE },
                        )
                    }
                }
            }
        }
    }

    /** Кнопка HOME при уже открытом лаунчере возвращает на главный экран. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) screen.value = SCREEN_HOME
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCREEN, screen.value)
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
        const val STATE_SCREEN = "screen"
        const val SCREEN_HOME = "home"
        const val SCREEN_VEHICLE = "vehicle"
        const val SCREEN_APPS = "apps"
        const val SCREEN_SETTINGS = "settings"
        const val KEY_NAV = "nav"
        const val KEY_AUDIO = "audio"
        const val KEY_PHONE = "phone"
        val HOME_SHORTCUTS = listOf(SlotId.HYBRID, SlotId.MAPS, SlotId.RADIO, SlotId.SETTINGS)
    }
}
