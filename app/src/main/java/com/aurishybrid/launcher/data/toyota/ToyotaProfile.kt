package com.aurishybrid.launcher.data.toyota

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Один Toyota-параметр (обычно mode 21 к конкретному ЭБУ).
 * role — что это для лаунчера: HV_SOC, HV_BATT_TEMP, HV_CURRENT (или null — только диагностика).
 * expr == null — показываются только сырые байты.
 * positiveMeans — для HV_CURRENT: "discharge" или "charge" (знак тока; задаётся после проверки).
 */
data class ToyotaEntry(
    val id: String,
    val name: String,
    val role: String?,
    val header: String,
    val mode: Int,
    val pid: Int,
    val expr: ByteExpr?,
    val unit: String,
    val min: Double?,
    val max: Double?,
    val positiveMeans: String?,
    val source: String,
)

data class ToyotaReading(
    val entry: ToyotaEntry,
    val value: Double?,
    val rawHex: String?,
    val status: String,
)

object ToyotaProfileLoader {
    private const val FILE = "toyota_profile.json"

    fun load(context: Context): List<ToyotaEntry> {
        val user = File(context.filesDir, FILE)
        val text = if (user.exists()) user.readText()
        else context.assets.open(FILE).bufferedReader().use { it.readText() }
        return runCatching { parse(text) }.getOrElse { emptyList() }
    }

    /** Проверяет и сохраняет пользовательский профиль. Возвращает число параметров. */
    fun import(context: Context, text: String): Int {
        val entries = parse(text)
        File(context.filesDir, FILE).writeText(text)
        return entries.size
    }

    fun resetToBuiltIn(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    fun parse(text: String): List<ToyotaEntry> {
        val arr = JSONObject(text).getJSONArray("entries")
        return (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            val id = o.getString("id")
            val exprText = o.optString("expr", "").trim()
            ToyotaEntry(
                id = id,
                name = o.optString("name", id),
                role = o.optString("role", "").ifBlank { null },
                header = o.getString("header").uppercase(),
                mode = o.getString("mode").toInt(16),
                pid = o.getString("pid").toInt(16),
                expr = if (exprText.isEmpty()) null else try {
                    ByteExpr.parse(exprText)
                } catch (e: IllegalStateException) {
                    throw IllegalArgumentException("$id: ошибка формулы — ${e.message}")
                },
                unit = o.optString("unit", ""),
                min = if (o.has("min")) o.getDouble("min") else null,
                max = if (o.has("max")) o.getDouble("max") else null,
                positiveMeans = o.optString("positiveMeans", "").ifBlank { null },
                source = o.optString("source", ""),
            )
        }
    }
}
