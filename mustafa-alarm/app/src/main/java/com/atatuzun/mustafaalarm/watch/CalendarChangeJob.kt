package com.atatuzun.mustafaalarm.watch

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import com.atatuzun.mustafaalarm.graph

/**
 * Fires when the calendar provider changes (our writes or the Google sync adapter).
 * Re-schedules itself after each run so the trigger is always registered.
 *
 * Decision #5 (binding): primary trigger URI is CalendarContract.CONTENT_URI; Events.CONTENT_URI
 * is added defensively. This matches the Task 2 SpikeTest's risk3_scheduleChangeJob assertion.
 *
 * JOB_ID = 1001 and schedule(context) signature are pinned (Task 2 carryover).
 */
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
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.Events.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    ),
                )
                .setTriggerContentUpdateDelay(1_000)
                .setTriggerContentMaxDelay(5_000)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
