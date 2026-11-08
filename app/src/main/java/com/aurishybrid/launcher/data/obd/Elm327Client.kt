package com.aurishybrid.launcher.data.obd

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/** Стандартные PID SAE J1979, режим 01. */
object Pid {
    const val COOLANT = 0x05
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val MAF = 0x10
    const val MODULE_VOLTAGE = 0x42
    const val HYBRID_BATTERY_REMAINING = 0x5B
    const val FUEL_RATE = 0x5E
}

/** Адаптер не прислал '>' вовремя (соединение может быть живым). */
class ElmTimeoutException(message: String) : IOException(message)

/** Низкоуровневый обмен с ELM327 поверх любого транспорта (TCP или Bluetooth SPP). */
class Elm327Client(private val transport: ObdTransport) : Closeable {

    val label: String get() = transport.label

    var description: String = "ELM327"
        private set

    /** Настройки адаптера сразу после ATZ — нужны прокси, чтобы вернуть клиенту ожидаемое состояние. */
    var defaults: Map<String, String> = mapOf("E" to "ATE1", "L" to "ATL0", "S" to "ATS1", "H" to "ATH0", "SH" to "ATSH7DF")
        private set

    /** Ответ целиком, включая эхо и завершающий '>'. */
    suspend fun commandRaw(cmd: String, timeoutMs: Long = 3000L): String = withContext(Dispatchers.IO) {
        val input = transport.input
        val output = transport.output
        while (input.available() > 0) input.read()
        output.write("$cmd\r".toByteArray(Charsets.US_ASCII))
        output.flush()
        val sb = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (input.available() > 0) {
                val b = input.read()
                if (b < 0) throw IOException("адаптер закрыл соединение")
                val c = b.toChar()
                if (c != '\u0000') sb.append(c)
                if (c == '>') break
            } else {
                if (SystemClock.elapsedRealtime() > deadline) throw ElmTimeoutException("нет ответа на $cmd")
                delay(5)
            }
        }
        sb.toString()
    }

    suspend fun command(cmd: String, timeoutMs: Long = 3000L): String =
        commandRaw(cmd, timeoutMs).removeSuffix(">").trim()

    /** Всё, что адаптер прислал без запроса (для теста одновременных подключений). */
    suspend fun drain(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        while (transport.input.available() > 0) sb.append(transport.input.read().toChar())
        sb.toString()
    }

    suspend fun initialize() {
        command("ATZ", 5000L)
        delay(500)
        drain()
        val probe = commandRaw("ATI")
        val echo = probe.contains("ATI")
        val lf = probe.contains('\n')
        defaults = mapOf(
            "E" to if (echo) "ATE1" else "ATE0",
            "L" to if (lf) "ATL1" else "ATL0",
            "S" to "ATS1",
            "H" to "ATH0",
            "SH" to "ATSH7DF",
        )
        description = probe.replace("ATI", "").replace(">", "").lines()
            .lastOrNull { it.isNotBlank() }?.trim() ?: "ELM327"
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) command(c)
    }

    override fun close() {
        transport.close()
    }
}

object ObdParser {
    fun hex2(v: Int): String = String.format(Locale.US, "%02X", v)

    fun cmd(mode: Int, pid: Int): String = hex2(mode) + hex2(pid)

    private fun isHex(c: Char) = c in '0'..'9' || c in 'A'..'F'

    fun parse(raw: String, mode: Int, pid: Int): IntArray? = parseAll(raw, mode, pid).firstOrNull()

    /**
     * Понимает ответы с пробелами/без, с заголовками CAN (ATH1, 11 бит) и многокадровые
     * ответы ELM ("0:", "1:" …). Возвращает байты данных после PID (A, B, C …).
     */
    fun parseAll(raw: String, mode: Int, pid: Int): List<IntArray> {
        val prefix = hex2(mode + 0x40) + hex2(pid)
        val lines = raw.split('\r', '\n')
            .map { it.replace(" ", "").replace(">", "").uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
        val framed = lines.filter { it.length > 2 && isHex(it[0]) && it[1] == ':' }
        val candidates = if (framed.isNotEmpty()) listOf(framed.joinToString("") { it.substring(2) }) else lines
        val out = mutableListOf<IntArray>()
        for (l in candidates) {
            val idx = l.indexOf(prefix)
            if (idx != 0 && idx != 3 && idx != 5) continue
            val payload = l.substring(idx + prefix.length)
            val hex = payload.substring(0, payload.length - payload.length % 2)
            if (!hex.all { isHex(it) }) continue
            out.add(IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) })
        }
        return out
    }
}
