package com.atatuzun.mustafaalarm.data.settings

enum class StopMethod { ONE_PRESS, THREE_PRESSES }

enum class SoundMode { SOUND_AND_VIBRATION, SOUND_ONLY, VIBRATION_ONLY }

/** Spec §9.5 defaults. calendarId/accountEmail are filled by first-run setup. */
data class AlarmSettings(
    val snoozeMinutes: Int = 30,
    val autoSnoozeMinutes: Int = 1,
    val showSnoozeButton: Boolean = true,
    val stopMethod: StopMethod = StopMethod.THREE_PRESSES,
    val soundMode: SoundMode = SoundMode.SOUND_AND_VIBRATION,
    val defaultSoundUri: String? = null,
    val volumePercent: Int = 100,
    val increaseDeviceVolume: Boolean = true,
    val fadeIn: Boolean = true,
    val use24Hour: Boolean = true,
    val darkTheme: Boolean = true,
    val nextAlarmNotification: Boolean = true,
    val accountEmail: String? = null,
    val calendarId: Long? = null,
    val calendarSyncId: String? = null,
    val lastCalendarChange: Long? = null,
    /** Alarm stream volume saved before ringing starts; restored on crash-restart (spec §5.4). */
    val preRingVolume: Int? = null,
)
