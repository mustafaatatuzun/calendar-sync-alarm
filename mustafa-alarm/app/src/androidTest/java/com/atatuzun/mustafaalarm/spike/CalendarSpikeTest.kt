package com.atatuzun.mustafaalarm.spike

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.atatuzun.mustafaalarm.watch.CalendarChangeJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CalendarSpikeTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val account = InstrumentationRegistry.getArguments().getString("account") ?: "your-google-account@gmail.com"
    private val zone = ZoneId.systemDefault()
    private val tomorrow = LocalDate.now(zone).plusDays(1)

    private data class Cal(val id: Long, val syncId: String?, val syncEvents: Int, val visible: Int)

    @Test
    fun risk0_calendarReachesPhone() {
        val deadline = System.currentTimeMillis() + 180_000
        var cal = findSpikeCalendar()
        while (cal == null && System.currentTimeMillis() < deadline) {
            requestSync(); Thread.sleep(5_000); cal = findSpikeCalendar()
        }
        report("risk0.calendar", cal)
        assertNotNull("'$SPIKE_NAME' never appeared in the provider", cal)
    }

    @Test
    fun risk1_appCanEnableSyncAndVisibility() {
        val cal = findSpikeCalendar() ?: error("run risk0 first")
        report("risk1.before", cal)
        val values = ContentValues().apply { put(Calendars.SYNC_EVENTS, 1); put(Calendars.VISIBLE, 1) }
        val rows = resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, cal.id), values, null, null)
        val after = findSpikeCalendar()
        report("risk1.after", "$after rows=$rows")
        assertEquals(1, rows)
        assertEquals(1, after!!.syncEvents)
        assertEquals(1, after.visible)
    }

    @Test
    fun risk2and4_writeEvents() {
        val cal = findSpikeCalendar() ?: error("run risk0 first")
        val oneOffStart = millis(tomorrow, 9)
        val oneOff = insert(cal.id, "spike one-off", oneOffStart, oneOffStart + 15 * MIN, null, null)
        val colourRows = resolver.update(eventUri(oneOff), ContentValues().apply { put(Events.EVENT_COLOR_KEY, "8") }, null, null)
        report("risk2.oneOff", "id=$oneOff colourRows=$colourRows")

        val seriesStart = millis(tomorrow, 10)
        val series = insert(cal.id, "spike series", seriesStart, null, "FREQ=DAILY;COUNT=5", "PT15M")
        requestSync()
        report("risk4.series", "id=$series syncId=${waitForSyncId(series)}")
        val second = millis(tomorrow.plusDays(1), 10)
        val moved = insertException(series, ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, second); put(Events.DTSTART, second + 30 * MIN); put(Events.DTEND, second + 45 * MIN)
        })
        val third = millis(tomorrow.plusDays(2), 10)
        val cancelled = insertException(series, ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, third); put(Events.STATUS, Events.STATUS_CANCELED)
        })
        report("risk4.exceptions", "moved=$moved cancelled=$cancelled")

        // A series that gets an exception before its first upload (offline snooze case).
        val freshStart = millis(tomorrow, 11)
        val fresh = insert(cal.id, "spike unsynced series", freshStart, null, "FREQ=DAILY;COUNT=3", "PT15M")
        val freshMoved = insertException(fresh, ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, freshStart); put(Events.DTSTART, freshStart + 30 * MIN); put(Events.DTEND, freshStart + 45 * MIN)
        })
        report("risk4.unsynced", "series=$fresh moved=$freshMoved")
        requestSync()

        val begins = instanceBegins(cal.id, millis(tomorrow, 0), millis(tomorrow.plusDays(6), 0))
            .filter { it.first == "spike series" }.map { it.second }
        report("risk4.localInstances", begins.joinToString { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().toString() })
        assertEquals(
            listOf(seriesStart, second + 30 * MIN, millis(tomorrow.plusDays(3), 10), millis(tomorrow.plusDays(4), 10)),
            begins,
        )
    }

    @Test
    fun risk2and3_readBack() {
        val cal = findSpikeCalendar() ?: error("run risk0 first")
        val projection = arrayOf(
            Events._ID, Events.TITLE, Events.DTSTART, Events.EVENT_COLOR_KEY, Events.STATUS,
            Events._SYNC_ID, Events.DIRTY, Events.ORIGINAL_ID, Events.DELETED,
        )
        resolver.query(Events.CONTENT_URI, projection, "${Events.CALENDAR_ID}=?", arrayOf(cal.id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val start = Instant.ofEpochMilli(c.getLong(2)).atZone(zone).toLocalDateTime()
                report("event", "id=${c.getLong(0)} title='${c.getString(1)}' start=$start colorKey=${c.getString(3)} status=${c.getString(4)} syncId=${c.getString(5)} dirty=${c.getInt(6)} originalId=${c.getString(7)} deleted=${c.getInt(8)}")
            }
        }
    }

    @Test
    fun risk3_scheduleChangeJob() {
        CalendarChangeJob.schedule(context)
        report("risk3.jobScheduled", true)
    }

    private fun report(key: String, value: Any?) = Log.i(TAG, "$key=$value")
    private fun millis(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun eventUri(id: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, id)

    private fun findSpikeCalendar(): Cal? = resolver.query(
        Calendars.CONTENT_URI,
        arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.SYNC_EVENTS, Calendars.VISIBLE),
        "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CALENDAR_DISPLAY_NAME}=?",
        arrayOf(account, "com.google", SPIKE_NAME), null,
    )?.use { c -> if (c.moveToFirst()) Cal(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3)) else null }

    private fun requestSync() {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(Account(account, "com.google"), CalendarContract.AUTHORITY, extras)
    }

    private fun insert(calId: Long, title: String, start: Long, end: Long?, rrule: String?, duration: String?): Long {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, title)
            put(Events.EVENT_TIMEZONE, zone.id)
            put(Events.DTSTART, start)
            if (end != null) put(Events.DTEND, end)
            if (rrule != null) put(Events.RRULE, rrule)
            if (duration != null) put(Events.DURATION, duration)
            put(Events.HAS_ALARM, 0)
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
        }
        return ContentUris.parseId(resolver.insert(Events.CONTENT_URI, values)!!)
    }

    private fun insertException(seriesId: Long, values: ContentValues): Long =
        ContentUris.parseId(resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, seriesId), values)!!)

    private fun waitForSyncId(eventId: Long): String? {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val syncId = resolver.query(eventUri(eventId), arrayOf(Events._SYNC_ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (syncId != null) return syncId
            requestSync(); Thread.sleep(5_000)
        }
        return null
    }

    private fun instanceBegins(calId: Long, from: Long, to: Long): List<Pair<String, Long>> {
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        return resolver.query(
            uri, arrayOf(Instances.TITLE, Instances.BEGIN, Instances.STATUS),
            "${Instances.CALENDAR_ID}=?", arrayOf(calId.toString()), "${Instances.BEGIN} ASC",
        )?.use { c ->
            buildList { while (c.moveToNext()) if (c.getInt(2) != Events.STATUS_CANCELED) add(c.getString(0) to c.getLong(1)) }
        } ?: emptyList()
    }

    companion object {
        const val TAG = "MustafaSpike"
        const val SPIKE_NAME = "Alarms Spike"
        const val MIN = 60_000L
    }
}
