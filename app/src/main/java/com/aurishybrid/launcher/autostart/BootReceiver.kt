package com.aurishybrid.launcher.autostart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aurishybrid.launcher.LauncherApp

/**
 * Не запускает Activity напрямую (на Android 10+ это запрещено из фона).
 * Помечает «нужен автозапуск» и поднимает OBD-прокси (connectedDevice разрешён из BOOT_COMPLETED).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? LauncherApp ?: return
        app.syncObdService()
        if (app.prefs.autostartHa) app.requestAutostart()
    }
}
