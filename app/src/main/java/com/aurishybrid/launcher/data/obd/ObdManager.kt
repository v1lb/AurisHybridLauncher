package com.aurishybrid.launcher.data.obd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.ContextCompat
import com.aurishybrid.launcher.core.Prefs
import com.aurishybrid.launcher.data.ObdSnapshot
import com.aurishybrid.launcher.data.ObdState
import com.aurishybrid.launcher.data.ShareMode
import com.aurishybrid.launcher.data.toyota.ToyotaEntry
import com.aurishybrid.launcher.data.toyota.ToyotaProfileLoader
import com.aurishybrid.launcher.data.toyota.ToyotaReading
import com.aurishybrid.launcher.data.toyota.ToyotaScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

/**
 * Кто запускает сеанс OBD:
 *  ACTIVITY — режим VISIBLE_ONLY: сеанс живёт, пока лаунчер на экране; при уходе адаптер освобождается.
 *  SERVICE  — режим PROXY: foreground-сервис держит адаптер и раздаёт его Hybrid Assistant через 127.0.0.1.
 * Одновременно работает только один из них — второго сеанса к адаптеру лаунчер не открывает.
 */
enum class Caller { ACTIVITY, SERVICE }

class ObdManager(private val context: Context, private val prefs: Prefs) {

    private val _state = MutableStateFlow(ObdSnapshot())
    val state: StateFlow<ObdSnapshot> = _state

    private val _scanProgress = MutableStateFlow<String?>(null)
    val scanProgress: StateFlow<String?> = _scanProgress

    private val config = MutableStateFlow(0)
    private val paused = MutableStateFlow(false)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val scanRequests = Channel<CompletableDeferred<String>>(Channel.BUFFERED)

    @Volatile
    private var arbiter: ElmArbiter? = null

    @Volatile
    var profile: List<ToyotaEntry> = ToyotaProfileLoader.load(context)
        private set

    fun reloadConfig() {
        profile = ToyotaProfileLoader.load(context)
        config.update { it + 1 }
    }

    fun transportConfig(): TransportConfig? = when (prefs.obdTransport) {
        Prefs.TRANSPORT_BT -> prefs.obdDeviceAddress?.let { TransportConfig.Bluetooth(it) }
        else -> TransportConfig.Wifi(prefs.wifiHost, prefs.wifiPort)
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    suspend fun run(caller: Caller) {
        combine(config, paused) { _, p -> p }.collectLatest { isPaused ->
            val mode = prefs.shareMode
            val mine = (caller == Caller.ACTIVITY && mode == ShareMode.VISIBLE_ONLY) ||
                (caller == Caller.SERVICE && mode == ShareMode.PROXY)
            if (!mine) return@collectLatest
            if (!prefs.obdEnabled) {
                _state.value = ObdSnapshot(ObdState.DISABLED); return@collectLatest
            }
            if (isPaused) {
                _state.value = ObdSnapshot(ObdState.PAUSED, "идёт проверка адаптера"); return@collectLatest
            }
            val cfg = transportConfig()
            if (cfg == null) {
                _state.value = ObdSnapshot(ObdState.NO_DEVICE); return@collectLatest
            }
            if (cfg is TransportConfig.Bluetooth && Build.VERSION.SDK_INT >= 31 &&
                !granted(Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                _state.value = ObdSnapshot(ObdState.NO_PERMISSION); return@collectLatest
            }
            val proxy = mode == ShareMode.PROXY
            try {
                coroutineScope {
                    if (proxy) launch { proxyLoop() }
                    if (cfg is TransportConfig.Wifi) launch { watchWifi() }
                    connectionLoop(cfg, proxy)
                }
            } finally {
                arbiter = null
                _state.value = ObdSnapshot(ObdState.IDLE)
            }
        }
    }

    /** Тест адаптера: собственный сеанс на время теста закрывается, чтобы не занимать подключение. */
    suspend fun probe(): AdapterProbe.Result {
        val cfg = transportConfig()
            ?: return AdapterProbe.Result(AdapterProbe.Verdict.NO_CONNECTION, "Адаптер не задан")
        paused.value = true
        try {
            withTimeoutOrNull(6000) {
                _state.first { it.state != ObdState.CONNECTED && it.state != ObdState.CONNECTING && it.state != ObdState.RECONNECTING }
            }
            delay(700)
            return AdapterProbe(context).run(cfg)
        } finally {
            paused.value = false
        }
    }

    suspend fun scanToyota(): String {
        if (arbiter == null) return "Нет подключения к адаптеру. Включите OBD2 в лаунчере и зажигание (READY)."
        val d = CompletableDeferred<String>()
        scanRequests.send(d)
        return withTimeoutOrNull(15 * 60_000L) { d.await() } ?: "Таймаут сканирования"
    }

    // ---------------- соединение и переподключение ----------------

    private suspend fun connectionLoop(cfg: TransportConfig, proxy: Boolean) {
        var backoff = MIN_BACKOFF
        var attempt = 0
        var lastError: String? = null
        while (currentCoroutineContext().isActive) {
            attempt++
            _state.update {
                ObdSnapshot(
                    ObdState.CONNECTING, "${cfg.label}, попытка $attempt",
                    transport = cfg.label, proxyEnabled = proxy,
                    proxyClientConnected = it.proxyClientConnected, proxyMessage = it.proxyMessage
                )
            }
            try {
                Elm327Client(Transports.open(context, cfg)).use { elm ->
                    elm.initialize()
                    val arb = ElmArbiter(elm, ::onSniff)
                    arbiter = arb
                    backoff = MIN_BACKOFF
                    attempt = 0
                    pollSession(arb, cfg, proxy)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                arbiter = null
            }
            val msg = "${lastError ?: "связь потеряна"} · повтор через ${backoff / 1000} с"
            _state.update {
                ObdSnapshot(
                    ObdState.RECONNECTING, msg, transport = cfg.label, proxyEnabled = proxy,
                    proxyClientConnected = it.proxyClientConnected, proxyMessage = it.proxyMessage
                )
            }
            // Ждём backoff или сигнал «Wi-Fi снова доступен» — что раньше.
            withTimeoutOrNull(backoff) { kicks.receive() }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    private suspend fun watchWifi() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                kicks.trySend(Unit)
            }
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            cm.registerNetworkCallback(req, cb)
        } catch (e: Exception) {
            return
        }
        try {
            awaitCancellation()
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    private suspend fun proxyLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                _state.update { it.copy(proxyMessage = "прокси: 127.0.0.1:${prefs.proxyPort}") }
                ObdProxyServer(prefs.proxyPort, { arbiter }) { connected ->
                    _state.update { it.copy(proxyClientConnected = connected) }
                }.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(proxyMessage = "прокси: ошибка — ${e.message}") }
                delay(5000)
            }
        }
    }

    // ---------------- опрос ----------------

    private suspend fun pollSession(arb: ElmArbiter, cfg: TransportConfig, proxy: Boolean) {
        var timeouts = 0
        suspend fun q(cmd: String, timeout: Long = 2000L): String? = try {
            arb.launcher(cmd, timeout).also { timeouts = 0 }
        } catch (e: ElmTimeoutException) {
            if (++timeouts >= 3) throw IOException("адаптер не отвечает (3 таймаута подряд)")
            null
        }

        val supported = arb.launcherBatch { send -> ObdQueries.supported(send, 0x01, 0x40) }
        if (supported.isNullOrEmpty()) throw IOException("ЭБУ не ответил на 0100 — включите зажигание (READY)")
        _state.update {
            ObdSnapshot(
                ObdState.CONNECTED, arb.description, transport = cfg.label, proxyEnabled = proxy,
                proxyClientConnected = it.proxyClientConnected, proxyMessage = it.proxyMessage,
                supportedPids = supported
            )
        }

        var tick = 0
        while (currentCoroutineContext().isActive) {
            scanRequests.tryReceive().getOrNull()?.let { d -> d.complete(runScan(arb)) }

            // Прокси: пока Hybrid Assistant активно опрашивает, лаунчер не шлёт своих запросов
            // и берёт значения из его ответов (onSniff).
            if (proxy && arb.clientActiveWithin(CLIENT_QUIET_MS)) {
                _state.update { it.copy(proxyClientActive = true) }
                delay(300)
                continue
            }
            _state.update { it.copy(proxyClientActive = false) }

            val query: suspend (String, Long) -> String? = { c, t -> q(c, t) }
            for (pid in FAST_PIDS) readMode01(pid, supported, query)
            if (tick % 8 == 0) for (pid in SLOW_PIDS) readMode01(pid, supported, query)
            if (tick % 10 == 0) pollToyota(arb)
            tick++
            delay(200)
        }
    }

    private suspend fun readMode01(pid: Int, supported: Set<Int>, q: suspend (String, Long) -> String?) {
        if (pid !in supported) return
        if (pid == Pid.MAF && Pid.FUEL_RATE in supported) return
        val raw = q(ObdParser.cmd(1, pid), 2000L) ?: return
        val data = ObdParser.parse(raw, 1, pid) ?: IntArray(0)
        _state.update { applyMode01(pid, data, it) }
    }

    private fun applyMode01(pid: Int, d: IntArray, s: ObdSnapshot): ObdSnapshot = when (pid) {
        Pid.SPEED -> s.copy(speedKmh = d.getOrNull(0)?.toFloat())
        Pid.RPM -> s.copy(rpm = d.u16()?.div(4))
        Pid.COOLANT -> s.copy(coolantC = d.getOrNull(0)?.minus(40))
        Pid.MODULE_VOLTAGE -> s.copy(moduleVoltage = d.u16()?.let { it / 1000f })
        Pid.HYBRID_BATTERY_REMAINING -> s.copy(hvSocPct = d.getOrNull(0)?.let { it * 100f / 255f })
        Pid.FUEL_RATE -> s.copy(fuelRateLh = d.u16()?.let { it / 20f }, fuelRateSource = "OBD2 PID 5E")
        // л/ч = MAF[г/с] * 3600 / (AFR 14.7 * плотность бензина 745 г/л)
        Pid.MAF -> if (Pid.FUEL_RATE in s.supportedPids) s else s.copy(
            fuelRateLh = d.u16()?.let { (it / 100f) * 3600f / (14.7f * 745f) },
            fuelRateSource = "оценка по MAF (PID 10)"
        )
        else -> s
    }

    private suspend fun pollToyota(arb: ElmArbiter) {
        val groups = profile.groupBy { Triple(it.header, it.mode, it.pid) }
        if (groups.isEmpty()) return
        for ((key, entries) in groups) {
            val raw = try {
                arb.launcherBatch { send ->
                    send("ATSH${key.first}", 1000L)
                    val r = send(ObdParser.cmd(key.second, key.third), 2500L)
                    send("ATSH7DF", 1000L)
                    r
                }
            } catch (e: ElmTimeoutException) {
                null
            }
            applyToyota(entries, raw)
        }
    }

    private fun applyToyota(entries: List<ToyotaEntry>, raw: String?) {
        val first = entries.first()
        val data = raw?.let { ObdParser.parse(it, first.mode, first.pid) }
        val readings = entries.associate { it.id to evaluate(it, data, raw) }
        _state.update { it.copy(toyota = it.toyota + readings) }
    }

    private fun evaluate(e: ToyotaEntry, data: IntArray?, raw: String?): ToyotaReading {
        if (data == null) return ToyotaReading(e, null, raw?.trim()?.take(40), "нет ответа")
        val hex = data.joinToString(" ") { ObdParser.hex2(it) }
        val expr = e.expr ?: return ToyotaReading(e, null, hex, "сырые байты")
        val v = expr.eval(data) ?: return ToyotaReading(e, null, hex, "мало байтов в ответе")
        if ((e.min != null && v < e.min) || (e.max != null && v > e.max)) {
            return ToyotaReading(e, null, hex, "вне диапазона: " + String.format(Locale.US, "%.1f", v))
        }
        return ToyotaReading(e, v, hex, "OK")
    }

    /** Разбор ответов, которые получил Hybrid Assistant через прокси. */
    private fun onSniff(cmd: String, raw: String, header: String?) {
        if (cmd.length < 4 || !cmd.all { it in '0'..'9' || it in 'A'..'F' }) return
        val mode = cmd.substring(0, 2).toInt(16)
        val pid = cmd.substring(2, 4).toInt(16)
        val data = ObdParser.parse(raw, mode, pid) ?: return
        if (mode == 1) {
            _state.update { applyMode01(pid, data, it) }
            return
        }
        val h = header ?: return
        val entries = profile.filter { it.header.equals(h, ignoreCase = true) && it.mode == mode && it.pid == pid }
        if (entries.isNotEmpty()) applyToyota(entries, raw)
    }

    private suspend fun runScan(arb: ElmArbiter): String = try {
        arb.launcherBatch { send -> ToyotaScanner { _scanProgress.value = it }.scan(send) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Сканирование прервано: ${e.message}"
    } finally {
        _scanProgress.value = null
    }

    private fun IntArray.u16(): Int? = if (size >= 2) this[0] * 256 + this[1] else null

    private companion object {
        const val MIN_BACKOFF = 2_000L
        const val MAX_BACKOFF = 30_000L
        const val CLIENT_QUIET_MS = 3_000L
        val FAST_PIDS = listOf(Pid.SPEED, Pid.RPM, Pid.FUEL_RATE, Pid.MAF)
        val SLOW_PIDS = listOf(Pid.COOLANT, Pid.HYBRID_BATTERY_REMAINING, Pid.MODULE_VOLTAGE)
    }
}
