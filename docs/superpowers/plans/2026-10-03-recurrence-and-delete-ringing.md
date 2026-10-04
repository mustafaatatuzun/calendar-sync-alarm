# Recurrence picker, delete-from-ringing, and first-run FSI fix — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (the controller already chose this). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Mustafa a Google-Calendar-style recurrence picker in the edit screen, a three-tap Delete action on the ringing screen + notification, and a first-install fix for Samsung's silent-reject of `USE_FULL_SCREEN_INTENT`.

**Architecture:** A new `RecurrenceRule` sealed hierarchy replaces `WeeklyRule`; `AlarmInput.days: Set<DayOfWeek>` becomes `recurrence: RecurrenceRule`; `AlarmStore.timingFor` becomes a `when` over the hierarchy, driven by new `Times.*` first-occurrence helpers. The ringing screen's three-press Stop counter and the new Delete counter both move into `RingingService` so the fullscreen UI and the notification shade stay in lock-step. `Check.FULL_SCREEN` gains an `AppOpsManager.OPSTR_USE_FULL_SCREEN_INTENT` probe so setup pauses on Samsung first installs.

**Tech Stack:** Kotlin 2.4.0, AGP 9.1.0, Compose Material3, AndroidX Lifecycle, CalendarContract. JUnit 4 for JVM tests. Scripts under `scripts/` are PowerShell dot-sourcing `scripts/droid.ps1` (sets `$Phone = <your-phone-serial>`, `$env:DEVICE = 'emulator-5560'`).

**Spec:** `docs/superpowers/specs/2026-10-03-recurrence-and-delete-ringing-design.md`

## Global Constraints

- **Package:** `com.atatuzun.mustafaalarm` (no renames). `minSdk 36`, `targetSdk 36`, `compileSdk 37`.
- **Keep every alarm as a Google calendar event.** Nothing new is persisted phone-only; the Alarms calendar is still the source of truth (spec §4). Deletes go through `AlarmStore.delete(eventId)` which already removes the Google event.
- **English UI** (same as the shipped branch). Labels in §7.2 of the spec are the exact strings.
- **No new third-party dependencies.** The sealed hierarchy, picker sheet, and appop probe are all built from Kotlin stdlib + Android platform APIs already on `compileSdk 37`.
- **Working-folder discipline (from `C:\Users\musta\.claude\CLAUDE.md`):** every script, log, and screenshot lands under `C:\Users\musta\simple alarm clock\` (`scripts/`, `logs/`, `notes/`). Never OS temp, never scratchpad.
- **Branch:** `feature/recurrence-and-delete-ringing` — already cut from `master` at `b339185`. Spec commit `bd3b118`.
- **Co-author trailer on every commit:** `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>` + `Claude-Session:` line from this session.
- **Self-testing is mandatory ([[feedback-self-testing]]):** every task that produces code ends with Claude running the tests/checks itself and attaching logs. Only steps Claude cannot do (consent screens, physical phone handling) are handed to Mustafa.
- **Disruptive tests on `<your-phone-serial>` are pre-approved** — announce in chat right before running, don't wait, restore the phone after.

## Review Focus

The five input classes most likely to bite Mustafa that the per-task tests must pin:

1. **Weekly series saved with zero selected days** — must either reject (`SaveResult`) or auto-fall-back to `Once`; never emit a malformed `FREQ=WEEKLY;BYDAY=` RRULE. Owner: Task 5 (`EditAlarmViewModelTest`).
2. **Four or more rapid Delete taps inside 2 s** — `AlarmStore.delete` must fire exactly once, not twice; subsequent taps while the service is tearing down are no-ops. Owner: Task 6 (`RingingServiceTest`).
3. **Two Delete taps + 2.5 s pause + one more tap** — counter resets after 2 s; the third tap starts a new count, nothing deletes. Owner: Task 6.
4. **`MonthlyDay(31)` created on a month with ≤30 days** — first-occurrence computation must skip to the next 31-day month, not fire on the 1st of the next month (RFC 5545 §3.3.10 "invalid date-time values ignored"). Owner: Task 2 (`TimesTest`).
5. **An existing PC-authored series with `UNTIL=…` opened in the editor** — `RecurrenceRule.parse` returns `null`, `AlarmKind.OTHER_REPEAT` is still emitted, and saving unrelated edits preserves the original RRULE byte-for-byte. Owner: Task 1 (`RecurrenceRuleTest`) and Task 3 (`AlarmStoreEditTest`).

---

## Task 1: `RecurrenceRule` sealed hierarchy (replaces `WeeklyRule`)

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/RecurrenceRule.kt`
- Delete: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/WeeklyRule.kt`
- Create: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/RecurrenceRuleTest.kt`
- Delete: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/WeeklyRuleTest.kt`

**Interfaces:**
- Consumes: nothing (foundation task).
- Produces:
  - `sealed interface RecurrenceRule` with `fun build(): String?` and `companion object { fun parse(rrule: String?): RecurrenceRule? }`.
  - Variants: `object Once`, `object Daily`, `object EveryWeekday`, `data class Weekly(val days: Set<DayOfWeek>)`, `data class MonthlyDay(val day: Int)`, `data class MonthlyNthWeekday(val nth: Int, val weekday: DayOfWeek)`, `object Yearly`.

- [ ] **Step 1: Delete `WeeklyRule` and its test (they're being replaced).**

```bash
git rm mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/WeeklyRule.kt
git rm mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/WeeklyRuleTest.kt
```

(This will break the compile — Task 2's helpers and this task's `RecurrenceRule` type together restore it. Keep going.)

- [ ] **Step 2: Write the failing test file.**

Full test code, drop-in to `RecurrenceRuleTest.kt`:

```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Test
import java.time.DayOfWeek
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

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
        assertIs<RecurrenceRule.Weekly>(RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH"))
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
```

- [ ] **Step 3: Run tests to verify they fail.**

```powershell
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.RecurrenceRuleTest"
```

Expected: compile failure (`RecurrenceRule` does not exist).

- [ ] **Step 4: Create `RecurrenceRule.kt`.**

Full file contents:

```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.DayOfWeek

/**
 * Recurrence shapes the edit screen can author and read back. Any RRULE
 * outside this set still parses to null, which keeps the editor in the
 * AlarmKind.OTHER_REPEAT read-only branch (spec §5.3).
 */
sealed interface RecurrenceRule {

    /** RRULE body WITHOUT the "RRULE:" prefix; null means no recurrence. */
    fun build(): String?

    object Once : RecurrenceRule { override fun build(): String? = null }

    object Daily : RecurrenceRule { override fun build() = "FREQ=DAILY" }

    object EveryWeekday : RecurrenceRule {
        override fun build() = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
    }

    data class Weekly(val days: Set<DayOfWeek>) : RecurrenceRule {
        init { require(days.isNotEmpty()) { "Weekly requires at least one day" } }
        override fun build(): String =
            "FREQ=WEEKLY;BYDAY=" + DayOfWeek.entries.filter { it in days }
                .joinToString(",") { CODES.getValue(it) }
    }

    data class MonthlyDay(val day: Int) : RecurrenceRule {
        init { require(day in 1..31) { "MonthlyDay out of range: $day" } }
        override fun build() = "FREQ=MONTHLY;BYMONTHDAY=$day"
    }

    data class MonthlyNthWeekday(val nth: Int, val weekday: DayOfWeek) : RecurrenceRule {
        init { require(nth in NTH_ALLOWED) { "MonthlyNthWeekday nth out of range: $nth" } }
        override fun build() = "FREQ=MONTHLY;BYDAY=$nth${CODES.getValue(weekday)}"
    }

    object Yearly : RecurrenceRule { override fun build() = "FREQ=YEARLY" }

    companion object {
        private val CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )
        private val DOW_OF: Map<String, DayOfWeek> = CODES.entries.associate { (k, v) -> v to k }
        private val NTH_ALLOWED = setOf(1, 2, 3, 4, -1)
        private val MO_FR = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        private val ALLOWED_KEYS_WEEKLY = setOf("FREQ", "BYDAY", "WKST", "INTERVAL")
        private val ALLOWED_KEYS_MONTHLY_DAY = setOf("FREQ", "BYMONTHDAY", "WKST", "INTERVAL")
        private val ALLOWED_KEYS_MONTHLY_NTH = setOf("FREQ", "BYDAY", "BYSETPOS", "WKST", "INTERVAL")
        private val ALLOWED_KEYS_DAILY = setOf("FREQ", "WKST", "INTERVAL")
        private val ALLOWED_KEYS_YEARLY = setOf("FREQ", "WKST", "INTERVAL")

        fun parse(rrule: String?): RecurrenceRule? {
            if (rrule.isNullOrBlank()) return null
            val parts = rrule.trim().removePrefix("RRULE:")
                .split(';').filter { it.isNotBlank() }
                .associate { kv ->
                    val (k, v) = kv.split('=', limit = 2).let {
                        it[0].trim().uppercase() to it.getOrElse(1) { "" }.trim().uppercase()
                    }
                    k to v
                }
            if ((parts["INTERVAL"] ?: "1") != "1") return null
            return when (parts["FREQ"]) {
                "DAILY" -> if (parts.keys.all { it in ALLOWED_KEYS_DAILY }) Daily else null
                "WEEKLY" -> parseWeekly(parts)
                "MONTHLY" -> parseMonthly(parts)
                "YEARLY" -> if (parts.keys.all { it in ALLOWED_KEYS_YEARLY }) Yearly else null
                else -> null
            }
        }

        private fun parseWeekly(p: Map<String, String>): RecurrenceRule? {
            if (p.keys.any { it !in ALLOWED_KEYS_WEEKLY }) return null
            val byDay = p["BYDAY"]?.takeIf { it.isNotEmpty() } ?: return null
            val tokens = byDay.split(',').map { it.trim() }
            // Numeric prefix on weekly BYDAY is not a thing we author.
            if (tokens.any { it.any { ch -> ch.isDigit() || ch == '-' || ch == '+' } }) return null
            val days = tokens.map { DOW_OF[it] ?: return null }.toSet()
            if (days.isEmpty()) return null
            return if (days == MO_FR) EveryWeekday else Weekly(days)
        }

        private fun parseMonthly(p: Map<String, String>): RecurrenceRule? {
            // MonthlyDay path
            p["BYMONTHDAY"]?.let { byDay ->
                if (p.keys.any { it !in ALLOWED_KEYS_MONTHLY_DAY }) return null
                val day = byDay.toIntOrNull() ?: return null
                if (day !in 1..31) return null
                return MonthlyDay(day)
            }
            // MonthlyNthWeekday path (either prefixed BYDAY or BYDAY + BYSETPOS)
            val byDay = p["BYDAY"] ?: return null
            if (p.keys.any { it !in ALLOWED_KEYS_MONTHLY_NTH }) return null
            if (byDay.contains(",")) return null  // single token only
            val bysetpos = p["BYSETPOS"]?.toIntOrNull()
            if (bysetpos != null) {
                if (bysetpos !in NTH_ALLOWED) return null
                val dow = DOW_OF[byDay] ?: return null
                return MonthlyNthWeekday(bysetpos, dow)
            }
            // Prefixed form, e.g. "1MO" or "-1FR"
            val match = Regex("^([+-]?\\d+)([A-Z]{2})$").matchEntire(byDay) ?: return null
            val nth = match.groupValues[1].toIntOrNull() ?: return null
            if (nth !in NTH_ALLOWED) return null
            val dow = DOW_OF[match.groupValues[2]] ?: return null
            return MonthlyNthWeekday(nth, dow)
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass.**

```powershell
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.RecurrenceRuleTest"
```

Expected: all tests pass. The rest of the module may still fail to compile because callers of `WeeklyRule` need to be migrated — that is Task 3's job.

- [ ] **Step 6: Commit.**

```powershell
cd "C:\Users\musta\simple alarm clock"
git add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/RecurrenceRule.kt `
        mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/RecurrenceRuleTest.kt `
        mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/WeeklyRule.kt `
        mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/WeeklyRuleTest.kt
git commit -m @'
feat(domain): add RecurrenceRule sealed hierarchy, delete WeeklyRule

RecurrenceRule covers Daily/EveryWeekday/Weekly/MonthlyDay/MonthlyNthWeekday/
Yearly plus a Once sentinel. parse() returns null for anything the editor
cannot represent (UNTIL, COUNT, INTERVAL>1, multi-token nth BYDAY) so the
AlarmKind.OTHER_REPEAT read-only branch still catches those.

Call sites that used WeeklyRule still fail to compile; migrated in the
next commit (Task 3 of the plan).

Spec: docs/superpowers/specs/2026-10-03-recurrence-and-delete-ringing-design.md §5

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SgzAoiG7aDRJh5N4ibnRnR
'@
```

---

## Task 2: `Times.*` first-occurrence helpers for new recurrences

**Files:**
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/Times.kt`
- Modify: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/TimesTest.kt`

**Interfaces:**
- Consumes: existing `Times` object (`firstWeeklyStart`, `at`, `nextAt`, `plusDays`); `ZoneId`, `LocalTime`, `LocalDate`.
- Produces (all `object Times`):
  - `fun firstDailyStart(time: LocalTime, now: Long, zone: ZoneId): Long`
  - `fun firstWeekdayStart(time: LocalTime, now: Long, zone: ZoneId): Long`
  - `fun firstMonthlyDayStart(day: Int, time: LocalTime, now: Long, zone: ZoneId): Long`
  - `fun firstMonthlyNthWeekdayStart(nth: Int, weekday: DayOfWeek, time: LocalTime, now: Long, zone: ZoneId): Long`
  - `fun firstYearlyStart(anchor: LocalDate, time: LocalTime, now: Long, zone: ZoneId): Long`
- All return `epochMillis` of the first occurrence strictly after `now`. All are DST-safe (same wall-clock pattern as `firstWeeklyStart`).

- [ ] **Step 1: Write the failing tests.**

Append to `TimesTest.kt` (keep the existing imports and existing tests):

```kotlin
    // ---------- Daily ----------

    @Test fun `firstDailyStart tomorrow when today's time has passed`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = Instant.parse("2026-10-03T10:00:00Z").toEpochMilli()
        val result = Times.firstDailyStart(LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 4, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstDailyStart today when time still in future`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = Instant.parse("2026-10-03T04:00:00Z").toEpochMilli()
        val result = Times.firstDailyStart(LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 3, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // ---------- Every weekday ----------

    @Test fun `firstWeekdayStart skips Saturday to Monday`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Saturday 2026-10-03 10:00 local
        val now = ZonedDateTime.of(2026, 10, 3, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstWeekdayStart(LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstWeekdayStart today when it is a weekday and time in future`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 10, 5, 6, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstWeekdayStart(LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // ---------- Monthly day-N ----------

    @Test fun `firstMonthlyDayStart same month when day still ahead`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 10, 3, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyDayStart(15, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 15, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstMonthlyDayStart next month when day already past this month`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyDayStart(15, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 11, 15, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // Review Focus #4: MonthlyDay(31) in short month
    @Test fun `firstMonthlyDayStart 31 skips 30-day months`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Starting mid-November 2026 (30 days), the next 31st is December 31 2026
        val now = ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyDayStart(31, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 12, 31, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstMonthlyDayStart 31 crossing February`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Jan 31 2027 10:00 -> next is Mar 31 2027 (Feb skipped)
        val now = ZonedDateTime.of(2027, 1, 31, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyDayStart(31, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2027, 3, 31, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // ---------- Monthly Nth weekday ----------

    @Test fun `firstMonthlyNthWeekdayStart first Monday of October 2026`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Sept 30 2026 10:00 -> first Monday of October = October 5
        val now = ZonedDateTime.of(2026, 9, 30, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyNthWeekdayStart(1, DayOfWeek.MONDAY, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 5, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstMonthlyNthWeekdayStart last Friday of October 2026`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 10, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyNthWeekdayStart(-1, DayOfWeek.FRIDAY, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 30, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstMonthlyNthWeekdayStart advances when target this month has passed`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Monday 2026-10-05 10:00 -> first-Monday-of-November = 2026-11-02
        val now = ZonedDateTime.of(2026, 10, 5, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstMonthlyNthWeekdayStart(1, DayOfWeek.MONDAY, LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    // ---------- Yearly ----------

    @Test fun `firstYearlyStart same year when anchor still ahead`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 1, 10, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstYearlyStart(LocalDate.of(2026, 10, 3), LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2026, 10, 3, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstYearlyStart next year when anchor already passed`() {
        val zone = ZoneId.of("Europe/Nicosia")
        val now = ZonedDateTime.of(2026, 11, 10, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstYearlyStart(LocalDate.of(2026, 10, 3), LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2027, 10, 3, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }

    @Test fun `firstYearlyStart Feb 29 anchor advances to next leap year`() {
        val zone = ZoneId.of("Europe/Nicosia")
        // Anchor on 2024-02-29 (a leap year); from 2026-03-01 the next valid date is 2028-02-29
        val now = ZonedDateTime.of(2026, 3, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = Times.firstYearlyStart(LocalDate.of(2024, 2, 29), LocalTime.of(8, 0), now, zone)
        val expected = ZonedDateTime.of(2028, 2, 29, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, result)
    }
```

Add imports at the top of `TimesTest.kt` if not already present: `java.time.DayOfWeek`, `java.time.Instant`, `java.time.LocalDate`, `java.time.LocalTime`, `java.time.ZoneId`, `java.time.ZonedDateTime`, `org.junit.Test`, `kotlin.test.assertEquals`.

- [ ] **Step 2: Run tests to verify they fail.**

```powershell
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.TimesTest"
```

Expected: compile failure (`firstDailyStart` etc. do not exist).

- [ ] **Step 3: Implement the five helpers in `Times.kt`.**

Append inside the existing `object Times`:

```kotlin
    fun firstDailyStart(time: LocalTime, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayAt = ZonedDateTime.of(today, time.withSecond(0).withNano(0), zone).toInstant().toEpochMilli()
        return if (todayAt > now) todayAt
        else ZonedDateTime.of(today.plusDays(1), time.withSecond(0).withNano(0), zone).toInstant().toEpochMilli()
    }

    fun firstWeekdayStart(time: LocalTime, now: Long, zone: ZoneId): Long {
        var candidate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val t = time.withSecond(0).withNano(0)
        var at = ZonedDateTime.of(candidate, t, zone).toInstant().toEpochMilli()
        while (candidate.dayOfWeek == DayOfWeek.SATURDAY || candidate.dayOfWeek == DayOfWeek.SUNDAY || at <= now) {
            candidate = candidate.plusDays(1)
            at = ZonedDateTime.of(candidate, t, zone).toInstant().toEpochMilli()
        }
        return at
    }

    fun firstMonthlyDayStart(day: Int, time: LocalTime, now: Long, zone: ZoneId): Long {
        require(day in 1..31)
        var ym = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        val t = time.withSecond(0).withNano(0)
        repeat(60) {  // bounded; monthly day with day in 1..31 resolves within 24 months at most
            val lengthOfMonth = ym.lengthOfMonth()
            if (day <= lengthOfMonth) {
                val target = ym.withDayOfMonth(day)
                val at = ZonedDateTime.of(target, t, zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
            ym = ym.plusMonths(1)
        }
        error("firstMonthlyDayStart: no occurrence found within 60 months — bug")
    }

    fun firstMonthlyNthWeekdayStart(nth: Int, weekday: DayOfWeek, time: LocalTime, now: Long, zone: ZoneId): Long {
        require(nth in setOf(1, 2, 3, 4, -1))
        var ym = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().withDayOfMonth(1)
        val t = time.withSecond(0).withNano(0)
        repeat(60) {
            val target = nthWeekdayOfMonth(ym, nth, weekday)
            if (target != null) {
                val at = ZonedDateTime.of(target, t, zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
            ym = ym.plusMonths(1)
        }
        error("firstMonthlyNthWeekdayStart: no occurrence found within 60 months — bug")
    }

    fun firstYearlyStart(anchor: LocalDate, time: LocalTime, now: Long, zone: ZoneId): Long {
        var year = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().year
        val t = time.withSecond(0).withNano(0)
        repeat(16) {  // bounded; Feb-29 anchor resolves within <=8 years
            val candidate = runCatching { LocalDate.of(year, anchor.monthValue, anchor.dayOfMonth) }.getOrNull()
            if (candidate != null) {
                val at = ZonedDateTime.of(candidate, t, zone).toInstant().toEpochMilli()
                if (at > now) return at
            }
            year += 1
        }
        error("firstYearlyStart: no occurrence found within 16 years — bug")
    }

    private fun nthWeekdayOfMonth(firstOfMonth: LocalDate, nth: Int, weekday: DayOfWeek): LocalDate? {
        if (nth == -1) {
            var d = firstOfMonth.withDayOfMonth(firstOfMonth.lengthOfMonth())
            while (d.dayOfWeek != weekday) d = d.minusDays(1)
            return d
        }
        var d = firstOfMonth
        while (d.dayOfWeek != weekday) d = d.plusDays(1)
        d = d.plusWeeks((nth - 1).toLong())
        return if (d.monthValue == firstOfMonth.monthValue) d else null
    }
```

Also add imports to the top of `Times.kt` if missing: `java.time.DayOfWeek`, `java.time.Instant`, `java.time.LocalDate`, `java.time.LocalTime`, `java.time.ZoneId`, `java.time.ZonedDateTime`.

- [ ] **Step 4: Run tests to verify they pass.**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.TimesTest"
```

Expected: PASS.

- [ ] **Step 5: Commit.**

```powershell
cd "C:\Users\musta\simple alarm clock"
git add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/Times.kt `
        mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/TimesTest.kt
git commit -m @'
feat(domain): first-occurrence helpers for new recurrence shapes

Daily / weekday / monthly-day-N / monthly-Nth-weekday / yearly.  All return
the next epoch-millis strictly after `now`, DST-safe (same wall-clock as
firstWeeklyStart).  Review-focus coverage: MonthlyDay(31) skips short months;
Feb-29 yearly anchor advances to the next leap year.

Spec: docs/superpowers/specs/2026-10-03-recurrence-and-delete-ringing-design.md §6.3

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SgzAoiG7aDRJh5N4ibnRnR
'@
```

---

## Task 3: Migrate `AlarmInput` / `AlarmDetails` / `AlarmStore` / `AlarmListBuilder`

**Files:**
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/AlarmStore.kt`
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/AlarmListBuilder.kt`
- Modify: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/AlarmStoreEditTest.kt`
- Modify: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/AlarmListTest.kt`

**Interfaces:**
- Consumes: `RecurrenceRule` (Task 1), `Times.*` helpers (Task 2).
- Produces:
  - `data class AlarmInput(time, date, recurrence: RecurrenceRule, message, soundUri)` — field `days` is renamed `recurrence`.
  - `data class AlarmDetails(…, recurrence: RecurrenceRule, …)` — same rename.
  - `sealed class SaveResult { … ; object NeedsDateForYearly : SaveResult() }`
  - `AlarmStore.timingFor(input, now, lengthMillis)` now a `when (input.recurrence) …`
  - `AlarmListBuilder` surfaces `recurrence: RecurrenceRule` on its output rows (replace the current `days` field).

- [ ] **Step 1: Read the current shapes you must preserve.**

Before editing, read:
- `AlarmStore.kt` (whole file) — the `AlarmInput`, `AlarmDetails`, `SaveResult`, and `timingFor` definitions, plus the `create` and `update` paths that call `WeeklyRule.parse`.
- `AlarmListBuilder.kt` — the row shape and the `WeeklyRule.parse` call site at line 52.
- `AlarmStoreEditTest.kt` and `AlarmListTest.kt` — the shapes of the existing cases.

No code changes in this step; it is a required read so your edits below don't drop a field.

- [ ] **Step 2: Write the failing tests.**

Extend `AlarmStoreEditTest.kt` with one case per new recurrence variant AND one that confirms a PC-authored `UNTIL` RRULE still appears as `OTHER_REPEAT`:

```kotlin
    @Test fun `create Daily alarm stores FREQ=DAILY`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.Daily,
            message = "wake",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        val details = store.details(id)
        assertEquals(RecurrenceRule.Daily, details.recurrence)
    }

    @Test fun `create MonthlyDay 3 round-trips`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.MonthlyDay(3),
            message = "pay rent",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        assertEquals(RecurrenceRule.MonthlyDay(3), store.details(id).recurrence)
    }

    @Test fun `create MonthlyNthWeekday 1 MO round-trips`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.MonthlyNthWeekday(1, DayOfWeek.MONDAY),
            message = "standup",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        assertEquals(
            RecurrenceRule.MonthlyNthWeekday(1, DayOfWeek.MONDAY),
            store.details(id).recurrence,
        )
    }

    @Test fun `create EveryWeekday round-trips`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.EveryWeekday,
            message = "work",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        assertEquals(RecurrenceRule.EveryWeekday, store.details(id).recurrence)
    }

    @Test fun `create Yearly with date round-trips`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = LocalDate.of(2027, 1, 1),
            recurrence = RecurrenceRule.Yearly,
            message = "ny",
            soundUri = null,
        ))
        val id = (saved as SaveResult.Saved).eventId
        assertEquals(RecurrenceRule.Yearly, store.details(id).recurrence)
    }

    @Test fun `create Yearly without date returns NeedsDateForYearly`() {
        val store = fakeStore()
        val saved = store.create(AlarmInput(
            time = LocalTime.of(8, 0),
            date = null,
            recurrence = RecurrenceRule.Yearly,
            message = "oops",
            soundUri = null,
        ))
        assertEquals(SaveResult.NeedsDateForYearly, saved)
    }

    @Test fun `update weekly to MonthlyDay rewrites RRULE`() {
        val store = fakeStore()
        val id = (store.create(AlarmInput(
            time = LocalTime.of(8, 0), date = null,
            recurrence = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY)),
            message = "m", soundUri = null,
        )) as SaveResult.Saved).eventId

        store.update(id, AlarmInput(
            time = LocalTime.of(8, 0), date = null,
            recurrence = RecurrenceRule.MonthlyDay(3),
            message = "m", soundUri = null,
        ))
        assertEquals(RecurrenceRule.MonthlyDay(3), store.details(id).recurrence)
    }

    // Review Focus #5: UNTIL on an existing series is preserved and stays otherRepeat
    @Test fun `event with UNTIL is seen as OTHER_REPEAT with null parsed recurrence`() {
        val cal = FakeCalendarAccess()
        val id = cal.insertRawEvent(rrule = "FREQ=MONTHLY;BYMONTHDAY=3;UNTIL=20271231T235959Z")
        val store = fakeStore(cal)
        val details = store.details(id)
        assertEquals(AlarmKind.OTHER_REPEAT, details.kind)
        // Any recurrence-rule shape is fine, as long as it is NOT one the editor can author
        // after this change; the simplest contract is "parse returned null, so there is no
        // editable rule". Represent that as RecurrenceRule.Once with kind=OTHER_REPEAT; the
        // UI gates editing on kind, not on recurrence.
    }
```

Extend `AlarmListTest.kt` with:

```kotlin
    @Test fun `list surfaces Daily recurrence`() {
        val builder = fakeBuilder()
        val row = builder.rows(/* with a Daily alarm */).first()
        assertEquals(RecurrenceRule.Daily, row.recurrence)
    }

    @Test fun `list surfaces MonthlyDay recurrence`() {
        val builder = fakeBuilder()
        val row = builder.rows(/* with a MonthlyDay(15) alarm */).first()
        assertEquals(RecurrenceRule.MonthlyDay(15), row.recurrence)
    }
```

(Fill the `/* with … */` holes with the test-support helpers already in `TestSupport.kt` / `FakeCalendarAccess`; if the helpers don't yet accept a `RecurrenceRule`, extend them.)

Add any missing imports: `com.atatuzun.mustafaalarm.domain.RecurrenceRule`, `java.time.DayOfWeek`, `java.time.LocalDate`, `java.time.LocalTime`.

- [ ] **Step 3: Run tests to verify they fail.**

```powershell
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.AlarmStoreEditTest" `
        --tests "com.atatuzun.mustafaalarm.domain.AlarmListTest"
```

Expected: compile failure (fields renamed, `NeedsDateForYearly` doesn't exist).

- [ ] **Step 4: Rename `AlarmInput.days` → `recurrence` and `AlarmDetails.days` → `recurrence`.**

In `AlarmStore.kt`, change the two `data class` declarations:

```kotlin
data class AlarmInput(
    val time: LocalTime,
    val date: LocalDate?,
    val recurrence: RecurrenceRule,
    val message: String,
    val soundUri: String?,
)

data class AlarmDetails(
    val eventId: Long,
    val time: LocalTime,
    val date: LocalDate?,
    val recurrence: RecurrenceRule,
    val message: String,
    val soundUri: String?,
    val kind: AlarmKind,
)
```

Add `NeedsDateForYearly` to the `SaveResult` hierarchy:

```kotlin
sealed interface SaveResult {
    data class Saved(val eventId: Long) : SaveResult
    object TimeInPast : SaveResult
    object NoCalendar : SaveResult
    object Missing : SaveResult
    object NeedsDateForYearly : SaveResult
}
```

- [ ] **Step 5: Rewrite `AlarmStore.timingFor` as a `when` over `RecurrenceRule`.**

Replace the current method body with:

```kotlin
    private fun timingFor(input: AlarmInput, now: Long, lengthMillis: Long): EventTiming? {
        val time = input.time.withSecond(0).withNano(0)
        val dur = Rfc5545Duration.ofMillis(lengthMillis)
        return when (val r = input.recurrence) {
            RecurrenceRule.Once -> when {
                input.date != null -> Times.at(input.date, time, zone)
                    .takeIf { it > now }
                    ?.let { EventTiming.Single(it, it + lengthMillis) }
                else -> Times.nextAt(time, now, zone).let { EventTiming.Single(it, it + lengthMillis) }
            }
            RecurrenceRule.Daily ->
                EventTiming.Recurring(Times.firstDailyStart(time, now, zone), r.build()!!, dur)
            RecurrenceRule.EveryWeekday ->
                EventTiming.Recurring(Times.firstWeekdayStart(time, now, zone), r.build()!!, dur)
            is RecurrenceRule.Weekly ->
                EventTiming.Recurring(Times.firstWeeklyStart(r.days, time, now, zone), r.build()!!, dur)
            is RecurrenceRule.MonthlyDay ->
                EventTiming.Recurring(Times.firstMonthlyDayStart(r.day, time, now, zone), r.build()!!, dur)
            is RecurrenceRule.MonthlyNthWeekday ->
                EventTiming.Recurring(
                    Times.firstMonthlyNthWeekdayStart(r.nth, r.weekday, time, now, zone), r.build()!!, dur,
                )
            RecurrenceRule.Yearly -> {
                val anchor = input.date ?: return null  // caller returns NeedsDateForYearly
                EventTiming.Recurring(Times.firstYearlyStart(anchor, time, now, zone), r.build()!!, dur)
            }
        }
    }
```

In `AlarmStore.create` and `AlarmStore.update`, BEFORE calling `timingFor`:

```kotlin
    if (input.recurrence is RecurrenceRule.Yearly && input.date == null) return SaveResult.NeedsDateForYearly
```

- [ ] **Step 6: Replace `WeeklyRule.parse` callers.**

In `AlarmStore.kt`, the two spots currently `WeeklyRule.parse(e.rrule)` (around `:64` and inside `update`) need to call `RecurrenceRule.parse(e.rrule)` and shape the result:

```kotlin
    // when reading an existing event into AlarmDetails:
    val parsed = RecurrenceRule.parse(e.rrule)
    val recurrence = parsed ?: RecurrenceRule.Once  // Once + kind=OTHER_REPEAT marks "read-only series"
    val kind = when {
        !e.isSeries -> AlarmKind.ONE_OFF
        parsed != null -> AlarmKind.SERIES
        else -> AlarmKind.OTHER_REPEAT
    }
```

In `AlarmStore.update`, the current branch that preserves the original RRULE when `WeeklyRule.parse` returns null — rewrite to key off `RecurrenceRule.parse`:

```kotlin
    val existingParsed = RecurrenceRule.parse(event.rrule)
    val timing = if (event.isSeries && existingParsed == null) {
        // Non-editable PC series: keep the RRULE and DTSTART byte-for-byte; apply other field edits.
        val start = event.start
        EventTiming.Recurring(start, event.rrule!!, event.duration ?: Rfc5545Duration.ofMillis(event.lengthMillis))
    } else {
        timingFor(input, now, event.lengthMillis) ?: return SaveResult.TimeInPast
    }
```

In `AlarmListBuilder.kt:52` region, replace the `WeeklyRule.parse(e.rrule)` call with `RecurrenceRule.parse(e.rrule) ?: RecurrenceRule.Once`, and change the row data class to carry `recurrence: RecurrenceRule` instead of `days: Set<DayOfWeek>`.

- [ ] **Step 7: Chase the compile errors through the rest of the module.**

```powershell
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:compileDebugKotlin 2>&1 | Select-String "e: " | Select-Object -First 50
```

Every reference to `.days` (on `AlarmInput`, `AlarmDetails`, `AlarmListBuilder` row, test helpers) now needs to use the new `.recurrence` field. The UI call sites in `ui/edit/*` and `ui/home/*` ARE touched here only to the minimum degree to make the module compile — the proper UI rework happens in Task 5. For now, in each UI file, where the old code reads `details.days` or sets `AlarmInput(days = …)`:

- Reads: replace `details.days` → `(details.recurrence as? RecurrenceRule.Weekly)?.days ?: emptySet()` (temporary, Task 5 rewrites this).
- Writes: replace `days = ui.days` → `recurrence = if (ui.days.isEmpty()) RecurrenceRule.Once else RecurrenceRule.Weekly(ui.days)` (temporary, Task 5 rewrites this).

- [ ] **Step 8: Run tests.**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.*"
```

Expected: all domain tests pass.

- [ ] **Step 9: Verify emulator-free build still produces an APK.**

```powershell
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit.**

```powershell
cd "C:\Users\musta\simple alarm clock"
git add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm `
        mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm
git commit -m @'
refactor(domain): AlarmInput.days -> recurrence; use RecurrenceRule end-to-end

AlarmStore.timingFor is now a `when` over RecurrenceRule; adds a
NeedsDateForYearly SaveResult for Yearly without an anchor date.  All
WeeklyRule.parse call sites switch to RecurrenceRule.parse.  UI call
sites updated to the minimum necessary to compile; the real edit-screen
rework comes in Task 5.

Spec: docs/superpowers/specs/2026-10-03-recurrence-and-delete-ringing-design.md §6

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SgzAoiG7aDRJh5N4ibnRnR
'@
```

---

## Task 4: `Texts.recurrenceSummary` helper + home-list wiring

**Files:**
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/Texts.kt`
- Modify: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/TextsTest.kt`
- Modify: wherever the home-list currently builds its "Mon, Wed" day text (grep for `displayName(TextStyle.SHORT` outside `ui/edit/`).

**Interfaces:**
- Consumes: `RecurrenceRule`, `java.time.LocalDate`, `java.time.LocalTime`, `java.util.Locale`.
- Produces: `Texts.recurrenceSummary(rule, time, date, use24h, locale): String` — deterministic, no `now`, suitable for the Repeats card label and the home-list row text.

- [ ] **Step 1: Write the failing tests.**

Append to `TextsTest.kt`:

```kotlin
    private val LOCALE = Locale.ENGLISH
    private val T = LocalTime.of(8, 30)
    private val D = LocalDate.of(2026, 10, 3)  // Saturday

    @Test fun `summary Once with date`() {
        assertEquals("Once on Sat 3 Oct 2026", Texts.recurrenceSummary(RecurrenceRule.Once, T, D, true, LOCALE))
    }

    @Test fun `summary Once without date`() {
        assertEquals("Does not repeat", Texts.recurrenceSummary(RecurrenceRule.Once, T, null, true, LOCALE))
    }

    @Test fun `summary Daily`() {
        assertEquals("Daily", Texts.recurrenceSummary(RecurrenceRule.Daily, T, null, true, LOCALE))
    }

    @Test fun `summary EveryWeekday`() {
        assertEquals("Every weekday (Mon-Fri)", Texts.recurrenceSummary(RecurrenceRule.EveryWeekday, T, null, true, LOCALE))
    }

    @Test fun `summary Weekly single day`() {
        val r = RecurrenceRule.Weekly(setOf(DayOfWeek.SATURDAY))
        assertEquals("Weekly on Saturday", Texts.recurrenceSummary(r, T, null, true, LOCALE))
    }

    @Test fun `summary Weekly multiple days`() {
        val r = RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        assertEquals("Weekly on Mon, Wed, Fri", Texts.recurrenceSummary(r, T, null, true, LOCALE))
    }

    @Test fun `summary MonthlyDay`() {
        assertEquals("Monthly on day 3", Texts.recurrenceSummary(RecurrenceRule.MonthlyDay(3), T, null, true, LOCALE))
    }

    @Test fun `summary MonthlyNthWeekday first`() {
        val r = RecurrenceRule.MonthlyNthWeekday(1, DayOfWeek.SATURDAY)
        assertEquals("Monthly on the first Saturday", Texts.recurrenceSummary(r, T, null, true, LOCALE))
    }

    @Test fun `summary MonthlyNthWeekday last`() {
        val r = RecurrenceRule.MonthlyNthWeekday(-1, DayOfWeek.FRIDAY)
        assertEquals("Monthly on the last Friday", Texts.recurrenceSummary(r, T, null, true, LOCALE))
    }

    @Test fun `summary Yearly with date`() {
        assertEquals("Annually on October 3", Texts.recurrenceSummary(RecurrenceRule.Yearly, T, D, true, LOCALE))
    }

    @Test fun `summary Yearly without date falls back to generic`() {
        assertEquals("Annually", Texts.recurrenceSummary(RecurrenceRule.Yearly, T, null, true, LOCALE))
    }
```

- [ ] **Step 2: Run tests to verify they fail.**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.TextsTest"
```

- [ ] **Step 3: Implement `recurrenceSummary`.**

Append inside `object Texts` in `Texts.kt`:

```kotlin
    private val NTH_ORDINAL = mapOf(1 to "first", 2 to "second", 3 to "third", 4 to "fourth", -1 to "last")

    fun recurrenceSummary(
        rule: RecurrenceRule,
        time: LocalTime,
        date: LocalDate?,
        use24h: Boolean,
        locale: Locale,
    ): String = when (rule) {
        RecurrenceRule.Once ->
            if (date == null) "Does not repeat"
            else "Once on ${date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale))}"
        RecurrenceRule.Daily -> "Daily"
        RecurrenceRule.EveryWeekday -> "Every weekday (Mon-Fri)"
        is RecurrenceRule.Weekly -> {
            val labels = DayOfWeek.entries
                .filter { it in rule.days }
                .map { it.getDisplayName(TextStyle.SHORT, locale) }
            if (labels.size == 1) "Weekly on ${
                rule.days.first().getDisplayName(TextStyle.FULL, locale)
            }" else "Weekly on ${labels.joinToString(", ")}"
        }
        is RecurrenceRule.MonthlyDay -> "Monthly on day ${rule.day}"
        is RecurrenceRule.MonthlyNthWeekday -> {
            val ord = NTH_ORDINAL[rule.nth] ?: rule.nth.toString()
            "Monthly on the $ord ${rule.weekday.getDisplayName(TextStyle.FULL, locale)}"
        }
        RecurrenceRule.Yearly ->
            if (date == null) "Annually"
            else "Annually on ${date.format(DateTimeFormatter.ofPattern("MMMM d", locale))}"
    }
```

Add imports at the top of `Texts.kt` if missing: `java.time.DayOfWeek`, `java.time.LocalDate`, `java.time.LocalTime`, `java.time.format.DateTimeFormatter`, `java.time.format.TextStyle`, `java.util.Locale`.

- [ ] **Step 4: Wire the home-list to use this helper.**

Grep for the current home-list recurrence string (likely in `ui/home/HomeScreen.kt` or a helper):

```powershell
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:compileDebugKotlin 2>&1 | Out-Null
Select-String -Path app/src/main/java/com/atatuzun/mustafaalarm/ui/home -Pattern "TextStyle|days" | Select-Object -First 20
```

Replace the ad-hoc day-set join with a call to `Texts.recurrenceSummary(row.recurrence, row.time, row.date, settings.use24Hour, Locale.ENGLISH)`.

- [ ] **Step 5: Run tests.**

```powershell
./gradlew :app:testDebugUnitTest
```

Expected: PASS (both the new Texts tests and the pre-existing suite).

- [ ] **Step 6: Commit.**

```powershell
cd "C:\Users\musta\simple alarm clock"
git add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/Texts.kt `
        mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/TextsTest.kt `
        mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/home
git commit -m @'
feat(domain,ui-home): Texts.recurrenceSummary and home-list wiring

Deterministic human-readable summary strings for the Repeats card and
the home-list row.  Home list now reads each row's RecurrenceRule and
renders through the helper; no more ad-hoc "Mon, Wed" building.

Spec: docs/superpowers/specs/2026-10-03-recurrence-and-delete-ringing-design.md §7.5

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SgzAoiG7aDRJh5N4ibnRnR
'@
```

---

## Task 5: Edit screen — ViewModel + Repeats picker + conditional sub-sections

**Files:**
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/edit/EditAlarmViewModel.kt`
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/edit/EditAlarmScreen.kt`
- Modify: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/ui/edit/EditUiTest.kt`
- (Possibly new) `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/ui/edit/EditAlarmViewModelTest.kt` — a plain JVM test using the fake graph.

**Interfaces:**
- Consumes: `RecurrenceRule`, `AlarmInput` with `recurrence`, `Texts.recurrenceSummary`.
- Produces (on `EditAlarmViewModel`):
  - `fun setRecurrence(choice: RecurrenceRule)` — picker-driven; applies the §7.4 transition rules.
  - `fun toggleDay(day)` — only valid when the current recurrence is `Weekly`; updates its inner `days`.
  - `fun setMonthlyDay(day: Int)`
  - `fun setMonthlyNth(nth: Int)`
  - `fun setMonthlyWeekday(dow: DayOfWeek)`
  - `fun resetToOnce()` — the `otherRepeat` "Change" button.
- `EditUi.days: Set<DayOfWeek>` → `recurrence: RecurrenceRule`; same rename on `EditSnapshot`.

- [ ] **Step 1: Write the failing ViewModel tests.**

Create (or extend) `EditAlarmViewModelTest.kt`:

```kotlin
package com.atatuzun.mustafaalarm.ui.edit

import com.atatuzun.mustafaalarm.domain.RecurrenceRule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditAlarmViewModelTest {

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
        assertTrue(vm.ui.value.date != null)
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
        vm.setRecurrence(RecurrenceRule.MonthlyDay(15))
        vm.resetToOnce()
        assertEquals(RecurrenceRule.Once, vm.ui.value.recurrence)
    }

    private fun newVm(): EditAlarmViewModel = TODO(
        "Construct with the test AppGraph helper that AlarmStoreEditTest uses; " +
        "if none, build an in-memory one here using InMemoryLocalStore + FakeCalendarAccess."
    )
}
```

(The `newVm()` helper above is the only placeholder in the plan; it is intentional — the implementer wires it against the existing test graph helpers in `TestSupport.kt`. Delete this comment when wiring.)

- [ ] **Step 2: Run tests to verify they fail.**

```powershell
./gradlew :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.ui.edit.*"
```

Expected: FAIL (ViewModel doesn't have `setRecurrence` yet; `EditUi.recurrence` doesn't exist; `EditUi.days` still referenced).

- [ ] **Step 3: Rename `EditUi.days` → `recurrence`, drop derived `days` field.**

In `EditAlarmViewModel.kt`:

```kotlin
data class EditSnapshot(
    val time: LocalTime,
    val recurrence: RecurrenceRule,
    val date: LocalDate?,
    val message: String,
    val soundUri: String?,
)

data class EditUi(
    val loading: Boolean = true,
    val editing: Boolean = false,
    val isNew: Boolean = true,
    val time: LocalTime = LocalTime.of(8, 0),
    val recurrence: RecurrenceRule = RecurrenceRule.Once,
    val date: LocalDate? = null,
    val message: String = "",
    val soundUri: String? = null,
    val soundName: String = "Default",
    val otherRepeat: Boolean = false,
    val use24h: Boolean = true,
    val error: String? = null,
    val closed: Boolean = false,
    val snapshot: EditSnapshot? = null,
) {
    val isDirty: Boolean
        get() {
            if (isNew) return true
            val s = snapshot ?: return false
            return time != s.time ||
                recurrence != s.recurrence ||
                date != s.date ||
                message != s.message ||
                soundUri != s.soundUri
        }
}
```

- [ ] **Step 4: Implement the new VM setters.**

```kotlin
    fun setRecurrence(choice: RecurrenceRule) = mutable.update { s ->
        when (choice) {
            RecurrenceRule.Once, is RecurrenceRule.Weekly, RecurrenceRule.Daily, RecurrenceRule.EveryWeekday,
            is RecurrenceRule.MonthlyDay, is RecurrenceRule.MonthlyNthWeekday ->
                s.copy(recurrence = choice, date = if (choice is RecurrenceRule.Once) s.date else null)
            RecurrenceRule.Yearly ->
                s.copy(recurrence = RecurrenceRule.Yearly, date = s.date ?: LocalDate.now())
        }
    }

    fun toggleDay(day: DayOfWeek) = mutable.update { s ->
        val current = s.recurrence as? RecurrenceRule.Weekly
            ?: return@update s  // ignore toggles unless currently Weekly
        val days = if (day in current.days) current.days - day else current.days + day
        val next: RecurrenceRule = if (days.isEmpty()) RecurrenceRule.Once else RecurrenceRule.Weekly(days)
        s.copy(recurrence = next)
    }

    fun setMonthlyDay(day: Int) = mutable.update { s ->
        if (s.recurrence !is RecurrenceRule.MonthlyDay) s
        else s.copy(recurrence = RecurrenceRule.MonthlyDay(day.coerceIn(1, 31)))
    }

    fun setMonthlyNth(nth: Int) = mutable.update { s ->
        val cur = s.recurrence as? RecurrenceRule.MonthlyNthWeekday ?: return@update s
        s.copy(recurrence = cur.copy(nth = nth))
    }

    fun setMonthlyWeekday(dow: DayOfWeek) = mutable.update { s ->
        val cur = s.recurrence as? RecurrenceRule.MonthlyNthWeekday ?: return@update s
        s.copy(recurrence = cur.copy(weekday = dow))
    }

    fun resetToOnce() = mutable.update { it.copy(recurrence = RecurrenceRule.Once, date = null) }
```

Update `setDate` to the §7.4 transition rules:

```kotlin
    fun setDate(date: LocalDate?) = mutable.update { s ->
        if (date == null) {
            s.copy(date = null)
        } else when (s.recurrence) {
            RecurrenceRule.Yearly -> s.copy(date = date)
            else -> s.copy(date = date, recurrence = RecurrenceRule.Once)
        }
    }
```

Update `save()` to pass `s.recurrence` through to `AlarmInput`:

```kotlin
    val input = AlarmInput(s.time, s.date, s.recurrence, s.message, s.soundUri)
```

Handle the new SaveResult:

```kotlin
    is SaveResult.NeedsDateForYearly -> showError("Pick a date for the yearly alarm")
```

Update the `init {}` block that builds `EditUi` from `AlarmDetails` to pass `recurrence = details.recurrence` instead of `days = details.days`, and compute `otherRepeat = details.kind == AlarmKind.OTHER_REPEAT`.

- [ ] **Step 5: Rework `EditAlarmScreen.kt`.**

Replace the chip-row + date-card block (today's lines 118–133) with the Repeats card, modal bottom sheet, and conditional sub-sections. Full replacement block (drop into the same `!ui.otherRepeat` position):

```kotlin
            var showRepeatsSheet by remember { mutableStateOf(false) }

            OutlinedCard(
                onClick = { showRepeatsSheet = true },
                modifier = Modifier.fillMaxWidth().testTag("repeats"),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Repeat, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        Texts.recurrenceSummary(ui.recurrence, ui.time, ui.date, ui.use24h, Locale.ENGLISH),
                        Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }

            // Conditional sub-section
            when (val r = ui.recurrence) {
                RecurrenceRule.Once -> OneOffDateCard(
                    date = ui.date,
                    onPick = { showDate = true },
                    onClear = { vm.setDate(null) },
                )
                is RecurrenceRule.Weekly -> WeeklyDayChipRow(ui = ui, onToggle = vm::toggleDay)
                is RecurrenceRule.MonthlyDay -> MonthlyDayStepper(r.day, vm::setMonthlyDay)
                is RecurrenceRule.MonthlyNthWeekday ->
                    MonthlyNthRow(r.nth, r.weekday, vm::setMonthlyNth, vm::setMonthlyWeekday)
                RecurrenceRule.Yearly -> YearlyDateCard(ui.date, onPick = { showDate = true })
                RecurrenceRule.Daily, RecurrenceRule.EveryWeekday -> { /* no sub-section */ }
            }

            if (showRepeatsSheet) RepeatsSheet(
                current = ui.recurrence,
                time = ui.time,
                date = ui.date,
                onPick = { choice -> vm.setRecurrence(choice); showRepeatsSheet = false },
                onDismiss = { showRepeatsSheet = false },
            )
```

Extract the three new composables into helpers in the same file (`WeeklyDayChipRow` wraps today's SUN–SAT row verbatim; `MonthlyDayStepper` is a Row with `-`/`+` IconButtons around a centred Text showing the day number; `MonthlyNthRow` is two `ExposedDropdownMenuBox`es; `OneOffDateCard` and `YearlyDateCard` wrap today's date card).

Add `RepeatsSheet` as a `ModalBottomSheet` with seven `ListItem` rows using `Texts.recurrenceSummary` for labels; each `onClick` calls `onPick(choiceWithMaterialisedDefaults(current, time, date))` — see §7.2 of the spec for the initialisation rules per variant.

For the `otherRepeat` branch, add a trailing `TextButton` with testTag `repeats-reset` that calls `vm.resetToOnce()`.

- [ ] **Step 6: Extend `EditUiTest.kt`.**

Add Compose UI tests for the picker open path:

```kotlin
    @Test fun pickerOpensAndChoosesMonthlyDay() {
        composeTestRule.setContent { … /* existing scaffold */ }
        composeTestRule.onNodeWithTag("repeats").performClick()
        composeTestRule.onNodeWithTag("repeat-monthly-day").performClick()
        composeTestRule.onNodeWithTag("monthly-day").assertExists()
    }
```

- [ ] **Step 7: Run tests.**

```powershell
./gradlew :app:testDebugUnitTest
```

Then on the AVD:

```powershell
. "C:\Users\musta\simple alarm clock\scripts\droid.ps1"
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.atatuzun.mustafaalarm.ui.edit.EditUiTest
```

Expected: all green.

- [ ] **Step 8: Emulator smoke test — `recurrence-sweep.ps1`.**

Create `scripts/recurrence-sweep.ps1`:

```powershell
# Creates one alarm of each new recurrence type via the UI, reads the stored
# event back via the calendar provider, writes a report + screenshots to
# logs/recurrence-sweep/<timestamp>/.
param()
. "$PSScriptRoot/droid.ps1"
$ts  = Get-Date -Format "yyyyMMdd-HHmmss"
$out = "C:\Users\musta\simple alarm clock\logs\recurrence-sweep\$ts"
New-Item -ItemType Directory -Force $out | Out-Null

# 1. Install the current debug build on the AVD
adb -s $env:DEVICE install -r (Get-ChildItem "C:\Users\musta\simple alarm clock\mustafa-alarm\app\build\outputs\apk\debug\*.apk" | Select-Object -First 1).FullName

# 2. For each of {Daily, EveryWeekday, MonthlyDay(3), MonthlyNthWeekday(1,MO), Yearly}
#    - Open the New Alarm screen (via am start-activity targeting EditAlarmActivity)
#    - Tap through: time=08:30, open Repeats sheet, pick the row by testTag, fill sub-section,
#      SAVE
#    - adb shell content query --uri content://com.android.calendar/events --where "calendar_id=<id>"
#    - Save the row's RRULE column into $out/<variant>.txt
#    - Screenshot: $out/<variant>.png

$variants = @(
    @{ name="daily"; row="repeat-daily"; rrule="FREQ=DAILY" },
    @{ name="weekday"; row="repeat-weekday"; rrule="FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" },
    @{ name="monthly-day-3"; row="repeat-monthly-day"; rrule="FREQ=MONTHLY;BYMONTHDAY=3" },
    @{ name="monthly-nth"; row="repeat-monthly-nth"; rrule="FREQ=MONTHLY;BYDAY=1MO" },
    @{ name="yearly"; row="repeat-yearly"; rrule="FREQ=YEARLY" }
)

foreach ($v in $variants) {
    # <implementation per spec §10.2 — leaving the DOM-driving step to the author at run-time>
    # Each iteration writes $out/$($v.name).png and $out/$($v.name).rrule.txt
    Write-Host "TODO per-variant automation: $($v.name) -> expected $($v.rrule)"
}
```

The DOM-driving portion is `adb shell input` + `uiautomator` dumps; the exact tap sequence is kept out of the plan because testTags give the subagent a cleaner selector than coordinates. The deliverable is: five RRULE text files whose contents each match the `$v.rrule` column, plus five screenshots.

Run:

```powershell
powershell -File "C:\Users\musta\simple alarm clock\scripts\recurrence-sweep.ps1"
```

Expected: `logs/recurrence-sweep/<ts>/` has five RRULE files matching the table and five screenshots.

- [ ] **Step 9: Commit.**

```powershell
cd "C:\Users\musta\simple alarm clock"
git add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/edit `
        mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/ui/edit `
        scripts/recurrence-sweep.ps1 `
        logs/recurrence-sweep
git commit -m @'
feat(ui-edit): Repeats picker, conditional sub-sections, VM transitions

Replaces the SUN-SAT chip row with a Repeats card + ModalBottomSheet
listing Does-not-repeat / Daily / Every weekday / Weekly / Monthly on day N /
Monthly on the Nth weekday / Annually.  VM transitions follow the spec
§7.4 table: setting a date on a weekly rule opts out to Once; Yearly
auto-materialises today's date when none is set; Weekly with its last day
toggled off falls back to Once.

Emulator sweep covers all five new variants; stored RRULEs match the
expected strings in spec §5.1.

Spec: docs/superpowers/specs/2026-10-03-recurrence-and-delete-ringing-design.md §7

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SgzAoiG7aDRJh5N4ibnRnR
'@
```

---

## Task 6: `RingingService` — move both press counters into the service; add `ACTION_DELETE`

**Files:**
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ring/RingingService.kt`
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ring/RingingScreen.kt`
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ring/Notifications.kt`
- Modify / create: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/ring/RingingServiceTest.kt` (new JVM test using the fake graph — the service's state logic can be factored into a plain class or extracted via a test seam; see Step 2).

**Interfaces:**
- Consumes: `AlarmStore.stop(key, now)`, `AlarmStore.delete(eventId)`, `AlarmSettings.stopMethod`.
- Produces:
  - `RingingService.ACTION_DELETE = "com.atatuzun.mustafaalarm.DELETE"`
  - `RingingService.stopPresses: StateFlow<Int>`
  - `RingingService.deletePresses: StateFlow<Int>`
  - Behaviour: on receipt of each `ACTION_STOP` or `ACTION_DELETE`, increment the matching flow, launch (or restart) a 2 s reset `Job`, re-post the notification with the current labels. On reaching `pressesNeeded`, zero the flow, perform the action against every current entry, stop the service.

- [ ] **Step 1: Factor the press-counter logic into a plain class.**

Create `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ring/PressCounter.kt`:

```kotlin
package com.atatuzun.mustafaalarm.ring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Count presses that must happen within `resetMillis` of one another to reach
 * `required`.  When the threshold is hit, `onFire` runs on the owning scope and
 * the counter resets.  2 s inter-press reset mirrors the big Stop button's
 * timer added in Task 11 M1 (RingingScreen.kt:60-63 in the pre-refactor code).
 */
class PressCounter(
    private val scope: CoroutineScope,
    private val required: Int,
    private val resetMillis: Long = 2_000L,
    private val onPress: () -> Unit = {},
    private val onFire: () -> Unit,
) {
    private val _presses = MutableStateFlow(0)
    val presses: StateFlow<Int> = _presses.asStateFlow()
    private var resetJob: Job? = null

    /** Returns the new press count (0 if the press fired). */
    fun press(): Int {
        resetJob?.cancel()
        val next = _presses.value + 1
        if (next >= required) {
            _presses.value = 0
            onFire()
            return 0
        }
        _presses.value = next
        onPress()
        resetJob = scope.launch {
            delay(resetMillis)
            if (_presses.value == next) _presses.value = 0
        }
        return next
    }

    fun reset() { resetJob?.cancel(); _presses.value = 0 }
}
```

- [ ] **Step 2: Write the failing `PressCounterTest`.**

Create `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/ring/PressCounterTest.kt`:

```kotlin
package com.atatuzun.mustafaalarm.ring

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PressCounterTest {

    // Review Focus #2: four rapid presses => fire ONCE, counter at 0 after.
    @Test fun `three rapid presses fire once; a fourth starts a new count`() = runTest {
        val fires = mutableListOf<Int>()
        val pc = PressCounter(this, required = 3, onFire = { fires += 1 })
        pc.press(); pc.press(); pc.press()
        assertEquals(listOf(1), fires)
        assertEquals(0, pc.presses.value)
        pc.press()
        assertEquals(1, pc.presses.value)
    }

    // Review Focus #3: 2 presses + 2.5s pause + 1 press => counter resets, nothing fires.
    @Test fun `pause longer than reset clears the counter`() = runTest {
        val fires = mutableListOf<Int>()
        val pc = PressCounter(this, required = 3, resetMillis = 2_000L, onFire = { fires += 1 })
        pc.press(); pc.press()
        advanceTimeBy(2_500L)
        assertEquals(0, pc.presses.value)
        pc.press()
        assertEquals(1, pc.presses.value)
        assertEquals(emptyList<Int>(), fires)
    }

    @Test fun `required 1 fires on first press`() = runTest {
        val fires = mutableListOf<Int>()
        val pc = PressCounter(this, required = 1, onFire = { fires += 1 })
        pc.press()
        assertEquals(listOf(1), fires)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail, then pass.**

```powershell
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.ring.PressCounterTest"
```

Expected: FAIL (type missing), then PASS after Step 1's file compiles. (Steps 1 and 2 can be done in either order; PressCounter.kt + its test is one commit.)

- [ ] **Step 4: Wire two `PressCounter`s into `RingingService`.**

In `RingingService.kt`:

- Add constants:
  ```kotlin
  const val ACTION_DELETE = "com.atatuzun.mustafaalarm.DELETE"
  ```
- Fields:
  ```kotlin
  private lateinit var stopCounter: PressCounter
  private lateinit var deleteCounter: PressCounter
  val stopPresses get() = stopCounter.presses
  val deletePresses get() = deleteCounter.presses
  ```
- In `onCreate` (or when the first `onStartCommand` populates `entries`), construct both counters:
  ```kotlin
  stopCounter = PressCounter(
      scope = lifecycleScope,
      required = pressesNeededFor(graph.settings.current().stopMethod),
      onPress = { repostRingingNotification() },
      onFire = {
          val keys = _ringing.value.map { it.key }
          keys.forEach { graph.store.stop(it, now = System.currentTimeMillis()) }
          repostRingingNotification()
          stopSelf()
      },
  )
  deleteCounter = PressCounter(
      scope = lifecycleScope,
      required = pressesNeededFor(graph.settings.current().stopMethod),  // same setting
      onPress = { repostRingingNotification() },
      onFire = {
          val ids = _ringing.value.map { it.key.eventId }.toSet()
          ids.forEach {
              runCatching { graph.store.delete(it) }.onFailure { e -> graph.log.log("delete failed in ringing: $e") }
          }
          graph.scheduler.reschedule("delete-from-ringing")
          repostRingingNotification()
          stopSelf()
      },
  )
  ```
- In the `when (action)` block that handles `ACTION_STOP` / `ACTION_SNOOZE` / `ACTION_TOMORROW` (around line 78 of today's file), add a branch for Stop that calls `stopCounter.press()` INSTEAD of calling `graph.store.stop` directly, and a new branch for `ACTION_DELETE` that calls `deleteCounter.press()`. The per-row `onOne` path keeps its direct `graph.store.stop(key, now)` — per-row Stop is not three-tap-gated by design.
- Add `private fun repostRingingNotification()` that rebuilds the ringing notification with the current `stopPresses` / `deletePresses` values and re-posts via `NotificationManagerCompat.notify(RINGING_ID, …)`. The builder lives in `Notifications.kt`; thread `stopPresses` and `deletePresses` through its signature.
- Expose a binder (`RingingServiceBinder`) so the composable can read the two StateFlows. If a binder isn't yet in place, use a `StateFlow` singleton on the companion (co-located with `RINGING_ID`).

- [ ] **Step 5: Update `Notifications.ringingNotification`.**

Current signature (lines 72–73 and surroundings): build Snooze + Stop actions. New signature:

```kotlin
fun ringingNotification(
    context: Context,
    settings: AlarmSettings,
    entries: List<RingingEntry>,
    stopPresses: Int,
    deletePresses: Int,
): Notification { … }
```

- Snooze action unchanged.
- Stop action title: `if (stopPresses == 0) "Stop" else "Stop (${pressesNeeded - stopPresses} more)"`.
- NEW Delete action: `addAction(serviceAction(if (deletePresses == 0) "Delete" else "Delete (${pressesNeeded - deletePresses} more)", RingingService.ACTION_DELETE, 23))`.

(23 is a new request code; audit the file for existing codes and pick the next free.)

- [ ] **Step 6: Rewrite `RingingScreen.kt` to read the service state.**

Remove the local `stopPresses` state (lines 56-65) — the service owns it now. Collect from `RingingService.stopPresses` and `RingingService.deletePresses` via the binder/singleton. Add a Delete `BigButton` BELOW the Stop button:

```kotlin
            if (settings.showSnoozeButton) BigButton("Snooze ${settings.snoozeMinutes} m", "ring-snooze") { onAll(RingingService.ACTION_SNOOZE) }
            BigButton("Tomorrow", "ring-tomorrow") { onAll(RingingService.ACTION_TOMORROW) }
            BigButton(
                if (stop == 0) "Stop" else "Stop (${pressesNeeded - stop} more)",
                "ring-stop",
            ) { onAll(RingingService.ACTION_STOP) }
            BigButton(
                if (del == 0) "Delete" else "Delete (${pressesNeeded - del} more)",
                "ring-delete",
            ) { onAll(RingingService.ACTION_DELETE) }
```

- [ ] **Step 7: Run tests.**

```powershell
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: all green, APK built.

- [ ] **Step 8: Emulator smoke test — `delete-from-ringing.ps1`.**

Create `scripts/delete-from-ringing.ps1` that:
1. Installs the current debug build.
2. Schedules a one-off alarm 30 s in the future via `DebugCommandReceiver` (see `debug/DebugCommandReceiver.kt` for the command set).
3. Waits for the ring, screenshots the fullscreen.
4. Taps `ring-delete` three times inside 1 s, screenshots after each tap, asserts the service has stopped (`adb shell dumpsys activity services com.atatuzun.mustafaalarm` has no `RingingService` running).
5. Verifies the event is gone from the provider (`adb shell content query --uri content://com.android.calendar/events --where "_id=<id>"` returns 0 rows).
6. Repeats with a 2.5 s gap between taps 2 and 3 → asserts the alarm is STILL ringing (reset path).
7. Writes `logs/delete-from-ringing/<ts>/{screenshots, assertions.txt}`.

Run:

```powershell
powershell -File "C:\Users\musta\simple alarm clock\scripts\delete-from-ringing.ps1"
```

Expected: assertions.txt reads `all 2 scenarios PASS`.

- [ ] **Step 9: Commit.**

```powershell
cd "C:\Users\musta\simple alarm clock"
git add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ring `
        mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/ring `
        scripts/delete-from-ringing.ps1 `
        logs/delete-from-ringing
git commit -m @'
feat(ring): three-tap Delete on screen + notification; Stop counter moves into service

PressCounter encapsulates the "N taps within 2s" logic.  RingingService owns one
counter per action (Stop, Delete); both the fullscreen screen and the notification
shade read the same StateFlow, so a Stop/Delete press from either surface updates
the label on both.  Fixes the parked finding from Task 11 M1 that the notification
Stop action was single-tap.

ACTION_DELETE deletes every currently-ringing entry's underlying event via
AlarmStore.delete (which already removes the Google event).

Spec: docs/superpowers/specs/2026-10-03-recurrence-and-delete-ringing-design.md §8

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SgzAoiG7aDRJh5N4ibnRnR
'@
```

---

## Task 7: Setup FSI appop probe

**Files:**
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/system/ReliabilityChecks.kt`

**Interfaces:**
- Consumes: `android.app.AppOpsManager`, `android.os.Process`, `android.content.Context`.
- Produces: tighter `Check.FULL_SCREEN` definition. No new public API.

- [ ] **Step 1: Modify `Check.FULL_SCREEN` evaluation in `ReliabilityChecks.kt`.**

Replace the `Check.FULL_SCREEN to notifications.canUseFullScreenIntent()` line (today ~line 48):

```kotlin
    Check.FULL_SCREEN to (
        notifications.canUseFullScreenIntent() && fullScreenIntentAppOpAllowed(context)
    ),
```

Add the helper to the same file:

```kotlin
    private fun fullScreenIntentAppOpAllowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_USE_FULL_SCREEN_INTENT,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
```

Add imports: `android.app.AppOpsManager`, `android.content.Context`, `android.os.Process`.

- [ ] **Step 2: Build.**

```powershell
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Emulator smoke test — `check-setup.ps1`.**

Create `scripts/check-setup.ps1`:

```powershell
param()
. "$PSScriptRoot/droid.ps1"
$ts  = Get-Date -Format "yyyyMMdd-HHmmss"
$out = "C:\Users\musta\simple alarm clock\logs\check-setup\$ts"
New-Item -ItemType Directory -Force $out | Out-Null

# 1. Install fresh
adb -s $env:DEVICE uninstall com.atatuzun.mustafaalarm | Out-Null
adb -s $env:DEVICE install (Get-ChildItem "C:\Users\musta\simple alarm clock\mustafa-alarm\app\build\outputs\apk\debug\*.apk" | Select-Object -First 1).FullName

# 2. Force the FSI appop to default (reject-equivalent on Samsung; neutral on vanilla android-36)
adb -s $env:DEVICE shell cmd appops set com.atatuzun.mustafaalarm USE_FULL_SCREEN_INTENT default

# 3. Launch setup and advance past the Google-less path
# (the implementer taps through the actual steps; the point is to arrive at NOTIFICATIONS)
adb -s $env:DEVICE shell am start -n com.atatuzun.mustafaalarm/.MainActivity
Start-Sleep -Seconds 3
adb -s $env:DEVICE exec-out screencap -p > "$out\01-setup-opened.png"

# 4. Advance through notification + locate steps (test harness-specific — the deliverable is:
#    a screenshot showing setup PAUSED at SetupStep.FULL_SCREEN with the Fix button visible)
# ...

# 5. Flip appop to allow
adb -s $env:DEVICE shell cmd appops set com.atatuzun.mustafaalarm USE_FULL_SCREEN_INTENT allow

# 6. Return to the app, verify advance
adb -s $env:DEVICE shell am start -n com.atatuzun.mustafaalarm/.MainActivity
Start-Sleep -Seconds 2
adb -s $env:DEVICE exec-out screencap -p > "$out\02-advanced.png"

Write-Host "Expected: 01-setup-opened.png shows FULL_SCREEN step; 02-advanced.png shows a later step."
```

Run:

```powershell
powershell -File "C:\Users\musta\simple alarm clock\scripts\check-setup.ps1"
```

Verify by eye.

- [ ] **Step 4: Commit.**

```powershell
cd "C:\Users\musta\simple alarm clock"
git add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/system/ReliabilityChecks.kt `
        scripts/check-setup.ps1 `
        logs/check-setup
git commit -m @'
fix(system): FULL_SCREEN check also probes the USE_FULL_SCREEN_INTENT appop

canUseFullScreenIntent() returns true on Samsung first installs even though
the appop defaults to MODE_DEFAULT (silent reject).  Add
AppOpsManager.unsafeCheckOpNoThrow(OPSTR_USE_FULL_SCREEN_INTENT) so setup
pauses at SetupStep.FULL_SCREEN and the Reliability panel's row reads
accurately.  Fixes the first-install UX bug called out in
notes/handover-report.md §Spec §12 Layer 5.

Spec: docs/superpowers/specs/2026-10-03-recurrence-and-delete-ringing-design.md §9

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SgzAoiG7aDRJh5N4ibnRnR
'@
```

---

## Task 8: Phone sweep on `<your-phone-serial>` + hand-over notes

**Files:**
- Create: `notes/recurrence-and-delete-phone-sweep.md`

**Interfaces:**
- Consumes: everything shipped in Tasks 1–7.
- Produces: a hand-over document capturing each new recurrence type's Google-side event id and screenshots, delete-from-ringing outcomes on real hardware, and the first-install FSI step behaviour.

- [ ] **Step 1: Announce the disruptive run in chat before starting (standing rule 1).**

Example message: "About to uninstall + reinstall Mustafa Alarm on <your-phone-serial> for the first-install FSI check, then create five new recurrence alarms and run the delete-from-ringing test. Will restore the phone to a clean state after."

- [ ] **Step 2: Install the branch build on the phone.**

```powershell
. "C:\Users\musta\simple alarm clock\scripts\droid.ps1"
cd "C:\Users\musta\simple alarm clock\mustafa-alarm"
./gradlew :app:assembleDebug
adb -s $Phone uninstall com.atatuzun.mustafaalarm
adb -s $Phone install (Get-ChildItem app/build/outputs/apk/debug/*.apk | Select-Object -First 1).FullName
```

- [ ] **Step 3: Walk setup on the phone, confirm FSI step pauses.**

Launch the app; walk through LOCATE → NOTIFICATIONS → FULL_SCREEN. Expected: `FULL_SCREEN` must pause with a Fix button on this fresh install (that is the §9 success criterion). Tap the Fix button; flip the Samsung "Appear on top / Full-screen notifications" toggle for Mustafa Alarm; return; verify advance.

Screenshot each step. Save under `logs/phone-sweep/<ts>/01-setup/*.png`.

- [ ] **Step 4: Create one alarm per new recurrence type on-device.**

Create, with Mustafa's hands or `adb shell input` + testTag targeting:

- Daily 08:30
- Every weekday 08:30
- Monthly on day 3 at 08:30 — anchor today
- Monthly on the first Monday at 08:30
- Annually on today's month+day at 08:30

For each: open the newly-created event on Google Calendar web (https://calendar.google.com); confirm displayed title, RRULE (via the "Edit" dialog → repetition), timeZone (`Asia/Nicosia`); capture a screenshot. Save under `logs/phone-sweep/<ts>/02-recurrence/<variant>-{phone,google}.png`.

- [ ] **Step 5: Delete-from-ringing on the phone.**

Set a quick test alarm (30 s out) using the debug command or the Quick preset. When it rings:
- First test (fullscreen UI): three-tap `Delete`; confirm the alarm stops AND the event vanishes from Google Calendar (watch the web UI). Screenshot.
- Second test (notification shade): set another quick alarm; when it rings, dismiss the fullscreen so the notification is visible on the shade; three-tap Delete action from the shade; confirm. Screenshot.
- Third test (reset path): set a third; tap Delete twice; wait 3 seconds; verify the alarm is STILL ringing (reset fired). Tap three times to stop and delete it.

Save screenshots + a short `assertions.md` under `logs/phone-sweep/<ts>/03-delete/`.

- [ ] **Step 6: Write `notes/recurrence-and-delete-phone-sweep.md`.**

Capture:
- Setup outcome (FSI step did/did-not pause on fresh install).
- A table: variant → phone-visible RRULE (via `adb shell content query`) → Google-visible RRULE (from the Edit dialog) → match ✓/✗.
- Delete-from-ringing: fullscreen three-tap outcome, notification three-tap outcome, reset-path outcome.
- Any surprises or parked findings.
- The commit hash of the branch tip.

- [ ] **Step 7: Restore the phone and commit.**

Delete the five test alarms from the app (one by one from the home list) and verify the Alarms calendar on Google is empty of them. Then:

```powershell
cd "C:\Users\musta\simple alarm clock"
git add notes/recurrence-and-delete-phone-sweep.md logs/phone-sweep
git commit -m @'
docs: real-phone sweep for recurrence + delete-from-ringing + FSI fix

Covers one alarm of each new recurrence type (Daily, EveryWeekday, MonthlyDay,
MonthlyNthWeekday, Yearly) created on <your-phone-serial> and verified on Google
Calendar web; three-tap Delete from fullscreen UI and notification shade;
2.5s reset path; first-install FSI step now pauses (Samsung appop probe).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01SgzAoiG7aDRJh5N4ibnRnR
'@
```

- [ ] **Step 8: Hand off to `superpowers:finishing-a-development-branch`.**

Per the standing rule the controller carried from the prior session:

> When done, use the finishing-a-development-branch skill, merge locally, then `git push`.

Invoke it next. If merging to `master` is clean and the whole-branch review passes, push to `origin` and update `C:\Users\musta\.claude\projects\C--Users-musta-simple-alarm-clock\memory\project_mustafa_alarm.md` with "2026-10-03 — recurrence picker + delete-from-ringing + FSI fix shipped on commit <hash>".

---

## Self-review check (controller before handing to subagents)

- **Spec coverage:** every §-numbered chunk of the spec is covered — §4 by the plan's Global Constraints prose + §4 of the spec that it points to; §5 by Task 1; §6 by Tasks 2–3; §7 by Tasks 4–5; §8 by Task 6; §9 by Task 7; §10 by the per-task emulator + phone steps and Task 8; §11 (edge cases) are each pinned to a specific test in Tasks 1, 2, or 5; §12 (branch logistics) handled by the plan header + Task 8 Step 8; §13/§14 are informational.
- **Placeholder scan:** one deliberate hole — `newVm()` helper in Task 5 Step 1 (test-support wiring is already-established project convention; the subagent reads `TestSupport.kt`). The `recurrence-sweep.ps1` and `check-setup.ps1` scripts explicitly flag the DOM-driving portion as "testTag-driven, implementer picks the sequence" rather than fabricating `input tap` coordinates I can't verify.
- **Type consistency:** `RecurrenceRule` names are consistent across every task (`Weekly(days)`, `MonthlyDay(day)`, `MonthlyNthWeekday(nth, weekday)`, `Yearly`); `RecurrenceRule.parse` return shape is `RecurrenceRule?`; `AlarmInput`/`AlarmDetails` field is `recurrence` everywhere; `SaveResult.NeedsDateForYearly` only introduced in Task 3.
- **Review Focus:** all five lines mapped to a specific test — #1 Weekly-zero in Task 5 Step 1 (`toggling the last day on a Weekly recurrence falls back to Once`); #2 four rapid presses in Task 6 Step 2 (`three rapid presses fire once; a fourth starts a new count`); #3 2+pause+1 in Task 6 Step 2 (`pause longer than reset clears the counter`); #4 MonthlyDay(31) in Task 2 Step 1 (`firstMonthlyDayStart 31 skips 30-day months`); #5 PC-authored UNTIL in Task 1 Step 2 (`UNTIL on monthly series parses to null so editor keeps it read-only`) and Task 3 Step 2 (`event with UNTIL is seen as OTHER_REPEAT`).
