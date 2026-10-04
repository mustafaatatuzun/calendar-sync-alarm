package com.atatuzun.mustafaalarm.domain

import java.time.LocalTime

object QuickPresets {
    val relativeMinutes = listOf(5, 15, 30, 45, 60, 120, 240, 480, 720, 1440)
    val morning: List<LocalTime> = listOf("05:30", "06:00", "06:30", "07:00", "07:30", "08:00").map(LocalTime::parse)

    fun relativeTarget(minutes: Int, now: Long): Long = Times.floorMinute(now) + minutes * MINUTE
}
