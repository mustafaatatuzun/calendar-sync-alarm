package com.atatuzun.mustafaalarm.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** English UI strings that depend on time. */
object Texts {
    private val h24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val h12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val longDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

    fun clock(time: LocalTime, use24h: Boolean): String = time.format(if (use24h) h24 else h12)

    fun clock(millis: Long, zone: ZoneId, use24h: Boolean): String = clock(Times.localTime(millis, zone), use24h)

    fun dayLabel(day: LocalDate, today: LocalDate): String = when {
        day == today -> "Today"
        day == today.plusDays(1) -> "Tomorrow"
        day > today && day < today.plusDays(7) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        else -> day.format(longDate)
    }

    fun untilNext(next: Long, now: Long): String {
        val minutes = ((next - now).coerceAtLeast(0) + MINUTE - 1) / MINUTE
        val rest = when {
            minutes == 0L -> "now"
            minutes == 1L -> "in 1 minute"
            minutes < 60 -> "in $minutes minutes"
            minutes < 48 * 60 -> if (minutes % 60 == 0L) "in ${minutes / 60} h" else "in ${minutes / 60} h ${minutes % 60} min"
            else -> "in ${minutes / (24 * 60)} days"
        }
        return "Next alarm $rest"
    }

    fun weekdays(days: Set<DayOfWeek>): String =
        DayOfWeek.entries.filter { it in days }.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

    fun relative(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

    private val NTH_ORDINAL = mapOf(1 to "first", 2 to "second", 3 to "third", 4 to "fourth", -1 to "last")

    fun recurrenceSummary(
        rule: RecurrenceRule,
        time: LocalTime,
        date: LocalDate?,
        use24h: Boolean,
        locale: Locale,
    ): String = when (rule) {
        RecurrenceRule.Once ->
            if (date == null) "Does not repeat"
            else "Once on ${date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale))}"
        RecurrenceRule.Daily -> "Daily"
        RecurrenceRule.EveryWeekday -> "Every weekday (Mon-Fri)"
        is RecurrenceRule.Weekly -> {
            val labels = DayOfWeek.entries
                .filter { it in rule.days }
                .map { it.getDisplayName(TextStyle.SHORT, locale) }
            if (labels.size == 1) "Weekly on ${
                rule.days.first().getDisplayName(TextStyle.FULL, locale)
            }" else "Weekly on ${labels.joinToString(", ")}"
        }
        is RecurrenceRule.MonthlyDay -> "Monthly on day ${rule.day}"
        is RecurrenceRule.MonthlyNthWeekday -> {
            val ord = NTH_ORDINAL[rule.nth] ?: rule.nth.toString()
            "Monthly on the $ord ${rule.weekday.getDisplayName(TextStyle.FULL, locale)}"
        }
        RecurrenceRule.Yearly ->
            if (date == null) "Annually"
            else "Annually on ${date.format(DateTimeFormatter.ofPattern("MMMM d", locale))}"
    }
}
