package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.domain.FakeCalendarAccess.Companion.CAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime

class AlarmStoreEditTest {
    private val clock = TestClock(t("2026-10-02T10:00")) // Friday
    private val cal = FakeCalendarAccess()
    private val local = InMemoryLocalStore()
    private var calendarId: Long? = CAL
    private val store = AlarmStore(cal, local, clock, { calendarId }, { 30 }, { true })

    /** Builds an AlarmInput; days convenience param converts to RecurrenceRule.Weekly (or Once when empty). */
    private fun input(
        time: String,
        date: LocalDate? = null,
        days: Set<DayOfWeek> = emptySet(),
        message: String = "m",
        sound: String? = null,
        recurrence: RecurrenceRule? = null,
    ) = AlarmInput(
        LocalTime.parse(time),
        date,
        recurrence ?: if (days.isEmpty()) RecurrenceRule.Once else RecurrenceRule.Weekly(days),
        message,
        sound,
    )

    private fun fakeStore(cal: FakeCalendarAccess = FakeCalendarAccess()): AlarmStore =
        AlarmStore(cal, InMemoryLocalStore(), clock, { CAL }, { 30 }, { true })

    private fun create(i: AlarmInput) = (store.create(i) as SaveResult.Saved).eventId

    @Test
    fun create_laterToday() {
        val id = create(input("11:00", message = "Fırat şap makinesi teklif ver"))
        val e = cal.event(id)!!
        assertEquals(t("2026-10-02T11:00"), e.dtStart)
        assertEquals(t("2026-10-02T11:15"), e.dtEnd)
        assertNull(e.rrule)
        assertEquals("Fırat şap makinesi teklif ver", e.title)
        assertEquals("Asia/Famagusta", e.timeZone)
        assertFalse(e.isOff)
    }

    @Test
    fun create_timeAlreadyPassed_isTomorrow() =
        assertEquals(t("2026-10-03T09:00"), cal.event(create(input("09:00")))!!.dtStart)

    @Test
    fun create_trimsMessage_keepsUnicode() {
        // Turkish (ş, ı, ğ), emoji and newline round-trip verbatim; leading/trailing spaces are stripped.
        assertEquals("Fırat şap doğru 🚚\nmakinesi", cal.event(create(input("11:00", message = "  Fırat şap doğru 🚚\nmakinesi  ")))!!.title)
        // Blank-only message falls back to the default title.
        assertEquals(DEFAULT_TITLE, cal.event(create(input("11:00", message = "   ")))!!.title)
        // A 320-character Unicode-dense message must survive create → details without truncation.
        val long300 = "ğşıö".repeat(80) // 4 × 80 = 320 chars, all non-ASCII
        val longId = create(input("11:00", message = long300))
        assertEquals(long300, store.details(longId)!!.message)
    }

    @Test
    fun create_weekly() {
        val e = cal.event(create(input("08:00", days = setOf(MONDAY, WEDNESDAY))))!!
        assertEquals(t("2026-10-05T08:00"), e.dtStart)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", e.rrule)
        assertEquals("PT15M", e.duration)
        assertNull(e.dtEnd)
    }

    @Test
    fun create_onDate_andPastDateIsRejected() {
        assertEquals(t("2026-10-10T09:00"), cal.event(create(input("09:00", date = LocalDate.of(2026, 10, 10))))!!.dtStart)
        val before = cal.events.size
        assertEquals(SaveResult.TimeInPast, store.create(input("09:00", date = LocalDate.of(2026, 10, 2))))
        assertEquals(before, cal.events.size)
    }

    @Test
    fun create_savesSound_andHistoryOnlyWhenAsked() {
        val id = create(input("08:30", sound = "content://media/1"))
        assertEquals("content://media/1", local.sounds[id])
        assertEquals(listOf((8 * 60 + 30) to t("2026-10-02T10:00")), local.history)
        store.create(input("08:45"), recordHistory = false)
        assertEquals(1, local.history.size)
    }

    @Test
    fun create_withoutCalendar() {
        calendarId = null
        assertEquals(SaveResult.NoCalendar, store.create(input("11:00")))
    }

    @Test
    fun details_describesEachKind() {
        val weekly = store.details(create(input("08:00", days = setOf(MONDAY, WEDNESDAY))))!!
        assertEquals(AlarmKind.SERIES, weekly.kind)
        assertEquals(RecurrenceRule.Weekly(setOf(MONDAY, WEDNESDAY)), weekly.recurrence)
        assertEquals(LocalTime.of(8, 0), weekly.time)
        assertNull(weekly.date)

        val oneOff = store.details(create(input("11:00")))!!
        assertEquals(AlarmKind.ONE_OFF, oneOff.kind)
        assertEquals(LocalDate.of(2026, 10, 2), oneOff.date)

        // FREQ=DAILY is now parseable → SERIES kind (RecurrenceRule.Daily)
        val pcId = cal.insertEvent(CAL, "Daily PC", EventTiming.Recurring(t("2026-09-01T07:00"), "FREQ=DAILY", "P3600S"), "UTC")
        val pc = store.details(pcId)!!
        assertEquals(AlarmKind.SERIES, pc.kind)
        assertEquals(RecurrenceRule.Daily, pc.recurrence)
        assertEquals(LocalTime.of(7, 0), pc.time)

        // FREQ=DAILY;COUNT=60 is NOT parseable (COUNT disallowed) → OTHER_REPEAT with Once recurrence
        val pcCountId = cal.insertEvent(CAL, "PC Count", EventTiming.Recurring(t("2026-09-01T07:00"), "FREQ=DAILY;COUNT=60", "P3600S"), "UTC")
        val pcCount = store.details(pcCountId)!!
        assertEquals(AlarmKind.OTHER_REPEAT, pcCount.kind)
        assertEquals(RecurrenceRule.Once, pcCount.recurrence)
    }

    @Test
    fun update_movesOneOff_keepsPcLength() {
        val id = cal.insertEvent(CAL, "PC meeting", EventTiming.Single(t("2026-10-03T09:00"), t("2026-10-03T10:00")), "UTC")
        store.update(id, input("14:30", date = LocalDate.of(2026, 10, 3), message = "PC meeting"))
        val e = cal.event(id)!!
        assertEquals(t("2026-10-03T14:30"), e.dtStart)
        assertEquals(t("2026-10-03T15:30"), e.dtEnd)
        assertEquals("Asia/Famagusta", e.timeZone)
    }

    @Test
    fun update_nonWeeklySeries_keepsRule_changesTimeOfDayOnly() {
        val id = cal.insertEvent(CAL, "Daily PC", EventTiming.Recurring(t("2026-09-01T07:00"), "FREQ=DAILY;COUNT=60", "P3600S"), "Asia/Famagusta")
        store.update(id, input("06:45", message = "Daily PC"))
        val e = cal.event(id)!!
        assertEquals("FREQ=DAILY;COUNT=60", e.rrule)
        assertEquals(t("2026-09-01T06:45"), e.dtStart)
        assertEquals("P3600S", e.duration)
    }

    @Test
    fun update_oneOffIntoWeekly() {
        val id = create(input("11:00"))
        store.update(id, input("12:00", days = setOf(FRIDAY)))
        val e = cal.event(id)!!
        assertEquals("FREQ=WEEKLY;BYDAY=FR", e.rrule)
        assertEquals(t("2026-10-02T12:00"), e.dtStart)
        assertEquals("PT15M", e.duration)
        assertNull(e.dtEnd)
    }

    @Test
    fun update_turnsTheAlarmOn_andRejectsMissingOnes() {
        val id = create(input("11:00"))
        store.setEnabled(id, false)
        store.update(id, input("11:30"))
        assertFalse(cal.event(id)!!.isOff)
        assertEquals(SaveResult.Missing, store.update(999, input("11:30")))
    }

    @Test
    fun delete_removesEventAndSound() {
        val id = create(input("11:00", sound = "content://media/1"))
        store.delete(id)
        assertNull(cal.event(id))
        assertNull(local.sounds[id])
    }

    @Test
    fun setEnabled_oneOff_togglesGraphite() {
        val id = create(input("11:00"))
        store.setEnabled(id, false)
        assertTrue(cal.event(id)!!.isOff)
        store.setEnabled(id, true)
        assertFalse(cal.event(id)!!.isOff)
        assertEquals(t("2026-10-02T11:00"), cal.event(id)!!.dtStart)
    }

    @Test
    fun setEnabled_series_alsoGreysItsExceptions() {
        val id = create(input("09:00", days = DayOfWeek.entries.toSet())) // first ring Sat 2026-10-03 09:00
        val ex = cal.insertException(id, t("2026-10-03T09:00"), EventPatch(timing = EventTiming.Single(t("2026-10-03T09:30"), t("2026-10-03T09:45"))))
        store.setEnabled(id, false)
        assertTrue(cal.event(id)!!.isOff)
        assertTrue(cal.event(ex)!!.isOff)
        store.setEnabled(id, true)
        assertFalse(cal.event(id)!!.isOff)
        assertFalse(cal.event(ex)!!.isOff)
    }

    @Test
    fun setEnabled_on_movesPastOneOffToItsNextOccurrence() {
        val id = cal.insertEvent(CAL, "Yesterday", EventTiming.Single(t("2026-10-01T09:00"), t("2026-10-01T09:15")), ZONE.id)
        cal.updateEvent(id, EventPatch(color = ColorPatch.GRAPHITE))
        store.setEnabled(id, true)
        val e = cal.event(id)!!
        assertEquals(t("2026-10-03T09:00"), e.dtStart)
        assertEquals(t("2026-10-03T09:15"), e.dtEnd)
        assertFalse(e.isOff)
    }

    // ---- New recurrence rule tests (Task 3) -----------------------------------------------

    @Test fun `create Daily alarm stores FREQ=DAILY`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.Daily,
            message = "wake",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        val details = store.details(id)
        assertEquals(RecurrenceRule.Daily, details!!.recurrence)
    }

    @Test fun `create MonthlyDay 3 round-trips`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.MonthlyDay(3),
            message = "pay rent",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        assertEquals(RecurrenceRule.MonthlyDay(3), store.details(id)!!.recurrence)
    }

    @Test fun `create MonthlyNthWeekday 1 MO round-trips`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.MonthlyNthWeekday(1, DayOfWeek.MONDAY),
            message = "standup",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        assertEquals(
            RecurrenceRule.MonthlyNthWeekday(1, DayOfWeek.MONDAY),
            store.details(id)!!.recurrence,
        )
    }

    @Test fun `create EveryWeekday round-trips`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.EveryWeekday,
            message = "work",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        assertEquals(RecurrenceRule.EveryWeekday, store.details(id)!!.recurrence)
    }

    @Test fun `create Yearly with date round-trips`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = LocalDate.of(2027, 1, 1),
            recurrence = RecurrenceRule.Yearly,
            message = "ny",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        assertEquals(RecurrenceRule.Yearly, store.details(id)!!.recurrence)
    }

    @Test fun `create Yearly without date returns NeedsDateForYearly`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.Yearly,
            message = "oops",
            soundUri = null,
        ))
        assertEquals(SaveResult.NeedsDateForYearly, saved)
    }

    @Test fun `update weekly to MonthlyDay rewrites RRULE`() {
        val store = fakeStore()
        val id = (store.create(AlarmInput(
            time = LocalTime.of(8, 0), date = null,
            recurrence = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY)),
            message = "m", soundUri = null,
        )) as SaveResult.Saved).eventId

        store.update(id, AlarmInput(
            time = LocalTime.of(8, 0), date = null,
            recurrence = RecurrenceRule.MonthlyDay(3),
            message = "m", soundUri = null,
        ))
        assertEquals(RecurrenceRule.MonthlyDay(3), store.details(id)!!.recurrence)
    }

    // Review Focus #5: UNTIL on an existing series is preserved and stays OTHER_REPEAT
    @Test fun `event with UNTIL is seen as OTHER_REPEAT with null parsed recurrence`() {
        val cal = FakeCalendarAccess()
        val id = cal.insertRawEvent(rrule = "FREQ=MONTHLY;BYMONTHDAY=3;UNTIL=20271231T235959Z")
        val store = fakeStore(cal)
        val details = store.details(id)
        assertEquals(AlarmKind.OTHER_REPEAT, details!!.kind)
        // parse returned null → recurrence is Once; UI gates editing on kind, not recurrence
        assertEquals(RecurrenceRule.Once, details.recurrence)
    }
}
