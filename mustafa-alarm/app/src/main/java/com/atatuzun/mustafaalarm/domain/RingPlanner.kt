package com.atatuzun.mustafaalarm.domain

import java.time.ZoneId

/**
 * Pure scheduling rules (spec §6.3, §7):
 * - the ring cache holds the next ≤100 active occurrences within 14 days (or the single next one beyond),
 *   plus occurrences that should have rung in the last 60 minutes, never rang and still exist;
 * - AlarmManager is armed for the earliest cached time;
 * - when it fires, everything cached up to the end of the current minute rings together.
 *
 * Ring instants are local-wall-clock adjusted per spec §5.1 / Decision 2026-10-02 #2:
 * see [Times.adjustedRingAt] and the [deviceZone] parameter on [planCache].
 */
object RingPlanner {
    const val CACHE_LIMIT = 100
    const val MISSED_WINDOW = 60 * MINUTE
    const val HORIZON = 14 * DAY

    /**
     * Build (or rebuild) the ring cache from a fresh calendar snapshot.
     *
     * @param active     all Instances rows currently visible in the calendar provider
     * @param previous   the cache from the previous tick (used to recover missed alarms)
     * @param handled    keys of occurrences the user already acted on (STOP/SNOOZE/TOMORROW)
     * @param ringing    keys of occurrences currently ringing (already dispatched, not re-fired)
     * @param now        current epoch millis
     * @param deviceZone the device's current timezone; injected so tests can override
     *                   (callers pass ZoneId.systemDefault())
     */
    fun planCache(
        active: List<InstanceRow>,
        previous: List<CachedOccurrence>,
        handled: Set<InstanceKey>,
        ringing: Set<InstanceKey>,
        now: Long,
        deviceZone: ZoneId = ZoneId.systemDefault(),
    ): List<CachedOccurrence> {
        val minute = Times.floorMinute(now)
        val activeRows = active.filter { it.isActive }

        // Map each active row to its CachedOccurrence with wall-clock-adjusted ringAt
        val activeOccurrences = activeRows.map { rowToOccurrence(it, deviceZone) }
        val activeAdjustedKeys = activeOccurrences.mapTo(HashSet()) { it.key }

        val upcomingAll = activeOccurrences.asSequence()
            .filter { it.ringAt >= minute && it.key !in handled && it.key !in ringing }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.ringAt }, { it.key.eventId }))
            .toList()

        val withinHorizon = upcomingAll.filter { it.ringAt < now + HORIZON }
        val upcoming = withinHorizon.ifEmpty { upcomingAll.take(1) }.take(CACHE_LIMIT)

        // Recover occurrences that should have rung recently but never did (e.g. phone was off)
        val missed = previous.filter {
            it.ringAt >= now - MISSED_WINDOW && it.ringAt < minute &&
                it.key !in handled && it.key !in ringing && it.key in activeAdjustedKeys
        }

        return (missed.sortedBy { it.ringAt } + upcoming).distinctBy { it.key }
    }

    /**
     * Before the first unlock the calendar is unreadable: keep what was promised, minus what was
     * handled or is already ringing.
     */
    fun planCacheLocked(
        previous: List<CachedOccurrence>,
        handled: Set<InstanceKey>,
        ringing: Set<InstanceKey>,
        now: Long,
    ): List<CachedOccurrence> =
        previous.filter { it.ringAt >= now - MISSED_WINDOW && it.key !in handled && it.key !in ringing }
            .sortedBy { it.ringAt }

    /** The earliest ringAt in the cache, or null if empty. A past value means "fire now". */
    fun nextTrigger(cache: List<CachedOccurrence>): Long? = cache.minOfOrNull { it.ringAt }

    /**
     * Occurrences that must ring at [now]: everything whose ringAt falls in the current minute,
     * plus anything missed within [MISSED_WINDOW], excluding already-handled or ringing ones.
     */
    fun due(
        cache: List<CachedOccurrence>,
        handled: Set<InstanceKey>,
        ringing: Set<InstanceKey>,
        now: Long,
    ): List<CachedOccurrence> {
        val minuteEnd = Times.floorMinute(now) + MINUTE
        return cache.filter {
            it.ringAt < minuteEnd && it.ringAt >= now - MISSED_WINDOW &&
                it.key !in handled && it.key !in ringing
        }
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private fun rowToOccurrence(row: InstanceRow, deviceZone: ZoneId): CachedOccurrence {
        val eventZone = row.eventTimezone
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: deviceZone
        val adjustedBegin = Times.adjustedRingAt(row.begin, eventZone, deviceZone)
        return CachedOccurrence(InstanceKey(row.eventId, adjustedBegin), row.alarmId, row.title)
    }
}
