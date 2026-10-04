package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class TimeEntryTest {
    @Test fun acceptsOnlyDigitsThatCanStillMakeATime() {
        assertEquals("", TimeEntry.push("", '7'))
        assertEquals("0", TimeEntry.push("", '0'))
        assertEquals("2", TimeEntry.push("2", '4'))
        assertEquals("23", TimeEntry.push("2", '3'))
        assertEquals("23", TimeEntry.push("23", '6'))
        assertEquals("235", TimeEntry.push("23", '5'))
        assertEquals("2359", TimeEntry.push("235", '9'))
        assertEquals("2359", TimeEntry.push("2359", '1'))
    }

    @Test fun parse() {
        assertEquals(LocalTime.of(7, 30), TimeEntry.parse("0730"))
        assertEquals(LocalTime.of(7, 30), TimeEntry.parse("073"))
        assertEquals(LocalTime.of(7, 0), TimeEntry.parse("07"))
        assertNull(TimeEntry.parse("0"))
        assertNull(TimeEntry.parse(""))
    }

    @Test fun displayAndPop() {
        assertEquals("07:3_", TimeEntry.display("073"))
        assertEquals("__:__", TimeEntry.display(""))
        assertEquals("07", TimeEntry.pop("073"))
    }
}
