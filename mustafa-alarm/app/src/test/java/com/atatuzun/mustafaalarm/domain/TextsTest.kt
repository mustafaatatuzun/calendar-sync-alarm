package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class TextsTest {
    private val today = LocalDate.of(2026, 10, 2) // Friday

    @Test fun dayLabels() {
        assertEquals("Today", Texts.dayLabel(today, today))
        assertEquals("Tomorrow", Texts.dayLabel(today.plusDays(1), today))
        assertEquals("Monday", Texts.dayLabel(today.plusDays(3), today))
        assertEquals("Mon 12 Oct 2026", Texts.dayLabel(today.plusDays(10), today))
        assertEquals("Thu 1 Oct 2026", Texts.dayLabel(today.minusDays(1), today))
    }

    @Test fun untilNext() {
        val now = t("2026-10-02T09:14")
        assertEquals("Next alarm in 17 minutes", Texts.untilNext(now + 17 * MINUTE, now))
        assertEquals("Next alarm in 1 minute", Texts.untilNext(now + 30_000, now))
        assertEquals("Next alarm in 2 h 5 min", Texts.untilNext(now + 125 * MINUTE, now))
        assertEquals("Next alarm in 2 h", Texts.untilNext(now + 120 * MINUTE, now))
        assertEquals("Next alarm in 3 days", Texts.untilNext(now + 3 * DAY, now))
        assertEquals("Next alarm now", Texts.untilNext(now - 5_000, now))
    }

    @Test fun clocks() {
        assertEquals("07:05", Texts.clock(LocalTime.of(7, 5), true))
        assertEquals("7:05 AM", Texts.clock(LocalTime.of(7, 5), false))
        assertEquals("19:30", Texts.clock(t("2026-10-02T19:30"), ZONE, true))
    }

    @Test fun weekdaysAndRelative() {
        assertEquals("Mon Wed", Texts.weekdays(setOf(WEDNESDAY, MONDAY)))
        assertEquals("5m", Texts.relative(5))
        assertEquals("1h", Texts.relative(60))
        assertEquals("24h", Texts.relative(1440))
    }

    private val LOCALE = Locale.ENGLISH
    private val T = LocalTime.of(8, 30)
    private val D = LocalDate.of(2026, 10, 3)  // Saturday

    @Test fun `summary Once with date`() {
        assertEquals("Once on Sat 3 Oct 2026", Texts.recurrenceSummary(RecurrenceRule.Once, T, D, true, LOCALE))
    }

    @Test fun `summary Once without date`() {
        assertEquals("Does not repeat", Texts.recurrenceSummary(RecurrenceRule.Once, T, null, true, LOCALE))
    }

    @Test fun `summary Daily`() {
        assertEquals("Daily", Texts.recurrenceSummary(RecurrenceRule.Daily, T, null, true, LOCALE))
    }

    @Test fun `summary EveryWeekday`() {
        assertEquals("Every weekday (Mon-Fri)", Texts.recurrenceSummary(RecurrenceRule.EveryWeekday, T, null, true, LOCALE))
    }

    @Test fun `summary Weekly single day`() {
        val r = RecurrenceRule.Weekly(setOf(DayOfWeek.SATURDAY))
        assertEquals("Weekly on Saturday", Texts.recurrenceSummary(r, T, null, true, LOCALE))
    }

    @Test fun `summary Weekly multiple days`() {
        val r = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        assertEquals("Weekly on Mon, Wed, Fri", Texts.recurrenceSummary(r, T, null, true, LOCALE))
    }

    @Test fun `summary MonthlyDay`() {
        assertEquals("Monthly on day 3", Texts.recurrenceSummary(RecurrenceRule.MonthlyDay(3), T, null, true, LOCALE))
    }

    @Test fun `summary MonthlyNthWeekday first`() {
        val r = RecurrenceRule.MonthlyNthWeekday(1, DayOfWeek.SATURDAY)
        assertEquals("Monthly on the first Saturday", Texts.recurrenceSummary(r, T, null, true, LOCALE))
    }

    @Test fun `summary MonthlyNthWeekday last`() {
        val r = RecurrenceRule.MonthlyNthWeekday(-1, DayOfWeek.FRIDAY)
        assertEquals("Monthly on the last Friday", Texts.recurrenceSummary(r, T, null, true, LOCALE))
    }

    @Test fun `summary Yearly with date`() {
        assertEquals("Annually on October 3", Texts.recurrenceSummary(RecurrenceRule.Yearly, T, D, true, LOCALE))
    }

    @Test fun `summary Yearly without date falls back to generic`() {
        assertEquals("Annually", Texts.recurrenceSummary(RecurrenceRule.Yearly, T, null, true, LOCALE))
    }
}
