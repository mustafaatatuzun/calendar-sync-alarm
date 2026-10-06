package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactLineTest {
    private val ahmet = AlarmContact("Ahmet Yılmaz", "+90 532 123 45 67")

    @Test
    fun read_emptyOrMissing_isNull() {
        assertNull(ContactLine.read(null))
        assertNull(ContactLine.read(""))
        assertNull(ContactLine.read("Bring the invoice"))
    }

    @Test
    fun read_ownLine() =
        assertEquals(ahmet, ContactLine.read("Call: Ahmet Yılmaz | +90 532 123 45 67"))

    @Test
    fun read_amongOtherLines() =
        assertEquals(ahmet, ContactLine.read("Bring the invoice\nCall: Ahmet Yılmaz | +90 532 123 45 67\nthanks"))

    @Test
    fun read_htmlFromGoogleCalendarWeb() =
        assertEquals(ahmet, ContactLine.read("Bring the invoice<br>Call: <b>Ahmet Yılmaz</b> | +90 532 123 45 67<br>"))

    @Test
    fun read_nameWithPipe_splitsOnLastPipe() =
        assertEquals(AlarmContact("A | B", "123"), ContactLine.read("Call: A | B | 123"))

    @Test
    fun write_intoEmpty() =
        assertEquals("Call: Ahmet Yılmaz | +90 532 123 45 67", ContactLine.write(null, ahmet))

    @Test
    fun write_keepsOtherText() =
        assertEquals(
            "Bring the invoice\nCall: Ahmet Yılmaz | +90 532 123 45 67",
            ContactLine.write("Bring the invoice", ahmet),
        )

    @Test
    fun write_replacesOldContact() =
        assertEquals(
            "Bring the invoice\nCall: Ahmet Yılmaz | +90 532 123 45 67",
            ContactLine.write("Bring the invoice\nCall: Old | 1", ahmet),
        )

    @Test
    fun write_replacesOldContact_inHtmlDescription() {
        val written = ContactLine.write("Bring the invoice<br>Call: <b>Old</b> | 1<br>", ahmet)
        assertEquals(ahmet, ContactLine.read(written))
        assertEquals("Bring the invoice\nCall: Ahmet Yılmaz | +90 532 123 45 67", written)
    }

    @Test
    fun write_null_removesContactOnly() {
        assertEquals("Bring the invoice", ContactLine.write("Bring the invoice\nCall: Old | 1", null))
        assertEquals("", ContactLine.write("Call: Old | 1", null))
        assertEquals("", ContactLine.write(null, null))
    }

    @Test
    fun roundTrip() =
        assertEquals(ahmet, ContactLine.read(ContactLine.write("x", ahmet)))

    private val ayse = AlarmContact("Ayşe Kaya", "+357 99 123456")

    @Test
    fun whatsApp_isItsOwnLine_independentOfCall() {
        val both = ContactLine.write(ContactLine.write("Notes", ahmet, ContactKind.CALL), ayse, ContactKind.WHATSAPP)
        assertEquals("Notes\nCall: Ahmet Yılmaz | +90 532 123 45 67\nWhatsApp: Ayşe Kaya | +357 99 123456", both)
        assertEquals(ahmet, ContactLine.read(both, ContactKind.CALL))
        assertEquals(ayse, ContactLine.read(both, ContactKind.WHATSAPP))
    }

    @Test
    fun whatsApp_removeLeavesCallLine() {
        val both = "Call: Ahmet Yılmaz | +90 532 123 45 67\nWhatsApp: Ayşe Kaya | +357 99 123456"
        assertEquals("Call: Ahmet Yılmaz | +90 532 123 45 67", ContactLine.write(both, null, ContactKind.WHATSAPP))
        assertNull(ContactLine.read("Call: Ahmet Yılmaz | +90 532 123 45 67", ContactKind.WHATSAPP))
    }
}
