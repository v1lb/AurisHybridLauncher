package com.aurishybrid.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.aurishybrid.launcher.apps.AppLauncher
import com.aurishybrid.launcher.core.Prefs
import com.aurishybrid.launcher.data.ShareMode
import com.aurishybrid.launcher.data.VehicleRepository
import com.aurishybrid.launcher.data.obd.ObdManager
import com.aurishybrid.launcher.service.ObdProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Простой service locator без DI-фреймворков (минимум RAM и зависимостей). */
class LauncherApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var apps: AppLauncher
        private set
    lateinit var obd: ObdManager
        private set
    lateinit var vehicle: VehicleRepository
        private set

    private val _autostartSignal = MutableStateFlow(0)
    /** Сигнал для MainActivity: «есть отложенный автозапуск Hybrid Assistant». */
    val autostartSignal: StateFlow<Int> = _autostartSignal

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        apps = AppLauncher(this, prefs)
        obd = ObdManager(this, prefs)
        vehicle = VehicleRepository(this, prefs, obd)

        // Пробуждение магнитолы (ACC ON после сна): экран включается.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (prefs.autostartHa && prefs.autostartOnWake) requestAutostart()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Ставит автозапуск «в очередь»; сам запуск делает MainActivity, когда она на экране. */
    fun requestAutostart() {
        prefs.pendingAutostartAt = SystemClock.elapsedRealtime()
        _autostartSignal.update { it + 1 }
    }

    /** Запускает или останавливает прокси-сервис в соответствии с настройками. */
    fun syncObdService() {
        val intent = Intent(this, ObdProxyService::class.java)
        if (prefs.obdEnabled && prefs.shareMode == ShareMode.PROXY) {
            runCatching { ContextCompat.startForegroundService(this, intent) }
        } else {
            stopService(intent)
        }
    }
}
