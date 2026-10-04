# Mustafa Alarm — Design Spec

Date: 2026-10-02 · Status: reviewed 2026-10-02 (review findings and Mustafa's decisions applied; see §14)

## 1. Goal

A personal Android alarm app, **Mustafa Alarm**, for Mustafa's own phone only (never published, no ads, no payments). It must:

1. Ring as reliably as *Simple Alarm* (Base Juegos) — locked screen, battery saver, Samsung app-sleeping, reboot.
2. Create alarms the way Mustafa does today: a time, a specific date **or** repeat weekdays, and a message (his alarms are work reminders, e.g. "Fırat şap makinesi teklif ver").
3. Snooze or push an alarm to tomorrow from the ringing screen.
4. Sign in with Google and keep every alarm as an event in a dedicated **"Alarms"** Google Calendar, **two-way**: phone changes move the event; changes made in Google Calendar on the PC move the alarm.

**Success:** Mustafa can stop using Simple Alarm, and Google Calendar on his PC always shows exactly what will ring on his phone, and when.

**Out of scope:** Play Store, ads, billing, analytics, timer/stopwatch, home-screen widgets, night clock, "upcoming alarm" pre-notifications, language picker, rate/FAQ screens, multiple Google accounts at once.

## 2. Environment facts

| Item | Value |
|---|---|
| Workfolder | `C:\Users\musta\simple alarm clock\` — ALL generated files (scripts, logs, screenshots, outputs) go here, never in OS temp or the harness scratchpad (user's global rule) |
| Android project location | `C:\Users\musta\simple alarm clock\mustafa-alarm\` |
| JDK | 17 (`C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`) |
| Android SDK | `D:\Program Files\Android\Sdk` (`ANDROID_HOME`), platforms 34–37, build-tools 34.0.0 / 36.0.0 |
| adb | `D:\Program Files\Android\Sdk\platform-tools\adb.exe` (not on PATH under that name — use full path) |
| Emulator | New AVD `mustafa_alarm_36`, image `android-36.1/google_apis/x86_64` — same Android 16 generation as the phone. (The existing AVD `crm_phone`, API 34, belongs to the CRM project and is not used.) |
| Real phone | Samsung Galaxy S24 Ultra SM-S928B, adb serial `<your-phone-serial>`, Android 16 (SDK 36), One UI 8.5; has `com.google.android.syncadapters.calendar`, Google Calendar, Simple Alarm, Samsung Clock installed |
| Google account | `your-google-account@gmail.com` — owns the "Alarms" calendar; the same account is connected to Claude's Google Calendar tools (used for sync tests) |
| Git | repo at workfolder root; `.gitignore` excludes the APK, decompiled output, research clones, tools, logs, keystores |

Reference material (read-only, never copied into the app):
- `notes/app_analysis.md` — Simple Alarm's manifest/permission blueprint.
- `out/jadx/sources/ar/com/basejuegos/simplealarm/ringing/RingingForegroundService.java` and `out/jadx/sources/x3/k.java` — how Simple Alarm rings (study only; proprietary).
- `screenshoots/*.jpg` — the UI Mustafa is used to.
- `notes/github_search.md` and `research/` — similar open-source apps. `VytenisNarmontas/CalendarAlarm` has **no license** (read, never copy). `campagnola/calendar-alarm` is GPL-3.

## 3. Technology decisions

- **Kotlin**, **Jetpack Compose** (Material 3, dark theme), single Gradle module `app`.
- Application ID / package: `com.atatuzun.mustafaalarm`. App label: `Mustafa Alarm`.
- `minSdk 36`, `targetSdk 36` — built for Android 16, the version on Mustafa's phone (changed 2026-10-02 at Mustafa's request). `compileSdk 37` because current AndroidX libraries require it; it is build-time only and does not change app behaviour.
- Persistence: **Room** for phone-only data, **DataStore** for settings. **All of it lives in device-protected storage** (Room DB, DataStore file, event log, ring cache) so Stop/Snooze/Tomorrow and the settings they need work before first unlock (Samsung's night-time auto-restart makes this a real case). Device-protected storage is not encrypted at rest; it only ever holds alarm titles, times and settings.
- Background: **AlarmManager** for ringing, **JobScheduler** content-URI trigger for calendar changes, **WorkManager** for the 15-minute safety check.
- Manifest permissions: `USE_EXACT_ALARM`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `READ_CALENDAR` + `WRITE_CALENDAR`, `READ_SYNC_SETTINGS` (reliability check reads per-calendar sync state; `requestSync` itself needs no permission), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `VIBRATE`, `INTERNET` (setup only).
- Google: **Credential Manager** "Sign in with Google" + **Identity AuthorizationClient** for the Calendar scope; plain HTTPS calls to the Calendar REST API (no heavy client library needed for two calls).
- UI text in **English**. Alarm messages may be any language.
- One build variant installed on devices: debuggable, signed with the project's own keystore (so `adb shell run-as` works for tests and updates install over each other).

## 4. Architecture

```
Google Calendar (web / PC)
        ⇅  Android's Google Calendar sync adapter (push from Google)
Phone calendar provider — "Alarms" calendar
        ⇅  CalendarAccess
   AlarmStore  ⇄  LocalStore (Room + device-protected cache)
        ↓
   Scheduler → AlarmManager.setAlarmClock → AlarmReceiver → RingingService → RingingActivity
```

Units (each one job, testable on its own):

| Unit | Responsibility | Depends on |
|---|---|---|
| `GoogleSetup` | Sign in, obtain Calendar authorization, create the "Alarms" calendar once (REST), then ask `CalendarAccess` to locate it in the phone's provider and enable its sync/visibility | Credential Manager, AuthorizationClient, HTTPS, CalendarAccess |
| `CalendarAccess` | The **only** code that touches `CalendarContract`: query instances, insert/update/delete events, create single-occurrence exceptions, set event colour | ContentResolver |
| `LocalStore` | Room: per-event sound, handled-instance records, alarm-creation history; device-protected ring cache + currently-ringing set | Room, files |
| `AlarmStore` | Combines the two into domain operations (create, edit, delete, toggle, snooze, tomorrow, stop) and the alarm list for the UI | CalendarAccess, LocalStore |
| `Scheduler` | Computes the next ring minute, registers it, refreshes the ring cache | AlarmStore, AlarmManager |
| `RingingService` + `RingingActivity` | Plays sound/vibration, shows the full-screen ringing UI, performs actions | AlarmStore, Scheduler |
| `Watchers` | Reboot, locked boot, app update, time/time-zone change, calendar change (JobScheduler content trigger), 15-min safety check | Scheduler |
| `Screens` | List, add/edit, quick alarms, settings (incl. reliability check), first-run setup, ringing | AlarmStore, settings |
| `EventLog` | Append-only log of every step (scheduled, rang, snoozed, moved, synced, permission missing) to an app file + logcat tag `MustafaAlarm` | — |

**Google Calendar is the source of truth** for time, date, recurrence, message and on/off. The phone stores only what an event cannot hold.

## 5. Data model

### 5.1 Alarm ↔ event

| Alarm field | Event representation |
|---|---|
| Message | `TITLE` (empty message → "Alarm") |
| Ring time | `DTSTART` in the device time zone at creation (`EVENT_TIMEZONE` = device zone). **Ringing follows local wall-clock time** (decision 2026-10-02): the ring instant of an occurrence is its wall-clock time in the event's `EVENT_TIMEZONE`, re-interpreted in the phone's *current* zone. At home the two are identical; after travelling, a 07:00 alarm still rings at 07:00 local. `TIMEZONE_CHANGED` reschedules with the new zone. The calendar keeps the event's own zone (the PC shows it in the PC's zone as usual). |
| Length | App-created events are 15 minutes (`DTEND` for one-offs, `DURATION=PT15M` for recurring) |
| One-off | Single event. An alarm with no date and no repeat days gets the next date that time occurs |
| Repeat weekdays | `RRULE:FREQ=WEEKLY;BYDAY=…` |
| On | Event uses the calendar's default colour (no event colour) |
| Off / done | Event colour **Graphite** (Google event colour id `8`, via the account's `Colors` table `COLOR_KEY`) |
| Reminders | None. The "Alarms" calendar is created with no default reminders and no notifications |

Any recurrence created on the PC (daily, monthly, custom) is honoured: the app rings at every **instance** (`CalendarContract.Instances`). The edit screen itself only creates weekly rules; editing a non-weekly series in the app shows its time/message only and keeps the rule untouched.

All-day events in "Alarms" are ignored (never ring, not listed).

### 5.2 Instance identity

An occurrence is identified by `InstanceKey = (Instances.EVENT_ID, Instances.BEGIN)`. For a one-off and for an unmodified series occurrence this is `(eventId, originalBeginMillis)`. A moved occurrence is its own Events row (an exception), so its key uses the **exception's** event id and new begin time. For grouping in the UI ("each alarm appears once") every instance carries `alarmId = ORIGINAL_ID ?: EVENT_ID`, i.e. exceptions roll up into their series.

The ring instant of an occurrence is derived from `BEGIN` by the wall-clock rule in §5.1 (`ringAt == BEGIN` whenever the phone is in the event's zone).

### 5.3 Phone-only data (Room)

- `alarm_extras(eventId PK, soundUri)` — per-alarm sound; missing → default sound.
- `handled_instances(eventId, originalBeginMillis, action, handledAt)` — prevents re-ringing an occurrence after Stop/Snooze/Tomorrow, including after reboot.
- `creation_history(hourMinute, createdAt)` — feeds "Your frequent alarms" (top 5 times over the last 60 days).
- `pending_actions(eventId, begin, action, pressedAt)` — Snooze/Tomorrow/Stop pressed while the calendar provider is unavailable (before first unlock) or when a provider write fails; a temporary ring-cache entry keeps the alarm ringing at its new time and the action is applied to the calendar on the next unlocked reschedule.
- `auto_snooze_count(eventId, begin, count)` — how many times an occurrence auto-snoozed without an answer (see §6.1).

Known limitation: these tables key on the provider's local event `_ID`. That id survives normal sync, but a full re-sync or removing/re-adding the Google account recreates rows, orphaning per-alarm sounds and handled records. Acceptable for a personal app; `handled_instances` is pruned by age.

### 5.4 Ring cache and ringing state (also device-protected)

- `ring_cache` — up to 100 upcoming occurrences in the next 14 days (or the single next one beyond 14 days): `(eventId, begin, ringAtMillis, alarmId, title)`. Rewritten on every reschedule.
- `ringing_now` — the occurrences currently ringing, so a restarted service (or the first reschedule after a reboot) resumes them.
- `pre_ring_volume` — the alarm-stream level before "Increase device volume" raised it, so it is restored even if the process was killed while ringing.

## 6. Sync rules

### 6.1 Phone action → event

| Phone action | One-off alarm | Repeating alarm |
|---|---|---|
| **Snooze** (setting, default 30 min) | Event `DTSTART` = time Snooze was pressed + snooze duration (length kept) | Exception for this occurrence moved to press time + duration; series untouched |
| **Tomorrow** | Event moves to the same time tomorrow | This occurrence's exception moves to the same time tomorrow; if tomorrow already has an occurrence, this occurrence is cancelled instead (exception with `STATUS_CANCELED`) |
| **Stop** | Event stays at the time it last rang; colour → Graphite (done) | No calendar change; occurrence recorded in `handled_instances` |
| **No answer** (auto-snooze, default after 1 min) | Same as Snooze, **at most 3 times per occurrence**; the 4th unanswered ring is treated as **Tomorrow** (event moves to the same time tomorrow, log records `unanswered → tomorrow`). Nothing is ever silently lost. (Decision 2026-10-02.) | Same as one-off: 3 auto-snoozes on the occurrence's exception, then Tomorrow for that occurrence |
| **Turn off / on** | Graphite / default colour. Turning on a one-off whose time is past moves it to the next occurrence of that time | Whole series Graphite / default colour |
| **Edit** | Event updated | Series updated |
| **Delete** | Event deleted | Series deleted |
| **Snooze/Tomorrow on an occurrence that is already an exception** | — | The existing exception event is updated directly |

### 6.2 PC change (inside "Alarms" only) → phone

| Change in Google Calendar | Phone result |
|---|---|
| Create a timed event | New alarm, on, default sound |
| Move / rename / change date | Alarm follows |
| Any recurrence | Rings at every occurrence |
| Colour → Graphite / back to default | Off / on |
| Delete | Alarm removed |
| All-day event | Ignored |

### 6.3 Edge rules

- **Offline:** phone changes are written to the provider immediately and ring immediately; Android uploads when online.
- **Conflict:** the last change to reach Google wins.
- **Missed while phone off / asleep:** evaluated at **every** reschedule (boot, time change, safety check, calendar change — not only boot): occurrences that were in the previous `ring_cache`, have `ringAt` within the last 60 minutes, never rang, are not in `handled_instances` and still exist in the calendar ring immediately. Anything older is left alone. The rule is **cache-based only**: the live Instances query (§7) starts at the current minute, so an event moved or created in the past from the PC never rings.
- **Deleted while ringing:** keeps ringing; Stop/Snooze/Tomorrow then do nothing to the calendar.
- **Reboot or process kill while ringing:** `ringing_now` still lists the occurrence(s). The first reschedule afterwards sees "ringing but the ringer is not running" and re-fires through AlarmManager one second later; the ringer resumes them. (Boot receivers may not start a `mediaPlayback` foreground service directly on Android 15+, so this always goes through `setAlarmClock`.)
- **Quick alarms** create normal one-off alarms titled "Alarm".

## 7. Scheduling

- Query `Instances` for the "Alarms" calendar from the **current minute** to `now + 14 days` (extend to 366 days if empty), excluding all-day, cancelled and Graphite events, handled instances and instances currently ringing. Past occurrences come only from the cache-based missed rule (§6.3).
- Register the **earliest ring instant** with `AlarmManager.setAlarmClock(AlarmClockInfo(t, showIntent), operation)`. `USE_EXACT_ALARM` is declared, so `canScheduleExactAlarms()` is always true and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` never fires; if it is ever false anyway, fall back to the **inexact** `setAndAllowWhileIdle` (the exact variants throw `SecurityException` without the permission), log it and show ✗ in the reliability check.
- All occurrences sharing that minute ring together. A past trigger (missed rule, resume after reboot) is also registered with `setAlarmClock`; AlarmManager fires it at once and the foreground service may start because the start comes from an alarm clock.
- Reschedule after: every AlarmStore write, ring/handle, boot (`BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED`, receivers `directBootAware`), `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, calendar content change, and the 15-minute WorkManager safety check.
- Calendar content change: a JobScheduler job with `addTriggerContentUri` on **`CalendarContract.CONTENT_URI`** (the provider notifies the authority root, not `/events`) and, defensively, on `Events.CONTENT_URI`, both with `FLAG_NOTIFY_FOR_DESCENDANTS`; the job is one-shot and re-armed after each run, at boot and by the safety check.
- Safety check (every 15 min): re-arms the alarm idempotently (same PendingIntent replaces the old one), re-arms the content job, and logs a warning when `getNextAlarmClock()` is **later** than our expected trigger or null. (`getNextAlarmClock()` is system-wide — Samsung Clock's earlier alarm is a normal result, not an error.)
- Before first unlock (calendar provider unavailable) the scheduler works from `ring_cache` only.

## 8. Ringing

Same proven techniques Simple Alarm uses (implemented independently):
- `AlarmReceiver` starts `RingingService` (foreground service type **mediaPlayback**), which holds a partial wake lock.
- Ringing notification: channel importance HIGH, category `alarm`, ongoing, `setFullScreenIntent(…, true)`, `FOREGROUND_SERVICE_IMMEDIATE`, actions Snooze and Stop.
- Audio: `USAGE_ALARM`, looping. Fade-in (log curve over ~30 s) when enabled. "Increase device volume" raises the alarm stream to max and restores the previous level when ringing ends; the previous level is persisted (`pre_ring_volume`, §5.4) so it is restored on the next start even after a kill. Vibration pattern unless "sound only".
- Default sound bundled in `res/raw` (used before first unlock or if the chosen ringtone is unavailable).
- `onStartCommand` returns `START_STICKY`; a restart with a null intent resumes from `ringing_now`.
- Auto-snooze after the configured time with no answer — at most 3 times per occurrence, then Tomorrow (§6.1).
- Stop method: one press or three presses (default three, matching Simple Alarm's "Three Buttons"). The notification Stop action stops immediately.

## 9. Screens

1. **Alarm list (home)** — top bar ⚡ Quick alarms, ⚙ Settings, ＋ New. Banners: "Next alarm in N minutes"; volume too low (alarm stream below 50 % of max while "Increase device volume" is off); missing permission; Google sync off; "Alarms calendar is missing [Recreate]". Groups by next ring day (Today, Tomorrow, weekday name, date). Each alarm appears once. Row: icon (📅 one-off / 🔁 repeating), message, time, on/off switch, edit, delete. Grey rows = off/done; done one-offs stay visible until the end of their day.
2. **Add / edit** — big time display with number-pad entry dialog; SUN–SAT chips; "Date ＋" date picker (future dates only); message field; sound picker; Save; Delete when editing.
3. **Quick alarms** — relative 5m, 15m, 30m, 45m, 1h, 2h, 4h, 8h, 12h, 24h; morning 05:30, 06:00, 06:30, 07:00, 07:30, 08:00; "Your frequent alarms" (top 5); "Create my own" → add screen. Tapping a preset creates the alarm immediately and shows a snackbar "Alarm set for HH:MM · Add message · Undo".
4. **Ringing** — big current time; list of all ringing messages; big buttons Snooze N m / Tomorrow / Stop apply to all; tapping a row reveals Snooze / Tomorrow / Stop for that alarm only (others keep ringing).
5. **Settings** — Account (signed-in email, switch account, "Alarms" calendar status + last sync time); Sound (default sound, sound/vibration/both, volume, increase device volume = on, fade in = on); Snooze & stop (snooze 30 min, auto-snooze after 1 min, show snooze button = on, stop method = three presses); Display (24-hour = on, dark theme, next-alarm notification = on — a silent, low-priority ongoing notification "Next: HH:MM · message"); **Reliability check** (✓/✗ + Fix for: exact alarms, full-screen intent, notifications, battery Unrestricted, calendar permission, calendar sync on for the "Alarms" calendar).
6. **First run** — calendar permission first (locating needs it) → look for an "Alarms" calendar in the phone's provider. **Found:** the app is usable immediately; no sign-in. **Missing:** Sign in with Google → authorize Calendar → create "Alarms" (REST) → locate it. Then the remaining permissions one at a time (notifications, full-screen intent, battery unrestricted) with a one-line reason each; steps after the calendar step have "Skip for now". The gate is "Alarms calendar located", not "signed in" (decision 2026-10-02): an expired OAuth grant or a reinstall without network never locks the app out of alarms that already exist. Sign-in failures show the error and Retry.

## 10. Google setup

### 10.1 Google Cloud (one-time, **done first — before any app code** — by Claude in Mustafa's Chrome via the browser extension, Mustafa watching and clicking Allow where Google requires; decision 2026-10-02 so implementation never waits on it)
- Project "Mustafa Alarm" under `your-google-account@gmail.com`.
- Enable **Google Calendar API**.
- Google Auth Platform: External audience, **Testing** status, test user `your-google-account@gmail.com`.
- OAuth client **Android**: package `com.atatuzun.mustafaalarm`, SHA-1 of the project keystore.
- OAuth client **Web application**: its client ID is the `serverClientId` for Sign in with Google.
- Scope: `https://www.googleapis.com/auth/calendar.app.created` (create secondary calendars and manage their events — least privilege).
- Testing-mode grants expire after 7 days; acceptable because the REST API is only used at setup and when recreating the calendar.
- The Android OAuth client needs the keystore SHA-1, so the keystore (§10.3) is generated as part of this step.
- Open point to verify at first run: whether `calendar.app.created` also authorises `calendarList.patch` (step 3 of §10.2). If it returns 403, the fallback is to switch off the calendar's notifications once by hand on the PC; alarms are unaffected.

### 10.2 In-app setup flow
1. Credential Manager `GetSignInWithGoogleOption(serverClientId)` → account email.
2. `AuthorizationClient.authorize(scope)` → access token (may show Google's consent screen).
3. If no "Alarms" calendar exists in the provider for that account: `POST calendars` `{summary: "Alarms", timeZone: <device>}`, then `PATCH users/me/calendarList/{id}` `{defaultReminders: [], notificationSettings: {notifications: []}}`.
4. Request a sync, wait for the calendar row to appear in `CalendarContract.Calendars` (match `ACCOUNT_NAME` + `_SYNC_ID == id`), set `SYNC_EVENTS=1`, `VISIBLE=1`. Store the calendar id.

### 10.3 Signing key
- Keystore `mustafa-alarm/keystore/mustafa-alarm.jks` + `mustafa-alarm/keystore.properties` (both git-ignored). SHA-1 recorded in `notes/signing.md`.
- Losing the keystore means: uninstall/reinstall, and a new Android OAuth client.

## 11. Failure handling

| Situation | Behaviour |
|---|---|
| No internet | Fully functional; Android uploads later |
| Calendar sync off (account or calendar) | Alarms still ring and edits are kept; banner "Google sync is off — your PC won't see changes" + Fix (opens account sync settings) |
| "Alarms" calendar missing | Banner + Recreate (runs 10.2 step 3–4); existing ring cache keeps ringing meanwhile |
| Permission removed | Already-cached alarms keep ringing where Android allows; banner + ✗ in reliability check + Fix |
| Sign-in / authorization fails | First-run shows the error + Retry |
| Crash while ringing | `START_STICKY` restart resumes from `ringing_now`; safety check re-registers the next alarm |
| Reboot while ringing | First reschedule after boot re-fires through AlarmManager within 1 s (§6.3); the ringer resumes from `ringing_now` |
| Action pressed before first unlock or provider write fails | Stored in `pending_actions`, alarm keeps ringing/snoozes locally, applied to the calendar on the next unlocked reschedule |
| Ringtone unavailable | Bundled default sound |

## 12. Testing (Claude does all of this; evidence saved under `logs/`)

**Layer 1 — JVM unit tests (TDD, run on the PC):** next-ring computation for every alarm type; snooze/tomorrow/stop/toggle mapping to event mutations (via a fake `CalendarAccess`); exception handling for repeating alarms; missed-alarm rule; DST and time-zone change; multiple alarms in the same minute; frequent-alarm ranking.

**Layer 2 — Instrumented calendar tests (emulator):** tests create a local calendar (`ACCOUNT_TYPE_LOCAL`, sync-adapter mode) and exercise the real `CalendarAccess`: insert, move, exception, cancel, colour, delete; verify instance queries and that the content-change job reschedules.

**Layer 3 — Ringing & reliability via adb (emulator first, then phone):** set an alarm 1 min ahead, then verify ringing screen (screenshot), audio playing (`dumpsys audio`/media session), notification (`dumpsys notification`), registered next alarm (`dumpsys alarm`) and the EventLog (`run-as … cat`). Drive Snooze/Tomorrow/Stop with UI Automator/`input` and re-verify. Scenarios: screen locked; forced Doze (`dumpsys deviceidle force-idle`); app process killed; reboot before unlock; time-zone change; app update (`install -r`); auto-snooze; 4 alarms in the same minute; missed alarm after reboot.

**Layer 4 — Real Google two-way sync (phone):** create/move/recolour/delete events in "Alarms" with Claude's Google Calendar tools and verify the phone's alarm list + `dumpsys alarm` follow (record latency); snooze/tomorrow/stop on the phone and read the event back from Google to verify.

**Layer 5 — Samsung reality check (phone):** battery Unrestricted vs. default; app left idle (sleeping-apps behaviour); screen off for a long period; reliability-check screen shows all ✓.

**Rules:** disruptive steps on the real phone (reboot, time-zone change via adb, forced Doze, app kill, test alarms at low volume) are **pre-approved for any time** (decision 2026-10-02): Claude announces each one in the chat right before running it and does not wait for a reply; the phone is restored (zone, Doze state, volume) after each test. Test events only ever go into the "Alarms" calendar and are deleted afterwards.

**Needs Mustafa (once each):** click Allow on Google consent screens during Cloud setup (step 0) and first sign-in; keep the phone connected with USB debugging authorised.

## 13. Risks to verify first (spike before building screens)

1. A normal app can set `SYNC_EVENTS`/`VISIBLE` on the new calendar row in the provider. (Documented as app-writable columns — a confirmation, not a real risk; no fallback planned.)
2. Setting an event colour key through `CalendarContract` syncs to Google as Graphite, and a PC colour change comes back as the same key.
3. The JobScheduler content trigger fires on the S24 when the sync adapter writes PC changes; measure PC→phone latency.
4. Exceptions (moved/cancelled single occurrences) created through the provider sync correctly to Google.
5. Ringing works before first unlock after a reboot (direct boot) using the ring cache and bundled sound.

**If a spike fails, Claude uses the safest fallback and continues without pausing** (decision 2026-10-02), records the swap in this spec and reports it at hand-over:
- Risk 2 fails → on/off is encoded in the title instead of the colour (prefix `⏸ ` = off/done; PC users toggle by adding/removing the prefix). Colour code is kept behind the abstraction so it can be switched back.
- Risk 3 fails → PC changes are picked up by the 15-minute safety check (plus a 1-minute poll while the app is in the foreground); the measured latency is reported.
- Risk 4 fails → Snooze/Tomorrow on a repeating occurrence are applied through the Calendar REST API (`events.instances` + `events.patch`); this needs daily tokens, so the consent screen is switched to "In production" (unverified, personal use) — the only fallback with a user-visible setup consequence, reported immediately.
- Risk 5 fails → the ringer skips `LOCKED_BOOT_COMPLETED` handling and relies on `BOOT_COMPLETED`; the reliability check shows a warning that alarms need the phone unlocked once after a reboot.

## 14. Review log (2026-10-02)

Written review before implementation. Corrections applied above:

| # | Finding | Resolution |
|---|---|---|
| 1 | §6.3 (PC-moved past events never ring) contradicted §7 (query from `now − 60 min`) | Query starts at the current minute; the missed rule is cache-based only |
| 2 | `setExactAndAllowWhileIdle` fallback needs the same permission it was a fallback for | Inexact `setAndAllowWhileIdle` + log + ✗ in reliability check |
| 3 | "`getNextAlarmClock()` belongs to us" is unknowable (system-wide) | Idempotent re-arm; warn only when system next is later than ours |
| 4 | Content trigger on `Events.CONTENT_URI` likely never fires (provider notifies the authority root) | Trigger on `CalendarContract.CONTENT_URI` (+ `Events.CONTENT_URI` defensively) |
| 5 | Only ring cache / ringing_now were device-protected; settings and handled records needed before unlock too | All phone data in device-protected storage |
| 6 | Reboot while ringing left `ringing_now` entries excluded from every planner filter → never rang again | "Ringing but silent" re-fire via AlarmManager |
| 7 | Boot receiver cannot start a `mediaPlayback` FGS directly on Android 15+ | Missed/resume always goes through `setAlarmClock` |
| 8 | Instance identity undefined for exceptions | `(EVENT_ID, BEGIN)` + `alarmId = ORIGINAL_ID ?: EVENT_ID` |
| 9 | Auto-snooze repeated indefinitely | **Mustafa:** 3 auto-snoozes, then Tomorrow |
| 10 | Time-zone semantics undefined | **Mustafa:** local wall-clock time |
| 11 | Hard sign-in gate locked the app even when the calendar existed | **Mustafa:** gate on "Alarms calendar located" |
| 12 | Google Cloud setup could block implementation mid-way | **Mustafa:** done first, before app code; app still creates the calendar |
| 13 | Disruptive phone tests required asking each time | **Mustafa:** pre-approved any time, announced before each run |
| 14 | Spike failure required a pause | **Mustafa:** safest fallback, continue, record and report |
| 15 | Garbled emulator row, risk 1 not a real risk, `READ_SYNC_SETTINGS` missing, pre-ring volume lost on kill, local `_ID` keying limitation | Fixed / documented |
