package com.atatuzun.mustafaalarm.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Mustafa's zone. It follows EU summer time (ends 2026-10-25 04:00 → 03:00). */
val ZONE: ZoneId = ZoneId.of("Asia/Famagusta")

/** Epoch millis of a local date-time in [ZONE], e.g. t("2026-10-02T09:00"). */
fun t(local: String): Long = LocalDateTime.parse(local).atZone(ZONE).toInstant().toEpochMilli()

class TestClock(var now: Long, private val zoneId: ZoneId = ZONE) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)
    override fun instant(): Instant = Instant.ofEpochMilli(now)
    fun set(local: String) { now = t(local) }
}
