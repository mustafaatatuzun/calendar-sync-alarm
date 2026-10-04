package com.atatuzun.mustafaalarm.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CalendarRestClientTest {
    private val calls = mutableListOf<List<String>>()

    private fun client(code: Int, body: String) = CalendarRestClient { method, url, token, payload ->
        calls += listOf(method, url, token, payload)
        HttpResult(code, body)
    }

    @Test
    fun createCalendar_postsSummaryAndZone_returnsTheId() {
        val response = """{"kind":"calendar#calendar","etag":"\"abc\"","id":"x1y2@group.calendar.google.com","summary":"Alarms"}"""
        val id = client(200, response).createCalendar("tok", "Alarms", "Asia/Famagusta")
        assertEquals("x1y2@group.calendar.google.com", id)
        assertEquals(
            listOf("POST", "https://www.googleapis.com/calendar/v3/calendars", "tok", """{"summary":"Alarms","timeZone":"Asia/Famagusta"}"""),
            calls.single(),
        )
    }

    @Test
    fun clearNotifications_patchesTheEncodedCalendarListEntry() {
        client(200, "{}").clearNotifications("tok", "x1y2@group.calendar.google.com")
        val call = calls.single()
        assertEquals("PATCH", call[0])
        assertEquals("https://www.googleapis.com/calendar/v3/users/me/calendarList/x1y2%40group.calendar.google.com", call[1])
        assertEquals("""{"defaultReminders":[],"notificationSettings":{"notifications":[]}}""", call[3])
    }

    @Test
    fun httpErrors_becomeIOExceptions() {
        assertThrows(IOException::class.java) { client(403, """{"error":"insufficientPermissions"}""").createCalendar("tok", "Alarms", "UTC") }
    }

    @Test
    fun json_escapesQuotesBackslashesAndControlCharacters() =
        assertEquals("\"a\\\"b\\\\c\\n\"", CalendarRestClient.json("a\"b\\c\n"))
}
