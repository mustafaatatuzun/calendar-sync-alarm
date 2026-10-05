package com.atatuzun.mustafaalarm.system

import android.Manifest
import android.accounts.Account
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
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
    OVER_OTHER_APPS("Show over other apps", "Opens the full-screen alarm while you are using the phone."),
    BATTERY("Battery: Unrestricted", "Stops Samsung from putting the app to sleep."),
    SYNC("Google sync for Alarms", "Lets your PC see changes made on the phone."),
}

data class Banners(
    val volumeLow: Boolean = false,
    val missingPermission: Boolean = false,
    val syncOff: Boolean = false,
)

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
            Check.FULL_SCREEN to (
                notifications.canUseFullScreenIntent() && fullScreenIntentAppOpAllowed(context)
            ),
            Check.OVER_OTHER_APPS to Settings.canDrawOverlays(context),
            Check.BATTERY to context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            Check.SYNC to syncOn(settings.current()),
        )
    }

    fun banners(): Banners {
        val s = settings.current()
        val status = status()
        val audio = context.getSystemService(AudioManager::class.java)
        // volumeLow: alarm stream below 50% of max OR app volumePercent below 50%, AND
        // increaseDeviceVolume is off (meaning the app won't raise the device volume while ringing).
        val volumeLow = !s.increaseDeviceVolume && (
            alarmVolumeLowPredicate(
                audio.getStreamVolume(AudioManager.STREAM_ALARM),
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            ) || s.volumePercent < 50
        )
        return Banners(
            volumeLow = volumeLow,
            missingPermission = status.filterKeys { it != Check.SYNC }.containsValue(false),
            syncOff = status[Check.SYNC] == false,
        )
    }

    /** Alarm stream below 50 % of its maximum. */
    fun alarmVolumeLow(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        return alarmVolumeLowPredicate(
            audio.getStreamVolume(AudioManager.STREAM_ALARM),
            audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
        )
    }

    fun fixIntent(check: Check): Intent {
        val pkg = Uri.fromParts("package", context.packageName, null)
        val intent = when (check) {
            Check.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Check.CALENDAR -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            Check.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
            Check.FULL_SCREEN -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            Check.OVER_OTHER_APPS -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
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
        return syncPredicate(
            accountEmail = email,
            masterSyncOn = ContentResolver.getMasterSyncAutomatically(),
            accountSyncOn = ContentResolver.getSyncAutomatically(Account(email, GOOGLE_ACCOUNT_TYPE), CalendarContract.AUTHORITY),
            calendarSyncEvents = row?.syncEvents == true,
        )
    }

    private fun fullScreenIntentAppOpAllowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
        @Suppress("DEPRECATION", "DiscouragedApi")
        val mode = ops.unsafeCheckOpNoThrow(
            "android:use_full_screen_intent",
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

/**
 * Pure (context-free) predicate for the SYNC check.
 * The Android-specific ContentResolver wiring stays in [ReliabilityChecks.syncOn];
 * this function encapsulates the branching logic for JVM-testability.
 *
 * Returns true when [accountEmail] is null (phone-local calendar: nothing to sync),
 * or when all three sync flags are enabled.
 */
internal fun syncPredicate(
    accountEmail: String?,
    masterSyncOn: Boolean,
    accountSyncOn: Boolean,
    calendarSyncEvents: Boolean,
): Boolean {
    if (accountEmail == null) return true  // phone-local test calendar: nothing to sync
    return masterSyncOn && accountSyncOn && calendarSyncEvents
}

/**
 * Pure (context-free) predicate for the alarm-volume-low banner.
 * [streamVolume] × 2 < [maxVolume] means the alarm stream is below 50 % of its maximum.
 * The AudioManager read stays in [ReliabilityChecks]; this function is JVM-testable.
 */
internal fun alarmVolumeLowPredicate(streamVolume: Int, maxVolume: Int): Boolean =
    streamVolume * 2 < maxVolume
