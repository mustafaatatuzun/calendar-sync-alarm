package com.atatuzun.mustafaalarm.domain

/** Everything the domain needs from the "Alarms" calendar. The only Android implementation is ProviderCalendarAccess. */
interface CalendarAccess {
    fun calendarExists(calendarId: Long): Boolean

    /** Occurrences overlapping [from, to) — including all-day, cancelled and Graphite ones (callers filter). */
    fun instances(calendarId: Long, from: Long, to: Long): List<InstanceRow>

    /** Non-deleted events of the calendar, exceptions included. */
    fun events(calendarId: Long): List<EventRow>

    fun event(eventId: Long): EventRow?

    fun insertEvent(calendarId: Long, title: String, timing: EventTiming, zone: String): Long

    fun updateEvent(eventId: Long, patch: EventPatch)

    fun deleteEvent(eventId: Long)

    /** Creates an exception replacing the occurrence of [seriesId] that originally began at [originalInstanceTime]. */
    fun insertException(seriesId: Long, originalInstanceTime: Long, patch: EventPatch): Long
}
