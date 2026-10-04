package com.atatuzun.mustafaalarm.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmListState
import com.atatuzun.mustafaalarm.system.Banners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ListUi(
    val state: AlarmListState? = null,
    val banners: Banners = Banners(),
    val use24h: Boolean = true,
    val now: Long = System.currentTimeMillis(),
)

class AlarmListViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(ListUi())
    val ui: StateFlow<ListUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch { graph.changes.collect { refresh() } }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                mutable.update { it.copy(now = System.currentTimeMillis()) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val state = runCatching { graph.store.list() }
                    .getOrElse { graph.log.log("list failed: $it"); AlarmListState.Unavailable }
                Triple(state, graph.checks.banners(), graph.settings.current().use24Hour)
            }
            mutable.update {
                it.copy(
                    state = loaded.first,
                    banners = loaded.second,
                    use24h = loaded.third,
                    now = System.currentTimeMillis(),
                )
            }
        }
    }

    fun setEnabled(eventId: Long, on: Boolean) = write("toggle") { graph.store.setEnabled(eventId, on) }

    fun delete(eventId: Long) = write("delete") { graph.store.delete(eventId) }

    /** Writes, then reschedules; the reschedule emits graph.changes, which reloads the list. */
    private fun write(reason: String, block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(block).onFailure { graph.log.log("$reason failed: $it") }
            graph.scheduler.reschedule(reason)
        }
    }
}
