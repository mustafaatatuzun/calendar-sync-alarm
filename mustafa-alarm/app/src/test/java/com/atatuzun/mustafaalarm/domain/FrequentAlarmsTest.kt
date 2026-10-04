package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class FrequentAlarmsTest {
    @Test
    fun topFive_byCount_thenMostRecent_withinSixtyDays() {
        val now = t("2026-10-02T10:00")
        val history = listOf(
            450 to now - DAY, 450 to now - 2 * DAY, 450 to now - 3 * DAY, // 07:30 ×3
            480 to now - DAY, 480 to now - 5 * DAY,                        // 08:00 ×2
            540 to now - 10 * DAY,                                         // 09:00
            600 to now - HOUR,                                             // 10:00 (newest single)
            360 to now - 61 * DAY, 360 to now - 62 * DAY, 360 to now - 70 * DAY, // 06:00, too old
            420 to now - 20 * DAY,                                         // 07:00
            330 to now - 30 * DAY,                                         // 05:30
        )
        assertEquals(
            listOf("07:30", "08:00", "10:00", "09:00", "07:00").map(LocalTime::parse),
            FrequentAlarms.top(history, now),
        )
    }

    @Test
    fun emptyHistory() = assertTrue(FrequentAlarms.top(emptyList(), 0).isEmpty())
}
