package com.aurishybrid.launcher.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.aurishybrid.launcher.core.Prefs

enum class SlotId(val title: String) {
    HYBRID("Hybrid"),
    WAZE("Waze"),
    MAPS("Карты"),
    RADIO("Радио"),
    BLUETOOTH("Bluetooth"),
    MUSIC("Музыка"),
    SETTINGS("Настройки"),
}

data class AppEntry(val packageName: String, val label: String)

sealed interface LaunchTarget {
    val label: String

    data class App(val packageName: String, override val label: String) : LaunchTarget
    data class Action(val intent: Intent, override val label: String) : LaunchTarget
}

/** target == null — приложение не найдено; кнопка ведёт в настройки выбора, а не «в никуда». */
data class ResolvedSlot(val id: SlotId, val target: LaunchTarget?, val how: String)

/**
 * Поиск и запуск приложений только через PackageManager / Intent.
 * Имена пакетов FM-радио, Bluetooth и Hybrid Assistant на B9520B не известны заранее,
 * поэтому они ищутся по подписи приложения и могут быть выбраны вручную.
 */
class AppLauncher(private val context: Context, private val prefs: Prefs) {

    private val pm: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    fun launchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).asSequence()
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun resolveAll(): List<ResolvedSlot> {
        val apps = launchableApps()
        return SlotId.entries.map { resolve(it, apps) }
    }

    fun resolve(id: SlotId, apps: List<AppEntry> = launchableApps()): ResolvedSlot {
        prefs.slotPackage(id)?.let { pkg ->
            if (isLaunchable(pkg)) {
                return ResolvedSlot(id, LaunchTarget.App(pkg, labelOf(pkg)), "выбрано вручную ($pkg)")
            }
        }
        val auto: ResolvedSlot? = when (id) {
            SlotId.HYBRID -> byLabel(id, apps, Regex("hybrid\\s*assistant", RegexOption.IGNORE_CASE))
                ?: byLabel(id, apps, Regex("hybrid", RegexOption.IGNORE_CASE))
            SlotId.WAZE -> byPackage(id, "com.waze")
            SlotId.MAPS -> byPackage(id, "com.google.android.apps.maps")
            SlotId.RADIO -> byLabel(id, apps, Regex("(\\bfm\\b|radio|радио)", RegexOption.IGNORE_CASE))
            SlotId.BLUETOOTH -> byLabel(id, apps, Regex("(bluetooth|^bt\\b|блютуз)", RegexOption.IGNORE_CASE))
            SlotId.MUSIC -> musicSelector()
                ?: byLabel(id, apps, Regex("(music|музык|плеер)", RegexOption.IGNORE_CASE))
            SlotId.SETTINGS -> settingsAction()
        }
        return auto ?: ResolvedSlot(id, null, "не найдено — выберите приложение")
    }

    /** Запуск с флагами лаунчера: существующая задача выводится на передний план, без дубликатов. */
    fun launch(from: Context, target: LaunchTarget): Boolean {
        val intent = when (target) {
            is LaunchTarget.App -> pm.getLaunchIntentForPackage(target.packageName) ?: return false
            is LaunchTarget.Action -> Intent(target.intent)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            from.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun isLaunchable(pkg: String): Boolean = pm.getLaunchIntentForPackage(pkg) != null

    private fun labelOf(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun byPackage(id: SlotId, pkg: String): ResolvedSlot? =
        if (isLaunchable(pkg)) ResolvedSlot(id, LaunchTarget.App(pkg, labelOf(pkg)), "найден пакет $pkg") else null

    private fun byLabel(id: SlotId, apps: List<AppEntry>, re: Regex): ResolvedSlot? =
        apps.firstOrNull { re.containsMatchIn(it.label) }?.let {
            ResolvedSlot(id, LaunchTarget.App(it.packageName, it.label), "найдено по названию: ${it.label} (${it.packageName})")
        }

    @Suppress("DEPRECATION")
    private fun musicSelector(): ResolvedSlot? {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        val ri = pm.resolveActivity(intent, 0) ?: return null
        return ResolvedSlot(
            SlotId.MUSIC,
            LaunchTarget.Action(intent, ri.loadLabel(pm).toString()),
            "системный плеер (CATEGORY_APP_MUSIC)"
        )
    }

    @Suppress("DEPRECATION")
    private fun settingsAction(): ResolvedSlot? {
        val intent = Intent(Settings.ACTION_SETTINGS)
        if (pm.resolveActivity(intent, 0) == null) return null
        return ResolvedSlot(SlotId.SETTINGS, LaunchTarget.Action(intent, "Android Settings"), "системные настройки Android")
    }
}
