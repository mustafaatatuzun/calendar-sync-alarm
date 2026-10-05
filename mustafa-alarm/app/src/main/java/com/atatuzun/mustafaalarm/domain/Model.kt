package com.atatuzun.mustafaalarm.domain

const val GRAPHITE_COLOR_KEY = "8"          // Google event colour "Graphite" = alarm off / done
const val STATUS_CANCELED = 2               // CalendarContract.Events.STATUS_CANCELED
const val DEFAULT_TITLE = "Alarm"
const val DEFAULT_LENGTH_MINUTES = 15L
const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

/** One occurrence: the Instances row's EVENT_ID and BEGIN. */
data class InstanceKey(val eventId: Long, val begin: Long)

/** One row of CalendarContract.Events in the "Alarms" calendar. */
data class EventRow(
    val id: Long,
    val title: String,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val rrule: String?,
    val allDay: Boolean,
    val colorKey: String?,
    val status: Int?,
    val originalId: Long?,
    val originalInstanceTime: Long?,
    val timeZone: String?,
    val description: String? = null,
) {
    val isSeries: Boolean get() = !rrule.isNullOrBlank()
    val isException: Boolean get() = originalId != null
    val isOff: Boolean get() = colorKey == GRAPHITE_COLOR_KEY
    val isCanceled: Boolean get() = status == STATUS_CANCELED

    val lengthMillis: Long
        get() = when {
            dtEnd != null && dtEnd > dtStart -> dtEnd - dtStart
            duration != null -> Rfc5545Duration.parseMillis(duration) ?: DEFAULT_LENGTH_MINUTES * MINUTE
            else -> DEFAULT_LENGTH_MINUTES * MINUTE
        }
}

/** One row of CalendarContract.Instances (an occurrence joined with its event). */
data class InstanceRow(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val allDay: Boolean,
    val colorKey: String?,
    val status: Int?,
    val rrule: String?,
    val originalId: Long?,
    /** The event's own timezone string (CalendarContract.Instances.EVENT_TIMEZONE), or null for floating events. */
    val eventTimezone: String? = null,
) {
    val key: InstanceKey get() = InstanceKey(eventId, begin)

    /** The alarm this occurrence belongs to: the series for a moved occurrence, else the event itself. */
    val alarmId: Long get() = originalId ?: eventId

    /** Would ring: timed, not cancelled, not Graphite. */
    val isActive: Boolean get() = !allDay && status != STATUS_CANCELED && colorKey != GRAPHITE_COLOR_KEY
}

/** An occurrence the phone has promised to ring (ring_cache row). */
data class CachedOccurrence(val key: InstanceKey, val alarmId: Long, val title: String) {
    val ringAt: Long get() = key.begin
}

/** An occurrence that is ringing now (ringing_now row). */
data class RingingEntry(val key: InstanceKey, val alarmId: Long, val title: String, val startedAt: Long) {
    val ringAt: Long get() = key.begin
}

enum class RingAction { SNOOZE, TOMORROW, STOP }

data class PendingAction(val id: Long, val key: InstanceKey, val action: RingAction, val pressedAt: Long)

/** When an event happens. A Single has DTEND; a Recurring has RRULE + DURATION. */
sealed interface EventTiming {
    val start: Long

    data class Single(override val start: Long, val end: Long) : EventTiming
    data class Recurring(override val start: Long, val rrule: String, val duration: String) : EventTiming
}

enum class ColorPatch { GRAPHITE, DEFAULT }

/** A change to an event; null fields are left untouched. */
data class EventPatch(
    val title: String? = null,
    val timing: EventTiming? = null,
    val zone: String? = null,
    val color: ColorPatch? = null,
    val canceled: Boolean = false,
    val description: String? = null,
)
