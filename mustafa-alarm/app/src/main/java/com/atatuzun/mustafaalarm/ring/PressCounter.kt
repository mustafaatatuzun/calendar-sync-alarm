package com.atatuzun.mustafaalarm.ring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Count presses that must happen within `resetMillis` of one another to reach
 * `required`.  When the threshold is hit, `onFire` runs on the owning scope and
 * the counter resets.  2 s inter-press reset mirrors the big Stop button's
 * timer added in Task 11 M1 (RingingScreen.kt:60-63 in the pre-refactor code).
 */
class PressCounter(
    private val scope: CoroutineScope,
    private val required: Int,
    private val resetMillis: Long = 2_000L,
    private val onPress: () -> Unit = {},
    private val onFire: () -> Unit,
    private val onReset: () -> Unit = {},
) {
    private val _presses = MutableStateFlow(0)
    val presses: StateFlow<Int> = _presses.asStateFlow()
    private var resetJob: Job? = null

    /** Returns the new press count (0 if the press fired). */
    fun press(): Int {
        resetJob?.cancel()
        val next = _presses.value + 1
        if (next >= required) {
            _presses.value = 0
            onFire()
            return 0
        }
        _presses.value = next
        onPress()
        resetJob = scope.launch {
            delay(resetMillis)
            if (_presses.value == next) {
                _presses.value = 0
                onReset()
            }
        }
        return next
    }

    fun reset() { resetJob?.cancel(); _presses.value = 0 }
}
