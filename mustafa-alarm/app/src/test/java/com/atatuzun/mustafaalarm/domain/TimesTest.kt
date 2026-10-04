package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimesTest {
    // 2026-10-02 is a Friday.
    @Test fun floorMinute_dropsSecondsAndMillis() =
        assertEquals(t("2026-10-02T09:14"), Times.floorMinute(t("2026-10-02T09:14") + 59_999))

    @Test fun nextAt_laterToday() =
        assertEquals(t("2026-10-02T11:00"), Times.nextAt(LocalTime.of(11, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun nextAt_alreadyPassed_isTomorrow() =
        assertEquals(t("2026-10-03T09:00"), Times.nextAt(LocalTime.of(9, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun nextAt_exactlyNow_isTomorrow() =
        assertEquals(t("2026-10-03T10:00"), Times.nextAt(LocalTime.of(10, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun plusDays_keepsWallClock_acrossDstEnd() {
        val before = t("2026-10-24T08:00")
        val after = Times.plusDays(before, 1, ZONE)
        assertEquals(t("2026-10-25T08:00"), after)
        assertEquals(25 * HOUR, after - before)
    }

    @Test fun firstWeeklyStart_picksNextMatchingDay() =
        assertEquals(t("2026-10-05T08:00"), Times.firstWeeklyStart(setOf(MONDAY, WEDNESDAY), LocalTime.of(8, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun firstWeeklyStart_todayWhenStillAhead() =
        assertEquals(t("2026-10-02T11:00"), Times.firstWeeklyStart(setOf(FRIDAY), LocalTime.of(11, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun firstWeeklyStart_nextWeekWhenTodayPassed() =
        assertEquals(t("2026-10-09T09:00"), Times.firstWeeklyStart(setOf(FRIDAY), LocalTime.of(9, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun dayParts() {
        assertEquals(t("2026-10-02T00:00"), Times.startOfDay(t("2026-10-02T17:45"), ZONE))
        assertEquals(LocalTime.of(17, 45), Times.localTime(t("2026-10-02T17:45") + 30_000, ZONE))
        assertEquals(LocalDate.of(2026, 10, 2), Times.localDate(t("2026-10-02T23:59"), ZONE))
        assertEquals(t("2026-10-02T07:30"), Times.at(LocalDate.of(2026, 10, 2), LocalTime.of(7, 30, 45), ZONE))
    }

    // ── adjustedRingAt ────────────────────────────────────────────────────────

    /** Same zone: identity — begin is returned verbatim. */
    @Test fun adjustedRingAt_sameZone_returnsBeginVerbatim() {
        val zone = ZoneId.of("Asia/Famagusta")
        val begin = t("2026-10-02T09:00")
        assertEquals(begin, Times.adjustedRingAt(begin, zone, zone))
    }

    /**
     * Cross-zone: the wall-clock time visible in the event's zone (09:00 London BST = UTC+1)
     * is re-interpreted in Famagusta (EEST = UTC+3), yielding 09:00 Famagusta — which is earlier
     * in UTC by 2 hours.
     */
    @Test fun adjustedRingAt_crossZone_reinterpretsLocalTime() {
        val london = ZoneId.of("Europe/London")    // BST = UTC+1 on 2026-10-02
        val famagusta = ZoneId.of("Asia/Famagusta") // EEST = UTC+3 on 2026-10-02
        // begin = epoch of 09:00 London BST (= 08:00 UTC)
        val begin = LocalDateTime.of(2026, 10, 2, 9, 0).atZone(london).toInstant().toEpochMilli()
        val result = Times.adjustedRingAt(begin, london, famagusta)
        // expected = epoch of 09:00 Famagusta (= 06:00 UTC)
        val expected = LocalDateTime.of(2026, 10, 2, 9, 0).atZone(famagusta).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    /**
     * DST spring-forward: 02:30 Europe/Berlin on 2026-03-29 is in the gap (02:00 → 03:00).
     * Java resolves the non-existent local time forward to 03:30 CEST. adjustedRingAt must
     * preserve that resolution when re-interpreting in the device zone.
     */
    @Test fun adjustedRingAt_dstSpringForward() {
        val berlin = ZoneId.of("Europe/Berlin")
        val utc = ZoneId.of("UTC")
        // Construct begin from the gap time; Java forward-shifts it to 03:30 CEST = 01:30 UTC
        val begin = LocalDateTime.of(2026, 3, 29, 2, 30).atZone(berlin).toInstant().toEpochMilli()
        // adjustedRingAt extracts localDt = 03:30 from begin-in-Berlin, then interprets in UTC
        val result = Times.adjustedRingAt(begin, berlin, utc)
        val expected = LocalDateTime.of(2026, 3, 29, 3, 30).atZone(utc).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // ---------- Daily ----------

    @Test fun `firstDailyStart tomorrow when today's time has passed`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = Instant.parse("2026-10-03T10:00:00Z").toEpochMilli()
        val result = Times.firstDailyStart(LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 4, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstDailyStart today when time still in future`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = Instant.parse("2026-10-03T04:00:00Z").toEpochMilli()
        val result = Times.firstDailyStart(LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 3, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // ---------- Every weekday ----------

    @Test fun `firstWeekdayStart skips Saturday to Monday`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Saturday 2026-10-03 10:00 local
        val now = ZonedDateTime.of(2026, 10, 3, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstWeekdayStart(LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstWeekdayStart today when it is a weekday and time in future`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 10, 5, 6, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstWeekdayStart(LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // ---------- Monthly day-N ----------

    @Test fun `firstMonthlyDayStart same month when day still ahead`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 10, 3, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyDayStart(15, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 15, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstMonthlyDayStart next month when day already past this month`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyDayStart(15, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 11, 15, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // Review Focus #4: MonthlyDay(31) in short month
    @Test fun `firstMonthlyDayStart 31 skips 30-day months`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Starting mid-November 2026 (30 days), the next 31st is December 31 2026
        val now = ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyDayStart(31, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 12, 31, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstMonthlyDayStart 31 crossing February`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Jan 31 2027 10:00 -> next is Mar 31 2027 (Feb skipped)
        val now = ZonedDateTime.of(2027, 1, 31, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyDayStart(31, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2027, 3, 31, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // ---------- Monthly Nth weekday ----------

    @Test fun `firstMonthlyNthWeekdayStart first Monday of October 2026`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Sept 30 2026 10:00 -> first Monday of October = October 5
        val now = ZonedDateTime.of(2026, 9, 30, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyNthWeekdayStart(1, DayOfWeek.MONDAY, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstMonthlyNthWeekdayStart last Friday of October 2026`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 10, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyNthWeekdayStart(-1, DayOfWeek.FRIDAY, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 30, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstMonthlyNthWeekdayStart advances when target this month has passed`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Monday 2026-10-05 10:00 -> first-Monday-of-November = 2026-11-02
        val now = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyNthWeekdayStart(1, DayOfWeek.MONDAY, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // ---------- Yearly ----------

    @Test fun `firstYearlyStart same year when anchor still ahead`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 1, 10, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstYearlyStart(LocalDate.of(2026, 10, 3), LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 3, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstYearlyStart next year when anchor already passed`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 11, 10, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstYearlyStart(LocalDate.of(2026, 10, 3), LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2027, 10, 3, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstYearlyStart Feb 29 anchor advances to next leap year`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Anchor on 2024-02-29 (a leap year); from 2026-03-01 the next valid date is 2028-02-29
        val now = ZonedDateTime.of(2026, 3, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstYearlyStart(LocalDate.of(2024, 2, 29), LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2028, 2, 29, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }
}
