package com.atatuzun.mustafaalarm.domain

class InMemoryLocalStore : LocalStore {
    val handled = linkedMapOf<InstanceKey, Pair<RingAction, Long>>()
    val sounds = mutableMapOf<Long, String>()
    val history = mutableListOf<Pair<Int, Long>>()
    var cache: List<CachedOccurrence> = emptyList()
    val ringingNow = linkedMapOf<InstanceKey, RingingEntry>()
    val pending = mutableListOf<PendingAction>()
    private var nextPendingId = 1L
    val autoSnoozeCounters = mutableMapOf<Long, Int>()

    override fun handledKeys(): Set<InstanceKey> = handled.keys.toSet()
    override fun markHandled(key: InstanceKey, action: RingAction, at: Long) { handled[key] = action to at }
    override fun pruneHandled(before: Long) { handled.keys.removeAll { it.begin < before } }
    override fun soundFor(alarmId: Long): String? = sounds[alarmId]
    override fun setSound(alarmId: Long, uri: String?) { if (uri == null) sounds.remove(alarmId) else sounds[alarmId] = uri }
    override fun recordCreation(minuteOfDay: Int, at: Long) { history += minuteOfDay to at }
    override fun creationHistory(since: Long): List<Pair<Int, Long>> = history.filter { it.second >= since }
    override fun ringCache(): List<CachedOccurrence> = cache
    override fun replaceRingCache(items: List<CachedOccurrence>) { cache = items }
    override fun addToRingCache(item: CachedOccurrence) { cache = (cache.filter { it.key != item.key } + item).sortedBy { it.ringAt } }
    override fun ringing(): List<RingingEntry> = ringingNow.values.toList()
    override fun addRinging(entries: List<RingingEntry>) { entries.forEach { ringingNow[it.key] = it } }
    override fun removeRinging(keys: Collection<InstanceKey>) { keys.forEach { ringingNow.remove(it) } }
    override fun pendingActions(): List<PendingAction> = pending.sortedBy { it.pressedAt }
    override fun addPending(key: InstanceKey, action: RingAction, pressedAt: Long) { pending += PendingAction(nextPendingId++, key, action, pressedAt) }
    override fun removePending(id: Long) { pending.removeAll { it.id == id } }

    override fun autoSnoozeCount(alarmId: Long): Int = autoSnoozeCounters.getOrDefault(alarmId, 0)
    override fun incrementAutoSnooze(alarmId: Long) { autoSnoozeCounters[alarmId] = autoSnoozeCount(alarmId) + 1 }
    override fun clearAutoSnooze(alarmId: Long) { autoSnoozeCounters.remove(alarmId) }

    private val snoozeOrigins = mutableMapOf<Long, Long>()
    override fun snoozeOrigin(eventId: Long): Long? = snoozeOrigins[eventId]
    override fun setSnoozeOrigin(eventId: Long, originalBegin: Long) { snoozeOrigins[eventId] = originalBegin }
    override fun clearSnoozeOrigin(eventId: Long) { snoozeOrigins.remove(eventId) }
}
