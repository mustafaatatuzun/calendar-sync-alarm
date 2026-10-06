package com.atatuzun.mustafaalarm.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarm_extras")
data class AlarmExtraEntity(@PrimaryKey val eventId: Long, val soundUri: String)

@Entity(tableName = "handled_instances", primaryKeys = ["eventId", "originalBeginMillis"])
data class HandledEntity(val eventId: Long, val originalBeginMillis: Long, val action: String, val handledAt: Long)

@Entity(tableName = "creation_history")
data class CreationEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val hourMinute: Int, val createdAt: Long)

@Entity(tableName = "ring_cache", primaryKeys = ["eventId", "originalBeginMillis"])
data class RingCacheEntity(val eventId: Long, val originalBeginMillis: Long, val alarmId: Long, val ringAtMillis: Long, val title: String)

@Entity(tableName = "ringing_now", primaryKeys = ["eventId", "originalBeginMillis"])
data class RingingEntity(val eventId: Long, val originalBeginMillis: Long, val alarmId: Long, val title: String, val startedAt: Long)

@Entity(tableName = "pending_actions")
data class PendingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val originalBeginMillis: Long,
    val action: String,
    val pressedAt: Long,
)

/**
 * Counts how many times an alarm has been auto-snoozed without a human response (spec §5.3).
 * Keyed by alarmId (series root = originalId ?: eventId) per Task 6 decision.
 * NOTE: local _ID keying means a full re-sync can orphan rows; acceptable per spec §5.3 known limitation.
 */
@Entity(tableName = "auto_snooze_count")
data class AutoSnoozeEntity(@PrimaryKey val alarmId: Long, val count: Int)

@Entity(tableName = "snooze_origin")
data class SnoozeOriginEntity(@PrimaryKey val eventId: Long, val originalBeginMillis: Long)
