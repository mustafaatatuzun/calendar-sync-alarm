package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.domain.FakeCalendarAccess.Companion.CAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class AlarmStoreContactTest {
    private val clock = TestClock(t("2026-10-05T09:00") + 20_000) // Monday 09:00:20
    private val cal = FakeCalendarAccess()
    private val local = InMemoryLocalStore()
    private var available = true
    private val store = AlarmStore(cal, local, clock, { CAL }, { 30 }, { available })
    private val ahmet = AlarmContact("Ahmet Yılmaz", "+90 532 123 45 67")

    private val ayse = AlarmContact("Ayşe Kaya", "+357 99 123456")

    private fun input(
        message: String = "Call Ahmet",
        contact: AlarmContact? = null,
        recurrence: RecurrenceRule = RecurrenceRule.Once,
        whatsApp: AlarmContact? = null,
    ) = AlarmInput(LocalTime.of(11, 0), null, recurrence, message, null, contact, whatsApp)

    @Test
    fun create_withCallAndWhatsApp_storesBothLines_detailsReadsBoth() {
        val id = create(input(contact = ahmet, whatsApp = ayse))
        assertEquals("Call: Ahmet Yılmaz | +90 532 123 45 67\nWhatsApp: Ayşe Kaya | +357 99 123456", cal.event(id)!!.description)
        assertEquals(ahmet, store.details(id)!!.contact)
        assertEquals(ayse, store.details(id)!!.whatsApp)
    }

    @Test
    fun update_addsWhatsApp_keepsCallAndNotes() {
        val id = create(input(contact = ahmet))
        cal.updateEvent(id, EventPatch(description = "Notes\nCall: Ahmet Yılmaz | +90 532 123 45 67"))
        store.update(id, input(contact = ahmet, whatsApp = ayse))
        assertEquals(
            "Notes\nCall: Ahmet Yılmaz | +90 532 123 45 67\nWhatsApp: Ayşe Kaya | +357 99 123456",
            cal.event(id)!!.description,
        )
    }

    @Test
    fun peopleFor_returnsBoth_withSeriesFallbackPerKind() {
        val id = create(input(contact = ahmet, whatsApp = ayse, recurrence = RecurrenceRule.Daily))
        val begin = cal.event(id)!!.dtStart
        assertEquals(AlarmPeople(ahmet, ayse), store.peopleFor(InstanceKey(id, begin)))
        store.snooze(InstanceKey(id, begin), clock.millis())
        val exception = cal.events(CAL).single { it.originalId == id }
        cal.updateEvent(exception.id, EventPatch(description = "Call: Ahmet Yılmaz | +90 532 123 45 67"))
        assertEquals(AlarmPeople(ahmet, ayse), store.peopleFor(InstanceKey(exception.id, exception.dtStart)))
    }

    private fun create(i: AlarmInput) = (store.create(i) as SaveResult.Saved).eventId

    @Test
    fun create_withContact_writesDescription_andDetailsReadsIt() {
        val id = create(input(contact = ahmet))
        assertEquals("Call: Ahmet Yılmaz | +90 532 123 45 67", cal.event(id)!!.description)
        assertEquals(ahmet, store.details(id)!!.contact)
    }

    @Test
    fun create_withoutContact_leavesDescriptionEmpty() {
        val id = create(input())
        assertNull(store.details(id)!!.contact)
        assertEquals("", cal.event(id)!!.description.orEmpty())
    }

    @Test
    fun update_replacesContact_keepsPcWrittenNotes() {
        val id = create(input(contact = AlarmContact("Old", "1")))
        cal.updateEvent(id, EventPatch(description = "Bring the invoice\nCall: Old | 1"))
        store.update(id, input(contact = ahmet))
        assertEquals("Bring the invoice\nCall: Ahmet Yılmaz | +90 532 123 45 67", cal.event(id)!!.description)
    }

    @Test
    fun update_contactUnchanged_leavesPcDescriptionByteForByte() {
        val id = create(input())
        val html = "<b>Agenda</b><br>1. invoice<br>2. delivery"
        cal.updateEvent(id, EventPatch(description = html))
        store.update(id, input(message = "Renamed"))
        assertEquals(html, cal.event(id)!!.description)
    }

    @Test
    fun update_withoutContact_removesOnlyTheContactLine() {
        val id = create(input(contact = ahmet))
        cal.updateEvent(id, EventPatch(description = "Bring the invoice\nCall: Ahmet Yılmaz | +90 532 123 45 67"))
        store.update(id, input(contact = null))
        assertEquals("Bring the invoice", cal.event(id)!!.description)
        assertNull(store.details(id)!!.contact)
    }

    @Test
    fun peopleFor_seriesOccurrence_andSnoozedException() {
        val id = create(input(contact = ahmet, recurrence = RecurrenceRule.Daily))
        val begin = cal.event(id)!!.dtStart
        assertEquals(AlarmPeople(ahmet, null), store.peopleFor(InstanceKey(id, begin)))
        store.snooze(InstanceKey(id, begin), clock.millis())
        val exception = cal.events(CAL).single { it.originalId == id }
        assertEquals(AlarmPeople(ahmet, null), store.peopleFor(InstanceKey(exception.id, exception.dtStart)))
    }

    @Test
    fun peopleFor_calendarUnavailable_isNull() {
        val id = create(input(contact = ahmet))
        available = false
        assertNull(store.peopleFor(InstanceKey(id, cal.event(id)!!.dtStart)))
    }

    @Test
    fun peopleFor_nobodyAttached_isNull() {
        val id = create(input())
        assertNull(store.peopleFor(InstanceKey(id, cal.event(id)!!.dtStart)))
    }

    @Test
    fun rename_oneOff_changesTitle_keepsContact() {
        val id = create(input(contact = ahmet))
        assertEquals("Call Ahmet about invoice", store.rename(InstanceKey(id, cal.event(id)!!.dtStart), "  Call Ahmet about invoice "))
        assertEquals("Call Ahmet about invoice", cal.event(id)!!.title)
        assertEquals(ahmet, store.details(id)!!.contact)
    }

    @Test
    fun rename_blank_fallsBackToDefaultTitle() {
        val id = create(input())
        assertEquals(DEFAULT_TITLE, store.rename(InstanceKey(id, cal.event(id)!!.dtStart), "   "))
        assertEquals(DEFAULT_TITLE, cal.event(id)!!.title)
    }

    @Test
    fun rename_snoozedOccurrence_renamesExceptionAndSeries() {
        val id = create(input(recurrence = RecurrenceRule.Daily))
        val begin = cal.event(id)!!.dtStart
        store.snooze(InstanceKey(id, begin), clock.millis())
        val exception = cal.events(CAL).single { it.originalId == id }
        store.rename(InstanceKey(exception.id, exception.dtStart), "New text")
        assertEquals("New text", cal.event(exception.id)!!.title)
        assertEquals("New text", cal.event(id)!!.title)
    }

    @Test
    fun rename_deletedAlarm_isNull() =
        assertNull(store.rename(InstanceKey(999, 0), "x"))
}
