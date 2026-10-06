package com.atatuzun.mustafaalarm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlarmExtraEntity::class,
        HandledEntity::class,
        CreationEntity::class,
        RingCacheEntity::class,
        RingingEntity::class,
        PendingEntity::class,
        AutoSnoozeEntity::class,
        SnoozeOriginEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao

    companion object {
        /** v2 adds snooze_origin (the time a one-off was set for before snoozes moved it). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `snooze_origin` (`eventId` INTEGER NOT NULL, " +
                        "`originalBeginMillis` INTEGER NOT NULL, PRIMARY KEY(`eventId`))",
                )
            }
        }

        /** Device-protected storage: readable before the first unlock after a reboot. */
        fun open(context: Context): AlarmDatabase =
            Room.databaseBuilder(context.createDeviceProtectedStorageContext(), AlarmDatabase::class.java, "mustafa-alarm.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
