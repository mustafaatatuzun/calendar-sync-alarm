package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.domain.FakeCalendarAccess.Companion.CAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AlarmStoreRingTest {
    private val clock = TestClock(t("2026-10-05T09:00") + 20_000) // Monday 09:00:20
    private val cal = FakeCalendarAccess()
    private val local = InMemoryLocalStore()
    private var unlocked = true
    private val store = AlarmStore(cal, local, clock, { CAL }, { 30 }, { unlocked })

    private fun oneOff(start: String, title: String = "One-off", minutes: Long = 15) =
        cal.insertEvent(CAL, title, EventTiming.Single(t(start), t(start) + minutes * MINUTE), ZONE.id)

    private fun series(start: String, rule: String) =
        cal.insertEvent(CAL, "Series", EventTiming.Recurring(t(start), rule, "PT15M"), ZONE.id)

    private fun begins(from: String, to: String) =
        cal.instances(CAL, t(from), t(to)).filter { it.isActive }.map { it.begin }

    private fun key(id: Long, start: String) = InstanceKey(id, t(start))

    @Test
    fun snooze_oneOff_movesToPressMinutePlusSnooze() {
        val id = oneOff("2026-10-05T09:00")
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis() + 20_000) // pressed 09:00:40
        assertEquals(t("2026-10-05T09:30"), cal.event(id)!!.dtStart)
        assertEquals(t("2026-10-05T09:45"), cal.event(id)!!.dtEnd)
        assertEquals(RingAction.SNOOZE, local.handled[key(id, "2026-10-05T09:00")]!!.first)
    }

    @Test
    fun snooze_keepsLengthOfPcEvent() {
        val id = oneOff("2026-10-05T09:00", minutes = 60)
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        val e = cal.event(id)!!
        assertEquals(t("2026-10-05T09:30"), e.dtStart)
        assertEquals(HOUR, e.dtEnd!! - e.dtStart)
    }

    @Test
    fun snooze_weeklyOccurrence_createsAnExceptionAndLeavesTheSeries() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(t("2026-10-05T09:00"), cal.event(id)!!.dtStart)
        val ex = cal.events.values.single { it.originalId == id }
        assertEquals(t("2026-10-05T09:00"), ex.originalInstanceTime)
        assertEquals(t("2026-10-05T09:30"), ex.dtStart)
        assertEquals(listOf(t("2026-10-05T09:30"), t("2026-10-07T09:00")), begins("2026-10-05T00:00", "2026-10-08T00:00"))
    }

    @Test
    fun snooze_ofAnException_updatesItInPlace() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        val ex = cal.events.values.single { it.originalId == id }
        clock.set("2026-10-05T09:30")
        store.snooze(InstanceKey(ex.id, t("2026-10-05T09:30")), clock.millis())
        assertEquals(1, cal.events.values.count { it.originalId == id })
        assertEquals(t("2026-10-05T10:00"), cal.event(ex.id)!!.dtStart)
    }

    @Test
    fun snoozeTwice_sameKey_appliesOnce() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis() + 5_000)
        assertEquals(1, cal.events.values.count { it.originalId == id })
    }

    @Test
    fun snooze_acrossMidnight() {
        clock.set("2026-10-05T23:50")
        val id = oneOff("2026-10-05T23:50")
        store.snooze(key(id, "2026-10-05T23:50"), clock.millis())
        assertEquals(t("2026-10-06T00:20"), cal.event(id)!!.dtStart)
    }

    @Test
    fun tomorrow_oneOff_keepsWallClock_acrossDstEnd() {
        clock.set("2026-10-24T08:00")
        val id = oneOff("2026-10-24T08:00")
        store.tomorrow(key(id, "2026-10-24T08:00"), clock.millis())
        assertEquals(t("2026-10-25T08:00"), cal.event(id)!!.dtStart)
    }

    @Test
    fun tomorrow_weekly_movesTheOccurrenceWhenTomorrowIsFree() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        store.tomorrow(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(listOf(t("2026-10-06T09:00"), t("2026-10-07T09:00")), begins("2026-10-05T00:00", "2026-10-08T00:00"))
    }

    @Test
    fun tomorrow_daily_cancelsWhenTomorrowAlreadyRings() {
        val id = series("2026-10-05T09:00", "FREQ=DAILY")
        store.tomorrow(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(listOf(t("2026-10-06T09:00")), begins("2026-10-05T00:00", "2026-10-07T00:00"))
        assertTrue(cal.events.values.single { it.originalId == id }.isCanceled)
    }

    @Test
    fun snooze_unsyncedSeries_waitsInPending_ringsLocally_thenAppliesOnceSynced() {
        cal.syncNewEvents = false
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        assertTrue("no exception before the series has a sync id", cal.events.values.none { it.originalId == id })
        assertEquals(1, local.pending.size)
        assertTrue(local.ringCache().any { it.key == key(id, "2026-10-05T09:30") })

        cal.markSynced(id)
        store.applyPending()
        assertTrue(local.pending.isEmpty())
        assertEquals(t("2026-10-05T09:30"), cal.events.values.single { it.originalId == id }.dtStart)
    }

    @Test
    fun tomorrow_unsyncedDailySeries_doesNotCancelYet() {
        cal.syncNewEvents = false
        val id = series("2026-10-05T09:00", "FREQ=DAILY")
        store.tomorrow(key(id, "2026-10-05T09:00"), clock.millis())
        assertTrue(cal.events.values.none { it.originalId == id })
        assertEquals(1, local.pending.size)
    }

    private fun snoozeAt(eventId: Long, at: String) = store.snooze(InstanceKey(eventId, t(at)), t(at))

    @Test
    fun tomorrow_afterSnoozes_oneOff_goesToTheOriginalTimeTomorrow() {
        val id = oneOff("2026-10-05T09:00")
        snoozeAt(id, "2026-10-05T09:00") // → 09:30
        snoozeAt(id, "2026-10-05T09:30") // → 10:00
        snoozeAt(id, "2026-10-05T10:00") // → 10:30
        assertEquals(t("2026-10-05T10:30"), cal.event(id)!!.dtStart)
        store.tomorrow(key(id, "2026-10-05T10:30"), t("2026-10-05T10:30"))
        assertEquals(t("2026-10-06T09:00"), cal.event(id)!!.dtStart)
        assertEquals(null, local.snoozeOrigin(id))
    }

    @Test
    fun tomorrow_afterSnoozes_weeklyOccurrence_goesToTheOriginalTimeTomorrow() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        snoozeAt(id, "2026-10-05T09:00") // exception at 09:30
        val ex = cal.events.values.single { it.originalId == id }
        snoozeAt(ex.id, "2026-10-05T09:30") // → 10:00
        store.tomorrow(InstanceKey(ex.id, t("2026-10-05T10:00")), t("2026-10-05T10:00"))
        assertEquals(listOf(t("2026-10-06T09:00"), t("2026-10-07T09:00")), begins("2026-10-05T00:00", "2026-10-08T00:00"))
    }

    @Test
    fun editingAfterASnooze_forgetsTheOldOriginalTime() {
        val id = oneOff("2026-10-05T09:00")
        snoozeAt(id, "2026-10-05T09:00")
        store.update(id, AlarmInput(LocalTime.of(12, 0), null, RecurrenceRule.Once, "One-off", null))
        snoozeAt(id, "2026-10-05T12:00")
        store.tomorrow(key(id, "2026-10-05T12:30"), t("2026-10-05T12:30"))
        assertEquals(t("2026-10-06T12:00"), cal.event(id)!!.dtStart)
    }

    @Test
    fun stop_afterSnoozes_forgetsTheOriginalTime() {
        val id = oneOff("2026-10-05T09:00")
        snoozeAt(id, "2026-10-05T09:00")
        assertEquals(t("2026-10-05T09:00"), local.snoozeOrigin(id))
        store.stop(key(id, "2026-10-05T09:30"), t("2026-10-05T09:30"))
        assertEquals(null, local.snoozeOrigin(id))
    }

    @Test
    fun stop_oneOff_greysItAtItsTime() {
        val id = oneOff("2026-10-05T09:00")
        store.stop(key(id, "2026-10-05T09:00"), clock.millis())
        assertTrue(cal.event(id)!!.isOff)
        assertEquals(t("2026-10-05T09:00"), cal.event(id)!!.dtStart)
    }

    @Test
    fun stop_weekly_changesNothingInTheCalendar() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        val before = cal.events.toMap()
        store.stop(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(before, cal.events.toMap())
        assertEquals(RingAction.STOP, local.handled[key(id, "2026-10-05T09:00")]!!.first)
    }

    @Test
    fun deletedWhileRinging_actionsOnlyMarkHandled() {
        val id = oneOff("2026-10-05T09:00")
        cal.deleteEvent(id)
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        assertTrue(cal.events.isEmpty())
        assertTrue(key(id, "2026-10-05T09:00") in local.handledKeys())
        assertTrue(local.pending.isEmpty())
    }

    @Test
    fun locked_snoozeIsQueued_keepsRinging_thenAppliedAfterUnlock() {
        val id = oneOff("2026-10-05T09:00")
        local.addRinging(listOf(RingingEntry(key(id, "2026-10-05T09:00"), id, "One-off", clock.millis())))
        unlocked = false
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(t("2026-10-05T09:00"), cal.event(id)!!.dtStart)
        assertEquals(RingAction.SNOOZE, local.pending.single().action)
        assertEquals(listOf(CachedOccurrence(key(id, "2026-10-05T09:30"), id, "One-off")), local.cache)
        unlocked = true
        store.applyPending()
        assertEquals(t("2026-10-05T09:30"), cal.event(id)!!.dtStart)
        assertTrue(local.pending.isEmpty())
    }

    @Test
    fun failedWrite_isQueued_soTheAlarmIsNotLost() {
        val id = oneOff("2026-10-05T09:00")
        cal.failWrites = true
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(1, local.pending.size)
        assertEquals(t("2026-10-05T09:30"), local.cache.single().ringAt)
        cal.failWrites = false
        store.applyPending()
        assertEquals(t("2026-10-05T09:30"), cal.event(id)!!.dtStart)
    }

    @Test
    fun refreshRingCache_readsTheCalendar_andSkipsHandled() {
        val a = oneOff("2026-10-05T10:00", "A")
        oneOff("2026-10-05T11:00", "B")
        local.markHandled(key(a, "2026-10-05T10:00"), RingAction.STOP, clock.millis())
        assertEquals(listOf("B"), store.refreshRingCache().map { it.title })
        assertEquals(listOf("B"), local.cache.map { it.title })
    }

    @Test
    fun refreshRingCache_extendsTo366Days_whenNothingSooner() {
        oneOff("2026-11-20T08:00", "Far")
        assertEquals(listOf("Far"), store.refreshRingCache().map { it.title })
    }

    @Test
    fun refreshRingCache_calendarMissing_keepsPreviousCache() {
        local.cache = listOf(CachedOccurrence(InstanceKey(7, t("2026-10-05T12:00")), 7, "Cached"))
        cal.exists = false
        assertEquals(listOf("Cached"), store.refreshRingCache().map { it.title })
    }

    @Test
    fun refreshRingCache_locked_keepsPreviousMinusHandled() {
        local.cache = listOf(
            CachedOccurrence(InstanceKey(7, t("2026-10-05T12:00")), 7, "Keep"),
            CachedOccurrence(InstanceKey(8, t("2026-10-05T13:00")), 8, "Stopped"),
        )
        local.markHandled(InstanceKey(8, t("2026-10-05T13:00")), RingAction.STOP, clock.millis())
        unlocked = false
        assertEquals(listOf("Keep"), store.refreshRingCache().map { it.title })
    }

    @Test
    fun refreshRingCache_appliesPendingFirst() {
        val id = oneOff("2026-10-05T09:00")
        unlocked = false
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        unlocked = true
        assertEquals(listOf(key(id, "2026-10-05T09:30")), store.refreshRingCache().map { it.key })
        assertTrue(local.pending.isEmpty())
    }

    // ---- auto-snooze cap (Decision #1) -----------------------------------------------------

    /**
     * After 3 auto-snoozes (count reaches 3) the 4th unanswered ring is promoted to Tomorrow.
     * Uses a one-off: each snooze moves the event forward so consecutive keys are different.
     */
    @Test
    fun autoSnooze_threeAutoSnoozesThenTomorrow() {
        val id = oneOff("2026-10-05T09:00")
        // Ring 1 → snooze to 09:30 (count=1)
        clock.now = t("2026-10-05T09:00")
        store.autoSnooze(InstanceKey(id, t("2026-10-05T09:00")), clock.millis())
        assertEquals(1, local.autoSnoozeCount(id))
        // Ring 2 → snooze to 10:00 (count=2)
        clock.now = t("2026-10-05T09:30")
        store.autoSnooze(InstanceKey(id, t("2026-10-05T09:30")), clock.millis())
        assertEquals(2, local.autoSnoozeCount(id))
        // Ring 3 → snooze to 10:30 (count=3)
        clock.now = t("2026-10-05T10:00")
        store.autoSnooze(InstanceKey(id, t("2026-10-05T10:00")), clock.millis())
        assertEquals(3, local.autoSnoozeCount(id))
        // Ring 4 → count≥3: Tomorrow at the time the alarm was set for (not the snoozed 10:30), counter cleared
        clock.now = t("2026-10-05T10:30")
        store.autoSnooze(InstanceKey(id, t("2026-10-05T10:30")), clock.millis())
        assertEquals(t("2026-10-06T09:00"), cal.event(id)!!.dtStart)
        assertEquals(0, local.autoSnoozeCount(id))
    }

    // ---- ring-set uses cache keys, not raw calendar begins (Decision 2026-10-02 #2) --------

    /**
     * An event stored in UTC has a raw calendar BEGIN that differs from its wall-clock time in the
     * device zone (Asia/Famagusta).  refreshRingCache must return a CachedOccurrence whose key uses
     * the adjusted (wall-clock-in-deviceZone) begin, not the raw epoch.  A subsequent addRinging /
     * removeRinging round-trip with the cache key must succeed.
     */
    @Test
    fun ringSet_usesCacheKey_notRawBegin_crossZone() {
        // UTC wall-clock 10:00 → raw epoch = 2026-10-05T10:00Z = 2026-10-05T13:00 Famagusta
        // adjustedRingAt(UTC wall 10:00, UTC, Famagusta) = 2026-10-05T10:00 Famagusta = t("2026-10-05T10:00")
        val utcBegin = LocalDateTime.parse("2026-10-05T10:00")
            .atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val id = cal.insertEvent(CAL, "UTC alarm", EventTiming.Single(utcBegin, utcBegin + 15 * MINUTE), "UTC")

        val cache = store.refreshRingCache()

        assertEquals(1, cache.size)
        val expectedKey = InstanceKey(id, t("2026-10-05T10:00")) // wall-clock 10:00 in Famagusta
        assertEquals(expectedKey, cache.single().key)
        assertTrue(utcBegin != cache.single().key.begin) // raw epoch must differ from adjusted key

        // Callers record addRinging with the cache key; removeRinging must match using the same key.
        local.addRinging(listOf(RingingEntry(expectedKey, id, "UTC alarm", clock.millis())))
        local.removeRinging(setOf(expectedKey))
        assertTrue(local.ringing().isEmpty())
    }
}
