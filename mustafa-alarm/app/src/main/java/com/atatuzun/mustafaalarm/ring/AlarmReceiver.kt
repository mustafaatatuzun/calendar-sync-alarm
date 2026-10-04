package com.atatuzun.mustafaalarm.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.atatuzun.mustafaalarm.graph

/**
 * AlarmManager → foreground ringing service (an exact-alarm broadcast may start a foreground service).
 *
 * Carryover #5 (binding): any provider call that might throw is wrapped in RingingService.fire() itself;
 * the receiver just starts the service and returns quickly.
 */
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
