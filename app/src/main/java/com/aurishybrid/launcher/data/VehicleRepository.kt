package com.aurishybrid.launcher.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.aurishybrid.launcher.core.Prefs
import com.aurishybrid.launcher.data.obd.Caller
import com.aurishybrid.launcher.data.obd.ObdManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Объединяет GPS и OBD2 для экрана. run() вызывается, пока лаунчер виден.
 * OBD-сеанс в режиме VISIBLE_ONLY запускается отсюда же; в режиме PROXY им владеет сервис.
 */
class VehicleRepository(
    private val context: Context,
    private val prefs: Prefs,
    val obd: ObdManager,
) {
    private val gpsSpeed = MutableStateFlow<Float?>(null)
    private val gpsState = MutableStateFlow("нет данных")
    private val verified = MutableStateFlow(prefs.verifiedToyota)
    private val gpsReload = MutableStateFlow(0)

    val data: Flow<VehicleData> =
        combine(obd.state, gpsSpeed, gpsState, verified) { o, g, s, v -> VehicleData(o, g, s, v) }

    fun reloadConfig() {
        gpsReload.value = gpsReload.value + 1
        obd.reloadConfig()
    }

    fun setVerified(id: String, value: Boolean) {
        val set = prefs.verifiedToyota.toMutableSet()
        if (value) set.add(id) else set.remove(id)
        prefs.verifiedToyota = set
        verified.value = set
    }

    suspend fun run() = coroutineScope {
        launch { gpsReload.collectLatest { gpsLoop() } }
        launch { obd.run(Caller.ACTIVITY) }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun gpsLoop() {
        gpsSpeed.value = null
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            gpsState.value = "нет разрешения"; return
        }
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm == null || !lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            gpsState.value = "GPS недоступен"; return
        }
        var lastFix = 0L
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastFix = SystemClock.elapsedRealtime()
                gpsSpeed.value = if (location.hasSpeed()) location.speed * 3.6f else null
                gpsState.value = "OK"
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                gpsState.value = "GPS выключен"
            }
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            gpsState.value = "ошибка GPS: ${e.message}"; return
        }
        try {
            while (currentCoroutineContext().isActive) {
                delay(1000)
                if (SystemClock.elapsedRealtime() - lastFix > 5000) {
                    gpsSpeed.value = null
                    if (gpsState.value == "OK") gpsState.value = "нет сигнала"
                }
            }
        } finally {
            lm.removeUpdates(listener)
        }
    }
}
