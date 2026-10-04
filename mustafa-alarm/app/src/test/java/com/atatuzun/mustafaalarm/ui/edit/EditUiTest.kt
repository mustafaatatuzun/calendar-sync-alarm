package com.atatuzun.mustafaalarm.ui.edit

import com.atatuzun.mustafaalarm.domain.RecurrenceRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class EditUiTest {

    private val baseSnapshot = EditSnapshot(
        time = LocalTime.of(7, 30),
        recurrence = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
        date = null,
        message = "Edit check",
        soundUri = null,
    )

    private fun base() = EditUi(
        loading = false,
        editing = true,
        isNew = false,
        time = LocalTime.of(7, 30),
        recurrence = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
        date = null,
        message = "Edit check",
        soundUri = null,
        snapshot = baseSnapshot,
    )

    @Test fun `not dirty when nothing changed`() {
        assertFalse(base().isDirty)
    }

    @Test fun `dirty when time changes`() {
        assertTrue(base().copy(time = LocalTime.of(8, 0)).isDirty)
    }

    @Test fun `dirty when recurrence changes`() {
        assertTrue(base().copy(recurrence = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY))).isDirty)
    }

    @Test fun `dirty when date set`() {
        assertTrue(base().copy(date = LocalDate.of(2026, 10, 5)).isDirty)
    }

    @Test fun `dirty when message changes`() {
        assertTrue(base().copy(message = "Other").isDirty)
    }

    @Test fun `dirty when sound changes`() {
        assertTrue(base().copy(soundUri = "content://some/sound").isDirty)
    }

    @Test fun `not dirty when all fields reset to snapshot`() {
        val modified = base().copy(time = LocalTime.of(9, 0), message = "Changed")
        val reverted = modified.copy(time = LocalTime.of(7, 30), message = "Edit check")
        assertFalse(reverted.isDirty)
    }

    @Test fun `new alarm always dirty`() {
        val newAlarm = EditUi(loading = false, isNew = true, snapshot = null)
        assertTrue(newAlarm.isDirty)
    }

    @Test fun `loading returns not dirty`() {
        val loading = EditUi(loading = true, isNew = false, snapshot = null)
        assertFalse(loading.isDirty)
    }
}
