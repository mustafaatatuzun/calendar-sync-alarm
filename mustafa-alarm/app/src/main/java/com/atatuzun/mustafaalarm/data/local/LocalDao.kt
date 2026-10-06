package com.atatuzun.mustafaalarm.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
abstract class LocalDao {
    @Query("SELECT * FROM handled_instances") abstract fun handled(): List<HandledEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun insertHandled(entity: HandledEntity)
    @Query("DELETE FROM handled_instances WHERE originalBeginMillis < :before") abstract fun pruneHandled(before: Long)

    @Query("SELECT soundUri FROM alarm_extras WHERE eventId = :alarmId") abstract fun sound(alarmId: Long): String?
    @Upsert abstract fun upsertExtra(entity: AlarmExtraEntity)
    @Query("DELETE FROM alarm_extras WHERE eventId = :alarmId") abstract fun deleteExtra(alarmId: Long)

    @Insert abstract fun insertCreation(entity: CreationEntity)
    @Query("SELECT * FROM creation_history WHERE createdAt >= :since") abstract fun creations(since: Long): List<CreationEntity>

    @Query("SELECT * FROM ring_cache ORDER BY ringAtMillis, eventId") abstract fun ringCache(): List<RingCacheEntity>
    @Query("DELETE FROM ring_cache") abstract fun clearRingCache()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun insertRingCache(items: List<RingCacheEntity>)

    @Transaction
    open fun replaceRingCache(items: List<RingCacheEntity>) {
        clearRingCache()
        insertRingCache(items)
    }

    @Query("SELECT * FROM ringing_now ORDER BY startedAt, eventId") abstract fun ringing(): List<RingingEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun insertRinging(items: List<RingingEntity>)
    @Query("DELETE FROM ringing_now WHERE eventId = :eventId AND originalBeginMillis = :begin") abstract fun deleteRinging(eventId: Long, begin: Long)

    @Query("SELECT * FROM pending_actions ORDER BY pressedAt, id") abstract fun pending(): List<PendingEntity>
    @Insert abstract fun insertPending(entity: PendingEntity)
    @Query("DELETE FROM pending_actions WHERE id = :id") abstract fun deletePending(id: Long)

    @Query("SELECT count FROM auto_snooze_count WHERE alarmId = :alarmId") abstract fun autoSnoozeCount(alarmId: Long): Int?
    @Upsert abstract fun upsertAutoSnooze(entity: AutoSnoozeEntity)
    @Query("DELETE FROM auto_snooze_count WHERE alarmId = :alarmId") abstract fun deleteAutoSnooze(alarmId: Long)

    @Query("SELECT originalBeginMillis FROM snooze_origin WHERE eventId = :eventId") abstract fun snoozeOrigin(eventId: Long): Long?
    @Upsert abstract fun upsertSnoozeOrigin(entity: SnoozeOriginEntity)
    @Query("DELETE FROM snooze_origin WHERE eventId = :eventId") abstract fun deleteSnoozeOrigin(eventId: Long)
}
