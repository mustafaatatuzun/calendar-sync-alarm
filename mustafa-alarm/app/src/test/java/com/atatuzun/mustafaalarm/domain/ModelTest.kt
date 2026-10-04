package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private val base = EventRow(1, "a", 0, null, null, null, false, null, null, null, null, null)

    @Test
    fun lengthMillis_prefersDtEnd_thenDuration_thenDefault() {
        assertEquals(15 * MINUTE, base.lengthMillis)
        assertEquals(HOUR, base.copy(dtEnd = HOUR).lengthMillis)
        assertEquals(HOUR, base.copy(rrule = "FREQ=DAILY", duration = "P3600S").lengthMillis)
    }

    @Test
    fun eventFlags() {
        assertTrue(base.copy(rrule = "FREQ=DAILY").isSeries)
        assertTrue(base.copy(originalId = 9).isException)
        assertTrue(base.copy(colorKey = GRAPHITE_COLOR_KEY).isOff)
        assertTrue(base.copy(status = STATUS_CANCELED).isCanceled)
        assertFalse(base.isSeries || base.isException || base.isOff || base.isCanceled)
    }

    @Test
    fun instanceActivityAndOwner() {
        val i = InstanceRow(5, 100, 200, "a", false, null, null, null, 2)
        assertEquals(InstanceKey(5, 100), i.key)
        assertEquals(2L, i.alarmId)
        assertEquals(5L, i.copy(originalId = null).alarmId)
        assertTrue(i.isActive)
        assertFalse(i.copy(colorKey = GRAPHITE_COLOR_KEY).isActive)
        assertFalse(i.copy(status = STATUS_CANCELED).isActive)
        assertFalse(i.copy(allDay = true).isActive)
    }

    @Test
    fun ringAtIsTheOccurrenceBegin() {
        assertEquals(100L, CachedOccurrence(InstanceKey(1, 100), 1, "a").ringAt)
        assertEquals(100L, RingingEntry(InstanceKey(1, 100), 1, "a", 5).ringAt)
    }
}
