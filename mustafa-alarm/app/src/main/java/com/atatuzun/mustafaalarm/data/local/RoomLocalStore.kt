package com.atatuzun.mustafaalarm.data.local

import com.atatuzun.mustafaalarm.domain.CachedOccurrence
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.LocalStore
import com.atatuzun.mustafaalarm.domain.PendingAction
import com.atatuzun.mustafaalarm.domain.RingAction
import com.atatuzun.mustafaalarm.domain.RingingEntry

class RoomLocalStore(private val dao: LocalDao) : LocalStore {
    override fun handledKeys(): Set<InstanceKey> = dao.handled().mapTo(HashSet()) { InstanceKey(it.eventId, it.originalBeginMillis) }
    override fun markHandled(key: InstanceKey, action: RingAction, at: Long) = dao.insertHandled(HandledEntity(key.eventId, key.begin, action.name, at))
    override fun pruneHandled(before: Long) = dao.pruneHandled(before)

    override fun soundFor(alarmId: Long): String? = dao.sound(alarmId)
    override fun setSound(alarmId: Long, uri: String?) {
        if (uri == null) dao.deleteExtra(alarmId) else dao.upsertExtra(AlarmExtraEntity(alarmId, uri))
    }

    override fun recordCreation(minuteOfDay: Int, at: Long) = dao.insertCreation(CreationEntity(hourMinute = minuteOfDay, createdAt = at))
    override fun creationHistory(since: Long): List<Pair<Int, Long>> = dao.creations(since).map { it.hourMinute to it.createdAt }

    override fun ringCache(): List<CachedOccurrence> =
        dao.ringCache().map { CachedOccurrence(InstanceKey(it.eventId, it.originalBeginMillis), it.alarmId, it.title) }
    override fun replaceRingCache(items: List<CachedOccurrence>) = dao.replaceRingCache(items.map { it.toEntity() })
    override fun addToRingCache(item: CachedOccurrence) = dao.insertRingCache(listOf(item.toEntity()))

    override fun ringing(): List<RingingEntry> =
        dao.ringing().map { RingingEntry(InstanceKey(it.eventId, it.originalBeginMillis), it.alarmId, it.title, it.startedAt) }
    override fun addRinging(entries: List<RingingEntry>) =
        dao.insertRinging(entries.map { RingingEntity(it.key.eventId, it.key.begin, it.alarmId, it.title, it.startedAt) })
    override fun removeRinging(keys: Collection<InstanceKey>) = keys.forEach { dao.deleteRinging(it.eventId, it.begin) }

    override fun pendingActions(): List<PendingAction> =
        dao.pending().map { PendingAction(it.id, InstanceKey(it.eventId, it.originalBeginMillis), RingAction.valueOf(it.action), it.pressedAt) }
    override fun addPending(key: InstanceKey, action: RingAction, pressedAt: Long) =
        dao.insertPending(PendingEntity(eventId = key.eventId, originalBeginMillis = key.begin, action = action.name, pressedAt = pressedAt))
    override fun removePending(id: Long) = dao.deletePending(id)

    override fun autoSnoozeCount(alarmId: Long): Int = dao.autoSnoozeCount(alarmId) ?: 0
    override fun incrementAutoSnooze(alarmId: Long) {
        val current = dao.autoSnoozeCount(alarmId) ?: 0
        dao.upsertAutoSnooze(AutoSnoozeEntity(alarmId, current + 1))
    }
    override fun clearAutoSnooze(alarmId: Long) = dao.deleteAutoSnooze(alarmId)

    private fun CachedOccurrence.toEntity() = RingCacheEntity(key.eventId, key.begin, alarmId, ringAt, title)
}
