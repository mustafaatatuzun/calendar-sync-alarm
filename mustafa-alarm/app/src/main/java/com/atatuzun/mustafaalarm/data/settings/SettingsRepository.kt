package com.atatuzun.mustafaalarm.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/** Settings in device-protected storage (readable before first unlock). One instance per process (AppGraph). */
class SettingsRepository(
    context: Context,
    fileName: String = "settings",
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = {
            val deContext = context.createDeviceProtectedStorageContext()
            File(deContext.filesDir, "datastore").mkdirs()
            File(deContext.filesDir, "datastore/$fileName.preferences_pb")
        },
    )

    val flow: Flow<AlarmSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    /** Blocking read for receivers, services and workers. Never call on the main thread. */
    fun current(): AlarmSettings = runBlocking { flow.first() }

    suspend fun update(transform: (AlarmSettings) -> AlarmSettings) {
        store.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }

    fun updateBlocking(transform: (AlarmSettings) -> AlarmSettings) = runBlocking { update(transform) }
}

private object Keys {
    val snooze = intPreferencesKey("snoozeMinutes")
    val autoSnooze = intPreferencesKey("autoSnoozeMinutes")
    val showSnooze = booleanPreferencesKey("showSnoozeButton")
    val stopMethod = stringPreferencesKey("stopMethod")
    val soundMode = stringPreferencesKey("soundMode")
    val defaultSound = stringPreferencesKey("defaultSoundUri")
    val volume = intPreferencesKey("volumePercent")
    val increaseVolume = booleanPreferencesKey("increaseDeviceVolume")
    val fadeIn = booleanPreferencesKey("fadeIn")
    val use24Hour = booleanPreferencesKey("use24Hour")
    val darkTheme = booleanPreferencesKey("darkTheme")
    val nextNotification = booleanPreferencesKey("nextAlarmNotification")
    val accountEmail = stringPreferencesKey("accountEmail")
    val calendarId = longPreferencesKey("calendarId")
    val calendarSyncId = stringPreferencesKey("calendarSyncId")
    val lastCalendarChange = longPreferencesKey("lastCalendarChange")
    val preRingVolume = intPreferencesKey("preRingVolume")
}

private fun Preferences.toSettings(): AlarmSettings {
    val d = AlarmSettings()
    return AlarmSettings(
        snoozeMinutes = this[Keys.snooze] ?: d.snoozeMinutes,
        autoSnoozeMinutes = this[Keys.autoSnooze] ?: d.autoSnoozeMinutes,
        showSnoozeButton = this[Keys.showSnooze] ?: d.showSnoozeButton,
        stopMethod = this[Keys.stopMethod]?.let { runCatching { StopMethod.valueOf(it) }.getOrNull() } ?: d.stopMethod,
        soundMode = this[Keys.soundMode]?.let { runCatching { SoundMode.valueOf(it) }.getOrNull() } ?: d.soundMode,
        defaultSoundUri = this[Keys.defaultSound],
        volumePercent = this[Keys.volume] ?: d.volumePercent,
        increaseDeviceVolume = this[Keys.increaseVolume] ?: d.increaseDeviceVolume,
        fadeIn = this[Keys.fadeIn] ?: d.fadeIn,
        use24Hour = this[Keys.use24Hour] ?: d.use24Hour,
        darkTheme = this[Keys.darkTheme] ?: d.darkTheme,
        nextAlarmNotification = this[Keys.nextNotification] ?: d.nextAlarmNotification,
        accountEmail = this[Keys.accountEmail],
        calendarId = this[Keys.calendarId],
        calendarSyncId = this[Keys.calendarSyncId],
        lastCalendarChange = this[Keys.lastCalendarChange],
        preRingVolume = this[Keys.preRingVolume],
    )
}

private fun AlarmSettings.writeTo(p: MutablePreferences) {
    p[Keys.snooze] = snoozeMinutes
    p[Keys.autoSnooze] = autoSnoozeMinutes
    p[Keys.showSnooze] = showSnoozeButton
    p[Keys.stopMethod] = stopMethod.name
    p[Keys.soundMode] = soundMode.name
    p.putOrRemove(Keys.defaultSound, defaultSoundUri)
    p[Keys.volume] = volumePercent
    p[Keys.increaseVolume] = increaseDeviceVolume
    p[Keys.fadeIn] = fadeIn
    p[Keys.use24Hour] = use24Hour
    p[Keys.darkTheme] = darkTheme
    p[Keys.nextNotification] = nextAlarmNotification
    p.putOrRemove(Keys.accountEmail, accountEmail)
    p.putOrRemove(Keys.calendarId, calendarId)
    p.putOrRemove(Keys.calendarSyncId, calendarSyncId)
    p.putOrRemove(Keys.lastCalendarChange, lastCalendarChange)
    p.putOrRemove(Keys.preRingVolume, preRingVolume)
}

private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
    if (value == null) remove(key) else this[key] = value
}
