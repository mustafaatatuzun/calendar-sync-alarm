package com.atatuzun.mustafaalarm.ui.settings

import android.media.RingtoneManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.system.Check
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUi(
    val settings: AlarmSettings = AlarmSettings(),
    val checks: Map<Check, Boolean> = emptyMap(),
    val calendarState: String = "…",
    val pendingUploads: Int? = null,
    val defaultSoundName: String = "Default",
)

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch { graph.settings.flow.collect { s -> mutable.update { it.copy(settings = s) } } }
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val s = graph.settings.current()
                val calId = s.calendarId
                val calendarState = runCatching {
                    when {
                        calId == null -> "Not set up"
                        !graph.calendarAvailable() -> "No calendar access"
                        graph.calendar.calendarExists(calId) -> "OK"
                        else -> "Missing"
                    }
                }.getOrDefault("Unknown")
                SettingsUi(
                    settings = s,
                    checks = graph.checks.status(),
                    calendarState = calendarState,
                    pendingUploads = calId?.takeIf { graph.calendarAvailable() }?.let { graph.calendar.dirtyCount(it) },
                    defaultSoundName = soundName(s.defaultSoundUri),
                )
            }
            mutable.value = loaded
        }
    }

    /** Saves, then reschedules (the next-alarm notification and the ring cache depend on settings). */
    fun update(transform: (AlarmSettings) -> AlarmSettings) {
        viewModelScope.launch {
            graph.settings.update(transform)
            withContext(Dispatchers.IO) { graph.scheduler.reschedule("settings") }
        }
    }

    fun setDefaultSound(uri: String?) {
        update { it.copy(defaultSoundUri = uri) }
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { soundName(uri) }
            mutable.update { it.copy(defaultSoundName = name) }
        }
    }

    private fun soundName(uri: String?): String =
        if (uri == null) "Default"
        else runCatching { RingtoneManager.getRingtone(graph.context, Uri.parse(uri))?.getTitle(graph.context) }.getOrNull() ?: "Custom sound"
}
