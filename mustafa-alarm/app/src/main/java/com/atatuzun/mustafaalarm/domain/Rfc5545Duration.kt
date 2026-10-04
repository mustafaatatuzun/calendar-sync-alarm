package com.atatuzun.mustafaalarm.domain

/** RFC 5545 DURATION values, plus the provider's "P900S" form (seconds without the T). */
object Rfc5545Duration {
    private val pattern = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")

    fun parseMillis(text: String): Long? {
        val match = pattern.matchEntire(text.trim().uppercase()) ?: return null
        val (sign, w, d, h, m, s) = match.destructured
        if (sign == "-" || listOf(w, d, h, m, s).all { it.isEmpty() }) return null
        val seconds = w.num() * 604_800 + d.num() * 86_400 + h.num() * 3_600 + m.num() * 60 + s.num()
        return if (seconds > 0) seconds * 1_000 else null
    }

    fun ofMillis(millis: Long): String =
        if (millis % MINUTE == 0L) "PT${millis / MINUTE}M" else "PT${millis / 1_000}S"

    private fun String.num(): Long = toLongOrNull() ?: 0L
}
