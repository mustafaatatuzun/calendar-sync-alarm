package com.atatuzun.mustafaalarm.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests covering the pure-Kotlin logic in ReliabilityChecks. */
class ReliabilityChecksTest {

    // ── syncPredicate ──────────────────────────────────────────────────────────

    @Test
    fun `syncPredicate null email returns true (phone-local calendar)`() {
        assertTrue(syncPredicate(accountEmail = null, masterSyncOn = false, accountSyncOn = false, calendarSyncEvents = false))
    }

    @Test
    fun `syncPredicate all flags on returns true`() {
        assertTrue(syncPredicate(accountEmail = "user@example.com", masterSyncOn = true, accountSyncOn = true, calendarSyncEvents = true))
    }

    @Test
    fun `syncPredicate masterSyncOn false returns false`() {
        assertFalse(syncPredicate(accountEmail = "user@example.com", masterSyncOn = false, accountSyncOn = true, calendarSyncEvents = true))
    }

    @Test
    fun `syncPredicate accountSyncOn false returns false`() {
        assertFalse(syncPredicate(accountEmail = "user@example.com", masterSyncOn = true, accountSyncOn = false, calendarSyncEvents = true))
    }

    @Test
    fun `syncPredicate calendarSyncEvents false returns false`() {
        assertFalse(syncPredicate(accountEmail = "user@example.com", masterSyncOn = true, accountSyncOn = true, calendarSyncEvents = false))
    }

    @Test
    fun `syncPredicate all flags off with email returns false`() {
        assertFalse(syncPredicate(accountEmail = "user@example.com", masterSyncOn = false, accountSyncOn = false, calendarSyncEvents = false))
    }

    // ── alarmVolumeLowPredicate ────────────────────────────────────────────────

    @Test
    fun `alarmVolumeLowPredicate volume well below 50 percent returns true`() {
        assertTrue(alarmVolumeLowPredicate(streamVolume = 1, maxVolume = 6)) // 2 < 6
    }

    @Test
    fun `alarmVolumeLowPredicate volume at exactly 50 percent returns false`() {
        assertFalse(alarmVolumeLowPredicate(streamVolume = 3, maxVolume = 6)) // 6 < 6 is false
    }

    @Test
    fun `alarmVolumeLowPredicate volume above 50 percent returns false`() {
        assertFalse(alarmVolumeLowPredicate(streamVolume = 4, maxVolume = 6)) // 8 < 6 is false
    }

    @Test
    fun `alarmVolumeLowPredicate volume at max returns false`() {
        assertFalse(alarmVolumeLowPredicate(streamVolume = 7, maxVolume = 7)) // 14 < 7 is false
    }

    @Test
    fun `alarmVolumeLowPredicate volume zero always low`() {
        assertTrue(alarmVolumeLowPredicate(streamVolume = 0, maxVolume = 7)) // 0 < 7
    }

    @Test
    fun `alarmVolumeLowPredicate boundary 2 of 5 is low`() {
        assertTrue(alarmVolumeLowPredicate(streamVolume = 2, maxVolume = 5)) // 4 < 5
    }

    @Test
    fun `alarmVolumeLowPredicate boundary threshold depends on max`() {
        // max=10: threshold is 5. streamVolume=4 → 8 < 10 → true; streamVolume=5 → 10 < 10 → false
        assertTrue(alarmVolumeLowPredicate(streamVolume = 4, maxVolume = 10))
        assertFalse(alarmVolumeLowPredicate(streamVolume = 5, maxVolume = 10))
    }

    @Test
    fun `Check enum has exactly seven entries`() {
        assertEquals(7, Check.entries.size)
    }

    @Test
    fun `Check OVER_OTHER_APPS is present with expected label`() {
        assertEquals("Show over other apps", Check.OVER_OTHER_APPS.label)
    }

    @Test
    fun `Check entries have non-blank labels and reasons`() {
        for (check in Check.entries) {
            assertTrue("label blank for $check", check.label.isNotBlank())
            assertTrue("reason blank for $check", check.reason.isNotBlank())
        }
    }

    @Test
    fun `Check labels are distinct`() {
        val labels = Check.entries.map { it.label }
        assertEquals("duplicate labels found", labels.size, labels.toSet().size)
    }

    @Test
    fun `Banners defaults are all false`() {
        val b = Banners()
        assertFalse(b.volumeLow)
        assertFalse(b.missingPermission)
        assertFalse(b.syncOff)
    }

    @Test
    fun `Banners copy preserves individual fields`() {
        val b = Banners(volumeLow = true)
        assertTrue(b.volumeLow)
        assertFalse(b.missingPermission)
        assertFalse(b.syncOff)

        val b2 = b.copy(missingPermission = true)
        assertTrue(b2.volumeLow)
        assertTrue(b2.missingPermission)
        assertFalse(b2.syncOff)
    }

    @Test
    fun `Banners with all flags true round-trips through equals`() {
        val a = Banners(volumeLow = true, missingPermission = true, syncOff = true)
        val b = Banners(volumeLow = true, missingPermission = true, syncOff = true)
        assertEquals(a, b)
    }

    @Test
    fun `Check NOTIFICATIONS is present with expected label`() {
        assertEquals("Notifications", Check.NOTIFICATIONS.label)
    }

    @Test
    fun `Check SYNC is present with expected label`() {
        assertEquals("Google sync for Alarms", Check.SYNC.label)
    }

    @Test
    fun `Check BATTERY is present with expected label`() {
        assertEquals("Battery: Unrestricted", Check.BATTERY.label)
    }

    @Test
    fun `Check name values match enum constants`() {
        assertNotNull(Check.valueOf("NOTIFICATIONS"))
        assertNotNull(Check.valueOf("CALENDAR"))
        assertNotNull(Check.valueOf("EXACT_ALARMS"))
        assertNotNull(Check.valueOf("FULL_SCREEN"))
        assertNotNull(Check.valueOf("BATTERY"))
        assertNotNull(Check.valueOf("SYNC"))
    }
}
