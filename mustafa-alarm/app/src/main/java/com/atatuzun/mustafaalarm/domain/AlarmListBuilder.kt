package com.atatuzun.mustafaalarm.domain

import java.time.LocalDate
import java.time.ZoneId

data class AlarmItem(
    val eventId: Long,
    val title: String,
    val shownAt: Long,
    val kind: AlarmKind,
    val recurrence: RecurrenceRule,
    val on: Boolean,
) {
    val nextRing: Long? get() = if (on) shownAt else null
}

data class AlarmSection(val day: LocalDate, val items: List<AlarmItem>)

sealed interface AlarmListState {
    data class Ready(val sections: List<AlarmSection>, val nextRing: Long?) : AlarmListState
    data object NoCalendar : AlarmListState
    data object CalendarMissing : AlarmListState
    data object Unavailable : AlarmListState
}

/** Home-screen list (spec §9.1): grouped by the day each alarm next rings, each alarm once. */
object AlarmListBuilder {
    fun build(
        events: List<EventRow>,
        instances: List<InstanceRow>,
        handled: Set<InstanceKey>,
        now: Long,
        zone: ZoneId,
    ): List<AlarmSection> {
        val minute = Times.floorMinute(now)
        val todayStart = Times.startOfDay(now, zone)
        val nextByAlarm = instances.asSequence()
            .filter { !it.allDay && it.status != STATUS_CANCELED && it.begin >= minute && it.key !in handled }
            .groupBy { it.alarmId }
            .mapValues { (_, rows) -> rows.minOf { it.begin } }

        val items = events
            .filter { !it.allDay && !it.isException && !it.isCanceled }
            .mapNotNull { e ->
                if (!e.isSeries) {
                    val on = !e.isOff && e.dtStart >= minute
                    if (!on && e.dtStart < todayStart) null
                    else AlarmItem(e.id, e.title, e.dtStart, AlarmKind.ONE_OFF, RecurrenceRule.Once, on)
                } else {
                    val next = nextByAlarm[e.id] ?: return@mapNotNull null
                    val parsed = RecurrenceRule.parse(e.rrule)
                    val recurrence = parsed ?: RecurrenceRule.Once
                    val kind = if (parsed != null) AlarmKind.SERIES else AlarmKind.OTHER_REPEAT
                    AlarmItem(e.id, e.title, next, kind, recurrence, !e.isOff)
                }
            }

        return items.groupBy { Times.localDate(it.shownAt, zone) }
            .toSortedMap()
            .map { (day, list) -> AlarmSection(day, list.sortedWith(compareBy({ it.shownAt }, { it.title }))) }
    }
}
