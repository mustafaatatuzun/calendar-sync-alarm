package com.atatuzun.mustafaalarm.log

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Append-only log of every step (spec §4 EventLog): app file in device-protected storage + logcat tag MustafaAlarm. */
class EventLog(context: Context) {
    private val file = File(context.createDeviceProtectedStorageContext().filesDir, "event-log.txt")

    @Synchronized
    fun log(message: String) {
        Log.i(TAG, message)
        runCatching {
            if (file.length() > MAX_BYTES) file.renameTo(File(file.parentFile, "event-log.1.txt"))
            file.appendText("${time(System.currentTimeMillis())} $message\n")
        }
    }

    companion object {
        const val TAG = "MustafaAlarm"
        private const val MAX_BYTES = 1_000_000L
        private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun time(millis: Long): String =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(format)
    }
}
