package com.atatuzun.mustafaalarm.debug

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
import com.atatuzun.mustafaalarm.domain.GRAPHITE_COLOR_KEY
import java.time.ZoneId

/** Debug/test only: a phone-local calendar (no Google account) that behaves like "Alarms". */
object LocalCalendars {
    const val ACCOUNT_NAME = "Mustafa Alarm Local"
    private const val TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL

    fun create(resolver: ContentResolver, name: String): Long {
        ensureGraphiteColour(resolver)
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, TYPE)
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.CALENDAR_COLOR, 0xFF3F51B5.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        return ContentUris.parseId(resolver.insert(Calendars.CONTENT_URI.asSyncAdapter(), values)!!)
    }

    fun findByName(resolver: ContentResolver, name: String): Long? = resolver.query(
        Calendars.CONTENT_URI, arrayOf(Calendars._ID),
        "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?",
        arrayOf(ACCOUNT_NAME, TYPE, name), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    fun delete(resolver: ContentResolver, calendarId: Long) {
        resolver.delete(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId).asSyncAdapter(),
            null, null,
        )
    }

    private fun ensureGraphiteColour(resolver: ContentResolver) {
        val exists = resolver.query(
            Colors.CONTENT_URI, arrayOf(Colors.COLOR_KEY),
            "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=? AND ${Colors.COLOR_TYPE}=? AND ${Colors.COLOR_KEY}=?",
            arrayOf(ACCOUNT_NAME, TYPE, Colors.TYPE_EVENT.toString(), GRAPHITE_COLOR_KEY), null,
        )?.use { it.count > 0 } ?: false
        if (exists) return
        val values = ContentValues().apply {
            put(Colors.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Colors.ACCOUNT_TYPE, TYPE)
            put(Colors.COLOR_TYPE, Colors.TYPE_EVENT)
            put(Colors.COLOR_KEY, GRAPHITE_COLOR_KEY)
            put(Colors.COLOR, 0xFF616161.toInt())
        }
        resolver.insert(Colors.CONTENT_URI.asSyncAdapter(), values)
    }

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, TYPE)
        .build()
}
