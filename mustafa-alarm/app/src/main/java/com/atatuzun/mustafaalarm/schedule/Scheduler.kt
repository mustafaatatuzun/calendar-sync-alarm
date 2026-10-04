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
import com.atatuzun.mustafaalarm.ring.RingingService
import com.atatuzun.mustafaalarm.ui.MainActivity

/**
 * Arms AlarmManager for the next cached ring time (spec §7).
 *
 * Decision #4 (binding): primary call is setAlarmClock. If canScheduleExactAlarms()
 * returns false (USE_EXACT_ALARM declared but revoked by the user), fall back to
 * setAndAllowWhileIdle (INEXACT) — never setExactAndAllowWhileIdle which also
 * requires the same permission. Log + surface ✗ in reliability data.
 */
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

    /**
     * Refreshes the ring cache and arms AlarmManager for the earliest cached time.
     * Blocking; synchronized so concurrent reschedule calls from workers and receivers
     * do not race on the AlarmManager state. Never call on the main thread.
     *
     * Log format: `reschedule[<reason>] next=<yyyy-MM-dd HH:mm:ss|none> cached=<n>`
     */
    @Synchronized
    fun reschedule(reason: String): Long? {
        val now = System.currentTimeMillis()
        val cache = store.refreshRingCache()
        // Marked as ringing but the ringer is not running (process was killed): restart it through AlarmManager.
        val ringingButSilent = local.ringing().isNotEmpty() && !RingingService.isRunning
        val trigger = if (ringingButSilent) now + 1_000 else RingPlanner.nextTrigger(cache)
        val operation = firePendingIntent()
        when {
            trigger == null -> alarmManager.cancel(operation)
            alarmManager.canScheduleExactAlarms() ->
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showPendingIntent()), operation)
            else -> {
                // Decision #4 (binding): USE_EXACT_ALARM declared but not currently granted.
                // Exact variants throw SecurityException here — use inexact setAndAllowWhileIdle.
                // The reliability check (Task 16) will surface this as ✗.
                log.log("reschedule[$reason]: exact alarms not allowed, using inexact setAndAllowWhileIdle")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
            }
        }
        // Notification update must not block the alarm arm; swallow errors.
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

    // ── private ──────────────────────────────────────────────────────────────

    /** The PendingIntent that fires AlarmReceiver.ACTION_FIRE. Idempotent (FLAG_UPDATE_CURRENT). */
    private fun firePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The "show" PendingIntent passed to AlarmClockInfo — tapping the alarm notification opens the app. */
    private fun showPendingIntent(): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}
