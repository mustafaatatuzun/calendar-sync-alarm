package com.atatuzun.mustafaalarm.ring

import com.atatuzun.mustafaalarm.domain.RingingEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What is ringing right now, published by RingingService for RingingActivity. */
object RingingState {
    private val mutable = MutableStateFlow<List<RingingEntry>>(emptyList())
    val entries: StateFlow<List<RingingEntry>> = mutable

    fun publish(entries: List<RingingEntry>) {
        mutable.value = entries
    }
}
