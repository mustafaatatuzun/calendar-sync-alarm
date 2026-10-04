package com.atatuzun.mustafaalarm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlarmExtraEntity::class,
        HandledEntity::class,
        CreationEntity::class,
        RingCacheEntity::class,
        RingingEntity::class,
        PendingEntity::class,
        AutoSnoozeEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao

    companion object {
        /** Device-protected storage: readable before the first unlock after a reboot. */
        fun open(context: Context): AlarmDatabase =
            Room.databaseBuilder(context.createDeviceProtectedStorageContext(), AlarmDatabase::class.java, "mustafa-alarm.db").build()
    }
}
