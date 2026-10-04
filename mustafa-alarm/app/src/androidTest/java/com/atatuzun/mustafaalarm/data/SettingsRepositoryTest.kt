package com.atatuzun.mustafaalarm.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.data.settings.SettingsRepository
import com.atatuzun.mustafaalarm.data.settings.StopMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    @Test
    fun defaults_update_andClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "settings-test-${System.nanoTime()}"
        val repo = SettingsRepository(context, name)
        try {
            assertEquals(AlarmSettings(), repo.current())
            repo.updateBlocking { it.copy(snoozeMinutes = 10, stopMethod = StopMethod.ONE_PRESS, calendarId = 42, accountEmail = "a@b.c") }
            assertEquals(
                AlarmSettings(snoozeMinutes = 10, stopMethod = StopMethod.ONE_PRESS, calendarId = 42, accountEmail = "a@b.c"),
                repo.current(),
            )
            repo.updateBlocking { it.copy(calendarId = null) }
            assertNull(repo.current().calendarId)
        } finally {
            File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$name.preferences_pb").delete()
        }
    }

    @Test
    fun preRingVolume_persistAndClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "settings-prv-${System.nanoTime()}"
        val repo = SettingsRepository(context, name)
        try {
            assertNull(repo.current().preRingVolume)
            repo.updateBlocking { it.copy(preRingVolume = 7) }
            assertEquals(7, repo.current().preRingVolume)
            repo.updateBlocking { it.copy(preRingVolume = null) }
            assertNull(repo.current().preRingVolume)
        } finally {
            File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$name.preferences_pb").delete()
        }
    }

    @Test
    fun deviceProtectedStorage_pathPrefix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dpsPath = context.createDeviceProtectedStorageContext().filesDir.absolutePath
        assertTrue(
            "Settings file must live under /data/user_de — got $dpsPath",
            dpsPath.startsWith("/data/user_de/0/com.atatuzun.mustafaalarm"),
        )
        // Verify the DataStore file actually lands at the DE path after a write.
        val name = "settings-de-path-${System.nanoTime()}"
        val repo = SettingsRepository(context, name)
        try {
            repo.updateBlocking { it.copy(snoozeMinutes = 5) }
            val deFile = File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$name.preferences_pb")
            assertTrue("DataStore file must exist at DE path: $deFile", deFile.exists())
        } finally {
            File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$name.preferences_pb").delete()
        }
    }

    @Test
    fun settings_survive_restart_from_DE() {
        // Write a setting, cancel the DataStore scope (simulates process death), then open a
        // fresh instance on the same DE file and verify the value persists across the restart.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "settings-restart-${System.nanoTime()}"
        val job1 = Job()
        val repo1 = SettingsRepository(context, name, CoroutineScope(Dispatchers.IO + job1))
        repo1.updateBlocking { it.copy(preRingVolume = 7) }
        runBlocking { job1.cancelAndJoin() }  // close the DataStore; equivalent to process death
        // Fresh instance on the same DE file — simulates a new process reading from DE storage.
        val repo2 = SettingsRepository(context, name)
        try {
            assertEquals(
                "preRingVolume must survive across a simulated process restart from DE storage",
                7 as Int?,
                repo2.current().preRingVolume,
            )
        } finally {
            File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$name.preferences_pb").delete()
        }
    }
}
