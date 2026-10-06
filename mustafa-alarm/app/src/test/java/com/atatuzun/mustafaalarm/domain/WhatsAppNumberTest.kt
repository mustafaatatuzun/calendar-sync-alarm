package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppNumberTest {
    private val noE164: (String) -> String? = { null }

    @Test
    fun international_keepsDigitsOnly() =
        assertEquals("905321234567", WhatsAppNumber.digits("+90 (532) 123-45 67", noE164))

    @Test
    fun doubleZeroPrefix_isInternational() =
        assertEquals("35799123456", WhatsAppNumber.digits("00357 99 123456", noE164))

    @Test
    fun localNumber_usesTheConverter() =
        assertEquals("905480001234", WhatsAppNumber.digits("0548 000 12 34") { if (it == "0548 000 12 34") "+905480001234" else null })

    @Test
    fun localNumber_converterFails_fallsBackToDigits() =
        assertEquals("05480001234", WhatsAppNumber.digits("0548 000 12 34", noE164))

    @Test
    fun tooShortOrEmpty_isNull() {
        assertNull(WhatsAppNumber.digits("", noE164))
        assertNull(WhatsAppNumber.digits("*123#", noE164))
    }
}
