package com.aurishybrid.launcher.data

import android.os.SystemClock
import com.aurishybrid.launcher.core.Prefs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject

/** Одна минута поездки для столбчатой диаграммы расхода. */
data class MinuteBar(val distM: Float, val fuelMl: Float, val fuelKnown: Boolean, val evSec: Float) {
    /** л/100 км за минуту; null — машина стояла или расход не измерялся. */
    val l100: Float? get() = if (fuelKnown && distM >= 30f) fuelMl / distM * 100f else null
    val moved: Boolean get() = distM >= 30f
}

/**
 * Накопленные значения поездки. Всё считается только из реальных данных OBD2:
 * пока связи нет (например, лаунчер в фоне в режиме «только на экране»), ничего не накапливается.
 */
data class TripStats(
    val startedAt: Long = 0L,
    val lastSampleAt: Long = 0L,
    /** Секунды, в течение которых данные реально поступали. */
    val coveredSec: Double = 0.0,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    /** Пробег, на котором был известен расход топлива. */
    val fuelDistKm: Double = 0.0,
    /** Время в движении с известными оборотами ДВС и из него — с заглушенным ДВС. */
    val movingSec: Double = 0.0,
    val evSec: Double = 0.0,
    val minutes: List<MinuteBar> = emptyList(),
) {
    val avgL100: Float? get() = if (fuelDistKm >= 0.3) (fuelL / fuelDistKm * 100.0).toFloat() else null
    val avgSpeedKmh: Float? get() = if (coveredSec >= 60.0) (distanceKm / coveredSec * 3600.0).toFloat() else null
    val evRatioPct: Float? get() = if (movingSec >= 30.0) (evSec / movingSec * 100.0).toFloat() else null
    val hasData: Boolean get() = coveredSec > 0.0
}

data class TripRecord(
    val startedAt: Long,
    val distanceKm: Double,
    val avgL100: Float?,
    val evRatioPct: Float?,
    val coveredSec: Double,
)

data class TripState(val current: TripStats = TripStats(), val history: List<TripRecord> = emptyList()) {
    val best: TripRecord? get() = history.filter { it.avgL100 != null && it.distanceKm >= 2.0 }.minByOrNull { it.avgL100!! }
}

class TripComputer(private val prefs: Prefs, private val obd: StateFlow<ObdSnapshot>) {

    private val lock = Any()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<TripState> = _state

    // Аккумулятор текущей минуты (не сохраняется между перезапусками).
    private var mSec = 0.0
    private var mDist = 0.0
    private var mFuel = 0.0
    private var mFuelKnown = true
    private var mEv = 0.0

    /** Завершить текущую поездку (в историю) и начать новую. */
    fun reset() = synchronized(lock) {
        _state.value = finishTrip(_state.value)
        clearMinute()
        save()
    }

    fun clearHistory() = synchronized(lock) {
        _state.value = _state.value.copy(history = emptyList())
        save()
    }

    suspend fun run() {
        var lastTick = 0L
        var lastLive = false
        var sinceSave = 0
        while (currentCoroutineContext().isActive) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            val s = obd.value
            val speed = s.speedKmh
            val live = s.state == ObdState.CONNECTED && speed != null
            if (live && lastLive && speed != null) {
                val dt = ((now - lastTick) / 1000.0).coerceIn(0.0, MAX_DT_SEC)
                synchronized(lock) { integrate(dt, speed.toDouble(), s.fuelRateLh, s.rpm) }
                if (++sinceSave >= SAVE_EVERY_SEC) {
                    sinceSave = 0
                    synchronized(lock) { save() }
                }
            }
            lastLive = live
            lastTick = now
        }
    }

    private fun integrate(dt: Double, speedKmh: Double, fuelRateLh: Float?, rpm: Int?) {
        val wall = System.currentTimeMillis()
        var st = _state.value
        var cur = st.current
        // Новая поездка: первая запись, долгий перерыв (зажигание выключали) или скачок системных часов назад.
        val gap = wall - cur.lastSampleAt
        if (cur.startedAt == 0L || gap > NEW_TRIP_GAP_MS || gap < -60_000L) {
            st = finishTrip(st)
            clearMinute()
            cur = st.current.copy(startedAt = wall)
        }
        val dKm = speedKmh * dt / 3600.0
        val moving = speedKmh >= 1.0
        val fuel = fuelRateLh?.let { it * dt / 3600.0 }
        val ev = moving && rpm != null && rpm < ENGINE_ON_RPM

        mSec += dt
        mDist += dKm * 1000.0
        if (fuel != null) mFuel += fuel * 1000.0 else mFuelKnown = false
        if (ev) mEv += dt
        var minutes = cur.minutes
        if (mSec >= 60.0) {
            minutes = (minutes + MinuteBar(mDist.toFloat(), mFuel.toFloat(), mFuelKnown, mEv.toFloat())).takeLast(MAX_MINUTES)
            clearMinute()
        }
        cur = cur.copy(
            lastSampleAt = wall,
            coveredSec = cur.coveredSec + dt,
            distanceKm = cur.distanceKm + dKm,
            fuelL = cur.fuelL + (fuel ?: 0.0),
            fuelDistKm = cur.fuelDistKm + if (fuel != null) dKm else 0.0,
            movingSec = cur.movingSec + if (moving && rpm != null) dt else 0.0,
            evSec = cur.evSec + if (ev) dt else 0.0,
            minutes = minutes,
        )
        _state.value = st.copy(current = cur)
    }

    private fun clearMinute() {
        mSec = 0.0; mDist = 0.0; mFuel = 0.0; mFuelKnown = true; mEv = 0.0
    }

    private fun finishTrip(st: TripState): TripState {
        val c = st.current
        val history = if (c.distanceKm >= MIN_RECORD_KM) {
            (listOf(TripRecord(c.startedAt, c.distanceKm, c.avgL100, c.evRatioPct, c.coveredSec)) + st.history).take(MAX_HISTORY)
        } else st.history
        return TripState(TripStats(), history)
    }

    // ---------------- хранение ----------------

    private fun save() {
        val st = _state.value
        val c = st.current
        val o = JSONObject()
        o.put("cur", JSONObject().apply {
            put("startedAt", c.startedAt); put("lastSampleAt", c.lastSampleAt)
            put("coveredSec", c.coveredSec); put("distanceKm", c.distanceKm)
            put("fuelL", c.fuelL); put("fuelDistKm", c.fuelDistKm)
            put("movingSec", c.movingSec); put("evSec", c.evSec)
            put("minutes", JSONArray().apply {
                c.minutes.forEach { m ->
                    put(JSONArray().apply { put(m.distM.toDouble()); put(m.fuelMl.toDouble()); put(m.fuelKnown); put(m.evSec.toDouble()) })
                }
            })
        })
        o.put("history", JSONArray().apply {
            st.history.forEach { r ->
                put(JSONObject().apply {
                    put("startedAt", r.startedAt); put("distanceKm", r.distanceKm)
                    if (r.avgL100 != null) put("avgL100", r.avgL100.toDouble())
                    if (r.evRatioPct != null) put("evRatioPct", r.evRatioPct.toDouble())
                    put("coveredSec", r.coveredSec)
                })
            }
        })
        prefs.tripJson = o.toString()
    }

    private fun load(): TripState = try {
        val o = JSONObject(prefs.tripJson ?: "{}")
        val c = o.optJSONObject("cur")
        val cur = if (c == null) TripStats() else TripStats(
            startedAt = c.optLong("startedAt"), lastSampleAt = c.optLong("lastSampleAt"),
            coveredSec = c.optDouble("coveredSec", 0.0), distanceKm = c.optDouble("distanceKm", 0.0),
            fuelL = c.optDouble("fuelL", 0.0), fuelDistKm = c.optDouble("fuelDistKm", 0.0),
            movingSec = c.optDouble("movingSec", 0.0), evSec = c.optDouble("evSec", 0.0),
            minutes = c.optJSONArray("minutes")?.let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONArray(i)
                    MinuteBar(m.getDouble(0).toFloat(), m.getDouble(1).toFloat(), m.getBoolean(2), m.getDouble(3).toFloat())
                }
            } ?: emptyList(),
        )
        val history = o.optJSONArray("history")?.let { a ->
            (0 until a.length()).map { i ->
                val r = a.getJSONObject(i)
                TripRecord(
                    r.optLong("startedAt"), r.optDouble("distanceKm", 0.0),
                    if (r.has("avgL100")) r.getDouble("avgL100").toFloat() else null,
                    if (r.has("evRatioPct")) r.getDouble("evRatioPct").toFloat() else null,
                    r.optDouble("coveredSec", 0.0),
                )
            }
        } ?: emptyList()
        TripState(cur, history)
    } catch (e: Exception) {
        TripState()
    }

    private companion object {
        const val MAX_DT_SEC = 2.5
        const val SAVE_EVERY_SEC = 15
        const val NEW_TRIP_GAP_MS = 20 * 60_000L
        const val ENGINE_ON_RPM = 100
        const val MAX_MINUTES = 15
        const val MAX_HISTORY = 10
        const val MIN_RECORD_KM = 0.5
    }
}
