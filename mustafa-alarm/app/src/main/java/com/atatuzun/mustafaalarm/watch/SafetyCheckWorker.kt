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

/**
 * WorkManager periodic worker (every 15 min) that idempotently re-arms the alarm and the
 * calendar-change job (spec §7 safety-check). Uses KEEP policy so it survives app restarts
 * without resetting the 15-minute window.
 *
 * Warning logic (spec §7):
 * - warns when the system's next alarm clock is LATER than our expected trigger, or null.
 * - does NOT warn when the system clock is earlier than ours (Samsung Clock may own one).
 */
class SafetyCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val graph = applicationContext.graph
        try {
            val expected = RingPlanner.nextTrigger(graph.local.ringCache())
            val armed = graph.scheduler.systemNextAlarmClock()
            if (expected != null && expected > System.currentTimeMillis() &&
                (armed == null || armed > expected)
            ) {
                graph.log.log(
                    "safety: alarm for ${EventLog.time(expected)} was not armed " +
                        "(system next=${armed?.let { EventLog.time(it) } ?: "null"})",
                )
            }
            graph.scheduler.reschedule("safety-check")
            CalendarChangeJob.schedule(applicationContext)
        } catch (t: Throwable) {
            graph.log.log("safety check failed: $t")
        }
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
