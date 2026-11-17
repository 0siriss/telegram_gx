package org.telegram.max

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Проверка внешнего адреса для экрана входа: какой IP и страну видят снаружи. Идёт
 * обычным HTTPS-запросом мимо ядра, тем же сетевым путём, что и соединение с MAX
 * (системный VPN действует на оба одинаково).
 */
object MaxIpCheck {

    class Result(val ip: String, val countryCode: String)

    /** Сервисы по очереди: если первый недоступен, пробуем второй. */
    private val SERVICES = listOf("https://ipwho.is/", "https://ipinfo.io/json")

    /** Блокирующий вызов, звать не с главного потока. */
    fun lookup(): Result? {
        for (url in SERVICES) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.setRequestProperty("Accept", "application/json")
                val body = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val json = JSONObject(body)
                val ip = json.optString("ip", "")
                // ipwho.is отдаёт country_code, ipinfo.io — country.
                val code = json.optString("country_code", "").ifEmpty { json.optString("country", "") }
                if (ip.isNotEmpty() && code.length == 2) return Result(ip, code.uppercase(Locale.ROOT))
            } catch (e: Throwable) {
                MaxLog.e("проверка IP через $url не удалась", e)
            }
        }
        return null
    }

    /** Флаг из двухбуквенного кода страны: пара региональных символов Unicode. */
    fun flag(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
        val sb = StringBuilder()
        for (c in code) sb.appendCodePoint(0x1F1E6 + (c - 'A'))
        return sb.toString()
    }

    fun countryName(code: String): String =
        Locale("", code).getDisplayCountry(Locale("ru")).ifEmpty { code }

    /** «🇷🇺 Россия» */
    fun describe(code: String): String = (flag(code) + " " + countryName(code)).trim()
}
