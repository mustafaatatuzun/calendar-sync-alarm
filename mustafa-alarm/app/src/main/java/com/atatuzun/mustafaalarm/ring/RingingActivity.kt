package com.atatuzun.mustafaalarm.ring

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.domain.AlarmPeople
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.system.ContactActions
import com.atatuzun.mustafaalarm.ui.theme.MustafaAlarmTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun isVolumeKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

/** Full-screen ringing UI over the lock screen (spec §9.4). Works before first unlock. */
class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val graph = applicationContext.graph
        setContent {
            val entries by RingingState.entries.collectAsStateWithLifecycle()
            var settings by remember { mutableStateOf(AlarmSettings()) }
            var people by remember { mutableStateOf(emptyMap<InstanceKey, AlarmPeople>()) }
            var seen by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { settings = withContext(Dispatchers.IO) { graph.settings.current() } }
            LaunchedEffect(entries) { if (entries.isNotEmpty()) seen = true else if (seen) finish() }
            LaunchedEffect(entries) {
                people = withContext(Dispatchers.IO) {
                    entries.mapNotNull { e -> runCatching { graph.store.peopleFor(e.key) }.getOrNull()?.let { e.key to it } }.toMap()
                }
            }
            LaunchedEffect(Unit) { delay(5_000); if (!seen) finish() }
            MustafaAlarmTheme(dark = true) {
                RingingScreen(
                    entries = entries,
                    settings = settings,
                    onAll = { action -> RingingService.command(this@RingingActivity, action) },
                    onOne = { action, key -> RingingService.command(this@RingingActivity, action, listOf(key)) },
                    people = people,
                    onRename = { key, text -> RingingService.command(this@RingingActivity, RingingService.ACTION_RENAME, listOf(key), text) },
                    onCall = { launchUnlocked(ContactActions.dial(it)) },
                    onWhatsApp = { launchUnlocked(ContactActions.whatsApp(this@RingingActivity, it)) },
                )
            }
        }
        // Hide system bars AFTER setContent so the decor view is in place (Finding 2b).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStart() {
        super.onStart()
        RingingService.screenShown(this, true)
    }

    /** Volume up/down mutes the ringing (sound + vibration) instead of changing the volume. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) mute()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        isVolumeKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun mute() = RingingService.command(this, RingingService.ACTION_MUTE)

    override fun onStop() {
        RingingService.screenShown(this, false)
        super.onStop()
    }

    /** Opens the dialer / WhatsApp; the alarm keeps ringing. Neither can show over the lock screen, so unlock first. */
    private fun launchUnlocked(intent: Intent?) {
        val log = applicationContext.graph.log
        val open = { ContactActions.start(this, intent, log::log) }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (intent == null || !keyguard.isKeyguardLocked) {
            open()
            return
        }
        log.log("ringing: ${intent.data?.scheme} — asking to unlock first")
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { open() }
            override fun onDismissCancelled() { log.log("ringing: unlock cancelled, not opening") }
            override fun onDismissError() { log.log("ringing: unlock failed, not opening") }
        })
    }
}
