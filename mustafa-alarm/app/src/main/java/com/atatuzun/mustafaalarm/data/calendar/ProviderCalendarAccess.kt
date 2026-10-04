package com.atatuzun.mustafaalarm.data.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.atatuzun.mustafaalarm.domain.CalendarAccess
import com.atatuzun.mustafaalarm.domain.ColorPatch
import com.atatuzun.mustafaalarm.domain.EventPatch
import com.atatuzun.mustafaalarm.domain.EventRow
import com.atatuzun.mustafaalarm.domain.EventTiming
import com.atatuzun.mustafaalarm.domain.GRAPHITE_COLOR_KEY
import com.atatuzun.mustafaalarm.domain.InstanceRow

/**
 * The only code that touches CalendarContract (spec §4). Blocking — call off the main thread.
 *
 * Decision #7 / risk-2 note: the on/off marker uses EVENT_COLOR_KEY = GRAPHITE_COLOR_KEY ("8").
 * If the spike later shows risk-2 FAIL, swap applyOff/applyOn here (a single-file change):
 *   applyOff → prefix title with "⏸ " instead of writing colorKey
 *   applyOn  → strip the prefix instead of clearing colorKey
 */
class ProviderCalendarAccess(private val resolver: ContentResolver) : CalendarAccess, CalendarSetupAccess {

    override fun calendarExists(calendarId: Long): Boolean = calendarRow(calendarId) != null

    /**
     * Occurrences overlapping [from, to) — including all-day, cancelled and Graphite ones.
     *
     * Carryover #1 (binding): INSTANCE_COLUMNS includes EVENT_TIMEZONE at index 9 so that
     * RingPlanner.adjustedRingAt works correctly across time zones (spec Decision #2).
     * Without this column the eventTimezone field would be null and adjustedRingAt would be
     * a silent no-op on cross-zone data.
     *
     * Task 5 / Carryover #2: the WHERE clause is CALENDAR_ID=? so the scan is bounded
     * per-calendar only (not all-calendars). SQLite will use the CALENDAR_ID index in
     * CalendarProvider; the caller may post-filter by originalId with O(calendar-size) cost.
     */
    override fun instances(calendarId: Long, from: Long, to: Long): List<InstanceRow> {
        val uri = Instances.CONTENT_URI.buildUpon()
            .also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }
            .build()
        val deleted = deletedEventIds(calendarId)
        val rows = mutableListOf<InstanceRow>()
        resolver.query(
            uri, INSTANCE_COLUMNS,
            "${Instances.CALENDAR_ID}=?", arrayOf(calendarId.toString()),
            "${Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(0) in deleted) continue // deleted on the phone, waiting for the sync adapter
                rows += InstanceRow(
                    eventId = c.getLong(0),
                    begin = c.getLong(1),
                    end = c.getLong(2),
                    title = c.getStringOrNull(3).orEmpty(),
                    allDay = c.getInt(4) != 0,
                    colorKey = c.getStringOrNull(5),
                    status = c.getIntOrNull(6),
                    rrule = c.getStringOrNull(7),
                    originalId = c.getLongOrNull(8),
                    eventTimezone = c.getStringOrNull(9), // Carryover #1: must not be omitted
                )
            }
        }
        return rows
    }

    /**
     * Non-deleted events of the calendar, exceptions included.
     *
     * Task 5 (binding): WHERE clause is CALENDAR_ID=? so the scan is per-calendar only.
     * CalendarProvider has an index on CALENDAR_ID; even without an exact ORIGINAL_ID index
     * the iteration is O(calendar-size), not O(all-calendars-size).
     */
    override fun events(calendarId: Long): List<EventRow> =
        queryEvents("${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0", arrayOf(calendarId.toString()))

    override fun event(eventId: Long): EventRow? =
        queryEvents("${Events._ID}=? AND ${Events.DELETED}=0", arrayOf(eventId.toString())).firstOrNull()

    override fun insertEvent(calendarId: Long, title: String, timing: EventTiming, zone: String): Long {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.EVENT_TIMEZONE, zone)
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
            put(Events.HAS_ALARM, 0)
            putTiming(timing)
        }
        val uri = resolver.insert(Events.CONTENT_URI, values) ?: error("Calendar refused the new event")
        return ContentUris.parseId(uri)
    }

    override fun updateEvent(eventId: Long, patch: EventPatch) {
        val values = ContentValues().apply { putPatch(patch, forException = false) }
        if (values.size() > 0) resolver.update(eventUri(eventId), values, null, null)
    }

    override fun deleteEvent(eventId: Long) {
        resolver.delete(eventUri(eventId), null, null)
    }

    /**
     * Creates an exception replacing the occurrence of [seriesId] at [originalInstanceTime].
     *
     * Decision #7 risk-4 note: kept isolated from updateEvent so that if the ContentProvider
     * path later fails for cross-device sync (risk-4 FAIL), swapping to REST API is a
     * single-method replacement here without touching updateEvent.
     */
    override fun insertException(seriesId: Long, originalInstanceTime: Long, patch: EventPatch): Long {
        val values = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, originalInstanceTime)
            putPatch(patch, forException = true)
        }
        val uri = resolver.insert(
            ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, seriesId), values,
        ) ?: error("Calendar refused the exception for event $seriesId")
        return ContentUris.parseId(uri)
    }

    override fun findCalendars(accountName: String, accountType: String): List<CalendarRow> =
        queryCalendars(
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",
            arrayOf(accountName, accountType),
        )

    override fun calendarRow(calendarId: Long): CalendarRow? =
        queryCalendars("${Calendars._ID}=?", arrayOf(calendarId.toString())).firstOrNull()

    /**
     * Enables sync and visibility for the calendar.
     *
     * Uses sync-adapter URI parameters so the CalendarProvider skips its non-sync-adapter
     * cascade that would compile `UPDATE events SET event_color=... WHERE _id=?` — a statement
     * that fails on Android 16 because `event_color` was removed as a user-writable column.
     * (Spec §4; Decision #7 risk-1 PASS: SYNC_EVENTS and VISIBLE are writable.)
     */
    override fun enableSyncAndVisibility(calendarId: Long) {
        val row = calendarRow(calendarId) ?: return
        val values = ContentValues().apply {
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
        }
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendEncodedPath(calendarId.toString())
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, row.accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, row.accountType)
            .build()
        resolver.update(uri, values, null, null)
    }

    override fun requestSync(accountName: String) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(Account(accountName, GOOGLE_ACCOUNT_TYPE), CalendarContract.AUTHORITY, extras)
    }

    override fun dirtyCount(calendarId: Long): Int? = runCatching {
        resolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=? AND ${Events.DIRTY}=1",
            arrayOf(calendarId.toString()), null,
        )?.use { it.count }
    }.getOrNull()

    private fun eventUri(id: Long): Uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)

    /** Instances has no DELETED column; phone-deleted events (DELETED=1 until uploaded) are filtered by id. */
    private fun deletedEventIds(calendarId: Long): Set<Long> =
        resolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=1",
            arrayOf(calendarId.toString()), null,
        )?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptySet()

    private fun queryEvents(selection: String, args: Array<String>): List<EventRow> =
        resolver.query(Events.CONTENT_URI, EVENT_COLUMNS, selection, args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        EventRow(
                            id = c.getLong(0),
                            title = c.getStringOrNull(1).orEmpty(),
                            dtStart = c.getLong(2),
                            dtEnd = c.getLongOrNull(3),
                            duration = c.getStringOrNull(4),
                            rrule = c.getStringOrNull(5),
                            allDay = c.getInt(6) != 0,
                            colorKey = c.getStringOrNull(7),
                            status = c.getIntOrNull(8),
                            originalId = c.getLongOrNull(9),
                            originalInstanceTime = c.getLongOrNull(10),
                            timeZone = c.getStringOrNull(11),
                        ),
                    )
                }
            }
        } ?: emptyList()

    private fun queryCalendars(selection: String, args: Array<String>): List<CalendarRow> =
        resolver.query(Calendars.CONTENT_URI, CALENDAR_COLUMNS, selection, args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CalendarRow(
                            id = c.getLong(0),
                            accountName = c.getString(1),
                            accountType = c.getString(2),
                            displayName = c.getStringOrNull(3),
                            syncId = c.getStringOrNull(4),
                            ownerAccount = c.getStringOrNull(5),
                            syncEvents = c.getInt(6) != 0,
                            visible = c.getInt(7) != 0,
                        ),
                    )
                }
            }
        } ?: emptyList()

    private fun ContentValues.putTiming(timing: EventTiming) {
        when (timing) {
            is EventTiming.Single -> {
                put(Events.DTSTART, timing.start)
                put(Events.DTEND, timing.end)
                putNull(Events.RRULE)
                putNull(Events.DURATION)
            }
            is EventTiming.Recurring -> {
                put(Events.DTSTART, timing.start)
                putNull(Events.DTEND)
                put(Events.RRULE, timing.rrule)
                put(Events.DURATION, timing.duration)
            }
        }
    }

    /** Exceptions get only DTSTART/DTEND: an RRULE or DURATION key would make the provider split the series. */
    private fun ContentValues.putPatch(patch: EventPatch, forException: Boolean) {
        patch.title?.let { put(Events.TITLE, it) }
        patch.zone?.let { put(Events.EVENT_TIMEZONE, it) }
        patch.timing?.let { timing ->
            if (forException) {
                require(timing is EventTiming.Single) { "an exception is a single occurrence" }
                put(Events.DTSTART, timing.start)
                put(Events.DTEND, timing.end)
            } else {
                putTiming(timing)
            }
        }
        // Decision #7 / risk-2: on/off marker via color key.
        // If risk-2 spike fails, replace this block: applyOff → title prefix "⏸ "; applyOn → strip prefix.
        // Note: Events.EVENT_COLOR ("event_color") was removed as a directly writable column in Android 16;
        //       only EVENT_COLOR_KEY is written here — the provider derives event_color internally.
        when (patch.color) {
            ColorPatch.GRAPHITE -> put(Events.EVENT_COLOR_KEY, GRAPHITE_COLOR_KEY)
            ColorPatch.DEFAULT -> putNull(Events.EVENT_COLOR_KEY)
            null -> Unit
        }
        if (patch.canceled) put(Events.STATUS, Events.STATUS_CANCELED)
    }

    private companion object {
        // Carryover #1 (binding): EVENT_TIMEZONE at index 9 MUST remain in this projection.
        val INSTANCE_COLUMNS = arrayOf(
            Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.ALL_DAY,
            Instances.EVENT_COLOR_KEY, Instances.STATUS, Instances.RRULE, Instances.ORIGINAL_ID,
            Instances.EVENT_TIMEZONE, // index 9 — feeds InstanceRow.eventTimezone → RingPlanner.adjustedRingAt
        )
        val EVENT_COLUMNS = arrayOf(
            Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.DURATION, Events.RRULE,
            Events.ALL_DAY, Events.EVENT_COLOR_KEY, Events.STATUS,
            Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.EVENT_TIMEZONE,
        )
        val CALENDAR_COLUMNS = arrayOf(
            Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_DISPLAY_NAME, Calendars._SYNC_ID, Calendars.OWNER_ACCOUNT,
            Calendars.SYNC_EVENTS, Calendars.VISIBLE,
        )
    }
}
