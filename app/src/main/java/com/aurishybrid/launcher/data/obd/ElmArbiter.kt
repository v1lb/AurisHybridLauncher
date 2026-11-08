package com.aurishybrid.launcher.data.obd

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

typealias ElmSend = suspend (cmd: String, timeoutMs: Long) -> String

/**
 * Единственный владелец ELM327. Последовательно обслуживает лаунчер и внешнего клиента
 * (Hybrid Assistant через прокси). У каждой стороны свои AT-настройки (эхо, пробелы,
 * заголовки, ATSH …): при смене стороны арбитр восстанавливает нужное состояние адаптера.
 * Каждая команда клиента выполняется целиком, без вклинивания запросов лаунчера.
 */
class ElmArbiter(
    private val elm: Elm327Client,
    private val onSniff: (cmd: String, raw: String, header: String?) -> Unit,
) {
    private enum class Owner { NONE, LAUNCHER, CLIENT }

    private val mutex = Mutex()
    private var owner = Owner.NONE
    private val clientAt = LinkedHashMap<String, String>()

    @Volatile
    private var lastClientAt = 0L

    val description: String get() = elm.description

    fun clientActiveWithin(ms: Long): Boolean = SystemClock.elapsedRealtime() - lastClientAt < ms

    suspend fun launcher(cmd: String, timeoutMs: Long = 2000L): String =
        launcherBatch { send -> send(cmd, timeoutMs) }

    /** Несколько команд подряд без вмешательства клиента (например ATSH + запрос). */
    suspend fun <T> launcherBatch(block: suspend (ElmSend) -> T): T = mutex.withLock {
        if (owner != Owner.LAUNCHER) {
            for (c in LAUNCHER_STATE) elm.command(c, 1500L)
            owner = Owner.LAUNCHER
        }
        block { c, t -> elm.command(c, t) }
    }

    /** Команда внешнего клиента. Возвращает ответ адаптера как есть (с эхо и '>'). */
    suspend fun client(cmd: String): String = mutex.withLock {
        lastClientAt = SystemClock.elapsedRealtime()
        if (owner != Owner.CLIENT) {
            for ((family, c) in elm.defaults) if (family !in clientAt) elm.command(c, 1500L)
            for (c in clientAt.values) elm.command(c, 1500L)
            owner = Owner.CLIENT
        }
        val norm = cmd.uppercase(Locale.US).replace(" ", "")
        val raw = elm.commandRaw(cmd, if (norm.startsWith("AT")) 6000L else 10000L)
        record(norm)
        runCatching { onSniff(norm, raw, clientAt["SH"]?.removePrefix("ATSH")) }
        raw
    }

    /** Запоминает только AT-команды, меняющие состояние адаптера. */
    private fun record(norm: String) {
        if (!norm.startsWith("AT")) return
        val body = norm.substring(2)
        if (body == "Z" || body == "WS" || body == "D") {
            clientAt.clear(); return
        }
        val family = FAMILIES.firstOrNull { f ->
            body.startsWith(f) && (f.length > 1 || (body.length == 2 && body[1].isDigit()))
        } ?: return
        clientAt[family] = norm
    }

    private companion object {
        val LAUNCHER_STATE = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSH7DF")
        val FAMILIES = listOf(
            "CAF", "CFC", "CRA", "SH", "SP", "TP", "ST", "AT", "CF", "CM", "AL", "NL",
            "E", "H", "S", "L", "R",
        )
    }
}

object ObdQueries {
    /** Битовые маски поддерживаемых PID (00, 20, 40 …). null — ЭБУ не отвечает в этом режиме. */
    suspend fun supported(send: ElmSend, mode: Int, maxBase: Int = 0xE0): Set<Int>? {
        val result = mutableSetOf<Int>()
        var base = 0x00
        while (base <= maxBase) {
            val raw = try {
                send(ObdParser.cmd(mode, base), if (base == 0) 12000L else 3000L)
            } catch (e: ElmTimeoutException) {
                ""
            }
            val frames = ObdParser.parseAll(raw, mode, base).filter { it.size >= 4 }
            if (frames.isEmpty()) return if (base == 0) null else result
            for (data in frames) {
                for (i in 0 until 32) {
                    if (data[i / 8] and (0x80 shr (i % 8)) != 0) result.add(base + i + 1)
                }
            }
            if ((base + 0x20) !in result) break
            base += 0x20
        }
        return result
    }
}
