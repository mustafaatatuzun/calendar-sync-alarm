package com.atatuzun.mustafaalarm.ui.edit

import com.atatuzun.mustafaalarm.domain.AlarmContact
import com.atatuzun.mustafaalarm.domain.AlarmInput
import com.atatuzun.mustafaalarm.domain.AlarmStore
import com.atatuzun.mustafaalarm.domain.FakeCalendarAccess
import com.atatuzun.mustafaalarm.domain.FakeCalendarAccess.Companion.CAL
import com.atatuzun.mustafaalarm.domain.InMemoryLocalStore
import com.atatuzun.mustafaalarm.domain.RecurrenceRule
import com.atatuzun.mustafaalarm.domain.SaveResult
import com.atatuzun.mustafaalarm.domain.TestClock
import com.atatuzun.mustafaalarm.domain.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class EditAlarmViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    // Review Focus #1: Weekly with zero days must not reach save.
    @Test fun `setting Weekly with empty days is impossible via setRecurrence`() {
        val vm = newVm()
        // Attempt to construct Weekly(emptySet()) is blocked by the data class init {}.
        try {
            vm.setRecurrence(RecurrenceRule.Weekly(emptySet()))
            error("Weekly(emptySet()) must throw")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `toggling the last day on a Weekly recurrence falls back to Once`() {
        val vm = newVm()
        vm.setRecurrence(RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY)))
        vm.toggleDay(DayOfWeek.MONDAY)  // removes the only day
        assertEquals(RecurrenceRule.Once, vm.ui.value.recurrence)
    }

    @Test fun `picking Yearly materialises today's date when date is null`() {
        val vm = newVm()
        vm.setRecurrence(RecurrenceRule.Yearly)
        assertEquals(RecurrenceRule.Yearly, vm.ui.value.recurrence)
        assertNotNull(vm.ui.value.date)
    }

    @Test fun `picking Weekly clears a previously set date`() {
        val vm = newVm()
        vm.setDate(LocalDate.of(2026, 10, 10))
        vm.setRecurrence(RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY)))
        assertEquals(null, vm.ui.value.date)
    }

    @Test fun `setting a date on a Weekly recurrence opts out to Once`() {
        val vm = newVm()
        vm.setRecurrence(RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY)))
        vm.setDate(LocalDate.of(2026, 10, 10))
        assertEquals(RecurrenceRule.Once, vm.ui.value.recurrence)
        assertEquals(LocalDate.of(2026, 10, 10), vm.ui.value.date)
    }

    @Test fun `setting a date on a Yearly recurrence keeps Yearly`() {
        val vm = newVm()
        vm.setRecurrence(RecurrenceRule.Yearly)
        vm.setDate(LocalDate.of(2026, 10, 10))
        assertEquals(RecurrenceRule.Yearly, vm.ui.value.recurrence)
        assertEquals(LocalDate.of(2026, 10, 10), vm.ui.value.date)
    }

    @Test fun `resetToOnce clears recurrence and days and date`() {
        val vm = newVm()
        vm.setRecurrence(RecurrenceRule.Yearly)
        assertNotNull(vm.ui.value.date)   // sanity: yearly materialised a date
        vm.resetToOnce()
        assertEquals(RecurrenceRule.Once, vm.ui.value.recurrence)
        assertEquals(null, vm.ui.value.date)
    }

    @Test fun `editing loads the contact, and changing it is a saveable edit`() {
        val cal = FakeCalendarAccess()
        val store = storeOn(cal)
        val id = (store.create(AlarmInput(LocalTime.of(11, 0), null, RecurrenceRule.Once, "Call", null, ahmet)) as SaveResult.Saved).eventId
        val vm = newVm(store, id)
        assertEquals(ahmet, vm.ui.value.contact)
        assertFalse(vm.ui.value.isDirty)

        vm.setContact(null)
        assertTrue(vm.ui.value.isDirty)
        vm.save()
        assertNull(store.details(id)!!.contact)
    }

    @Test fun `a new alarm saves the picked contact`() {
        val cal = FakeCalendarAccess()
        val vm = newVm(storeOn(cal))
        vm.setContact(ahmet)
        vm.save()
        assertEquals("Call: Ahmet Yılmaz | +90 532 123 45 67", cal.events.values.single().description)
    }

    private val ahmet = AlarmContact("Ahmet Yılmaz", "+90 532 123 45 67")

    private fun storeOn(cal: FakeCalendarAccess) =
        AlarmStore(cal, InMemoryLocalStore(), TestClock(t("2026-10-03T09:00")), { CAL }, { 30 }, { true })

    private fun newVm(store: AlarmStore = storeOn(FakeCalendarAccess()), eventId: Long? = null): EditAlarmViewModel =
        EditAlarmViewModel(
            store = store,
            use24hFn = { false },
            rescheduleFn = {},
            logFn = {},
            soundNameFn = { _ -> "Default" },
            eventId = eventId,
            ioDispatcher = testDispatcher,
        )
}
