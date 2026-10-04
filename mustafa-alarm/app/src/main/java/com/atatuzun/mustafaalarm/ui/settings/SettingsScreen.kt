package com.atatuzun.mustafaalarm.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.data.settings.SoundMode
import com.atatuzun.mustafaalarm.data.settings.StopMethod
import com.atatuzun.mustafaalarm.log.EventLog
import com.atatuzun.mustafaalarm.system.Check

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onSwitchAccount: (() -> Unit)? = null) {
    val vm = viewModel { SettingsViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.startActivity(graph.checks.fixIntent(Check.NOTIFICATIONS))
        vm.refresh()
    }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (!result.values.all { it }) context.startActivity(graph.checks.fixIntent(Check.CALENDAR))
        vm.refresh()
    }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            vm.setDefaultSound(uri?.takeUnless { it == Settings.System.DEFAULT_ALARM_ALERT_URI }?.toString())
        }
    }
    val s = ui.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("Account")
            InfoRow("Google account", s.accountEmail ?: "Not signed in", "account-email")
            if (onSwitchAccount != null) ActionRow("Switch account", "Sign in with another Google account", "switch-account", onSwitchAccount)
            InfoRow("Alarms calendar", ui.calendarState, "calendar-state")
            InfoRow("Last calendar update received", s.lastCalendarChange?.let { EventLog.time(it) } ?: "—", "last-change")
            InfoRow("Changes waiting to upload", ui.pendingUploads?.toString() ?: "unknown", "pending-uploads")

            Section("Sound")
            ActionRow("Default sound", ui.defaultSoundName, "default-sound") { soundPicker.launch(ringtoneIntent(s.defaultSoundUri)) }
            ChoiceRow(
                "Sound / vibration", s.soundMode,
                listOf(SoundMode.SOUND_AND_VIBRATION to "Sound and vibration", SoundMode.SOUND_ONLY to "Sound only", SoundMode.VIBRATION_ONLY to "Vibration only"),
                "sound-mode",
            ) { mode -> vm.update { it.copy(soundMode = mode) } }
            SliderRow("Volume", s.volumePercent, "volume") { v -> vm.update { it.copy(volumePercent = v) } }
            SwitchRow("Increase device volume", "Raise the alarm volume to maximum while ringing", s.increaseDeviceVolume, "increase-volume") { on ->
                vm.update { it.copy(increaseDeviceVolume = on) }
            }
            SwitchRow("Fade in", "Start quietly and get louder over 30 seconds", s.fadeIn, "fade-in") { on -> vm.update { it.copy(fadeIn = on) } }

            Section("Snooze & stop")
            ChoiceRow("Snooze duration", s.snoozeMinutes, listOf(5, 10, 15, 20, 30, 45, 60).map { it to "$it min" }, "snooze") { m ->
                vm.update { it.copy(snoozeMinutes = m) }
            }
            ChoiceRow("Auto-snooze after", s.autoSnoozeMinutes, listOf(1, 2, 3, 5, 10).map { it to if (it == 1) "1 minute" else "$it minutes" }, "auto-snooze") { m ->
                vm.update { it.copy(autoSnoozeMinutes = m) }
            }
            SwitchRow("Show snooze button", "Shows Snooze while an alarm rings", s.showSnoozeButton, "show-snooze") { on -> vm.update { it.copy(showSnoozeButton = on) } }
            ChoiceRow(
                "Stop method", s.stopMethod,
                listOf(StopMethod.THREE_PRESSES to "Press Stop three times", StopMethod.ONE_PRESS to "Press Stop once"),
                "stop-method",
            ) { m -> vm.update { it.copy(stopMethod = m) } }

            Section("Display")
            SwitchRow("24-hour clock", null, s.use24Hour, "use-24h") { on -> vm.update { it.copy(use24Hour = on) } }
            SwitchRow("Dark theme", null, s.darkTheme, "dark-theme") { on -> vm.update { it.copy(darkTheme = on) } }
            SwitchRow("Next-alarm notification", "A silent notification showing the next alarm", s.nextAlarmNotification, "next-notification") { on ->
                vm.update { it.copy(nextAlarmNotification = on) }
            }

            Section("Reliability check")
            Check.entries.forEach { check ->
                CheckRow(check, ui.checks[check] == true) {
                    when (check) {
                        Check.NOTIFICATIONS -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        Check.CALENDAR -> calendarPermission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        else -> context.startActivity(graph.checks.fixIntent(check))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun ringtoneIntent(current: String?): Intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
