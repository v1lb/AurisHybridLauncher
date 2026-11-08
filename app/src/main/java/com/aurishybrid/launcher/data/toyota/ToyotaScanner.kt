package com.aurishybrid.launcher.data.toyota

import com.aurishybrid.launcher.data.obd.ElmSend
import com.aurishybrid.launcher.data.obd.ElmTimeoutException
import com.aurishybrid.launcher.data.obd.ObdParser
import com.aurishybrid.launcher.data.obd.ObdQueries
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * Поиск ЭБУ, отвечающих в режимах 01 и 21 (Toyota), и снимок сырых ответов всех PID режима 21.
 * Два круга с паузой — чтобы по изменившимся байтам найти «живые» параметры.
 * Отчёт — текст, который можно сверить с показаниями Hybrid Assistant и превратить в профиль.
 */
class ToyotaScanner(private val progress: (String) -> Unit) {

    suspend fun scan(send: ElmSend): String {
        val r = StringBuilder()
        r.appendLine("Toyota ECU scan ${LocalDateTime.now()}")
        r.appendLine("ATI: ${safe { send("ATI", 2000L) }.oneLine()}")
        r.appendLine("ATDPN: ${safe { send("ATDPN", 2000L) }.oneLine()}")
        for (h in HEADERS) {
            progress("ЭБУ $h…")
            safe { send("ATSH$h", 1000L) }
            val std = ObdQueries.supported(send, 0x01, 0x40)
            r.appendLine("$h mode 01: " + (std?.sorted()?.joinToString(" ") { ObdParser.hex2(it) } ?: "нет ответа"))
            val ext = ObdQueries.supported(send, 0x21)
            if (ext == null) {
                r.appendLine("$h mode 21: нет ответа")
                continue
            }
            val pids = ext.filter { it % 0x20 != 0 }.sorted()
            r.appendLine("$h mode 21: " + pids.joinToString(" ") { ObdParser.hex2(it) })
            for (round in 1..2) {
                for (pid in pids) {
                    progress("ЭБУ $h 21${ObdParser.hex2(pid)} (круг $round)")
                    val raw = safe { send(ObdParser.cmd(0x21, pid), 2500L) }
                    val data = ObdParser.parse(raw, 0x21, pid)
                    r.appendLine(
                        "R$round $h 21${ObdParser.hex2(pid)} = " +
                            (data?.joinToString(" ") { ObdParser.hex2(it) } ?: "? ${raw.oneLine()}")
                    )
                }
                if (round == 1) delay(1500)
            }
        }
        safe { send("ATSH7DF", 1000L) }
        progress("готово")
        return r.toString()
    }

    private suspend fun safe(block: suspend () -> String): String = try {
        block()
    } catch (e: ElmTimeoutException) {
        "TIMEOUT"
    }

    private fun String.oneLine() = replace('\r', ' ').replace('\n', ' ').trim()

    private companion object {
        val HEADERS = listOf("7E0", "7E1", "7E2", "7E3", "7E4", "7E5", "7E6", "7E7")
    }
}
