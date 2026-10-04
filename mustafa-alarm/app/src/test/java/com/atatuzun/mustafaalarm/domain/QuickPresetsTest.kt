package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class QuickPresetsTest {
    @Test fun presetsMatchTheSpec() {
        assertEquals(listOf(5, 15, 30, 45, 60, 120, 240, 480, 720, 1440), QuickPresets.relativeMinutes)
        assertEquals(listOf("05:30", "06:00", "06:30", "07:00", "07:30", "08:00").map(LocalTime::parse), QuickPresets.morning)
    }

    @Test fun relativeTarget_countsFromTheCurrentMinute() =
        assertEquals(t("2026-10-02T09:19"), QuickPresets.relativeTarget(5, t("2026-10-02T09:14") + 40_000))
}
