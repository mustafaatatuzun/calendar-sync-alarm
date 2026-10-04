package com.atatuzun.mustafaalarm.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.atatuzun.mustafaalarm.graph

/**
 * Responds to system events that require a reschedule (spec §7):
 * - LOCKED_BOOT_COMPLETED / BOOT_COMPLETED: re-arm after a restart
 * - MY_PACKAGE_REPLACED: re-arm after an app update
 * - TIME_SET / TIMEZONE_CHANGED: absolute times changed, rebuild the ring cache
 * - SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED: permission revoked/re-granted
 *
 * directBootAware="true" so the LOCKED_BOOT_COMPLETED broadcast is received
 * before the user's first unlock after reboot.
 */
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
