package com.atatuzun.mustafaalarm.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** In-memory "Alarms" calendar; expands one-offs, weekly/daily series and exceptions the way the provider does. */
class FakeCalendarAccess(private val defaultZone: ZoneId = ZONE) : CalendarAccess {
    val events = linkedMapOf<Long, EventRow>()
    var exists = true
    var failWrites = false
    /** Google-synced calendars give new events a sync id; false mimics a series not yet uploaded. */
    var syncNewEvents = true
    private var nextId = 100L

    fun markSynced(eventId: Long) {
        events[eventId]?.let { events[eventId] = it.copy(syncId = "sync-$eventId") }
    }

    override fun calendarExists(calendarId: Long) = exists && calendarId == CAL
    override fun events(calendarId: Long): List<EventRow> = events.values.toList()
    override fun event(eventId: Long): EventRow? = events[eventId]

    override fun insertEvent(calendarId: Long, title: String, timing: EventTiming, zone: String, description: String?): Long {
        checkWritable()
        val id = nextId++
        events[id] = EventRow(
            id, title, timing.start, null, null, null, false, null, null, null, null, zone, description,
            syncId = if (syncNewEvents) "sync-$id" else null,
        ).withTiming(timing)
        return id
    }

    override fun updateEvent(eventId: Long, patch: EventPatch) {
        checkWritable()
        events[eventId]?.let { events[eventId] = it.patched(patch) }
    }

    override fun deleteEvent(eventId: Long) {
        checkWritable()
        events.remove(eventId)
        events.values.removeAll { it.originalId == eventId }
    }

    override fun insertException(seriesId: Long, originalInstanceTime: Long, patch: EventPatch): Long {
        checkWritable()
        val series = events.getValue(seriesId)
        val id = nextId++
        val base = EventRow(
            id, series.title, originalInstanceTime, originalInstanceTime + series.lengthMillis, null, null, false,
            series.colorKey, null, seriesId, originalInstanceTime, series.timeZone, series.description,
        )
        events[id] = base.patched(patch)
        return id
    }

    override fun instances(calendarId: Long, from: Long, to: Long): List<InstanceRow> {
        val out = mutableListOf<InstanceRow>()
        for (e in events.values) {
            when {
                e.isCanceled -> Unit
                e.isException || !e.isSeries ->
                    if (e.dtStart < to && e.dtStart + e.lengthMillis > from) out += e.instanceAt(e.dtStart)
                else -> {
                    val replaced = events.values.filter { it.originalId == e.id }.mapNotNull { it.originalInstanceTime }.toSet()
                    occurrences(e, to).filter { it + e.lengthMillis > from && it !in replaced }.forEach { out += e.instanceAt(it) }
                }
            }
        }
        return out.sortedWith(compareBy({ it.begin }, { it.eventId }))
    }

    private fun EventRow.instanceAt(begin: Long) =
        InstanceRow(id, begin, begin + lengthMillis, title, allDay, colorKey, status, rrule, originalId, eventTimezone = this.timeZone)

    private fun occurrences(e: EventRow, to: Long): Sequence<Long> {
        val zone = e.timeZone?.let(ZoneId::of) ?: defaultZone
        val start = Instant.ofEpochMilli(e.dtStart).atZone(zone)
        val rule = e.rrule!!
        val count = Regex("COUNT=(\\d+)").find(rule)?.groupValues?.get(1)?.toInt() ?: Int.MAX_VALUE
        val recurrenceRule = RecurrenceRule.parse(rule)
        val dates = generateSequence(start.toLocalDate()) { it.plusDays(1) }
        val matching = when (recurrenceRule) {
            is RecurrenceRule.Weekly -> dates.filter { it.dayOfWeek in recurrenceRule.days }
            is RecurrenceRule.EveryWeekday -> dates.filter { it.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) }
            is RecurrenceRule.Daily -> dates
            is RecurrenceRule.MonthlyDay -> dates.filter { it.dayOfMonth == recurrenceRule.day }
            else -> error("FakeCalendarAccess occurrences: unsupported rule for series expansion: $rule")
        }
        return matching.map { ZonedDateTime.of(it, start.toLocalTime(), zone).toInstant().toEpochMilli() }
            .filter { it >= e.dtStart }.take(count).takeWhile { it < to }
    }

    private fun checkWritable() {
        if (failWrites) throw IllegalStateException("provider write failed (test)")
    }

    /**
     * Inserts a raw calendar event with the given RRULE verbatim (bypasses EventTiming).
     * Used to simulate PC-authored events with rules the app cannot edit (e.g. UNTIL=…).
     */
    fun insertRawEvent(
        rrule: String,
        title: String = "Raw",
        start: Long = 0L,
        duration: String = "PT15M",
    ): Long {
        val id = nextId++
        events[id] = EventRow(id, title, start, null, rrule, duration, false, null, null, null, null, "UTC", syncId = "sync-$id")
        return id
    }

    companion object {
        const val CAL = 1L
    }
}

private fun EventRow.withTiming(t: EventTiming): EventRow = when (t) {
    is EventTiming.Single -> copy(dtStart = t.start, dtEnd = t.end, rrule = null, duration = null)
    is EventTiming.Recurring -> copy(dtStart = t.start, dtEnd = null, rrule = t.rrule, duration = t.duration)
}

fun EventRow.patched(p: EventPatch): EventRow {
    var e = this
    p.title?.let { e = e.copy(title = it) }
    p.description?.let { e = e.copy(description = it) }
    p.timing?.let { e = e.withTiming(it) }
    p.zone?.let { e = e.copy(timeZone = it) }
    when (p.color) {
        ColorPatch.GRAPHITE -> e = e.copy(colorKey = GRAPHITE_COLOR_KEY)
        ColorPatch.DEFAULT -> e = e.copy(colorKey = null)
        null -> Unit
    }
    if (p.canceled) e = e.copy(status = STATUS_CANCELED)
    return e
}
