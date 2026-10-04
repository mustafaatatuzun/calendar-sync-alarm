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
        // Only schedule jobs after first unlock: before that the calendar is unreadable
        // and WorkManager may not have initialised fully in direct-boot mode.
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            CalendarChangeJob.schedule(this)
            SafetyCheckWorker.schedule(this)
        }
    }
}
