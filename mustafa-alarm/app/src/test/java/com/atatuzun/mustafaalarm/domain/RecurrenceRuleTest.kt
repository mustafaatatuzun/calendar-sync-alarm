package com.atatuzun.mustafaalarm.domain

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.DayOfWeek

class RecurrenceRuleTest {

    // ---------- build() ----------

    @Test fun `Once build returns null`() { assertNull(RecurrenceRule.Once.build()) }

    @Test fun `Daily build`() { assertEquals("FREQ=DAILY", RecurrenceRule.Daily.build()) }

    @Test fun `EveryWeekday build`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", RecurrenceRule.EveryWeekday.build())
    }

    @Test fun `Weekly build orders Mon through Sun`() {
        val r = RecurrenceRule.Weekly(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", r.build())
    }

    @Test fun `MonthlyDay build`() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=15", RecurrenceRule.MonthlyDay(15).build())
    }

    @Test fun `MonthlyNthWeekday first Monday`() {
        assertEquals("FREQ=MONTHLY;BYDAY=1MO", RecurrenceRule.MonthlyNthWeekday(1, DayOfWeek.MONDAY).build())
    }

    @Test fun `MonthlyNthWeekday last Friday`() {
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", RecurrenceRule.MonthlyNthWeekday(-1, DayOfWeek.FRIDAY).build())
    }

    @Test fun `Yearly build`() { assertEquals("FREQ=YEARLY", RecurrenceRule.Yearly.build()) }

    // ---------- parse(build()) roundtrip ----------

    @Test fun `roundtrip Daily`() {
        assertEquals(RecurrenceRule.Daily, RecurrenceRule.parse(RecurrenceRule.Daily.build()))
    }

    @Test fun `roundtrip EveryWeekday`() {
        assertEquals(RecurrenceRule.EveryWeekday, RecurrenceRule.parse(RecurrenceRule.EveryWeekday.build()))
    }

    @Test fun `roundtrip Weekly single day`() {
        val r = RecurrenceRule.Weekly(setOf(DayOfWeek.SATURDAY))
        assertEquals(r, RecurrenceRule.parse(r.build()))
    }

    @Test fun `roundtrip Weekly all seven days NOT collapsed to EveryWeekday`() {
        val r = RecurrenceRule.Weekly(DayOfWeek.entries.toSet())
        assertEquals(r, RecurrenceRule.parse(r.build()))
    }

    @Test fun `roundtrip MonthlyDay 1 through 31`() {
        for (d in 1..31) {
            val r = RecurrenceRule.MonthlyDay(d)
            assertEquals(r, RecurrenceRule.parse(r.build()))
        }
    }

    @Test fun `roundtrip MonthlyNthWeekday all nth values`() {
        for (nth in listOf(1, 2, 3, 4, -1)) for (dow in DayOfWeek.entries) {
            val r = RecurrenceRule.MonthlyNthWeekday(nth, dow)
            assertEquals(r, RecurrenceRule.parse(r.build()))
        }
    }

    @Test fun `roundtrip Yearly`() {
        assertEquals(RecurrenceRule.Yearly, RecurrenceRule.parse(RecurrenceRule.Yearly.build()))
    }

    // ---------- parse() tolerates RRULE prefix, whitespace, case ----------

    @Test fun `parse accepts RRULE prefix`() {
        assertEquals(RecurrenceRule.Daily, RecurrenceRule.parse("RRULE:FREQ=DAILY"))
    }

    @Test fun `parse is case insensitive`() {
        assertEquals(RecurrenceRule.Daily, RecurrenceRule.parse("freq=daily"))
    }

    @Test fun `parse tolerates WKST and INTERVAL=1`() {
        val r = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY))
        assertEquals(r, RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO;WKST=MO;INTERVAL=1"))
    }

    // ---------- parse() rejection cases (null -> otherRepeat fallback) ----------

    @Test fun `parse rejects UNTIL`() {
        assertNull(RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO;UNTIL=20270101T000000Z"))
    }

    @Test fun `parse rejects COUNT`() {
        assertNull(RecurrenceRule.parse("FREQ=DAILY;COUNT=10"))
    }

    @Test fun `parse rejects INTERVAL greater than 1`() {
        assertNull(RecurrenceRule.parse("FREQ=DAILY;INTERVAL=2"))
    }

    @Test fun `parse rejects HOURLY`() { assertNull(RecurrenceRule.parse("FREQ=HOURLY")) }

    @Test fun `parse rejects multi-token nth BYDAY`() {
        assertNull(RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=1MO,2TU"))
    }

    @Test fun `parse rejects MonthlyDay 32`() {
        assertNull(RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=32"))
    }

    @Test fun `parse rejects MonthlyDay 0`() {
        assertNull(RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=0"))
    }

    @Test fun `parse rejects Weekly with empty BYDAY`() {
        assertNull(RecurrenceRule.parse("FREQ=WEEKLY;BYDAY="))
    }

    @Test fun `parse rejects Weekly with numeric prefix on BYDAY`() {
        assertNull(RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=1MO"))
    }

    @Test fun `parse rejects MonthlyNthWeekday with nth 5`() {
        assertNull(RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=5MO"))
    }

    @Test fun `parse rejects MonthlyNthWeekday with nth minus 2`() {
        assertNull(RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=-2FR"))
    }

    // ---------- BYSETPOS dialect ----------

    @Test fun `parse accepts BYSETPOS dialect and normalises to BYDAY form`() {
        val r = RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1")
        assertEquals(RecurrenceRule.MonthlyNthWeekday(1, DayOfWeek.MONDAY), r)
    }

    @Test fun `parse accepts BYSETPOS minus 1`() {
        val r = RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-1")
        assertEquals(RecurrenceRule.MonthlyNthWeekday(-1, DayOfWeek.FRIDAY), r)
    }

    @Test fun `parse rejects BYSETPOS 5`() {
        assertNull(RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=5"))
    }

    // ---------- EveryWeekday vs Weekly MO-FR disambiguation ----------

    @Test fun `parse MO through FR returns EveryWeekday (exact set match)`() {
        assertEquals(
            RecurrenceRule.EveryWeekday,
            RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"),
        )
    }

    @Test fun `parse MO TU WE TH is Weekly not EveryWeekday`() {
        assertTrue(RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH") is RecurrenceRule.Weekly)
    }

    @Test fun `parse Yearly rejects when BYMONTH present`() {
        assertNull(RecurrenceRule.parse("FREQ=YEARLY;BYMONTH=10"))
    }

    // ---------- Review Focus #5: UNTIL opened in editor stays otherRepeat ----------

    @Test fun `UNTIL on monthly series parses to null so editor keeps it read-only`() {
        assertNull(RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=3;UNTIL=20271231T235959Z"))
    }

    @Test fun `parse null and blank are null`() {
        assertNull(RecurrenceRule.parse(null))
        assertNull(RecurrenceRule.parse(""))
        assertNull(RecurrenceRule.parse("   "))
    }
}
