package com.atatuzun.mustafaalarm.domain

/** A person to call when the alarm rings. */
data class AlarmContact(val name: String, val number: String)

/**
 * Stores an [AlarmContact] as one "Call: <name> | <number>" line in the event description, leaving any
 * other text (e.g. notes typed in Google Calendar on the PC) untouched.
 */
object ContactLine {
    private const val PREFIX = "Call:"
    private val lineBreak = Regex("""\r?\n|<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]*>")

    fun read(description: String?): AlarmContact? =
        description.orEmpty().split(lineBreak)
            .map { it.replace(tag, "").trim() }
            .firstNotNullOfOrNull(::parse)

    /** The description with the contact line replaced, added, or (for null) removed. */
    fun write(description: String?, contact: AlarmContact?): String {
        val kept = description.orEmpty().split(lineBreak).filter { parse(it.replace(tag, "").trim()) == null }
            .joinToString("\n").trimEnd()
        val line = contact?.let { "$PREFIX ${it.name.trim()} | ${it.number.trim()}" } ?: return kept
        return if (kept.isEmpty()) line else "$kept\n$line"
    }

    private fun parse(line: String): AlarmContact? {
        if (!line.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = line.substring(PREFIX.length)
        val bar = rest.lastIndexOf('|').takeIf { it >= 0 } ?: return null
        val name = rest.substring(0, bar).trim()
        val number = rest.substring(bar + 1).trim()
        return if (number.isEmpty()) null else AlarmContact(name.ifEmpty { number }, number)
    }
}
