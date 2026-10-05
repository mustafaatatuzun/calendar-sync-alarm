package com.atatuzun.mustafaalarm.ring

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.atatuzun.mustafaalarm.domain.AlarmContact
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.ui.theme.MustafaAlarmTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
            var contacts by remember { mutableStateOf(emptyMap<InstanceKey, AlarmContact>()) }
            var seen by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { settings = withContext(Dispatchers.IO) { graph.settings.current() } }
            LaunchedEffect(entries) { if (entries.isNotEmpty()) seen = true else if (seen) finish() }
            LaunchedEffect(entries) {
                contacts = withContext(Dispatchers.IO) {
                    entries.mapNotNull { e -> runCatching { graph.store.contactFor(e.key) }.getOrNull()?.let { e.key to it } }.toMap()
                }
            }
            LaunchedEffect(Unit) { delay(5_000); if (!seen) finish() }
            MustafaAlarmTheme(dark = true) {
                RingingScreen(
                    entries = entries,
                    settings = settings,
                    onAll = { action -> RingingService.command(this@RingingActivity, action) },
                    onOne = { action, key -> RingingService.command(this@RingingActivity, action, listOf(key)) },
                    contacts = contacts,
                    onRename = { key, text -> RingingService.command(this@RingingActivity, RingingService.ACTION_RENAME, listOf(key), text) },
                    onCall = ::dial,
                )
            }
        }
        // Hide system bars AFTER setContent so the decor view is in place (Finding 2b).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Opens the dialer with the number filled in; the alarm keeps ringing. The dialer can't show over the lock screen, so unlock first. */
    private fun dial(contact: AlarmContact) {
        val open = {
            runCatching {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { applicationContext.graph.log.log("ringing: cannot open dialer: $it") }
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        val log = applicationContext.graph.log
        if (!keyguard.isKeyguardLocked) {
            open()
            return
        }
        log.log("ringing: call ${contact.name} — asking to unlock first")
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { open() }
            override fun onDismissCancelled() { log.log("ringing: unlock cancelled, not calling") }
            override fun onDismissError() { log.log("ringing: unlock failed, not calling") }
        })
    }
}
