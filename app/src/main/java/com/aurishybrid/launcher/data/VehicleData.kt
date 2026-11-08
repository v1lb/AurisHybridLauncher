package com.aurishybrid.launcher.data

import com.aurishybrid.launcher.data.toyota.ToyotaReading
import kotlin.math.abs

enum class ObdState { DISABLED, IDLE, PAUSED, NO_PERMISSION, NO_DEVICE, CONNECTING, RECONNECTING, CONNECTED, ERROR }

enum class ShareMode { VISIBLE_ONLY, PROXY }

enum class BatteryFlow { UNKNOWN, IDLE, CHARGE, DISCHARGE }

/** null в любом поле = данных нет → в UI показывается N/A. */
data class ObdSnapshot(
    val state: ObdState = ObdState.IDLE,
    val message: String? = null,
    val transport: String? = null,
    val proxyEnabled: Boolean = false,
    val proxyClientConnected: Boolean = false,
    val proxyClientActive: Boolean = false,
    val proxyMessage: String? = null,
    val speedKmh: Float? = null,
    val rpm: Int? = null,
    val coolantC: Int? = null,
    val moduleVoltage: Float? = null,
    val hvSocPct: Float? = null,
    val fuelRateLh: Float? = null,
    val fuelRateSource: String? = null,
    val supportedPids: Set<Int> = emptySet(),
    val toyota: Map<String, ToyotaReading> = emptyMap(),
)

data class VehicleData(
    val obd: ObdSnapshot = ObdSnapshot(),
    val gpsSpeedKmh: Float? = null,
    val gpsState: String = "нет данных",
    /** id Toyota-параметров, которые пользователь сверил и отметил как проверенные. */
    val verified: Set<String> = emptySet(),
) {
    val speedKmh: Float? get() = obd.speedKmh ?: gpsSpeedKmh
    val speedSource: String?
        get() = when {
            obd.speedKmh != null -> "OBD2 PID 0D"
            gpsSpeedKmh != null -> "GPS"
            else -> null
        }

    val voltage12: Float? get() = obd.moduleVoltage
    val voltageSource: String? get() = if (obd.moduleVoltage != null) "OBD2 PID 42" else null

    /** Мгновенный расход; на стоянке (< 5 км/ч) л/100 км не определён. */
    val fuelL100: Float?
        get() {
            val rate = obd.fuelRateLh ?: return null
            val v = speedKmh ?: return null
            return if (v >= 5f) rate / v * 100f else null
        }

    /** Только проверенные пользователем Toyota-параметры попадают на главный экран. */
    fun toyota(role: String): ToyotaReading? =
        obd.toyota.values.firstOrNull { it.entry.role == role && it.entry.id in verified && it.value != null }

    private fun ToyotaReading.sourceText() = "Toyota ${entry.header} ${"%02X".format(entry.mode)}${"%02X".format(entry.pid)}"

    val hvBatteryTempC: Float? get() = toyota("HV_BATT_TEMP")?.value?.toFloat()
    val hvBatteryTempSource: String? get() = toyota("HV_BATT_TEMP")?.sourceText()

    val socPct: Float? get() = obd.hvSocPct ?: toyota("HV_SOC")?.value?.toFloat()
    val socSource: String?
        get() = when {
            obd.hvSocPct != null -> "OBD2 PID 5B"
            else -> toyota("HV_SOC")?.sourceText()
        }

    val hvCurrentA: Double? get() = toyota("HV_CURRENT")?.value

    val batteryFlow: BatteryFlow
        get() {
            val r = toyota("HV_CURRENT") ?: return BatteryFlow.UNKNOWN
            val meaning = r.entry.positiveMeans ?: return BatteryFlow.UNKNOWN
            val v = r.value ?: return BatteryFlow.UNKNOWN
            if (abs(v) < 2.0) return BatteryFlow.IDLE
            val discharge = if (meaning == "discharge") v > 0 else v < 0
            return if (discharge) BatteryFlow.DISCHARGE else BatteryFlow.CHARGE
        }
}
