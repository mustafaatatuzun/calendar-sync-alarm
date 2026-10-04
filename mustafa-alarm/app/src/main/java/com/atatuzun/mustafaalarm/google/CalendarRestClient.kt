package com.atatuzun.mustafaalarm.google

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class HttpResult(val code: Int, val body: String)

fun interface HttpCall {
    fun call(method: String, url: String, token: String, body: String): HttpResult
}

/** The two Calendar REST calls the app needs (spec §10.2 step 3). Blocking. */
class CalendarRestClient(private val http: HttpCall = UrlConnectionHttp) {

    fun createCalendar(token: String, summary: String, timeZone: String): String {
        val response = send("POST", "$BASE/calendars", token, """{"summary":${json(summary)},"timeZone":${json(timeZone)}}""")
        return ID.find(response)?.groupValues?.get(1)
            ?: throw IOException("Google did not return a calendar id: ${response.take(300)}")
    }

    fun clearNotifications(token: String, calendarId: String) {
        send(
            "PATCH",
            "$BASE/users/me/calendarList/${URLEncoder.encode(calendarId, "UTF-8")}",
            token,
            """{"defaultReminders":[],"notificationSettings":{"notifications":[]}}""",
        )
    }

    private fun send(method: String, url: String, token: String, body: String): String {
        val result = http.call(method, url, token, body)
        if (result.code !in 200..299) {
            throw IOException("Google Calendar API $method $url failed: HTTP ${result.code} ${result.body.take(300)}")
        }
        return result.body
    }

    companion object {
        const val BASE = "https://www.googleapis.com/calendar/v3"
        private val ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")

        fun json(text: String): String = buildString {
            append('"')
            text.forEach { c ->
                when (c) {
                    '"'  -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}

object UrlConnectionHttp : HttpCall {
    override fun call(method: String, url: String, token: String, body: String): HttpResult {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }
}
