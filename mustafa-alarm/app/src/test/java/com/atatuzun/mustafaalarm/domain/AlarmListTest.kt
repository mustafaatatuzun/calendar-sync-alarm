package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.domain.FakeCalendarAccess.Companion.CAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class AlarmListTest {
    private val clock = TestClock(t("2026-10-02T10:00")) // Friday
    private val cal = FakeCalendarAccess()
    private val local = InMemoryLocalStore()
    private var calendarId: Long? = CAL
    private var available = true
    private val store = AlarmStore(cal, local, clock, { calendarId }, { 30 }, { available })

    private fun ready() = store.list() as AlarmListState.Ready
    private fun items() = ready().sections.flatMap { it.items }
    private fun oneOff(start: String, title: String) =
        cal.insertEvent(CAL, title, EventTiming.Single(t(start), t(start) + 15 * MINUTE), ZONE.id)
    private fun series(start: String, rule: String, title: String) =
        cal.insertEvent(CAL, title, EventTiming.Recurring(t(start), rule, "PT15M"), ZONE.id)

    @Test
    fun groupsByDay_sorted_eachAlarmOnce() {
        oneOff("2026-10-02T12:00", "Noon")
        series("2026-10-01T07:00", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU", "Daily")
        oneOff("2026-10-05T09:00", "Monday")
        val sections = ready().sections
        assertEquals(listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5)), sections.map { it.day })
        assertEquals(listOf("Noon"), sections[0].items.map { it.title })
        assertEquals(listOf("Daily"), sections[1].items.map { it.title })
        assertEquals(AlarmKind.SERIES, sections[1].items.single().kind)
        assertEquals(1, items().count { it.title == "Daily" })
        assertEquals(t("2026-10-02T12:00"), ready().nextRing)
    }

    @Test
    fun offOneOffInTheFuture_isGreyOnItsDay() {
        val id = oneOff("2026-10-03T09:00", "Off")
        store.setEnabled(id, false)
        val item = items().single()
        assertFalse(item.on)
        assertEquals(t("2026-10-03T09:00"), item.shownAt)
        assertNull(ready().nextRing)
    }

    @Test
    fun doneOneOffs_stayVisibleUntilTheEndOfTheirDay() {
        val today = oneOff("2026-10-02T08:00", "Done today")
        store.stop(InstanceKey(today, t("2026-10-02T08:00")), clock.millis())
        val yesterday = oneOff("2026-10-01T08:00", "Done yesterday")
        store.stop(InstanceKey(yesterday, t("2026-10-01T08:00")), clock.millis())
        assertEquals(listOf("Done today"), items().map { it.title })
        assertFalse(items().single().on)
    }

    @Test
    fun snoozedOccurrence_movesTheSeriesRowToTheSnoozeTime() {
        clock.set("2026-10-02T09:00")
        val id = series("2026-10-02T09:00", "FREQ=DAILY", "Daily")
        store.snooze(InstanceKey(id, t("2026-10-02T09:00")), clock.millis())
        val item = items().single()
        assertEquals(id, item.eventId)
        assertEquals(t("2026-10-02T09:30"), item.shownAt)
    }

    @Test
    fun stoppedOccurrence_showsTheNextOne() {
        clock.set("2026-10-02T09:00")
        val id = series("2026-10-02T09:00", "FREQ=WEEKLY;BYDAY=FR", "Fridays")
        store.stop(InstanceKey(id, t("2026-10-02T09:00")), clock.millis())
        assertEquals(t("2026-10-09T09:00"), items().single().shownAt)
    }

    @Test
    fun offSeries_isGreyAtItsNextOccurrence() {
        val id = series("2026-10-01T07:00", "FREQ=DAILY", "Daily PC")
        store.setEnabled(id, false)
        val item = items().single()
        assertFalse(item.on)
        // FREQ=DAILY is now parseable as RecurrenceRule.Daily → kind is SERIES (not OTHER_REPEAT)
        assertEquals(AlarmKind.SERIES, item.kind)
        assertEquals(t("2026-10-03T07:00"), item.shownAt)
    }

    @Test
    fun allDayEventsAreIgnored() {
        cal.events[500] = EventRow(500, "Holiday", t("2026-10-03T00:00"), t("2026-10-04T00:00"), null, null, true, null, null, null, null, "UTC")
        assertTrue(ready().sections.isEmpty())
    }

    @Test
    fun grouping_usesDeviceZone_notEventZone() {
        val start = Instant.parse("2026-10-02T21:30:00Z").toEpochMilli() // 00:30 on 3 Oct in Famagusta
        cal.insertEvent(CAL, "UTC event", EventTiming.Single(start, start + HOUR), "UTC")
        assertEquals(LocalDate.of(2026, 10, 3), ready().sections.single().day)
    }

    @Test
    fun list_reportsMissingCalendar_andUnavailable() {
        cal.exists = false
        assertEquals(AlarmListState.CalendarMissing, store.list())
        available = false
        assertEquals(AlarmListState.Unavailable, store.list())
        calendarId = null
        assertEquals(AlarmListState.NoCalendar, store.list())
    }

    // ---- New recurrence-surface tests (Task 3) --------------------------------------------

    @Test fun `list surfaces Daily recurrence`() {
        series("2026-10-02T10:01", "FREQ=DAILY", "Daily")
        val item = items().first()
        assertEquals(RecurrenceRule.Daily, item.recurrence)
    }

    @Test fun `list surfaces MonthlyDay recurrence`() {
        series("2026-10-15T10:00", "FREQ=MONTHLY;BYMONTHDAY=15", "Monthly 15th")
        val item = items().first()
        assertEquals(RecurrenceRule.MonthlyDay(15), item.recurrence)
    }
}
