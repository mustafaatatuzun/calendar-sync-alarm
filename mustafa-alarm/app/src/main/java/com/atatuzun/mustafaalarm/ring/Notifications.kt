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
import com.atatuzun.mustafaalarm.data.settings.StopMethod
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
            NotificationChannel(CHANNEL_RINGING_QUIET, "Ringing alarm (screen open)", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
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

    /**
     * The foreground notification while ringing: full-screen intent, category alarm,
     * Snooze + Stop (dynamic label) + Delete (spec §8).
     *
     * [stopPresses] / [deletePresses] drive the countdown labels on the Stop and Delete actions.
     * [quiet]: the ringing screen is already showing, so post on a low-importance channel without the
     * full-screen intent; that takes the heads-up pop-up off the top of the ringing screen.
     */
    fun ringing(
        entries: List<RingingEntry>,
        settings: AlarmSettings,
        stopPresses: Int = 0,
        deletePresses: Int = 0,
        quiet: Boolean = false,
    ): Notification {
        val zone = ZoneId.systemDefault()
        val pressesNeeded = RingingService.pressesNeededFor(settings.stopMethod)
        val screen = PendingIntent.getActivity(
            context, 20,
            Intent(context, RingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, if (quiet) CHANNEL_RINGING_QUIET else CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(entries.joinToString(" · ") { it.title }.ifEmpty { DEFAULT_TITLE })
            .setContentText(entries.firstOrNull()?.let { "Alarm ${Texts.clock(it.ringAt, zone, settings.use24Hour)}" } ?: "Alarm")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(screen)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (!quiet) builder.setFullScreenIntent(screen, true)
        if (settings.showSnoozeButton) builder.addAction(serviceAction("Snooze ${settings.snoozeMinutes} m", RingingService.ACTION_SNOOZE, 21))
        val stopLabel = if (stopPresses == 0) "Stop" else "Stop (${pressesNeeded - stopPresses} more)"
        builder.addAction(serviceAction(stopLabel, RingingService.ACTION_STOP, 22))
        val deleteLabel = if (deletePresses == 0) "Delete" else "Delete (${pressesNeeded - deletePresses} more)"
        builder.addAction(serviceAction(deleteLabel, RingingService.ACTION_DELETE, 23))
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
        const val CHANNEL_RINGING_QUIET = "ringing_quiet"
        const val CHANNEL_NEXT = "next_alarm"
        const val ID_RINGING = 1
        const val ID_NEXT = 2
    }
}
