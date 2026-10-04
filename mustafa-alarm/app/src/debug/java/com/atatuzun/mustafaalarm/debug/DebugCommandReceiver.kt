package com.atatuzun.mustafaalarm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmInput
import com.atatuzun.mustafaalarm.ring.RingingService
import com.atatuzun.mustafaalarm.domain.AlarmListState
import com.atatuzun.mustafaalarm.domain.MINUTE
import com.atatuzun.mustafaalarm.domain.Times
import com.atatuzun.mustafaalarm.domain.RecurrenceRule
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.log.EventLog
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * adb-only test hooks (manifest requires android.permission.DUMP, held only by the shell).
 *
 * Exposed actions: USE_LOCAL_CALENDAR, CREATE, LIST, DELETE_ALL, SETTINGS, RESCHEDULE.
 *
 * Carryover #9 (binding): CREATE passes recordHistory=false so quick/debug alarm creation
 * does NOT call local.recordCreation(...). History recording is reserved for the add-screen
 * path and morning/frequent presets (Tasks 14–15).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val pending = goAsync()
        graph.background.execute {
            try {
                handle(context, graph, intent)
            } catch (t: Throwable) {
                graph.log.log("debug ${intent.action} failed: $t")
            } finally {
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, graph: AppGraph, intent: Intent) {
        val command = intent.action?.substringAfterLast('.') ?: return
        val zone = graph.clock.zone
        when (command) {
            "USE_LOCAL_CALENDAR" -> {
                val resolver = context.contentResolver
                val id = LocalCalendars.findByName(resolver, "Alarms")
                    ?: LocalCalendars.create(resolver, "Alarms")
                graph.settings.updateBlocking { it.copy(calendarId = id) }
                graph.log.log("debug: using local calendar $id")
            }
            "CREATE" -> {
                // Carryover #9: recordHistory=false — debug creates must not pollute creation history.
                val message = intent.getStringExtra("msg").orEmpty().replace('_', ' ')
                val dayCodeMap = mapOf(
                    "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
                    "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY,
                    "SU" to DayOfWeek.SUNDAY,
                )
                val days = intent.getStringExtra("days")
                    ?.split(',')?.mapNotNull { dayCodeMap[it.uppercase().trim()] }?.toSet()
                    .orEmpty()
                val inMinutes = intent.getIntExtra("inMinutes", -1)
                val input = if (inMinutes > 0) {
                    val at = Times.floorMinute(graph.clock.millis()) + inMinutes * MINUTE
                    AlarmInput(Times.localTime(at, zone), Times.localDate(at, zone), RecurrenceRule.Once, message, null)
                } else {
                    AlarmInput(
                        LocalTime.parse(intent.getStringExtra("time") ?: "08:00"),
                        intent.getStringExtra("date")?.let(LocalDate::parse),
                        if (days.isEmpty()) RecurrenceRule.Once else RecurrenceRule.Weekly(days), message, null,
                    )
                }
                graph.log.log("debug: create -> ${graph.store.create(input, recordHistory = false)}")
            }
            "LIST" -> when (val state = graph.store.list()) {
                is AlarmListState.Ready -> state.sections.flatMap { it.items }.forEach {
                    graph.log.log(
                        "debug: item id=${it.eventId} '${it.title}' at ${EventLog.time(it.shownAt)}" +
                            " on=${it.on} kind=${it.kind}",
                    )
                }
                else -> graph.log.log("debug: list -> $state")
            }
            "DELETE_ALL" -> graph.settings.current().calendarId?.let { cal ->
                graph.calendar.events(cal)
                    .filter { it.originalId == null }
                    .forEach { graph.calendar.deleteEvent(it.id) }
            }
            "SETTINGS" -> graph.settings.updateBlocking { s ->
                s.copy(
                    increaseDeviceVolume = if (intent.hasExtra("increaseDeviceVolume"))
                        intent.getBooleanExtra("increaseDeviceVolume", true) else s.increaseDeviceVolume,
                    volumePercent = intent.getIntExtra("volumePercent", s.volumePercent),
                    snoozeMinutes = intent.getIntExtra("snoozeMinutes", s.snoozeMinutes),
                    autoSnoozeMinutes = intent.getIntExtra("autoSnoozeMinutes", s.autoSnoozeMinutes),
                    fadeIn = if (intent.hasExtra("fadeIn"))
                        intent.getBooleanExtra("fadeIn", true) else s.fadeIn,
                )
            }
            "RESCHEDULE" -> Unit // falls through to reschedule below
            "RING_ACTION" -> RingingService.command(context, "com.atatuzun.mustafaalarm." + intent.getStringExtra("action").orEmpty())
        }
        graph.scheduler.reschedule("debug-$command")
    }
}
