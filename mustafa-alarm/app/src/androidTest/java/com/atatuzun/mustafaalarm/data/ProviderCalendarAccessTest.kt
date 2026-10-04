package com.atatuzun.mustafaalarm.data

import android.Manifest
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.atatuzun.mustafaalarm.data.calendar.ProviderCalendarAccess
import com.atatuzun.mustafaalarm.debug.LocalCalendars
import com.atatuzun.mustafaalarm.domain.ColorPatch
import com.atatuzun.mustafaalarm.domain.DAY
import com.atatuzun.mustafaalarm.domain.EventPatch
import com.atatuzun.mustafaalarm.domain.EventTiming
import com.atatuzun.mustafaalarm.domain.GRAPHITE_COLOR_KEY
import com.atatuzun.mustafaalarm.domain.HOUR
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.MINUTE
import com.atatuzun.mustafaalarm.domain.Times
import com.atatuzun.mustafaalarm.domain.WeeklyRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ProviderCalendarAccessTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val access = ProviderCalendarAccess(resolver)
    private val zone = ZoneId.systemDefault()
    private val tomorrow = LocalDate.now(zone).plusDays(1)
    private var cal = 0L

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = Times.at(date, LocalTime.of(hour, minute), zone)
    private fun active(from: Long, to: Long) = access.instances(cal, from, to).filter { it.isActive }
    private fun dailyAt10(count: Int = 3) =
        access.insertEvent(cal, "Daily", EventTiming.Recurring(at(tomorrow, 10), "FREQ=DAILY;COUNT=$count", "PT15M"), zone.id)

    @Before fun setUp() { cal = LocalCalendars.create(resolver, "ma-test-${System.nanoTime()}") }
    @After fun tearDown() { LocalCalendars.delete(resolver, cal) }

    @Test
    fun insertSingle_isReadBackWithTitleAndTimes() {
        val start = at(tomorrow, 9)
        val id = access.insertEvent(cal, "Fırat şap makinesi 🚚", EventTiming.Single(start, start + 15 * MINUTE), zone.id)
        val row = active(start - HOUR, start + HOUR).single()
        assertEquals(InstanceKey(id, start), row.key)
        assertEquals("Fırat şap makinesi 🚚", row.title)
        assertEquals(id, row.alarmId)
        val e = access.event(id)!!
        assertEquals(start + 15 * MINUTE, e.dtEnd)
        assertNull(e.rrule)
        assertEquals(zone.id, e.timeZone)
        assertEquals(listOf(id), access.events(cal).map { it.id })
    }

    @Test
    fun weeklySeries_expandsOnItsDays() {
        val first = Times.firstWeeklyStart(setOf(MONDAY, WEDNESDAY), LocalTime.of(8, 0), System.currentTimeMillis(), zone)
        val id = access.insertEvent(cal, "Weekly", EventTiming.Recurring(first, WeeklyRule.build(setOf(MONDAY, WEDNESDAY)), "PT15M"), zone.id)
        val rows = active(first, first + 14 * DAY)
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.eventId == id && Times.localDate(it.begin, zone).dayOfWeek in setOf(MONDAY, WEDNESDAY) })
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", access.event(id)!!.rrule)
    }

    @Test
    fun updateTiming_movesASingle() {
        val start = at(tomorrow, 9)
        val id = access.insertEvent(cal, "Move me", EventTiming.Single(start, start + 15 * MINUTE), zone.id)
        val moved = at(tomorrow, 10)
        access.updateEvent(id, EventPatch(timing = EventTiming.Single(moved, moved + 15 * MINUTE)))
        assertEquals(listOf(moved), active(at(tomorrow, 0), at(tomorrow.plusDays(1), 0)).map { it.begin })
    }

    @Test
    fun graphiteColour_thenDefault() {
        val start = at(tomorrow, 9)
        val id = access.insertEvent(cal, "Colour", EventTiming.Single(start, start + 15 * MINUTE), zone.id)
        access.updateEvent(id, EventPatch(color = ColorPatch.GRAPHITE))
        assertEquals(GRAPHITE_COLOR_KEY, access.event(id)!!.colorKey)
        assertEquals(GRAPHITE_COLOR_KEY, access.instances(cal, start - HOUR, start + HOUR).single().colorKey)
        assertTrue(active(start - HOUR, start + HOUR).isEmpty())
        access.updateEvent(id, EventPatch(color = ColorPatch.DEFAULT))
        assertNull(access.event(id)!!.colorKey)
        assertEquals(1, active(start - HOUR, start + HOUR).size)
    }

    @Test
    fun exception_movesOneOccurrence() {
        val id = dailyAt10()
        val second = at(tomorrow.plusDays(1), 10)
        val ex = access.insertException(id, second, EventPatch(timing = EventTiming.Single(second + 30 * MINUTE, second + 45 * MINUTE)))
        assertEquals(
            listOf(at(tomorrow, 10), second + 30 * MINUTE, at(tomorrow.plusDays(2), 10)),
            active(at(tomorrow, 0), at(tomorrow.plusDays(4), 0)).map { it.begin },
        )
        val exRow = access.event(ex)!!
        assertEquals(id, exRow.originalId)
        assertEquals(second, exRow.originalInstanceTime)
        assertEquals(id, active(second, second + HOUR).single().alarmId)
    }

    @Test
    fun canceledException_removesTheOccurrence() {
        val id = dailyAt10()
        access.insertException(id, at(tomorrow.plusDays(1), 10), EventPatch(canceled = true))
        assertEquals(
            listOf(at(tomorrow, 10), at(tomorrow.plusDays(2), 10)),
            active(at(tomorrow, 0), at(tomorrow.plusDays(4), 0)).map { it.begin },
        )
    }

    @Test
    fun updatingAnException_movesIt() {
        val id = dailyAt10()
        val second = at(tomorrow.plusDays(1), 10)
        val ex = access.insertException(id, second, EventPatch(timing = EventTiming.Single(second + 30 * MINUTE, second + 45 * MINUTE)))
        access.updateEvent(ex, EventPatch(timing = EventTiming.Single(second + HOUR, second + HOUR + 15 * MINUTE)))
        assertEquals(
            listOf(at(tomorrow, 10), second + HOUR, at(tomorrow.plusDays(2), 10)),
            active(at(tomorrow, 0), at(tomorrow.plusDays(4), 0)).map { it.begin },
        )
    }

    @Test
    fun delete_removesEventAndInstances() {
        val start = at(tomorrow, 9)
        val id = access.insertEvent(cal, "Delete me", EventTiming.Single(start, start + 15 * MINUTE), zone.id)
        access.deleteEvent(id)
        assertTrue(active(start - HOUR, start + HOUR).isEmpty())
        assertNull(access.event(id))
        assertTrue(access.events(cal).isEmpty())
    }

    @Test
    fun calendarExists_andSetupAccess() {
        assertTrue(access.calendarExists(cal))
        assertFalse(access.calendarExists(Long.MAX_VALUE / 2))
        val row = access.findCalendars(LocalCalendars.ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL).single { it.id == cal }
        assertTrue(row.syncEvents && row.visible)
        access.enableSyncAndVisibility(cal)
        assertTrue(access.calendarRow(cal)!!.visible)
        assertNotNull(access.dirtyCount(cal))
    }

    /**
     * Carryover #1 (binding): EVENT_TIMEZONE must be in the INSTANCE_COLUMNS projection and
     * carried through to InstanceRow.eventTimezone. Without it, RingPlanner.adjustedRingAt
     * is a silent no-op on cross-zone data (spec Decision #2).
     *
     * We insert an event with explicit timezone "UTC" and verify it is read back from the
     * Instances table correctly regardless of the device's own timezone.
     */
    @Test
    fun instanceRow_carriesEventTimezone() {
        val utcStart = at(tomorrow, 9)
        val id = access.insertEvent(
            cal, "UTC event",
            EventTiming.Single(utcStart, utcStart + 15 * MINUTE),
            "UTC", // explicitly UTC — different from device zone when emulator runs in another zone
        )
        val instance = access.instances(cal, utcStart - HOUR, utcStart + HOUR).single { it.eventId == id }
        assertEquals(
            "InstanceRow.eventTimezone must be populated from Instances.EVENT_TIMEZONE (Carryover #1)",
            "UTC",
            instance.eventTimezone,
        )
    }
}
