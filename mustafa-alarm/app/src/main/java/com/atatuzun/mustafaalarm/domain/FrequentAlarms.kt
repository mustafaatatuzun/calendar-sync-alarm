package com.atatuzun.mustafaalarm.domain

import java.time.LocalTime

/** "Your frequent alarms": the most-created times of the last 60 days (spec §5.3, §9.3). */
object FrequentAlarms {
    const val WINDOW = 60 * DAY

    fun top(history: List<Pair<Int, Long>>, now: Long, limit: Int = 5): List<LocalTime> =
        history.filter { it.second >= now - WINDOW }
            .groupBy({ it.first }, { it.second })
            .entries
            .sortedWith(compareByDescending<Map.Entry<Int, List<Long>>> { it.value.size }.thenByDescending { it.value.max() })
            .take(limit)
            .map { LocalTime.of(it.key / 60, it.key % 60) }
}
