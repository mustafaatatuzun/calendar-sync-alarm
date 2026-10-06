package com.atatuzun.mustafaalarm.domain

/** A person to reach when the alarm rings. */
data class AlarmContact(val name: String, val number: String)

/** Who an alarm calls and who it messages on WhatsApp; either may be missing. */
data class AlarmPeople(val call: AlarmContact?, val whatsApp: AlarmContact?)

enum class ContactKind(val prefix: String) { CALL("Call:"), WHATSAPP("WhatsApp:") }

/**
 * Stores each [AlarmContact] as one "<Kind>: <name> | <number>" line in the event description, leaving any
 * other text (e.g. notes typed in Google Calendar on the PC) untouched.
 */
object ContactLine {
    private val lineBreak = Regex("""\r?\n|<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]*>")

    fun read(description: String?, kind: ContactKind = ContactKind.CALL): AlarmContact? =
        description.orEmpty().split(lineBreak)
            .firstNotNullOfOrNull { parse(it, kind) }

    /** The description with the [kind] line replaced, added, or (for null) removed. */
    fun write(description: String?, contact: AlarmContact?, kind: ContactKind = ContactKind.CALL): String {
        val kept = description.orEmpty().split(lineBreak).filter { parse(it, kind) == null }
            .joinToString("\n").trimEnd()
        val line = contact?.let { "${kind.prefix} ${it.name.trim()} | ${it.number.trim()}" } ?: return kept
        return if (kept.isEmpty()) line else "$kept\n$line"
    }

    private fun parse(rawLine: String, kind: ContactKind): AlarmContact? {
        val line = rawLine.replace(tag, "").trim()
        if (!line.startsWith(kind.prefix, ignoreCase = true)) return null
        val rest = line.substring(kind.prefix.length)
        val bar = rest.lastIndexOf('|').takeIf { it >= 0 } ?: return null
        val name = rest.substring(0, bar).trim()
        val number = rest.substring(bar + 1).trim()
        return if (number.isEmpty()) null else AlarmContact(name.ifEmpty { number }, number)
    }
}

/** wa.me links need the full international number as digits only. */
object WhatsAppNumber {
    /** [toE164] converts a local-format number using the phone's country, or returns null. */
    fun digits(number: String, toE164: (String) -> String?): String? {
        val trimmed = number.trim()
        val international = when {
            trimmed.startsWith("+") -> trimmed
            trimmed.startsWith("00") -> trimmed.drop(2)
            else -> toE164(trimmed) ?: trimmed
        }
        return international.filter(Char::isDigit).takeIf { it.length >= 7 }
    }
}
