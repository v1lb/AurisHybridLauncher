package com.aurishybrid.launcher.data.obd

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Проверка адаптера: связь, ответ ELM327 и ЭБУ, затем — допускает ли адаптер
 * второе одновременное подключение и как он себя при этом ведёт.
 * Запускается, когда собственный сеанс лаунчера остановлен.
 */
class AdapterProbe(private val context: Context) {

    enum class Verdict { NO_CONNECTION, SINGLE_CLIENT, SECOND_KICKS_FIRST, BROADCAST, ISOLATED, SECOND_NOT_SERVED, NOT_TESTED }

    data class Result(val verdict: Verdict, val report: String)

    suspend fun run(cfg: TransportConfig): Result {
        val r = StringBuilder()
        fun line(s: String) {
            r.appendLine(s)
        }
        line("Проверка: ${cfg.label}")
        if (cfg is TransportConfig.Wifi) {
            line(if (Transports.wifiNetwork(context) == null) "Wi-Fi: НЕ подключён" else "Wi-Fi: подключён")
        }

        val t0 = SystemClock.elapsedRealtime()
        val a = try {
            Elm327Client(Transports.open(context, cfg))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            line("Подключение 1: ОШИБКА — ${e.message}")
            line("Если Hybrid Assistant сейчас подключён к адаптеру, он может занимать единственное подключение.")
            return Result(Verdict.NO_CONNECTION, r.toString())
        }
        line("Подключение 1: OK за ${SystemClock.elapsedRealtime() - t0} мс")

        a.use { first ->
            try {
                line("ATZ → " + first.command("ATZ", 5000L).oneLine())
                delay(500); first.drain()
                for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0")) first.command(c)
                line("ATI → " + first.command("ATI").oneLine())
                line("ATRV (напряжение на разъёме OBD) → " + first.command("ATRV").oneLine())
                first.command("ATSP0")
                val r0100 = first.command("0100", 12000L)
                line("0100 → " + r0100.oneLine())
                line(
                    if (ObdParser.parse(r0100, 1, 0) != null) "ЭБУ отвечает: да"
                    else "ЭБУ не ответил: включите зажигание (READY) и повторите"
                )
                line("Протокол (ATDPN) → " + first.command("ATDPN").oneLine())
            } catch (e: IOException) {
                line("Обмен с адаптером прерван: ${e.message}")
                return Result(Verdict.NO_CONNECTION, r.toString())
            }

            if (cfg !is TransportConfig.Wifi) {
                line("Одновременные подключения для Bluetooth не проверяются: SPP-адаптер держит одно соединение.")
                line(advice(Verdict.SINGLE_CLIENT))
                return Result(Verdict.NOT_TESTED, r.toString())
            }

            line("--- Второе одновременное подключение ---")
            val b = try {
                Elm327Client(Transports.open(context, cfg))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                line("Подключение 2: отклонено (${e.message})")
                line(advice(Verdict.SINGLE_CLIENT))
                return Result(Verdict.SINGLE_CLIENT, r.toString())
            }
            b.use { second ->
                line("Подключение 2: принято")
                delay(300)
                second.drain()
                val firstAlive = try {
                    first.command("ATRV", 3000L).isNotBlank()
                } catch (e: IOException) {
                    false
                }
                if (!firstAlive) {
                    line("Подключение 1 перестало отвечать после открытия второго.")
                    line(advice(Verdict.SECOND_KICKS_FIRST))
                    return Result(Verdict.SECOND_KICKS_FIRST, r.toString())
                }
                delay(400)
                val leaked = second.drain()
                if (Regex("\\d{1,2}\\.\\d\\s*V").containsMatchIn(leaked)) {
                    line("Ответ на запрос подключения 1 пришёл и в подключение 2 (общий канал).")
                    line(advice(Verdict.BROADCAST))
                    return Result(Verdict.BROADCAST, r.toString())
                }
                val secondOk = try {
                    second.command("ATRV", 3000L).isNotBlank()
                } catch (e: IOException) {
                    false
                }
                val v = if (secondOk) Verdict.ISOLATED else Verdict.SECOND_NOT_SERVED
                line(if (secondOk) "Подключение 2 получает свои ответы отдельно." else "Подключение 2 открыто, но не обслуживается.")
                line(advice(v))
                return Result(v, r.toString())
            }
        }
    }

    private fun String.oneLine() = replace('\r', ' ').replace('\n', ' ').trim()

    private fun advice(v: Verdict): String = "ВЫВОД: " + when (v) {
        Verdict.SINGLE_CLIENT, Verdict.SECOND_KICKS_FIRST, Verdict.SECOND_NOT_SERVED, Verdict.NOT_TESTED ->
            "адаптер обслуживает одно приложение. Используйте режим «прокси» (Hybrid Assistant → 127.0.0.1:35000), " +
                "если в Hybrid Assistant можно указать IP и порт; иначе — режим «только когда лаунчер на экране»."
        Verdict.BROADCAST ->
            "подключения делят один канал ELM327: ответы видят оба, команды смешиваются. Прямая одновременная работа " +
                "небезопасна — нужен режим «прокси»."
        Verdict.ISOLATED ->
            "адаптер принимает несколько клиентов, но за ним один чип ELM327: AT-настройки и запросы двух приложений " +
                "будут мешать друг другу. Рекомендуется режим «прокси»."
        Verdict.NO_CONNECTION -> "нет связи с адаптером."
    }
}
