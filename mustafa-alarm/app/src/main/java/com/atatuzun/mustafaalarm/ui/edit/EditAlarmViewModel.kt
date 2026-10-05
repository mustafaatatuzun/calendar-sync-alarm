package com.atatuzun.mustafaalarm.ui.edit

import android.media.RingtoneManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmContact
import com.atatuzun.mustafaalarm.domain.AlarmInput
import com.atatuzun.mustafaalarm.domain.AlarmKind
import com.atatuzun.mustafaalarm.domain.AlarmStore
import com.atatuzun.mustafaalarm.domain.DEFAULT_TITLE
import com.atatuzun.mustafaalarm.domain.RecurrenceRule
import com.atatuzun.mustafaalarm.domain.SaveResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** The fields that define "something has changed" per spec §9. */
data class EditSnapshot(
    val time: LocalTime,
    val recurrence: RecurrenceRule,
    val date: LocalDate?,
    val message: String,
    val soundUri: String?,
    val contact: AlarmContact? = null,
)

data class EditUi(
    val loading: Boolean = true,
    val editing: Boolean = false,
    val isNew: Boolean = true,
    val time: LocalTime = LocalTime.of(8, 0),
    val recurrence: RecurrenceRule = RecurrenceRule.Once,
    val date: LocalDate? = null,
    val message: String = "",
    val soundUri: String? = null,
    val soundName: String = "Default",
    val contact: AlarmContact? = null,
    val otherRepeat: Boolean = false,
    val use24h: Boolean = true,
    val error: String? = null,
    val closed: Boolean = false,
    /**
     * Captured once after the init coroutine resolves. Null while loading.
     * New alarms: isDirty is always true (spec §9 — first save always valid).
     * Edit alarms: isDirty if any meaningful field differs from snapshot.
     */
    val snapshot: EditSnapshot? = null,
) {
    val isDirty: Boolean
        get() {
            if (isNew) return true   // new alarm: always saveable
            val s = snapshot ?: return false  // still loading, disable until ready
            return time != s.time ||
                recurrence != s.recurrence ||
                date != s.date ||
                message != s.message ||
                soundUri != s.soundUri ||
                contact != s.contact
        }
}

/**
 * Primary constructor takes individual deps (enables JVM-only unit tests).
 * Production callers use the secondary constructor that takes AppGraph.
 */
class EditAlarmViewModel(
    private val store: AlarmStore,
    private val use24hFn: () -> Boolean,
    private val rescheduleFn: (String) -> Unit,
    private val logFn: (String) -> Unit,
    private val soundNameFn: (String?) -> String,
    private val eventId: Long?,
    /** Overridable in tests so init coroutine completes synchronously. */
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** Production constructor — pulls everything from the live AppGraph. */
    constructor(graph: AppGraph, eventId: Long?) : this(
        store = graph.store,
        use24hFn = { graph.settings.current().use24Hour },
        rescheduleFn = { reason -> graph.scheduler.reschedule(reason) },
        logFn = { msg -> graph.log.log(msg) },
        soundNameFn = { uri ->
            if (uri == null) "Default"
            else runCatching {
                RingtoneManager.getRingtone(graph.context, Uri.parse(uri))?.getTitle(graph.context)
            }.getOrNull() ?: "Custom sound"
        },
        eventId = eventId,
    )

    private val mutable = MutableStateFlow(EditUi())
    val ui: StateFlow<EditUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            mutable.value = withContext(ioDispatcher) {
                val use24h = use24hFn()
                val details = eventId?.let { runCatching { store.details(it) }.getOrNull() }
                if (details == null) {
                    val defaultTime = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)
                    EditUi(
                        loading = false,
                        isNew = true,
                        time = defaultTime,
                        use24h = use24h,
                        // snapshot not needed for new alarms (isDirty always true when isNew)
                    )
                } else {
                    val message = details.message.takeUnless { it == DEFAULT_TITLE }.orEmpty()
                    val snapshot = EditSnapshot(
                        time = details.time,
                        recurrence = details.recurrence,
                        date = details.date,
                        message = message,
                        soundUri = details.soundUri,
                        contact = details.contact,
                    )
                    EditUi(
                        loading = false,
                        editing = true,
                        isNew = false,
                        time = details.time,
                        recurrence = details.recurrence,
                        date = details.date,
                        message = message,
                        soundUri = details.soundUri,
                        soundName = soundNameFn(details.soundUri),
                        contact = details.contact,
                        otherRepeat = details.kind == AlarmKind.OTHER_REPEAT,
                        use24h = use24h,
                        snapshot = snapshot,
                    )
                }
            }
        }
    }

    fun setTime(time: LocalTime) = mutable.update { it.copy(time = time) }

    fun setRecurrence(choice: RecurrenceRule) = mutable.update { s ->
        when (choice) {
            RecurrenceRule.Once, is RecurrenceRule.Weekly, RecurrenceRule.Daily, RecurrenceRule.EveryWeekday,
            is RecurrenceRule.MonthlyDay, is RecurrenceRule.MonthlyNthWeekday ->
                s.copy(recurrence = choice, date = if (choice is RecurrenceRule.Once) s.date else null)
            RecurrenceRule.Yearly ->
                s.copy(recurrence = RecurrenceRule.Yearly, date = s.date ?: LocalDate.now())
        }
    }

    fun toggleDay(day: DayOfWeek) = mutable.update { s ->
        val current = s.recurrence as? RecurrenceRule.Weekly
            ?: return@update s  // ignore toggles unless currently Weekly
        val days = if (day in current.days) current.days - day else current.days + day
        val next: RecurrenceRule = if (days.isEmpty()) RecurrenceRule.Once else RecurrenceRule.Weekly(days)
        s.copy(recurrence = next)
    }

    fun setMonthlyDay(day: Int) = mutable.update { s ->
        if (s.recurrence !is RecurrenceRule.MonthlyDay) s
        else s.copy(recurrence = RecurrenceRule.MonthlyDay(day.coerceIn(1, 31)))
    }

    fun setMonthlyNth(nth: Int) = mutable.update { s ->
        val cur = s.recurrence as? RecurrenceRule.MonthlyNthWeekday ?: return@update s
        s.copy(recurrence = cur.copy(nth = nth))
    }

    fun setMonthlyWeekday(dow: DayOfWeek) = mutable.update { s ->
        val cur = s.recurrence as? RecurrenceRule.MonthlyNthWeekday ?: return@update s
        s.copy(recurrence = cur.copy(weekday = dow))
    }

    fun resetToOnce() = mutable.update { it.copy(recurrence = RecurrenceRule.Once, date = null) }

    fun setDate(date: LocalDate?) = mutable.update { s ->
        if (date == null) {
            s.copy(date = null)
        } else when (s.recurrence) {
            RecurrenceRule.Yearly -> s.copy(date = date)
            else -> s.copy(date = date, recurrence = RecurrenceRule.Once)
        }
    }

    fun setMessage(message: String) = mutable.update { it.copy(message = message) }

    fun setContact(contact: AlarmContact?) = mutable.update { it.copy(contact = contact) }

    fun clearError() = mutable.update { it.copy(error = null) }

    fun setSound(uri: String?) {
        viewModelScope.launch {
            val name = withContext(ioDispatcher) { soundNameFn(uri) }
            mutable.update { it.copy(soundUri = uri, soundName = name) }
        }
    }

    fun save() {
        val s = mutable.value
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    val input = AlarmInput(s.time, s.date, s.recurrence, s.message, s.soundUri, s.contact)
                    val saved = if (eventId == null) store.create(input) else store.update(eventId, input)
                    if (saved is SaveResult.Saved) rescheduleFn(if (eventId == null) "create" else "edit")
                    saved
                }
            }
            result.fold(
                onSuccess = { saved ->
                    when (saved) {
                        is SaveResult.Saved -> mutable.update { it.copy(closed = true) }
                        SaveResult.TimeInPast -> showError("That time has already passed")
                        SaveResult.NoCalendar -> showError("Finish the Google setup first")
                        SaveResult.Missing -> showError("This alarm no longer exists")
                        SaveResult.NeedsDateForYearly -> showError("Pick a date for the yearly alarm")
                    }
                },
                onFailure = { e ->
                    logFn("save failed: $e")
                    showError("Could not save: ${e.message}")
                },
            )
        }
    }

    fun delete() {
        val id = eventId ?: return
        viewModelScope.launch {
            withContext(ioDispatcher) {
                runCatching { store.delete(id) }.onFailure { logFn("delete failed: $it") }
                rescheduleFn("delete")
            }
            mutable.update { it.copy(closed = true) }
        }
    }

    private fun showError(text: String) = mutable.update { it.copy(error = text) }
}
