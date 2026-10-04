package com.atatuzun.mustafaalarm.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object Times {
    fun floorMinute(millis: Long): Long = millis - Math.floorMod(millis, MINUTE)

    fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun localTime(millis: Long, zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().withSecond(0).withNano(0)

    fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        ZonedDateTime.of(date, time.withSecond(0).withNano(0), zone).toInstant().toEpochMilli()

    fun startOfDay(millis: Long, zone: ZoneId): Long =
        localDate(millis, zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Same wall-clock time [days] later (DST-safe). */
    fun plusDays(millis: Long, days: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).plusDays(days).toInstant().toEpochMilli()

    /** The next moment strictly after [now] whose wall-clock time is [time]. */
    fun nextAt(time: LocalTime, now: Long, zone: ZoneId): Long {
        val today = localDate(now, zone)
        val candidate = at(today, time, zone)
        return if (candidate > now) candidate else at(today.plusDays(1), time, zone)
    }

    /** The first moment strictly after [now] that falls on one of [days] at [time]. */
    fun firstWeeklyStart(days: Set<DayOfWeek>, time: LocalTime, now: Long, zone: ZoneId): Long {
        require(days.isNotEmpty()) { "no weekdays selected" }
        val today = localDate(now, zone)
        return (0L..7L).asSequence()
            .map { today.plusDays(it) }
            .filter { it.dayOfWeek in days }
            .map { at(it, time, zone) }
            .first { it > now }
    }

    /**
     * Local-wall-clock ring instant: when the device's current zone matches the
     * event's own [eventZone], the stored [begin] is already correct. When
     * the zones differ, re-interpret the [begin]'s wall-clock time (as seen in the
     * event's zone) in the device's current zone. Spec §5.1, Decision 2026-10-02 #2.
     */
    fun adjustedRingAt(begin: Long, eventZone: ZoneId, deviceZone: ZoneId): Long {
        if (eventZone == deviceZone) return begin
        val localDt = Instant.ofEpochMilli(begin).atZone(eventZone).toLocalDateTime()
        return localDt.atZone(deviceZone).toInstant().toEpochMilli()
    }

    fun firstDailyStart(time: LocalTime, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayAt = ZonedDateTime.of(today, time.withSecond(0).withNano(0), zone).toInstant().toEpochMilli()
        return if (todayAt > now) todayAt
        else ZonedDateTime.of(today.plusDays(1), time.withSecond(0).withNano(0), zone).toInstant().toEpochMilli()
    }

    fun firstWeekdayStart(time: LocalTime, now: Long, zone: ZoneId): Long {
        var candidate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val t = time.withSecond(0).withNano(0)
        var at = ZonedDateTime.of(candidate, t, zone).toInstant().toEpochMilli()
        while (candidate.dayOfWeek == DayOfWeek.SATURDAY || candidate.dayOfWeek == DayOfWeek.SUNDAY || at <= now) {
            candidate = candidate.plusDays(1)
            at = ZonedDateTime.of(candidate, t, zone).toInstant().toEpochMilli()
        }
        return at
    }

    fun firstMonthlyDayStart(day: Int, time: LocalTime, now: Long, zone: ZoneId): Long {
        require(day in 1..31)
        var ym = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        val t = time.withSecond(0).withNano(0)
        repeat(60) {  // bounded; monthly day with day in 1..31 resolves within 24 months at most
            val lengthOfMonth = ym.lengthOfMonth()
            if (day <= lengthOfMonth) {
                val target = ym.withDayOfMonth(day)
                val at = ZonedDateTime.of(target, t, zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
            ym = ym.plusMonths(1)
        }
        error("firstMonthlyDayStart: no occurrence found within 60 months — bug")
    }

    fun firstMonthlyNthWeekdayStart(nth: Int, weekday: DayOfWeek, time: LocalTime, now: Long, zone: ZoneId): Long {
        require(nth in setOf(1, 2, 3, 4, -1))
        var ym = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        val t = time.withSecond(0).withNano(0)
        repeat(60) {
            val target = nthWeekdayOfMonth(ym, nth, weekday)
            if (target != null) {
                val at = ZonedDateTime.of(target, t, zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
            ym = ym.plusMonths(1)
        }
        error("firstMonthlyNthWeekdayStart: no occurrence found within 60 months — bug")
    }

    fun firstYearlyStart(anchor: LocalDate, time: LocalTime, now: Long, zone: ZoneId): Long {
        var year = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().year
        val t = time.withSecond(0).withNano(0)
        repeat(16) {  // bounded; Feb-29 anchor resolves within <=8 years
            val candidate = runCatching { LocalDate.of(year, anchor.monthValue, anchor.dayOfMonth) }.getOrNull()
            if (candidate != null) {
                val at = ZonedDateTime.of(candidate, t, zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
            year += 1
        }
        error("firstYearlyStart: no occurrence found within 16 years — bug")
    }

    private fun nthWeekdayOfMonth(firstOfMonth: LocalDate, nth: Int, weekday: DayOfWeek): LocalDate? {
        if (nth == -1) {
            var d = firstOfMonth.withDayOfMonth(firstOfMonth.lengthOfMonth())
            while (d.dayOfWeek != weekday) d = d.minusDays(1)
            return d
        }
        var d = firstOfMonth
        while (d.dayOfWeek != weekday) d = d.plusDays(1)
        d = d.plusWeeks((nth - 1).toLong())
        return if (d.monthValue == firstOfMonth.monthValue) d else null
    }
}
