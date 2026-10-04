package com.atatuzun.mustafaalarm.ui.quick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmInput
import com.atatuzun.mustafaalarm.domain.FrequentAlarms
import com.atatuzun.mustafaalarm.domain.QuickPresets
import com.atatuzun.mustafaalarm.domain.RecurrenceRule
import com.atatuzun.mustafaalarm.domain.SaveResult
import com.atatuzun.mustafaalarm.domain.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.ZoneId

data class QuickResult(val eventId: Long, val at: Long)

data class QuickUi(
    val frequent: List<LocalTime> = emptyList(),
    val result: QuickResult? = null,
    val use24h: Boolean = true,
    val error: String? = null,
)

class QuickAlarmsViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(QuickUi())
    val ui: StateFlow<QuickUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                FrequentAlarms.top(graph.local.creationHistory(now - FrequentAlarms.WINDOW), now) to
                    graph.settings.current().use24Hour
            }
            mutable.update { it.copy(frequent = loaded.first, use24h = loaded.second) }
        }
    }

    /** Relative presets (5m … 24h) do not count toward frequent alarms. */
    fun createIn(minutes: Int) =
        create(QuickPresets.relativeTarget(minutes, System.currentTimeMillis()), recordHistory = false)

    fun createAt(time: LocalTime) =
        create(Times.nextAt(time, System.currentTimeMillis(), ZoneId.systemDefault()), recordHistory = true)

    private fun create(at: Long, recordHistory: Boolean) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val input = AlarmInput(
                        Times.localTime(at, zone),
                        Times.localDate(at, zone),
                        RecurrenceRule.Once,
                        "",
                        null,
                    )
                    graph.store.create(input, recordHistory).also {
                        if (it is SaveResult.Saved) graph.scheduler.reschedule("quick")
                    }
                }.getOrElse { graph.log.log("quick alarm failed: $it"); null }
            }
            if (saved is SaveResult.Saved) {
                mutable.update { it.copy(result = QuickResult(saved.eventId, saved.start), error = null) }
            } else {
                mutable.update { it.copy(error = "Could not set the alarm") }
            }
        }
    }

    fun undo() {
        val result = mutable.value.result ?: return
        mutable.update { it.copy(result = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { graph.store.delete(result.eventId) }
                .onFailure { graph.log.log("undo failed: $it") }
            graph.scheduler.reschedule("quick-undo")
        }
    }

    fun dismissResult() = mutable.update { it.copy(result = null) }

    fun refreshFrequent() {
        viewModelScope.launch {
            val frequent = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                FrequentAlarms.top(graph.local.creationHistory(now - FrequentAlarms.WINDOW), now)
            }
            mutable.update { it.copy(frequent = frequent) }
        }
    }
}
