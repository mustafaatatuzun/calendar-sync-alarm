package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.domain.Rfc5545Duration.ofMillis
import com.atatuzun.mustafaalarm.domain.Rfc5545Duration.parseMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Rfc5545DurationTest {
    @Test fun parsesRfcStyle() {
        assertEquals(15 * MINUTE, parseMillis("PT15M"))
        assertEquals(HOUR, parseMillis("PT1H"))
        assertEquals(90 * MINUTE, parseMillis("PT1H30M"))
        assertEquals(DAY, parseMillis("P1D"))
        assertEquals(7 * DAY, parseMillis("P1W"))
    }

    @Test fun parsesProviderStyleDuration() {
        assertEquals(15 * MINUTE, parseMillis("P900S"))
        assertEquals(HOUR, parseMillis("P3600S"))
    }

    @Test fun rejectsGarbage() {
        listOf("", "P", "PT", "15M", "PTXM", "-PT15M", "PT0M").forEach { assertNull(it, parseMillis(it)) }
    }

    @Test fun formats() {
        assertEquals("PT15M", ofMillis(15 * MINUTE))
        assertEquals("PT60M", ofMillis(HOUR))
        assertEquals("PT90S", ofMillis(90_500))
    }
}
