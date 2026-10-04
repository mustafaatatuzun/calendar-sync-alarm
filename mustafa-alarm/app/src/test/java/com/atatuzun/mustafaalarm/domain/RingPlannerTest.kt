package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RingPlannerTest {
    private val now = t("2026-10-02T10:00") + 20_000 // 10:00:20

    private fun inst(
        id: Long, begin: Long, colorKey: String? = null, status: Int? = null, allDay: Boolean = false, originalId: Long? = null,
    ) = InstanceRow(id, begin, begin + 15 * MINUTE, "a$id", allDay, colorKey, status, null, originalId)

    private fun cached(id: Long, begin: Long) = CachedOccurrence(InstanceKey(id, begin), id, "a$id")

    @Test
    fun upcoming_isSorted_andSkipsInactiveHandledAndRinging() {
        val rows = listOf(
            inst(1, t("2026-10-02T12:00")),
            inst(2, t("2026-10-02T11:00")),
            inst(3, t("2026-10-02T11:30"), colorKey = GRAPHITE_COLOR_KEY),
            inst(4, t("2026-10-02T11:40"), status = STATUS_CANCELED),
            inst(5, t("2026-10-02T11:50"), allDay = true),
            inst(6, t("2026-10-02T13:00")),
            inst(7, t("2026-10-02T14:00")),
        )
        val cache = RingPlanner.planCache(
            rows, emptyList(),
            handled = setOf(InstanceKey(6, t("2026-10-02T13:00"))),
            ringing = setOf(InstanceKey(7, t("2026-10-02T14:00"))),
            now = now,
        )
        assertEquals(listOf(2L, 1L), cache.map { it.key.eventId })
    }

    @Test
    fun currentMinute_isUpcoming_earlierMinutesNeverEnterTheCache() {
        val rows = listOf(inst(1, t("2026-10-02T10:00")), inst(2, t("2026-10-02T09:59")))
        val cache = RingPlanner.planCache(rows, emptyList(), emptySet(), emptySet(), now)
        assertEquals(listOf(1L), cache.map { it.key.eventId })
    }

    @Test
    fun missed_keepsRecentUnrungEntriesThatStillExist() {
        val recent = inst(1, t("2026-10-02T09:30"))
        val tooOld = inst(2, t("2026-10-02T08:59"))
        val stopped = inst(3, t("2026-10-02T09:40"))
        val deletedOnPc = cached(4, t("2026-10-02T09:45"))
        val previous = listOf(cached(1, recent.begin), cached(2, tooOld.begin), cached(3, stopped.begin), deletedOnPc)
        val cache = RingPlanner.planCache(listOf(recent, tooOld, stopped), previous, setOf(stopped.key), emptySet(), now)
        assertEquals(listOf(recent.key), cache.map { it.key })
    }

    @Test
    fun horizon_keepsTwoWeeks_orElseTheSingleNextOne() {
        val near = inst(1, t("2026-10-10T08:00"))
        val far = inst(2, t("2026-10-20T08:00"))
        val farther = inst(3, t("2026-11-20T08:00"))
        assertEquals(listOf(1L), RingPlanner.planCache(listOf(near, far), emptyList(), emptySet(), emptySet(), now).map { it.key.eventId })
        assertEquals(listOf(2L), RingPlanner.planCache(listOf(farther, far), emptyList(), emptySet(), emptySet(), now).map { it.key.eventId })
    }

    @Test
    fun cache_isCappedAt100() {
        val rows = (1..150L).map { inst(it, t("2026-10-02T11:00") + it * MINUTE) }
        assertEquals(100, RingPlanner.planCache(rows, emptyList(), emptySet(), emptySet(), now).size)
    }

    @Test
    fun movedOccurrence_carriesItsSeriesAsAlarmId() {
        val c = RingPlanner.planCache(listOf(inst(9, t("2026-10-02T11:00"), originalId = 4)), emptyList(), emptySet(), emptySet(), now).single()
        assertEquals(4L, c.alarmId)
        assertEquals(InstanceKey(9, t("2026-10-02T11:00")), c.key)
    }

    @Test
    fun nextTrigger_isTheEarliest_evenWhenInThePast() {
        assertEquals(t("2026-10-02T09:30"), RingPlanner.nextTrigger(listOf(cached(1, t("2026-10-02T11:00")), cached(2, t("2026-10-02T09:30")))))
        assertNull(RingPlanner.nextTrigger(emptyList()))
    }

    @Test
    fun due_ringsTheWholeMinuteTogether_plusMissedOnes() {
        val eight = t("2026-10-02T08:00")
        val cache = listOf(
            cached(1, eight), cached(2, eight), cached(3, eight), cached(4, eight),
            cached(5, eight + MINUTE), cached(6, eight - 30 * MINUTE), cached(7, eight - 61 * MINUTE),
        )
        val due = RingPlanner.due(cache, emptySet(), emptySet(), eight + 200)
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), due.map { it.key.eventId })
    }

    @Test
    fun due_skipsHandledAndRinging() {
        val eight = t("2026-10-02T08:00")
        val cache = listOf(cached(1, eight), cached(2, eight), cached(3, eight))
        val due = RingPlanner.due(cache, setOf(InstanceKey(1, eight)), setOf(InstanceKey(2, eight)), eight + 500)
        assertEquals(listOf(3L), due.map { it.key.eventId })
    }

    @Test
    fun lockedPlan_keepsUnhandledRecentAndFutureEntries() {
        val previous = listOf(
            cached(1, now - 30 * MINUTE), cached(2, now - 61 * MINUTE), cached(3, now + HOUR), cached(4, now + 2 * HOUR),
        )
        val plan = RingPlanner.planCacheLocked(previous, setOf(InstanceKey(4, now + 2 * HOUR)), emptySet(), now)
        assertEquals(listOf(1L, 3L), plan.map { it.key.eventId })
    }

    @Test
    fun handledOccurrence_staysExcluded_whenClockMovesBackwards() {
        val eight = inst(1, t("2026-10-02T08:00"))
        val clockSetBack = t("2026-10-02T07:55")
        assertTrue(RingPlanner.planCache(listOf(eight), emptyList(), setOf(eight.key), emptySet(), clockSetBack).isEmpty())
        assertTrue(RingPlanner.due(listOf(cached(1, eight.begin)), setOf(eight.key), emptySet(), eight.begin).isEmpty())
    }

    /**
     * Spec §5.1 / Decision 2026-10-02 #2: when the device zone changes, ringAt shifts by the
     * UTC-offset delta between the two zones (wall-clock semantics are preserved).
     *
     * Setup: event stored with eventTimezone="Europe/London" (BST = UTC+1 on 2026-10-02).
     * Device A = London; Device B = Famagusta (EEST = UTC+3 on 2026-10-02).
     * London → Famagusta offset delta = 2 hours; ringAt in Famagusta is 2 hours earlier (UTC).
     */
    @Test
    fun deviceZoneSwitch_shiftsRingAtByOffsetDelta() {
        val londonZone = ZoneId.of("Europe/London")
        val famagustaZone = ZoneId.of("Asia/Famagusta")
        // begin = 15:00 Famagusta (= UTC 12:00 = 13:00 London BST).
        // With londonZone device: same zones → ringAt = begin (UTC 12:00).
        // With famagustaZone device: London wall 13:00 reinterpreted → 13:00 Famagusta = UTC 10:00.
        val begin = t("2026-10-02T15:00")
        val row = InstanceRow(42, begin, begin + 15 * MINUTE, "test", false, null, null, null, null, "Europe/London")
        val nowT = t("2026-10-02T09:00") // UTC 06:00 — before both adjusted instants

        val ringAtLondon = RingPlanner.planCache(
            listOf(row), emptyList(), emptySet(), emptySet(), nowT, londonZone,
        ).single().ringAt
        val ringAtFamagusta = RingPlanner.planCache(
            listOf(row), emptyList(), emptySet(), emptySet(), nowT, famagustaZone,
        ).single().ringAt

        // London (UTC+1) vs Famagusta (UTC+3): 2-hour offset delta.
        // Famagusta rings earlier in epoch, so ringAtLondon > ringAtFamagusta by exactly 2 hours.
        assertEquals(2 * HOUR, ringAtLondon - ringAtFamagusta)
    }
}
