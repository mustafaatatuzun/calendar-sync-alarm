package com.atatuzun.mustafaalarm.domain

/** Phone-only data (spec §5.3–5.4). The Android implementation is Room in device-protected storage. */
interface LocalStore {
    fun handledKeys(): Set<InstanceKey>
    fun markHandled(key: InstanceKey, action: RingAction, at: Long)
    fun pruneHandled(before: Long)

    fun soundFor(alarmId: Long): String?
    fun setSound(alarmId: Long, uri: String?)

    fun recordCreation(minuteOfDay: Int, at: Long)
    fun creationHistory(since: Long): List<Pair<Int, Long>>

    fun ringCache(): List<CachedOccurrence>
    fun replaceRingCache(items: List<CachedOccurrence>)
    fun addToRingCache(item: CachedOccurrence)

    fun ringing(): List<RingingEntry>
    fun addRinging(entries: List<RingingEntry>)
    fun removeRinging(keys: Collection<InstanceKey>)

    fun pendingActions(): List<PendingAction>
    fun addPending(key: InstanceKey, action: RingAction, pressedAt: Long)
    fun removePending(id: Long)

    /** How many times this alarm has been auto-snoozed without a human response (spec §5.3). */
    fun autoSnoozeCount(alarmId: Long): Int
    fun incrementAutoSnooze(alarmId: Long)
    fun clearAutoSnooze(alarmId: Long)

    /** The time a one-off alarm was set for before its first snooze moved it; "Tomorrow" goes back to it. */
    fun snoozeOrigin(eventId: Long): Long?
    fun setSnoozeOrigin(eventId: Long, originalBegin: Long)
    fun clearSnoozeOrigin(eventId: Long)
}
