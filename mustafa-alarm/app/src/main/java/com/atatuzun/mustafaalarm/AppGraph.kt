package com.atatuzun.mustafaalarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import com.atatuzun.mustafaalarm.data.calendar.ProviderCalendarAccess
import com.atatuzun.mustafaalarm.data.local.AlarmDatabase
import com.atatuzun.mustafaalarm.data.local.RoomLocalStore
import com.atatuzun.mustafaalarm.data.settings.SettingsRepository
import com.atatuzun.mustafaalarm.domain.AlarmStore
import com.atatuzun.mustafaalarm.domain.LocalStore
import com.atatuzun.mustafaalarm.google.CalendarRestClient
import com.atatuzun.mustafaalarm.google.GoogleSetup
import com.atatuzun.mustafaalarm.log.EventLog
import com.atatuzun.mustafaalarm.ring.Notifications
import com.atatuzun.mustafaalarm.schedule.Scheduler
import com.atatuzun.mustafaalarm.system.ReliabilityChecks
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manual dependency graph, one per process.
 * Everything here is safe before first unlock (device-protected storage).
 *
 * Carryover #6 (binding): all AlarmStore calls that include autoSnooze MUST be
 * dispatched on [background], which is a single-thread executor. This serialises
 * incrementAutoSnooze and prevents the non-atomic read-modify-write from racing.
 */
class AppGraph(val context: Context) {
    /**
     * Carryover #8: DeviceClock fetches ZoneId.systemDefault() on every call, so prod
     * behaviour always matches the device's live zone (same as Clock.systemDefaultZone()
     * but also reflects zone changes at runtime).
     */
    val clock: Clock = DeviceClock()
    val log = EventLog(context)
    val settings = SettingsRepository(context)

    /**
     * Single-thread background executor. Every blocking call from receivers, workers and
     * the scheduler goes here. This is the dispatcher-constraint required by Carryover #6.
     */
    val background: ExecutorService = Executors.newSingleThreadExecutor()

    /** Emits after every reschedule so screens reload without polling. */
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val local: LocalStore by lazy { RoomLocalStore(AlarmDatabase.open(context).dao()) }
    val calendar: ProviderCalendarAccess by lazy { ProviderCalendarAccess(context.contentResolver) }
    val store: AlarmStore by lazy {
        AlarmStore(
            calendar = calendar,
            local = local,
            clock = clock,
            calendarId = { settings.current().calendarId },
            snoozeMinutes = { settings.current().snoozeMinutes },
            calendarAvailable = { calendarAvailable() },
            log = { log.log(it) },
        )
    }
    val notifications: Notifications by lazy { Notifications(context) }
    val scheduler: Scheduler by lazy {
        Scheduler(context, store, local, settings, notifications, log) { changes.tryEmit(Unit) }
    }
    val checks: ReliabilityChecks by lazy { ReliabilityChecks(context, calendar, settings) }
    val google: GoogleSetup by lazy { GoogleSetup(calendar, CalendarRestClient(), settings, log) }

    /**
     * The calendar provider is readable: user unlocked since boot AND calendar permissions
     * are granted. Returns false before first unlock so receivers avoid provider calls.
     */
    fun calendarAvailable(): Boolean =
        context.getSystemService(UserManager::class.java).isUserUnlocked &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
}

/**
 * A Clock whose zone follows the device's live setting.
 * Clock.systemDefaultZone() freezes the zone at creation; this one re-queries on each call,
 * so time-zone changes (TIMEZONE_CHANGED broadcast) are immediately visible.
 */
class DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}

/** Convenience accessor: `context.graph` from any receiver, service or worker. */
val Context.graph: AppGraph get() = (applicationContext as MustafaAlarmApp).graph
