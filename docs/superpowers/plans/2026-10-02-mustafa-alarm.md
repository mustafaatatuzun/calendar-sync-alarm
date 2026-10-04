# Mustafa Alarm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Mustafa Alarm, a personal Android alarm app that rings as reliably as Simple Alarm and keeps every alarm as an event in a dedicated, two-way-synced "Alarms" Google Calendar.

**Architecture:** Google Calendar is the source of truth; the app reads and writes the phone's calendar provider (`CalendarContract`) and lets Android's Google sync adapter carry changes to and from Google. All alarm logic (next ring, snooze/tomorrow/stop mapping, missed alarms, list grouping) is pure Kotlin in `domain/`, tested on the PC with fakes. Thin Android adapters (provider access, Room in device-protected storage, AlarmManager scheduler, foreground ringing service, receivers/jobs) and Compose screens sit around it.

**Tech Stack:** Kotlin 2.4.0, AGP 9.1.0, Gradle 9.3.1, Jetpack Compose (BOM 2026.09.00, Material 3), Navigation Compose 2.10.2, Room 2.8.5 (KSP 2.3.12), DataStore 1.2.1, WorkManager 2.12.0, Credential Manager 1.6.0 + googleid 1.2.1, Play Services Auth 22.0.0 (AuthorizationClient), JUnit 4.13.2, AndroidX Test.

**Spec:** `docs/superpowers/specs/2026-10-02-mustafa-alarm-design.md` — read it before starting any task; this plan argues from it.

---

## How to run things (read once)

- Every command block runs in **PowerShell from the workfolder root** `C:\Users\musta\simple alarm clock` and starts by dot-sourcing the helper: `. .\scripts\droid.ps1` (created in Task 1). It sets `JAVA_HOME`, `ANDROID_HOME`, `$adb`, `$Root`, `$Proj`, `$Pkg` and defines `Gradle`, `A` (adb to `$env:DEVICE`), `Shot`, `Tap-Id`, `Tap-Text`, `Assert-Text`, `AppLog`, `Mark-AppLog` + `Wait-AppLog` (waits only see log lines written after the last mark), `Debug-Cmd`, `Instrument`, `Start-Emu`, `Commit`.
- `$env:DEVICE` picks the target: `emulator-5560` (default, AVD `mustafa_alarm_36`, Android 16) or `<your-phone-serial>` (Mustafa's S24 Ultra). `Gradle` copies it into `ANDROID_SERIAL`, so `installDebug` only touches that device.
- Never use `connectedDebugAndroidTest` (it uninstalls the app afterwards). Install with `Gradle :app:installDebug :app:installDebugAndroidTest` and run instrumented tests with `Instrument <class>`.
- Evidence (screenshots, dumps, logs) goes under `logs\` (git-ignored, kept as the audit trail). Notes go under `notes\`. Never write to the OS temp dir or the harness scratchpad (user's global rule).
- Commit with `Commit "<subject>" -Session <your Claude-Session URL>`; it appends the `Co-Authored-By` trailer (and the session line when given). Work happens on branch `feature/mustafa-alarm`.

## Needs Mustafa (once each — everything else Claude does and verifies itself)

1. **Step 0 (before Task 1):** watch the Chrome session for Task 17 (Google Cloud project + OAuth clients) and click anything Google insists a human clicks (re-login, 2FA, "Allow"). Mustafa's decision 2026-10-02: Task 17 runs **first**, so nothing later waits on it. Task 17 therefore also generates the keystore (it needs the SHA-1); Task 1 reuses it.
2. Task 18: pick the account and press **Allow** on Google's consent screen during the first sign-in on the phone.
3. Back up `mustafa-alarm\keystore\mustafa-alarm.jks` and `mustafa-alarm\keystore.properties` somewhere safe (losing them = reinstall + new OAuth client).
4. Keep the phone connected with USB debugging authorised. **Disruptive phone tests are pre-approved for any time** (Mustafa, 2026-10-02): reboot, time-zone change via adb, forced Doze, app kill, low-volume test alarms — announce each in the chat right before running it, do not wait for a reply, restore the phone afterwards.

## Decisions from the spec review (2026-10-02) — binding for every task, never re-ask

Spec §14 has the full log. Where a task's code sample below disagrees with one of these, **the decision wins** and the task is adjusted while implementing:

1. **Unanswered alarm:** at most **3 auto-snoozes** per occurrence (`auto_snooze_count` table), the 4th unanswered ring is handled as **Tomorrow** and logged `unanswered → tomorrow`. Affects Tasks 6 (AlarmStore), 9 (Room), 11 (RingingService `ACTION_AUTO_SNOOZE`), 12 (`auto-snooze` scenario: after 3 cycles expect the event tomorrow).
2. **Time zone = local wall-clock:** ring instant = occurrence's wall-clock time in its `EVENT_TIMEZONE`, re-interpreted in the current device zone (`ringAt == BEGIN` while zones match). Affects Tasks 3 (`Times`), 4 (`RingPlanner`/`CachedOccurrence.ringAt`), 12 (`timezone` scenario: after `adb shell service call alarm` zone change the alarm must ring at the same local HH:MM).
3. **First-run gate = "Alarms calendar located"**, not "signed in": calendar permission first, look for the calendar, sign-in/REST only when it is missing. Affects Task 18.
4. **Scheduler fallback:** when `canScheduleExactAlarms()` is false use inexact `setAndAllowWhileIdle` (never `setExactAndAllowWhileIdle`), log it, ✗ in the reliability check. Affects Tasks 10, 16.
5. **Content trigger:** `CalendarContract.CONTENT_URI` is the primary URI (already in Tasks 2/10 alongside `Events.CONTENT_URI`).
6. **Pre-ring volume persisted** (`pre_ring_volume` in DataStore) and restored on the next service start if a kill skipped the restore. Affects Tasks 9, 11.
7. **Spike failure (Task 2, risks 2–5): use the safest fallback from spec §13 and continue** — title prefix `⏸ ` instead of colour; 15-min poll instead of content trigger; REST `events.patch` for exception writes (consent screen → "In production"); `BOOT_COMPLETED`-only if direct boot fails. Record the swap in spec §13 and in the hand-over report. Never stop to ask.
8. Manifest adds `READ_SYNC_SETTINGS` (reliability check reads per-calendar sync state). Affects Tasks 10, 16.

## Global Constraints

- Application ID / namespace `com.atatuzun.mustafaalarm`; app label `Mustafa Alarm`; Android project in `mustafa-alarm\` (single module `app`).
- **Built for Android 16, the version on Mustafa's phone (Mustafa's decision, 2026-10-02):** `minSdk 36`, `targetSdk 36`. `compileSdk 37` is build-time only: current AndroidX (core 1.19.1, Compose UI 1.12.1, lifecycle 2.11.0, navigation 2.10.2) declare `minCompileSdk=37`; how the app behaves is set by `targetSdk 36`. SDK platform on disk: `android-37.0`.
- Emulator: a new AVD **`mustafa_alarm_36`** on `system-images;android-36.1;google_apis;x86_64` — Android 16 QPR2, the same API level as the phone (36.1) — always started on port 5560, so its serial is **`emulator-5560`** (created in Task 1). The existing `crm_phone` AVD (API 34) belongs to the CRM project and is not used.
- Toolchain: JDK 17 at `C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`; Gradle 9.3.1 wrapper; AGP 9.1.0 with `android.builtInKotlin=false` and `android.newDsl=false` + `org.jetbrains.kotlin.android` 2.4.0 — the exact combination proven on this PC by `C:\Users\musta\crm\mobile\android`. `GRADLE_USER_HOME` is `D:\Program Files\Android\gradle` (already set).
- adb is `D:\Program Files\Android\Sdk\platform-tools\adb.exe` (always full path, via `$adb`).
- UI text in English. Empty message → title `Alarm`. App-created events last 15 minutes (`DTEND` for one-offs, `DURATION=PT15M` for recurring). "Off/done" = event colour key `"8"` (Graphite); "on" = no event colour.
- Defaults: snooze 30 min, auto-snooze after 1 min, show snooze button on, stop method three presses, sound+vibration, volume 100 %, increase device volume on, fade-in on (log curve ≈30 s), 24-hour on, dark theme on, next-alarm notification on.
- Scope `https://www.googleapis.com/auth/calendar.app.created` only. The REST API is used only to create the "Alarms" calendar (and clear its notifications).
- Decompiled Simple Alarm (`out\jadx\…`) and `research\VytenisNarmontas_CalendarAlarm` are read-only study material: never copy code from them.
- Test events go only into the "Alarms" calendar (or the throw-away "Alarms Spike" calendar in Task 2). Test alarms on the phone use low volume and are announced first; disruptive phone steps are pre-approved (see "Needs Mustafa" #4) — announce, run, restore.
- Task order: **Task 17 first (step 0)**, then Tasks 1–16, 18, 19.
- The debug commands `USE_LOCAL_CALENDAR` and `DELETE_ALL` (and the `check-*.ps1` scripts that use them) are **emulator-only**: never send them to the phone, where they would replace or wipe Mustafa's real alarms.
- Logcat tag `MustafaAlarm`; the app's EventLog file is `/data/user_de/0/com.atatuzun.mustafaalarm/files/event-log.txt` (read with `AppLog`).

## Decisions this plan adds where the spec is silent

1. **Device-protected storage for all phone data.** The Room database and the DataStore file live in device-protected storage, so `ring_cache`, `ringing_now`, `handled_instances` and settings are usable before first unlock (spec §5.4 lists ring_cache/ringing_now; the rest needs it too because Stop/Snooze can be pressed on a locked phone after Samsung's night-time auto-restart).
2. **InstanceKey = (`Instances.EVENT_ID`, `Instances.BEGIN`).** For one-offs and unmodified series occurrences this equals the spec's `(eventId, originalBeginMillis)`; a moved occurrence (exception event) is keyed by its own event id. `ringAt == key.begin` always.
3. **Pending actions.** Snooze/Tomorrow/Stop pressed while the calendar is unavailable (before first unlock) or when a provider write fails are stored in a `pending_actions` table, a temporary ring-cache entry keeps the alarm ringing at its new time, and the action is applied to the calendar on the next unlocked reschedule.
4. **Missed-alarm rule runs at every reschedule**, not only at boot/time change: any ring-cache entry whose time passed less than 60 minutes ago, never rang and still exists in the calendar rings immediately (superset of spec §6.3, same 60-minute cache-based limit, so events moved into the past from the PC still never ring).
5. **Snooze time is truncated to the minute** (`floor(pressedAt to minute) + snooze`). **Tomorrow** = the same wall-clock time as the occurrence that rang, one calendar day later (DST-safe).
6. **Saving an edited alarm turns it on.** **Delete asks for confirmation** (it also deletes the Google event). **Turning a series off also greys its existing exceptions.**
7. **Ringing screen:** the big Stop button follows the stop method (three presses by default); the per-row Stop (revealed by tapping one alarm) is a single press. The notification Stop is immediate (spec).
8. **"Default" sound** = the system default alarm sound; the bundled `res/raw/default_alarm.wav` (generated by our own script, Task 11) plays before first unlock or when the chosen sound cannot be opened.
9. **First run asks for the Android calendar permission before locating the "Alarms" calendar** (locating needs it). Permission steps after the calendar step have "Skip for now".
10. **Settings → Account shows "Last calendar update received HH:MM" and "Changes waiting to upload: N"** instead of a "last sync time" (Android exposes no per-calendar last-sync timestamp to apps).
11. **Quick relative alarms (5m…24h) do not count toward "Your frequent alarms"**; alarms created from the add screen and from the morning/frequent presets do.
12. **Debug-only command receiver** (`src/debug`, protected by `android.permission.DUMP`, so only adb can call it) lets Claude create/list/delete alarms and drive ringing actions from scripts.
13. **Ready for Android 17 when the phone gets it.** Android 17's background-audio hardening (applies to every app on Android 17) requires background playback to come from a non-short foreground service, and exempts `USAGE_ALARM` audio/volume changes for apps holding the exact-alarm permission. The ringer already plays `USAGE_ALARM` audio from a `mediaPlayback` foreground service started by an exact alarm and declares `USE_EXACT_ALARM`, so no change is needed; re-run Task 12's scenarios on the phone after that update.

## Review Focus

1. **Double presses** — Snooze tapped twice, or notification Stop and screen Stop at the same moment: the action must be applied once (one exception, one move). Test: Task 6 `snoozeTwice_sameKey_appliesOnce`.
2. **Events created on the PC with other lengths or time zones** (60-minute event, `EVENT_TIMEZONE=UTC`, `DURATION=P3600S`): grouping uses the phone's local day and snooze keeps the event's own length. Tests: Task 6 `snooze_keepsLengthOfPcEvent`, Task 7 `grouping_usesDeviceZone_notEventZone`, Task 3 `parsesProviderStyleDuration`.
3. **Clock set backwards / time change after an alarm was handled** — the same occurrence must not ring twice. Test: Task 4 `handledOccurrence_staysExcluded_whenClockMovesBackwards`.
4. **Messages with Turkish letters, emoji, newlines or 300+ characters** must survive storage and display. Tests: Task 5 `create_trimsMessage_keepsUnicode`, Task 9 `ringCache_keepsUnicodeTitles`.
5. **"Alarms" calendar deleted on the PC or calendar permission revoked** — cached alarms keep ringing and the list shows a banner instead of crashing. Tests: Task 6 `refreshRingCache_calendarMissing_keepsPreviousCache`, Task 7 `list_reportsMissingCalendar_andUnavailable`.

---

## File map

```
scripts/droid.ps1                      helpers for every command block (Task 1)
scripts/make_default_sound.py          generates res/raw/default_alarm.wav (Task 11)
scripts/ring-scenarios.ps1             ringing & reliability scenarios (Tasks 11–12, 19)
scripts/check-list.ps1 / check-edit.ps1 / check-quick.ps1 / check-settings.ps1   UI checks (Tasks 13–16)
notes/signing.md, notes/spike-results.md, notes/google-cloud.md, notes/test-report.md
mustafa-alarm/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew(.bat), gradle/wrapper/*
  keystore/mustafa-alarm.jks, keystore.properties            (git-ignored)
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/res/…                                          theme, colours, icons, raw sound
  app/src/main/java/com/atatuzun/mustafaalarm/
    MustafaAlarmApp.kt, AppGraph.kt                           app + manual DI (Task 10)
    domain/  Model.kt Times.kt WeeklyRule.kt Rfc5545Duration.kt FadeCurve.kt TimeEntry.kt Texts.kt
             QuickPresets.kt RingPlanner.kt CalendarAccess.kt LocalStore.kt AlarmStore.kt
             AlarmListBuilder.kt FrequentAlarms.kt                                   (Tasks 3–7, pure Kotlin)
    data/calendar/ ProviderCalendarAccess.kt CalendarSetupAccess.kt                  (Task 8)
    data/local/    Entities.kt LocalDao.kt AlarmDatabase.kt RoomLocalStore.kt         (Task 9)
    data/settings/ AlarmSettings.kt SettingsRepository.kt                            (Task 9)
    log/EventLog.kt                                                                  (Task 9)
    schedule/Scheduler.kt                                                            (Task 10)
    ring/ AlarmReceiver.kt Notifications.kt AlarmPlayer.kt RingingService.kt RingingState.kt RingingActivity.kt RingingScreen.kt (Tasks 10–11)
    watch/ SystemEventReceiver.kt CalendarChangeJob.kt SafetyCheckWorker.kt          (Tasks 2, 10)
    system/ReliabilityChecks.kt                                                      (Task 13)
    ui/ MainActivity.kt AppNav.kt Common.kt theme/Theme.kt list/* edit/* quick/* settings/* setup/*   (Tasks 13–18)
    google/ GoogleSetup.kt CalendarRestClient.kt TaskAwait.kt                        (Task 18)
  app/src/debug/AndroidManifest.xml, app/src/debug/java/…/debug/ DebugCommandReceiver.kt LocalCalendars.kt (Tasks 8, 10)
  app/src/test/java/…/domain/*Test.kt + fakes                                        (Tasks 1, 3–7)
  app/src/androidTest/java/…/ spike/CalendarSpikeTest.kt data/*Test.kt               (Tasks 2, 8, 9)
```

---

### Task 1: Branch, toolchain, signing and an installable shell app

**Files:**
- Create: `scripts/droid.ps1`, `notes/signing.md`
- Create: `mustafa-alarm/settings.gradle.kts`, `mustafa-alarm/build.gradle.kts`, `mustafa-alarm/gradle.properties`, `mustafa-alarm/gradle/libs.versions.toml`, `mustafa-alarm/gradle/wrapper/gradle-wrapper.properties` (+ copied `gradlew.bat`, `gradlew`, `gradle-wrapper.jar`), `mustafa-alarm/local.properties`
- Create: `mustafa-alarm/keystore/mustafa-alarm.jks`, `mustafa-alarm/keystore.properties` (git-ignored)
- Create: `mustafa-alarm/app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/atatuzun/mustafaalarm/ui/MainActivity.kt`
- Create: `app/src/main/res/values/{strings,colors,themes}.xml`, `res/drawable/{ic_alarm,ic_launcher_foreground}.xml`, `res/mipmap-anydpi/ic_launcher.xml`
- Test: `app/src/test/java/com/atatuzun/mustafaalarm/BuildSmokeTest.kt`
- SDK (outside the repo): `cmdline-tools;latest`, `system-images;android-36.1;google_apis;x86_64`, AVD `mustafa_alarm_36`

**Interfaces:**
- Consumes: nothing.
- Produces: the Gradle project every later task builds; `scripts/droid.ps1` helper functions (exact names listed in "How to run things"); version catalog aliases `libs.*` used by later tasks unchanged; `ui.MainActivity` (launcher activity, rewritten in Task 13).

- [ ] **Step 1: Create the branch**

```powershell
git checkout -b feature/mustafa-alarm
git status --short
```
Expected: `Switched to a new branch 'feature/mustafa-alarm'`, clean status.

- [ ] **Step 2: Create `scripts/droid.ps1`**

```powershell
# Dot-source from the workfolder root:  . .\scripts\droid.ps1
[Console]::OutputEncoding = [Text.Encoding]::UTF8   # adb output (Turkish letters, emoji, "·") decodes correctly
$Root = Split-Path $PSScriptRoot -Parent
$Proj = Join-Path $Root 'mustafa-alarm'
$Pkg = 'com.atatuzun.mustafaalarm'
$Phone = '<your-phone-serial>'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
$env:ANDROID_HOME = 'D:\Program Files\Android\Sdk'
$adb = 'D:\Program Files\Android\Sdk\platform-tools\adb.exe'
$emulatorExe = 'D:\Program Files\Android\Sdk\emulator\emulator.exe'
if (-not $env:DEVICE) { $env:DEVICE = 'emulator-5560' }

function Gradle {
    $env:ANDROID_SERIAL = $env:DEVICE
    & (Join-Path $Proj 'gradlew.bat') -p $Proj @args
    if ($LASTEXITCODE -ne 0) { throw "gradle failed: $args" }
}
function A { & $adb -s $env:DEVICE @args }

function Start-Emu([switch]$Cold) {
    if ((& $adb devices) -match 'emulator-5560\s+device') { return 'emulator already running' }
    $emuArgs = @('-avd', 'mustafa_alarm_36', '-port', '5560', '-no-boot-anim')
    if ($Cold) { $emuArgs += '-no-snapshot-load' }   # a real boot (BOOT_COMPLETED), not a snapshot restore
    Start-Process -FilePath $emulatorExe -ArgumentList $emuArgs -WindowStyle Minimized
    & $adb -s emulator-5560 wait-for-device
    Wait-Boot 'emulator-5560'
}
function Wait-Boot([string]$serial = $env:DEVICE) {
    for ($i = 0; $i -lt 120; $i++) {
        if ((& $adb -s $serial shell getprop sys.boot_completed 2>$null) -match '1') { Start-Sleep 5; return "$serial booted" }
        Start-Sleep 2
    }
    throw "$serial did not finish booting in 4 minutes"
}

function New-Evidence([string]$name) { $d = Join-Path $Root "logs\$name"; New-Item -ItemType Directory -Force $d | Out-Null; $d }
function Shot([string]$name) {
    $dir = New-Evidence 'shots'
    A shell screencap -p /sdcard/ma_shot.png | Out-Null
    A pull /sdcard/ma_shot.png (Join-Path $dir "$name.png") | Out-Null
    A shell rm /sdcard/ma_shot.png | Out-Null
    Join-Path $dir "$name.png"
}
function Shot-To([string]$dir, [string]$name) { Copy-Item (Shot $name) (Join-Path $dir "$name.png") -Force }

function UiXml {
    A shell uiautomator dump /sdcard/ma_ui.xml | Out-Null
    $local = Join-Path (New-Evidence 'ui') 'last-ui.xml'
    A pull /sdcard/ma_ui.xml $local | Out-Null
    [xml](Get-Content $local -Raw -Encoding UTF8)
}
function Find-Node([string]$text) { (UiXml).SelectNodes('//node') | Where-Object { $_.text -eq $text -or $_.'content-desc' -eq $text } | Select-Object -First 1 }
function Find-Id([string]$id) { (UiXml).SelectNodes('//node') | Where-Object { $_.'resource-id' -eq $id } | Select-Object -First 1 }
function Tap-Node($n) {
    $b = [regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    A shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2)) | Out-Null
    Start-Sleep -Milliseconds 800
}
function Tap-Text([string]$text) { $n = Find-Node $text; if (-not $n) { throw "not on screen: $text" }; Tap-Node $n }
function Tap-Id([string]$id) { $n = Find-Id $id; if (-not $n) { throw "no node with id: $id" }; Tap-Node $n }
function Assert-Text([string]$text) { if (-not (Find-Node $text)) { throw "expected on screen: $text" }; "ok: '$text' on screen" }
function Assert-TextLike([string]$pattern) {
    $hit = (UiXml).SelectNodes('//node') | Where-Object { $_.text -match $pattern } | Select-Object -First 1
    if (-not $hit) { throw "expected text matching /$pattern/ on screen" }; "ok: '$($hit.text)'"
}

function AppLog {
    $lines = @(A shell run-as $Pkg cat "/data/user_de/0/$Pkg/files/event-log.txt" 2>$null)
    if ($lines.Count -gt 0 -and $lines[0] -match '^\d{4}-\d\d-\d\d ') { return $lines }
    # Before the first unlock after a reboot run-as may fail: same messages from logcat.
    @(A logcat -d -v time -s 'MustafaAlarm:I')
}
$global:AppLogMark = 0
function Mark-AppLog { $global:AppLogMark = @(AppLog).Count }   # later waits only look at lines written after this
function Wait-AppLog([string]$pattern, [int]$seconds = 120) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $lines = @(AppLog)
        $start = if ($lines.Count -lt $global:AppLogMark) { 0 } else { $global:AppLogMark }   # rotated or logcat fallback
        $new = if ($lines.Count -gt $start) { $lines[$start..($lines.Count - 1)] -join "`n" } else { '' }
        if ($new -match $pattern) { return $Matches[0] }
        Start-Sleep 3
    }
    throw "timed out after $seconds s waiting for /$pattern/ in the app log"
}
function Last-CreatedId {
    $m = [regex]::Matches((AppLog | Out-String), 'created eventId=(\d+)')
    if ($m.Count -eq 0) { throw 'no created alarm in the app log' }
    [long]$m[$m.Count - 1].Groups[1].Value
}
function Debug-Cmd([string]$action, [string[]]$extra = @()) {
    A shell am broadcast -n "$Pkg/.debug.DebugCommandReceiver" -a "$Pkg.debug.$action" @extra | Out-Null
    Start-Sleep -Milliseconds 1500
}
function Grant-All {
    foreach ($p in 'READ_CALENDAR', 'WRITE_CALENDAR', 'POST_NOTIFICATIONS') { A shell pm grant $Pkg "android.permission.$p" | Out-Null }
    A shell dumpsys deviceidle whitelist "+$Pkg" | Out-Null
    A shell appops set $Pkg USE_FULL_SCREEN_INTENT allow | Out-Null
}
function Open-App {
    A shell input keyevent KEYCODE_WAKEUP | Out-Null
    A shell wm dismiss-keyguard | Out-Null
    # NEW_TASK|CLEAR_TASK: fresh activity on the start screen, without killing the process (force-stop would cancel alarms)
    A shell am start -n "$Pkg/.ui.MainActivity" -f 0x10008000 | Out-Null
    Start-Sleep 3
}
function Instrument([string]$target) {
    $out = A shell am instrument -w -e class $target "$Pkg.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-String
    $out
    if ($out -notmatch 'OK \(\d+ test') { throw "instrumented tests failed: $target" }
}
function Commit([string]$subject, [string]$Session) {
    $trailer = 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>'
    if ($Session) { $trailer += "`nClaude-Session: $Session" }
    git -C $Root commit -m $subject -m $trailer
}
```

- [ ] **Step 3: Write the failing smoke test** — `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/BuildSmokeTest.kt`

```kotlin
package com.atatuzun.mustafaalarm

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildSmokeTest {
    @Test
    fun applicationIdIsMustafaAlarm() {
        assertEquals("com.atatuzun.mustafaalarm", BuildConfig.APPLICATION_ID)
    }
}
```

- [ ] **Step 4: Gradle wrapper and root build files**

```powershell
. .\scripts\droid.ps1
New-Item -ItemType Directory -Force "$Proj\gradle\wrapper" | Out-Null
Copy-Item 'C:\Users\musta\crm\mobile\android\gradlew.bat' $Proj
if (Test-Path 'C:\Users\musta\crm\mobile\android\gradlew') { Copy-Item 'C:\Users\musta\crm\mobile\android\gradlew' $Proj }
Copy-Item 'C:\Users\musta\crm\mobile\android\gradle\wrapper\gradle-wrapper.jar' "$Proj\gradle\wrapper\"
Set-Content -Encoding ascii "$Proj\local.properties" 'sdk.dir=D\:\\Program Files\\Android\\Sdk'
```

`mustafa-alarm/gradle/wrapper/gradle-wrapper.properties`:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-all.zip
```

`mustafa-alarm/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "mustafa-alarm"
include(":app")
```

`mustafa-alarm/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

`mustafa-alarm/gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
# Same switches as C:\Users\musta\crm\mobile\android (AGP 9.1.0 + Kotlin 2.4.0, proven on this PC)
android.builtInKotlin=false
android.newDsl=false
kotlin.incremental=false
# Web OAuth client ID used as serverClientId for Sign in with Google (filled in Task 17)
mustafaAlarm.serverClientId=
```

`mustafa-alarm/gradle/libs.versions.toml`:
```toml
[versions]
agp = "9.1.0"
kotlin = "2.4.0"
ksp = "2.3.12"
composeBom = "2026.09.00"
coreKtx = "1.19.1"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
navigation = "2.10.2"
room = "2.8.5"
work = "2.12.0"
datastore = "1.2.1"
credentials = "1.6.0"
googleid = "1.2.1"
playServicesAuth = "22.0.0"
coroutines = "1.11.0"
junit = "4.13.2"
androidxTestExt = "1.3.0"
androidxTestRunner = "1.7.0"
androidxTestRules = "1.7.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
androidx-work-runtime = { group = "androidx.work", name = "work-runtime", version.ref = "work" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
androidx-credentials = { group = "androidx.credentials", name = "credentials", version.ref = "credentials" }
androidx-credentials-play-services-auth = { group = "androidx.credentials", name = "credentials-play-services-auth", version.ref = "credentials" }
googleid = { group = "com.google.android.libraries.identity.googleid", name = "googleid", version.ref = "googleid" }
play-services-auth = { group = "com.google.android.gms", name = "play-services-auth", version.ref = "playServicesAuth" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestExt" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "androidxTestRules" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 5: Create the signing key and record its SHA-1**

```powershell
. .\scripts\droid.ps1
$keytool = "$env:JAVA_HOME\bin\keytool.exe"
New-Item -ItemType Directory -Force "$Proj\keystore" | Out-Null
$pw = [guid]::NewGuid().ToString('N')
& $keytool -genkeypair -v -keystore "$Proj\keystore\mustafa-alarm.jks" -alias mustafa-alarm -keyalg RSA -keysize 2048 -validity 36500 -storepass $pw -keypass $pw -dname 'CN=Mustafa Atatuzun, O=Personal, C=CY'
Set-Content -Encoding ascii "$Proj\keystore.properties" "storeFile=keystore/mustafa-alarm.jks`nstorePassword=$pw`nkeyAlias=mustafa-alarm`nkeyPassword=$pw"
& $keytool -list -v -keystore "$Proj\keystore\mustafa-alarm.jks" -alias mustafa-alarm -storepass $pw | Select-String 'SHA1:|SHA256:'
git -C $Root check-ignore -v "mustafa-alarm/keystore/mustafa-alarm.jks" "mustafa-alarm/keystore.properties"
```
Expected: two fingerprint lines; `check-ignore` prints a `.gitignore` rule for both files (if it prints nothing, add `mustafa-alarm/keystore/` and `mustafa-alarm/keystore.properties` to `.gitignore` before continuing).

Write `notes/signing.md` with the SHA-1 and SHA-256 lines from the output:
```markdown
# Mustafa Alarm signing key

- Keystore: `mustafa-alarm/keystore/mustafa-alarm.jks` (alias `mustafa-alarm`), passwords in `mustafa-alarm/keystore.properties` — both git-ignored. Back both up (Mustafa).
- SHA-1: <paste the SHA1 value>
- SHA-256: <paste the SHA256 value>
- Losing the keystore means: uninstall/reinstall the app and create a new Android OAuth client with the new SHA-1.
```
(The two `<paste …>` markers are filled with the literal keytool output in this step; they are data, not open work.)

- [ ] **Step 6: App module** — `mustafa-alarm/app/build.gradle.kts`

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.atatuzun.mustafaalarm"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.atatuzun.mustafaalarm"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val serverClientId = providers.gradleProperty("mustafaAlarm.serverClientId").getOrElse("")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$serverClientId\"")
    }

    signingConfigs {
        create("project") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore/mustafa-alarm.jks"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("project")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
```

- [ ] **Step 7: Manifest, resources, launcher activity**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.MustafaAlarm">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Mustafa Alarm</string>
</resources>
```

`res/values/colors.xml`:
```xml
<resources>
    <color name="background">#FF0D1117</color>
    <color name="ic_launcher_background">#FF1A56C4</color>
</resources>
```

`res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.MustafaAlarm" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>
```

`res/drawable/ic_alarm.xml` (Material "alarm" glyph, Apache-2.0):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM12.5,8L11,8v6l4.75,2.85 0.75,-1.23 -4,-2.37L12.5,8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z" />
</vector>
```

`res/drawable/ic_launcher_foreground.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="2.5" android:scaleY="2.5" android:translateX="24" android:translateY="24">
        <path android:fillColor="#FFFFFFFF"
            android:pathData="M22,5.72l-4.6,-3.86 -1.29,1.53 4.6,3.86L22,5.72zM7.88,3.39L6.6,1.86 2,5.71l1.29,1.53 4.59,-3.85zM12.5,8L11,8v6l4.75,2.85 0.75,-1.23 -4,-2.37L12.5,8zM12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z" />
    </group>
</vector>
```

`res/mipmap-anydpi/ic_launcher.xml`:
```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`app/src/main/java/com/atatuzun/mustafaalarm/ui/MainActivity.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Mustafa Alarm", style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 8: Build and run the smoke test**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.BuildSmokeTest" :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`, 1 test passed.
Contingencies (only if the matching error appears, then re-run this step and note the change in `notes/build-notes.md`):
- `Failed to find target with hash string 'android-37'` → in `app/build.gradle.kts` replace `compileSdk = 37` with `compileSdkVersion = "android-37.0"`.
- A KSP/Kotlin version-compatibility error → set `ksp` in `libs.versions.toml` to the newest version listed at `https://repo1.maven.org/maven2/com/google/devtools/ksp/com.google.devtools.ksp.gradle.plugin/maven-metadata.xml`.

- [ ] **Step 9: Create the Android 16 emulator (same API level as the phone)**

The SDK has no command-line tools yet; install them, the Android 16 QPR2 image and the AVD. The download goes to `out\downloads\` (workfolder, git-ignored).
```powershell
. .\scripts\droid.ps1
$sdk = $env:ANDROID_HOME
$dl = New-Item -ItemType Directory -Force (Join-Path $Root 'out\downloads')
$zip = Join-Path $dl 'commandlinetools-win-16111833_latest.zip'
Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip' -OutFile $zip
Expand-Archive $zip -DestinationPath (Join-Path $dl 'cmdline-tools-unzipped') -Force
New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
Move-Item (Join-Path $dl 'cmdline-tools-unzipped\cmdline-tools') "$sdk\cmdline-tools\latest"
$sdkmanager = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$avdmanager = "$sdk\cmdline-tools\latest\bin\avdmanager.bat"
("y`n" * 30) | & $sdkmanager --licenses | Out-Null
& $sdkmanager 'system-images;android-36.1;google_apis;x86_64' 'emulator' 'platform-tools'
'no' | & $avdmanager create avd -n mustafa_alarm_36 -k 'system-images;android-36.1;google_apis;x86_64' -d pixel_7
Start-Emu
A shell getprop ro.build.version.sdk_full
```
Expected: the last line prints `36.1` (same as the phone). If `sdkmanager` reports that the image needs a newer emulator, the `'emulator'` package in the same command already updates it; re-run `Start-Emu`.

- [ ] **Step 10: Install on the emulator and check signature + launch**

```powershell
. .\scripts\droid.ps1
Start-Emu
Gradle :app:installDebug
Open-App
Assert-Text 'Mustafa Alarm'
Shot 'task01-hello'
& 'D:\Program Files\Android\Sdk\build-tools\36.0.0\apksigner.bat' verify --print-certs "$Proj\app\build\outputs\apk\debug\app-debug.apk" | Select-String 'SHA-1'
```
Expected: `ok: 'Mustafa Alarm' on screen`; the apksigner SHA-1 equals the one in `notes/signing.md` (apksigner prints it without colons, lower-case — compare ignoring those).

- [ ] **Step 11: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add scripts/droid.ps1 notes/signing.md mustafa-alarm/settings.gradle.kts mustafa-alarm/build.gradle.kts mustafa-alarm/gradle.properties mustafa-alarm/gradle mustafa-alarm/gradlew.bat mustafa-alarm/app
if (Test-Path "$Proj\gradlew") { git -C $Root add mustafa-alarm/gradlew }
git -C $Root status --short
Commit "build: scaffold Mustafa Alarm Android project with project signing key"
```
Expected: no `.jks`, `keystore.properties`, `local.properties` or `build/` paths in the status list.

### Task 2: Spike — verify the calendar-provider risks on the real phone (spec §13 risks 1–4)

This task de-risks the whole design before any alarm code exists. It uses a throw-away Google calendar **"Alarms Spike"** (deleted in Task 19) so the real "Alarms" calendar is still created by the app in Task 18. **Gate:** if risk 1, 2, 3 or 4 fails, stop and bring the result to Mustafa (spec §13 fallback = Calendar REST API, which needs his decision).

**Files:**
- Modify: `mustafa-alarm/app/src/main/AndroidManifest.xml` (calendar permissions, job service)
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/watch/CalendarChangeJob.kt` (minimal; completed in Task 10)
- Test: `mustafa-alarm/app/src/androidTest/java/com/atatuzun/mustafaalarm/spike/CalendarSpikeTest.kt`
- Create: `notes/spike-results.md`

**Interfaces:**
- Consumes: Task 1 project and `droid.ps1`.
- Produces: `CalendarChangeJob.schedule(context: Context)` and `CalendarChangeJob.JOB_ID = 1001` (Task 10 keeps both names). Spike findings that Tasks 8, 10, 18 rely on (column writability, colour key `"8"`, exception URIs, trigger latency).

- [ ] **Step 1: Write the spike test** — `app/src/androidTest/java/com/atatuzun/mustafaalarm/spike/CalendarSpikeTest.kt`

```kotlin
package com.atatuzun.mustafaalarm.spike

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.atatuzun.mustafaalarm.watch.CalendarChangeJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CalendarSpikeTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val account = InstrumentationRegistry.getArguments().getString("account") ?: "your-google-account@gmail.com"
    private val zone = ZoneId.systemDefault()
    private val tomorrow = LocalDate.now(zone).plusDays(1)

    private data class Cal(val id: Long, val syncId: String?, val syncEvents: Int, val visible: Int)

    @Test
    fun risk0_calendarReachesPhone() {
        val deadline = System.currentTimeMillis() + 180_000
        var cal = findSpikeCalendar()
        while (cal == null && System.currentTimeMillis() < deadline) {
            requestSync(); Thread.sleep(5_000); cal = findSpikeCalendar()
        }
        report("risk0.calendar", cal)
        assertNotNull("'$SPIKE_NAME' never appeared in the provider", cal)
    }

    @Test
    fun risk1_appCanEnableSyncAndVisibility() {
        val cal = findSpikeCalendar() ?: error("run risk0 first")
        report("risk1.before", cal)
        val values = ContentValues().apply { put(Calendars.SYNC_EVENTS, 1); put(Calendars.VISIBLE, 1) }
        val rows = resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, cal.id), values, null, null)
        val after = findSpikeCalendar()
        report("risk1.after", "$after rows=$rows")
        assertEquals(1, rows)
        assertEquals(1, after!!.syncEvents)
        assertEquals(1, after.visible)
    }

    @Test
    fun risk2and4_writeEvents() {
        val cal = findSpikeCalendar() ?: error("run risk0 first")
        val oneOffStart = millis(tomorrow, 9)
        val oneOff = insert(cal.id, "spike one-off", oneOffStart, oneOffStart + 15 * MIN, null, null)
        val colourRows = resolver.update(eventUri(oneOff), ContentValues().apply { put(Events.EVENT_COLOR_KEY, "8") }, null, null)
        report("risk2.oneOff", "id=$oneOff colourRows=$colourRows")

        val seriesStart = millis(tomorrow, 10)
        val series = insert(cal.id, "spike series", seriesStart, null, "FREQ=DAILY;COUNT=5", "PT15M")
        requestSync()
        report("risk4.series", "id=$series syncId=${waitForSyncId(series)}")
        val second = millis(tomorrow.plusDays(1), 10)
        val moved = insertException(series, ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, second); put(Events.DTSTART, second + 30 * MIN); put(Events.DTEND, second + 45 * MIN)
        })
        val third = millis(tomorrow.plusDays(2), 10)
        val cancelled = insertException(series, ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, third); put(Events.STATUS, Events.STATUS_CANCELED)
        })
        report("risk4.exceptions", "moved=$moved cancelled=$cancelled")

        // A series that gets an exception before its first upload (offline snooze case).
        val freshStart = millis(tomorrow, 11)
        val fresh = insert(cal.id, "spike unsynced series", freshStart, null, "FREQ=DAILY;COUNT=3", "PT15M")
        val freshMoved = insertException(fresh, ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, freshStart); put(Events.DTSTART, freshStart + 30 * MIN); put(Events.DTEND, freshStart + 45 * MIN)
        })
        report("risk4.unsynced", "series=$fresh moved=$freshMoved")
        requestSync()

        val begins = instanceBegins(cal.id, millis(tomorrow, 0), millis(tomorrow.plusDays(6), 0))
            .filter { it.first == "spike series" }.map { it.second }
        report("risk4.localInstances", begins.joinToString { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().toString() })
        assertEquals(
            listOf(seriesStart, second + 30 * MIN, millis(tomorrow.plusDays(3), 10), millis(tomorrow.plusDays(4), 10)),
            begins,
        )
    }

    @Test
    fun risk2and3_readBack() {
        val cal = findSpikeCalendar() ?: error("run risk0 first")
        val projection = arrayOf(
            Events._ID, Events.TITLE, Events.DTSTART, Events.EVENT_COLOR_KEY, Events.STATUS,
            Events._SYNC_ID, Events.DIRTY, Events.ORIGINAL_ID, Events.DELETED,
        )
        resolver.query(Events.CONTENT_URI, projection, "${Events.CALENDAR_ID}=?", arrayOf(cal.id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val start = Instant.ofEpochMilli(c.getLong(2)).atZone(zone).toLocalDateTime()
                report("event", "id=${c.getLong(0)} title='${c.getString(1)}' start=$start colorKey=${c.getString(3)} status=${c.getString(4)} syncId=${c.getString(5)} dirty=${c.getInt(6)} originalId=${c.getString(7)} deleted=${c.getInt(8)}")
            }
        }
    }

    @Test
    fun risk3_scheduleChangeJob() {
        CalendarChangeJob.schedule(context)
        report("risk3.jobScheduled", true)
    }

    private fun report(key: String, value: Any?) = Log.i(TAG, "$key=$value")
    private fun millis(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun eventUri(id: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, id)

    private fun findSpikeCalendar(): Cal? = resolver.query(
        Calendars.CONTENT_URI,
        arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.SYNC_EVENTS, Calendars.VISIBLE),
        "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CALENDAR_DISPLAY_NAME}=?",
        arrayOf(account, "com.google", SPIKE_NAME), null,
    )?.use { c -> if (c.moveToFirst()) Cal(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3)) else null }

    private fun requestSync() {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(Account(account, "com.google"), CalendarContract.AUTHORITY, extras)
    }

    private fun insert(calId: Long, title: String, start: Long, end: Long?, rrule: String?, duration: String?): Long {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, title)
            put(Events.EVENT_TIMEZONE, zone.id)
            put(Events.DTSTART, start)
            if (end != null) put(Events.DTEND, end)
            if (rrule != null) put(Events.RRULE, rrule)
            if (duration != null) put(Events.DURATION, duration)
            put(Events.HAS_ALARM, 0)
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
        }
        return ContentUris.parseId(resolver.insert(Events.CONTENT_URI, values)!!)
    }

    private fun insertException(seriesId: Long, values: ContentValues): Long =
        ContentUris.parseId(resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, seriesId), values)!!)

    private fun waitForSyncId(eventId: Long): String? {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val syncId = resolver.query(eventUri(eventId), arrayOf(Events._SYNC_ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (syncId != null) return syncId
            requestSync(); Thread.sleep(5_000)
        }
        return null
    }

    private fun instanceBegins(calId: Long, from: Long, to: Long): List<Pair<String, Long>> {
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        return resolver.query(
            uri, arrayOf(Instances.TITLE, Instances.BEGIN, Instances.STATUS),
            "${Instances.CALENDAR_ID}=?", arrayOf(calId.toString()), "${Instances.BEGIN} ASC",
        )?.use { c ->
            buildList { while (c.moveToNext()) if (c.getInt(2) != Events.STATUS_CANCELED) add(c.getString(0) to c.getLong(1)) }
        } ?: emptyList()
    }

    companion object {
        const val TAG = "MustafaSpike"
        const val SPIKE_NAME = "Alarms Spike"
        const val MIN = 60_000L
    }
}
```

- [ ] **Step 2: Build to see it fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:assembleDebugAndroidTest
```
Expected: FAIL — `Unresolved reference 'CalendarChangeJob'`.

- [ ] **Step 3: Minimal change job + manifest**

`app/src/main/java/com/atatuzun/mustafaalarm/watch/CalendarChangeJob.kt`:
```kotlin
package com.atatuzun.mustafaalarm.watch

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import android.util.Log

/** Runs whenever the calendar provider changes (our writes or the Google sync adapter). */
class CalendarChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Log.i("MustafaAlarm", "calendar change job ran: ${params.triggeredContentUris?.joinToString() ?: "-"}")
        schedule(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        const val JOB_ID = 1001

        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.Events.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1_000)
                .setTriggerContentMaxDelay(5_000)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
```

In `AndroidManifest.xml` add before `<application …>`:
```xml
    <uses-permission android:name="android.permission.READ_CALENDAR" />
    <uses-permission android:name="android.permission.WRITE_CALENDAR" />
```
and inside `<application>` after the activity:
```xml
        <service
            android:name=".watch.CalendarChangeJob"
            android:exported="false"
            android:permission="android.permission.BIND_JOB_SERVICE" />
```

- [ ] **Step 4: Build passes**

```powershell
. .\scripts\droid.ps1
Gradle :app:assembleDebug :app:assembleDebugAndroidTest
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Create the "Alarms Spike" calendar in Google Calendar (Chrome)**

Invoke the `claude-in-chrome` skill. Tell Mustafa: "I'm creating a throw-away calendar 'Alarms Spike' in your Google Calendar; I'll delete it at the end." In a new tab open `https://calendar.google.com/calendar/u/0/r/settings/createcalendar`, type `Alarms Spike` as the name, press **Create calendar**. Screenshot to `logs\spike\create-calendar.png` (via the extension screenshot). Then confirm with `mcp__claude_ai_Google_Calendar__list_calendars`: an entry with summary `Alarms Spike` exists; record its `id` in `notes/spike-results.md`.

- [ ] **Step 6: Risks 0 and 1 on the phone**

Tell Mustafa: "Installing Mustafa Alarm (no alarms yet) and running calendar tests on your phone." Then:
```powershell
. .\scripts\droid.ps1
$env:DEVICE = $Phone
Gradle :app:installDebug :app:installDebugAndroidTest
A logcat -c
Instrument 'com.atatuzun.mustafaalarm.spike.CalendarSpikeTest#risk0_calendarReachesPhone'
Instrument 'com.atatuzun.mustafaalarm.spike.CalendarSpikeTest#risk1_appCanEnableSyncAndVisibility'
A logcat -d -s MustafaSpike:I | Tee-Object (Join-Path (New-Evidence 'spike') 'risk0-1.txt')
```
Expected: both `OK (1 test)`; log shows `risk1.after=Cal(... syncEvents=1, visible=1) rows=1`. **Risk 1 = PASS** only if both assertions held.

- [ ] **Step 7: Risks 2 and 4 — phone → Google**

```powershell
. .\scripts\droid.ps1
$env:DEVICE = $Phone
A logcat -c
Instrument 'com.atatuzun.mustafaalarm.spike.CalendarSpikeTest#risk2and4_writeEvents'
A logcat -d -s MustafaSpike:I | Tee-Object (Join-Path (New-Evidence 'spike') 'risk2-4-write.txt')
```
Expected: `OK (1 test)`, `risk4.series=… syncId=<non-null>`. Wait 60 s, then call `mcp__claude_ai_Google_Calendar__list_events` for the "Alarms Spike" calendar id from tomorrow 00:00 to tomorrow+6 days (expanded single events) and save the JSON response to `logs\spike\google-after-write.json`. Check:
- "spike one-off" exists tomorrow 09:00 with `colorId` `"8"` → **risk 2 (phone→Google) PASS**.
- "spike series": tomorrow 10:00, day+1 **10:30**, no day+2 occurrence, day+3 10:00, day+4 10:00 → **risk 4 PASS**.
- "spike unsynced series": first occurrence at 11:30 (record PASS/FAIL separately as "risk 4b"; a 4b failure is noted, not a gate — Task 6's pending/fallback logic still holds).

- [ ] **Step 8: Risks 2 (Google→phone) and 3 — content trigger latency**

```powershell
. .\scripts\droid.ps1
$env:DEVICE = $Phone
Instrument 'com.atatuzun.mustafaalarm.spike.CalendarSpikeTest#risk3_scheduleChangeJob'
A shell dumpsys jobscheduler | Select-String 'CalendarChangeJob' | Select-Object -First 3
A logcat -c
Get-Date -Format 'HH:mm:ss.fff'
```
Immediately after printing the time (T0), with the Google Calendar tools: `update_event` on "spike one-off" → start tomorrow 09:45 (end 10:00) and colour back to default (remove `colorId`); `create_event` "spike from pc" in the spike calendar tomorrow 12:00–12:15 with `colorId` `"8"`. Then poll:
```powershell
. .\scripts\droid.ps1
$env:DEVICE = $Phone
for ($i = 0; $i -lt 60; $i++) { $l = A logcat -d -v time -s MustafaAlarm:I | Out-String; if ($l -match 'calendar change job ran') { $l; break }; Start-Sleep 5 }
A logcat -c
Instrument 'com.atatuzun.mustafaalarm.spike.CalendarSpikeTest#risk2and3_readBack'
A logcat -d -s MustafaSpike:I | Tee-Object (Join-Path (New-Evidence 'spike') 'readback.txt')
```
Repeat the read-back every 30 s (max 10 min) until "spike one-off" shows `start=…T09:45 colorKey=null` and "spike from pc" shows `colorKey=8`. **Risk 3 PASS** if a `calendar change job ran` line appears after T0 (latency = its timestamp − T0). **Risk 2 (Google→phone) PASS** if the read-back matches. Record both latencies.

- [ ] **Step 9: Write `notes/spike-results.md` and apply the gate**

```markdown
# Spike results (spec §13) — <date>

| Risk | Result | Evidence |
|---|---|---|
| 1 App can set SYNC_EVENTS/VISIBLE | PASS/FAIL | logs/spike/risk0-1.txt |
| 2 Graphite colour key 8 phone→Google | PASS/FAIL | logs/spike/google-after-write.json |
| 2 Colour/move Google→phone | PASS/FAIL, latency N s | logs/spike/readback.txt |
| 3 Content-trigger job fires on sync-adapter writes | PASS/FAIL, latency N s | logcat excerpt |
| 4 Exceptions (move + cancel) reach Google | PASS/FAIL | logs/spike/google-after-write.json |
| 4b Exception on a not-yet-synced series | PASS/FAIL | same |

"Alarms Spike" calendar id: <id> (delete in Task 19)
```
If any of 1, 2, 3, 4 is FAIL: stop here, show Mustafa this table and the spec §13 fallback, and wait for his decision.

- [ ] **Step 10: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add mustafa-alarm/app/src/main mustafa-alarm/app/src/androidTest notes/spike-results.md
Commit "test: spike calendar-provider risks on the real phone"
```

### Task 3: Domain model and time math (pure Kotlin)

**Files** (all under `mustafa-alarm/app/src/`):
- Create: `main/java/com/atatuzun/mustafaalarm/domain/{Model,Times,WeeklyRule,Rfc5545Duration,FadeCurve,TimeEntry,Texts,QuickPresets}.kt`
- Test: `test/java/com/atatuzun/mustafaalarm/domain/{TestSupport,ModelTest,TimesTest,WeeklyRuleTest,Rfc5545DurationTest,FadeCurveTest,TimeEntryTest,TextsTest,QuickPresetsTest}.kt`

**Interfaces:**
- Consumes: nothing (no Android imports anywhere in `domain/`).
- Produces (exact names later tasks use):
  - Constants `GRAPHITE_COLOR_KEY = "8"`, `STATUS_CANCELED = 2`, `DEFAULT_TITLE = "Alarm"`, `DEFAULT_LENGTH_MINUTES = 15L`, `MINUTE`, `HOUR`, `DAY` (Long millis).
  - `data class InstanceKey(eventId: Long, begin: Long)`
  - `data class EventRow(id, title, dtStart, dtEnd: Long?, duration: String?, rrule: String?, allDay, colorKey: String?, status: Int?, originalId: Long?, originalInstanceTime: Long?, timeZone: String?)` with `isSeries`, `isException`, `isOff`, `isCanceled`, `lengthMillis`.
  - `data class InstanceRow(eventId, begin, end, title, allDay, colorKey: String?, status: Int?, rrule: String?, originalId: Long?)` with `key`, `alarmId`, `isActive`.
  - `data class CachedOccurrence(key: InstanceKey, alarmId: Long, title: String)` with `ringAt`; `data class RingingEntry(key, alarmId, title, startedAt)` with `ringAt`; `enum RingAction { SNOOZE, TOMORROW, STOP }`; `data class PendingAction(id, key, action, pressedAt)`.
  - `sealed interface EventTiming { Single(start, end); Recurring(start, rrule, duration) }`, `enum ColorPatch { GRAPHITE, DEFAULT }`, `data class EventPatch(title, timing, zone, color, canceled)`.
  - `Times.floorMinute/localDate/localTime/at/startOfDay/plusDays/nextAt/firstWeeklyStart`, `WeeklyRule.build/parse/dayOf`, `Rfc5545Duration.parseMillis/ofMillis`, `FadeCurve.volumeAt`, `TimeEntry.push/pop/parse/display`, `Texts.clock/dayLabel/untilNext/weekdays/relative`, `QuickPresets.relativeMinutes/morning/relativeTarget`.
  - Test helpers `ZONE` (Asia/Famagusta), `t("2026-10-02T09:00")`, `TestClock`.

- [ ] **Step 1: Write the failing tests**

`test/java/com/atatuzun/mustafaalarm/domain/TestSupport.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Mustafa's zone. It follows EU summer time (ends 2026-10-25 04:00 → 03:00). */
val ZONE: ZoneId = ZoneId.of("Asia/Famagusta")

/** Epoch millis of a local date-time in [ZONE], e.g. t("2026-10-02T09:00"). */
fun t(local: String): Long = LocalDateTime.parse(local).atZone(ZONE).toInstant().toEpochMilli()

class TestClock(var now: Long, private val zoneId: ZoneId = ZONE) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)
    override fun instant(): Instant = Instant.ofEpochMilli(now)
    fun set(local: String) { now = t(local) }
}
```

`ModelTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private val base = EventRow(1, "a", 0, null, null, null, false, null, null, null, null, null)

    @Test
    fun lengthMillis_prefersDtEnd_thenDuration_thenDefault() {
        assertEquals(15 * MINUTE, base.lengthMillis)
        assertEquals(HOUR, base.copy(dtEnd = HOUR).lengthMillis)
        assertEquals(HOUR, base.copy(rrule = "FREQ=DAILY", duration = "P3600S").lengthMillis)
    }

    @Test
    fun eventFlags() {
        assertTrue(base.copy(rrule = "FREQ=DAILY").isSeries)
        assertTrue(base.copy(originalId = 9).isException)
        assertTrue(base.copy(colorKey = GRAPHITE_COLOR_KEY).isOff)
        assertTrue(base.copy(status = STATUS_CANCELED).isCanceled)
        assertFalse(base.isSeries || base.isException || base.isOff || base.isCanceled)
    }

    @Test
    fun instanceActivityAndOwner() {
        val i = InstanceRow(5, 100, 200, "a", false, null, null, null, 2)
        assertEquals(InstanceKey(5, 100), i.key)
        assertEquals(2L, i.alarmId)
        assertEquals(5L, i.copy(originalId = null).alarmId)
        assertTrue(i.isActive)
        assertFalse(i.copy(colorKey = GRAPHITE_COLOR_KEY).isActive)
        assertFalse(i.copy(status = STATUS_CANCELED).isActive)
        assertFalse(i.copy(allDay = true).isActive)
    }

    @Test
    fun ringAtIsTheOccurrenceBegin() {
        assertEquals(100L, CachedOccurrence(InstanceKey(1, 100), 1, "a").ringAt)
        assertEquals(100L, RingingEntry(InstanceKey(1, 100), 1, "a", 5).ringAt)
    }
}
```

`TimesTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime

class TimesTest {
    // 2026-10-02 is a Friday.
    @Test fun floorMinute_dropsSecondsAndMillis() =
        assertEquals(t("2026-10-02T09:14"), Times.floorMinute(t("2026-10-02T09:14") + 59_999))

    @Test fun nextAt_laterToday() =
        assertEquals(t("2026-10-02T11:00"), Times.nextAt(LocalTime.of(11, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun nextAt_alreadyPassed_isTomorrow() =
        assertEquals(t("2026-10-03T09:00"), Times.nextAt(LocalTime.of(9, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun nextAt_exactlyNow_isTomorrow() =
        assertEquals(t("2026-10-03T10:00"), Times.nextAt(LocalTime.of(10, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun plusDays_keepsWallClock_acrossDstEnd() {
        val before = t("2026-10-24T08:00")
        val after = Times.plusDays(before, 1, ZONE)
        assertEquals(t("2026-10-25T08:00"), after)
        assertEquals(25 * HOUR, after - before)
    }

    @Test fun firstWeeklyStart_picksNextMatchingDay() =
        assertEquals(t("2026-10-05T08:00"), Times.firstWeeklyStart(setOf(MONDAY, WEDNESDAY), LocalTime.of(8, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun firstWeeklyStart_todayWhenStillAhead() =
        assertEquals(t("2026-10-02T11:00"), Times.firstWeeklyStart(setOf(FRIDAY), LocalTime.of(11, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun firstWeeklyStart_nextWeekWhenTodayPassed() =
        assertEquals(t("2026-10-09T09:00"), Times.firstWeeklyStart(setOf(FRIDAY), LocalTime.of(9, 0), t("2026-10-02T10:00"), ZONE))

    @Test fun dayParts() {
        assertEquals(t("2026-10-02T00:00"), Times.startOfDay(t("2026-10-02T17:45"), ZONE))
        assertEquals(LocalTime.of(17, 45), Times.localTime(t("2026-10-02T17:45") + 30_000, ZONE))
        assertEquals(LocalDate.of(2026, 10, 2), Times.localDate(t("2026-10-02T23:59"), ZONE))
        assertEquals(t("2026-10-02T07:30"), Times.at(LocalDate.of(2026, 10, 2), LocalTime.of(7, 30, 45), ZONE))
    }
}
```

`WeeklyRuleTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY

class WeeklyRuleTest {
    @Test fun build_ordersMondayFirst() =
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", WeeklyRule.build(setOf(FRIDAY, MONDAY, WEDNESDAY)))

    @Test fun parse_plainWeekly() = assertEquals(setOf(MONDAY, WEDNESDAY), WeeklyRule.parse("FREQ=WEEKLY;BYDAY=MO,WE"))

    @Test fun parse_acceptsPrefixWkstAndIntervalOne() {
        assertEquals(setOf(TUESDAY), WeeklyRule.parse("RRULE:FREQ=WEEKLY;WKST=SU;BYDAY=TU"))
        assertEquals(setOf(SUNDAY), WeeklyRule.parse("FREQ=WEEKLY;INTERVAL=1;BYDAY=SU"))
    }

    @Test fun parse_rejectsEverythingElse() {
        listOf(
            null, "", "FREQ=DAILY", "FREQ=WEEKLY", "FREQ=WEEKLY;BYDAY=1MO", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO",
            "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T000000Z", "FREQ=WEEKLY;BYDAY=MO;COUNT=4", "FREQ=MONTHLY;BYMONTHDAY=1",
        ).forEach { assertNull(it, WeeklyRule.parse(it)) }
    }

    @Test fun dayOf_mapsCodes() {
        assertEquals(SATURDAY, WeeklyRule.dayOf("sa"))
        assertNull(WeeklyRule.dayOf("XX"))
    }
}
```

`Rfc5545DurationTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.domain.Rfc5545Duration.ofMillis
import com.atatuzun.mustafaalarm.domain.Rfc5545Duration.parseMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Rfc5545DurationTest {
    @Test fun parsesRfcStyle() {
        assertEquals(15 * MINUTE, parseMillis("PT15M"))
        assertEquals(HOUR, parseMillis("PT1H"))
        assertEquals(90 * MINUTE, parseMillis("PT1H30M"))
        assertEquals(DAY, parseMillis("P1D"))
        assertEquals(7 * DAY, parseMillis("P1W"))
    }

    @Test fun parsesProviderStyleDuration() {
        assertEquals(15 * MINUTE, parseMillis("P900S"))
        assertEquals(HOUR, parseMillis("P3600S"))
    }

    @Test fun rejectsGarbage() {
        listOf("", "P", "PT", "15M", "PTXM", "-PT15M", "PT0M").forEach { assertNull(it, parseMillis(it)) }
    }

    @Test fun formats() {
        assertEquals("PT15M", ofMillis(15 * MINUTE))
        assertEquals("PT60M", ofMillis(HOUR))
        assertEquals("PT90S", ofMillis(90_500))
    }
}
```

`FadeCurveTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class FadeCurveTest {
    @Test fun startsQuiet_endsFull() {
        assertEquals(0.05f, FadeCurve.volumeAt(0), 0.0001f)
        assertEquals(1f, FadeCurve.volumeAt(30_000), 0f)
        assertEquals(1f, FadeCurve.volumeAt(60_000), 0f)
    }

    @Test fun risesMonotonically() {
        val v = (0..60).map { FadeCurve.volumeAt(it * 500L) }
        assertTrue(v.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun logShape() = assertEquals(log10(5.5).toFloat(), FadeCurve.volumeAt(15_000), 0.001f)
}
```

`TimeEntryTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class TimeEntryTest {
    @Test fun acceptsOnlyDigitsThatCanStillMakeATime() {
        assertEquals("", TimeEntry.push("", '7'))
        assertEquals("0", TimeEntry.push("", '0'))
        assertEquals("2", TimeEntry.push("2", '4'))
        assertEquals("23", TimeEntry.push("2", '3'))
        assertEquals("23", TimeEntry.push("23", '6'))
        assertEquals("235", TimeEntry.push("23", '5'))
        assertEquals("2359", TimeEntry.push("235", '9'))
        assertEquals("2359", TimeEntry.push("2359", '1'))
    }

    @Test fun parse() {
        assertEquals(LocalTime.of(7, 30), TimeEntry.parse("0730"))
        assertEquals(LocalTime.of(7, 30), TimeEntry.parse("073"))
        assertEquals(LocalTime.of(7, 0), TimeEntry.parse("07"))
        assertNull(TimeEntry.parse("0"))
        assertNull(TimeEntry.parse(""))
    }

    @Test fun displayAndPop() {
        assertEquals("07:3_", TimeEntry.display("073"))
        assertEquals("__:__", TimeEntry.display(""))
        assertEquals("07", TimeEntry.pop("073"))
    }
}
```

`TextsTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime

class TextsTest {
    private val today = LocalDate.of(2026, 10, 2) // Friday

    @Test fun dayLabels() {
        assertEquals("Today", Texts.dayLabel(today, today))
        assertEquals("Tomorrow", Texts.dayLabel(today.plusDays(1), today))
        assertEquals("Monday", Texts.dayLabel(today.plusDays(3), today))
        assertEquals("Mon 12 Oct 2026", Texts.dayLabel(today.plusDays(10), today))
        assertEquals("Thu 1 Oct 2026", Texts.dayLabel(today.minusDays(1), today))
    }

    @Test fun untilNext() {
        val now = t("2026-10-02T09:14")
        assertEquals("Next alarm in 17 minutes", Texts.untilNext(now + 17 * MINUTE, now))
        assertEquals("Next alarm in 1 minute", Texts.untilNext(now + 30_000, now))
        assertEquals("Next alarm in 2 h 5 min", Texts.untilNext(now + 125 * MINUTE, now))
        assertEquals("Next alarm in 2 h", Texts.untilNext(now + 120 * MINUTE, now))
        assertEquals("Next alarm in 3 days", Texts.untilNext(now + 3 * DAY, now))
        assertEquals("Next alarm now", Texts.untilNext(now - 5_000, now))
    }

    @Test fun clocks() {
        assertEquals("07:05", Texts.clock(LocalTime.of(7, 5), true))
        assertEquals("7:05 AM", Texts.clock(LocalTime.of(7, 5), false))
        assertEquals("19:30", Texts.clock(t("2026-10-02T19:30"), ZONE, true))
    }

    @Test fun weekdaysAndRelative() {
        assertEquals("Mon Wed", Texts.weekdays(setOf(WEDNESDAY, MONDAY)))
        assertEquals("5m", Texts.relative(5))
        assertEquals("1h", Texts.relative(60))
        assertEquals("24h", Texts.relative(1440))
    }
}
```

`QuickPresetsTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class QuickPresetsTest {
    @Test fun presetsMatchTheSpec() {
        assertEquals(listOf(5, 15, 30, 45, 60, 120, 240, 480, 720, 1440), QuickPresets.relativeMinutes)
        assertEquals(listOf("05:30", "06:00", "06:30", "07:00", "07:30", "08:00").map(LocalTime::parse), QuickPresets.morning)
    }

    @Test fun relativeTarget_countsFromTheCurrentMinute() =
        assertEquals(t("2026-10-02T09:19"), QuickPresets.relativeTarget(5, t("2026-10-02T09:14") + 40_000))
}
```

- [ ] **Step 2: Run to see them fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.*"
```
Expected: FAIL — compilation errors (`Unresolved reference 'Times'`, `'EventRow'`, …).

- [ ] **Step 3: Implement**

`main/java/com/atatuzun/mustafaalarm/domain/Model.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

const val GRAPHITE_COLOR_KEY = "8"          // Google event colour "Graphite" = alarm off / done
const val STATUS_CANCELED = 2               // CalendarContract.Events.STATUS_CANCELED
const val DEFAULT_TITLE = "Alarm"
const val DEFAULT_LENGTH_MINUTES = 15L
const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

/** One occurrence: the Instances row's EVENT_ID and BEGIN. */
data class InstanceKey(val eventId: Long, val begin: Long)

/** One row of CalendarContract.Events in the "Alarms" calendar. */
data class EventRow(
    val id: Long,
    val title: String,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val rrule: String?,
    val allDay: Boolean,
    val colorKey: String?,
    val status: Int?,
    val originalId: Long?,
    val originalInstanceTime: Long?,
    val timeZone: String?,
) {
    val isSeries: Boolean get() = !rrule.isNullOrBlank()
    val isException: Boolean get() = originalId != null
    val isOff: Boolean get() = colorKey == GRAPHITE_COLOR_KEY
    val isCanceled: Boolean get() = status == STATUS_CANCELED

    val lengthMillis: Long
        get() = when {
            dtEnd != null && dtEnd > dtStart -> dtEnd - dtStart
            duration != null -> Rfc5545Duration.parseMillis(duration) ?: DEFAULT_LENGTH_MINUTES * MINUTE
            else -> DEFAULT_LENGTH_MINUTES * MINUTE
        }
}

/** One row of CalendarContract.Instances (an occurrence joined with its event). */
data class InstanceRow(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val allDay: Boolean,
    val colorKey: String?,
    val status: Int?,
    val rrule: String?,
    val originalId: Long?,
) {
    val key: InstanceKey get() = InstanceKey(eventId, begin)

    /** The alarm this occurrence belongs to: the series for a moved occurrence, else the event itself. */
    val alarmId: Long get() = originalId ?: eventId

    /** Would ring: timed, not cancelled, not Graphite. */
    val isActive: Boolean get() = !allDay && status != STATUS_CANCELED && colorKey != GRAPHITE_COLOR_KEY
}

/** An occurrence the phone has promised to ring (ring_cache row). */
data class CachedOccurrence(val key: InstanceKey, val alarmId: Long, val title: String) {
    val ringAt: Long get() = key.begin
}

/** An occurrence that is ringing now (ringing_now row). */
data class RingingEntry(val key: InstanceKey, val alarmId: Long, val title: String, val startedAt: Long) {
    val ringAt: Long get() = key.begin
}

enum class RingAction { SNOOZE, TOMORROW, STOP }

data class PendingAction(val id: Long, val key: InstanceKey, val action: RingAction, val pressedAt: Long)

/** When an event happens. A Single has DTEND; a Recurring has RRULE + DURATION. */
sealed interface EventTiming {
    val start: Long

    data class Single(override val start: Long, val end: Long) : EventTiming
    data class Recurring(override val start: Long, val rrule: String, val duration: String) : EventTiming
}

enum class ColorPatch { GRAPHITE, DEFAULT }

/** A change to an event; null fields are left untouched. */
data class EventPatch(
    val title: String? = null,
    val timing: EventTiming? = null,
    val zone: String? = null,
    val color: ColorPatch? = null,
    val canceled: Boolean = false,
)
```

`Times.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object Times {
    fun floorMinute(millis: Long): Long = millis - Math.floorMod(millis, MINUTE)

    fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun localTime(millis: Long, zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().withSecond(0).withNano(0)

    fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        ZonedDateTime.of(date, time.withSecond(0).withNano(0), zone).toInstant().toEpochMilli()

    fun startOfDay(millis: Long, zone: ZoneId): Long =
        localDate(millis, zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Same wall-clock time [days] later (DST-safe). */
    fun plusDays(millis: Long, days: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).plusDays(days).toInstant().toEpochMilli()

    /** The next moment strictly after [now] whose wall-clock time is [time]. */
    fun nextAt(time: LocalTime, now: Long, zone: ZoneId): Long {
        val today = localDate(now, zone)
        val candidate = at(today, time, zone)
        return if (candidate > now) candidate else at(today.plusDays(1), time, zone)
    }

    /** The first moment strictly after [now] that falls on one of [days] at [time]. */
    fun firstWeeklyStart(days: Set<DayOfWeek>, time: LocalTime, now: Long, zone: ZoneId): Long {
        require(days.isNotEmpty()) { "no weekdays selected" }
        val today = localDate(now, zone)
        return (0L..7L).asSequence()
            .map { today.plusDays(it) }
            .filter { it.dayOfWeek in days }
            .map { at(it, time, zone) }
            .first { it > now }
    }
}
```

`WeeklyRule.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.DayOfWeek

/** The only recurrence the edit screen creates: RRULE FREQ=WEEKLY;BYDAY=… */
object WeeklyRule {
    private val codes = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )
    private val allowedKeys = setOf("FREQ", "BYDAY", "WKST", "INTERVAL")

    fun build(days: Set<DayOfWeek>): String =
        "FREQ=WEEKLY;BYDAY=" + DayOfWeek.entries.filter { it in days }.joinToString(",") { codes.getValue(it) }

    fun dayOf(code: String): DayOfWeek? = codes.entries.firstOrNull { it.value == code.trim().uppercase() }?.key

    /** Weekdays of a plain weekly rule; null for anything the edit screen cannot represent. */
    fun parse(rrule: String?): Set<DayOfWeek>? {
        if (rrule.isNullOrBlank()) return null
        val parts = rrule.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate {
            val kv = it.split('=', limit = 2)
            kv[0].trim().uppercase() to kv.getOrElse(1) { "" }.trim().uppercase()
        }
        if (parts["FREQ"] != "WEEKLY" || parts.keys.any { it !in allowedKeys }) return null
        if ((parts["INTERVAL"] ?: "1") != "1") return null
        val byDay = parts["BYDAY"] ?: return null
        val days = byDay.split(',').map { dayOf(it) ?: return null }.toSet()
        return days.ifEmpty { null }
    }
}
```

`Rfc5545Duration.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

/** RFC 5545 DURATION values, plus the provider's "P900S" form (seconds without the T). */
object Rfc5545Duration {
    private val pattern = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")

    fun parseMillis(text: String): Long? {
        val match = pattern.matchEntire(text.trim().uppercase()) ?: return null
        val (sign, w, d, h, m, s) = match.destructured
        if (sign == "-" || listOf(w, d, h, m, s).all { it.isEmpty() }) return null
        val seconds = w.num() * 604_800 + d.num() * 86_400 + h.num() * 3_600 + m.num() * 60 + s.num()
        return if (seconds > 0) seconds * 1_000 else null
    }

    fun ofMillis(millis: Long): String =
        if (millis % MINUTE == 0L) "PT${millis / MINUTE}M" else "PT${millis / 1_000}S"

    private fun String.num(): Long = toLongOrNull() ?: 0L
}
```

`FadeCurve.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import kotlin.math.log10

/** Fade-in: a log curve from 5 % to 100 % of the target volume over 30 seconds. */
object FadeCurve {
    const val DURATION_MS = 30_000L
    private const val START = 0.05f

    fun volumeAt(elapsedMs: Long, durationMs: Long = DURATION_MS): Float {
        if (elapsedMs >= durationMs) return 1f
        if (elapsedMs <= 0) return START
        val x = elapsedMs.toDouble() / durationMs
        return maxOf(START, log10(1 + 9 * x).toFloat())
    }
}
```

`TimeEntry.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.LocalTime

/** Number-pad time entry: digits fill HH then MM ("0730" → 07:30). */
object TimeEntry {
    fun push(digits: String, digit: Char): String {
        if (!digit.isDigit() || digits.length >= 4) return digits
        val next = digits + digit
        return if (canBecomeValid(next)) next else digits
    }

    fun pop(digits: String): String = digits.dropLast(1)

    fun parse(digits: String): LocalTime? {
        if (digits.length < 2 || !canBecomeValid(digits)) return null
        val hour = digits.substring(0, 2).toInt()
        val minute = (digits.getOrNull(2)?.digitToInt() ?: 0) * 10 + (digits.getOrNull(3)?.digitToInt() ?: 0)
        return LocalTime.of(hour, minute)
    }

    fun display(digits: String): String {
        val padded = digits.padEnd(4, '_')
        return "${padded.substring(0, 2)}:${padded.substring(2, 4)}"
    }

    private fun canBecomeValid(d: String): Boolean = when (d.length) {
        0 -> true
        1 -> d[0] in '0'..'2'
        2 -> d.toInt() <= 23
        else -> d.substring(0, 2).toInt() <= 23 && d[2] in '0'..'5'
    }
}
```

`Texts.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** English UI strings that depend on time. */
object Texts {
    private val h24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val h12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val longDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

    fun clock(time: LocalTime, use24h: Boolean): String = time.format(if (use24h) h24 else h12)

    fun clock(millis: Long, zone: ZoneId, use24h: Boolean): String = clock(Times.localTime(millis, zone), use24h)

    fun dayLabel(day: LocalDate, today: LocalDate): String = when {
        day == today -> "Today"
        day == today.plusDays(1) -> "Tomorrow"
        day > today && day < today.plusDays(7) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        else -> day.format(longDate)
    }

    fun untilNext(next: Long, now: Long): String {
        val minutes = ((next - now).coerceAtLeast(0) + MINUTE - 1) / MINUTE
        val rest = when {
            minutes == 0L -> "now"
            minutes == 1L -> "in 1 minute"
            minutes < 60 -> "in $minutes minutes"
            minutes < 48 * 60 -> if (minutes % 60 == 0L) "in ${minutes / 60} h" else "in ${minutes / 60} h ${minutes % 60} min"
            else -> "in ${minutes / (24 * 60)} days"
        }
        return "Next alarm $rest"
    }

    fun weekdays(days: Set<DayOfWeek>): String =
        DayOfWeek.entries.filter { it in days }.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }

    fun relative(minutes: Int): String = if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"
}
```

`QuickPresets.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.LocalTime

object QuickPresets {
    val relativeMinutes = listOf(5, 15, 30, 45, 60, 120, 240, 480, 720, 1440)
    val morning: List<LocalTime> = listOf("05:30", "06:00", "06:30", "07:00", "07:30", "08:00").map(LocalTime::parse)

    fun relativeTarget(minutes: Int, now: Long): Long = Times.floorMinute(now) + minutes * MINUTE
}
```

- [ ] **Step 4: Run to see them pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.*"
```
Expected: `BUILD SUCCESSFUL`, all domain tests pass.

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain mustafa-alarm/app/src/test
Commit "feat(domain): alarm model, time math, weekly rules and UI texts"
```

### Task 4: RingPlanner — what to cache, when to fire, what rings

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/RingPlanner.kt`
- Test: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/RingPlannerTest.kt`

**Interfaces:**
- Consumes: Task 3 `InstanceRow`, `InstanceKey`, `CachedOccurrence`, `Times`, `MINUTE`/`DAY`, test helpers `t()`.
- Produces:
  - `RingPlanner.CACHE_LIMIT = 100`, `MISSED_WINDOW = 60 * MINUTE`, `HORIZON = 14 * DAY`
  - `RingPlanner.planCache(active: List<InstanceRow>, previous: List<CachedOccurrence>, handled: Set<InstanceKey>, ringing: Set<InstanceKey>, now: Long): List<CachedOccurrence>`
  - `RingPlanner.planCacheLocked(previous: List<CachedOccurrence>, handled: Set<InstanceKey>, ringing: Set<InstanceKey>, now: Long): List<CachedOccurrence>`
  - `RingPlanner.nextTrigger(cache: List<CachedOccurrence>): Long?` (a past value means "fire now")
  - `RingPlanner.due(cache: List<CachedOccurrence>, handled: Set<InstanceKey>, ringing: Set<InstanceKey>, now: Long): List<CachedOccurrence>`

- [ ] **Step 1: Write the failing tests** — `RingPlannerTest.kt`

```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingPlannerTest {
    private val now = t("2026-10-02T10:00") + 20_000 // 10:00:20

    private fun inst(
        id: Long, begin: Long, colorKey: String? = null, status: Int? = null, allDay: Boolean = false, originalId: Long? = null,
    ) = InstanceRow(id, begin, begin + 15 * MINUTE, "a$id", allDay, colorKey, status, null, originalId)

    private fun cached(id: Long, begin: Long) = CachedOccurrence(InstanceKey(id, begin), id, "a$id")

    @Test
    fun upcoming_isSorted_andSkipsInactiveHandledAndRinging() {
        val rows = listOf(
            inst(1, t("2026-10-02T12:00")),
            inst(2, t("2026-10-02T11:00")),
            inst(3, t("2026-10-02T11:30"), colorKey = GRAPHITE_COLOR_KEY),
            inst(4, t("2026-10-02T11:40"), status = STATUS_CANCELED),
            inst(5, t("2026-10-02T11:50"), allDay = true),
            inst(6, t("2026-10-02T13:00")),
            inst(7, t("2026-10-02T14:00")),
        )
        val cache = RingPlanner.planCache(
            rows, emptyList(),
            handled = setOf(InstanceKey(6, t("2026-10-02T13:00"))),
            ringing = setOf(InstanceKey(7, t("2026-10-02T14:00"))),
            now = now,
        )
        assertEquals(listOf(2L, 1L), cache.map { it.key.eventId })
    }

    @Test
    fun currentMinute_isUpcoming_earlierMinutesNeverEnterTheCache() {
        val rows = listOf(inst(1, t("2026-10-02T10:00")), inst(2, t("2026-10-02T09:59")))
        val cache = RingPlanner.planCache(rows, emptyList(), emptySet(), emptySet(), now)
        assertEquals(listOf(1L), cache.map { it.key.eventId })
    }

    @Test
    fun missed_keepsRecentUnrungEntriesThatStillExist() {
        val recent = inst(1, t("2026-10-02T09:30"))
        val tooOld = inst(2, t("2026-10-02T08:59"))
        val stopped = inst(3, t("2026-10-02T09:40"))
        val deletedOnPc = cached(4, t("2026-10-02T09:45"))
        val previous = listOf(cached(1, recent.begin), cached(2, tooOld.begin), cached(3, stopped.begin), deletedOnPc)
        val cache = RingPlanner.planCache(listOf(recent, tooOld, stopped), previous, setOf(stopped.key), emptySet(), now)
        assertEquals(listOf(recent.key), cache.map { it.key })
    }

    @Test
    fun horizon_keepsTwoWeeks_orElseTheSingleNextOne() {
        val near = inst(1, t("2026-10-10T08:00"))
        val far = inst(2, t("2026-10-20T08:00"))
        val farther = inst(3, t("2026-11-20T08:00"))
        assertEquals(listOf(1L), RingPlanner.planCache(listOf(near, far), emptyList(), emptySet(), emptySet(), now).map { it.key.eventId })
        assertEquals(listOf(2L), RingPlanner.planCache(listOf(farther, far), emptyList(), emptySet(), emptySet(), now).map { it.key.eventId })
    }

    @Test
    fun cache_isCappedAt100() {
        val rows = (1..150L).map { inst(it, t("2026-10-02T11:00") + it * MINUTE) }
        assertEquals(100, RingPlanner.planCache(rows, emptyList(), emptySet(), emptySet(), now).size)
    }

    @Test
    fun movedOccurrence_carriesItsSeriesAsAlarmId() {
        val c = RingPlanner.planCache(listOf(inst(9, t("2026-10-02T11:00"), originalId = 4)), emptyList(), emptySet(), emptySet(), now).single()
        assertEquals(4L, c.alarmId)
        assertEquals(InstanceKey(9, t("2026-10-02T11:00")), c.key)
    }

    @Test
    fun nextTrigger_isTheEarliest_evenWhenInThePast() {
        assertEquals(t("2026-10-02T09:30"), RingPlanner.nextTrigger(listOf(cached(1, t("2026-10-02T11:00")), cached(2, t("2026-10-02T09:30")))))
        assertNull(RingPlanner.nextTrigger(emptyList()))
    }

    @Test
    fun due_ringsTheWholeMinuteTogether_plusMissedOnes() {
        val eight = t("2026-10-02T08:00")
        val cache = listOf(
            cached(1, eight), cached(2, eight), cached(3, eight), cached(4, eight),
            cached(5, eight + MINUTE), cached(6, eight - 30 * MINUTE), cached(7, eight - 61 * MINUTE),
        )
        val due = RingPlanner.due(cache, emptySet(), emptySet(), eight + 200)
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L), due.map { it.key.eventId })
    }

    @Test
    fun due_skipsHandledAndRinging() {
        val eight = t("2026-10-02T08:00")
        val cache = listOf(cached(1, eight), cached(2, eight), cached(3, eight))
        val due = RingPlanner.due(cache, setOf(InstanceKey(1, eight)), setOf(InstanceKey(2, eight)), eight + 500)
        assertEquals(listOf(3L), due.map { it.key.eventId })
    }

    @Test
    fun lockedPlan_keepsUnhandledRecentAndFutureEntries() {
        val previous = listOf(
            cached(1, now - 30 * MINUTE), cached(2, now - 61 * MINUTE), cached(3, now + HOUR), cached(4, now + 2 * HOUR),
        )
        val plan = RingPlanner.planCacheLocked(previous, setOf(InstanceKey(4, now + 2 * HOUR)), emptySet(), now)
        assertEquals(listOf(1L, 3L), plan.map { it.key.eventId })
    }

    @Test
    fun handledOccurrence_staysExcluded_whenClockMovesBackwards() {
        val eight = inst(1, t("2026-10-02T08:00"))
        val clockSetBack = t("2026-10-02T07:55")
        assertTrue(RingPlanner.planCache(listOf(eight), emptyList(), setOf(eight.key), emptySet(), clockSetBack).isEmpty())
        assertTrue(RingPlanner.due(listOf(cached(1, eight.begin)), setOf(eight.key), emptySet(), eight.begin).isEmpty())
    }
}
```

- [ ] **Step 2: Run to see them fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.RingPlannerTest"
```
Expected: FAIL — `Unresolved reference 'RingPlanner'`.

- [ ] **Step 3: Implement** — `domain/RingPlanner.kt`

```kotlin
package com.atatuzun.mustafaalarm.domain

/**
 * Pure scheduling rules (spec §6.3, §7):
 * - the ring cache holds the next ≤100 active occurrences within 14 days (or the single next one beyond),
 *   plus occurrences that should have rung in the last 60 minutes, never rang and still exist;
 * - AlarmManager is armed for the earliest cached time;
 * - when it fires, everything cached up to the end of the current minute rings together.
 */
object RingPlanner {
    const val CACHE_LIMIT = 100
    const val MISSED_WINDOW = 60 * MINUTE
    const val HORIZON = 14 * DAY

    fun planCache(
        active: List<InstanceRow>,
        previous: List<CachedOccurrence>,
        handled: Set<InstanceKey>,
        ringing: Set<InstanceKey>,
        now: Long,
    ): List<CachedOccurrence> {
        val minute = Times.floorMinute(now)
        val activeRows = active.filter { it.isActive }
        val activeKeys = activeRows.mapTo(HashSet()) { it.key }
        val upcomingAll = activeRows.asSequence()
            .filter { it.begin >= minute && it.key !in handled && it.key !in ringing }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.begin }, { it.eventId }))
            .map { CachedOccurrence(it.key, it.alarmId, it.title) }
            .toList()
        val withinHorizon = upcomingAll.filter { it.ringAt < now + HORIZON }
        val upcoming = withinHorizon.ifEmpty { upcomingAll.take(1) }.take(CACHE_LIMIT)
        val missed = previous.filter {
            it.ringAt >= now - MISSED_WINDOW && it.ringAt < minute &&
                it.key !in handled && it.key !in ringing && it.key in activeKeys
        }
        return (missed.sortedBy { it.ringAt } + upcoming).distinctBy { it.key }
    }

    /** Before first unlock the calendar is unreadable: keep what was promised, minus what was handled. */
    fun planCacheLocked(
        previous: List<CachedOccurrence>,
        handled: Set<InstanceKey>,
        ringing: Set<InstanceKey>,
        now: Long,
    ): List<CachedOccurrence> =
        previous.filter { it.ringAt >= now - MISSED_WINDOW && it.key !in handled && it.key !in ringing }
            .sortedBy { it.ringAt }

    fun nextTrigger(cache: List<CachedOccurrence>): Long? = cache.minOfOrNull { it.ringAt }

    fun due(
        cache: List<CachedOccurrence>,
        handled: Set<InstanceKey>,
        ringing: Set<InstanceKey>,
        now: Long,
    ): List<CachedOccurrence> {
        val minuteEnd = Times.floorMinute(now) + MINUTE
        return cache.filter {
            it.ringAt < minuteEnd && it.ringAt >= now - MISSED_WINDOW && it.key !in handled && it.key !in ringing
        }
    }
}
```

- [ ] **Step 4: Run to see them pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.RingPlannerTest"
```
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/RingPlanner.kt mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/RingPlannerTest.kt
Commit "feat(domain): ring cache, missed-alarm and same-minute rules"
```

### Task 5: AlarmStore — create, edit, delete, on/off (with fakes)

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/{CalendarAccess,LocalStore,AlarmStore}.kt`
- Create (test fakes): `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/{FakeCalendarAccess,InMemoryLocalStore}.kt`
- Test: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/AlarmStoreEditTest.kt`

**Interfaces:**
- Consumes: Task 3 model, `Times`, `WeeklyRule`, `Rfc5545Duration`, `TestClock`, `t()`.
- Produces:
  - `interface CalendarAccess { calendarExists(calendarId): Boolean; instances(calendarId, from, to): List<InstanceRow>; events(calendarId): List<EventRow>; event(eventId): EventRow?; insertEvent(calendarId, title, timing: EventTiming, zone: String): Long; updateEvent(eventId, patch: EventPatch); deleteEvent(eventId); insertException(seriesId, originalInstanceTime, patch: EventPatch): Long }`
  - `interface LocalStore { handledKeys(): Set<InstanceKey>; markHandled(key, action: RingAction, at: Long); pruneHandled(before: Long); soundFor(alarmId): String?; setSound(alarmId, uri: String?); recordCreation(minuteOfDay: Int, at: Long); creationHistory(since: Long): List<Pair<Int, Long>>; ringCache(): List<CachedOccurrence>; replaceRingCache(items); addToRingCache(item); ringing(): List<RingingEntry>; addRinging(entries); removeRinging(keys: Collection<InstanceKey>); pendingActions(): List<PendingAction>; addPending(key, action, pressedAt); removePending(id) }`
  - `enum AlarmKind { ONE_OFF, WEEKLY, OTHER_REPEAT }`, `data class AlarmInput(time: LocalTime, date: LocalDate?, days: Set<DayOfWeek>, message: String, soundUri: String?)`, `sealed SaveResult { Saved(eventId, start); TimeInPast; NoCalendar; Missing }`, `data class AlarmDetails(eventId, time, date, days, kind, message, soundUri, on)`.
  - `class AlarmStore(calendar: CalendarAccess, local: LocalStore, clock: java.time.Clock, calendarId: () -> Long?, snoozeMinutes: () -> Int, calendarAvailable: () -> Boolean, log: (String) -> Unit = {})` with `create(input, recordHistory = true): SaveResult`, `details(eventId): AlarmDetails?`, `update(eventId, input): SaveResult`, `delete(eventId)`, `setEnabled(eventId, on)`. Log line on create: `created eventId=<id> '<title>'` (scripts parse `eventId=`).
  - Test fakes `FakeCalendarAccess` (`CAL = 1L`, `events`, `exists`, `failWrites`) and `InMemoryLocalStore` (`handled`, `sounds`, `history`, `cache`, `ringingNow`, `pending`), plus top-level `EventRow.patched(EventPatch)`.

- [ ] **Step 1: Write the fakes and the failing tests**

`test/java/com/atatuzun/mustafaalarm/domain/FakeCalendarAccess.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** In-memory "Alarms" calendar; expands one-offs, weekly/daily series and exceptions the way the provider does. */
class FakeCalendarAccess(private val defaultZone: ZoneId = ZONE) : CalendarAccess {
    val events = linkedMapOf<Long, EventRow>()
    var exists = true
    var failWrites = false
    private var nextId = 100L

    override fun calendarExists(calendarId: Long) = exists && calendarId == CAL
    override fun events(calendarId: Long): List<EventRow> = events.values.toList()
    override fun event(eventId: Long): EventRow? = events[eventId]

    override fun insertEvent(calendarId: Long, title: String, timing: EventTiming, zone: String): Long {
        checkWritable()
        val id = nextId++
        events[id] = EventRow(id, title, timing.start, null, null, null, false, null, null, null, null, zone).withTiming(timing)
        return id
    }

    override fun updateEvent(eventId: Long, patch: EventPatch) {
        checkWritable()
        events[eventId]?.let { events[eventId] = it.patched(patch) }
    }

    override fun deleteEvent(eventId: Long) {
        checkWritable()
        events.remove(eventId)
        events.values.removeAll { it.originalId == eventId }
    }

    override fun insertException(seriesId: Long, originalInstanceTime: Long, patch: EventPatch): Long {
        checkWritable()
        val series = events.getValue(seriesId)
        val id = nextId++
        val base = EventRow(
            id, series.title, originalInstanceTime, originalInstanceTime + series.lengthMillis, null, null, false,
            series.colorKey, null, seriesId, originalInstanceTime, series.timeZone,
        )
        events[id] = base.patched(patch)
        return id
    }

    override fun instances(calendarId: Long, from: Long, to: Long): List<InstanceRow> {
        val out = mutableListOf<InstanceRow>()
        for (e in events.values) {
            when {
                e.isCanceled -> Unit
                e.isException || !e.isSeries ->
                    if (e.dtStart < to && e.dtStart + e.lengthMillis > from) out += e.instanceAt(e.dtStart)
                else -> {
                    val replaced = events.values.filter { it.originalId == e.id }.mapNotNull { it.originalInstanceTime }.toSet()
                    occurrences(e, to).filter { it + e.lengthMillis > from && it !in replaced }.forEach { out += e.instanceAt(it) }
                }
            }
        }
        return out.sortedWith(compareBy({ it.begin }, { it.eventId }))
    }

    private fun EventRow.instanceAt(begin: Long) =
        InstanceRow(id, begin, begin + lengthMillis, title, allDay, colorKey, status, rrule, originalId)

    private fun occurrences(e: EventRow, to: Long): Sequence<Long> {
        val zone = e.timeZone?.let(ZoneId::of) ?: defaultZone
        val start = Instant.ofEpochMilli(e.dtStart).atZone(zone)
        val rule = e.rrule!!
        val count = Regex("COUNT=(\\d+)").find(rule)?.groupValues?.get(1)?.toInt() ?: Int.MAX_VALUE
        val days = WeeklyRule.parse(rule)
        val dates = generateSequence(start.toLocalDate()) { it.plusDays(1) }
        val matching = when {
            days != null -> dates.filter { it.dayOfWeek in days }
            rule.contains("FREQ=DAILY") -> dates
            else -> error("FakeCalendarAccess supports weekly and daily rules only: $rule")
        }
        return matching.map { ZonedDateTime.of(it, start.toLocalTime(), zone).toInstant().toEpochMilli() }
            .filter { it >= e.dtStart }.take(count).takeWhile { it < to }
    }

    private fun checkWritable() {
        if (failWrites) throw IllegalStateException("provider write failed (test)")
    }

    companion object {
        const val CAL = 1L
    }
}

private fun EventRow.withTiming(t: EventTiming): EventRow = when (t) {
    is EventTiming.Single -> copy(dtStart = t.start, dtEnd = t.end, rrule = null, duration = null)
    is EventTiming.Recurring -> copy(dtStart = t.start, dtEnd = null, rrule = t.rrule, duration = t.duration)
}

fun EventRow.patched(p: EventPatch): EventRow {
    var e = this
    p.title?.let { e = e.copy(title = it) }
    p.timing?.let { e = e.withTiming(it) }
    p.zone?.let { e = e.copy(timeZone = it) }
    when (p.color) {
        ColorPatch.GRAPHITE -> e = e.copy(colorKey = GRAPHITE_COLOR_KEY)
        ColorPatch.DEFAULT -> e = e.copy(colorKey = null)
        null -> Unit
    }
    if (p.canceled) e = e.copy(status = STATUS_CANCELED)
    return e
}
```

`test/java/com/atatuzun/mustafaalarm/domain/InMemoryLocalStore.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

class InMemoryLocalStore : LocalStore {
    val handled = linkedMapOf<InstanceKey, Pair<RingAction, Long>>()
    val sounds = mutableMapOf<Long, String>()
    val history = mutableListOf<Pair<Int, Long>>()
    var cache: List<CachedOccurrence> = emptyList()
    val ringingNow = linkedMapOf<InstanceKey, RingingEntry>()
    val pending = mutableListOf<PendingAction>()
    private var nextPendingId = 1L

    override fun handledKeys(): Set<InstanceKey> = handled.keys.toSet()
    override fun markHandled(key: InstanceKey, action: RingAction, at: Long) { handled[key] = action to at }
    override fun pruneHandled(before: Long) { handled.keys.removeAll { it.begin < before } }
    override fun soundFor(alarmId: Long): String? = sounds[alarmId]
    override fun setSound(alarmId: Long, uri: String?) { if (uri == null) sounds.remove(alarmId) else sounds[alarmId] = uri }
    override fun recordCreation(minuteOfDay: Int, at: Long) { history += minuteOfDay to at }
    override fun creationHistory(since: Long): List<Pair<Int, Long>> = history.filter { it.second >= since }
    override fun ringCache(): List<CachedOccurrence> = cache
    override fun replaceRingCache(items: List<CachedOccurrence>) { cache = items }
    override fun addToRingCache(item: CachedOccurrence) { cache = (cache.filter { it.key != item.key } + item).sortedBy { it.ringAt } }
    override fun ringing(): List<RingingEntry> = ringingNow.values.toList()
    override fun addRinging(entries: List<RingingEntry>) { entries.forEach { ringingNow[it.key] = it } }
    override fun removeRinging(keys: Collection<InstanceKey>) { keys.forEach { ringingNow.remove(it) } }
    override fun pendingActions(): List<PendingAction> = pending.sortedBy { it.pressedAt }
    override fun addPending(key: InstanceKey, action: RingAction, pressedAt: Long) { pending += PendingAction(nextPendingId++, key, action, pressedAt) }
    override fun removePending(id: Long) { pending.removeAll { it.id == id } }
}
```

`test/java/com/atatuzun/mustafaalarm/domain/AlarmStoreEditTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.domain.FakeCalendarAccess.Companion.CAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime

class AlarmStoreEditTest {
    private val clock = TestClock(t("2026-10-02T10:00")) // Friday
    private val cal = FakeCalendarAccess()
    private val local = InMemoryLocalStore()
    private var calendarId: Long? = CAL
    private val store = AlarmStore(cal, local, clock, { calendarId }, { 30 }, { true })

    private fun input(time: String, date: LocalDate? = null, days: Set<DayOfWeek> = emptySet(), message: String = "m", sound: String? = null) =
        AlarmInput(LocalTime.parse(time), date, days, message, sound)

    private fun create(i: AlarmInput) = (store.create(i) as SaveResult.Saved).eventId

    @Test
    fun create_laterToday() {
        val id = create(input("11:00", message = "Fırat şap makinesi teklif ver"))
        val e = cal.event(id)!!
        assertEquals(t("2026-10-02T11:00"), e.dtStart)
        assertEquals(t("2026-10-02T11:15"), e.dtEnd)
        assertNull(e.rrule)
        assertEquals("Fırat şap makinesi teklif ver", e.title)
        assertEquals("Asia/Famagusta", e.timeZone)
        assertFalse(e.isOff)
    }

    @Test
    fun create_timeAlreadyPassed_isTomorrow() =
        assertEquals(t("2026-10-03T09:00"), cal.event(create(input("09:00")))!!.dtStart)

    @Test
    fun create_trimsMessage_keepsUnicode() {
        assertEquals("Fırat şap 🚚\nmakinesi", cal.event(create(input("11:00", message = "  Fırat şap 🚚\nmakinesi  ")))!!.title)
        assertEquals(DEFAULT_TITLE, cal.event(create(input("11:00", message = "   ")))!!.title)
    }

    @Test
    fun create_weekly() {
        val e = cal.event(create(input("08:00", days = setOf(MONDAY, WEDNESDAY))))!!
        assertEquals(t("2026-10-05T08:00"), e.dtStart)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", e.rrule)
        assertEquals("PT15M", e.duration)
        assertNull(e.dtEnd)
    }

    @Test
    fun create_onDate_andPastDateIsRejected() {
        assertEquals(t("2026-10-10T09:00"), cal.event(create(input("09:00", date = LocalDate.of(2026, 10, 10))))!!.dtStart)
        val before = cal.events.size
        assertEquals(SaveResult.TimeInPast, store.create(input("09:00", date = LocalDate.of(2026, 10, 2))))
        assertEquals(before, cal.events.size)
    }

    @Test
    fun create_savesSound_andHistoryOnlyWhenAsked() {
        val id = create(input("08:30", sound = "content://media/1"))
        assertEquals("content://media/1", local.sounds[id])
        assertEquals(listOf((8 * 60 + 30) to t("2026-10-02T10:00")), local.history)
        store.create(input("08:45"), recordHistory = false)
        assertEquals(1, local.history.size)
    }

    @Test
    fun create_withoutCalendar() {
        calendarId = null
        assertEquals(SaveResult.NoCalendar, store.create(input("11:00")))
    }

    @Test
    fun details_describesEachKind() {
        val weekly = store.details(create(input("08:00", days = setOf(MONDAY, WEDNESDAY))))!!
        assertEquals(AlarmKind.WEEKLY, weekly.kind)
        assertEquals(setOf(MONDAY, WEDNESDAY), weekly.days)
        assertEquals(LocalTime.of(8, 0), weekly.time)
        assertNull(weekly.date)

        val oneOff = store.details(create(input("11:00")))!!
        assertEquals(AlarmKind.ONE_OFF, oneOff.kind)
        assertEquals(LocalDate.of(2026, 10, 2), oneOff.date)

        val pcId = cal.insertEvent(CAL, "Daily PC", EventTiming.Recurring(t("2026-09-01T07:00"), "FREQ=DAILY", "P3600S"), "UTC")
        val pc = store.details(pcId)!!
        assertEquals(AlarmKind.OTHER_REPEAT, pc.kind)
        assertTrue(pc.days.isEmpty())
        assertEquals(LocalTime.of(7, 0), pc.time)
    }

    @Test
    fun update_movesOneOff_keepsPcLength() {
        val id = cal.insertEvent(CAL, "PC meeting", EventTiming.Single(t("2026-10-03T09:00"), t("2026-10-03T10:00")), "UTC")
        store.update(id, input("14:30", date = LocalDate.of(2026, 10, 3), message = "PC meeting"))
        val e = cal.event(id)!!
        assertEquals(t("2026-10-03T14:30"), e.dtStart)
        assertEquals(t("2026-10-03T15:30"), e.dtEnd)
        assertEquals("Asia/Famagusta", e.timeZone)
    }

    @Test
    fun update_nonWeeklySeries_keepsRule_changesTimeOfDayOnly() {
        val id = cal.insertEvent(CAL, "Daily PC", EventTiming.Recurring(t("2026-09-01T07:00"), "FREQ=DAILY;COUNT=60", "P3600S"), "Asia/Famagusta")
        store.update(id, input("06:45", message = "Daily PC"))
        val e = cal.event(id)!!
        assertEquals("FREQ=DAILY;COUNT=60", e.rrule)
        assertEquals(t("2026-09-01T06:45"), e.dtStart)
        assertEquals("P3600S", e.duration)
    }

    @Test
    fun update_oneOffIntoWeekly() {
        val id = create(input("11:00"))
        store.update(id, input("12:00", days = setOf(FRIDAY)))
        val e = cal.event(id)!!
        assertEquals("FREQ=WEEKLY;BYDAY=FR", e.rrule)
        assertEquals(t("2026-10-02T12:00"), e.dtStart)
        assertEquals("PT15M", e.duration)
        assertNull(e.dtEnd)
    }

    @Test
    fun update_turnsTheAlarmOn_andRejectsMissingOnes() {
        val id = create(input("11:00"))
        store.setEnabled(id, false)
        store.update(id, input("11:30"))
        assertFalse(cal.event(id)!!.isOff)
        assertEquals(SaveResult.Missing, store.update(999, input("11:30")))
    }

    @Test
    fun delete_removesEventAndSound() {
        val id = create(input("11:00", sound = "content://media/1"))
        store.delete(id)
        assertNull(cal.event(id))
        assertNull(local.sounds[id])
    }

    @Test
    fun setEnabled_oneOff_togglesGraphite() {
        val id = create(input("11:00"))
        store.setEnabled(id, false)
        assertTrue(cal.event(id)!!.isOff)
        store.setEnabled(id, true)
        assertFalse(cal.event(id)!!.isOff)
        assertEquals(t("2026-10-02T11:00"), cal.event(id)!!.dtStart)
    }

    @Test
    fun setEnabled_series_alsoGreysItsExceptions() {
        val id = create(input("09:00", days = DayOfWeek.entries.toSet())) // first ring Sat 2026-10-03 09:00
        val ex = cal.insertException(id, t("2026-10-03T09:00"), EventPatch(timing = EventTiming.Single(t("2026-10-03T09:30"), t("2026-10-03T09:45"))))
        store.setEnabled(id, false)
        assertTrue(cal.event(id)!!.isOff)
        assertTrue(cal.event(ex)!!.isOff)
        store.setEnabled(id, true)
        assertFalse(cal.event(id)!!.isOff)
        assertFalse(cal.event(ex)!!.isOff)
    }

    @Test
    fun setEnabled_on_movesPastOneOffToItsNextOccurrence() {
        val id = cal.insertEvent(CAL, "Yesterday", EventTiming.Single(t("2026-10-01T09:00"), t("2026-10-01T09:15")), ZONE.id)
        cal.updateEvent(id, EventPatch(color = ColorPatch.GRAPHITE))
        store.setEnabled(id, true)
        val e = cal.event(id)!!
        assertEquals(t("2026-10-03T09:00"), e.dtStart)
        assertEquals(t("2026-10-03T09:15"), e.dtEnd)
        assertFalse(e.isOff)
    }
}
```

- [ ] **Step 2: Run to see them fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.AlarmStoreEditTest"
```
Expected: FAIL — `Unresolved reference 'CalendarAccess'` / `'AlarmStore'`.

- [ ] **Step 3: Implement**

`main/java/com/atatuzun/mustafaalarm/domain/CalendarAccess.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

/** Everything the domain needs from the "Alarms" calendar. The only Android implementation is ProviderCalendarAccess. */
interface CalendarAccess {
    fun calendarExists(calendarId: Long): Boolean

    /** Occurrences overlapping [from, to) — including all-day, cancelled and Graphite ones (callers filter). */
    fun instances(calendarId: Long, from: Long, to: Long): List<InstanceRow>

    /** Non-deleted events of the calendar, exceptions included. */
    fun events(calendarId: Long): List<EventRow>

    fun event(eventId: Long): EventRow?

    fun insertEvent(calendarId: Long, title: String, timing: EventTiming, zone: String): Long

    fun updateEvent(eventId: Long, patch: EventPatch)

    fun deleteEvent(eventId: Long)

    /** Creates an exception replacing the occurrence of [seriesId] that originally began at [originalInstanceTime]. */
    fun insertException(seriesId: Long, originalInstanceTime: Long, patch: EventPatch): Long
}
```

`main/java/com/atatuzun/mustafaalarm/domain/LocalStore.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

/** Phone-only data (spec §5.3–5.4). The Android implementation is Room in device-protected storage. */
interface LocalStore {
    fun handledKeys(): Set<InstanceKey>
    fun markHandled(key: InstanceKey, action: RingAction, at: Long)
    fun pruneHandled(before: Long)

    fun soundFor(alarmId: Long): String?
    fun setSound(alarmId: Long, uri: String?)

    fun recordCreation(minuteOfDay: Int, at: Long)
    fun creationHistory(since: Long): List<Pair<Int, Long>>

    fun ringCache(): List<CachedOccurrence>
    fun replaceRingCache(items: List<CachedOccurrence>)
    fun addToRingCache(item: CachedOccurrence)

    fun ringing(): List<RingingEntry>
    fun addRinging(entries: List<RingingEntry>)
    fun removeRinging(keys: Collection<InstanceKey>)

    fun pendingActions(): List<PendingAction>
    fun addPending(key: InstanceKey, action: RingAction, pressedAt: Long)
    fun removePending(id: Long)
}
```

`main/java/com/atatuzun/mustafaalarm/domain/AlarmStore.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class AlarmKind { ONE_OFF, WEEKLY, OTHER_REPEAT }

/** What the add/edit screen submits: days → weekly; else date → that day; else the next time [time] occurs. */
data class AlarmInput(
    val time: LocalTime,
    val date: LocalDate?,
    val days: Set<DayOfWeek>,
    val message: String,
    val soundUri: String?,
)

sealed interface SaveResult {
    data class Saved(val eventId: Long, val start: Long) : SaveResult
    data object TimeInPast : SaveResult
    data object NoCalendar : SaveResult
    data object Missing : SaveResult
}

data class AlarmDetails(
    val eventId: Long,
    val time: LocalTime,
    val date: LocalDate?,
    val days: Set<DayOfWeek>,
    val kind: AlarmKind,
    val message: String,
    val soundUri: String?,
    val on: Boolean,
)

/** Domain operations on alarms; the calendar is the source of truth (spec §4, §6). Blocking — call off the main thread. */
class AlarmStore(
    private val calendar: CalendarAccess,
    private val local: LocalStore,
    private val clock: Clock,
    private val calendarId: () -> Long?,
    private val snoozeMinutes: () -> Int,
    private val calendarAvailable: () -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private val zone: ZoneId get() = clock.zone

    fun create(input: AlarmInput, recordHistory: Boolean = true): SaveResult {
        val calId = calendarId() ?: return SaveResult.NoCalendar
        val now = clock.millis()
        val timing = timingFor(input, now, DEFAULT_LENGTH_MINUTES * MINUTE) ?: return SaveResult.TimeInPast
        val title = titleOf(input.message)
        val id = calendar.insertEvent(calId, title, timing, zone.id)
        input.soundUri?.let { local.setSound(id, it) }
        if (recordHistory) local.recordCreation(input.time.hour * 60 + input.time.minute, now)
        log("created eventId=$id '$title'")
        return SaveResult.Saved(id, timing.start)
    }

    fun details(eventId: Long): AlarmDetails? {
        val e = calendar.event(eventId) ?: return null
        val days = WeeklyRule.parse(e.rrule)
        val kind = when {
            !e.isSeries -> AlarmKind.ONE_OFF
            days != null -> AlarmKind.WEEKLY
            else -> AlarmKind.OTHER_REPEAT
        }
        return AlarmDetails(
            eventId = e.id,
            time = Times.localTime(e.dtStart, zone),
            date = if (kind == AlarmKind.ONE_OFF) Times.localDate(e.dtStart, zone) else null,
            days = days ?: emptySet(),
            kind = kind,
            message = e.title,
            soundUri = local.soundFor(e.id),
            on = !e.isOff,
        )
    }

    fun update(eventId: Long, input: AlarmInput): SaveResult {
        val event = calendar.event(eventId) ?: return SaveResult.Missing
        val now = clock.millis()
        val timing = if (event.isSeries && WeeklyRule.parse(event.rrule) == null) {
            // A rule made on the PC (daily, monthly, …): keep it, change only the time of day.
            val start = Times.at(Times.localDate(event.dtStart, zone), input.time, zone)
            EventTiming.Recurring(start, event.rrule!!, event.duration ?: Rfc5545Duration.ofMillis(event.lengthMillis))
        } else {
            timingFor(input, now, event.lengthMillis) ?: return SaveResult.TimeInPast
        }
        calendar.updateEvent(
            eventId,
            EventPatch(title = titleOf(input.message), timing = timing, zone = zone.id, color = ColorPatch.DEFAULT),
        )
        local.setSound(eventId, input.soundUri)
        log("updated alarm $eventId")
        return SaveResult.Saved(eventId, timing.start)
    }

    fun delete(eventId: Long) {
        calendar.deleteEvent(eventId)
        local.setSound(eventId, null)
        log("deleted alarm $eventId")
    }

    fun setEnabled(eventId: Long, on: Boolean) {
        val event = calendar.event(eventId) ?: return
        val color = if (on) ColorPatch.DEFAULT else ColorPatch.GRAPHITE
        if (event.isSeries) {
            calendar.updateEvent(eventId, EventPatch(color = color))
            val calId = calendarId() ?: return
            calendar.events(calId)
                .filter { it.originalId == eventId && !it.isCanceled }
                .forEach { calendar.updateEvent(it.id, EventPatch(color = color)) }
        } else {
            val now = clock.millis()
            val timing = if (on && event.dtStart <= now) {
                val next = Times.nextAt(Times.localTime(event.dtStart, zone), now, zone)
                EventTiming.Single(next, next + event.lengthMillis)
            } else null
            calendar.updateEvent(eventId, EventPatch(color = color, timing = timing))
        }
        log("alarm $eventId turned ${if (on) "on" else "off"}")
    }

    private fun timingFor(input: AlarmInput, now: Long, lengthMillis: Long): EventTiming? {
        val time = input.time.withSecond(0).withNano(0)
        return when {
            input.days.isNotEmpty() -> EventTiming.Recurring(
                Times.firstWeeklyStart(input.days, time, now, zone), WeeklyRule.build(input.days), Rfc5545Duration.ofMillis(lengthMillis),
            )
            input.date != null -> Times.at(input.date, time, zone).takeIf { it > now }?.let { EventTiming.Single(it, it + lengthMillis) }
            else -> Times.nextAt(time, now, zone).let { EventTiming.Single(it, it + lengthMillis) }
        }
    }

    private fun titleOf(message: String): String = message.trim().ifEmpty { DEFAULT_TITLE }
}
```

- [ ] **Step 4: Run to see them pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.*"
```
Expected: PASS (all domain tests, including the 16 new ones).

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain mustafa-alarm/app/src/test
Commit "feat(domain): create/edit/delete/toggle alarms against the calendar"
```

### Task 6: AlarmStore — Snooze / Tomorrow / Stop, pending actions, ring cache refresh

**Files:**
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/AlarmStore.kt` (insert members before `private fun timingFor`)
- Test: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/AlarmStoreRingTest.kt`

**Interfaces:**
- Consumes: Task 4 `RingPlanner`, Task 5 `AlarmStore`, fakes.
- Produces on `AlarmStore`: `snooze(key: InstanceKey, pressedAt: Long)`, `tomorrow(key, pressedAt)`, `stop(key, pressedAt)`, `applyPending()`, `refreshRingCache(): List<CachedOccurrence>` (never throws on calendar errors; writes `LocalStore.replaceRingCache`; prunes handled records older than 7 days). Log lines `snooze InstanceKey(…)`, `stop …`, `tomorrow …`.

- [ ] **Step 1: Write the failing tests** — `AlarmStoreRingTest.kt`

```kotlin
package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.domain.FakeCalendarAccess.Companion.CAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmStoreRingTest {
    private val clock = TestClock(t("2026-10-05T09:00") + 20_000) // Monday 09:00:20
    private val cal = FakeCalendarAccess()
    private val local = InMemoryLocalStore()
    private var unlocked = true
    private val store = AlarmStore(cal, local, clock, { CAL }, { 30 }, { unlocked })

    private fun oneOff(start: String, title: String = "One-off", minutes: Long = 15) =
        cal.insertEvent(CAL, title, EventTiming.Single(t(start), t(start) + minutes * MINUTE), ZONE.id)

    private fun series(start: String, rule: String) =
        cal.insertEvent(CAL, "Series", EventTiming.Recurring(t(start), rule, "PT15M"), ZONE.id)

    private fun begins(from: String, to: String) =
        cal.instances(CAL, t(from), t(to)).filter { it.isActive }.map { it.begin }

    private fun key(id: Long, start: String) = InstanceKey(id, t(start))

    @Test
    fun snooze_oneOff_movesToPressMinutePlusSnooze() {
        val id = oneOff("2026-10-05T09:00")
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis() + 20_000) // pressed 09:00:40
        assertEquals(t("2026-10-05T09:30"), cal.event(id)!!.dtStart)
        assertEquals(t("2026-10-05T09:45"), cal.event(id)!!.dtEnd)
        assertEquals(RingAction.SNOOZE, local.handled[key(id, "2026-10-05T09:00")]!!.first)
    }

    @Test
    fun snooze_keepsLengthOfPcEvent() {
        val id = oneOff("2026-10-05T09:00", minutes = 60)
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        val e = cal.event(id)!!
        assertEquals(t("2026-10-05T09:30"), e.dtStart)
        assertEquals(HOUR, e.dtEnd!! - e.dtStart)
    }

    @Test
    fun snooze_weeklyOccurrence_createsAnExceptionAndLeavesTheSeries() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(t("2026-10-05T09:00"), cal.event(id)!!.dtStart)
        val ex = cal.events.values.single { it.originalId == id }
        assertEquals(t("2026-10-05T09:00"), ex.originalInstanceTime)
        assertEquals(t("2026-10-05T09:30"), ex.dtStart)
        assertEquals(listOf(t("2026-10-05T09:30"), t("2026-10-07T09:00")), begins("2026-10-05T00:00", "2026-10-08T00:00"))
    }

    @Test
    fun snooze_ofAnException_updatesItInPlace() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        val ex = cal.events.values.single { it.originalId == id }
        clock.set("2026-10-05T09:30")
        store.snooze(InstanceKey(ex.id, t("2026-10-05T09:30")), clock.millis())
        assertEquals(1, cal.events.values.count { it.originalId == id })
        assertEquals(t("2026-10-05T10:00"), cal.event(ex.id)!!.dtStart)
    }

    @Test
    fun snoozeTwice_sameKey_appliesOnce() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis() + 5_000)
        assertEquals(1, cal.events.values.count { it.originalId == id })
    }

    @Test
    fun snooze_acrossMidnight() {
        clock.set("2026-10-05T23:50")
        val id = oneOff("2026-10-05T23:50")
        store.snooze(key(id, "2026-10-05T23:50"), clock.millis())
        assertEquals(t("2026-10-06T00:20"), cal.event(id)!!.dtStart)
    }

    @Test
    fun tomorrow_oneOff_keepsWallClock_acrossDstEnd() {
        clock.set("2026-10-24T08:00")
        val id = oneOff("2026-10-24T08:00")
        store.tomorrow(key(id, "2026-10-24T08:00"), clock.millis())
        assertEquals(t("2026-10-25T08:00"), cal.event(id)!!.dtStart)
    }

    @Test
    fun tomorrow_weekly_movesTheOccurrenceWhenTomorrowIsFree() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        store.tomorrow(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(listOf(t("2026-10-06T09:00"), t("2026-10-07T09:00")), begins("2026-10-05T00:00", "2026-10-08T00:00"))
    }

    @Test
    fun tomorrow_daily_cancelsWhenTomorrowAlreadyRings() {
        val id = series("2026-10-05T09:00", "FREQ=DAILY")
        store.tomorrow(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(listOf(t("2026-10-06T09:00")), begins("2026-10-05T00:00", "2026-10-07T00:00"))
        assertTrue(cal.events.values.single { it.originalId == id }.isCanceled)
    }

    @Test
    fun stop_oneOff_greysItAtItsTime() {
        val id = oneOff("2026-10-05T09:00")
        store.stop(key(id, "2026-10-05T09:00"), clock.millis())
        assertTrue(cal.event(id)!!.isOff)
        assertEquals(t("2026-10-05T09:00"), cal.event(id)!!.dtStart)
    }

    @Test
    fun stop_weekly_changesNothingInTheCalendar() {
        val id = series("2026-10-05T09:00", "FREQ=WEEKLY;BYDAY=MO,WE")
        val before = cal.events.toMap()
        store.stop(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(before, cal.events.toMap())
        assertEquals(RingAction.STOP, local.handled[key(id, "2026-10-05T09:00")]!!.first)
    }

    @Test
    fun deletedWhileRinging_actionsOnlyMarkHandled() {
        val id = oneOff("2026-10-05T09:00")
        cal.deleteEvent(id)
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        assertTrue(cal.events.isEmpty())
        assertTrue(key(id, "2026-10-05T09:00") in local.handledKeys())
        assertTrue(local.pending.isEmpty())
    }

    @Test
    fun locked_snoozeIsQueued_keepsRinging_thenAppliedAfterUnlock() {
        val id = oneOff("2026-10-05T09:00")
        local.addRinging(listOf(RingingEntry(key(id, "2026-10-05T09:00"), id, "One-off", clock.millis())))
        unlocked = false
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(t("2026-10-05T09:00"), cal.event(id)!!.dtStart)
        assertEquals(RingAction.SNOOZE, local.pending.single().action)
        assertEquals(listOf(CachedOccurrence(key(id, "2026-10-05T09:30"), id, "One-off")), local.cache)
        unlocked = true
        store.applyPending()
        assertEquals(t("2026-10-05T09:30"), cal.event(id)!!.dtStart)
        assertTrue(local.pending.isEmpty())
    }

    @Test
    fun failedWrite_isQueued_soTheAlarmIsNotLost() {
        val id = oneOff("2026-10-05T09:00")
        cal.failWrites = true
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        assertEquals(1, local.pending.size)
        assertEquals(t("2026-10-05T09:30"), local.cache.single().ringAt)
        cal.failWrites = false
        store.applyPending()
        assertEquals(t("2026-10-05T09:30"), cal.event(id)!!.dtStart)
    }

    @Test
    fun refreshRingCache_readsTheCalendar_andSkipsHandled() {
        val a = oneOff("2026-10-05T10:00", "A")
        oneOff("2026-10-05T11:00", "B")
        local.markHandled(key(a, "2026-10-05T10:00"), RingAction.STOP, clock.millis())
        assertEquals(listOf("B"), store.refreshRingCache().map { it.title })
        assertEquals(listOf("B"), local.cache.map { it.title })
    }

    @Test
    fun refreshRingCache_extendsTo366Days_whenNothingSooner() {
        oneOff("2026-11-20T08:00", "Far")
        assertEquals(listOf("Far"), store.refreshRingCache().map { it.title })
    }

    @Test
    fun refreshRingCache_calendarMissing_keepsPreviousCache() {
        local.cache = listOf(CachedOccurrence(InstanceKey(7, t("2026-10-05T12:00")), 7, "Cached"))
        cal.exists = false
        assertEquals(listOf("Cached"), store.refreshRingCache().map { it.title })
    }

    @Test
    fun refreshRingCache_locked_keepsPreviousMinusHandled() {
        local.cache = listOf(
            CachedOccurrence(InstanceKey(7, t("2026-10-05T12:00")), 7, "Keep"),
            CachedOccurrence(InstanceKey(8, t("2026-10-05T13:00")), 8, "Stopped"),
        )
        local.markHandled(InstanceKey(8, t("2026-10-05T13:00")), RingAction.STOP, clock.millis())
        unlocked = false
        assertEquals(listOf("Keep"), store.refreshRingCache().map { it.title })
    }

    @Test
    fun refreshRingCache_appliesPendingFirst() {
        val id = oneOff("2026-10-05T09:00")
        unlocked = false
        store.snooze(key(id, "2026-10-05T09:00"), clock.millis())
        unlocked = true
        assertEquals(listOf(key(id, "2026-10-05T09:30")), store.refreshRingCache().map { it.key })
        assertTrue(local.pending.isEmpty())
    }
}
```

- [ ] **Step 2: Run to see them fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.AlarmStoreRingTest"
```
Expected: FAIL — `Unresolved reference 'snooze'` (and `tomorrow`, `stop`, `applyPending`, `refreshRingCache`).

- [ ] **Step 3: Implement** — in `AlarmStore.kt`, insert immediately before the line `    private fun timingFor(input: AlarmInput, now: Long, lengthMillis: Long): EventTiming? {`:

```kotlin
    // ---- ringing actions (spec §6.1) -------------------------------------------------------

    fun snooze(key: InstanceKey, pressedAt: Long) = act(key, RingAction.SNOOZE, pressedAt)

    fun tomorrow(key: InstanceKey, pressedAt: Long) = act(key, RingAction.TOMORROW, pressedAt)

    fun stop(key: InstanceKey, pressedAt: Long) = act(key, RingAction.STOP, pressedAt)

    /** Applies actions pressed while the calendar was unavailable, oldest first; stops at the first failure. */
    fun applyPending() {
        if (!calendarAvailable()) return
        for (p in local.pendingActions()) {
            val ok = runCatching { applyToCalendar(p.key, p.action, p.pressedAt) }
                .onFailure { log("pending ${p.action} on ${p.key} still failing: $it") }
                .isSuccess
            if (!ok) return
            local.removePending(p.id)
            log("applied pending ${p.action} on ${p.key}")
        }
    }

    /** Rebuilds and stores the ring cache. Never throws on calendar problems: falls back to the previous cache. */
    fun refreshRingCache(): List<CachedOccurrence> {
        val now = clock.millis()
        val previous = local.ringCache()
        val calId = calendarId()
        val fresh = if (calId != null && calendarAvailable()) {
            runCatching {
                applyPending()
                if (!calendar.calendarExists(calId)) {
                    null
                } else {
                    val handled = local.handledKeys()
                    val ringing = local.ringing().mapTo(HashSet()) { it.key }
                    val minute = Times.floorMinute(now)
                    var active = calendar.instances(calId, now - RingPlanner.MISSED_WINDOW, now + RingPlanner.HORIZON)
                    if (active.none { it.isActive && it.begin >= minute && it.key !in handled }) {
                        active = active + calendar.instances(calId, now + RingPlanner.HORIZON, now + 366 * DAY)
                    }
                    RingPlanner.planCache(active, previous, handled, ringing, now)
                }
            }.onFailure { log("ring cache refresh failed: $it") }.getOrNull()
        } else {
            null
        }
        val next = fresh ?: RingPlanner.planCacheLocked(
            previous, local.handledKeys(), local.ringing().mapTo(HashSet()) { it.key }, now,
        )
        local.replaceRingCache(next)
        local.pruneHandled(now - 7 * DAY)
        return next
    }

    private fun act(key: InstanceKey, action: RingAction, pressedAt: Long) {
        if (key in local.handledKeys()) return // double press, or notification + screen at once
        val applied = calendarAvailable() && runCatching { applyToCalendar(key, action, pressedAt) }
            .onFailure { log("$action on $key failed, will retry: $it") }
            .isSuccess
        if (!applied) {
            local.addPending(key, action, pressedAt)
            fallbackEntry(key, action, pressedAt)?.let(local::addToRingCache)
        }
        local.markHandled(key, action, pressedAt)
        log("${action.name.lowercase()} $key${if (applied) "" else " (pending)"}")
    }

    private fun applyToCalendar(key: InstanceKey, action: RingAction, pressedAt: Long) {
        val event = calendar.event(key.eventId) ?: return // deleted while ringing: nothing to change
        when (action) {
            RingAction.SNOOZE -> moveOccurrence(event, key, snoozeTime(pressedAt))
            RingAction.TOMORROW -> moveToTomorrow(event, key)
            RingAction.STOP ->
                if (!event.isSeries && !event.isException) calendar.updateEvent(event.id, EventPatch(color = ColorPatch.GRAPHITE))
        }
    }

    private fun moveOccurrence(event: EventRow, key: InstanceKey, newStart: Long) {
        val timing = EventTiming.Single(newStart, newStart + event.lengthMillis)
        if (event.isSeries) {
            calendar.insertException(event.id, key.begin, EventPatch(timing = timing))
        } else {
            calendar.updateEvent(event.id, EventPatch(timing = timing))
        }
    }

    private fun moveToTomorrow(event: EventRow, key: InstanceKey) {
        val newStart = Times.plusDays(key.begin, 1, zone)
        if (!event.isSeries && !event.isException) {
            moveOccurrence(event, key, newStart)
            return
        }
        val seriesId = event.originalId ?: event.id
        val dayStart = Times.startOfDay(newStart, zone)
        val dayEnd = Times.plusDays(dayStart, 1, zone)
        val tomorrowHasOne = calendarId()?.let { calId ->
            calendar.instances(calId, dayStart, dayEnd).any {
                it.alarmId == seriesId && it.key != key && it.status != STATUS_CANCELED && !it.allDay
            }
        } ?: false
        when {
            !tomorrowHasOne -> moveOccurrence(event, key, newStart)
            event.isSeries -> calendar.insertException(event.id, key.begin, EventPatch(canceled = true))
            else -> calendar.updateEvent(event.id, EventPatch(canceled = true))
        }
    }

    private fun snoozeTime(pressedAt: Long): Long = Times.floorMinute(pressedAt) + snoozeMinutes() * MINUTE

    /** Keeps the alarm ringing at its new time while the calendar change waits in pending_actions. */
    private fun fallbackEntry(key: InstanceKey, action: RingAction, pressedAt: Long): CachedOccurrence? {
        val at = when (action) {
            RingAction.SNOOZE -> snoozeTime(pressedAt)
            RingAction.TOMORROW -> Times.plusDays(key.begin, 1, zone)
            RingAction.STOP -> return null
        }
        val known = local.ringing().firstOrNull { it.key == key }?.let { it.alarmId to it.title }
            ?: local.ringCache().firstOrNull { it.key == key }?.let { it.alarmId to it.title }
            ?: (key.eventId to DEFAULT_TITLE)
        return CachedOccurrence(InstanceKey(key.eventId, at), known.first, known.second)
    }

```

- [ ] **Step 4: Run to see them pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.*"
```
Expected: PASS (all domain tests).

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/AlarmStore.kt mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/AlarmStoreRingTest.kt
Commit "feat(domain): snooze/tomorrow/stop mapping, pending actions, ring cache refresh"
```

### Task 7: Alarm list grouping and "Your frequent alarms"

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/{AlarmListBuilder,FrequentAlarms}.kt`
- Modify: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain/AlarmStore.kt` (add `list()`)
- Test: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/domain/{AlarmListTest,FrequentAlarmsTest}.kt`

**Interfaces:**
- Consumes: Tasks 3–6.
- Produces:
  - `data class AlarmItem(eventId: Long, title: String, shownAt: Long, kind: AlarmKind, weekdays: Set<DayOfWeek>, on: Boolean)` with `nextRing: Long?` (= `shownAt` when on).
  - `data class AlarmSection(day: LocalDate, items: List<AlarmItem>)`
  - `sealed interface AlarmListState { Ready(sections: List<AlarmSection>, nextRing: Long?); NoCalendar; CalendarMissing; Unavailable }`
  - `AlarmListBuilder.build(events, instances, handled, now, zone): List<AlarmSection>`
  - `AlarmStore.list(): AlarmListState`
  - `FrequentAlarms.top(history: List<Pair<Int, Long>>, now: Long, limit: Int = 5): List<LocalTime>`, `FrequentAlarms.WINDOW = 60 * DAY`

- [ ] **Step 1: Write the failing tests**

`AlarmListTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import com.atatuzun.mustafaalarm.domain.FakeCalendarAccess.Companion.CAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AlarmListTest {
    private val clock = TestClock(t("2026-10-02T10:00")) // Friday
    private val cal = FakeCalendarAccess()
    private val local = InMemoryLocalStore()
    private var calendarId: Long? = CAL
    private var available = true
    private val store = AlarmStore(cal, local, clock, { calendarId }, { 30 }, { available })

    private fun ready() = store.list() as AlarmListState.Ready
    private fun items() = ready().sections.flatMap { it.items }
    private fun oneOff(start: String, title: String) =
        cal.insertEvent(CAL, title, EventTiming.Single(t(start), t(start) + 15 * MINUTE), ZONE.id)
    private fun series(start: String, rule: String, title: String) =
        cal.insertEvent(CAL, title, EventTiming.Recurring(t(start), rule, "PT15M"), ZONE.id)

    @Test
    fun groupsByDay_sorted_eachAlarmOnce() {
        oneOff("2026-10-02T12:00", "Noon")
        series("2026-10-01T07:00", "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU", "Daily")
        oneOff("2026-10-05T09:00", "Monday")
        val sections = ready().sections
        assertEquals(listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5)), sections.map { it.day })
        assertEquals(listOf("Noon"), sections[0].items.map { it.title })
        assertEquals(listOf("Daily"), sections[1].items.map { it.title })
        assertEquals(AlarmKind.WEEKLY, sections[1].items.single().kind)
        assertEquals(1, items().count { it.title == "Daily" })
        assertEquals(t("2026-10-02T12:00"), ready().nextRing)
    }

    @Test
    fun offOneOffInTheFuture_isGreyOnItsDay() {
        val id = oneOff("2026-10-03T09:00", "Off")
        store.setEnabled(id, false)
        val item = items().single()
        assertFalse(item.on)
        assertEquals(t("2026-10-03T09:00"), item.shownAt)
        assertNull(ready().nextRing)
    }

    @Test
    fun doneOneOffs_stayVisibleUntilTheEndOfTheirDay() {
        val today = oneOff("2026-10-02T08:00", "Done today")
        store.stop(InstanceKey(today, t("2026-10-02T08:00")), clock.millis())
        val yesterday = oneOff("2026-10-01T08:00", "Done yesterday")
        store.stop(InstanceKey(yesterday, t("2026-10-01T08:00")), clock.millis())
        assertEquals(listOf("Done today"), items().map { it.title })
        assertFalse(items().single().on)
    }

    @Test
    fun snoozedOccurrence_movesTheSeriesRowToTheSnoozeTime() {
        clock.set("2026-10-02T09:00")
        val id = series("2026-10-02T09:00", "FREQ=DAILY", "Daily")
        store.snooze(InstanceKey(id, t("2026-10-02T09:00")), clock.millis())
        val item = items().single()
        assertEquals(id, item.eventId)
        assertEquals(t("2026-10-02T09:30"), item.shownAt)
    }

    @Test
    fun stoppedOccurrence_showsTheNextOne() {
        clock.set("2026-10-02T09:00")
        val id = series("2026-10-02T09:00", "FREQ=WEEKLY;BYDAY=FR", "Fridays")
        store.stop(InstanceKey(id, t("2026-10-02T09:00")), clock.millis())
        assertEquals(t("2026-10-09T09:00"), items().single().shownAt)
    }

    @Test
    fun offSeries_isGreyAtItsNextOccurrence() {
        val id = series("2026-10-01T07:00", "FREQ=DAILY", "Daily PC")
        store.setEnabled(id, false)
        val item = items().single()
        assertFalse(item.on)
        assertEquals(AlarmKind.OTHER_REPEAT, item.kind)
        assertEquals(t("2026-10-03T07:00"), item.shownAt)
    }

    @Test
    fun allDayEventsAreIgnored() {
        cal.events[500] = EventRow(500, "Holiday", t("2026-10-03T00:00"), t("2026-10-04T00:00"), null, null, true, null, null, null, null, "UTC")
        assertTrue(ready().sections.isEmpty())
    }

    @Test
    fun grouping_usesDeviceZone_notEventZone() {
        val start = Instant.parse("2026-10-02T21:30:00Z").toEpochMilli() // 00:30 on 3 Oct in Famagusta
        cal.insertEvent(CAL, "UTC event", EventTiming.Single(start, start + HOUR), "UTC")
        assertEquals(LocalDate.of(2026, 10, 3), ready().sections.single().day)
    }

    @Test
    fun list_reportsMissingCalendar_andUnavailable() {
        cal.exists = false
        assertEquals(AlarmListState.CalendarMissing, store.list())
        available = false
        assertEquals(AlarmListState.Unavailable, store.list())
        calendarId = null
        assertEquals(AlarmListState.NoCalendar, store.list())
    }
}
```

`FrequentAlarmsTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class FrequentAlarmsTest {
    @Test
    fun topFive_byCount_thenMostRecent_withinSixtyDays() {
        val now = t("2026-10-02T10:00")
        val history = listOf(
            450 to now - DAY, 450 to now - 2 * DAY, 450 to now - 3 * DAY, // 07:30 ×3
            480 to now - DAY, 480 to now - 5 * DAY,                        // 08:00 ×2
            540 to now - 10 * DAY,                                         // 09:00
            600 to now - HOUR,                                             // 10:00 (newest single)
            360 to now - 61 * DAY, 360 to now - 62 * DAY, 360 to now - 70 * DAY, // 06:00, too old
            420 to now - 20 * DAY,                                         // 07:00
            330 to now - 30 * DAY,                                         // 05:30
        )
        assertEquals(
            listOf("07:30", "08:00", "10:00", "09:00", "07:00").map(LocalTime::parse),
            FrequentAlarms.top(history, now),
        )
    }

    @Test
    fun emptyHistory() = assertTrue(FrequentAlarms.top(emptyList(), 0).isEmpty())
}
```

- [ ] **Step 2: Run to see them fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.domain.AlarmListTest" --tests "com.atatuzun.mustafaalarm.domain.FrequentAlarmsTest"
```
Expected: FAIL — `Unresolved reference 'AlarmListState'`, `'FrequentAlarms'`, `'list'`.

- [ ] **Step 3: Implement**

`domain/AlarmListBuilder.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

data class AlarmItem(
    val eventId: Long,
    val title: String,
    val shownAt: Long,
    val kind: AlarmKind,
    val weekdays: Set<DayOfWeek>,
    val on: Boolean,
) {
    val nextRing: Long? get() = if (on) shownAt else null
}

data class AlarmSection(val day: LocalDate, val items: List<AlarmItem>)

sealed interface AlarmListState {
    data class Ready(val sections: List<AlarmSection>, val nextRing: Long?) : AlarmListState
    data object NoCalendar : AlarmListState
    data object CalendarMissing : AlarmListState
    data object Unavailable : AlarmListState
}

/** Home-screen list (spec §9.1): grouped by the day each alarm next rings, each alarm once. */
object AlarmListBuilder {
    fun build(
        events: List<EventRow>,
        instances: List<InstanceRow>,
        handled: Set<InstanceKey>,
        now: Long,
        zone: ZoneId,
    ): List<AlarmSection> {
        val minute = Times.floorMinute(now)
        val todayStart = Times.startOfDay(now, zone)
        val nextByAlarm = instances.asSequence()
            .filter { !it.allDay && it.status != STATUS_CANCELED && it.begin >= minute && it.key !in handled }
            .groupBy { it.alarmId }
            .mapValues { (_, rows) -> rows.minOf { it.begin } }

        val items = events
            .filter { !it.allDay && !it.isException && !it.isCanceled }
            .mapNotNull { e ->
                if (!e.isSeries) {
                    val on = !e.isOff && e.dtStart >= minute
                    if (!on && e.dtStart < todayStart) null
                    else AlarmItem(e.id, e.title, e.dtStart, AlarmKind.ONE_OFF, emptySet(), on)
                } else {
                    val next = nextByAlarm[e.id] ?: return@mapNotNull null
                    val days = WeeklyRule.parse(e.rrule)
                    val kind = if (days != null) AlarmKind.WEEKLY else AlarmKind.OTHER_REPEAT
                    AlarmItem(e.id, e.title, next, kind, days ?: emptySet(), !e.isOff)
                }
            }

        return items.groupBy { Times.localDate(it.shownAt, zone) }
            .toSortedMap()
            .map { (day, list) -> AlarmSection(day, list.sortedWith(compareBy({ it.shownAt }, { it.title }))) }
    }
}
```

`domain/FrequentAlarms.kt`:
```kotlin
package com.atatuzun.mustafaalarm.domain

import java.time.LocalTime

/** "Your frequent alarms": the most-created times of the last 60 days (spec §5.3, §9.3). */
object FrequentAlarms {
    const val WINDOW = 60 * DAY

    fun top(history: List<Pair<Int, Long>>, now: Long, limit: Int = 5): List<LocalTime> =
        history.filter { it.second >= now - WINDOW }
            .groupBy({ it.first }, { it.second })
            .entries
            .sortedWith(compareByDescending<Map.Entry<Int, List<Long>>> { it.value.size }.thenByDescending { it.value.max() })
            .take(limit)
            .map { LocalTime.of(it.key / 60, it.key % 60) }
}
```

In `AlarmStore.kt`, insert immediately before the line `    // ---- ringing actions (spec §6.1) -------------------------------------------------------`:
```kotlin
    fun list(): AlarmListState {
        val calId = calendarId() ?: return AlarmListState.NoCalendar
        if (!calendarAvailable()) return AlarmListState.Unavailable
        if (!calendar.calendarExists(calId)) return AlarmListState.CalendarMissing
        val now = clock.millis()
        val sections = AlarmListBuilder.build(
            calendar.events(calId),
            calendar.instances(calId, Times.startOfDay(now, zone), now + 366 * DAY),
            local.handledKeys(),
            now,
            zone,
        )
        return AlarmListState.Ready(sections, sections.flatMap { it.items }.mapNotNull { it.nextRing }.minOrNull())
    }

```

- [ ] **Step 4: Run to see them pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest
```
Expected: PASS (every JVM test so far).

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/domain mustafa-alarm/app/src/test
Commit "feat(domain): alarm list grouping and frequent-alarm ranking"
```

### Task 8: ProviderCalendarAccess — the real calendar adapter (Layer 2 tests on the emulator)

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/data/calendar/{CalendarSetupAccess,ProviderCalendarAccess}.kt`
- Create: `mustafa-alarm/app/src/debug/java/com/atatuzun/mustafaalarm/debug/LocalCalendars.kt`
- Test: `mustafa-alarm/app/src/androidTest/java/com/atatuzun/mustafaalarm/data/ProviderCalendarAccessTest.kt`

**Interfaces:**
- Consumes: `CalendarAccess`, model, `Times`, `WeeklyRule` (Tasks 3–5).
- Produces:
  - `const val GOOGLE_ACCOUNT_TYPE = "com.google"`; `data class CalendarRow(id, accountName, accountType, displayName: String?, syncId: String?, ownerAccount: String?, syncEvents: Boolean, visible: Boolean)`
  - `interface CalendarSetupAccess { findCalendars(accountName, accountType = GOOGLE_ACCOUNT_TYPE): List<CalendarRow>; calendarRow(calendarId): CalendarRow?; enableSyncAndVisibility(calendarId); requestSync(accountName); dirtyCount(calendarId): Int? }`
  - `class ProviderCalendarAccess(resolver: ContentResolver) : CalendarAccess, CalendarSetupAccess`
  - Debug-only `LocalCalendars.create(resolver, name): Long`, `findByName(resolver, name): Long?`, `delete(resolver, calendarId)`, `ACCOUNT_NAME = "Mustafa Alarm Local"` (local calendar + Graphite colour row, created as sync adapter).

- [ ] **Step 1: Write the failing instrumented test** — `androidTest/java/com/atatuzun/mustafaalarm/data/ProviderCalendarAccessTest.kt`

```kotlin
package com.atatuzun.mustafaalarm.data

import android.Manifest
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.atatuzun.mustafaalarm.data.calendar.ProviderCalendarAccess
import com.atatuzun.mustafaalarm.debug.LocalCalendars
import com.atatuzun.mustafaalarm.domain.ColorPatch
import com.atatuzun.mustafaalarm.domain.DAY
import com.atatuzun.mustafaalarm.domain.EventPatch
import com.atatuzun.mustafaalarm.domain.EventTiming
import com.atatuzun.mustafaalarm.domain.GRAPHITE_COLOR_KEY
import com.atatuzun.mustafaalarm.domain.HOUR
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.MINUTE
import com.atatuzun.mustafaalarm.domain.Times
import com.atatuzun.mustafaalarm.domain.WeeklyRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ProviderCalendarAccessTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val access = ProviderCalendarAccess(resolver)
    private val zone = ZoneId.systemDefault()
    private val tomorrow = LocalDate.now(zone).plusDays(1)
    private var cal = 0L

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = Times.at(date, LocalTime.of(hour, minute), zone)
    private fun active(from: Long, to: Long) = access.instances(cal, from, to).filter { it.isActive }
    private fun dailyAt10(count: Int = 3) =
        access.insertEvent(cal, "Daily", EventTiming.Recurring(at(tomorrow, 10), "FREQ=DAILY;COUNT=$count", "PT15M"), zone.id)

    @Before fun setUp() { cal = LocalCalendars.create(resolver, "ma-test-${System.nanoTime()}") }
    @After fun tearDown() { LocalCalendars.delete(resolver, cal) }

    @Test
    fun insertSingle_isReadBackWithTitleAndTimes() {
        val start = at(tomorrow, 9)
        val id = access.insertEvent(cal, "Fırat şap makinesi 🚚", EventTiming.Single(start, start + 15 * MINUTE), zone.id)
        val row = active(start - HOUR, start + HOUR).single()
        assertEquals(InstanceKey(id, start), row.key)
        assertEquals("Fırat şap makinesi 🚚", row.title)
        assertEquals(id, row.alarmId)
        val e = access.event(id)!!
        assertEquals(start + 15 * MINUTE, e.dtEnd)
        assertNull(e.rrule)
        assertEquals(zone.id, e.timeZone)
        assertEquals(listOf(id), access.events(cal).map { it.id })
    }

    @Test
    fun weeklySeries_expandsOnItsDays() {
        val first = Times.firstWeeklyStart(setOf(MONDAY, WEDNESDAY), LocalTime.of(8, 0), System.currentTimeMillis(), zone)
        val id = access.insertEvent(cal, "Weekly", EventTiming.Recurring(first, WeeklyRule.build(setOf(MONDAY, WEDNESDAY)), "PT15M"), zone.id)
        val rows = active(first, first + 14 * DAY)
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.eventId == id && Times.localDate(it.begin, zone).dayOfWeek in setOf(MONDAY, WEDNESDAY) })
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", access.event(id)!!.rrule)
    }

    @Test
    fun updateTiming_movesASingle() {
        val start = at(tomorrow, 9)
        val id = access.insertEvent(cal, "Move me", EventTiming.Single(start, start + 15 * MINUTE), zone.id)
        val moved = at(tomorrow, 10)
        access.updateEvent(id, EventPatch(timing = EventTiming.Single(moved, moved + 15 * MINUTE)))
        assertEquals(listOf(moved), active(at(tomorrow, 0), at(tomorrow.plusDays(1), 0)).map { it.begin })
    }

    @Test
    fun graphiteColour_thenDefault() {
        val start = at(tomorrow, 9)
        val id = access.insertEvent(cal, "Colour", EventTiming.Single(start, start + 15 * MINUTE), zone.id)
        access.updateEvent(id, EventPatch(color = ColorPatch.GRAPHITE))
        assertEquals(GRAPHITE_COLOR_KEY, access.event(id)!!.colorKey)
        assertEquals(GRAPHITE_COLOR_KEY, access.instances(cal, start - HOUR, start + HOUR).single().colorKey)
        assertTrue(active(start - HOUR, start + HOUR).isEmpty())
        access.updateEvent(id, EventPatch(color = ColorPatch.DEFAULT))
        assertNull(access.event(id)!!.colorKey)
        assertEquals(1, active(start - HOUR, start + HOUR).size)
    }

    @Test
    fun exception_movesOneOccurrence() {
        val id = dailyAt10()
        val second = at(tomorrow.plusDays(1), 10)
        val ex = access.insertException(id, second, EventPatch(timing = EventTiming.Single(second + 30 * MINUTE, second + 45 * MINUTE)))
        assertEquals(
            listOf(at(tomorrow, 10), second + 30 * MINUTE, at(tomorrow.plusDays(2), 10)),
            active(at(tomorrow, 0), at(tomorrow.plusDays(4), 0)).map { it.begin },
        )
        val exRow = access.event(ex)!!
        assertEquals(id, exRow.originalId)
        assertEquals(second, exRow.originalInstanceTime)
        assertEquals(id, active(second, second + HOUR).single().alarmId)
    }

    @Test
    fun canceledException_removesTheOccurrence() {
        val id = dailyAt10()
        access.insertException(id, at(tomorrow.plusDays(1), 10), EventPatch(canceled = true))
        assertEquals(
            listOf(at(tomorrow, 10), at(tomorrow.plusDays(2), 10)),
            active(at(tomorrow, 0), at(tomorrow.plusDays(4), 0)).map { it.begin },
        )
    }

    @Test
    fun updatingAnException_movesIt() {
        val id = dailyAt10()
        val second = at(tomorrow.plusDays(1), 10)
        val ex = access.insertException(id, second, EventPatch(timing = EventTiming.Single(second + 30 * MINUTE, second + 45 * MINUTE)))
        access.updateEvent(ex, EventPatch(timing = EventTiming.Single(second + HOUR, second + HOUR + 15 * MINUTE)))
        assertEquals(
            listOf(at(tomorrow, 10), second + HOUR, at(tomorrow.plusDays(2), 10)),
            active(at(tomorrow, 0), at(tomorrow.plusDays(4), 0)).map { it.begin },
        )
    }

    @Test
    fun delete_removesEventAndInstances() {
        val start = at(tomorrow, 9)
        val id = access.insertEvent(cal, "Delete me", EventTiming.Single(start, start + 15 * MINUTE), zone.id)
        access.deleteEvent(id)
        assertTrue(active(start - HOUR, start + HOUR).isEmpty())
        assertNull(access.event(id))
        assertTrue(access.events(cal).isEmpty())
    }

    @Test
    fun calendarExists_andSetupAccess() {
        assertTrue(access.calendarExists(cal))
        assertFalse(access.calendarExists(Long.MAX_VALUE / 2))
        val row = access.findCalendars(LocalCalendars.ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL).single { it.id == cal }
        assertTrue(row.syncEvents && row.visible)
        access.enableSyncAndVisibility(cal)
        assertTrue(access.calendarRow(cal)!!.visible)
        assertNotNull(access.dirtyCount(cal))
    }
}
```

- [ ] **Step 2: Build to see it fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:assembleDebugAndroidTest
```
Expected: FAIL — `Unresolved reference 'ProviderCalendarAccess'` / `'LocalCalendars'`.

- [ ] **Step 3: Implement**

`main/java/com/atatuzun/mustafaalarm/data/calendar/CalendarSetupAccess.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data.calendar

const val GOOGLE_ACCOUNT_TYPE = "com.google"

data class CalendarRow(
    val id: Long,
    val accountName: String,
    val accountType: String,
    val displayName: String?,
    val syncId: String?,
    val ownerAccount: String?,
    val syncEvents: Boolean,
    val visible: Boolean,
)

/** Calendar-level operations used by first-run setup and the reliability check. */
interface CalendarSetupAccess {
    fun findCalendars(accountName: String, accountType: String = GOOGLE_ACCOUNT_TYPE): List<CalendarRow>
    fun calendarRow(calendarId: Long): CalendarRow?
    fun enableSyncAndVisibility(calendarId: Long)
    fun requestSync(accountName: String)

    /** Events changed on the phone and not yet uploaded; null when the provider will not say. */
    fun dirtyCount(calendarId: Long): Int?
}
```

`main/java/com/atatuzun/mustafaalarm/data/calendar/ProviderCalendarAccess.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data.calendar

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.atatuzun.mustafaalarm.domain.CalendarAccess
import com.atatuzun.mustafaalarm.domain.ColorPatch
import com.atatuzun.mustafaalarm.domain.EventPatch
import com.atatuzun.mustafaalarm.domain.EventRow
import com.atatuzun.mustafaalarm.domain.EventTiming
import com.atatuzun.mustafaalarm.domain.GRAPHITE_COLOR_KEY
import com.atatuzun.mustafaalarm.domain.InstanceRow

/** The only code that touches CalendarContract (spec §4). Blocking — call off the main thread. */
class ProviderCalendarAccess(private val resolver: ContentResolver) : CalendarAccess, CalendarSetupAccess {

    override fun calendarExists(calendarId: Long): Boolean = calendarRow(calendarId) != null

    override fun instances(calendarId: Long, from: Long, to: Long): List<InstanceRow> {
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val deleted = deletedEventIds(calendarId)
        val rows = mutableListOf<InstanceRow>()
        resolver.query(uri, INSTANCE_COLUMNS, "${Instances.CALENDAR_ID}=?", arrayOf(calendarId.toString()), "${Instances.BEGIN} ASC")
            ?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) in deleted) continue // deleted on the phone, waiting for the sync adapter
                    rows += InstanceRow(
                        eventId = c.getLong(0),
                        begin = c.getLong(1),
                        end = c.getLong(2),
                        title = c.getStringOrNull(3).orEmpty(),
                        allDay = c.getInt(4) != 0,
                        colorKey = c.getStringOrNull(5),
                        status = c.getIntOrNull(6),
                        rrule = c.getStringOrNull(7),
                        originalId = c.getLongOrNull(8),
                    )
                }
            }
        return rows
    }

    override fun events(calendarId: Long): List<EventRow> =
        queryEvents("${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0", arrayOf(calendarId.toString()))

    override fun event(eventId: Long): EventRow? =
        queryEvents("${Events._ID}=? AND ${Events.DELETED}=0", arrayOf(eventId.toString())).firstOrNull()

    override fun insertEvent(calendarId: Long, title: String, timing: EventTiming, zone: String): Long {
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, title)
            put(Events.EVENT_TIMEZONE, zone)
            put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
            put(Events.HAS_ALARM, 0)
            putTiming(timing)
        }
        val uri = resolver.insert(Events.CONTENT_URI, values) ?: error("Calendar refused the new event")
        return ContentUris.parseId(uri)
    }

    override fun updateEvent(eventId: Long, patch: EventPatch) {
        val values = ContentValues().apply { putPatch(patch, forException = false) }
        if (values.size() > 0) resolver.update(eventUri(eventId), values, null, null)
    }

    override fun deleteEvent(eventId: Long) {
        resolver.delete(eventUri(eventId), null, null)
    }

    override fun insertException(seriesId: Long, originalInstanceTime: Long, patch: EventPatch): Long {
        val values = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, originalInstanceTime)
            putPatch(patch, forException = true)
        }
        val uri = resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, seriesId), values)
            ?: error("Calendar refused the exception for event $seriesId")
        return ContentUris.parseId(uri)
    }

    override fun findCalendars(accountName: String, accountType: String): List<CalendarRow> =
        queryCalendars("${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?", arrayOf(accountName, accountType))

    override fun calendarRow(calendarId: Long): CalendarRow? =
        queryCalendars("${Calendars._ID}=?", arrayOf(calendarId.toString())).firstOrNull()

    override fun enableSyncAndVisibility(calendarId: Long) {
        val values = ContentValues().apply { put(Calendars.SYNC_EVENTS, 1); put(Calendars.VISIBLE, 1) }
        resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), values, null, null)
    }

    override fun requestSync(accountName: String) {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(Account(accountName, GOOGLE_ACCOUNT_TYPE), CalendarContract.AUTHORITY, extras)
    }

    override fun dirtyCount(calendarId: Long): Int? = runCatching {
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.DIRTY}=1", arrayOf(calendarId.toString()), null)
            ?.use { it.count }
    }.getOrNull()

    private fun eventUri(id: Long): Uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)

    /** Instances has no DELETED column, so phone-deleted events (DELETED=1 until uploaded) are filtered by id. */
    private fun deletedEventIds(calendarId: Long): Set<Long> =
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=1", arrayOf(calendarId.toString()), null)
            ?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptySet()

    private fun queryEvents(selection: String, args: Array<String>): List<EventRow> =
        resolver.query(Events.CONTENT_URI, EVENT_COLUMNS, selection, args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        EventRow(
                            id = c.getLong(0),
                            title = c.getStringOrNull(1).orEmpty(),
                            dtStart = c.getLong(2),
                            dtEnd = c.getLongOrNull(3),
                            duration = c.getStringOrNull(4),
                            rrule = c.getStringOrNull(5),
                            allDay = c.getInt(6) != 0,
                            colorKey = c.getStringOrNull(7),
                            status = c.getIntOrNull(8),
                            originalId = c.getLongOrNull(9),
                            originalInstanceTime = c.getLongOrNull(10),
                            timeZone = c.getStringOrNull(11),
                        ),
                    )
                }
            }
        } ?: emptyList()

    private fun queryCalendars(selection: String, args: Array<String>): List<CalendarRow> =
        resolver.query(Calendars.CONTENT_URI, CALENDAR_COLUMNS, selection, args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CalendarRow(
                            id = c.getLong(0),
                            accountName = c.getString(1),
                            accountType = c.getString(2),
                            displayName = c.getStringOrNull(3),
                            syncId = c.getStringOrNull(4),
                            ownerAccount = c.getStringOrNull(5),
                            syncEvents = c.getInt(6) != 0,
                            visible = c.getInt(7) != 0,
                        ),
                    )
                }
            }
        } ?: emptyList()

    private fun ContentValues.putTiming(timing: EventTiming) {
        when (timing) {
            is EventTiming.Single -> {
                put(Events.DTSTART, timing.start); put(Events.DTEND, timing.end)
                putNull(Events.RRULE); putNull(Events.DURATION)
            }
            is EventTiming.Recurring -> {
                put(Events.DTSTART, timing.start); putNull(Events.DTEND)
                put(Events.RRULE, timing.rrule); put(Events.DURATION, timing.duration)
            }
        }
    }

    /** Exceptions get only DTSTART/DTEND: an RRULE or DURATION key would make the provider split the series. */
    private fun ContentValues.putPatch(patch: EventPatch, forException: Boolean) {
        patch.title?.let { put(Events.TITLE, it) }
        patch.zone?.let { put(Events.EVENT_TIMEZONE, it) }
        patch.timing?.let { timing ->
            if (forException) {
                require(timing is EventTiming.Single) { "an exception is a single occurrence" }
                put(Events.DTSTART, timing.start); put(Events.DTEND, timing.end)
            } else {
                putTiming(timing)
            }
        }
        when (patch.color) {
            ColorPatch.GRAPHITE -> put(Events.EVENT_COLOR_KEY, GRAPHITE_COLOR_KEY)
            ColorPatch.DEFAULT -> { putNull(Events.EVENT_COLOR_KEY); putNull(Events.EVENT_COLOR) }
            null -> Unit
        }
        if (patch.canceled) put(Events.STATUS, Events.STATUS_CANCELED)
    }

    private companion object {
        val INSTANCE_COLUMNS = arrayOf(
            Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.ALL_DAY,
            Instances.EVENT_COLOR_KEY, Instances.STATUS, Instances.RRULE, Instances.ORIGINAL_ID,
        )
        val EVENT_COLUMNS = arrayOf(
            Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.DURATION, Events.RRULE, Events.ALL_DAY,
            Events.EVENT_COLOR_KEY, Events.STATUS, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME, Events.EVENT_TIMEZONE,
        )
        val CALENDAR_COLUMNS = arrayOf(
            Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_DISPLAY_NAME,
            Calendars._SYNC_ID, Calendars.OWNER_ACCOUNT, Calendars.SYNC_EVENTS, Calendars.VISIBLE,
        )
    }
}
```

`debug/java/com/atatuzun/mustafaalarm/debug/LocalCalendars.kt`:
```kotlin
package com.atatuzun.mustafaalarm.debug

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
import com.atatuzun.mustafaalarm.domain.GRAPHITE_COLOR_KEY
import java.time.ZoneId

/** Debug/test only: a phone-local calendar (no Google account) that behaves like "Alarms". */
object LocalCalendars {
    const val ACCOUNT_NAME = "Mustafa Alarm Local"
    private const val TYPE = CalendarContract.ACCOUNT_TYPE_LOCAL

    fun create(resolver: ContentResolver, name: String): Long {
        ensureGraphiteColour(resolver)
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, TYPE)
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.CALENDAR_COLOR, 0xFF3F51B5.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        return ContentUris.parseId(resolver.insert(Calendars.CONTENT_URI.asSyncAdapter(), values)!!)
    }

    fun findByName(resolver: ContentResolver, name: String): Long? = resolver.query(
        Calendars.CONTENT_URI, arrayOf(Calendars._ID),
        "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?",
        arrayOf(ACCOUNT_NAME, TYPE, name), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    fun delete(resolver: ContentResolver, calendarId: Long) {
        resolver.delete(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId).asSyncAdapter(), null, null)
    }

    private fun ensureGraphiteColour(resolver: ContentResolver) {
        val exists = resolver.query(
            Colors.CONTENT_URI, arrayOf(Colors.COLOR_KEY),
            "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=? AND ${Colors.COLOR_TYPE}=? AND ${Colors.COLOR_KEY}=?",
            arrayOf(ACCOUNT_NAME, TYPE, Colors.TYPE_EVENT.toString(), GRAPHITE_COLOR_KEY), null,
        )?.use { it.count > 0 } ?: false
        if (exists) return
        val values = ContentValues().apply {
            put(Colors.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Colors.ACCOUNT_TYPE, TYPE)
            put(Colors.COLOR_TYPE, Colors.TYPE_EVENT)
            put(Colors.COLOR_KEY, GRAPHITE_COLOR_KEY)
            put(Colors.COLOR, 0xFF616161.toInt())
        }
        resolver.insert(Colors.CONTENT_URI.asSyncAdapter(), values)
    }

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, TYPE)
        .build()
}
```

- [ ] **Step 4: Run on the emulator**

```powershell
. .\scripts\droid.ps1
Start-Emu
Gradle :app:installDebug :app:installDebugAndroidTest
Instrument 'com.atatuzun.mustafaalarm.data.ProviderCalendarAccessTest' | Tee-Object (Join-Path (New-Evidence 'task08') 'provider-tests.txt')
```
Expected: `OK (9 tests)`. If a test fails, use superpowers:systematic-debugging: dump the raw rows (`A shell content query --uri content://com.android.calendar/instances/when/<from>/<to>` is not available to shell; instead add a temporary `Log.i` of the cursor in the failing test), fix the adapter (not the assertion), and re-run.

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/data mustafa-alarm/app/src/debug mustafa-alarm/app/src/androidTest/java/com/atatuzun/mustafaalarm/data
Commit "feat(data): calendar provider adapter with instrumented tests"
```

### Task 9: Phone storage — Room + DataStore in device-protected storage, EventLog

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/data/local/{Entities,LocalDao,AlarmDatabase,RoomLocalStore}.kt`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/data/settings/{AlarmSettings,SettingsRepository}.kt`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/log/EventLog.kt`
- Test: `mustafa-alarm/app/src/androidTest/java/com/atatuzun/mustafaalarm/data/{RoomLocalStoreTest,SettingsRepositoryTest}.kt`

**Interfaces:**
- Consumes: `LocalStore` and model (Tasks 3, 5).
- Produces:
  - `AlarmDatabase.open(context): AlarmDatabase` (device-protected), `AlarmDatabase.dao(): LocalDao`, `class RoomLocalStore(dao: LocalDao) : LocalStore`.
  - `enum StopMethod { ONE_PRESS, THREE_PRESSES }`, `enum SoundMode { SOUND_AND_VIBRATION, SOUND_ONLY, VIBRATION_ONLY }`, `data class AlarmSettings(snoozeMinutes = 30, autoSnoozeMinutes = 1, showSnoozeButton = true, stopMethod = THREE_PRESSES, soundMode = SOUND_AND_VIBRATION, defaultSoundUri: String? = null, volumePercent = 100, increaseDeviceVolume = true, fadeIn = true, use24Hour = true, darkTheme = true, nextAlarmNotification = true, accountEmail: String? = null, calendarId: Long? = null, calendarSyncId: String? = null, lastCalendarChange: Long? = null)`.
  - `class SettingsRepository(context, fileName = "settings")` with `flow: Flow<AlarmSettings>`, `current(): AlarmSettings` (blocking), `suspend update(transform)`, `updateBlocking(transform)`.
  - `class EventLog(context)` with `log(message)`, `EventLog.TAG = "MustafaAlarm"`, `EventLog.time(millis): String` ("yyyy-MM-dd HH:mm:ss"); file `/data/user_de/0/com.atatuzun.mustafaalarm/files/event-log.txt`.

- [ ] **Step 1: Write the failing instrumented tests**

`androidTest/java/com/atatuzun/mustafaalarm/data/RoomLocalStoreTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atatuzun.mustafaalarm.data.local.AlarmDatabase
import com.atatuzun.mustafaalarm.data.local.RoomLocalStore
import com.atatuzun.mustafaalarm.domain.CachedOccurrence
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.RingAction
import com.atatuzun.mustafaalarm.domain.RingingEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLocalStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AlarmDatabase
    private lateinit var store: RoomLocalStore

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java).build()
        store = RoomLocalStore(db.dao())
    }

    @After fun tearDown() { db.close() }

    @Test
    fun handled_roundTrip_upsert_andPrune() {
        store.markHandled(InstanceKey(1, 1_000), RingAction.STOP, 5)
        store.markHandled(InstanceKey(2, 9_000), RingAction.SNOOZE, 6)
        store.markHandled(InstanceKey(1, 1_000), RingAction.SNOOZE, 7)
        assertEquals(setOf(InstanceKey(1, 1_000), InstanceKey(2, 9_000)), store.handledKeys())
        store.pruneHandled(5_000)
        assertEquals(setOf(InstanceKey(2, 9_000)), store.handledKeys())
    }

    @Test
    fun ringCache_keepsUnicodeTitles() {
        val title = "Fırat şap makinesi 🚚\nteklif ver " + "ğüşöçİ".repeat(60)
        store.replaceRingCache(listOf(CachedOccurrence(InstanceKey(3, 2_000), 3, title), CachedOccurrence(InstanceKey(4, 1_000), 9, "b")))
        assertEquals(listOf(InstanceKey(4, 1_000), InstanceKey(3, 2_000)), store.ringCache().map { it.key })
        assertEquals(title, store.ringCache().last().title)
        assertEquals(9L, store.ringCache().first().alarmId)
        store.addToRingCache(CachedOccurrence(InstanceKey(5, 500), 5, "c"))
        assertEquals(3, store.ringCache().size)
        store.replaceRingCache(emptyList())
        assertTrue(store.ringCache().isEmpty())
    }

    @Test
    fun ringing_addAndRemove() {
        store.addRinging(listOf(RingingEntry(InstanceKey(1, 10), 1, "a", 100), RingingEntry(InstanceKey(2, 10), 2, "b", 101)))
        assertEquals(listOf("a", "b"), store.ringing().map { it.title })
        store.removeRinging(listOf(InstanceKey(1, 10)))
        assertEquals(listOf(InstanceKey(2, 10)), store.ringing().map { it.key })
    }

    @Test
    fun pending_inPressOrder() {
        store.addPending(InstanceKey(1, 10), RingAction.SNOOZE, 20)
        store.addPending(InstanceKey(2, 10), RingAction.STOP, 10)
        val pending = store.pendingActions()
        assertEquals(listOf(10L, 20L), pending.map { it.pressedAt })
        assertEquals(RingAction.STOP, pending.first().action)
        store.removePending(pending.first().id)
        assertEquals(listOf(InstanceKey(1, 10)), store.pendingActions().map { it.key })
    }

    @Test
    fun soundAndHistory() {
        store.setSound(7, "content://x")
        assertEquals("content://x", store.soundFor(7))
        store.setSound(7, null)
        assertNull(store.soundFor(7))
        store.recordCreation(450, 100)
        store.recordCreation(480, 50)
        assertEquals(listOf(450 to 100L), store.creationHistory(60))
    }
}
```

`androidTest/java/com/atatuzun/mustafaalarm/data/SettingsRepositoryTest.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.data.settings.SettingsRepository
import com.atatuzun.mustafaalarm.data.settings.StopMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    @Test
    fun defaults_update_andClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "settings-test-${System.nanoTime()}"
        val repo = SettingsRepository(context, name)
        try {
            assertEquals(AlarmSettings(), repo.current())
            repo.updateBlocking { it.copy(snoozeMinutes = 10, stopMethod = StopMethod.ONE_PRESS, calendarId = 42, accountEmail = "a@b.c") }
            assertEquals(
                AlarmSettings(snoozeMinutes = 10, stopMethod = StopMethod.ONE_PRESS, calendarId = 42, accountEmail = "a@b.c"),
                repo.current(),
            )
            repo.updateBlocking { it.copy(calendarId = null) }
            assertNull(repo.current().calendarId)
        } finally {
            File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$name.preferences_pb").delete()
        }
    }
}
```

- [ ] **Step 2: Build to see it fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:assembleDebugAndroidTest
```
Expected: FAIL — `Unresolved reference 'AlarmDatabase'`, `'SettingsRepository'`.

- [ ] **Step 3: Implement**

`data/local/Entities.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarm_extras")
data class AlarmExtraEntity(@PrimaryKey val eventId: Long, val soundUri: String)

@Entity(tableName = "handled_instances", primaryKeys = ["eventId", "originalBeginMillis"])
data class HandledEntity(val eventId: Long, val originalBeginMillis: Long, val action: String, val handledAt: Long)

@Entity(tableName = "creation_history")
data class CreationEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val hourMinute: Int, val createdAt: Long)

@Entity(tableName = "ring_cache", primaryKeys = ["eventId", "originalBeginMillis"])
data class RingCacheEntity(val eventId: Long, val originalBeginMillis: Long, val alarmId: Long, val ringAtMillis: Long, val title: String)

@Entity(tableName = "ringing_now", primaryKeys = ["eventId", "originalBeginMillis"])
data class RingingEntity(val eventId: Long, val originalBeginMillis: Long, val alarmId: Long, val title: String, val startedAt: Long)

@Entity(tableName = "pending_actions")
data class PendingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val originalBeginMillis: Long,
    val action: String,
    val pressedAt: Long,
)
```

`data/local/LocalDao.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
abstract class LocalDao {
    @Query("SELECT * FROM handled_instances") abstract fun handled(): List<HandledEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun insertHandled(entity: HandledEntity)
    @Query("DELETE FROM handled_instances WHERE originalBeginMillis < :before") abstract fun pruneHandled(before: Long)

    @Query("SELECT soundUri FROM alarm_extras WHERE eventId = :alarmId") abstract fun sound(alarmId: Long): String?
    @Upsert abstract fun upsertExtra(entity: AlarmExtraEntity)
    @Query("DELETE FROM alarm_extras WHERE eventId = :alarmId") abstract fun deleteExtra(alarmId: Long)

    @Insert abstract fun insertCreation(entity: CreationEntity)
    @Query("SELECT * FROM creation_history WHERE createdAt >= :since") abstract fun creations(since: Long): List<CreationEntity>

    @Query("SELECT * FROM ring_cache ORDER BY ringAtMillis, eventId") abstract fun ringCache(): List<RingCacheEntity>
    @Query("DELETE FROM ring_cache") abstract fun clearRingCache()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun insertRingCache(items: List<RingCacheEntity>)

    @Transaction
    open fun replaceRingCache(items: List<RingCacheEntity>) {
        clearRingCache()
        insertRingCache(items)
    }

    @Query("SELECT * FROM ringing_now ORDER BY startedAt, eventId") abstract fun ringing(): List<RingingEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun insertRinging(items: List<RingingEntity>)
    @Query("DELETE FROM ringing_now WHERE eventId = :eventId AND originalBeginMillis = :begin") abstract fun deleteRinging(eventId: Long, begin: Long)

    @Query("SELECT * FROM pending_actions ORDER BY pressedAt, id") abstract fun pending(): List<PendingEntity>
    @Insert abstract fun insertPending(entity: PendingEntity)
    @Query("DELETE FROM pending_actions WHERE id = :id") abstract fun deletePending(id: Long)
}
```

`data/local/AlarmDatabase.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AlarmExtraEntity::class, HandledEntity::class, CreationEntity::class, RingCacheEntity::class, RingingEntity::class, PendingEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao

    companion object {
        /** Device-protected storage: readable before the first unlock after a reboot. */
        fun open(context: Context): AlarmDatabase =
            Room.databaseBuilder(context.createDeviceProtectedStorageContext(), AlarmDatabase::class.java, "mustafa-alarm.db").build()
    }
}
```

`data/local/RoomLocalStore.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data.local

import com.atatuzun.mustafaalarm.domain.CachedOccurrence
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.LocalStore
import com.atatuzun.mustafaalarm.domain.PendingAction
import com.atatuzun.mustafaalarm.domain.RingAction
import com.atatuzun.mustafaalarm.domain.RingingEntry

class RoomLocalStore(private val dao: LocalDao) : LocalStore {
    override fun handledKeys(): Set<InstanceKey> = dao.handled().mapTo(HashSet()) { InstanceKey(it.eventId, it.originalBeginMillis) }
    override fun markHandled(key: InstanceKey, action: RingAction, at: Long) = dao.insertHandled(HandledEntity(key.eventId, key.begin, action.name, at))
    override fun pruneHandled(before: Long) = dao.pruneHandled(before)

    override fun soundFor(alarmId: Long): String? = dao.sound(alarmId)
    override fun setSound(alarmId: Long, uri: String?) {
        if (uri == null) dao.deleteExtra(alarmId) else dao.upsertExtra(AlarmExtraEntity(alarmId, uri))
    }

    override fun recordCreation(minuteOfDay: Int, at: Long) = dao.insertCreation(CreationEntity(hourMinute = minuteOfDay, createdAt = at))
    override fun creationHistory(since: Long): List<Pair<Int, Long>> = dao.creations(since).map { it.hourMinute to it.createdAt }

    override fun ringCache(): List<CachedOccurrence> =
        dao.ringCache().map { CachedOccurrence(InstanceKey(it.eventId, it.originalBeginMillis), it.alarmId, it.title) }
    override fun replaceRingCache(items: List<CachedOccurrence>) = dao.replaceRingCache(items.map { it.toEntity() })
    override fun addToRingCache(item: CachedOccurrence) = dao.insertRingCache(listOf(item.toEntity()))

    override fun ringing(): List<RingingEntry> =
        dao.ringing().map { RingingEntry(InstanceKey(it.eventId, it.originalBeginMillis), it.alarmId, it.title, it.startedAt) }
    override fun addRinging(entries: List<RingingEntry>) =
        dao.insertRinging(entries.map { RingingEntity(it.key.eventId, it.key.begin, it.alarmId, it.title, it.startedAt) })
    override fun removeRinging(keys: Collection<InstanceKey>) = keys.forEach { dao.deleteRinging(it.eventId, it.begin) }

    override fun pendingActions(): List<PendingAction> =
        dao.pending().map { PendingAction(it.id, InstanceKey(it.eventId, it.originalBeginMillis), RingAction.valueOf(it.action), it.pressedAt) }
    override fun addPending(key: InstanceKey, action: RingAction, pressedAt: Long) =
        dao.insertPending(PendingEntity(eventId = key.eventId, originalBeginMillis = key.begin, action = action.name, pressedAt = pressedAt))
    override fun removePending(id: Long) = dao.deletePending(id)

    private fun CachedOccurrence.toEntity() = RingCacheEntity(key.eventId, key.begin, alarmId, ringAt, title)
}
```

`data/settings/AlarmSettings.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data.settings

enum class StopMethod { ONE_PRESS, THREE_PRESSES }

enum class SoundMode { SOUND_AND_VIBRATION, SOUND_ONLY, VIBRATION_ONLY }

/** Spec §9.5 defaults. calendarId/accountEmail are filled by first-run setup. */
data class AlarmSettings(
    val snoozeMinutes: Int = 30,
    val autoSnoozeMinutes: Int = 1,
    val showSnoozeButton: Boolean = true,
    val stopMethod: StopMethod = StopMethod.THREE_PRESSES,
    val soundMode: SoundMode = SoundMode.SOUND_AND_VIBRATION,
    val defaultSoundUri: String? = null,
    val volumePercent: Int = 100,
    val increaseDeviceVolume: Boolean = true,
    val fadeIn: Boolean = true,
    val use24Hour: Boolean = true,
    val darkTheme: Boolean = true,
    val nextAlarmNotification: Boolean = true,
    val accountEmail: String? = null,
    val calendarId: Long? = null,
    val calendarSyncId: String? = null,
    val lastCalendarChange: Long? = null,
)
```

`data/settings/SettingsRepository.kt`:
```kotlin
package com.atatuzun.mustafaalarm.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Settings in device-protected storage (readable before first unlock). One instance per process (AppGraph). */
class SettingsRepository(context: Context, fileName: String = "settings") {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.createDeviceProtectedStorageContext().preferencesDataStoreFile(fileName) },
    )

    val flow: Flow<AlarmSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    /** Blocking read for receivers, services and workers. Never call on the main thread. */
    fun current(): AlarmSettings = runBlocking { flow.first() }

    suspend fun update(transform: (AlarmSettings) -> AlarmSettings) {
        store.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }

    fun updateBlocking(transform: (AlarmSettings) -> AlarmSettings) = runBlocking { update(transform) }
}

private object Keys {
    val snooze = intPreferencesKey("snoozeMinutes")
    val autoSnooze = intPreferencesKey("autoSnoozeMinutes")
    val showSnooze = booleanPreferencesKey("showSnoozeButton")
    val stopMethod = stringPreferencesKey("stopMethod")
    val soundMode = stringPreferencesKey("soundMode")
    val defaultSound = stringPreferencesKey("defaultSoundUri")
    val volume = intPreferencesKey("volumePercent")
    val increaseVolume = booleanPreferencesKey("increaseDeviceVolume")
    val fadeIn = booleanPreferencesKey("fadeIn")
    val use24Hour = booleanPreferencesKey("use24Hour")
    val darkTheme = booleanPreferencesKey("darkTheme")
    val nextNotification = booleanPreferencesKey("nextAlarmNotification")
    val accountEmail = stringPreferencesKey("accountEmail")
    val calendarId = longPreferencesKey("calendarId")
    val calendarSyncId = stringPreferencesKey("calendarSyncId")
    val lastCalendarChange = longPreferencesKey("lastCalendarChange")
}

private fun Preferences.toSettings(): AlarmSettings {
    val d = AlarmSettings()
    return AlarmSettings(
        snoozeMinutes = this[Keys.snooze] ?: d.snoozeMinutes,
        autoSnoozeMinutes = this[Keys.autoSnooze] ?: d.autoSnoozeMinutes,
        showSnoozeButton = this[Keys.showSnooze] ?: d.showSnoozeButton,
        stopMethod = this[Keys.stopMethod]?.let { runCatching { StopMethod.valueOf(it) }.getOrNull() } ?: d.stopMethod,
        soundMode = this[Keys.soundMode]?.let { runCatching { SoundMode.valueOf(it) }.getOrNull() } ?: d.soundMode,
        defaultSoundUri = this[Keys.defaultSound],
        volumePercent = this[Keys.volume] ?: d.volumePercent,
        increaseDeviceVolume = this[Keys.increaseVolume] ?: d.increaseDeviceVolume,
        fadeIn = this[Keys.fadeIn] ?: d.fadeIn,
        use24Hour = this[Keys.use24Hour] ?: d.use24Hour,
        darkTheme = this[Keys.darkTheme] ?: d.darkTheme,
        nextAlarmNotification = this[Keys.nextNotification] ?: d.nextAlarmNotification,
        accountEmail = this[Keys.accountEmail],
        calendarId = this[Keys.calendarId],
        calendarSyncId = this[Keys.calendarSyncId],
        lastCalendarChange = this[Keys.lastCalendarChange],
    )
}

private fun AlarmSettings.writeTo(p: MutablePreferences) {
    p[Keys.snooze] = snoozeMinutes
    p[Keys.autoSnooze] = autoSnoozeMinutes
    p[Keys.showSnooze] = showSnoozeButton
    p[Keys.stopMethod] = stopMethod.name
    p[Keys.soundMode] = soundMode.name
    p.putOrRemove(Keys.defaultSound, defaultSoundUri)
    p[Keys.volume] = volumePercent
    p[Keys.increaseVolume] = increaseDeviceVolume
    p[Keys.fadeIn] = fadeIn
    p[Keys.use24Hour] = use24Hour
    p[Keys.darkTheme] = darkTheme
    p[Keys.nextNotification] = nextAlarmNotification
    p.putOrRemove(Keys.accountEmail, accountEmail)
    p.putOrRemove(Keys.calendarId, calendarId)
    p.putOrRemove(Keys.calendarSyncId, calendarSyncId)
    p.putOrRemove(Keys.lastCalendarChange, lastCalendarChange)
}

private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}
```

`log/EventLog.kt`:
```kotlin
package com.atatuzun.mustafaalarm.log

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Append-only log of every step (spec §4 EventLog): app file in device-protected storage + logcat tag MustafaAlarm. */
class EventLog(context: Context) {
    private val file = File(context.createDeviceProtectedStorageContext().filesDir, "event-log.txt")

    @Synchronized
    fun log(message: String) {
        Log.i(TAG, message)
        runCatching {
            if (file.length() > MAX_BYTES) file.renameTo(File(file.parentFile, "event-log.1.txt"))
            file.appendText("${time(System.currentTimeMillis())} $message\n")
        }
    }

    companion object {
        const val TAG = "MustafaAlarm"
        private const val MAX_BYTES = 1_000_000L
        private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun time(millis: Long): String =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(format)
    }
}
```

- [ ] **Step 4: Run on the emulator**

```powershell
. .\scripts\droid.ps1
Start-Emu
Gradle :app:installDebug :app:installDebugAndroidTest
$dir = New-Evidence 'task09'
Instrument 'com.atatuzun.mustafaalarm.data.RoomLocalStoreTest' | Tee-Object "$dir\room.txt"
Instrument 'com.atatuzun.mustafaalarm.data.SettingsRepositoryTest' | Tee-Object "$dir\settings.txt"
```
Expected: `OK (5 tests)` and `OK (1 test)`.

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/data mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/log mustafa-alarm/app/src/androidTest/java/com/atatuzun/mustafaalarm/data
Commit "feat(data): device-protected Room store, settings and event log"
```

### Task 10: Wiring and scheduling — AppGraph, Scheduler, receivers, change job, safety check, debug commands

After this task alarms are registered with AlarmManager and fire **silently** (logged and marked handled); Task 11 adds the ringer.

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/{MustafaAlarmApp,AppGraph}.kt`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/schedule/Scheduler.kt`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ring/{Notifications,AlarmReceiver}.kt`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/watch/{SystemEventReceiver,SafetyCheckWorker}.kt`
- Modify (replace whole file): `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/watch/CalendarChangeJob.kt`, `mustafa-alarm/app/src/main/AndroidManifest.xml`
- Create: `mustafa-alarm/app/src/debug/AndroidManifest.xml`, `mustafa-alarm/app/src/debug/java/com/atatuzun/mustafaalarm/debug/DebugCommandReceiver.kt`
- Test: `scripts/check-schedule.ps1`

**Interfaces:**
- Consumes: Tasks 3–9.
- Produces:
  - `class AppGraph(context)` with `context`, `clock: java.time.Clock`, `log: EventLog`, `settings: SettingsRepository`, `background: ExecutorService`, `changes: MutableSharedFlow<Unit>`, `local: LocalStore`, `calendar: ProviderCalendarAccess`, `store: AlarmStore`, `notifications: Notifications`, `scheduler: Scheduler`, `calendarAvailable(): Boolean`; extension `Context.graph`.
  - `Scheduler.reschedule(reason: String): Long?` (blocking, `@Synchronized`, logs `reschedule[<reason>] next=<yyyy-MM-dd HH:mm:ss|none> cached=<n>`), `Scheduler.systemNextAlarmClock(): Long?`.
  - `Notifications(context)` with `manager`, `createChannels()`, `showNextAlarm(next: CachedOccurrence?, settings)`, constants `CHANNEL_RINGING="ringing"`, `CHANNEL_NEXT="next_alarm"`, `ID_RINGING=1`, `ID_NEXT=2`.
  - `AlarmReceiver.ACTION_FIRE = "com.atatuzun.mustafaalarm.ALARM_FIRE"`; `SafetyCheckWorker.schedule(context)`; `CalendarChangeJob.schedule(context)` (unchanged name).
  - Debug broadcasts (adb only): `USE_LOCAL_CALENDAR`, `CREATE` (`--es time HH:mm` | `--ei inMinutes N`, `--es date yyyy-MM-dd`, `--es days MO,WE`, `--es msg Words_with_underscores`), `LIST` (logs `debug: item id=<id> '<title>' at <time> on=<bool> kind=<KIND>`), `DELETE_ALL`, `SETTINGS` (`--ez increaseDeviceVolume`, `--ei volumePercent`, `--ei snoozeMinutes`, `--ei autoSnoozeMinutes`, `--ez fadeIn`), `RESCHEDULE`.

- [ ] **Step 1: Write the failing device check** — `scripts/check-schedule.ps1`

```powershell
param([string]$Device = 'emulator-5560')
. "$PSScriptRoot\droid.ps1"
$env:DEVICE = $Device
if ($Device -notlike 'emulator-*') { throw 'this check deletes all alarms: emulator only' }
$out = New-Evidence 'check-schedule'

Grant-All
Mark-AppLog
Debug-Cmd USE_LOCAL_CALENDAR
Wait-AppLog 'debug: using local calendar \d+' 30 | Out-Null
Debug-Cmd DELETE_ALL

# 1. A new alarm is registered as an alarm clock and shown in the next-alarm notification.
Debug-Cmd CREATE @('--ei', 'inMinutes', '30', '--es', 'msg', 'Schedule_check')
Wait-AppLog 'reschedule\[debug-CREATE\] next=\d{4}-\d\d-\d\d \d\d:\d\d:\d\d' 30 | Out-Null
$alarm = A shell dumpsys alarm | Out-String
$alarm | Out-File -Encoding utf8 "$out\dumpsys-alarm.txt"
if ($alarm -notmatch [regex]::Escape("$Pkg.ALARM_FIRE")) { throw 'our alarm is not registered with AlarmManager' }
$notif = A shell dumpsys notification --noredact | Out-String
$notif | Out-File -Encoding utf8 "$out\dumpsys-notification.txt"
if ($notif -notmatch 'Next: \d\d:\d\d \S{1,3} Schedule check') { throw 'next-alarm notification missing' }

# 2. Our own write triggers the calendar-change job, which reschedules.
Wait-AppLog 'calendar changed' 60 | Out-Null
Wait-AppLog 'reschedule\[calendar-change\]' 30 | Out-Null
$jobs = A shell dumpsys jobscheduler | Out-String
if ($jobs -notmatch 'CalendarChangeJob') { throw 'calendar change job not scheduled' }
if ($jobs -notmatch 'SystemJobService') { throw 'WorkManager safety check not scheduled' }

# 3. An alarm one minute ahead fires (silently in this task).
Debug-Cmd CREATE @('--ei', 'inMinutes', '1', '--es', 'msg', 'Fire_check')
Wait-AppLog "fired: 'Fire check'" 150 | Out-Null

# 4. After a reboot the alarm is registered again.
A reboot
Start-Sleep 20
Wait-Boot | Out-Null
Wait-AppLog 'system event android.intent.action.BOOT_COMPLETED' 120 | Out-Null
Wait-AppLog 'reschedule\[android.intent.action.BOOT_COMPLETED\]' 60 | Out-Null
if ((A shell dumpsys alarm | Out-String) -notmatch [regex]::Escape("$Pkg.ALARM_FIRE")) { throw 'alarm lost after reboot' }

AppLog | Out-File -Encoding utf8 "$out\event-log.txt"
'check-schedule: PASS'
```

- [ ] **Step 2: Run it to see it fail**

```powershell
. .\scripts\droid.ps1
Start-Emu
Gradle :app:installDebug
.\scripts\check-schedule.ps1
```
Expected: FAIL at the first `Wait-AppLog` (no debug receiver yet → `run-as … event-log.txt: No such file` / timeout).

- [ ] **Step 3: Implement**

`main/java/com/atatuzun/mustafaalarm/AppGraph.kt`:
```kotlin
package com.atatuzun.mustafaalarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import com.atatuzun.mustafaalarm.data.calendar.ProviderCalendarAccess
import com.atatuzun.mustafaalarm.data.local.AlarmDatabase
import com.atatuzun.mustafaalarm.data.local.RoomLocalStore
import com.atatuzun.mustafaalarm.data.settings.SettingsRepository
import com.atatuzun.mustafaalarm.domain.AlarmStore
import com.atatuzun.mustafaalarm.domain.LocalStore
import com.atatuzun.mustafaalarm.log.EventLog
import com.atatuzun.mustafaalarm.ring.Notifications
import com.atatuzun.mustafaalarm.schedule.Scheduler
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Manual dependency graph, one per process. Everything here is safe before first unlock (device-protected storage). */
class AppGraph(val context: Context) {
    val clock: Clock = DeviceClock()
    val log = EventLog(context)
    val settings = SettingsRepository(context)
    val background: ExecutorService = Executors.newSingleThreadExecutor()

    /** Emits after every reschedule so screens reload. */
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val local: LocalStore by lazy { RoomLocalStore(AlarmDatabase.open(context).dao()) }
    val calendar: ProviderCalendarAccess by lazy { ProviderCalendarAccess(context.contentResolver) }
    val store: AlarmStore by lazy {
        AlarmStore(
            calendar = calendar,
            local = local,
            clock = clock,
            calendarId = { settings.current().calendarId },
            snoozeMinutes = { settings.current().snoozeMinutes },
            calendarAvailable = { calendarAvailable() },
            log = { log.log(it) },
        )
    }
    val notifications: Notifications by lazy { Notifications(context) }
    val scheduler: Scheduler by lazy { Scheduler(context, store, local, settings, notifications, log) { changes.tryEmit(Unit) } }

    /** The calendar provider is readable: user unlocked since boot and calendar permission granted. */
    fun calendarAvailable(): Boolean =
        context.getSystemService(UserManager::class.java).isUserUnlocked &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
}

/** Follows time-zone changes (Clock.systemDefaultZone() would freeze the zone at creation). */
class DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}

val Context.graph: AppGraph get() = (applicationContext as MustafaAlarmApp).graph
```

`main/java/com/atatuzun/mustafaalarm/MustafaAlarmApp.kt`:
```kotlin
package com.atatuzun.mustafaalarm

import android.app.Application
import android.os.UserManager
import com.atatuzun.mustafaalarm.watch.CalendarChangeJob
import com.atatuzun.mustafaalarm.watch.SafetyCheckWorker

class MustafaAlarmApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifications.createChannels()
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            CalendarChangeJob.schedule(this)
            SafetyCheckWorker.schedule(this)
        }
    }
}
```

`main/java/com/atatuzun/mustafaalarm/ring/Notifications.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.atatuzun.mustafaalarm.R
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.domain.CachedOccurrence
import com.atatuzun.mustafaalarm.domain.Texts
import com.atatuzun.mustafaalarm.ui.MainActivity
import java.time.ZoneId

class Notifications(private val context: Context) {
    val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RINGING, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the service plays the alarm itself
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NEXT, "Next alarm", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    /** Silent, low-priority "Next: HH:MM · message" (spec §9.5). */
    fun showNextAlarm(next: CachedOccurrence?, settings: AlarmSettings) {
        if (next == null || !settings.nextAlarmNotification) {
            manager.cancel(ID_NEXT)
            return
        }
        val text = "Next: ${Texts.clock(next.ringAt, ZoneId.systemDefault(), settings.use24Hour)} · ${next.title}"
        val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_NEXT)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        manager.notify(ID_NEXT, notification)
    }

    companion object {
        const val CHANNEL_RINGING = "ringing"
        const val CHANNEL_NEXT = "next_alarm"
        const val ID_RINGING = 1
        const val ID_NEXT = 2
    }
}
```

`main/java/com/atatuzun/mustafaalarm/schedule/Scheduler.kt`:
```kotlin
package com.atatuzun.mustafaalarm.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.atatuzun.mustafaalarm.data.settings.SettingsRepository
import com.atatuzun.mustafaalarm.domain.AlarmStore
import com.atatuzun.mustafaalarm.domain.LocalStore
import com.atatuzun.mustafaalarm.domain.RingPlanner
import com.atatuzun.mustafaalarm.domain.Times
import com.atatuzun.mustafaalarm.log.EventLog
import com.atatuzun.mustafaalarm.ring.AlarmReceiver
import com.atatuzun.mustafaalarm.ring.Notifications
import com.atatuzun.mustafaalarm.ui.MainActivity

class Scheduler(
    private val context: Context,
    private val store: AlarmStore,
    private val local: LocalStore,
    private val settings: SettingsRepository,
    private val notifications: Notifications,
    private val log: EventLog,
    private val onChanged: () -> Unit,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Refreshes the ring cache and arms AlarmManager for the earliest cached time (spec §7). Never on the main thread. */
    @Synchronized
    fun reschedule(reason: String): Long? {
        val now = System.currentTimeMillis()
        val cache = store.refreshRingCache()
        val trigger = RingPlanner.nextTrigger(cache)
        val operation = firePendingIntent()
        when {
            trigger == null -> alarmManager.cancel(operation)
            alarmManager.canScheduleExactAlarms() ->
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showPendingIntent()), operation)
            else -> {
                // Without the exact-alarm permission the exact variants throw; inexact is the only legal fallback.
                log.log("reschedule[$reason]: exact alarms not allowed, using inexact setAndAllowWhileIdle")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
            }
        }
        runCatching {
            val next = cache.filter { it.ringAt >= Times.floorMinute(now) }.minByOrNull { it.ringAt }
            notifications.showNextAlarm(next, settings.current())
        }
        log.log("reschedule[$reason] next=${trigger?.let { EventLog.time(it) } ?: "none"} cached=${cache.size}")
        onChanged()
        return trigger
    }

    /** Trigger time of the system-wide next alarm clock (any app), or null. */
    fun systemNextAlarmClock(): Long? = alarmManager.nextAlarmClock?.triggerTime

    private fun firePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun showPendingIntent(): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}
```

`main/java/com/atatuzun/mustafaalarm/ring/AlarmReceiver.kt` (interim; Task 11 replaces `onReceive`):
```kotlin
package com.atatuzun.mustafaalarm.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.atatuzun.mustafaalarm.domain.RingAction
import com.atatuzun.mustafaalarm.domain.RingPlanner
import com.atatuzun.mustafaalarm.graph

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val graph = context.graph
        val pending = goAsync()
        graph.background.execute {
            try {
                val now = graph.clock.millis()
                val ringing = graph.local.ringing().mapTo(HashSet()) { it.key }
                val due = RingPlanner.due(graph.store.refreshRingCache(), graph.local.handledKeys(), ringing, now)
                due.forEach { graph.local.markHandled(it.key, RingAction.STOP, now) }
                graph.log.log("fired: ${due.joinToString { "'${it.title}'" }} (silent until the ringer exists)")
                graph.scheduler.reschedule("fired")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.atatuzun.mustafaalarm.ALARM_FIRE"
    }
}
```

`main/java/com/atatuzun/mustafaalarm/watch/SystemEventReceiver.kt`:
```kotlin
package com.atatuzun.mustafaalarm.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.atatuzun.mustafaalarm.graph

/** Boot (locked and unlocked), app update, clock/zone change, exact-alarm permission change → reschedule (spec §7). */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val graph = context.graph
        val pending = goAsync()
        graph.background.execute {
            try {
                graph.log.log("system event $action")
                if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    CalendarChangeJob.schedule(context)
                    SafetyCheckWorker.schedule(context)
                }
                graph.scheduler.reschedule(action)
            } catch (t: Throwable) {
                graph.log.log("system event $action failed: $t")
            } finally {
                pending.finish()
            }
        }
    }
}
```

`main/java/com/atatuzun/mustafaalarm/watch/CalendarChangeJob.kt` (replace the Task 2 version):
```kotlin
package com.atatuzun.mustafaalarm.watch

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import com.atatuzun.mustafaalarm.graph

/** Fires when the calendar provider changes (our writes or the Google sync adapter); re-armed after each run. */
class CalendarChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val graph = applicationContext.graph
        graph.background.execute {
            try {
                graph.log.log("calendar changed (${params.triggeredContentUris?.size ?: 0} uris)")
                graph.settings.updateBlocking { it.copy(lastCalendarChange = System.currentTimeMillis()) }
                graph.scheduler.reschedule("calendar-change")
            } catch (t: Throwable) {
                graph.log.log("calendar change handling failed: $t")
            } finally {
                schedule(applicationContext)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        const val JOB_ID = 1001

        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.Events.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1_000)
                .setTriggerContentMaxDelay(5_000)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
```

`main/java/com/atatuzun/mustafaalarm/watch/SafetyCheckWorker.kt`:
```kotlin
package com.atatuzun.mustafaalarm.watch

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.atatuzun.mustafaalarm.domain.RingPlanner
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.log.EventLog
import java.util.concurrent.TimeUnit

/** Every 15 minutes: verify our alarm clock is still armed, reschedule, re-arm the change job (spec §7). */
class SafetyCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val graph = applicationContext.graph
        val expected = RingPlanner.nextTrigger(graph.local.ringCache())
        val armed = graph.scheduler.systemNextAlarmClock()
        if (expected != null && expected > System.currentTimeMillis() && (armed == null || armed > expected)) {
            graph.log.log("safety: alarm for ${EventLog.time(expected)} was not armed (system next=${armed?.let { EventLog.time(it) }})")
        }
        graph.scheduler.reschedule("safety-check")
        CalendarChangeJob.schedule(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "safety-check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SafetyCheckWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
```

`debug/java/com/atatuzun/mustafaalarm/debug/DebugCommandReceiver.kt`:
```kotlin
package com.atatuzun.mustafaalarm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmInput
import com.atatuzun.mustafaalarm.domain.AlarmListState
import com.atatuzun.mustafaalarm.domain.MINUTE
import com.atatuzun.mustafaalarm.domain.Times
import com.atatuzun.mustafaalarm.domain.WeeklyRule
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.log.EventLog
import java.time.LocalDate
import java.time.LocalTime

/** adb-only test hooks (manifest requires android.permission.DUMP, which only the shell holds). */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val pending = goAsync()
        graph.background.execute {
            try {
                handle(context, graph, intent)
            } catch (t: Throwable) {
                graph.log.log("debug ${intent.action} failed: $t")
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, graph: AppGraph, intent: Intent) {
        val command = intent.action?.substringAfterLast('.') ?: return
        val zone = graph.clock.zone
        when (command) {
            "USE_LOCAL_CALENDAR" -> {
                val resolver = context.contentResolver
                val id = LocalCalendars.findByName(resolver, "Alarms") ?: LocalCalendars.create(resolver, "Alarms")
                graph.settings.updateBlocking { it.copy(calendarId = id) }
                graph.log.log("debug: using local calendar $id")
            }
            "CREATE" -> {
                val message = intent.getStringExtra("msg").orEmpty().replace('_', ' ')
                val days = intent.getStringExtra("days")?.split(',')?.mapNotNull { WeeklyRule.dayOf(it) }?.toSet().orEmpty()
                val inMinutes = intent.getIntExtra("inMinutes", -1)
                val input = if (inMinutes > 0) {
                    val at = Times.floorMinute(graph.clock.millis()) + inMinutes * MINUTE
                    AlarmInput(Times.localTime(at, zone), Times.localDate(at, zone), emptySet(), message, null)
                } else {
                    AlarmInput(
                        LocalTime.parse(intent.getStringExtra("time") ?: "08:00"),
                        intent.getStringExtra("date")?.let(LocalDate::parse),
                        days, message, null,
                    )
                }
                graph.log.log("debug: create -> ${graph.store.create(input, recordHistory = false)}")
            }
            "LIST" -> when (val state = graph.store.list()) {
                is AlarmListState.Ready -> state.sections.flatMap { it.items }.forEach {
                    graph.log.log("debug: item id=${it.eventId} '${it.title}' at ${EventLog.time(it.shownAt)} on=${it.on} kind=${it.kind}")
                }
                else -> graph.log.log("debug: list -> $state")
            }
            "DELETE_ALL" -> graph.settings.current().calendarId?.let { cal ->
                graph.calendar.events(cal).filter { it.originalId == null }.forEach { graph.calendar.deleteEvent(it.id) }
            }
            "SETTINGS" -> graph.settings.updateBlocking { s ->
                s.copy(
                    increaseDeviceVolume = if (intent.hasExtra("increaseDeviceVolume")) intent.getBooleanExtra("increaseDeviceVolume", true) else s.increaseDeviceVolume,
                    volumePercent = intent.getIntExtra("volumePercent", s.volumePercent),
                    snoozeMinutes = intent.getIntExtra("snoozeMinutes", s.snoozeMinutes),
                    autoSnoozeMinutes = intent.getIntExtra("autoSnoozeMinutes", s.autoSnoozeMinutes),
                    fadeIn = if (intent.hasExtra("fadeIn")) intent.getBooleanExtra("fadeIn", true) else s.fadeIn,
                )
            }
            "RESCHEDULE" -> Unit
        }
        graph.scheduler.reschedule("debug-$command")
    }
}
```

`debug/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <!-- adb-only test hooks: DUMP is held by the shell, never granted to normal apps -->
        <receiver
            android:name=".debug.DebugCommandReceiver"
            android:exported="true"
            android:permission="android.permission.DUMP" />
    </application>
</manifest>
```

Replace `main/AndroidManifest.xml` with:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.READ_CALENDAR" />
    <uses-permission android:name="android.permission.WRITE_CALENDAR" />
    <uses-permission android:name="android.permission.READ_SYNC_SETTINGS" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".MustafaAlarmApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.MustafaAlarm">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <receiver
            android:name=".ring.AlarmReceiver"
            android:directBootAware="true"
            android:exported="false" />

        <receiver
            android:name=".watch.SystemEventReceiver"
            android:directBootAware="true"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.TIME_SET" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
                <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />
            </intent-filter>
        </receiver>

        <service
            android:name=".watch.CalendarChangeJob"
            android:exported="false"
            android:permission="android.permission.BIND_JOB_SERVICE" />
    </application>
</manifest>
```

- [ ] **Step 4: Run the check to see it pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest :app:installDebug
.\scripts\check-schedule.ps1
```
Expected: last line `check-schedule: PASS`; evidence in `logs\check-schedule\`. Takes ~6 minutes (1-minute alarm + reboot).

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add scripts/check-schedule.ps1 mustafa-alarm/app/src/main mustafa-alarm/app/src/debug
Commit "feat: schedule alarms with AlarmManager, reschedule on boot/time/calendar changes"
```

### Task 11: The ringer — foreground service, sound, full-screen ringing screen, actions

**Files:**
- Create: `scripts/make_default_sound.py` → generates `mustafa-alarm/app/src/main/res/raw/default_alarm.wav`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ring/{AlarmPlayer,RingingState,RingingService,RingingActivity,RingingScreen}.kt`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/theme/Theme.kt`
- Modify (replace whole file): `ring/Notifications.kt`, `ring/AlarmReceiver.kt`
- Modify: `schedule/Scheduler.kt` (restart a silent ringer), `debug/DebugCommandReceiver.kt` (`RING_ACTION`), `AndroidManifest.xml` (service + activity)
- Test: `scripts/ring-scenarios.ps1`

**Interfaces:**
- Consumes: Tasks 3–10 (`AppGraph`, `AlarmStore.snooze/tomorrow/stop/refreshRingCache`, `RingPlanner.due`, `LocalStore.ringing/addRinging/removeRinging/soundFor`, `FadeCurve`).
- Produces:
  - `RingingService.ACTION_FIRE/ACTION_SNOOZE/ACTION_TOMORROW/ACTION_STOP/ACTION_AUTO_SNOOZE` (= `"com.atatuzun.mustafaalarm.FIRE"`, `…SNOOZE`, `…TOMORROW`, `…STOP`, `…AUTO_SNOOZE`), `RingingService.isRunning`, `RingingService.fire(context)`, `RingingService.command(context, action, keys: List<InstanceKey>? = null)`.
  - `RingingState.entries: StateFlow<List<RingingEntry>>`.
  - `ui.theme.MustafaAlarmTheme(dark: Boolean = true, content)` (used by all screens).
  - Log lines: `ringing: '<title>' at <time>[, …]`, `snooze: '<title>'`, `tomorrow: '<title>'`, `stop: '<title>'`, `auto-snooze`.
  - Test tags on the ringing screen: `ring-clock`, `ring-snooze`, `ring-tomorrow`, `ring-stop`, `ring-row-<eventId>`, `row-snooze-<eventId>`, `row-tomorrow-<eventId>`, `row-stop-<eventId>`.
  - `scripts/ring-scenarios.ps1 -Scenario <name> [-Device <serial>]`.

- [ ] **Step 1: Write the failing scenario script** — `scripts/ring-scenarios.ps1`

```powershell
param([Parameter(Mandatory)][string]$Scenario, [string]$Device = 'emulator-5560')
. "$PSScriptRoot\droid.ps1"
$env:DEVICE = $Device
$Out = New-Evidence "ring-scenarios\$Scenario"

function Note([string]$text) { $line = "$(Get-Date -Format 'HH:mm:ss') $text"; Write-Host $line; Add-Content -Encoding utf8 "$Out\steps.txt" $line }
function Evidence([string]$name) {
    Shot-To $Out $name
    A shell dumpsys notification --noredact | Out-File -Encoding utf8 "$Out\$name-notification.txt"
    A shell dumpsys audio | Out-File -Encoding utf8 "$Out\$name-audio.txt"
    A shell dumpsys alarm | Out-File -Encoding utf8 "$Out\$name-alarm.txt"
    AppLog | Out-File -Encoding utf8 "$Out\$name-eventlog.txt"
}
function Assert-Ringing {
    if ((A shell dumpsys audio | Out-String) -notmatch 'state:started[^\r\n]*usage=USAGE_ALARM') { throw 'no alarm audio playing' }
    if ((A shell dumpsys notification --noredact | Out-String) -notmatch "pkg=$([regex]::Escape($Pkg)) [^\r\n]*id=1 ") { throw 'no ringing notification' }
    Note 'verified: alarm audio playing + ringing notification posted'
}
function Assert-Silent {
    Start-Sleep 3
    if ((A shell dumpsys audio | Out-String) -match 'state:started[^\r\n]*usage=USAGE_ALARM') { throw 'alarm audio still playing' }
    Note 'verified: silent'
}
function New-Alarm([int]$minutes, [string]$msg) {
    Debug-Cmd CREATE @('--ei', 'inMinutes', "$minutes", '--es', 'msg', $msg)
    $id = Last-CreatedId
    Note "created '$msg' in $minutes min (event $id)"
    A shell input keyevent KEYCODE_SLEEP | Out-Null   # screen off: the full-screen intent then opens the ringing screen
    $id
}
function Wait-Ring([string]$title, [int]$seconds = 150) {
    $m = Wait-AppLog "ringing: [^\r\n]*'$title' at \d{4}-\d\d-\d\d \d\d:\d\d:\d\d" $seconds
    Note "rang: $m"
    Start-Sleep 4
    [datetime]::ParseExact([regex]::Match($m, "'$title' at (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)").Groups[1].Value, 'yyyy-MM-dd HH:mm:ss', $null)
}
function Press-Stop { 1..3 | ForEach-Object { Tap-Id 'ring-stop' } }
function Listed([string]$title) {
    Debug-Cmd LIST
    $m = [regex]::Matches((AppLog | Out-String), "debug: item id=\d+ '$title' at (\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) on=(\w+)")
    if ($m.Count -eq 0) { throw "'$title' not listed" }
    $last = $m[$m.Count - 1]
    [pscustomobject]@{ At = [datetime]::ParseExact($last.Groups[1].Value, 'yyyy-MM-dd HH:mm:ss', $null); On = $last.Groups[2].Value -eq 'true' }
}

Grant-All
Mark-AppLog
if ($Device -like 'emulator-*') {
    Debug-Cmd USE_LOCAL_CALENDAR   # never on the phone: there the real "Alarms" calendar is used
    Debug-Cmd DELETE_ALL
}
Debug-Cmd SETTINGS @('--ez', 'increaseDeviceVolume', 'false', '--ei', 'volumePercent', '20', '--ei', 'autoSnoozeMinutes', '1', '--ei', 'snoozeMinutes', '30')
A shell input keyevent KEYCODE_WAKEUP | Out-Null
A shell input keyevent KEYCODE_HOME | Out-Null

switch ($Scenario) {
    'basic' {
        New-Alarm 1 'Basic_ring' | Out-Null
        Wait-Ring 'Basic ring' | Out-Null
        Assert-Ringing; Assert-Text 'Basic ring'; Evidence 'ringing'
        Tap-Id 'ring-stop'; Assert-Text 'Stop (2 more)'
        Tap-Id 'ring-stop'; Tap-Id 'ring-stop'
        Wait-AppLog "stop: 'Basic ring'" 20 | Out-Null
        Assert-Silent; Evidence 'stopped'
        if ((Listed 'Basic ring').On) { throw 'a stopped one-off must be grey (Graphite)' }
    }
    'snooze' {
        New-Alarm 1 'Snooze_test' | Out-Null
        $rang = Wait-Ring 'Snooze test'
        Assert-Ringing; Evidence 'ringing'
        Tap-Id 'ring-snooze'
        Wait-AppLog "snooze: 'Snooze test'" 20 | Out-Null
        Assert-Silent
        $at = (Listed 'Snooze test').At
        $delta = ($at - $rang).TotalMinutes
        if ($delta -lt 30 -or $delta -gt 31) { throw "snoozed to $at; expected 30-31 minutes after $rang" }
        Note "snoozed to $at"; Evidence 'snoozed'
    }
    'tomorrow' {
        New-Alarm 1 'Tomorrow_test' | Out-Null
        $rang = Wait-Ring 'Tomorrow test'
        Assert-Ringing
        Tap-Id 'ring-tomorrow'
        Wait-AppLog "tomorrow: 'Tomorrow test'" 20 | Out-Null
        Assert-Silent
        $at = (Listed 'Tomorrow test').At
        if ($at -ne $rang.AddDays(1)) { throw "moved to $at; expected $($rang.AddDays(1))" }
        Note "moved to $at"; Evidence 'moved'
    }
    'row-stop' {
        $one = New-Alarm 1 'Row_one'
        New-Alarm 1 'Row_two' | Out-Null
        Wait-Ring 'Row two' | Out-Null
        Assert-Text 'Row one'; Assert-Text 'Row two'
        Tap-Id "ring-row-$one"; Tap-Id "row-stop-$one"
        Wait-AppLog "stop: 'Row one'" 20 | Out-Null
        Assert-Ringing
        if (Find-Node 'Row one') { throw "'Row one' is still on the ringing screen" }
        Evidence 'one-stopped'
        Press-Stop
        Wait-AppLog "stop: 'Row two'" 20 | Out-Null
        Assert-Silent
    }
    default { throw "unknown scenario $Scenario" }
}
"ring-scenarios ${Scenario}: PASS"
```

- [ ] **Step 2: Run it to see it fail**

```powershell
. .\scripts\droid.ps1
Start-Emu
Gradle :app:installDebug
.\scripts\ring-scenarios.ps1 -Scenario basic
```
Expected: FAIL — `timed out … waiting for /ringing: …'Basic ring'…/` (Task 10 fires silently).

- [ ] **Step 3: Generate the bundled sound** — `scripts/make_default_sound.py`

```python
"""Writes mustafa-alarm/app/src/main/res/raw/default_alarm.wav: our own two-tone alarm loop (no third-party audio)."""
import math
import struct
import wave
from pathlib import Path

RATE = 44100
OUT = Path(__file__).resolve().parent.parent / "mustafa-alarm" / "app" / "src" / "main" / "res" / "raw" / "default_alarm.wav"


def tone(freq, seconds, volume=0.8):
    n = int(RATE * seconds)
    fade = int(RATE * 0.01)
    samples = []
    for i in range(n):
        envelope = min(1.0, i / fade, (n - i) / fade)
        value = math.sin(2 * math.pi * freq * i / RATE) + 0.3 * math.sin(4 * math.pi * freq * i / RATE)
        samples.append(volume * envelope * value / 1.3)
    return samples


def silence(seconds):
    return [0.0] * int(RATE * seconds)


def main():
    loop = []
    for _ in range(2):
        loop += tone(880, 0.12) + silence(0.06) + tone(1320, 0.12) + silence(0.06)
    loop += silence(0.8)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUT), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in loop))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
```

```powershell
.\.venv\Scripts\python.exe .\scripts\make_default_sound.py
```
Expected: `wrote …\res\raw\default_alarm.wav (≈134000 bytes)`.

- [ ] **Step 4: Implement the ringer**

`ui/theme/Theme.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF0B1A33),
    secondary = Color(0xFF8AB4F8), onSecondary = Color(0xFF0B1A33),
    background = Color(0xFF0D1117), onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF0D1117), onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF4A5670), onSurfaceVariant = Color(0xFFE6E8EB),
    surfaceContainer = Color(0xFF1B2230),
    error = Color(0xFFB00000),
)

private val LightColors = lightColorScheme(primary = Color(0xFF1A56C4), secondary = Color(0xFF1A56C4))

@Composable
fun MustafaAlarmTheme(dark: Boolean = true, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
```

`ring/RingingState.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ring

import com.atatuzun.mustafaalarm.domain.RingingEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What is ringing right now, published by RingingService for RingingActivity. */
object RingingState {
    private val mutable = MutableStateFlow<List<RingingEntry>>(emptyList())
    val entries: StateFlow<List<RingingEntry>> = mutable

    fun publish(entries: List<RingingEntry>) {
        mutable.value = entries
    }
}
```

`ring/AlarmPlayer.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.atatuzun.mustafaalarm.R
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.data.settings.SoundMode
import com.atatuzun.mustafaalarm.domain.FadeCurve

/** Plays the alarm on the ALARM stream, loops it, fades it in, vibrates (spec §8). Main thread only. */
class AlarmPlayer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var restoreVolume: Int? = null
    private var fadeStart = 0L
    private var target = 1f

    private val fade = object : Runnable {
        override fun run() {
            val volume = target * FadeCurve.volumeAt(SystemClock.elapsedRealtime() - fadeStart)
            media?.setVolume(volume, volume)
            if (volume < target) handler.postDelayed(this, 500)
        }
    }

    /** [sound] null = bundled default (before first unlock, or when the chosen sound cannot be opened). */
    fun start(sound: Uri?, settings: AlarmSettings) {
        if (settings.increaseDeviceVolume) {
            // runCatching: changing volume can throw under some Do Not Disturb modes; ringing must go on regardless.
            runCatching {
                val before = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                restoreVolume = before
            }
        }
        target = settings.volumePercent.coerceIn(1, 100) / 100f
        if (settings.soundMode != SoundMode.VIBRATION_ONLY) {
            media = sound?.let { open(it) } ?: open(null)
            if (settings.fadeIn) {
                fadeStart = SystemClock.elapsedRealtime()
                fade.run()
            } else {
                media?.setVolume(target, target)
            }
        }
        if (settings.soundMode != SoundMode.SOUND_ONLY) {
            vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator.also {
                it.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(fade)
        media?.runCatching { stop(); release() }
        media = null
        vibrator?.cancel()
        vibrator = null
        restoreVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        restoreVolume = null
    }

    private fun open(uri: Uri?): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            if (uri != null) setDataSource(context, uri)
            else context.resources.openRawResourceFd(R.raw.default_alarm).use { setDataSource(it) }
            isLooping = true
            prepare()
            start()
        }
    }.getOrNull()
}
```

`ring/RingingService.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.RingPlanner
import com.atatuzun.mustafaalarm.domain.RingingEntry
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.log.EventLog
import java.util.concurrent.Executors

/**
 * Foreground (mediaPlayback) service that rings (spec §8). All work runs on one background thread, in order,
 * so a double press or two simultaneous fires are handled once. START_STICKY: a restart resumes from ringing_now.
 */
class RingingService : Service() {
    private val graph get() = applicationContext.graph
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var player: AlarmPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var playingKeys: Set<InstanceKey> = emptySet()
    private val autoSnooze = Runnable { executor.execute { handle(ACTION_AUTO_SNOOZE, null) } }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacksAndMessages(null)
        player?.stop()
        player = null
        releaseWakeLock()
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                Notifications.ID_RINGING,
                graph.notifications.ringing(RingingState.entries.value, AlarmSettings()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: Exception) {
            // e.g. a START_STICKY restart that may not go foreground: AlarmManager starts us again legitimately.
            graph.log.log("ringing: cannot go foreground ($e); retrying through AlarmManager")
            isRunning = false
            graph.background.execute { graph.scheduler.reschedule("ringer-retry") }
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent?.action ?: ACTION_RESUME
        val keys = intent?.getStringArrayListExtra(EXTRA_KEYS)?.map(::parseKey)
        executor.execute { handle(action, keys) }
        return START_STICKY
    }

    private fun handle(action: String, keys: List<InstanceKey>?) {
        try {
            when (action) {
                ACTION_FIRE -> fire()
                ACTION_SNOOZE, ACTION_TOMORROW, ACTION_STOP -> act(action, keys)
                ACTION_AUTO_SNOOZE -> {
                    graph.log.log("auto-snooze")
                    act(ACTION_SNOOZE, null)
                }
                else -> graph.log.log("ringing: resume (${graph.local.ringing().size} ringing)")
            }
        } catch (t: Throwable) {
            graph.log.log("ringing: $action failed: $t")
        }
        render()
    }

    private fun fire() {
        val now = graph.clock.millis()
        val cache = graph.store.refreshRingCache()
        val ringing = graph.local.ringing().mapTo(HashSet()) { it.key }
        val due = RingPlanner.due(cache, graph.local.handledKeys(), ringing, now)
        if (due.isEmpty()) {
            graph.log.log("ringing: fired with nothing due")
        } else {
            graph.local.addRinging(due.map { RingingEntry(it.key, it.alarmId, it.title, now) })
            graph.log.log("ringing: " + due.joinToString { "'${it.title}' at ${EventLog.time(it.ringAt)}" })
        }
        graph.scheduler.reschedule("fired")
    }

    private fun act(action: String, keys: List<InstanceKey>?) {
        val now = graph.clock.millis()
        val ringing = graph.local.ringing()
        val targets = if (keys == null) ringing else ringing.filter { it.key in keys }
        for (entry in targets) {
            when (action) {
                ACTION_SNOOZE -> graph.store.snooze(entry.key, now)
                ACTION_TOMORROW -> graph.store.tomorrow(entry.key, now)
                ACTION_STOP -> graph.store.stop(entry.key, now)
            }
            graph.log.log("${action.substringAfterLast('.').lowercase()}: '${entry.title}'")
        }
        graph.local.removeRinging(targets.map { it.key })
        graph.scheduler.reschedule(action.substringAfterLast('.').lowercase())
    }

    /** Publishes the state and starts/updates/stops sound on the main thread. */
    private fun render() {
        val ringing = graph.local.ringing()
        val settings = graph.settings.current()
        val sound = ringing.firstOrNull()?.let { soundFor(it, settings) }
        RingingState.publish(ringing)
        main.post { if (ringing.isEmpty()) finishRinging() else showRinging(ringing, settings, sound) }
    }

    private fun soundFor(entry: RingingEntry, settings: AlarmSettings): Uri? {
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return null // media not readable yet: bundled sound
        val chosen = graph.local.soundFor(entry.alarmId) ?: settings.defaultSoundUri
        return chosen?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }

    private fun showRinging(ringing: List<RingingEntry>, settings: AlarmSettings, sound: Uri?) {
        acquireWakeLock()
        graph.notifications.manager.notify(Notifications.ID_RINGING, graph.notifications.ringing(ringing, settings))
        if (player == null) player = AlarmPlayer(this).also { it.start(sound, settings) }
        val keys = ringing.mapTo(HashSet()) { it.key }
        if (!playingKeys.containsAll(keys)) { // a new alarm joined: restart the no-answer timer
            main.removeCallbacks(autoSnooze)
            main.postDelayed(autoSnooze, settings.autoSnoozeMinutes * 60_000L)
        }
        playingKeys = keys
    }

    private fun finishRinging() {
        main.removeCallbacks(autoSnooze)
        player?.stop()
        player = null
        playingKeys = emptySet()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MustafaAlarm:ringing")
            .apply { setReferenceCounted(false); acquire(10 * 60_000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_FIRE = "com.atatuzun.mustafaalarm.FIRE"
        const val ACTION_SNOOZE = "com.atatuzun.mustafaalarm.SNOOZE"
        const val ACTION_TOMORROW = "com.atatuzun.mustafaalarm.TOMORROW"
        const val ACTION_STOP = "com.atatuzun.mustafaalarm.STOP"
        const val ACTION_AUTO_SNOOZE = "com.atatuzun.mustafaalarm.AUTO_SNOOZE"
        private const val ACTION_RESUME = "com.atatuzun.mustafaalarm.RESUME"
        private const val EXTRA_KEYS = "keys"

        @Volatile
        var isRunning = false
            private set

        fun fire(context: Context) {
            context.startForegroundService(Intent(context, RingingService::class.java).setAction(ACTION_FIRE))
        }

        /** [keys] null = every ringing alarm. */
        fun command(context: Context, action: String, keys: List<InstanceKey>? = null) {
            val intent = Intent(context, RingingService::class.java).setAction(action)
            keys?.let { list -> intent.putStringArrayListExtra(EXTRA_KEYS, ArrayList(list.map { "${it.eventId}:${it.begin}" })) }
            if (isRunning) context.startService(intent) else context.startForegroundService(intent)
        }

        private fun parseKey(text: String): InstanceKey = text.split(':').let { InstanceKey(it[0].toLong(), it[1].toLong()) }
    }
}
```

`ring/RingingActivity.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ring

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.ui.theme.MustafaAlarmTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Full-screen ringing UI over the lock screen (spec §9.4). Works before first unlock. */
class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val graph = applicationContext.graph
        setContent {
            val entries by RingingState.entries.collectAsStateWithLifecycle()
            var settings by remember { mutableStateOf(AlarmSettings()) }
            var seen by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { settings = withContext(Dispatchers.IO) { graph.settings.current() } }
            LaunchedEffect(entries) { if (entries.isNotEmpty()) seen = true else if (seen) finish() }
            LaunchedEffect(Unit) { delay(5_000); if (!seen) finish() }
            MustafaAlarmTheme(dark = true) {
                RingingScreen(
                    entries = entries,
                    settings = settings,
                    onAll = { action -> RingingService.command(this@RingingActivity, action) },
                    onOne = { action, key -> RingingService.command(this@RingingActivity, action, listOf(key)) },
                )
            }
        }
    }
}
```

`ring/RingingScreen.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.data.settings.StopMethod
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.RingingEntry
import com.atatuzun.mustafaalarm.domain.Texts
import kotlinx.coroutines.delay
import java.time.ZoneId

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RingingScreen(
    entries: List<RingingEntry>,
    settings: AlarmSettings,
    onAll: (String) -> Unit,
    onOne: (String, InstanceKey) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    var stopPresses by remember { mutableIntStateOf(0) }
    val pressesNeeded = if (settings.stopMethod == StopMethod.THREE_PRESSES) 3 else 1
    var expanded by remember { mutableStateOf<InstanceKey?>(null) }

    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Texts.clock(now, zone, settings.use24Hour), fontSize = 88.sp, fontWeight = FontWeight.Light, modifier = Modifier.testTag("ring-clock"))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(entries, key = { "${it.key.eventId}:${it.key.begin}" }) { entry ->
                    Card(
                        onClick = { expanded = if (expanded == entry.key) null else entry.key },
                        modifier = Modifier.fillMaxWidth().testTag("ring-row-${entry.key.eventId}"),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.headlineSmall)
                            Text(Texts.clock(entry.ringAt, zone, settings.use24Hour), style = MaterialTheme.typography.bodyMedium)
                            if (expanded == entry.key) {
                                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (settings.showSnoozeButton) {
                                        OutlinedButton(onClick = { onOne(RingingService.ACTION_SNOOZE, entry.key) }, modifier = Modifier.testTag("row-snooze-${entry.key.eventId}")) { Text("Snooze") }
                                    }
                                    OutlinedButton(onClick = { onOne(RingingService.ACTION_TOMORROW, entry.key) }, modifier = Modifier.testTag("row-tomorrow-${entry.key.eventId}")) { Text("Tomorrow") }
                                    OutlinedButton(onClick = { onOne(RingingService.ACTION_STOP, entry.key) }, modifier = Modifier.testTag("row-stop-${entry.key.eventId}")) { Text("Stop") }
                                }
                            }
                        }
                    }
                }
            }
            if (settings.showSnoozeButton) BigButton("Snooze ${settings.snoozeMinutes} m", "ring-snooze") { onAll(RingingService.ACTION_SNOOZE) }
            BigButton("Tomorrow", "ring-tomorrow") { onAll(RingingService.ACTION_TOMORROW) }
            BigButton(if (stopPresses == 0) "Stop" else "Stop (${pressesNeeded - stopPresses} more)", "ring-stop") {
                stopPresses++
                if (stopPresses >= pressesNeeded) {
                    stopPresses = 0
                    onAll(RingingService.ACTION_STOP)
                }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(64.dp).testTag(tag)) {
        Text(label, fontSize = 22.sp)
    }
}
```

Replace `ring/Notifications.kt` with the Task 10 file plus the ringing notification:
```kotlin
package com.atatuzun.mustafaalarm.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.atatuzun.mustafaalarm.R
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.domain.CachedOccurrence
import com.atatuzun.mustafaalarm.domain.DEFAULT_TITLE
import com.atatuzun.mustafaalarm.domain.RingingEntry
import com.atatuzun.mustafaalarm.domain.Texts
import com.atatuzun.mustafaalarm.ui.MainActivity
import java.time.ZoneId

class Notifications(private val context: Context) {
    val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RINGING, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the service plays the alarm itself
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NEXT, "Next alarm", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    /** Silent, low-priority "Next: HH:MM · message" (spec §9.5). */
    fun showNextAlarm(next: CachedOccurrence?, settings: AlarmSettings) {
        if (next == null || !settings.nextAlarmNotification) {
            manager.cancel(ID_NEXT)
            return
        }
        val text = "Next: ${Texts.clock(next.ringAt, ZoneId.systemDefault(), settings.use24Hour)} · ${next.title}"
        val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_NEXT)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        manager.notify(ID_NEXT, notification)
    }

    /** The foreground notification while ringing: full-screen intent, category alarm, Snooze + Stop (spec §8). */
    fun ringing(entries: List<RingingEntry>, settings: AlarmSettings): Notification {
        val zone = ZoneId.systemDefault()
        val screen = PendingIntent.getActivity(
            context, 20,
            Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(entries.joinToString(" · ") { it.title }.ifEmpty { DEFAULT_TITLE })
            .setContentText(entries.firstOrNull()?.let { "Alarm ${Texts.clock(it.ringAt, zone, settings.use24Hour)}" } ?: "Alarm")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (settings.showSnoozeButton) builder.addAction(serviceAction("Snooze ${settings.snoozeMinutes} m", RingingService.ACTION_SNOOZE, 21))
        builder.addAction(serviceAction("Stop", RingingService.ACTION_STOP, 22))
        return builder.build()
    }

    private fun serviceAction(label: String, action: String, requestCode: Int): Notification.Action {
        val intent = PendingIntent.getForegroundService(
            context, requestCode, Intent(context, RingingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_alarm), label, intent).build()
    }

    companion object {
        const val CHANNEL_RINGING = "ringing"
        const val CHANNEL_NEXT = "next_alarm"
        const val ID_RINGING = 1
        const val ID_NEXT = 2
    }
}
```

Replace `ring/AlarmReceiver.kt` with:
```kotlin
package com.atatuzun.mustafaalarm.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.atatuzun.mustafaalarm.graph

/** AlarmManager → foreground ringing service (an exact-alarm broadcast may start a foreground service). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        context.graph.log.log("alarm fired")
        RingingService.fire(context)
    }

    companion object {
        const val ACTION_FIRE = "com.atatuzun.mustafaalarm.ALARM_FIRE"
    }
}
```

In `schedule/Scheduler.kt` add the import `import com.atatuzun.mustafaalarm.ring.RingingService` and replace the line
```kotlin
        val trigger = RingPlanner.nextTrigger(cache)
```
with
```kotlin
        // Marked as ringing but the ringer is not running (process was killed): restart it through AlarmManager.
        val ringingButSilent = local.ringing().isNotEmpty() && !RingingService.isRunning
        val trigger = if (ringingButSilent) now + 1_000 else RingPlanner.nextTrigger(cache)
```

In `debug/DebugCommandReceiver.kt` add `import com.atatuzun.mustafaalarm.ring.RingingService` and replace
```kotlin
            "RESCHEDULE" -> Unit
```
with
```kotlin
            "RESCHEDULE" -> Unit
            "RING_ACTION" -> RingingService.command(context, "com.atatuzun.mustafaalarm." + intent.getStringExtra("action").orEmpty())
```

In `AndroidManifest.xml`, inside `<application>` after the MainActivity block, add:
```xml
        <activity
            android:name=".ring.RingingActivity"
            android:directBootAware="true"
            android:excludeFromRecents="true"
            android:exported="false"
            android:launchMode="singleTask"
            android:showWhenLocked="true"
            android:taskAffinity="com.atatuzun.mustafaalarm.ringing"
            android:turnScreenOn="true" />

        <service
            android:name=".ring.RingingService"
            android:directBootAware="true"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
```

- [ ] **Step 5: Run the scenarios to see them pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest :app:installDebug
foreach ($s in 'basic', 'snooze', 'tomorrow', 'row-stop') { .\scripts\ring-scenarios.ps1 -Scenario $s }
.\scripts\check-schedule.ps1
```
Expected: four `ring-scenarios <name>: PASS` lines and `check-schedule: PASS` (its step 3 now waits for `fired`… — update that line in `check-schedule.ps1` from `Wait-AppLog "fired: 'Fire check'" 150` to `Wait-AppLog "ringing: [^\r\n]*'Fire check'" 150` and add `Debug-Cmd RING_ACTION @('--es', 'action', 'STOP')` right after it). Screenshots of the ringing screen are in `logs\ring-scenarios\<name>\`; open `basic\ringing.png` and confirm the big clock, the message and the three buttons are visible.

- [ ] **Step 6: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add scripts/make_default_sound.py scripts/ring-scenarios.ps1 scripts/check-schedule.ps1 mustafa-alarm/app/src/main mustafa-alarm/app/src/debug
Commit "feat(ring): foreground ringing service, full-screen ringing screen, snooze/tomorrow/stop"
```

### Task 12: Reliability scenarios on the emulator (spec §12 Layer 3, risk 5)

**Files:**
- Modify: `scripts/ring-scenarios.ps1` (helpers + nine scenarios)
- Create: `notes/test-report.md` (Layer 1–3 section)
- Modify: `notes/spike-results.md` (risk 5 row)

**Interfaces:**
- Consumes: Task 11 ringer, debug commands, `droid.ps1`.
- Produces: scenario names `locked`, `doze`, `killed`, `reboot-locked`, `timezone`, `update`, `auto-snooze`, `same-minute`, `missed-after-reboot` (Task 19 reruns a subset on the phone).

- [ ] **Step 1: Add the scenarios (the failing tests)**

In `scripts/ring-scenarios.ps1`, insert after the `Listed` function:
```powershell
function New-AlarmAt([datetime]$at, [string]$msg) {
    Debug-Cmd CREATE @('--es', 'time', $at.ToString('HH:mm'), '--es', 'date', $at.ToString('yyyy-MM-dd'), '--es', 'msg', $msg)
    Note "created '$msg' at $($at.ToString('HH:mm')) (event $(Last-CreatedId))"
}
function Set-Tz([string]$tz) {
    $r = A shell cmd alarm set-timezone $tz 2>&1 | Out-String
    if ($r -match '(?i)unknown|error|usage') { A root | Out-Null; Start-Sleep 3; A shell service call alarm 3 s16 $tz | Out-Null }
    Note "time zone set to $tz"
}
```

and insert before the line `    default { throw "unknown scenario $Scenario" }`:
```powershell
    'locked' {
        $emulator = $Device -like 'emulator-*'
        if ($emulator) { A shell locksettings set-pin 1234 | Out-Null }   # the phone already has Mustafa's own lock
        try {
            New-Alarm 1 'Locked_ring' | Out-Null
            Wait-Ring 'Locked ring' | Out-Null
            Assert-Ringing; Assert-Text 'Locked ring'; Evidence 'over-lock-screen'
            Press-Stop
            Wait-AppLog "stop: 'Locked ring'" 20 | Out-Null
            Assert-Silent
        } finally { if ($emulator) { A shell locksettings clear --old 1234 | Out-Null } }
    }
    'doze' {
        New-Alarm 2 'Doze_ring' | Out-Null
        A shell dumpsys battery unplug | Out-Null
        A shell dumpsys deviceidle force-idle | Out-File -Encoding utf8 "$Out\force-idle.txt"
        try {
            $rang = Wait-Ring 'Doze ring' 200
            $m = [regex]::Matches((AppLog | Out-String), "(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) ringing: [^\r\n]*'Doze ring'")
            $firedAt = [datetime]::ParseExact($m[$m.Count - 1].Groups[1].Value, 'yyyy-MM-dd HH:mm:ss', $null)
            $late = ($firedAt - $rang).TotalSeconds
            Note "rang $late s after its time while forced idle"
            if ($late -gt 10) { throw "rang $late s late in Doze" }
            Assert-Ringing; Evidence 'doze'
            Press-Stop; Wait-AppLog "stop: 'Doze ring'" 20 | Out-Null
        } finally {
            A shell dumpsys deviceidle unforce | Out-Null
            A shell dumpsys battery reset | Out-Null
        }
    }
    'killed' {
        New-Alarm 2 'Killed_app' | Out-Null
        A shell am kill $Pkg | Out-Null
        Note 'app process killed before the alarm'
        Wait-Ring 'Killed app' 200 | Out-Null
        Assert-Ringing
        if ($Device -like 'emulator-*') {
            A root | Out-Null; Start-Sleep 3
            $procId = (A shell pidof $Pkg | Out-String).Trim()
            Mark-AppLog
            A shell kill -9 $procId | Out-Null
            Note "killed process $procId while ringing"
            Wait-AppLog 'ringing: resume|ringing: fired' 120 | Out-Null
            Start-Sleep 5
            Assert-Ringing; Evidence 'resumed'
            A unroot | Out-Null
        } else {
            Note 'no root on the phone: only the kill before the alarm is tested'
        }
        Press-Stop; Wait-AppLog "stop: 'Killed app'" 30 | Out-Null
    }
    'reboot-locked' {
        $emulator = $Device -like 'emulator-*'
        if ($emulator) { A shell locksettings set-pin 1234 | Out-Null }
        try {
            New-Alarm 4 'Before_unlock' | Out-Null
            A reboot
            Start-Sleep 20
            Wait-Boot | Out-Null
            Note 'rebooted; never unlocked since boot'
            Wait-AppLog 'system event android.intent.action.LOCKED_BOOT_COMPLETED' 120 | Out-Null
            Wait-Ring 'Before unlock' 300 | Out-Null
            Assert-Ringing; Evidence 'ringing-before-unlock'
            Tap-Id 'ring-snooze'
            Wait-AppLog "snooze: 'Before unlock'" 30 | Out-Null
            Wait-AppLog 'snooze InstanceKey\([^)]*\) \(pending\)' 30 | Out-Null
            Assert-Silent
            if ($emulator) {
                A shell input keyevent KEYCODE_WAKEUP | Out-Null
                A shell wm dismiss-keyguard | Out-Null
                Start-Sleep 2
                A shell input text 1234 | Out-Null
                A shell input keyevent KEYCODE_ENTER | Out-Null
            } else {
                Write-Host '>>> Mustafa: please unlock the phone now <<<'
            }
            Wait-AppLog 'system event android.intent.action.BOOT_COMPLETED' 300 | Out-Null
            Wait-AppLog 'applied pending SNOOZE' 60 | Out-Null
            Note "after unlock the alarm is at $((Listed 'Before unlock').At)"
            Evidence 'after-unlock'
        } finally { if ($emulator) { A shell locksettings clear --old 1234 | Out-Null } }
    }
    'timezone' {
        $original = (A shell getprop persist.sys.timezone | Out-String).Trim()
        New-Alarm 3 'Tz_change' | Out-Null
        $created = Get-Date
        try {
            Set-Tz 'Europe/London'
            Wait-AppLog 'system event android.intent.action.TIMEZONE_CHANGED' 60 | Out-Null
            Wait-Ring 'Tz change' 240 | Out-Null
            $after = ((Get-Date) - $created).TotalMinutes
            Note "rang $after min after creation (absolute time kept)"
            if ($after -gt 4) { throw 'alarm moved with the time-zone change' }
            Assert-Ringing; Evidence 'after-tz-change'
            Press-Stop; Wait-AppLog "stop: 'Tz change'" 20 | Out-Null
        } finally { Set-Tz $original }
    }
    'update' {
        New-Alarm 3 'After_update' | Out-Null
        Gradle :app:installDebug
        Wait-AppLog 'system event android.intent.action.MY_PACKAGE_REPLACED' 90 | Out-Null
        if ((A shell dumpsys alarm | Out-String) -notmatch [regex]::Escape("$Pkg.ALARM_FIRE")) { throw 'alarm not registered after the update' }
        A shell input keyevent KEYCODE_SLEEP | Out-Null
        Wait-Ring 'After update' 240 | Out-Null
        Assert-Ringing
        Press-Stop; Wait-AppLog "stop: 'After update'" 20 | Out-Null
    }
    'auto-snooze' {
        New-Alarm 1 'No_answer' | Out-Null
        $rang = Wait-Ring 'No answer'
        Assert-Ringing
        Wait-AppLog 'auto-snooze' 100 | Out-Null
        Wait-AppLog "snooze: 'No answer'" 20 | Out-Null
        Assert-Silent
        $at = (Listed 'No answer').At
        $delta = ($at - $rang).TotalMinutes
        if ($delta -lt 31 -or $delta -gt 32) { throw "auto-snoozed to $at; expected 31-32 minutes after $rang" }
        Note "auto-snoozed to $at"; Evidence 'auto-snoozed'
    }
    'same-minute' {
        $at = (Get-Date).AddMinutes(2)
        foreach ($i in 1..4) { New-AlarmAt $at "Same_minute_$i" }
        A shell input keyevent KEYCODE_SLEEP | Out-Null
        Wait-AppLog "ringing: 'Same minute 1' at [^,\r\n]*, 'Same minute 2' at [^,\r\n]*, 'Same minute 3' at [^,\r\n]*, 'Same minute 4'" 200 | Out-Null
        Start-Sleep 4
        foreach ($i in 1..4) { Assert-Text "Same minute $i" | Out-Null }
        Assert-Ringing; Evidence 'four-together'
        $n = A shell dumpsys notification --noredact | Out-String
        if ([regex]::Matches($n, "pkg=$([regex]::Escape($Pkg)) [^\r\n]*id=1 ").Count -ne 1) { throw 'expected exactly one ringing notification' }
        Press-Stop
        foreach ($i in 1..4) { Wait-AppLog "stop: 'Same minute $i'" 20 | Out-Null }
        Assert-Silent
    }
    'missed-after-reboot' {
        if ($Device -notlike 'emulator-*') { throw 'missed-after-reboot powers the device off: emulator only' }
        New-Alarm 2 'Missed_while_off' | Out-Null
        & $adb -s $Device emu kill | Out-Null
        Note 'emulator powered off before the alarm'
        Start-Sleep 240
        Start-Emu -Cold | Out-Null
        Wait-Ring 'Missed while off' 300 | Out-Null
        Assert-Ringing; Evidence 'rang-after-boot'
        Press-Stop; Wait-AppLog "stop: 'Missed while off'" 20 | Out-Null
    }
```

- [ ] **Step 2: Run them; every scenario must end in `PASS`**

```powershell
. .\scripts\droid.ps1
Start-Emu
Gradle :app:installDebug
$all = 'basic', 'snooze', 'tomorrow', 'row-stop', 'locked', 'doze', 'killed', 'reboot-locked', 'timezone', 'update', 'auto-snooze', 'same-minute', 'missed-after-reboot'
$summary = Join-Path (New-Evidence 'ring-scenarios') 'summary.txt'
foreach ($s in $all) {
    try { .\scripts\ring-scenarios.ps1 -Scenario $s 2>&1 | Tee-Object -Append $summary }
    catch { "ring-scenarios ${s}: FAIL $_" | Tee-Object -Append $summary }
}
Select-String -Path "$Root\logs\ring-scenarios\summary.txt" -Pattern ': PASS'
```
Expected: 13 `PASS` lines (≈45 minutes in total). Any failure is a bug in the app, not in the scenario: switch to superpowers:systematic-debugging with that scenario's `logs\ring-scenarios\<name>\` evidence, fix, re-run that scenario, then re-run all.

- [ ] **Step 3: Record results**

Add a "Risk 5 — ringing before first unlock" row to `notes/spike-results.md` (PASS/FAIL, evidence `logs/ring-scenarios/reboot-locked/`). Create `notes/test-report.md`:
```markdown
# Mustafa Alarm — test report

## Layer 1 — JVM unit tests
`Gradle :app:testDebugUnitTest` — <n> tests, all pass (<date>).

## Layer 2 — instrumented (emulator)
ProviderCalendarAccessTest 9/9, RoomLocalStoreTest 5/5, SettingsRepositoryTest 1/1 (<date>).

## Layer 3 — ringing & reliability (emulator)
| Scenario | Result | Evidence |
|---|---|---|
| basic … missed-after-reboot (one row each) | PASS | logs/ring-scenarios/<name>/ |
```
(Fill `<n>`, dates and one row per scenario from the run output.)

- [ ] **Step 4: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add scripts/ring-scenarios.ps1 notes/test-report.md notes/spike-results.md
Commit "test: reliability scenarios (lock, Doze, kill, direct boot, time zone, update, auto-snooze, same minute, missed)"
```

### Task 13: Home screen — alarm list, banners, navigation shell

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/system/ReliabilityChecks.kt`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/{AppNav,Common}.kt`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/list/{AlarmListViewModel,AlarmListScreen}.kt`
- Modify (replace whole file): `ui/MainActivity.kt`
- Modify: `AppGraph.kt` (add `checks`)
- Test: `scripts/check-list.ps1`

**Interfaces:**
- Consumes: `AppGraph` (Task 10), `AlarmStore.list/setEnabled/delete`, `Texts`, `MustafaAlarmTheme` (Task 11).
- Produces:
  - `enum class Check(label, reason) { NOTIFICATIONS, CALENDAR, EXACT_ALARMS, FULL_SCREEN, BATTERY, SYNC }`, `data class Banners(volumeLow, missingPermission, syncOff)`, `class ReliabilityChecks(context, calendar: CalendarSetupAccess, settings)` with `status(): Map<Check, Boolean>`, `banners(): Banners`, `alarmVolumeLow(): Boolean`, `fixIntent(check): Intent`; `AppGraph.checks`.
  - `object Routes { LIST="list"; NEW="edit"; EDIT="edit?eventId={eventId}"; QUICK="quick"; SETTINGS="settings"; SETUP="setup"; edit(eventId) }`, `@Composable AppNav(graph)`.
  - `@Composable Banner(text, action: String?, tag: String, onAction)`, `@Composable NextAlarmBar(text)` in `ui/Common.kt`.
  - `@Composable AlarmListScreen(graph, onNew, onEdit: (Long) -> Unit, onQuick, onSettings, onSetup: (() -> Unit)? = null)`.
  - Test tags: `alarm-<id>`, `switch-<id>`, `edit-<id>`, `delete-<id>`, `confirm-delete`, `banner-volume`, `banner-permission`, `banner-sync`, `banner-calendar`; content descriptions `Quick alarms`, `Settings`, `New alarm`.

- [ ] **Step 1: Write the failing device check** — `scripts/check-list.ps1`

```powershell
param([string]$Device = 'emulator-5560')
. "$PSScriptRoot\droid.ps1"
$env:DEVICE = $Device
if ($Device -notlike 'emulator-*') { throw 'this check deletes all alarms: emulator only' }
$out = New-Evidence 'check-list'

Grant-All
Mark-AppLog
Debug-Cmd USE_LOCAL_CALENDAR
Debug-Cmd DELETE_ALL
Debug-Cmd CREATE @('--ei', 'inMinutes', '20', '--es', 'msg', 'List_soon')
$soon = Last-CreatedId
Debug-Cmd CREATE @('--es', 'time', (Get-Date).AddMinutes(-1).ToString('HH:mm'), '--es', 'days', 'MO,TU,WE,TH,FR,SA,SU', '--es', 'msg', 'List_daily')
Debug-Cmd CREATE @('--es', 'time', '09:00', '--es', 'date', (Get-Date).AddDays(3).ToString('yyyy-MM-dd'), '--es', 'msg', 'List_later')
Open-App

foreach ($t in 'List soon', 'List daily', 'List later', 'Today', 'Tomorrow') { Assert-Text $t }
Assert-TextLike '^Next alarm in'
Shot-To $out 'list'
$daily = @((UiXml).SelectNodes('//node') | Where-Object { $_.text -eq 'List daily' }).Count
if ($daily -ne 1) { throw "the daily alarm is listed $daily times" }

Tap-Id "switch-$soon"
Start-Sleep 2
Debug-Cmd LIST
if ((AppLog | Out-String) -notmatch "item id=$soon 'List soon' at [^\r\n]* on=false") { throw 'turning off did not reach the calendar' }
Shot-To $out 'toggled-off'

Tap-Id "delete-$soon"
Assert-Text 'Delete alarm?'
Tap-Id 'confirm-delete'
Start-Sleep 2
if (Find-Node 'List soon') { throw 'deleted alarm is still listed' }
Shot-To $out 'deleted'
'check-list: PASS'
```

- [ ] **Step 2: Run it to see it fail**

```powershell
. .\scripts\droid.ps1
Start-Emu
Gradle :app:installDebug
.\scripts\check-list.ps1
```
Expected: FAIL — `expected on screen: List soon` (the activity still shows the Task 1 placeholder).

- [ ] **Step 3: Implement**

`system/ReliabilityChecks.kt`:
```kotlin
package com.atatuzun.mustafaalarm.system

import android.Manifest
import android.accounts.Account
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
import com.atatuzun.mustafaalarm.data.calendar.CalendarSetupAccess
import com.atatuzun.mustafaalarm.data.calendar.GOOGLE_ACCOUNT_TYPE
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.data.settings.SettingsRepository

enum class Check(val label: String, val reason: String) {
    NOTIFICATIONS("Notifications", "Needed to show a ringing alarm."),
    CALENDAR("Calendar access", "Your alarms live in the Alarms calendar."),
    EXACT_ALARMS("Exact alarms", "Lets alarms ring at the exact minute."),
    FULL_SCREEN("Full-screen alarms", "Shows the alarm over the lock screen."),
    BATTERY("Battery: Unrestricted", "Stops Samsung from putting the app to sleep."),
    SYNC("Google sync for Alarms", "Lets your PC see changes made on the phone."),
}

data class Banners(val volumeLow: Boolean = false, val missingPermission: Boolean = false, val syncOff: Boolean = false)

/** Reliability check (spec §9.5) and home-screen banners (spec §9.1). Blocking — call off the main thread. */
class ReliabilityChecks(
    private val context: Context,
    private val calendar: CalendarSetupAccess,
    private val settings: SettingsRepository,
) {
    fun status(): Map<Check, Boolean> {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return mapOf(
            Check.NOTIFICATIONS to notifications.areNotificationsEnabled(),
            Check.CALENDAR to calendarPermission(),
            Check.EXACT_ALARMS to context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            Check.FULL_SCREEN to notifications.canUseFullScreenIntent(),
            Check.BATTERY to context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            Check.SYNC to syncOn(settings.current()),
        )
    }

    fun banners(): Banners {
        val status = status()
        return Banners(
            volumeLow = !settings.current().increaseDeviceVolume && alarmVolumeLow(),
            missingPermission = status.filterKeys { it != Check.SYNC }.containsValue(false),
            syncOff = status[Check.SYNC] == false,
        )
    }

    /** Alarm stream below 50 % of its maximum. */
    fun alarmVolumeLow(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        return audio.getStreamVolume(AudioManager.STREAM_ALARM) * 2 < audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    }

    fun fixIntent(check: Check): Intent {
        val pkg = Uri.fromParts("package", context.packageName, null)
        val intent = when (check) {
            Check.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Check.CALENDAR -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            Check.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            Check.FULL_SCREEN -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            Check.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            Check.SYNC -> Intent(Settings.ACTION_SYNC_SETTINGS)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun calendarPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun syncOn(s: AlarmSettings): Boolean {
        val email = s.accountEmail ?: return true // phone-local test calendar: nothing to sync
        if (!calendarPermission()) return false
        val row = s.calendarId?.let { calendar.calendarRow(it) }
        return ContentResolver.getMasterSyncAutomatically() &&
            ContentResolver.getSyncAutomatically(Account(email, GOOGLE_ACCOUNT_TYPE), CalendarContract.AUTHORITY) &&
            row?.syncEvents == true
    }
}
```

In `AppGraph.kt` add the import `import com.atatuzun.mustafaalarm.system.ReliabilityChecks` and, right after the `val scheduler: Scheduler by lazy { … }` line, add:
```kotlin
    val checks: ReliabilityChecks by lazy { ReliabilityChecks(context, calendar, settings) }
```

`ui/Common.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Red warning banner with an optional action, like Simple Alarm's volume warning. */
@Composable
fun Banner(text: String, action: String?, tag: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFA00000), contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            if (action != null) {
                TextButton(onClick = onAction) { Text(action.uppercase(), color = Color(0xFFC8D7FF)) }
            }
        }
    }
}

@Composable
fun NextAlarmBar(text: String) {
    Surface(color = Color(0xFF3C3C3C), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), textAlign = TextAlign.Center)
    }
}
```

`ui/list/AlarmListViewModel.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmListState
import com.atatuzun.mustafaalarm.system.Banners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ListUi(
    val state: AlarmListState? = null,
    val banners: Banners = Banners(),
    val use24h: Boolean = true,
    val now: Long = System.currentTimeMillis(),
)

class AlarmListViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(ListUi())
    val ui: StateFlow<ListUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch { graph.changes.collect { refresh() } }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                mutable.update { it.copy(now = System.currentTimeMillis()) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val state = runCatching { graph.store.list() }
                    .getOrElse { graph.log.log("list failed: $it"); AlarmListState.Unavailable }
                Triple(state, graph.checks.banners(), graph.settings.current().use24Hour)
            }
            mutable.update { it.copy(state = loaded.first, banners = loaded.second, use24h = loaded.third, now = System.currentTimeMillis()) }
        }
    }

    fun setEnabled(eventId: Long, on: Boolean) = write("toggle") { graph.store.setEnabled(eventId, on) }

    fun delete(eventId: Long) = write("delete") { graph.store.delete(eventId) }

    /** Writes, then reschedules; the reschedule emits graph.changes, which reloads the list. */
    private fun write(reason: String, block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(block).onFailure { graph.log.log("$reason failed: $it") }
            graph.scheduler.reschedule(reason)
        }
    }
}
```

`ui/list/AlarmListScreen.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.list

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmItem
import com.atatuzun.mustafaalarm.domain.AlarmKind
import com.atatuzun.mustafaalarm.domain.AlarmListState
import com.atatuzun.mustafaalarm.domain.Texts
import com.atatuzun.mustafaalarm.domain.Times
import com.atatuzun.mustafaalarm.ui.Banner
import com.atatuzun.mustafaalarm.ui.NextAlarmBar
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    graph: AppGraph,
    onNew: () -> Unit,
    onEdit: (Long) -> Unit,
    onQuick: () -> Unit,
    onSettings: () -> Unit,
    onSetup: (() -> Unit)? = null,
) {
    val vm = viewModel { AlarmListViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var pendingDelete by remember { mutableStateOf<AlarmItem?>(null) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Alarms") },
                actions = {
                    IconButton(onClick = onQuick) { Icon(Icons.Filled.Bolt, contentDescription = "Quick alarms", tint = Color(0xFFFFC107)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    FilledIconButton(onClick = onNew) { Icon(Icons.Filled.Add, contentDescription = "New alarm") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.banners.volumeLow) item {
                Banner("Alarm volume is low. Raise it or turn on \"Increase device volume\".", "Settings", "banner-volume", onSettings)
            }
            if (ui.banners.missingPermission) item {
                Banner("Some permissions are missing — alarms may not ring.", "Fix", "banner-permission", onSettings)
            }
            if (ui.banners.syncOff) item {
                Banner("Google sync is off — your PC won't see changes", "Fix", "banner-sync") {
                    context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            when (val state = ui.state) {
                null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                AlarmListState.NoCalendar -> item {
                    Banner("No Alarms calendar yet. Finish the Google setup.", onSetup?.let { "Set up" }, "banner-calendar") { onSetup?.invoke() }
                }
                AlarmListState.CalendarMissing -> item {
                    Banner("Alarms calendar is missing", onSetup?.let { "Recreate" }, "banner-calendar") { onSetup?.invoke() }
                }
                AlarmListState.Unavailable -> item {
                    Banner("Calendar access is off — alarms can't be listed.", "Fix", "banner-permission", onSettings)
                }
                is AlarmListState.Ready -> {
                    state.nextRing?.let { next -> item { NextAlarmBar(Texts.untilNext(next, ui.now)) } }
                    if (state.sections.isEmpty()) item { Text("No alarms. Tap + to add one.", Modifier.padding(24.dp)) }
                    val today = Times.localDate(ui.now, zone)
                    state.sections.forEach { section ->
                        item(key = "day-${section.day}") {
                            Text(Texts.dayLabel(section.day, today), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(section.items, key = { it.eventId }) { item ->
                            AlarmRow(
                                item = item,
                                use24h = ui.use24h,
                                zone = zone,
                                onToggle = { vm.setEnabled(item.eventId, it) },
                                onEdit = { onEdit(item.eventId) },
                                onDelete = { pendingDelete = item },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete alarm?") },
            text = { Text("\"${item.title}\" will also be removed from Google Calendar.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(item.eventId); pendingDelete = null }, modifier = Modifier.testTag("confirm-delete")) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AlarmRow(
    item: AlarmItem,
    use24h: Boolean,
    zone: ZoneId,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val container = if (item.on) Color(0xFF4A5670) else Color(0xFF5F6368)
    val content = if (item.on) Color.White else Color(0xFF2E3135)
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth().testTag("alarm-${item.eventId}"),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (item.kind == AlarmKind.ONE_OFF) Icons.Filled.Event else Icons.Filled.Repeat,
                contentDescription = if (item.kind == AlarmKind.ONE_OFF) "One-off" else "Repeating",
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(Texts.clock(item.shownAt, zone, use24h), style = MaterialTheme.typography.displaySmall)
                if (item.kind == AlarmKind.WEEKLY) Text(Texts.weekdays(item.weekdays), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = item.on, onCheckedChange = onToggle, modifier = Modifier.testTag("switch-${item.eventId}"))
            Column {
                IconButton(onClick = onEdit, modifier = Modifier.testTag("edit-${item.eventId}")) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("delete-${item.eventId}")) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        }
    }
}
```

`ui/AppNav.kt` (later tasks add one `composable(…)` block each, right after the `Routes.LIST` block):
```kotlin
package com.atatuzun.mustafaalarm.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.ui.list.AlarmListScreen

object Routes {
    const val LIST = "list"
    const val NEW = "edit"
    const val EDIT = "edit?eventId={eventId}"
    const val QUICK = "quick"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    fun edit(eventId: Long) = "edit?eventId=$eventId"
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppNav(graph: AppGraph) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.LIST, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        composable(Routes.LIST) {
            AlarmListScreen(
                graph = graph,
                onNew = { nav.navigate(Routes.NEW) },
                onEdit = { nav.navigate(Routes.edit(it)) },
                onQuick = { nav.navigate(Routes.QUICK) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
    }
}
```

Replace `ui/MainActivity.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.ui.theme.MustafaAlarmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = applicationContext.graph
        setContent {
            val settings = graph.settings.flow.collectAsStateWithLifecycle(initialValue = null).value
            if (settings != null) {
                MustafaAlarmTheme(dark = settings.darkTheme) {
                    Surface(Modifier.fillMaxSize()) { AppNav(graph) }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run the check to see it pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest :app:installDebug
.\scripts\check-list.ps1
```
Expected: `check-list: PASS`. Open `logs\check-list\list.png`: rows grouped under Today / Tomorrow / a weekday, next-alarm bar on top, grey row in `toggled-off.png`.

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add scripts/check-list.ps1 mustafa-alarm/app/src/main
Commit "feat(ui): alarm list home screen with banners and reliability checks"
```

### Task 14: Add / edit screen

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/edit/{EditAlarmViewModel,EditAlarmScreen,TimePickers}.kt`
- Modify: `ui/AppNav.kt` (edit route)
- Test: `scripts/check-edit.ps1`

**Interfaces:**
- Consumes: `AlarmStore.create/update/details/delete`, `SaveResult`, `AlarmInput`, `AlarmKind`, `TimeEntry`, `Texts`, `Routes`.
- Produces: `@Composable EditAlarmScreen(graph, eventId: Long?, onDone)`; `@Composable TimePadDialog(initial: LocalTime, onDismiss, onConfirm: (LocalTime) -> Unit)`; `@Composable AlarmDatePicker(initial: LocalDate?, onDismiss, onPick: (LocalDate) -> Unit)`. Test tags `time`, `pad-0`…`pad-9`, `pad-back`, `pad-ok`, `pad-display`, `day-SUNDAY`…`day-SATURDAY`, `pick-date`, `clear-date`, `message`, `sound`, `save`, `save-top`, `delete`, `confirm-delete`.

- [ ] **Step 1: Write the failing device check** — `scripts/check-edit.ps1`

```powershell
param([string]$Device = 'emulator-5560')
. "$PSScriptRoot\droid.ps1"
$env:DEVICE = $Device
if ($Device -notlike 'emulator-*') { throw 'this check deletes all alarms: emulator only' }
$out = New-Evidence 'check-edit'

Grant-All
Mark-AppLog
Debug-Cmd USE_LOCAL_CALENDAR
Debug-Cmd DELETE_ALL
Open-App

# New weekly alarm: 07:30, Mon + Wed, "Edit check"
Tap-Text 'New alarm'
Assert-Text 'New Alarm'
Tap-Id 'time'
foreach ($d in '0', '7', '3', '0') { Tap-Id "pad-$d" }
Assert-Text '07:30'
Tap-Id 'pad-ok'
Tap-Id 'day-MONDAY'; Tap-Id 'day-WEDNESDAY'
Tap-Id 'message'
A shell input text 'Edit%scheck' | Out-Null
Shot-To $out 'new-filled'
Tap-Id 'save-top'
Start-Sleep 2
foreach ($t in 'Edit check', 'Mon Wed', '07:30') { Assert-Text $t }
$id = Last-CreatedId
Shot-To $out 'saved'
Debug-Cmd LIST
if ((AppLog | Out-String) -notmatch "item id=$id 'Edit check' at [^\r\n]* 07:30:00 on=true kind=WEEKLY") { throw 'weekly alarm not stored as expected' }

# Edit into a one-off today at a time that has passed → error
Tap-Id "edit-$id"
Assert-Text 'Edit Alarm'
Tap-Id 'day-MONDAY'; Tap-Id 'day-WEDNESDAY'
Tap-Id 'pick-date'
Tap-Text 'OK'
Tap-Id 'time'
foreach ($d in '0', '0', '0', '1') { Tap-Id "pad-$d" }
Tap-Id 'pad-ok'
Tap-Id 'save-top'
Assert-Text 'That time has already passed'
Shot-To $out 'past-time-error'

# Clear the date → next 00:01
Tap-Id 'clear-date'
Tap-Id 'save-top'
Start-Sleep 2
Debug-Cmd LIST
if ((AppLog | Out-String) -notmatch "item id=$id 'Edit check' at [^\r\n]* 00:01:00 on=true kind=ONE_OFF") { throw 'edit to a one-off was not stored' }

# The sound row opens the system ringtone picker
Tap-Id "edit-$id"
Tap-Id 'sound'
Start-Sleep 2
$focus = A shell dumpsys window | Select-String 'mCurrentFocus' | Out-String
Shot-To $out 'sound-picker'
if ($focus -match [regex]::Escape($Pkg)) { throw 'ringtone picker did not open' }
A shell input keyevent KEYCODE_BACK | Out-Null
Start-Sleep 1

# Delete from the edit screen
Tap-Id 'delete'
Tap-Id 'confirm-delete'
Start-Sleep 2
if (Find-Node 'Edit check') { throw 'alarm was not deleted' }
'check-edit: PASS'
```

- [ ] **Step 2: Run it to see it fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:installDebug
.\scripts\check-edit.ps1
```
Expected: FAIL — tapping `New alarm` navigates to the unregistered `edit` route (app crash) → `expected on screen: New Alarm`.

- [ ] **Step 3: Implement**

`ui/edit/TimePickers.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atatuzun.mustafaalarm.domain.Texts
import com.atatuzun.mustafaalarm.domain.TimeEntry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Number-pad time entry (spec §9.2): type 4 digits, e.g. 0730. */
@Composable
fun TimePadDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    var digits by remember { mutableStateOf("") }
    val parsed = TimeEntry.parse(digits)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onConfirm) }, modifier = Modifier.testTag("pad-ok")) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (digits.isEmpty()) Texts.clock(initial, true) else TimeEntry.display(digits),
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.testTag("pad-display"),
                )
                Text("Type the time as 4 digits, e.g. 0730", style = MaterialTheme.typography.bodySmall)
                listOf("123", "456", "789", " 0<").forEach { row ->
                    Row {
                        row.forEach { key ->
                            when (key) {
                                ' ' -> Spacer(Modifier.size(72.dp))
                                '<' -> IconButton(onClick = { digits = TimeEntry.pop(digits) }, modifier = Modifier.size(72.dp).testTag("pad-back")) {
                                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace")
                                }
                                else -> TextButton(onClick = { digits = TimeEntry.push(digits, key) }, modifier = Modifier.size(72.dp).testTag("pad-$key")) {
                                    Text(key.toString(), style = MaterialTheme.typography.headlineMedium)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Date picker limited to today and later (spec §9.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmDatePicker(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val todayUtc = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}
```

`ui/edit/EditAlarmViewModel.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.edit

import android.media.RingtoneManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmInput
import com.atatuzun.mustafaalarm.domain.AlarmKind
import com.atatuzun.mustafaalarm.domain.DEFAULT_TITLE
import com.atatuzun.mustafaalarm.domain.SaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class EditUi(
    val loading: Boolean = true,
    val editing: Boolean = false,
    val time: LocalTime = LocalTime.of(8, 0),
    val days: Set<DayOfWeek> = emptySet(),
    val date: LocalDate? = null,
    val message: String = "",
    val soundUri: String? = null,
    val soundName: String = "Default",
    val otherRepeat: Boolean = false,
    val use24h: Boolean = true,
    val error: String? = null,
    val closed: Boolean = false,
)

class EditAlarmViewModel(private val graph: AppGraph, private val eventId: Long?) : ViewModel() {
    private val mutable = MutableStateFlow(EditUi())
    val ui: StateFlow<EditUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            mutable.value = withContext(Dispatchers.IO) {
                val use24h = graph.settings.current().use24Hour
                val details = eventId?.let { runCatching { graph.store.details(it) }.getOrNull() }
                if (details == null) {
                    EditUi(loading = false, time = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0), use24h = use24h)
                } else {
                    EditUi(
                        loading = false,
                        editing = true,
                        time = details.time,
                        days = details.days,
                        date = details.date,
                        message = details.message.takeUnless { it == DEFAULT_TITLE }.orEmpty(),
                        soundUri = details.soundUri,
                        soundName = soundName(details.soundUri),
                        otherRepeat = details.kind == AlarmKind.OTHER_REPEAT,
                        use24h = use24h,
                    )
                }
            }
        }
    }

    fun setTime(time: LocalTime) = mutable.update { it.copy(time = time) }

    fun toggleDay(day: DayOfWeek) = mutable.update { s ->
        val days = if (day in s.days) s.days - day else s.days + day
        s.copy(days = days, date = if (days.isEmpty()) s.date else null)
    }

    fun setDate(date: LocalDate?) = mutable.update { it.copy(date = date, days = if (date != null) emptySet() else it.days) }

    fun setMessage(message: String) = mutable.update { it.copy(message = message) }

    fun clearError() = mutable.update { it.copy(error = null) }

    fun setSound(uri: String?) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { soundName(uri) }
            mutable.update { it.copy(soundUri = uri, soundName = name) }
        }
    }

    fun save() {
        val s = mutable.value
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = AlarmInput(s.time, s.date, s.days, s.message, s.soundUri)
                    val saved = if (eventId == null) graph.store.create(input) else graph.store.update(eventId, input)
                    if (saved is SaveResult.Saved) graph.scheduler.reschedule(if (eventId == null) "create" else "edit")
                    saved
                }
            }
            result.fold(
                onSuccess = { saved ->
                    when (saved) {
                        is SaveResult.Saved -> mutable.update { it.copy(closed = true) }
                        SaveResult.TimeInPast -> showError("That time has already passed")
                        SaveResult.NoCalendar -> showError("Finish the Google setup first")
                        SaveResult.Missing -> showError("This alarm no longer exists")
                    }
                },
                onFailure = { e ->
                    graph.log.log("save failed: $e")
                    showError("Could not save: ${e.message}")
                },
            )
        }
    }

    fun delete() {
        val id = eventId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { graph.store.delete(id) }.onFailure { graph.log.log("delete failed: $it") }
                graph.scheduler.reschedule("delete")
            }
            mutable.update { it.copy(closed = true) }
        }
    }

    private fun showError(text: String) = mutable.update { it.copy(error = text) }

    private fun soundName(uri: String?): String =
        if (uri == null) "Default"
        else runCatching { RingtoneManager.getRingtone(graph.context, Uri.parse(uri))?.getTitle(graph.context) }.getOrNull() ?: "Custom sound"
}
```

`ui/edit/EditAlarmScreen.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.edit

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.Texts
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val WEEK = listOf(
    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
)
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAlarmScreen(graph: AppGraph, eventId: Long?, onDone: () -> Unit) {
    val vm = viewModel(key = "edit-${eventId ?: "new"}") { EditAlarmViewModel(graph, eventId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showPad by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.closed) { if (ui.closed) onDone() }
    LaunchedEffect(ui.error) { ui.error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            vm.setSound(uri?.takeUnless { it == Settings.System.DEFAULT_ALARM_ALERT_URI }?.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui.editing) "Edit Alarm" else "New Alarm") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = vm::save, modifier = Modifier.testTag("save-top")) { Icon(Icons.Filled.Check, contentDescription = "Save") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (ui.loading) {
            LinearProgressIndicator(Modifier.padding(padding).fillMaxWidth())
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(onClick = { showPad = true }, modifier = Modifier.fillMaxWidth().testTag("time")) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(Texts.clock(ui.time, ui.use24h), style = MaterialTheme.typography.displayLarge)
                }
            }
            if (ui.otherRepeat) {
                Text("Repeats as set in Google Calendar", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WEEK.forEach { day -> DayBox(day, day in ui.days) { vm.toggleDay(day) } }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(ui.date?.let { "Date: ${it.format(DATE)}" } ?: "Date", Modifier.weight(1f))
                    if (ui.date != null) {
                        IconButton(onClick = { vm.setDate(null) }, modifier = Modifier.testTag("clear-date")) { Icon(Icons.Filled.Close, contentDescription = "Clear date") }
                    }
                    IconButton(onClick = { showDate = true }, modifier = Modifier.testTag("pick-date")) { Icon(Icons.Filled.AddCircleOutline, contentDescription = "Pick date") }
                }
            }
            OutlinedTextField(
                value = ui.message,
                onValueChange = vm::setMessage,
                label = { Text("Alarm message") },
                modifier = Modifier.fillMaxWidth().testTag("message"),
            )
            OutlinedCard(onClick = { picker.launch(ringtoneIntent(ui.soundUri)) }, modifier = Modifier.fillMaxWidth().testTag("sound")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(ui.soundName)
                }
            }
            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth().testTag("save")) { Text("SAVE") }
            if (ui.editing) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().testTag("delete")) { Text("DELETE") }
            }
        }
    }

    if (showPad) TimePadDialog(ui.time, onDismiss = { showPad = false }, onConfirm = { vm.setTime(it); showPad = false })
    if (showDate) AlarmDatePicker(ui.date, onDismiss = { showDate = false }, onPick = { vm.setDate(it); showDate = false })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete alarm?") },
            text = { Text("It will also be removed from Google Calendar.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }, modifier = Modifier.testTag("confirm-delete")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DayBox(day: DayOfWeek, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .testTag("day-$day"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase(),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun ringtoneIntent(current: String?): Intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
```

In `ui/AppNav.kt` add the imports `androidx.navigation.NavType`, `androidx.navigation.navArgument`, `com.atatuzun.mustafaalarm.ui.edit.EditAlarmScreen`, and right after the `composable(Routes.LIST) { … }` block add:
```kotlin
        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            EditAlarmScreen(graph, entry.arguments?.getLong("eventId")?.takeIf { it > 0 }, onDone = { nav.popBackStack() })
        }
```

- [ ] **Step 4: Run the checks to see them pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest :app:installDebug
.\scripts\check-edit.ps1
.\scripts\check-list.ps1
```
Expected: `check-edit: PASS`, `check-list: PASS`. Look at `logs\check-edit\new-filled.png` (big time, day boxes, message, sound row, Save) and `past-time-error.png` (snackbar).

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add scripts/check-edit.ps1 mustafa-alarm/app/src/main
Commit "feat(ui): add/edit alarm screen with number pad, weekdays, date and sound"
```

### Task 15: Quick alarms screen

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/quick/{QuickAlarmsViewModel,QuickAlarmsScreen}.kt`
- Modify: `ui/AppNav.kt` (quick route)
- Test: `scripts/check-quick.ps1`

**Interfaces:**
- Consumes: `QuickPresets`, `FrequentAlarms`, `AlarmStore.create(input, recordHistory)/delete`, `LocalStore.creationHistory`, `Texts`.
- Produces: `@Composable QuickAlarmsScreen(graph, onBack, onCreateOwn, onAddMessage: (Long) -> Unit)`. Test tags `quick-in-<minutes>`, `quick-morning-<HH:mm>`, `quick-frequent-<HH:mm>`, `quick-own`, `quick-result`, `quick-add-message`, `quick-undo`.

- [ ] **Step 1: Write the failing device check** — `scripts/check-quick.ps1`

```powershell
param([string]$Device = 'emulator-5560')
. "$PSScriptRoot\droid.ps1"
$env:DEVICE = $Device
if ($Device -notlike 'emulator-*') { throw 'this check deletes all alarms: emulator only' }
$out = New-Evidence 'check-quick'

Grant-All
Mark-AppLog
Debug-Cmd USE_LOCAL_CALENDAR
Debug-Cmd DELETE_ALL
Open-App
Tap-Text 'Quick alarms'
foreach ($t in '5m', '24h', '06:00', 'Create my own') { Assert-Text $t }
Shot-To $out 'quick'

# 15m → "Alarm set for HH:MM" → Undo deletes it
Tap-Id 'quick-in-15'
Assert-TextLike '^Alarm set for \d\d:\d\d$'
$undoId = Last-CreatedId
Shot-To $out 'result-bar'
Tap-Id 'quick-undo'
Start-Sleep 2
Debug-Cmd LIST
if ((AppLog | Out-String) -match "item id=$undoId ") { throw 'Undo did not delete the quick alarm' }

# 30m → "Add message" opens the edit screen of that alarm (titled "Alarm")
Tap-Id 'quick-in-30'
$msgId = Last-CreatedId
Tap-Id 'quick-add-message'
Assert-Text 'Edit Alarm'
Shot-To $out 'add-message'
A shell input keyevent KEYCODE_BACK | Out-Null
Start-Sleep 1
Debug-Cmd LIST
if ((AppLog | Out-String) -notmatch "item id=$msgId 'Alarm' at ") { throw 'quick alarm is not titled Alarm' }

# A morning preset counts toward "Your frequent alarms"
Tap-Id 'quick-morning-06:00'
Start-Sleep 1
A shell input keyevent KEYCODE_BACK | Out-Null
Start-Sleep 1
Tap-Text 'Quick alarms'
Assert-Text 'Your frequent alarms'
if (-not (Find-Id 'quick-frequent-06:00')) { throw '06:00 is not among the frequent alarms' }
Shot-To $out 'frequent'
'check-quick: PASS'
```

- [ ] **Step 2: Run it to see it fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:installDebug
.\scripts\check-quick.ps1
```
Expected: FAIL — the `quick` route is not registered yet (crash) → `expected on screen: 5m`.

- [ ] **Step 3: Implement**

`ui/quick/QuickAlarmsViewModel.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.quick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmInput
import com.atatuzun.mustafaalarm.domain.FrequentAlarms
import com.atatuzun.mustafaalarm.domain.QuickPresets
import com.atatuzun.mustafaalarm.domain.SaveResult
import com.atatuzun.mustafaalarm.domain.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZoneId

data class QuickResult(val eventId: Long, val at: Long)

data class QuickUi(
    val frequent: List<LocalTime> = emptyList(),
    val result: QuickResult? = null,
    val use24h: Boolean = true,
    val error: String? = null,
)

class QuickAlarmsViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(QuickUi())
    val ui: StateFlow<QuickUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                FrequentAlarms.top(graph.local.creationHistory(now - FrequentAlarms.WINDOW), now) to graph.settings.current().use24Hour
            }
            mutable.update { it.copy(frequent = loaded.first, use24h = loaded.second) }
        }
    }

    /** Relative presets (5m … 24h) do not count toward frequent alarms. */
    fun createIn(minutes: Int) = create(QuickPresets.relativeTarget(minutes, System.currentTimeMillis()), recordHistory = false)

    fun createAt(time: LocalTime) = create(Times.nextAt(time, System.currentTimeMillis(), ZoneId.systemDefault()), recordHistory = true)

    private fun create(at: Long, recordHistory: Boolean) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val input = AlarmInput(Times.localTime(at, zone), Times.localDate(at, zone), emptySet(), "", null)
                    graph.store.create(input, recordHistory).also { if (it is SaveResult.Saved) graph.scheduler.reschedule("quick") }
                }.getOrElse { graph.log.log("quick alarm failed: $it"); null }
            }
            if (saved is SaveResult.Saved) mutable.update { it.copy(result = QuickResult(saved.eventId, saved.start), error = null) }
            else mutable.update { it.copy(error = "Could not set the alarm") }
        }
    }

    fun undo() {
        val result = mutable.value.result ?: return
        mutable.update { it.copy(result = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.store.delete(result.eventId) }.onFailure { graph.log.log("undo failed: $it") }
            graph.scheduler.reschedule("quick-undo")
        }
    }

    fun dismissResult() = mutable.update { it.copy(result = null) }
}
```

`ui/quick/QuickAlarmsScreen.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.quick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.QuickPresets
import com.atatuzun.mustafaalarm.domain.Texts
import kotlinx.coroutines.delay
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAlarmsScreen(graph: AppGraph, onBack: () -> Unit, onCreateOwn: () -> Unit, onAddMessage: (Long) -> Unit) {
    val vm = viewModel { QuickAlarmsViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.result) {
        if (ui.result != null) {
            delay(8_000)
            vm.dismissResult()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick alarms") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = {
            ui.result?.let { result ->
                ResultBar(
                    time = Texts.clock(result.at, ZoneId.systemDefault(), ui.use24h),
                    onAddMessage = { vm.dismissResult(); onAddMessage(result.eventId) },
                    onUndo = vm::undo,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("In", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickPresets.relativeMinutes.forEach { m -> Preset(Texts.relative(m), "quick-in-$m") { vm.createIn(m) } }
            }
            Text("Morning", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickPresets.morning.forEach { t -> Preset(Texts.clock(t, ui.use24h), "quick-morning-$t") { vm.createAt(t) } }
            }
            if (ui.frequent.isNotEmpty()) {
                Text("Your frequent alarms", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.frequent.forEach { t -> Preset(Texts.clock(t, ui.use24h), "quick-frequent-$t") { vm.createAt(t) } }
                }
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = onCreateOwn, modifier = Modifier.fillMaxWidth().testTag("quick-own")) { Text("Create my own") }
        }
    }
}

@Composable
private fun Preset(label: String, tag: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun ResultBar(time: String, onAddMessage: () -> Unit, onUndo: () -> Unit) {
    Surface(color = Color(0xFF323232), modifier = Modifier.fillMaxWidth().navigationBarsPadding().testTag("quick-result")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Alarm set for $time", Modifier.weight(1f), color = Color.White)
            TextButton(onClick = onAddMessage, modifier = Modifier.testTag("quick-add-message")) { Text("Add message") }
            TextButton(onClick = onUndo, modifier = Modifier.testTag("quick-undo")) { Text("Undo") }
        }
    }
}
```

In `ui/AppNav.kt` add the import `com.atatuzun.mustafaalarm.ui.quick.QuickAlarmsScreen` and, after the `Routes.LIST` block, add:
```kotlin
        composable(Routes.QUICK) {
            QuickAlarmsScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onCreateOwn = { nav.navigate(Routes.NEW) },
                onAddMessage = { nav.navigate(Routes.edit(it)) },
            )
        }
```

- [ ] **Step 4: Run the check to see it pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:installDebug
.\scripts\check-quick.ps1
```
Expected: `check-quick: PASS`; `logs\check-quick\quick.png` shows the three preset groups, `result-bar.png` the "Alarm set for … · Add message · Undo" bar.

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add scripts/check-quick.ps1 mustafa-alarm/app/src/main
Commit "feat(ui): quick alarms with undo, add message and frequent times"
```

### Task 16: Settings screen and reliability check

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/settings/{SettingsViewModel,SettingsScreen,SettingsRows}.kt`
- Modify: `ui/AppNav.kt` (settings route)
- Test: `scripts/check-settings.ps1`

**Interfaces:**
- Consumes: `SettingsRepository`, `ReliabilityChecks` (`status`, `fixIntent`), `CalendarSetupAccess.dirtyCount`, `EventLog.time`, `Scheduler.reschedule`.
- Produces: `@Composable SettingsScreen(graph, onBack, onSwitchAccount: (() -> Unit)? = null)`. Row tags: `account-email`, `switch-account`, `calendar-state`, `last-change`, `pending-uploads`, `default-sound`, `sound-mode`, `volume`, `increase-volume`, `fade-in`, `snooze`, `auto-snooze`, `show-snooze`, `stop-method`, `use-24h`, `dark-theme`, `next-notification`, `check-<CHECK>`, `fix-<CHECK>`.

- [ ] **Step 1: Write the failing device check** — `scripts/check-settings.ps1`

```powershell
param([string]$Device = 'emulator-5560')
. "$PSScriptRoot\droid.ps1"
$env:DEVICE = $Device
if ($Device -notlike 'emulator-*') { throw 'this check deletes all alarms: emulator only' }
$out = New-Evidence 'check-settings'
function Scroll-To([string]$id) {
    for ($i = 0; $i -lt 10; $i++) {
        if (Find-Id $id) { return }
        A shell input swipe 540 1500 540 900 300 | Out-Null
        Start-Sleep -Milliseconds 600
    }
    throw "could not scroll to $id"
}

Grant-All
Mark-AppLog
Debug-Cmd USE_LOCAL_CALENDAR
Debug-Cmd DELETE_ALL
Debug-Cmd SETTINGS @('--ei', 'snoozeMinutes', '30', '--ez', 'increaseDeviceVolume', 'true')
Open-App
Tap-Text 'Settings'
foreach ($t in 'Google account', 'Default sound', 'Increase device volume') { Assert-Text $t }
Shot-To $out 'settings-top'
Scroll-To 'stop-method'
foreach ($t in 'Snooze duration', '30 min', 'Press Stop three times') { Assert-Text $t }

# A change survives a process restart
Tap-Id 'snooze'; Tap-Text '10 min'; Assert-Text '10 min'
A shell input keyevent KEYCODE_HOME | Out-Null
Start-Sleep 1
A shell am kill $Pkg | Out-Null
Open-App
Tap-Text 'Settings'
Scroll-To 'snooze'
Assert-Text '10 min'
Tap-Id 'snooze'; Tap-Text '30 min'

# The next-alarm notification follows its switch
Debug-Cmd CREATE @('--ei', 'inMinutes', '30', '--es', 'msg', 'Notif_check')
if ((A shell dumpsys notification --noredact | Out-String) -notmatch 'Next: \d\d:\d\d \S{1,3} Notif check') { throw 'next-alarm notification missing' }
Scroll-To 'next-notification'
Tap-Id 'next-notification'
Start-Sleep 3
if ((A shell dumpsys notification --noredact | Out-String) -match 'Next: \d\d:\d\d \S{1,3} Notif check') { throw 'next-alarm notification still shown after turning it off' }
Tap-Id 'next-notification'

# The reliability check reports a revoked permission and Fix opens system settings
A shell appops set $Pkg USE_FULL_SCREEN_INTENT deny | Out-Null
Open-App
Tap-Text 'Settings'
Scroll-To 'check-FULL_SCREEN'
if (-not (Find-Id 'fix-FULL_SCREEN')) { throw 'missing full-screen permission is not reported' }
Shot-To $out 'reliability-missing'
Tap-Id 'fix-FULL_SCREEN'
Start-Sleep 2
if ((A shell dumpsys window | Select-String 'mCurrentFocus' | Out-String) -match [regex]::Escape("$Pkg/")) { throw 'Fix did not open system settings' }
A shell input keyevent KEYCODE_BACK | Out-Null
A shell appops set $Pkg USE_FULL_SCREEN_INTENT allow | Out-Null

# Low alarm volume + "Increase device volume" off → banner on the home screen
Debug-Cmd SETTINGS @('--ez', 'increaseDeviceVolume', 'false')
A shell cmd media_session volume --stream 4 --set 1 | Out-Null
Open-App
if (-not (Find-Id 'banner-volume')) { throw 'low-volume banner missing' }
Shot-To $out 'volume-banner'
A shell cmd media_session volume --stream 4 --set 6 | Out-Null
Debug-Cmd SETTINGS @('--ez', 'increaseDeviceVolume', 'true')
'check-settings: PASS'
```

- [ ] **Step 2: Run it to see it fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:installDebug
.\scripts\check-settings.ps1
```
Expected: FAIL — the `settings` route is not registered (crash) → `expected on screen: Snooze duration`.

- [ ] **Step 3: Implement**

`ui/settings/SettingsRows.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atatuzun.mustafaalarm.system.Check

@Composable
fun Section(title: String) {
    Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
fun InfoRow(label: String, value: String, tag: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ActionRow(label: String, value: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SwitchRow(label: String, description: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChoiceRow(label: String, current: T, options: List<Pair<T, String>>, tag: String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ActionRow(label, options.firstOrNull { it.first == current }?.second ?: current.toString(), tag) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column {
                    options.forEach { (value, text) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(value); open = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == current, onClick = { onPick(value); open = false })
                            Text(text)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
fun SliderRow(label: String, percent: Int, tag: String, onDone: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag)) {
        Text("$label: ${value.toInt()} %", style = MaterialTheme.typography.titleMedium)
        Slider(value = value, onValueChange = { value = it }, valueRange = 10f..100f, onValueChangeFinished = { onDone(value.toInt()) })
    }
}

@Composable
fun CheckRow(check: Check, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("check-${check.name}"), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = if (ok) "OK" else "Missing",
            tint = if (ok) Color(0xFF4CAF50) else Color(0xFFE53935),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(check.label, style = MaterialTheme.typography.titleMedium)
            Text(check.reason, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok) TextButton(onClick = onFix, modifier = Modifier.testTag("fix-${check.name}")) { Text("Fix") }
    }
}
```

`ui/settings/SettingsViewModel.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.settings

import android.media.RingtoneManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.system.Check
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUi(
    val settings: AlarmSettings = AlarmSettings(),
    val checks: Map<Check, Boolean> = emptyMap(),
    val calendarState: String = "…",
    val pendingUploads: Int? = null,
    val defaultSoundName: String = "Default",
)

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch { graph.settings.flow.collect { s -> mutable.update { it.copy(settings = s) } } }
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val s = graph.settings.current()
                val calId = s.calendarId
                val calendarState = runCatching {
                    when {
                        calId == null -> "Not set up"
                        !graph.calendarAvailable() -> "No calendar access"
                        graph.calendar.calendarExists(calId) -> "OK"
                        else -> "Missing"
                    }
                }.getOrDefault("Unknown")
                SettingsUi(
                    settings = s,
                    checks = graph.checks.status(),
                    calendarState = calendarState,
                    pendingUploads = calId?.takeIf { graph.calendarAvailable() }?.let { graph.calendar.dirtyCount(it) },
                    defaultSoundName = soundName(s.defaultSoundUri),
                )
            }
            mutable.value = loaded
        }
    }

    /** Saves, then reschedules (the next-alarm notification and the ring cache depend on settings). */
    fun update(transform: (AlarmSettings) -> AlarmSettings) {
        viewModelScope.launch {
            graph.settings.update(transform)
            withContext(Dispatchers.IO) { graph.scheduler.reschedule("settings") }
        }
    }

    fun setDefaultSound(uri: String?) {
        update { it.copy(defaultSoundUri = uri) }
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { soundName(uri) }
            mutable.update { it.copy(defaultSoundName = name) }
        }
    }

    private fun soundName(uri: String?): String =
        if (uri == null) "Default"
        else runCatching { RingtoneManager.getRingtone(graph.context, Uri.parse(uri))?.getTitle(graph.context) }.getOrNull() ?: "Custom sound"
}
```

`ui/settings/SettingsScreen.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.data.settings.SoundMode
import com.atatuzun.mustafaalarm.data.settings.StopMethod
import com.atatuzun.mustafaalarm.log.EventLog
import com.atatuzun.mustafaalarm.system.Check

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onSwitchAccount: (() -> Unit)? = null) {
    val vm = viewModel { SettingsViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.startActivity(graph.checks.fixIntent(Check.NOTIFICATIONS))
        vm.refresh()
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (!result.values.all { it }) context.startActivity(graph.checks.fixIntent(Check.CALENDAR))
        vm.refresh()
    }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            vm.setDefaultSound(uri?.takeUnless { it == Settings.System.DEFAULT_ALARM_ALERT_URI }?.toString())
        }
    }
    val s = ui.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("Account")
            InfoRow("Google account", s.accountEmail ?: "Not signed in", "account-email")
            if (onSwitchAccount != null) ActionRow("Switch account", "Sign in with another Google account", "switch-account", onSwitchAccount)
            InfoRow("Alarms calendar", ui.calendarState, "calendar-state")
            InfoRow("Last calendar update received", s.lastCalendarChange?.let { EventLog.time(it) } ?: "—", "last-change")
            InfoRow("Changes waiting to upload", ui.pendingUploads?.toString() ?: "unknown", "pending-uploads")

            Section("Sound")
            ActionRow("Default sound", ui.defaultSoundName, "default-sound") { soundPicker.launch(ringtoneIntent(s.defaultSoundUri)) }
            ChoiceRow(
                "Sound / vibration", s.soundMode,
                listOf(SoundMode.SOUND_AND_VIBRATION to "Sound and vibration", SoundMode.SOUND_ONLY to "Sound only", SoundMode.VIBRATION_ONLY to "Vibration only"),
                "sound-mode",
            ) { mode -> vm.update { it.copy(soundMode = mode) } }
            SliderRow("Volume", s.volumePercent, "volume") { v -> vm.update { it.copy(volumePercent = v) } }
            SwitchRow("Increase device volume", "Raise the alarm volume to maximum while ringing", s.increaseDeviceVolume, "increase-volume") { on ->
                vm.update { it.copy(increaseDeviceVolume = on) }
            }
            SwitchRow("Fade in", "Start quietly and get louder over 30 seconds", s.fadeIn, "fade-in") { on -> vm.update { it.copy(fadeIn = on) } }

            Section("Snooze & stop")
            ChoiceRow("Snooze duration", s.snoozeMinutes, listOf(5, 10, 15, 20, 30, 45, 60).map { it to "$it min" }, "snooze") { m ->
                vm.update { it.copy(snoozeMinutes = m) }
            }
            ChoiceRow("Auto-snooze after", s.autoSnoozeMinutes, listOf(1, 2, 3, 5, 10).map { it to if (it == 1) "1 minute" else "$it minutes" }, "auto-snooze") { m ->
                vm.update { it.copy(autoSnoozeMinutes = m) }
            }
            SwitchRow("Show snooze button", "Shows Snooze while an alarm rings", s.showSnoozeButton, "show-snooze") { on -> vm.update { it.copy(showSnoozeButton = on) } }
            ChoiceRow(
                "Stop method", s.stopMethod,
                listOf(StopMethod.THREE_PRESSES to "Press Stop three times", StopMethod.ONE_PRESS to "Press Stop once"),
                "stop-method",
            ) { m -> vm.update { it.copy(stopMethod = m) } }

            Section("Display")
            SwitchRow("24-hour clock", null, s.use24Hour, "use-24h") { on -> vm.update { it.copy(use24Hour = on) } }
            SwitchRow("Dark theme", null, s.darkTheme, "dark-theme") { on -> vm.update { it.copy(darkTheme = on) } }
            SwitchRow("Next-alarm notification", "A silent notification showing the next alarm", s.nextAlarmNotification, "next-notification") { on ->
                vm.update { it.copy(nextAlarmNotification = on) }
            }

            Section("Reliability check")
            Check.entries.forEach { check ->
                CheckRow(check, ui.checks[check] == true) {
                    when (check) {
                        Check.NOTIFICATIONS -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        Check.CALENDAR -> calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        else -> context.startActivity(graph.checks.fixIntent(check))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun ringtoneIntent(current: String?): Intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
```

In `ui/AppNav.kt` add the import `com.atatuzun.mustafaalarm.ui.settings.SettingsScreen` and, after the `Routes.LIST` block, add:
```kotlin
        composable(Routes.SETTINGS) { SettingsScreen(graph, onBack = { nav.popBackStack() }) }
```

- [ ] **Step 4: Run all UI checks to see them pass**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest :app:installDebug
foreach ($c in 'check-settings', 'check-list', 'check-edit', 'check-quick') { & ".\scripts\$c.ps1" }
```
Expected: four `…: PASS` lines. `logs\check-settings\reliability-missing.png` shows a red ✗ with Fix next to "Full-screen alarms".

- [ ] **Step 5: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add scripts/check-settings.ps1 mustafa-alarm/app/src/main
Commit "feat(ui): settings with sound, snooze/stop, display options and reliability check"
```

### Task 17: Google Cloud project and OAuth clients (Chrome, Mustafa watching)

No app code. Claude drives Mustafa's Chrome with the `claude-in-chrome` skill (spec §10.1); Mustafa watches and handles anything Google insists a human does (re-login, 2FA).

**Files:**
- Create: `notes/google-cloud.md`
- Modify: `mustafa-alarm/gradle.properties` (`mustafaAlarm.serverClientId`)

**Interfaces:**
- Consumes: SHA-1 from `notes/signing.md` (Task 1).
- Produces: Web OAuth client ID in `mustafaAlarm.serverClientId` → `BuildConfig.GOOGLE_SERVER_CLIENT_ID` (used by Task 18). Android OAuth client bound to `com.atatuzun.mustafaalarm` + the project SHA-1.

- [ ] **Step 1: Tell Mustafa what is about to happen**

"I'll now create a Google Cloud project called 'Mustafa Alarm' in your Chrome (Calendar API, consent screen in Testing mode with you as the only test user, an Android client and a Web client). Please keep an eye on the browser; if Google asks you to sign in or confirm, do that and tell me."

- [ ] **Step 2: Create the project and enable the Calendar API**

Invoke the `claude-in-chrome` skill, get tab context, open a new tab:
1. `https://console.cloud.google.com/projectcreate` → Project name `Mustafa Alarm` → **Create**. Note the generated project ID (e.g. `mustafa-alarm-123456`).
2. `https://console.cloud.google.com/apis/library/calendar-json.googleapis.com?project=<project-id>` → **Enable**.
Screenshot each result page into `logs\google-cloud\` (`01-project.png`, `02-calendar-api.png`).

- [ ] **Step 3: Google Auth Platform — branding, audience, data access**

1. `https://console.cloud.google.com/auth/overview?project=<project-id>` → **Get started** → App name `Mustafa Alarm`, User support email `your-google-account@gmail.com` → Audience **External** → Contact email `your-google-account@gmail.com` → agree → **Create**.
2. `https://console.cloud.google.com/auth/audience?project=<project-id>` → confirm Publishing status **Testing** → Test users → **Add users** → `your-google-account@gmail.com` → Save.
3. `https://console.cloud.google.com/auth/scopes?project=<project-id>` → **Add or remove scopes** → "Manually add scopes": `https://www.googleapis.com/auth/calendar.app.created` → Add to table → Update → **Save**.
Screenshots `03-branding.png`, `04-audience.png`, `05-scopes.png`.

- [ ] **Step 4: OAuth clients**

1. `https://console.cloud.google.com/auth/clients/create?project=<project-id>` → Application type **Android** → Name `Mustafa Alarm Android` → Package name `com.atatuzun.mustafaalarm` → SHA-1 certificate fingerprint = the SHA-1 from `notes/signing.md` → **Create**.
2. Same page again → Application type **Web application** → Name `Mustafa Alarm server` → **Create** → copy the **Client ID** (`…apps.googleusercontent.com`). No client secret is needed by the app.
Screenshots `06-android-client.png`, `07-web-client.png`.

- [ ] **Step 5: Record and wire the values**

Write `notes/google-cloud.md`:
```markdown
# Google Cloud — Mustafa Alarm

- Project: Mustafa Alarm (`<project-id>`) under your-google-account@gmail.com
- API: Google Calendar API — enabled
- Google Auth Platform: External, **Testing**, test user your-google-account@gmail.com
- Scope: https://www.googleapis.com/auth/calendar.app.created
- Android client: `<android-client-id>` — package com.atatuzun.mustafaalarm, SHA-1 `<sha1>`
- Web client (serverClientId): `<web-client-id>`
- Testing-mode grants expire after 7 days; only setup / "Recreate" use the REST API, so this is acceptable (spec §10.1).
- Evidence: logs/google-cloud/*.png
```
(The `<…>` markers are replaced with the literal values read from the console in Steps 2–4.)

In `mustafa-alarm/gradle.properties` set `mustafaAlarm.serverClientId=<web-client-id>`.

```powershell
. .\scripts\droid.ps1
Gradle :app:assembleDebug
Select-String -Path "$Proj\app\build\generated\source\buildConfig\debug\com\atatuzun\mustafaalarm\BuildConfig.java" -Pattern 'GOOGLE_SERVER_CLIENT_ID'
```
Expected: the line shows the web client ID.

- [ ] **Step 6: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add notes/google-cloud.md mustafa-alarm/gradle.properties
Commit "chore: Google Cloud project and OAuth client IDs for Mustafa Alarm"
```

### Task 18: First-run Google setup — sign in, authorize, create "Alarms", permissions

**Files:**
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/google/{CalendarRestClient,GoogleSetup,TaskAwait}.kt`
- Create: `mustafa-alarm/app/src/main/java/com/atatuzun/mustafaalarm/ui/setup/{SetupViewModel,SetupScreen}.kt`
- Modify (replace whole file): `ui/AppNav.kt`; Modify: `ui/MainActivity.kt`, `AppGraph.kt`
- Test: `mustafa-alarm/app/src/test/java/com/atatuzun/mustafaalarm/google/CalendarRestClientTest.kt`

**Interfaces:**
- Consumes: `CalendarSetupAccess` (Task 8), `SettingsRepository`, `ReliabilityChecks`, `BuildConfig.GOOGLE_SERVER_CLIENT_ID` (Task 17), `CalendarChangeJob.schedule`, `SafetyCheckWorker.schedule`.
- Produces:
  - `fun interface HttpCall { call(method, url, token, body): HttpResult }`, `data class HttpResult(code: Int, body: String)`, `object UrlConnectionHttp : HttpCall`, `class CalendarRestClient(http: HttpCall = UrlConnectionHttp)` with `createCalendar(token, summary, timeZone): String`, `clearNotifications(token, calendarId)`, `CalendarRestClient.json(text)`.
  - `class GoogleSetup(calendar: CalendarSetupAccess, rest: CalendarRestClient, settings: SettingsRepository, log: EventLog)` with `suspend signIn(activity): String`, `suspend authorize(activity, email): AuthResult` (`Authorized(token)` | `NeedsConsent(pendingIntent)`), `tokenFromConsent(activity, data): String`, `ensureAlarmsCalendar(email, accessToken: String?): Long`; constants `SCOPE`, `CALENDAR_NAME = "Alarms"`. `AppGraph.google`.
  - `@Composable SetupScreen(graph, onDone)`; `AppNav(graph, startAtSetup: Boolean)`. Test tags `setup-next`, `setup-skip`, `setup-email`, `setup-error`.

- [ ] **Step 1: Write the failing unit test** — `test/java/com/atatuzun/mustafaalarm/google/CalendarRestClientTest.kt`

```kotlin
package com.atatuzun.mustafaalarm.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CalendarRestClientTest {
    private val calls = mutableListOf<List<String>>()

    private fun client(code: Int, body: String) = CalendarRestClient { method, url, token, payload ->
        calls += listOf(method, url, token, payload)
        HttpResult(code, body)
    }

    @Test
    fun createCalendar_postsSummaryAndZone_returnsTheId() {
        val response = """{"kind":"calendar#calendar","etag":"\"abc\"","id":"x1y2@group.calendar.google.com","summary":"Alarms"}"""
        val id = client(200, response).createCalendar("tok", "Alarms", "Asia/Famagusta")
        assertEquals("x1y2@group.calendar.google.com", id)
        assertEquals(
            listOf("POST", "https://www.googleapis.com/calendar/v3/calendars", "tok", """{"summary":"Alarms","timeZone":"Asia/Famagusta"}"""),
            calls.single(),
        )
    }

    @Test
    fun clearNotifications_patchesTheEncodedCalendarListEntry() {
        client(200, "{}").clearNotifications("tok", "x1y2@group.calendar.google.com")
        val call = calls.single()
        assertEquals("PATCH", call[0])
        assertEquals("https://www.googleapis.com/calendar/v3/users/me/calendarList/x1y2%40group.calendar.google.com", call[1])
        assertEquals("""{"defaultReminders":[],"notificationSettings":{"notifications":[]}}""", call[3])
    }

    @Test
    fun httpErrors_becomeIOExceptions() {
        assertThrows(IOException::class.java) { client(403, """{"error":"insufficientPermissions"}""").createCalendar("tok", "Alarms", "UTC") }
    }

    @Test
    fun json_escapesQuotesBackslashesAndControlCharacters() =
        assertEquals("\"a\\\"b\\\\c\\n\"", CalendarRestClient.json("a\"b\\c\n"))
}
```

- [ ] **Step 2: Run it to see it fail**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.google.CalendarRestClientTest"
```
Expected: FAIL — `Unresolved reference 'CalendarRestClient'`.

- [ ] **Step 3: Implement the REST client and run the test**

`google/CalendarRestClient.kt`:
```kotlin
package com.atatuzun.mustafaalarm.google

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

data class HttpResult(val code: Int, val body: String)

fun interface HttpCall {
    fun call(method: String, url: String, token: String, body: String): HttpResult
}

/** The two Calendar REST calls the app needs (spec §10.2 step 3). Blocking. */
class CalendarRestClient(private val http: HttpCall = UrlConnectionHttp) {

    fun createCalendar(token: String, summary: String, timeZone: String): String {
        val response = send("POST", "$BASE/calendars", token, """{"summary":${json(summary)},"timeZone":${json(timeZone)}}""")
        return ID.find(response)?.groupValues?.get(1) ?: throw IOException("Google did not return a calendar id: ${response.take(300)}")
    }

    fun clearNotifications(token: String, calendarId: String) {
        send(
            "PATCH", "$BASE/users/me/calendarList/${URLEncoder.encode(calendarId, "UTF-8")}", token,
            """{"defaultReminders":[],"notificationSettings":{"notifications":[]}}""",
        )
    }

    private fun send(method: String, url: String, token: String, body: String): String {
        val result = http.call(method, url, token, body)
        if (result.code !in 200..299) throw IOException("Google Calendar API $method $url failed: HTTP ${result.code} ${result.body.take(300)}")
        return result.body
    }

    companion object {
        const val BASE = "https://www.googleapis.com/calendar/v3"
        private val ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")

        fun json(text: String): String = buildString {
            append('"')
            text.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}

object UrlConnectionHttp : HttpCall {
    override fun call(method: String, url: String, token: String, body: String): HttpResult {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (method == "PATCH") "POST" else method // HttpURLConnection has no PATCH
            if (method == "PATCH") connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }
}
```

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest --tests "com.atatuzun.mustafaalarm.google.CalendarRestClientTest"
```
Expected: PASS (4 tests).

- [ ] **Step 4: Implement sign-in, authorization and calendar setup**

`google/TaskAwait.kt`:
```kotlin
package com.atatuzun.mustafaalarm.google

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
```

`google/GoogleSetup.kt`:
```kotlin
package com.atatuzun.mustafaalarm.google

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.atatuzun.mustafaalarm.BuildConfig
import com.atatuzun.mustafaalarm.data.calendar.CalendarRow
import com.atatuzun.mustafaalarm.data.calendar.CalendarSetupAccess
import com.atatuzun.mustafaalarm.data.calendar.GOOGLE_ACCOUNT_TYPE
import com.atatuzun.mustafaalarm.data.settings.SettingsRepository
import com.atatuzun.mustafaalarm.log.EventLog
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.time.ZoneId

/** Spec §10.2: sign in, authorize the Calendar scope, create/locate "Alarms", enable its sync and visibility. */
class GoogleSetup(
    private val calendar: CalendarSetupAccess,
    private val rest: CalendarRestClient,
    private val settings: SettingsRepository,
    private val log: EventLog,
) {
    sealed interface AuthResult {
        data class Authorized(val token: String) : AuthResult
        data class NeedsConsent(val pendingIntent: PendingIntent) : AuthResult
    }

    suspend fun signIn(activity: Activity): String {
        require(BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()) { "The Google client ID is missing from this build." }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type ${credential.type}"
        }
        val email = GoogleIdTokenCredential.createFrom(credential.data).id
        settings.update { it.copy(accountEmail = email) }
        log.log("setup: signed in as $email")
        return email
    }

    suspend fun authorize(activity: Activity, email: String): AuthResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        val consent = result.pendingIntent
        return if (result.hasResolution() && consent != null) AuthResult.NeedsConsent(consent)
        else AuthResult.Authorized(result.accessToken ?: error("Google returned no access token"))
    }

    fun tokenFromConsent(activity: Activity, data: Intent?): String =
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
            ?: error("Google returned no access token")

    /** Blocking — call off the main thread. Returns the provider id of the "Alarms" calendar. */
    fun ensureAlarmsCalendar(email: String, accessToken: String?): Long {
        val row = calendar.findCalendars(email).firstOrNull { it.displayName == CALENDAR_NAME }
            ?: createAndWait(email, accessToken ?: error("Not authorized for Google Calendar"))
        calendar.enableSyncAndVisibility(row.id)
        settings.updateBlocking { it.copy(accountEmail = email, calendarId = row.id, calendarSyncId = row.syncId) }
        log.log("setup: Alarms calendar ${row.id} (${row.syncId}) ready")
        return row.id
    }

    private fun createAndWait(email: String, token: String): CalendarRow {
        val googleId = rest.createCalendar(token, CALENDAR_NAME, ZoneId.systemDefault().id)
        log.log("setup: created Google calendar $googleId")
        runCatching { rest.clearNotifications(token, googleId) }.onFailure { log.log("setup: could not clear notifications: $it") }
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            calendar.requestSync(email)
            calendar.findCalendars(email).firstOrNull { it.syncId == googleId || it.ownerAccount == googleId }?.let { return it }
            Thread.sleep(3_000)
        }
        error("The new Alarms calendar did not reach the phone within 2 minutes. Check that Google Calendar sync is on, then Retry.")
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/calendar.app.created"
        const val CALENDAR_NAME = "Alarms"
    }
}
```

In `AppGraph.kt` add the imports `com.atatuzun.mustafaalarm.google.CalendarRestClient`, `com.atatuzun.mustafaalarm.google.GoogleSetup` and, after the `checks` line, add:
```kotlin
    val google: GoogleSetup by lazy { GoogleSetup(calendar, CalendarRestClient(), settings, log) }
```

`ui/setup/SetupViewModel.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.setup

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.google.GoogleSetup
import com.atatuzun.mustafaalarm.system.Check
import com.atatuzun.mustafaalarm.watch.CalendarChangeJob
import com.atatuzun.mustafaalarm.watch.SafetyCheckWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SetupStep(val title: String, val reason: String) {
    SIGN_IN("Sign in with Google", "Your alarms are kept in your Google Calendar."),
    AUTHORIZE("Allow Google Calendar", "Lets Mustafa Alarm create its own Alarms calendar."),
    CALENDAR("Alarms calendar", "Creates or finds the Alarms calendar and turns on its sync."),
    NOTIFICATIONS("Notifications", "Needed to show a ringing alarm."),
    FULL_SCREEN("Full-screen alarms", "Shows the alarm over the lock screen."),
    BATTERY("Battery: Unrestricted", "Stops Samsung from putting the app to sleep."),
}

data class SetupUi(
    val step: SetupStep = SetupStep.SIGN_IN,
    val email: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val consent: PendingIntent? = null,
    val finished: Boolean = false,
)

class SetupViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = mutable.asStateFlow()
    private var token: String? = null
    private val skipped = mutableSetOf<SetupStep>()
    private val permissionSteps = listOf(
        SetupStep.NOTIFICATIONS to Check.NOTIFICATIONS,
        SetupStep.FULL_SCREEN to Check.FULL_SCREEN,
        SetupStep.BATTERY to Check.BATTERY,
    )

    fun signIn(activity: Activity) = attempt {
        val email = graph.google.signIn(activity)
        mutable.update { it.copy(email = email, step = SetupStep.AUTHORIZE) }
    }

    fun authorize(activity: Activity) = attempt {
        when (val result = graph.google.authorize(activity, checkNotNull(mutable.value.email))) {
            is GoogleSetup.AuthResult.Authorized -> {
                token = result.token
                mutable.update { it.copy(step = SetupStep.CALENDAR) }
            }
            is GoogleSetup.AuthResult.NeedsConsent -> mutable.update { it.copy(consent = result.pendingIntent) }
        }
    }

    fun consentLaunched() = mutable.update { it.copy(consent = null) }

    fun onConsentResult(activity: Activity, data: Intent?) = attempt {
        token = graph.google.tokenFromConsent(activity, data)
        mutable.update { it.copy(step = SetupStep.CALENDAR) }
    }

    fun setUpCalendar() = attempt {
        withContext(Dispatchers.IO) {
            graph.google.ensureAlarmsCalendar(checkNotNull(mutable.value.email), token)
            CalendarChangeJob.schedule(graph.context)
            SafetyCheckWorker.schedule(graph.context)
            graph.scheduler.reschedule("setup")
        }
        mutable.update { it.copy(step = SetupStep.NOTIFICATIONS) }
        advance()
    }

    fun fail(message: String) = mutable.update { it.copy(error = message) }

    fun skip() {
        skipped += mutable.value.step
        advance()
    }

    /** Moves past permission steps that are granted or skipped; runs on resume and after each request. */
    fun advance() {
        if (mutable.value.step.ordinal < SetupStep.NOTIFICATIONS.ordinal) return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { graph.checks.status() }
            val next = permissionSteps.firstOrNull { (step, check) -> status[check] != true && step !in skipped }?.first
            mutable.update { if (next == null) it.copy(finished = true, error = null) else it.copy(step = next, error = null) }
        }
    }

    private fun attempt(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.log.log("setup failed: $e")
                mutable.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
```

`ui/setup/SetupScreen.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.system.Check
import com.atatuzun.mustafaalarm.ui.Banner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(graph: AppGraph, onDone: () -> Unit) {
    val vm = viewModel { SetupViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onConsentResult(activity, result.data)
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) vm.setUpCalendar() else vm.fail("Calendar permission is needed to find the Alarms calendar.")
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.advance() }
    LaunchedEffect(ui.consent) {
        ui.consent?.let {
            consent.launch(IntentSenderRequest.Builder(it).build())
            vm.consentLaunched()
        }
    }
    LaunchedEffect(ui.finished) { if (ui.finished) onDone() }
    LifecycleResumeEffect(Unit) {
        vm.advance()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Set up Mustafa Alarm") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.email?.let { Text("Signed in as $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("setup-email")) }
            SetupStep.entries.forEach { step ->
                StepRow(step, done = ui.finished || step.ordinal < ui.step.ordinal, current = !ui.finished && step == ui.step)
            }
            ui.error?.let { Banner(it, null, "setup-error") {} }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                enabled = !ui.busy,
                onClick = {
                    when (ui.step) {
                        SetupStep.SIGN_IN -> vm.signIn(activity)
                        SetupStep.AUTHORIZE -> vm.authorize(activity)
                        SetupStep.CALENDAR ->
                            if (hasCalendarPermission(activity)) vm.setUpCalendar()
                            else calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        SetupStep.NOTIFICATIONS -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        SetupStep.FULL_SCREEN -> activity.startActivity(graph.checks.fixIntent(Check.FULL_SCREEN))
                        SetupStep.BATTERY -> activity.startActivity(graph.checks.fixIntent(Check.BATTERY))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("setup-next"),
            ) { Text(if (ui.error != null) "Retry" else ui.step.title) }
            if (ui.step.ordinal >= SetupStep.NOTIFICATIONS.ordinal) {
                TextButton(onClick = vm::skip, modifier = Modifier.testTag("setup-skip")) { Text("Skip for now") }
            }
        }
    }
}

@Composable
private fun StepRow(step: SetupStep, done: Boolean, current: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when {
                done -> Icons.Filled.CheckCircle
                current -> Icons.Filled.RadioButtonChecked
                else -> Icons.Filled.RadioButtonUnchecked
            },
            contentDescription = if (done) "Done" else null,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(step.title, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
            Text(step.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun hasCalendarPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
```

Replace `ui/AppNav.kt`:
```kotlin
package com.atatuzun.mustafaalarm.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.ui.edit.EditAlarmScreen
import com.atatuzun.mustafaalarm.ui.list.AlarmListScreen
import com.atatuzun.mustafaalarm.ui.quick.QuickAlarmsScreen
import com.atatuzun.mustafaalarm.ui.settings.SettingsScreen
import com.atatuzun.mustafaalarm.ui.setup.SetupScreen

object Routes {
    const val LIST = "list"
    const val NEW = "edit"
    const val EDIT = "edit?eventId={eventId}"
    const val QUICK = "quick"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    fun edit(eventId: Long) = "edit?eventId=$eventId"
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppNav(graph: AppGraph, startAtSetup: Boolean) {
    val nav = rememberNavController()
    NavHost(
        nav,
        startDestination = if (startAtSetup) Routes.SETUP else Routes.LIST,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
        composable(Routes.SETUP) {
            SetupScreen(graph, onDone = { nav.navigate(Routes.LIST) { popUpTo(nav.graph.id) { inclusive = true } } })
        }
        composable(Routes.LIST) {
            AlarmListScreen(
                graph = graph,
                onNew = { nav.navigate(Routes.NEW) },
                onEdit = { nav.navigate(Routes.edit(it)) },
                onQuick = { nav.navigate(Routes.QUICK) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onSetup = { nav.navigate(Routes.SETUP) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            EditAlarmScreen(graph, entry.arguments?.getLong("eventId")?.takeIf { it > 0 }, onDone = { nav.popBackStack() })
        }
        composable(Routes.QUICK) {
            QuickAlarmsScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onCreateOwn = { nav.navigate(Routes.NEW) },
                onAddMessage = { nav.navigate(Routes.edit(it)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(graph, onBack = { nav.popBackStack() }, onSwitchAccount = { nav.navigate(Routes.SETUP) })
        }
    }
}
```

In `ui/MainActivity.kt` add the import `androidx.compose.runtime.remember` and replace
```kotlin
                MustafaAlarmTheme(dark = settings.darkTheme) {
                    Surface(Modifier.fillMaxSize()) { AppNav(graph) }
                }
```
with
```kotlin
                val startAtSetup = remember { settings.calendarId == null }
                MustafaAlarmTheme(dark = settings.darkTheme) {
                    Surface(Modifier.fillMaxSize()) { AppNav(graph, startAtSetup) }
                }
```

- [ ] **Step 5: Emulator regression + first-run screen**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest :app:installDebug
foreach ($c in 'check-list', 'check-edit', 'check-quick', 'check-settings') { & ".\scripts\$c.ps1" }
A shell pm clear $Pkg | Out-Null
Grant-All
Open-App
Assert-Text 'Set up Mustafa Alarm'; Assert-Text 'Sign in with Google'
Shot 'task18-first-run-emulator'
```
Expected: four `PASS` lines; the cleared app opens on the setup screen. (The emulator has no Google account, so setup stops here; restore the emulator's test calendar afterwards with `Debug-Cmd USE_LOCAL_CALENDAR`.)

- [ ] **Step 6: Real first run on the phone (Mustafa presses Allow on Google's screens)**

Tell Mustafa: "Installing the real app on your phone and starting setup. When Google shows the account picker and the 'Mustafa Alarm wants to access your Google Account' screen, please choose your-google-account@gmail.com and press Continue/Allow."
```powershell
. .\scripts\droid.ps1
$env:DEVICE = $Phone
A shell pm clear $Pkg | Out-Null   # wipes only the spike-era app data; no alarms exist yet on the phone
Gradle :app:installDebug
Mark-AppLog
Open-App
Assert-Text 'Set up Mustafa Alarm'
Tap-Id 'setup-next'                       # Sign in with Google → account picker (Mustafa)
Wait-AppLog 'setup: signed in as your-google-account@gmail.com' 180 | Out-Null
Tap-Id 'setup-next'                       # Allow Google Calendar → consent screen (Mustafa)
Start-Sleep 5
Shot 'task18-after-consent'
Tap-Id 'setup-next'                       # Alarms calendar → Android calendar permission dialog
Start-Sleep 2
if (Find-Node 'Allow') { Tap-Text 'Allow' }
Wait-AppLog 'setup: Alarms calendar \d+ \(.+\) ready' 180 | Out-Null
Shot 'task18-permissions'
```
Then, for each remaining step shown, press `setup-next` and accept the system screen (notifications dialog: `Tap-Text 'Allow'`; full-screen: the system toggle for Mustafa Alarm; battery: `Tap-Text 'Allow'`), until the list screen appears (`Assert-Text 'My Alarms'`). Screenshot `task18-done`.

- [ ] **Step 7: Verify setup end to end**

1. `mcp__claude_ai_Google_Calendar__list_calendars` shows a calendar with summary `Alarms`; record its id in `notes/google-cloud.md`.
2. `AppLog` contains `setup: created Google calendar <id>` and `setup: Alarms calendar <n> (<id>) ready`, and no `could not clear notifications` line (if it does, note it — the calendar was created by the API without default reminders anyway).
3. In the app, Settings → "Alarms calendar: OK", reliability check all green (scroll + screenshot `task18-reliability.png`); the home screen shows no sync banner.
4. Create an alarm on the phone through the UI ("Setup check", 23:59 today, via `New alarm` → pad `2359` → message → save). Within 2 minutes `mcp__claude_ai_Google_Calendar__list_events` on the Alarms calendar for today shows "Setup check" at 23:59–00:14. Delete it in the app; within 2 minutes it is gone from Google.

- [ ] **Step 8: Commit**

```powershell
. .\scripts\droid.ps1
git -C $Root add mustafa-alarm/app/src notes/google-cloud.md
Commit "feat(setup): Google sign-in, Calendar authorization and Alarms calendar creation"
```

### Task 19: Real phone — ringing, two-way Google sync, Samsung reality check, clean-up and hand-over

Spec §12 Layers 3–5 on the S24 Ultra. **Rules:** announce every test alarm; low volume; disruptive steps (reboot, Doze, time zone, long idle, battery "Optimized") only after Mustafa says yes; test events only in "Alarms".

**Files:**
- Modify: `notes/test-report.md` (phone, sync and Samsung sections), `notes/spike-results.md` (spike calendar deleted)
- Evidence: `logs\ring-scenarios\*` (phone runs), `logs\sync\*`, `logs\samsung\*`

**Interfaces:**
- Consumes: everything above; Google Calendar tools `list_calendars`, `list_events`, `get_event`, `create_event`, `update_event`, `delete_event` (load them with ToolSearch before use).
- Produces: a verified installation on the phone and the final test report.

- [ ] **Step 1: Ask once for the disruptive tests**

Ask Mustafa (AskUserQuestion, multi-select): "Which of these may I run on your phone now? Each is announced and the alarm volume is kept low: (a) reboot and let an alarm ring before you unlock — you will need to unlock the phone afterwards, (b) force Doze for a few minutes, (c) switch the time zone to London for ~5 minutes, (d) leave the phone idle with the screen off for 2+ hours with one alarm at the end, (e) switch battery to 'Optimized' for 30 minutes and back." Run only the approved ones in the steps below.

- [ ] **Step 2: Quiet test settings on the phone**

```powershell
. .\scripts\droid.ps1
$env:DEVICE = $Phone
Gradle :app:installDebug
Debug-Cmd SETTINGS @('--ez', 'increaseDeviceVolume', 'false', '--ei', 'volumePercent', '10')
A shell cmd media_session volume --stream 4 --set 3 | Out-Null
```

- [ ] **Step 3: Layer 3 on the phone**

Announce: "Test alarms will ring quietly on your phone for the next ~20 minutes."
```powershell
. .\scripts\droid.ps1
$always = 'basic', 'snooze', 'tomorrow', 'row-stop', 'locked', 'killed', 'update', 'auto-snooze', 'same-minute'
$summary = Join-Path (New-Evidence 'ring-scenarios') 'phone-summary.txt'
foreach ($s in $always) {
    try { .\scripts\ring-scenarios.ps1 -Scenario $s -Device $Phone 2>&1 | Tee-Object -Append $summary }
    catch { "ring-scenarios ${s}: FAIL $_" | Tee-Object -Append $summary }
}
```
Then, only if approved: `reboot-locked` (a), `doze` (b), `timezone` (c) with the same command. Expected: every run ends in `PASS`. A phone-only failure is debugged with superpowers:systematic-debugging (Samsung differences are exactly what this layer is for), fixed, re-verified on the emulator (Task 12 list) and on the phone.

- [ ] **Step 4: Layer 4 — Google Calendar on the PC → phone**

Use the Google Calendar tools on the "Alarms" calendar (id from `notes/google-cloud.md`). For each row, note the PC action time T0, then poll the phone (`Mark-AppLog` before the action; `Wait-AppLog 'calendar changed' 600`; `Debug-Cmd LIST`) and record the latency = first matching LIST − T0. Save each LIST excerpt to `logs\sync\<case>.txt`.

| Case | PC action | Expected on the phone |
|---|---|---|
| create | `create_event` "PC created" today now+10 min, 15 min | LIST shows 'PC created' at that time, on=true; it rings at that time (quiet), Stop it |
| stop-back | after the Stop above, `get_event` "PC created" | `colorId` = "8" (Graphite) |
| move | `create_event` "PC move" now+60 min, then `update_event` → now+90 min | LIST shows the new time |
| graphite | `update_event` "PC move" colour Graphite (colorId 8) | LIST on=false |
| delete | `delete_event` "PC move" | not in LIST |
| daily | `create_event` "PC daily" now+5 min, recurrence `RRULE:FREQ=DAILY;COUNT=3` | rings; press **Snooze** on the phone; `list_events` (single events) shows today's occurrence at +30 min, tomorrow's unchanged |
| daily-tomorrow | when the snoozed "PC daily" rings again, press **Tomorrow** | `list_events` shows today's occurrence gone (tomorrow already had one) |
| all-day | `create_event` "PC all day" all-day tomorrow | not in LIST, never rings |
| phone-side | create "Phone made" on the phone UI (tomorrow 09:15), edit it to 09:45, turn it off | `list_events` shows it at 09:45 with colorId 8 within ~2 min of each change |

```powershell
. .\scripts\droid.ps1
$env:DEVICE = $Phone
Mark-AppLog
# … perform the PC action with the Google Calendar tool, then:
Wait-AppLog 'calendar changed' 600
Debug-Cmd LIST
AppLog | Select-String "debug: item" | Select-Object -Last 20 | Out-File -Encoding utf8 (Join-Path (New-Evidence 'sync') '<case>.txt')
```
(Replace `<case>` with the row name each time.)

- [ ] **Step 5: Layer 5 — Samsung reality check**

1. Reliability screen all ✓: open Settings, scroll to "Reliability check", screenshot `logs\samsung\reliability.png`; `A shell dumpsys deviceidle whitelist | Select-String $Pkg` shows the app (= Unrestricted).
2. (d, if approved) Create "Idle check" for 2 h from now with `Debug-Cmd CREATE @('--ei','inMinutes','120','--es','msg','Idle_check')`, turn the screen off, leave the phone. Afterwards: `AppLog | Select-String "Idle check"` shows `ringing: 'Idle check' at T` logged within 10 s of T; save to `logs\samsung\idle.txt`.
3. (e, if approved) `A shell dumpsys deviceidle whitelist -$Pkg` (battery "Optimized"), create "Optimized check" in 30 min, screen off; verify it rang on time the same way; then `A shell dumpsys deviceidle whitelist +$Pkg` and confirm the reliability check is green again.

- [ ] **Step 6: Clean up**

1. Delete the test events from "Alarms" with the Google Calendar tools (titles used by the scenarios and Step 4: Basic ring, Snooze test, Tomorrow test, Row one, Row two, Locked ring, Killed app, After update, No answer, Same minute 1–4, Before unlock, Doze ring, Tz change, PC created, PC daily, Phone made, Idle check, Optimized check) — only events with exactly these titles.
2. Restore the defaults: `Debug-Cmd SETTINGS @('--ez','increaseDeviceVolume','true','--ei','volumePercent','100','--ei','snoozeMinutes','30','--ei','autoSnoozeMinutes','1','--ez','fadeIn','true')`.
3. In Chrome: `https://calendar.google.com/calendar/u/0/r/settings` → "Alarms Spike" → Remove calendar → **Delete** (tell Mustafa first). Confirm with `list_calendars` that only "Alarms" remains of the two; note it in `notes/spike-results.md`.

- [ ] **Step 7: Final report and full regression**

```powershell
. .\scripts\droid.ps1
Gradle :app:testDebugUnitTest
```
Expected: all unit tests pass. Complete `notes/test-report.md` with: Layer 3 phone table (scenario → PASS, evidence path), Layer 4 table (case → result, latency in seconds), Layer 5 results, and a short "Known limits" list (e.g. Testing-mode Google grant expires after 7 days → only "Recreate" needs a fresh consent; a "Tomorrow" pressed before first unlock is applied to Google after the unlock).

- [ ] **Step 8: Commit and finish the branch**

```powershell
. .\scripts\droid.ps1
git -C $Root add notes/test-report.md notes/spike-results.md
Commit "test: phone, two-way sync and Samsung reliability results"
```
Then use superpowers:finishing-a-development-branch. Tell Mustafa: "Mustafa Alarm is installed and verified. Keep Simple Alarm installed for a few days while you use the new app; when you are happy, turn off your Simple Alarm alarms. Your alarms are in the 'Alarms' calendar on your PC."

