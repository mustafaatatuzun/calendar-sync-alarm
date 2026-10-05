package com.atatuzun.mustafaalarm.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.data.settings.StopMethod
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.RingPlanner
import com.atatuzun.mustafaalarm.domain.RingingEntry
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.log.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val autoSnoozeRunnable = Runnable { executor.execute { handle(ACTION_AUTO_SNOOZE, null) } }

    private lateinit var serviceScope: CoroutineScope
    private lateinit var stopCounter: PressCounter
    private lateinit var deleteCounter: PressCounter

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        _stopPresses.value = 0
        _deletePresses.value = 0

        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pressesNeeded = pressesNeededFor(graph.settings.current().stopMethod)

        stopCounter = PressCounter(
            scope = serviceScope,
            required = pressesNeeded,
            onPress = {
                _stopPresses.value = stopCounter.presses.value
                repostRingingNotification()
            },
            onFire = {
                _stopPresses.value = 0
                val ringing = graph.local.ringing()
                val now = System.currentTimeMillis()
                ringing.forEach { graph.store.stop(it.key, now) }
                graph.local.removeRinging(ringing.map { it.key })
                graph.scheduler.reschedule("stop-from-counter")
                // render() re-reads the now-empty local store, publishes empty entries to
                // RingingState (so RingingActivity's LaunchedEffect fires finish()), and
                // tears down the player.  Without this, repostRingingNotification() only
                // updated the shade and the fullscreen activity stayed on screen.
                render()
                stopSelf()
            },
            onReset = {
                _stopPresses.value = 0
                repostRingingNotification()
            },
        )

        deleteCounter = PressCounter(
            scope = serviceScope,
            required = pressesNeeded,
            onPress = {
                _deletePresses.value = deleteCounter.presses.value
                repostRingingNotification()
            },
            onFire = {
                _deletePresses.value = 0
                val ringing = graph.local.ringing()
                val ids = ringing.map { it.key.eventId }.toSet()
                ids.forEach { id ->
                    runCatching { graph.store.delete(id) }
                        .onFailure { e -> graph.log.log("delete failed in ringing: $e") }
                }
                graph.local.removeRinging(ringing.map { it.key })
                graph.scheduler.reschedule("delete-from-ringing")
                // See Stop onFire — render() publishes empty to RingingState so the activity
                // can finish; without it the activity stayed on screen after the 3rd tap.
                render()
                stopSelf()
            },
            onReset = {
                _deletePresses.value = 0
                repostRingingNotification()
            },
        )
    }

    override fun onDestroy() {
        isRunning = false
        _stopPresses.value = 0
        _deletePresses.value = 0
        serviceScope.cancel()
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
                graph.notifications.ringing(RingingState.entries.value, AlarmSettings(), _stopPresses.value, _deletePresses.value),
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
        val text = intent?.getStringExtra(EXTRA_TEXT)
        executor.execute { handle(action, keys, text) }
        return START_STICKY
    }

    private fun handle(action: String, keys: List<InstanceKey>?, text: String? = null) {
        try {
            when (action) {
                ACTION_FIRE -> fire()
                ACTION_RENAME -> rename(keys, text.orEmpty())
                ACTION_SNOOZE, ACTION_TOMORROW -> act(action, keys)
                ACTION_STOP -> if (keys == null) { stopCounter.press(); return } else act(action, keys)
                ACTION_DELETE -> { deleteCounter.press(); return }
                ACTION_AUTO_SNOOZE -> autoSnooze()
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

    /**
     * Routes each ringing entry through [AlarmStore.autoSnooze], which enforces the 3-cycle cap
     * (Decision #1 binding) and emits the structured log line. Bypassing this and calling
     * store.snooze() would silently disable the cap.
     */
    private fun autoSnooze() {
        val now = graph.clock.millis()
        val ringing = graph.local.ringing()
        for (entry in ringing) {
            runCatching { graph.store.autoSnooze(entry.key, now) }
                .onFailure { graph.log.log("auto-snooze ${entry.key} failed: $it") }
        }
        graph.local.removeRinging(ringing.map { it.key })
        graph.scheduler.reschedule("auto-snooze")
    }

    /** Keeps ringing; only the message changes, in the calendar and on the screen/notification. */
    private fun rename(keys: List<InstanceKey>?, text: String) {
        val key = keys?.singleOrNull() ?: return
        val title = graph.store.rename(key, text) ?: return
        graph.local.addRinging(graph.local.ringing().filter { it.key == key }.map { it.copy(title = title) })
        graph.scheduler.reschedule("rename")
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

    private fun repostRingingNotification() {
        val entries = graph.local.ringing()
        val settings = graph.settings.current()
        graph.notifications.manager.notify(
            Notifications.ID_RINGING,
            graph.notifications.ringing(entries, settings, _stopPresses.value, _deletePresses.value),
        )
    }

    /**
     * Publishes the state and starts/stops sound on the main thread.
     *
     * preRingVolume handling (Carryover #3, I1 fix):
     * - Fresh start: read current alarm volume here (executor thread), persist to DataStore, then
     *   pass savedRestoreVolume=null so AlarmPlayer raises the volume itself on the main thread.
     * - Crash restart: settings.preRingVolume is non-null; pass it as savedRestoreVolume so the
     *   player skips raising (already at max) and knows what to restore on stop().
     */
    private fun render() {
        val ringing = graph.local.ringing()
        val settings = graph.settings.current()

        // Compute savedRestoreVolume on the executor thread before posting to main.
        val savedRestoreVolume: Int? = when {
            ringing.isNotEmpty() && player == null && settings.preRingVolume != null -> {
                // Crash restart: use persisted value, clear DataStore after player starts.
                settings.preRingVolume
            }
            ringing.isNotEmpty() && player == null && settings.increaseDeviceVolume -> {
                // Fresh start: read current volume and persist it now (before main thread raises it).
                runCatching {
                    val audio = getSystemService(AudioManager::class.java)
                    val vol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                    graph.settings.updateBlocking { it.copy(preRingVolume = vol) }
                    // Return null: AlarmPlayer will raise and track restoreVolume itself (savedRestoreVolume=null path).
                    null
                }.getOrNull()
                // getOrNull() returns null on exception → AlarmPlayer falls through to its own raise path.
                null
            }
            else -> null
        }

        val sound = ringing.firstOrNull()?.let { soundFor(it, settings) }
        RingingState.publish(ringing)
        main.post {
            if (ringing.isEmpty()) finishRinging()
            else showRinging(ringing, settings, sound, savedRestoreVolume)
        }
    }

    private fun soundFor(entry: RingingEntry, settings: AlarmSettings): Uri? {
        if (!getSystemService(UserManager::class.java).isUserUnlocked) return null // media not readable yet: bundled sound
        val chosen = graph.local.soundFor(entry.alarmId) ?: settings.defaultSoundUri
        return chosen?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }

    /**
     * [savedRestoreVolume] null  → fresh start: AlarmPlayer reads current and raises (increaseDeviceVolume path).
     * [savedRestoreVolume] non-null → crash restart: pass through to player so it skips raising.
     */
    private fun showRinging(ringing: List<RingingEntry>, settings: AlarmSettings, sound: Uri?, savedRestoreVolume: Int?) {
        acquireWakeLock()
        graph.notifications.manager.notify(
            Notifications.ID_RINGING,
            graph.notifications.ringing(ringing, settings, _stopPresses.value, _deletePresses.value),
        )
        if (player == null) {
            player = AlarmPlayer(this).also { it.start(sound, settings, savedRestoreVolume) }
            // Explicit activity launch on first ring. fullScreenIntent in the notification only
            // auto-invokes when the device is LOCKED. When unlocked (home screen, in-app, or on
            // Mustafa Alarm itself), Android deliberately falls back to a heads-up notification —
            // the user has to tap it to open the ringing screen. For an alarm we want full-screen
            // regardless of device state, so launch RingingActivity directly here. Android blocks this
            // launch silently (no exception) unless the app may draw over other apps.
            graph.log.log("ringing: open screen (overOtherApps=${Settings.canDrawOverlays(this)})")
            runCatching {
                startActivity(
                    Intent(this, RingingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                )
            }.onFailure { graph.log.log("ringing: startActivity failed: $it") }
            // Crash restart: DataStore preRingVolume already consumed; clear it so next fresh start persists correctly.
            if (savedRestoreVolume != null) {
                executor.execute {
                    runCatching { graph.settings.updateBlocking { it.copy(preRingVolume = null) } }
                }
            }
        }
        val keys = ringing.mapTo(HashSet()) { it.key }
        if (!playingKeys.containsAll(keys)) { // a new alarm joined: restart the no-answer timer
            main.removeCallbacks(autoSnoozeRunnable)
            main.postDelayed(autoSnoozeRunnable, settings.autoSnoozeMinutes * 60_000L)
        }
        playingKeys = keys
    }

    private fun finishRinging() {
        main.removeCallbacks(autoSnoozeRunnable)
        player?.stop()
        player = null
        playingKeys = emptySet()
        // player.stop() already restored the alarm stream volume; clear the DataStore sentinel.
        executor.execute {
            runCatching { graph.settings.updateBlocking { it.copy(preRingVolume = null) } }
        }
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
        const val ACTION_DELETE = "com.atatuzun.mustafaalarm.DELETE"
        const val ACTION_AUTO_SNOOZE = "com.atatuzun.mustafaalarm.AUTO_SNOOZE"
        const val ACTION_RENAME = "com.atatuzun.mustafaalarm.RENAME"
        private const val ACTION_RESUME = "com.atatuzun.mustafaalarm.RESUME"
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_TEXT = "text"

        @Volatile
        var isRunning = false
            private set

        private val _stopPresses = MutableStateFlow(0)
        val stopPresses: StateFlow<Int> = _stopPresses.asStateFlow()
        private val _deletePresses = MutableStateFlow(0)
        val deletePresses: StateFlow<Int> = _deletePresses.asStateFlow()

        fun fire(context: Context) {
            context.startForegroundService(Intent(context, RingingService::class.java).setAction(ACTION_FIRE))
        }

        /** [keys] null = every ringing alarm. */
        fun command(context: Context, action: String, keys: List<InstanceKey>? = null, text: String? = null) {
            val intent = Intent(context, RingingService::class.java).setAction(action)
            keys?.let { list -> intent.putStringArrayListExtra(EXTRA_KEYS, ArrayList(list.map { "${it.eventId}:${it.begin}" })) }
            text?.let { intent.putExtra(EXTRA_TEXT, it) }
            if (isRunning) context.startService(intent) else context.startForegroundService(intent)
        }

        private fun parseKey(text: String): InstanceKey = text.split(':').let { InstanceKey(it[0].toLong(), it[1].toLong()) }

        fun pressesNeededFor(method: StopMethod): Int = if (method == StopMethod.THREE_PRESSES) 3 else 1
    }
}
