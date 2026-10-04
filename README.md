# Calendar SYNC Alarm

An Android alarm clock that uses your **Google Calendar as the alarm source of truth**.

Set alarms on your PC in Google Calendar web, they ring on your phone. Edit them on your phone, they update on Google. One personal "Alarms" calendar on your Google account becomes the backing store — nothing is kept locally that you can't restore from Google.

Built in Kotlin + Jetpack Compose + Material 3, targeting modern Android (minSdk / targetSdk 36).

![Not published to Play Store](https://img.shields.io/badge/distribution-APK%20only-blue)
![Kotlin](https://img.shields.io/badge/kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/jetpack%20compose-material3-4285F4?logo=android&logoColor=white)
![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-green)

## Why

Simple Alarm by Base Juegos is a good alarm app, but it has no PC-side editor. Google Calendar on the web is the PC editor, but Google's own calendar app doesn't ring as reliably as a dedicated alarm app. This app glues the two together: your PC becomes the editing surface, your phone becomes the ringing surface, and the "Alarms" calendar on your Google account is the two-way-synced single source of truth.

## Features

- **Full-screen ringing** over the lock screen (with the right system permissions enabled during setup).
- **Three-tap Stop** and **three-tap Delete** on the ringing screen so a fat finger can't dismiss the alarm — the delete button also removes the underlying Google Calendar event.
- **Google Calendar as the backing store** — the app owns a dedicated "Alarms" calendar on your Google account. Every event in it is an alarm. Any event you add from a PC browser becomes an alarm.
- **Google Calendar's recurrence shapes:** Does not repeat / Daily / Every weekday / Weekly on selected days / Monthly on day N / Monthly on the Nth weekday / Annually.
- **Reinstall-safe:** alarms survive uninstall. The setup flow finds the existing "Alarms" calendar on your Google account and skips sign-in when it does.
- **Quick alarms** and a per-alarm **custom ringtone**.
- **Reliability panel** that catches Samsung's silent-reject default for `USE_FULL_SCREEN_INTENT` and the usual battery / notifications / exact-alarm permissions.
- **Home screen UX:** bottom bar with Search textbox + Quick + Add; compact alarm cards with the title getting the whole row.

## Install

Grab the latest APK from the [`releases/`](releases/) folder or from the GitHub Releases page and sideload it.

The APK is **debug-signed with a project keystore that is not published**. You'll see a signing-authority warning on first install — that's expected for sideloaded apps. If you'd rather build it yourself, see "Build" below.

### First run

1. **Setup** walks you through a few permissions.
2. **Google sign-in** — the app asks for Calendar access. It only uses `calendar.app.created` (can create one calendar; cannot read your other calendars). The "Alarms" calendar is created or found during this step.
3. **Full-screen alarms permission** — on Samsung the toggle may already look turned on but is actually in the system's default (reject) state. The app's setup step tells you to flip it off and back on to confirm it.
4. **Battery: Unrestricted** — so the alarm fires even after Doze.

That's it. Your "Alarms" calendar is now the single place to add/edit alarms, from the phone or from a PC browser.

## Build

```powershell
# Windows / PowerShell
# 1. Install JDK 17 (Microsoft OpenJDK is fine)
# 2. Install Android Studio or the standalone Android SDK; set ANDROID_HOME
# 3. Clone and build
git clone https://github.com/mustafaatatuzun/calendar-sync-alarm.git
cd calendar-sync-alarm\mustafa-alarm
.\gradlew.bat :app:assembleRelease
# APKs land in app\build\outputs\apk\release\
```

For debug (unsigned, no R8 shrinking) use `assembleDebug`. The release build splits by ABI and also produces a universal APK.

### Google Cloud / OAuth setup

To build and run your own copy you need a Google Cloud project with an OAuth client ID bound to your package name and signing cert. See `docs/superpowers/specs/2026-10-02-mustafa-alarm-design.md` §Setup for the full recipe. The short version:

1. Create a project in Google Cloud Console.
2. Enable the Google Calendar API.
3. Create an OAuth client ID of type Android, with your `applicationId` (`com.atatuzun.mustafaalarm`) and the SHA-1 of your signing certificate.
4. Add the OAuth client ID to `gradle.properties` as `mustafaAlarm.serverClientId` (so it ends up as a `BuildConfig` field).

## Project layout

```
mustafa-alarm/                       Android app (Gradle + Kotlin + Compose)
  app/src/main/java/com/atatuzun/mustafaalarm/
    domain/     RecurrenceRule, AlarmStore, Times, Texts, …
    ring/       RingingService + RingingActivity + press-counter / notifications
    ui/         Compose screens: setup, list, edit, settings, quick
    system/     Reliability checks, permissions, FSI appop probe
docs/superpowers/
  specs/        Design docs for the main project and the recurrence/delete work
  plans/        Task-by-task implementation plans
scripts/        PowerShell helpers for running against an emulator / device
releases/       Signed universal APK ready to install
```

## Design docs

The `docs/superpowers/` folder has the real design docs the app was built from. They're the most honest explanation of why the code is the shape it is:

- `specs/2026-10-02-mustafa-alarm-design.md` — the ground-truth design for the alarm pipeline + Google Calendar two-way sync.
- `specs/2026-10-03-recurrence-and-delete-ringing-design.md` — the recurrence picker + three-tap Delete + Samsung FSI appop fix.
- `plans/` — the task-by-task implementation plans that produced the above commits.

## License

[GPL-3.0](LICENSE). You can use, modify, and share this freely, including commercially; any derivative work you distribute must stay under the same license and include its source. The author built this for personal use; it is shared in the hope it is useful, with **no warranty**.

## Credits

- Inspired by [Simple Alarm by Base Juegos](https://play.google.com/store/apps/details?id=com.simplealarm.basejuegos).
- The specs, plans, implementation, and this README were written collaboratively with [Claude Code](https://claude.com/claude-code) (Anthropic).
