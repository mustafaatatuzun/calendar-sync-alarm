# Recurrence picker, delete-from-ringing, and first-run full-screen fix

Date: 2026-10-03
Author: Mustafa, with Claude Code (Opus 4.7)
Branch: `feature/recurrence-and-delete-ringing` (from `master` after the Mustafa Alarm merge, HEAD b339185)
Preceded by: `docs/superpowers/specs/2026-10-02-mustafa-alarm-design.md` (ground-truth design); `notes/handover-report.md` (what shipped; which feature requests this spec addresses).

## 1. Motivation

Three concrete gaps surfaced during the real-phone sweep of 2026-10-03.

1. **Only weekly recurrence is editable in-app.** Mustafa quote: "I want to be able to create alarm repeated for a certain number day of every month and first monday. just like on google calendar." Non-weekly RRULEs created on PC Google Calendar already ring correctly — `RingPlanner` iterates whatever instances the calendar provider expands — but the editor can only build `FREQ=WEEKLY;BYDAY=…`. All other series are opened read-only via the `otherRepeat` branch at `EditAlarmScreen.kt:118-120`.
2. **Deleting a ringing alarm is a six-step chore.** Right now: tap Stop three times, back out, find the alarm in the list, tap Delete, confirm. Mustafa wants a Delete action on the ringing screen (and the ringing notification) with the same three-tap fat-finger guard as Stop.
3. **First-install full-screen alarms silently fail on Samsung.** On `<your-phone-serial>`, Samsung's `USE_FULL_SCREEN_INTENT` appop defaulted to `default` (= silently reject) even though `NotificationManager.canUseFullScreenIntent()` returned `true`, so the setup `FULL_SCREEN` step auto-advanced and the user got a heads-up popup instead of the full-screen ringing activity. Fixed on the test device by `cmd appops set … allow`; needs to be caught at setup time on every fresh install.

## 2. Non-goals

- No "Custom" recurrence builder dialog (freq + interval + end-type combinator). Google Calendar's own menu covers Mustafa's stated needs; a Custom path is 2–3× the surface area for a case Mustafa did not ask for.
- No RRULE `UNTIL` / `COUNT` end condition in the editor. Series are open-ended; ending a series is still a PC-side operation (and preserved read-only in the app).
- No notification redesign. The Delete action joins Snooze + Stop as a third action with the same three-tap counter behaviour.
- No change to how events-are-alarms is decided. Every event on the Alarms calendar is still an alarm; nothing tags individual events (see §5 for the data-model consequences).
- No history migration. Existing alarms (weekly series + one-offs) continue to work with no data move; the new `RecurrenceRule.parse` is a strict superset of today's `WeeklyRule.parse` for the shapes the editor already produces.

## 3. Decisions resolved in this spec

| # | Question | Decision | Reasoning |
|---|---|---|---|
| 1 | Which Google Calendar menu options? | Does not repeat / Daily / Every weekday / Weekly on selected days / Monthly on day N / Monthly on the Nth weekday / Yearly | Mustafa's quoted ask names "day of every month" and "first monday"; the remaining Google defaults are free (one `when` arm each). |
| 2 | End condition (UNTIL/COUNT) in-app? | No. | Weekly today has none; keeps the editor UI small; PC-set ends preserved via `otherRepeat`. |
| 3 | Delete-from-ringing scope | Big button + ringing-notification action; three taps with 2 s reset, counter owned by the service so UI + notification stay in sync. | Mustafa asked for both surfaces; the counter must be shared or the notification tap can't honour three-tap. |
| 4 | Move three-press Stop counter from the composable into `RingingService`? | Yes — same session. | Needed anyway for Delete; the existing notification Stop action is single-tap today, which is the exact fat-finger risk Mustafa cares about. |
| 5 | Fold first-run FSI prompt into this session? | Yes. | Small change; its absence is the first-install UX bug flagged in the handover. The step already exists in `SetupViewModel`; what's missing is a reliable *check*. |
| 6 | New `AlarmInput` shape? | Replace `days: Set<DayOfWeek>` with `recurrence: RecurrenceRule`. Keep `date: LocalDate?` as the one-off / series-anchor. | Lets the sealed hierarchy own all shape-specific state. One `when` in `AlarmStore.timingFor`. |

## 4. Background: how alarm identity works today

Clarifying this up front because it determined every scope decision above.

- **The Alarms calendar is the source of truth.** Setup locates (or creates) one `com.google` calendar on Mustafa's account whose displayName is exactly "Alarms", persists its `calendarId`. From then on `RingPlanner` queries `events(calendar_id = <id>)` with no further filtering. Being in the calendar IS being an alarm; there is no per-event tag, no "is this ours" flag.
- **Local state is personal preferences and in-flight bookkeeping only.** `AlarmSettings` (volume %, snooze minutes, 24h flag, stop method, fade-in curve), `LocalStore` (handled keys, ring cache, pending actions, auto-snooze counts), and the per-alarm sound-URI override. On uninstall these are lost; alarms are not. On reinstall, setup's `LOCATE` step re-finds the Alarms calendar, sync pulls the events down, and every alarm returns automatically.
- **Direct consequence for this spec:** when the user picks "Monthly on day 3" we write an event with `RRULE:FREQ=MONTHLY;BYMONTHDAY=3` into the Alarms calendar. The next sync pushes it to Google and from then on both the app and the PC see the same series. No new identity surface, no new table.

## 5. Domain: `RecurrenceRule`

New file `app/src/main/java/com/atatuzun/mustafaalarm/domain/RecurrenceRule.kt`. Sealed interface with seven variants.

```kotlin
sealed interface RecurrenceRule {
    fun build(): String?   // null for Once; otherwise an RRULE body without the "RRULE:" prefix

    object Once : RecurrenceRule
    object Daily : RecurrenceRule
    object EveryWeekday : RecurrenceRule
    data class Weekly(val days: Set<DayOfWeek>) : RecurrenceRule   // non-empty
    data class MonthlyDay(val day: Int) : RecurrenceRule           // 1..31
    data class MonthlyNthWeekday(val nth: Int, val weekday: DayOfWeek) : RecurrenceRule   // nth in {1,2,3,4,-1}
    object Yearly : RecurrenceRule

    companion object { fun parse(rrule: String?): RecurrenceRule? }
}
```

### 5.1 `build()` output per variant

| Variant | RRULE body |
|---|---|
| `Once` | `null` |
| `Daily` | `FREQ=DAILY` |
| `EveryWeekday` | `FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR` |
| `Weekly(days)` | `FREQ=WEEKLY;BYDAY=<codes joined by ","; days ordered Mon..Sun as today>` |
| `MonthlyDay(day)` | `FREQ=MONTHLY;BYMONTHDAY=<day>` |
| `MonthlyNthWeekday(nth, dow)` | `FREQ=MONTHLY;BYDAY=<nth><code>` (e.g. `BYDAY=1MO`, `BYDAY=-1FR`) |
| `Yearly` | `FREQ=YEARLY` |

### 5.2 `parse()` behaviour

Returns a `RecurrenceRule` only for shapes this editor can produce. Everything else returns `null`, which keeps the `otherRepeat` read-only fallback intact for user-authored PC rules.

- Accept `RRULE:` prefix or no prefix; split on `;`; uppercase-canonicalise keys and values.
- `FREQ=DAILY` with no other keys (except `INTERVAL=1`, `WKST=…`) → `Daily`.
- `FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR` (exact set, order-insensitive) → `EveryWeekday`.
- `FREQ=WEEKLY;BYDAY=<any other BYDAY>` with `INTERVAL=1` → `Weekly(days)`. `BYDAY` values may NOT have numeric prefixes; any prefix (e.g. `1MO`) rejects → `null`.
- `FREQ=MONTHLY;BYMONTHDAY=N` with `N in 1..31` → `MonthlyDay(N)`.
- `FREQ=MONTHLY;BYDAY=<prefix><CODE>` with `prefix ∈ {1, 2, 3, 4, -1}` (one BYDAY token only; a leading `+` on positive values is accepted) → `MonthlyNthWeekday(prefix, weekday)`. We also accept the `FREQ=MONTHLY;BYDAY=MO;BYSETPOS=N` dialect for the same semantic with `N ∈ {1, 2, 3, 4, -1}` — some ICS exporters emit it (Google Calendar's own UI emits `BYDAY=1MO`). `build()` always emits the `BYDAY=1MO` form. Positive prefixes 5, 6, … and negative 2, 3, 4 are rejected → `null`; they're not reachable from the picker and would surprise the user if we round-tripped them.
- `FREQ=YEARLY` with no `BYDAY`/`BYMONTH`/`BYMONTHDAY` → `Yearly`. (Yearly series anchor their month/day on the DTSTART; no extra by-rules needed.)
- Any `UNTIL`/`COUNT` → `null` (we don't own the ending in-app; stay read-only).
- Any `INTERVAL > 1` → `null`.
- Any unexpected key or malformed value → `null`.

### 5.3 Fate of `WeeklyRule`

`WeeklyRule` is replaced. Keeping both is pointless duplication. The migration:

- `domain/WeeklyRule.kt` is deleted.
- Call sites that used `WeeklyRule.parse(e.rrule)` → use `(RecurrenceRule.parse(e.rrule) as? RecurrenceRule.Weekly)?.days` where they only wanted the weekly day-set (today: `AlarmStore.kt:64`, `AlarmStore.kt:85`-adjacent, `AlarmListBuilder.kt:52`).
- `WeeklyRule.build(days)` → `RecurrenceRule.Weekly(days).build()!!`.
- `test/.../WeeklyRuleTest.kt` is deleted; its cases fold into `RecurrenceRuleTest` (every one of today's assertions survives as a Weekly case).

## 6. Data model: `AlarmInput` and friends

### 6.1 `AlarmInput` (file: `domain/AlarmStore.kt`)

```kotlin
data class AlarmInput(
    val time: LocalTime,
    val date: LocalDate?,                 // one-off anchor OR series start (first occurrence computed from this + rule)
    val recurrence: RecurrenceRule,       // was `days: Set<DayOfWeek>`
    val message: String,
    val soundUri: String?,
)
```

### 6.2 `AlarmDetails` (same file, read-model for the edit screen)

```kotlin
data class AlarmDetails(
    val eventId: Long,
    val time: LocalTime,
    val date: LocalDate?,
    val recurrence: RecurrenceRule,       // was `days: Set<DayOfWeek>`
    val message: String,
    val soundUri: String?,
    val kind: AlarmKind,                  // ONE_OFF | SERIES | OTHER_REPEAT, unchanged
)
```

`AlarmKind.OTHER_REPEAT` is still emitted when `RecurrenceRule.parse` returns `null` on an existing event; the editor still shows that series as read-only.

### 6.3 `AlarmStore.timingFor()`

Current: builds a `Times.firstWeeklyStart(input.days, …) + WeeklyRule.build(input.days)` for the weekly case and `EventTiming.Single(start, dtend)` for the one-off case.

New: a `when (val r = input.recurrence)` with one arm per variant.

| Variant | First-occurrence start | DURATION |
|---|---|---|
| `Once` | `Times.todayOrDate(input.date, input.time, zone, now)` — same as the current one-off path | `Rfc5545Duration.ofMillis(lengthMillis)` kept as DTEND for `Single` |
| `Weekly(days)` | `Times.firstWeeklyStart(days, time, now, zone)` (today's helper, unchanged) | `Rfc5545Duration.ofMillis(lengthMillis)` |
| `Daily` | `Times.firstDailyStart(time, now, zone)` — new helper, returns today's `time` if still future else tomorrow's | same |
| `EveryWeekday` | `Times.firstWeekdayStart(time, now, zone)` — new helper, Mon–Fri skipping weekends | same |
| `MonthlyDay(d)` | `Times.firstMonthlyDayStart(d, time, now, zone)` — new helper; months with no day N (e.g. Feb 30) are skipped per RFC 5545 | same |
| `MonthlyNthWeekday(n, w)` | `Times.firstMonthlyNthWeekdayStart(n, w, time, now, zone)` — new helper | same |
| `Yearly` | `Times.firstYearlyStart(input.date!!, time, now, zone)` — new helper; `date` is required for yearly to anchor month+day. Enforced by `SaveResult`. | same |

For every non-`Once` arm, the `rrule` field of `EventTiming.Recurring` is `input.recurrence.build()!!`. The series anchor (`date` for `Yearly`; `now`-based for everything else) is the DTSTART; the RRULE itself carries no DTSTART.

### 6.4 Save validation (`SaveResult`)

Add one variant: `SaveResult.NeedsDateForYearly` — fired when `recurrence = Yearly` and `date == null`. The UI already clears `date` when `days.isNotEmpty()`; this prevents the equivalent gap for yearly.

All other validation unchanged (TimeInPast, NoCalendar, Missing).

## 7. Edit screen UI

File: `app/src/main/java/com/atatuzun/mustafaalarm/ui/edit/EditAlarmScreen.kt`.

### 7.1 Layout change

Replace lines 118–133 (the `otherRepeat`-gated chip-row + date-card block). New layout, inside the same `!otherRepeat` branch:

```
┌─────────────────────────────────────────────┐
│ 🔁  Repeats                                 │
│    Weekly on Mon, Wed              ▾        │   ← OutlinedCard, testTag="repeats"
└─────────────────────────────────────────────┘

<conditional sub-section — see §7.3>
```

The `otherRepeat` branch keeps today's "Repeats as set in Google Calendar" line and adds a single `TextButton("Change")` with testTag `repeats-reset` that calls `vm.resetToOnce()` → sets `recurrence = Once`, `date = null`, `days = emptySet()`. One tap only; no confirm dialog (the whole screen already has an undo on back → the SAVE button is dirty-gated).

### 7.2 Picker sheet

Tapping the Repeats card opens a `ModalBottomSheet` (testTag `repeats-sheet`) with seven rows, each a `TextButton` + optional radio marker for current selection. Labels are computed from the alarm's current `time`/`date`:

| Row | testTag | Example label (date = 2026-10-03 Sat, time = 08:30) |
|---|---|---|
| `Once` | `repeat-once` | "Does not repeat" |
| `Daily` | `repeat-daily` | "Daily" |
| `EveryWeekday` | `repeat-weekday` | "Every weekday (Mon–Fri)" |
| `Weekly` | `repeat-weekly` | "Weekly on Saturday" |
| `MonthlyDay` | `repeat-monthly-day` | "Monthly on day 3" |
| `MonthlyNthWeekday` | `repeat-monthly-nth` | "Monthly on the first Saturday" |
| `Yearly` | `repeat-yearly` | "Annually on October 3" |

Picking a row dismisses the sheet and calls `vm.setRecurrence(choice)`. The VM materialises defaults from the alarm's state:

- `MonthlyDay` → initialises `day = date?.dayOfMonth ?: LocalDate.now().dayOfMonth`.
- `MonthlyNthWeekday` → initialises `(nth, weekday)` from the ISO calculation on `date ?: LocalDate.now()` (which week-of-month the date falls in; special-case the last-of-month → `nth = -1`).
- `Weekly` → if the current `recurrence` is already `Weekly`, keep its days; otherwise initialise with `{ date?.dayOfWeek ?: today.dayOfWeek }`.
- `Yearly` → if `date == null`, materialise `LocalDate.now()` so the yearly rule has an immediate month+day anchor and SAVE stays enabled; the Yearly date card below the picker (§7.3) lets the user change it before saving. `NeedsDateForYearly` is kept as a defensive save-time guard in case a programmatic path produces `Yearly + null`.

### 7.3 Conditional sub-sections

| Current `recurrence` | Sub-section shown |
|---|---|
| `Once` | Current date card (`Icons.Filled.CalendarMonth` + "Date: …" + clear/pick buttons). Unchanged from today. |
| `Daily` / `EveryWeekday` / `Yearly` | For `Yearly`: a date card limited to picking the month+day (year is informational). For the other two: nothing. |
| `Weekly` | Current SUN–SAT chip row. Unchanged from today. |
| `MonthlyDay` | A `Stepper` 1..31 with a down/up pair of `IconButton`s (`Remove` / `Add`) and the current number centred — matches the alarm-settings number-row aesthetic already in `SettingsScreen.kt`. testTag `monthly-day`. |
| `MonthlyNthWeekday` | Two `ExposedDropdownMenuBox`es side by side: nth (1st / 2nd / 3rd / 4th / Last) and weekday (Mon..Sun, localised). testTags `monthly-nth` and `monthly-weekday`. |

### 7.4 `EditAlarmViewModel` changes

- `EditUi` field `days: Set<DayOfWeek>` → `recurrence: RecurrenceRule`.
- `EditSnapshot` same swap, so `isDirty` compares the new value.
- Replace `fun toggleDay(day)` with:
  - `fun setRecurrence(choice: RecurrenceRule)` — handles the picker-driven changes above.
  - `fun toggleDay(day)` kept but only valid when `recurrence is Weekly`; updates the inner `Weekly(days)`.
  - `fun setMonthlyDay(day: Int)` / `fun setMonthlyNth(nth: Int)` / `fun setMonthlyWeekday(dow: DayOfWeek)` for the sub-section controls.
- `fun resetToOnce()` for the `otherRepeat` "Change" button.
- `setDate` now only clears `recurrence` when the current recurrence is weekly/weekday-based AND date is being set, mirroring today's "`days = emptySet()` when date set" invariant: a Weekly + explicit date is still invalid (same as today). For `Yearly`, setting a date is required and does NOT clear the recurrence. New rule table in the VM:

| Transition | `date` after | `recurrence` after |
|---|---|---|
| User sets date, current is `Once` | the date | `Once` |
| User sets date, current is `Weekly`/`Daily`/`EveryWeekday`/`MonthlyDay`/`MonthlyNthWeekday` | the date | `Once` (picking a date opts out of any series — matches today's "weekly loses to a date" behaviour) |
| User sets date, current is `Yearly` | the date | `Yearly` (yearly NEEDS a date; the picker is the month+day anchor) |
| User picks a Weekly/Daily/EveryWeekday/MonthlyDay/MonthlyNthWeekday recurrence | `null` | the choice |
| User picks a Yearly recurrence | kept, or `LocalDate.now()` if `null` | `Yearly` |

### 7.5 Human-readable summary (used in the Repeats card and in the home-list)

New `Texts.recurrenceSummary(rule: RecurrenceRule, time: LocalTime, date: LocalDate?, use24h: Boolean, locale: Locale): String`. Deterministic (no `now`), unit-testable. Produces the labels in §7.2. The home-list currently builds its "Mon, Wed" string from the `Weekly` day-set; swap to this helper so the list shows "Monthly on day 3" / "Annually on October 3" etc. for the new types.

Call sites to update: `AlarmListBuilder.kt:52` region and the home card's day text (`ui/home/HomeScreen.kt` — grep for `WEEK_LABELS` or similar; swap to `Texts.recurrenceSummary`).

## 8. Delete-from-ringing

### 8.1 State moves from composable into service

Today the three-press counter lives in `RingingScreen.kt:56-65` as a Compose `remember`. Problem: the notification Stop action (`Notifications.kt:73`) is single-tap, which contradicts the three-tap guard the user actually wants. Fix once, for both actions, by owning both counters in `RingingService`:

```kotlin
// RingingService
private val _stopPresses = MutableStateFlow(0)
private val _deletePresses = MutableStateFlow(0)
val stopPresses: StateFlow<Int> = _stopPresses.asStateFlow()
val deletePresses: StateFlow<Int> = _deletePresses.asStateFlow()

private var stopResetJob: Job? = null
private var deleteResetJob: Job? = null
```

- On each `ACTION_STOP` broadcast: increment `_stopPresses`, cancel+relaunch a 2 s reset job, re-post the notification with the current label (`"Stop"` / `"Stop (N more)"`). On reaching `pressesNeeded`, zero the counter, actually stop every current entry (today's `graph.store.stop(entry.key, now)` loop), stop the service.
- Same shape for `ACTION_DELETE` and the new `graph.store.delete(entry.key.eventId)` loop. Delete acts on every currently-ringing entry (same semantics as the big Stop button). `pressesNeeded` for delete follows the same `AlarmSettings.stopMethod` setting that already drives Stop (THREE_PRESSES vs. ONE).

A new `ACTION_RESET_PRESSES` is NOT needed — the 2 s coroutine handles it internally.

### 8.2 Composable binding

`RingingScreen` now reads `service.stopPresses.collectAsStateWithLifecycle()` and `service.deletePresses.collectAsStateWithLifecycle()`. Buttons call `onAll(ACTION_STOP)` / `onAll(ACTION_DELETE)` as before — the service increments on receipt. Button labels:

```kotlin
BigButton(if (stop == 0) "Stop" else "Stop ($remain more)", "ring-stop") { onAll(ACTION_STOP) }
BigButton(if (del == 0) "Delete" else "Delete ($remain more)", "ring-delete") { onAll(ACTION_DELETE) }
```

Order on-screen: Snooze (if enabled), Tomorrow, Stop, Delete. Delete is the bottom-most button — intentional, so a fat-finger reaching for Stop doesn't land on Delete.

Per-row expander buttons: unchanged for Snooze / Tomorrow / Stop. A per-row Delete is NOT added — Delete deletes the alarm (all occurrences for a series), which is a different granularity from the per-row single-instance actions. Per-row is a §Non-goal.

### 8.3 Notification

File: `ring/Notifications.kt`. Current actions (line 72–73): Snooze (if enabled), Stop. New:

- Snooze (if enabled)
- `"Stop"` → `ACTION_STOP` (unchanged intent; now goes through the service's counter)
- `"Delete"` → `ACTION_DELETE`

Each action's title updates on the next notification build. The service rebuilds and re-posts the notification whenever either counter changes OR a 2 s reset fires. The rebuild path is a new method `RingingService.repostRingingNotification()` that reads the current entries + the current counter values and emits via `NotificationManagerCompat.notify(RINGING_ID, …)`.

Press-counter idempotency: in-flight requests from the notification shade are debounced to one tap per 150 ms per action (same action.intent extras per tap are safe; the race is only user-visible if someone double-fires via another path). Not critical to call out further.

### 8.4 Permission edge case

`AlarmStore.delete(eventId)` already removes the Google event via `graph.calendar.deleteEvent(eventId)`. On a device where the write permission was revoked after the alarm started ringing, delete can throw — swallow the exception, log, and fall back to tombstoning the alarm locally (the next sync will reconcile). Today's `onFailure { graph.log.log(…) }` branch in `EditAlarmViewModel.delete` is the pattern; mirror it in the service.

## 9. First-run full-screen-intent prompt

### 9.1 Problem

`SetupStep.FULL_SCREEN` exists (`SetupViewModel.kt:32,52`) and maps to `Check.FULL_SCREEN`. The step's "gate" is `graph.checks.status()[Check.FULL_SCREEN] == true`. `Check.FULL_SCREEN` is computed at `ReliabilityChecks.kt:48` as `notifications.canUseFullScreenIntent()`.

On fresh install on Samsung Android 14+, `canUseFullScreenIntent()` returns `true` (the app has a notification category of alarm, which satisfies the system API's docs) BUT Samsung's `USE_FULL_SCREEN_INTENT` appop defaults to `AppOpsManager.MODE_DEFAULT` which Samsung treats as reject. Result: setup auto-advances past the step; alarms post as heads-up.

### 9.2 Fix

Tighten `Check.FULL_SCREEN` to also probe the appop. Pseudocode in `ReliabilityChecks.kt:48`:

```kotlin
Check.FULL_SCREEN to (notifications.canUseFullScreenIntent() && fullScreenIntentAppOpAllowed(context))

private fun fullScreenIntentAppOpAllowed(context: Context): Boolean {
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
    val mode = ops.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_USE_FULL_SCREEN_INTENT,
        android.os.Process.myUid(),
        context.packageName,
    )
    // Samsung treats MODE_DEFAULT as reject. We treat anything other than MODE_ALLOWED as "not granted"
    // so the setup step shows a Fix button.
    return mode == AppOpsManager.MODE_ALLOWED
}
```

The `fixIntent(Check.FULL_SCREEN)` already launches `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` with the right package URI (`ReliabilityChecks.kt:90`) — no change needed to the intent itself.

### 9.3 Behaviour

- Fresh install on Samsung → `Check.FULL_SCREEN` reads `false` → setup pauses at `SetupStep.FULL_SCREEN` → user taps "Full-screen alarms" button → settings page opens → user flips the toggle → `onResume` of `SetupScreen` calls `vm.advance()` → step passes.
- Fresh install on vanilla Android where appop defaults to allow → same code reads `true` → step auto-advances. No regression.
- Settings > Reliability panel: the row now reads `✗` on Samsung instead of `✓`. Fix button works identically.

### 9.4 Test strategy

- JVM: not easily unit-testable (needs `AppOpsManager`). Skip.
- Emulator: `android-36` AVD defaults to `MODE_ALLOWED`. To exercise the fail path, `adb shell cmd appops set <pkg> USE_FULL_SCREEN_INTENT default` before launching setup, verify setup pauses, flip to `allow`, verify it advances.
- Phone: Mustafa reinstalls on `<your-phone-serial>` → setup must now pause at the step on first run. Verification by Mustafa (or by me if a disruptive uninstall-reinstall is pre-approved — standing rule 1 covers it).

## 10. Testing strategy end-to-end

Per [[feedback-self-testing]]: every build step ends with Claude's own checks green with logs/screenshots saved in `logs/`. Only steps Claude cannot do (consent screens) are handed to Mustafa.

### 10.1 JVM (134 tests today; new tests add to this)

- `domain/RecurrenceRuleTest.kt` — new. Cases per variant:
  - `build()` output matches the table in §5.1 for every variant with representative inputs.
  - `parse(build())` roundtrip for every variant (including `Weekly` with 1, 2, 7 days; `MonthlyNthWeekday` with nth ∈ {1,2,3,4,-1}; `MonthlyDay` with day ∈ {1, 15, 28, 29, 30, 31}).
  - `parse()` rejects: `UNTIL=…`, `COUNT=…`, `INTERVAL=2`, `BYDAY=1MO,2TU` (two tokens), `FREQ=HOURLY`, empty `BYDAY`, malformed `BYMONTHDAY=32`.
  - `parse()` accepts the Google-dialect `FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1` and normalises it to `MonthlyNthWeekday(1, MONDAY)`.
- `domain/AlarmStoreEditTest.kt` — extend. One new case per non-weekly variant: create → read back `AlarmDetails` → the `recurrence` field matches. Update: switching an existing weekly series to `MonthlyDay(3)` writes the right RRULE and keeps the DTSTART anchor.
- `domain/TextsTest.kt` — extend. `Texts.recurrenceSummary` output per variant matches §7.2 labels.
- `domain/TimesTest.kt` — extend. New helpers `firstDailyStart`, `firstWeekdayStart`, `firstMonthlyDayStart`, `firstMonthlyNthWeekdayStart`, `firstYearlyStart`. Edge cases: Feb 30 (skip), 31st-of-month on 30-day months (skip), DST transition days (same wall-clock rule as spec §Decision 2026-10-02 #5).
- Target: all green on JVM before any emulator work.

### 10.2 Emulator (`mustafa_alarm_36` AVD, port 5560, android-36)

- `scripts/recurrence-sweep.ps1` — new. Dot-source droid.ps1, launch the app, create one alarm of each type via UI automation (`adb shell input`), read it back via `adb shell content query --uri content://com.android.calendar/events --where "calendar_id=<id>"`, assert the stored RRULE equals the expected string from §5.1. Screenshots the picker open, each sub-section, and the home list showing the new summary strings. Output: `logs/recurrence-sweep/<timestamp>/`.
- `scripts/delete-from-ringing.ps1` — new. Create a one-off alarm 30 s in the future (via debug `DebugCommandReceiver`), let it ring, drive three taps on `ring-delete` with no delay between (expected: event deletes, service stops), then rerun with 3-second gap between tap 2 and tap 3 (expected: counter resets, alarm still ringing). Screenshot each state.
- Existing `scripts/ring-scenarios.ps1 basic locked` — rerun after the Stop-counter-into-service refactor to confirm no regressions.
- `scripts/check-setup.ps1` — new (small). Set the appop to default via `adb shell cmd appops set <pkg> USE_FULL_SCREEN_INTENT default`, reinstall the app, launch, screenshot that setup pauses at FULL_SCREEN, then `adb shell cmd appops set … allow` and verify advance.

### 10.3 Phone (`<your-phone-serial>`)

Per standing rule 1, disruptive tests on the phone are pre-approved — announce in chat right before running, don't wait, restore the phone after.

- Install the branch build. Verify first-run setup pauses at FULL_SCREEN on this fresh install (if it does, that proves §9's fix; if not, back to §9).
- Create one alarm of each new recurrence type on-device, verify each appears on Google Calendar with the correct RRULE within 60 s (push is immediate), verify each rings on the next scheduled instance where possible (Monthly-day uses a day in the next 3 days; Monthly-Nth-weekday uses the next matching weekday; Yearly uses tomorrow's date for the test only).
- Delete-from-ringing: let one alarm ring, three-tap Delete on the fullscreen screen, verify it's gone from Google. Then let another ring, three-tap Delete from the notification shade, verify same.
- Report: a new `notes/recurrence-and-delete-phone-sweep.md` capturing every alarm's `eventId`, screenshots of Google Calendar's web UI showing each series, and timings for sync lag.

## 11. Open-ended edge cases and how we handle them

| Edge case | Handling |
|---|---|
| User sets `Yearly` without a `date` and taps SAVE | SAVE disabled; snackbar via `SaveResult.NeedsDateForYearly`. |
| User sets `MonthlyDay(31)` on a current month with 30 days | RFC 5545 says skip that month; `Times.firstMonthlyDayStart` jumps to the next month that has a 31st. Visible symptom: the next ring is >30 days away. Acceptable; matches Google Calendar. |
| User sets `MonthlyNthWeekday(-1, SATURDAY)` ("last Saturday of month") | Supported; `Times.firstMonthlyNthWeekdayStart` resolves. |
| Existing weekly series opened in the new editor | `RecurrenceRule.parse` returns `Weekly(days)`; the sheet starts on "Weekly on …"; chip row shown; save produces the same RRULE as today → no drift. |
| Existing non-weekly series authored on PC (e.g. `FREQ=MONTHLY;BYMONTHDAY=15`) | `RecurrenceRule.parse` returns `MonthlyDay(15)` → the series is now EDITABLE in-app. Opening it populates the picker; "Change" is NOT needed (that button is only shown for shapes we still can't represent). The `otherRepeat` branch narrows to series with `UNTIL`/`COUNT`/`INTERVAL>1`/multi-token BYDAY/other provider dialects. |
| Zombie `otherRepeat` series after this change | None expected: today's `otherRepeat` was triggered for every non-weekly series, now only for truly unrepresentable ones. Confirmed by inspecting Mustafa's current calendar (3 alarms, all weekly or one-off) — no zombies. |
| Delete-from-ringing while a sync is in flight to Google | `AlarmStore.delete` writes locally first; the provider sync layer handles the retry. If delete arrives faster than the sync layer can push, the deletion wins eventually. Same behaviour as deleting from the list screen today — no new risk. |
| Appop toggled off AFTER setup completes | Reliability panel's `FULL_SCREEN` row goes `✗`; Fix button opens the right page. Already works today; no change. |

## 12. Rollout + branch logistics

- Branch: `feature/recurrence-and-delete-ringing` from `master` (b339185).
- Spec commit: this file. Co-authored by Claude.
- Plan: `docs/superpowers/plans/2026-10-03-recurrence-and-delete-ringing.md`, written next via `superpowers:writing-plans`.
- Execution: `superpowers:subagent-driven-development` with per-task review. Expected ~6–8 tasks (domain + model + VM + UI + service refactor + notification + setup appop + phone sweep).
- Finishing: `superpowers:finishing-a-development-branch`, merge locally into `master`, push to `origin`.

## 13. What is NOT in this spec (keep for a future session)

- `requestSync` nudge after provider writes to speed up Samsung's periodic Google-calendar sync (handover §Spec §12 Layer 4).
- Three-press Stop counter already present in composable (today's workaround) — being removed as part of §8.1, so this item resolves itself.
- `GoogleIdTokenCredential.id` deprecation refactor.
- Volume floor at 10 % vs. spec's 0–100 % — unrelated.
- `AlarmStore.setEnabled` series-off scan optimisation — unrelated.

## 14. Decisions log

| Date | Decision | Source |
|---|---|---|
| 2026-10-03 | Google-full-menu-minus-Custom; no UNTIL/COUNT; Delete big-button + notification; move both ring counters into service; fold FSI into this session. | Interactive brainstorm, this session. |
| 2026-10-03 | `RecurrenceRule` replaces `WeeklyRule`; `AlarmInput.days` becomes `recurrence`. | Follows from decisions above; drives §5–§7. |
| 2026-10-03 | Yearly requires a `date`; new `SaveResult.NeedsDateForYearly`. | Needed because RRULE `FREQ=YEARLY` carries no month/day of its own — the DTSTART does. |
| 2026-10-03 | Delete-from-ringing deletes ALL currently-ringing entries' underlying events (matches big-Stop semantics). | Simpler than per-row; matches Mustafa's wording ("Delete action next to Stop"). |
| 2026-10-03 | `Check.FULL_SCREEN` adds an `OPSTR_USE_FULL_SCREEN_INTENT` appop probe. | Only reliable way to detect Samsung's silent-reject default. |
