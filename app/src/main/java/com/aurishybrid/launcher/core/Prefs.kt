package com.aurishybrid.launcher.core

import android.content.Context
import com.aurishybrid.launcher.apps.SlotId
import com.aurishybrid.launcher.data.ShareMode

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var obdEnabled: Boolean
        get() = sp.getBoolean("obd_enabled", false)
        set(v) = sp.edit().putBoolean("obd_enabled", v).apply()

    /** TRANSPORT_WIFI (по умолчанию) или TRANSPORT_BT. */
    var obdTransport: String
        get() = sp.getString("obd_transport", TRANSPORT_WIFI) ?: TRANSPORT_WIFI
        set(v) = sp.edit().putString("obd_transport", v).apply()

    /** Типичный адрес адаптеров с сетью WIFI_OBDII. */
    var wifiHost: String
        get() = sp.getString("wifi_host", "192.168.0.10") ?: "192.168.0.10"
        set(v) = sp.edit().putString("wifi_host", v).apply()

    var wifiPort: Int
        get() = sp.getInt("wifi_port", 35000)
        set(v) = sp.edit().putInt("wifi_port", v).apply()

    var obdDeviceAddress: String?
        get() = sp.getString("obd_address", null)
        set(v) = sp.edit().putString("obd_address", v).apply()

    var shareMode: ShareMode
        get() = runCatching { ShareMode.valueOf(sp.getString("share_mode", null) ?: "") }.getOrDefault(ShareMode.VISIBLE_ONLY)
        set(v) = sp.edit().putString("share_mode", v.name).apply()

    var proxyPort: Int
        get() = sp.getInt("proxy_port", 35000)
        set(v) = sp.edit().putInt("proxy_port", v).apply()

    var verifiedToyota: Set<String>
        get() = sp.getStringSet("toyota_verified", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("toyota_verified", v).apply()

    var autostartHa: Boolean
        get() = sp.getBoolean("autostart_ha", false)
        set(v) = sp.edit().putBoolean("autostart_ha", v).apply()

    var autostartDelaySec: Int
        get() = sp.getInt("autostart_delay", 8)
        set(v) = sp.edit().putInt("autostart_delay", v).apply()

    var autostartOnWake: Boolean
        get() = sp.getBoolean("autostart_wake", false)
        set(v) = sp.edit().putBoolean("autostart_wake", v).apply()

    /** elapsedRealtime момента запроса автозапуска; 0 = нет запроса. */
    var pendingAutostartAt: Long
        get() = sp.getLong("pending_autostart", 0L)
        set(v) = sp.edit().putLong("pending_autostart", v).apply()

    fun slotPackage(id: SlotId): String? = sp.getString("slot_${id.name}", null)

    fun setSlotPackage(id: SlotId, pkg: String?) {
        sp.edit().putString("slot_${id.name}", pkg).apply()
    }

    companion object {
        const val TRANSPORT_WIFI = "wifi"
        const val TRANSPORT_BT = "bt"
    }
}
