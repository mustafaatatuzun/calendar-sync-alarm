package com.atatuzun.mustafaalarm.domain

import java.time.DayOfWeek

/**
 * Recurrence shapes the edit screen can author and read back. Any RRULE
 * outside this set still parses to null, which keeps the editor in the
 * AlarmKind.OTHER_REPEAT read-only branch (spec §5.3).
 */
sealed interface RecurrenceRule {

    /** RRULE body WITHOUT the "RRULE:" prefix; null means no recurrence. */
    fun build(): String?

    object Once : RecurrenceRule { override fun build(): String? = null }

    object Daily : RecurrenceRule { override fun build() = "FREQ=DAILY" }

    object EveryWeekday : RecurrenceRule {
        override fun build() = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
    }

    data class Weekly(val days: Set<DayOfWeek>) : RecurrenceRule {
        init { require(days.isNotEmpty()) { "Weekly requires at least one day" } }
        override fun build(): String =
            "FREQ=WEEKLY;BYDAY=" + DayOfWeek.entries.filter { it in days }
                .joinToString(",") { CODES.getValue(it) }
    }

    data class MonthlyDay(val day: Int) : RecurrenceRule {
        init { require(day in 1..31) { "MonthlyDay out of range: $day" } }
        override fun build() = "FREQ=MONTHLY;BYMONTHDAY=$day"
    }

    data class MonthlyNthWeekday(val nth: Int, val weekday: DayOfWeek) : RecurrenceRule {
        init { require(nth in NTH_ALLOWED) { "MonthlyNthWeekday nth out of range: $nth" } }
        override fun build() = "FREQ=MONTHLY;BYDAY=$nth${CODES.getValue(weekday)}"
    }

    object Yearly : RecurrenceRule { override fun build() = "FREQ=YEARLY" }

    companion object {
        private val CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )
        private val DOW_OF: Map<String, DayOfWeek> = CODES.entries.associate { (k, v) -> v to k }
        private val NTH_ALLOWED = setOf(1, 2, 3, 4, -1)
        private val MO_FR = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        private val ALLOWED_KEYS_WEEKLY = setOf("FREQ", "BYDAY", "WKST", "INTERVAL")
        private val ALLOWED_KEYS_MONTHLY_DAY = setOf("FREQ", "BYMONTHDAY", "WKST", "INTERVAL")
        private val ALLOWED_KEYS_MONTHLY_NTH = setOf("FREQ", "BYDAY", "BYSETPOS", "WKST", "INTERVAL")
        private val ALLOWED_KEYS_DAILY = setOf("FREQ", "WKST", "INTERVAL")
        private val ALLOWED_KEYS_YEARLY = setOf("FREQ", "WKST", "INTERVAL")

        fun parse(rrule: String?): RecurrenceRule? {
            if (rrule.isNullOrBlank()) return null
            val parts = rrule.trim().removePrefix("RRULE:")
                .split(';').filter { it.isNotBlank() }
                .associate { kv ->
                    val (k, v) = kv.split('=', limit = 2).let {
                        it[0].trim().uppercase() to it.getOrElse(1) { "" }.trim().uppercase()
                    }
                    k to v
                }
            if ((parts["INTERVAL"] ?: "1") != "1") return null
            return when (parts["FREQ"]) {
                "DAILY" -> if (parts.keys.all { it in ALLOWED_KEYS_DAILY }) Daily else null
                "WEEKLY" -> parseWeekly(parts)
                "MONTHLY" -> parseMonthly(parts)
                "YEARLY" -> if (parts.keys.all { it in ALLOWED_KEYS_YEARLY }) Yearly else null
                else -> null
            }
        }

        private fun parseWeekly(p: Map<String, String>): RecurrenceRule? {
            if (p.keys.any { it !in ALLOWED_KEYS_WEEKLY }) return null
            val byDay = p["BYDAY"]?.takeIf { it.isNotEmpty() } ?: return null
            val tokens = byDay.split(',').map { it.trim() }
            // Numeric prefix on weekly BYDAY is not a thing we author.
            if (tokens.any { it.any { ch -> ch.isDigit() || ch == '-' || ch == '+' } }) return null
            val days = tokens.map { DOW_OF[it] ?: return null }.toSet()
            if (days.isEmpty()) return null
            return if (days == MO_FR) EveryWeekday else Weekly(days)
        }

        private fun parseMonthly(p: Map<String, String>): RecurrenceRule? {
            // MonthlyDay path
            p["BYMONTHDAY"]?.let { byDay ->
                if (p.keys.any { it !in ALLOWED_KEYS_MONTHLY_DAY }) return null
                val day = byDay.toIntOrNull() ?: return null
                if (day !in 1..31) return null
                return MonthlyDay(day)
            }
            // MonthlyNthWeekday path (either prefixed BYDAY or BYDAY + BYSETPOS)
            val byDay = p["BYDAY"] ?: return null
            if (p.keys.any { it !in ALLOWED_KEYS_MONTHLY_NTH }) return null
            if (byDay.contains(",")) return null  // single token only
            val bysetpos = p["BYSETPOS"]?.toIntOrNull()
            if (bysetpos != null) {
                if (bysetpos !in NTH_ALLOWED) return null
                val dow = DOW_OF[byDay] ?: return null
                return MonthlyNthWeekday(bysetpos, dow)
            }
            // Prefixed form, e.g. "1MO" or "-1FR"
            val match = Regex("^([+-]?\\d+)([A-Z]{2})$").matchEntire(byDay) ?: return null
            val nth = match.groupValues[1].toIntOrNull() ?: return null
            if (nth !in NTH_ALLOWED) return null
            val dow = DOW_OF[match.groupValues[2]] ?: return null
            return MonthlyNthWeekday(nth, dow)
        }
    }
}
