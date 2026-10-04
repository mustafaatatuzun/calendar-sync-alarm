package com.atatuzun.mustafaalarm.domain

import java.time.LocalTime

/** Number-pad time entry: digits fill HH then MM ("0730" → 07:30). */
object TimeEntry {
    fun push(digits: String, digit: Char): String {
        if (!digit.isDigit() || digits.length >= 4) return digits
        val next = digits + digit
        return if (canBecomeValid(next)) next else digits
    }

    fun pop(digits: String): String = digits.dropLast(1)

    fun parse(digits: String): LocalTime? {
        if (digits.length < 2 || !canBecomeValid(digits)) return null
        val hour = digits.substring(0, 2).toInt()
        val minute = (digits.getOrNull(2)?.digitToInt() ?: 0) * 10 + (digits.getOrNull(3)?.digitToInt() ?: 0)
        return LocalTime.of(hour, minute)
    }

    fun display(digits: String): String {
        val padded = digits.padEnd(4, '_')
        return "${padded.substring(0, 2)}:${padded.substring(2, 4)}"
    }

    private fun canBecomeValid(d: String): Boolean = when (d.length) {
        0 -> true
        1 -> d[0] in '0'..'2'
        2 -> d.toInt() <= 23
        else -> d.substring(0, 2).toInt() <= 23 && d[2] in '0'..'5'
    }
}
