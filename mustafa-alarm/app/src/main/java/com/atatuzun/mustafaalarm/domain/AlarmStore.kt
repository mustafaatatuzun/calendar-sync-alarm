package com.atatuzun.mustafaalarm.domain

import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class AlarmKind { ONE_OFF, SERIES, OTHER_REPEAT }

/** What the add/edit screen submits: recurrence determines the timing shape. */
data class AlarmInput(
    val time: LocalTime,
    val date: LocalDate?,
    val recurrence: RecurrenceRule,
    val message: String,
    val soundUri: String?,
    val contact: AlarmContact? = null,
    val whatsApp: AlarmContact? = null,
)

sealed interface SaveResult {
    data class Saved(val eventId: Long, val start: Long) : SaveResult
    data object TimeInPast : SaveResult
    data object NoCalendar : SaveResult
    data object Missing : SaveResult
    data object NeedsDateForYearly : SaveResult
}

data class AlarmDetails(
    val eventId: Long,
    val time: LocalTime,
    val date: LocalDate?,
    val recurrence: RecurrenceRule,
    val kind: AlarmKind,
    val message: String,
    val soundUri: String?,
    val on: Boolean,
    val contact: AlarmContact? = null,
    val whatsApp: AlarmContact? = null,
)

/** Domain operations on alarms; the calendar is the source of truth (spec §4, §6). Blocking — call off the main thread. */
class AlarmStore(
    private val calendar: CalendarAccess,
    private val local: LocalStore,
    private val clock: Clock,
    private val calendarId: () -> Long?,
    private val snoozeMinutes: () -> Int,
    private val calendarAvailable: () -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private val zone: ZoneId get() = clock.zone

    fun create(input: AlarmInput, recordHistory: Boolean = true): SaveResult {
        val calId = calendarId() ?: return SaveResult.NoCalendar
        val now = clock.millis()
        if (input.recurrence is RecurrenceRule.Yearly && input.date == null) return SaveResult.NeedsDateForYearly
        val timing = timingFor(input, now, DEFAULT_LENGTH_MINUTES * MINUTE) ?: return SaveResult.TimeInPast
        val title = titleOf(input.message)
        val description = peopleDescription(null, input)
        val id = calendar.insertEvent(calId, title, timing, zone.id, description)
        input.soundUri?.let { local.setSound(id, it) }
        if (recordHistory) local.recordCreation(input.time.hour * 60 + input.time.minute, now)
        log("created eventId=$id '$title'")
        return SaveResult.Saved(id, timing.start)
    }

    fun details(eventId: Long): AlarmDetails? {
        val e = calendar.event(eventId) ?: return null
        val parsed = RecurrenceRule.parse(e.rrule)
        val recurrence = parsed ?: RecurrenceRule.Once  // Once + kind=OTHER_REPEAT marks "read-only series"
        val kind = when {
            !e.isSeries -> AlarmKind.ONE_OFF
            parsed != null -> AlarmKind.SERIES
            else -> AlarmKind.OTHER_REPEAT
        }
        return AlarmDetails(
            eventId = e.id,
            time = Times.localTime(e.dtStart, zone),
            date = if (kind == AlarmKind.ONE_OFF) Times.localDate(e.dtStart, zone) else null,
            recurrence = recurrence,
            kind = kind,
            message = e.title,
            soundUri = local.soundFor(e.id),
            on = !e.isOff,
            contact = ContactLine.read(e.description, ContactKind.CALL),
            whatsApp = ContactLine.read(e.description, ContactKind.WHATSAPP),
        )
    }

    fun update(eventId: Long, input: AlarmInput): SaveResult {
        val event = calendar.event(eventId) ?: return SaveResult.Missing
        val now = clock.millis()
        if (input.recurrence is RecurrenceRule.Yearly && input.date == null) return SaveResult.NeedsDateForYearly
        val timing = if (event.isSeries && RecurrenceRule.parse(event.rrule) == null) {
            // A rule the editor cannot represent: keep it byte-for-byte, change only the time of day.
            val start = Times.at(Times.localDate(event.dtStart, zone), input.time, zone)
            EventTiming.Recurring(start, event.rrule!!, event.duration ?: Rfc5545Duration.ofMillis(event.lengthMillis))
        } else {
            timingFor(input, now, event.lengthMillis) ?: return SaveResult.TimeInPast
        }
        calendar.updateEvent(
            eventId,
            EventPatch(
                title = titleOf(input.message), timing = timing, zone = zone.id, color = ColorPatch.DEFAULT,
                description = peopleDescription(event.description, input),
            ),
        )
        local.setSound(eventId, input.soundUri)
        log("updated alarm $eventId")
        return SaveResult.Saved(eventId, timing.start)
    }

    fun delete(eventId: Long) {
        calendar.deleteEvent(eventId)
        local.setSound(eventId, null)
        log("deleted alarm $eventId")
    }

    fun setEnabled(eventId: Long, on: Boolean) {
        val event = calendar.event(eventId) ?: return
        val color = if (on) ColorPatch.DEFAULT else ColorPatch.GRAPHITE
        if (event.isSeries) {
            calendar.updateEvent(eventId, EventPatch(color = color))
            val calId = calendarId() ?: return
            calendar.events(calId)
                .filter { it.originalId == eventId && !it.isCanceled }
                .forEach { calendar.updateEvent(it.id, EventPatch(color = color)) }
        } else {
            val now = clock.millis()
            val timing = if (on && event.dtStart <= now) {
                val next = Times.nextAt(Times.localTime(event.dtStart, zone), now, zone)
                EventTiming.Single(next, next + event.lengthMillis)
            } else null
            calendar.updateEvent(eventId, EventPatch(color = color, timing = timing))
        }
        log("alarm $eventId turned ${if (on) "on" else "off"}")
    }

    fun list(): AlarmListState {
        val calId = calendarId() ?: return AlarmListState.NoCalendar
        if (!calendarAvailable()) return AlarmListState.Unavailable
        if (!calendar.calendarExists(calId)) return AlarmListState.CalendarMissing
        val now = clock.millis()
        val sections = AlarmListBuilder.build(
            calendar.events(calId),
            calendar.instances(calId, Times.startOfDay(now, zone), now + 366 * DAY),
            local.handledKeys(),
            now,
            zone,
        )
        return AlarmListState.Ready(sections, sections.flatMap { it.items }.mapNotNull { it.nextRing }.minOrNull())
    }

    /**
     * Changes the message of a ringing occurrence. A moved (snoozed) occurrence is its own exception event,
     * so both it and its series are renamed. Returns the saved title, or null if the alarm is gone.
     */
    fun rename(key: InstanceKey, message: String): String? {
        val event = calendar.event(key.eventId) ?: return null
        val title = titleOf(message)
        calendar.updateEvent(event.id, EventPatch(title = title))
        event.originalId?.let { calendar.updateEvent(it, EventPatch(title = title)) }
        log("renamed alarm ${event.originalId ?: event.id} to '$title'")
        return title
    }

    /** Who to call / message for a ringing occurrence; null when nobody is set or the calendar can't be read yet. */
    fun peopleFor(key: InstanceKey): AlarmPeople? {
        if (!calendarAvailable()) return null
        val event = calendar.event(key.eventId) ?: return null
        val series by lazy { event.originalId?.let { calendar.event(it)?.description } }
        fun find(kind: ContactKind) = ContactLine.read(event.description, kind) ?: ContactLine.read(series, kind)
        return AlarmPeople(find(ContactKind.CALL), find(ContactKind.WHATSAPP))
            .takeIf { it.call != null || it.whatsApp != null }
    }

    // ---- ringing actions (spec §6.1) -------------------------------------------------------

    fun snooze(key: InstanceKey, pressedAt: Long) = act(key, RingAction.SNOOZE, pressedAt)

    fun tomorrow(key: InstanceKey, pressedAt: Long) {
        clearAutoSnoozeFor(key)
        act(key, RingAction.TOMORROW, pressedAt)
    }

    fun stop(key: InstanceKey, pressedAt: Long) {
        clearAutoSnoozeFor(key)
        act(key, RingAction.STOP, pressedAt)
    }

    /**
     * Called when the ringer fires without a user response. After 3 auto-snoozes the 4th ring
     * is treated as Tomorrow (spec §5.3 / Decision #1). Counter keyed by alarmId (series root).
     */
    fun autoSnooze(key: InstanceKey, pressedAt: Long) {
        val event = calendar.event(key.eventId)
        val alarmId = event?.run { originalId ?: id } ?: key.eventId
        val count = local.autoSnoozeCount(alarmId)
        if (count >= 3) {
            local.clearAutoSnooze(alarmId)
            act(key, RingAction.TOMORROW, pressedAt)
            log("unanswered → tomorrow eventId=${key.eventId} begin=${key.begin}")
        } else {
            local.incrementAutoSnooze(alarmId)
            val newCount = count + 1
            act(key, RingAction.SNOOZE, pressedAt)
            log("auto-snooze eventId=${key.eventId} begin=${key.begin} count=$newCount")
        }
    }

    /** Applies actions pressed while the calendar was unavailable, oldest first; stops at the first failure. */
    fun applyPending() {
        if (!calendarAvailable()) return
        for (p in local.pendingActions()) {
            val ok = runCatching { applyToCalendar(p.key, p.action, p.pressedAt) }
                .onFailure { log("pending ${p.action} on ${p.key} still failing: $it") }
                .isSuccess
            if (!ok) return
            local.removePending(p.id)
            log("applied pending ${p.action} on ${p.key}")
        }
    }

    /**
     * Rebuilds and stores the ring cache. Never throws on calendar problems: falls back to the
     * previous cache minus handled entries. Passes [zone] (= the device zone in production) to
     * [RingPlanner.planCache] so tests are zone-deterministic (Decision 2026-10-02 #2).
     */
    fun refreshRingCache(): List<CachedOccurrence> {
        val now = clock.millis()
        val previous = local.ringCache()
        val calId = calendarId()
        val fresh = if (calId != null && calendarAvailable()) {
            runCatching {
                applyPending()
                if (!calendar.calendarExists(calId)) {
                    null
                } else {
                    val handled = local.handledKeys()
                    val ringing = local.ringing().mapTo(HashSet()) { it.key }
                    val minute = Times.floorMinute(now)
                    var active = calendar.instances(calId, now - RingPlanner.MISSED_WINDOW, now + RingPlanner.HORIZON)
                    if (active.none { it.isActive && it.begin >= minute && it.key !in handled }) {
                        active = active + calendar.instances(calId, now + RingPlanner.HORIZON, now + 366 * DAY)
                    }
                    RingPlanner.planCache(active, previous, handled, ringing, now, zone)
                }
            }.onFailure { log("ring cache refresh failed: $it") }.getOrNull()
        } else {
            null
        }
        val next = fresh ?: RingPlanner.planCacheLocked(
            previous, local.handledKeys(), local.ringing().mapTo(HashSet()) { it.key }, now,
        )
        local.replaceRingCache(next)
        local.pruneHandled(now - 7 * DAY)
        return next
    }

    private fun act(key: InstanceKey, action: RingAction, pressedAt: Long) {
        if (key in local.handledKeys()) return // double-press or notification + screen at once
        val applied = calendarAvailable() && runCatching { applyToCalendar(key, action, pressedAt) }
            .onFailure { log("$action on $key failed, will retry: $it") }
            .isSuccess
        if (!applied) {
            local.addPending(key, action, pressedAt)
            fallbackEntry(key, action, pressedAt)?.let(local::addToRingCache)
        }
        local.markHandled(key, action, pressedAt)
        log("${action.name.lowercase()} $key${if (applied) "" else " (pending)"}")
    }

    private fun applyToCalendar(key: InstanceKey, action: RingAction, pressedAt: Long) {
        val event = calendar.event(key.eventId) ?: return // deleted while ringing: nothing to change
        when (action) {
            RingAction.SNOOZE -> moveOccurrence(event, key, snoozeTime(pressedAt))
            RingAction.TOMORROW -> moveToTomorrow(event, key)
            RingAction.STOP ->
                if (!event.isSeries && !event.isException) calendar.updateEvent(event.id, EventPatch(color = ColorPatch.GRAPHITE))
        }
    }

    private fun moveOccurrence(event: EventRow, key: InstanceKey, newStart: Long) {
        val timing = EventTiming.Single(newStart, newStart + event.lengthMillis)
        if (event.isSeries) {
            requireSynced(event)
            calendar.insertException(event.id, key.begin, EventPatch(timing = timing))
        } else {
            calendar.updateEvent(event.id, EventPatch(timing = timing))
        }
    }

    private fun moveToTomorrow(event: EventRow, key: InstanceKey) {
        val newStart = Times.plusDays(key.begin, 1, zone)
        if (!event.isSeries && !event.isException) {
            moveOccurrence(event, key, newStart)
            return
        }
        val seriesId = event.originalId ?: event.id
        val dayStart = Times.startOfDay(newStart, zone)
        val dayEnd = Times.plusDays(dayStart, 1, zone)
        val tomorrowHasOne = calendarId()?.let { calId ->
            calendar.instances(calId, dayStart, dayEnd).any {
                it.alarmId == seriesId && it.key != key && it.status != STATUS_CANCELED && !it.allDay
            }
        } ?: false
        when {
            !tomorrowHasOne -> moveOccurrence(event, key, newStart)
            event.isSeries -> {
                requireSynced(event)
                calendar.insertException(event.id, key.begin, EventPatch(canceled = true))
            }
            else -> calendar.updateEvent(event.id, EventPatch(canceled = true))
        }
    }

    /**
     * The calendar provider links an exception to its series by the series' sync id; without one it stops
     * listing the series' other occurrences. Throwing keeps the action pending (it still rings locally at
     * its new time) until the series has been uploaded to Google.
     */
    private fun requireSynced(series: EventRow) =
        check(series.syncId != null) { "series ${series.id} not synced yet" }

    private fun snoozeTime(pressedAt: Long): Long = Times.floorMinute(pressedAt) + snoozeMinutes() * MINUTE

    /** Keeps the alarm ringing at its new time while the calendar change waits in pending_actions. */
    private fun fallbackEntry(key: InstanceKey, action: RingAction, pressedAt: Long): CachedOccurrence? {
        val at = when (action) {
            RingAction.SNOOZE -> snoozeTime(pressedAt)
            RingAction.TOMORROW -> Times.plusDays(key.begin, 1, zone)
            RingAction.STOP -> return null
        }
        val known = local.ringing().firstOrNull { it.key == key }?.let { it.alarmId to it.title }
            ?: local.ringCache().firstOrNull { it.key == key }?.let { it.alarmId to it.title }
            ?: (key.eventId to DEFAULT_TITLE)
        return CachedOccurrence(InstanceKey(key.eventId, at), known.first, known.second)
    }

    private fun clearAutoSnoozeFor(key: InstanceKey) {
        val event = calendar.event(key.eventId)
        val alarmId = event?.run { originalId ?: id } ?: key.eventId
        local.clearAutoSnooze(alarmId)
    }

    // ---- scheduling helpers ----------------------------------------------------------------

    private fun timingFor(input: AlarmInput, now: Long, lengthMillis: Long): EventTiming? {
        val time = input.time.withSecond(0).withNano(0)
        val dur = Rfc5545Duration.ofMillis(lengthMillis)
        return when (val r = input.recurrence) {
            RecurrenceRule.Once -> when {
                input.date != null -> Times.at(input.date, time, zone)
                    .takeIf { it > now }
                    ?.let { EventTiming.Single(it, it + lengthMillis) }
                else -> Times.nextAt(time, now, zone).let { EventTiming.Single(it, it + lengthMillis) }
            }
            RecurrenceRule.Daily ->
                EventTiming.Recurring(Times.firstDailyStart(time, now, zone), r.build()!!, dur)
            RecurrenceRule.EveryWeekday ->
                EventTiming.Recurring(Times.firstWeekdayStart(time, now, zone), r.build()!!, dur)
            is RecurrenceRule.Weekly ->
                EventTiming.Recurring(Times.firstWeeklyStart(r.days, time, now, zone), r.build()!!, dur)
            is RecurrenceRule.MonthlyDay ->
                EventTiming.Recurring(Times.firstMonthlyDayStart(r.day, time, now, zone), r.build()!!, dur)
            is RecurrenceRule.MonthlyNthWeekday ->
                EventTiming.Recurring(
                    Times.firstMonthlyNthWeekdayStart(r.nth, r.weekday, time, now, zone), r.build()!!, dur,
                )
            RecurrenceRule.Yearly -> {
                val anchor = input.date ?: return null  // caller returns NeedsDateForYearly
                EventTiming.Recurring(Times.firstYearlyStart(anchor, time, now, zone), r.build()!!, dur)
            }
        }
    }

    private fun titleOf(message: String): String = message.trim().ifEmpty { DEFAULT_TITLE }

    /** The description with the input's Call/WhatsApp lines, or null when neither changed (PC notes stay byte-for-byte). */
    private fun peopleDescription(current: String?, input: AlarmInput): String? {
        var text = current
        var changed = false
        for ((kind, contact) in listOf(ContactKind.CALL to input.contact, ContactKind.WHATSAPP to input.whatsApp)) {
            if (ContactLine.read(text, kind) != contact) {
                text = ContactLine.write(text, contact, kind)
                changed = true
            }
        }
        return text.takeIf { changed }
    }
}
