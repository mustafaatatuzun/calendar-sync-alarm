package com.atatuzun.mustafaalarm.ui.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.system.Check
import com.atatuzun.mustafaalarm.ui.Banner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(graph: AppGraph, onDone: () -> Unit) {
    val vm = viewModel { SetupViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity ?: return

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onConsentResult(activity, result.data)
    }
    // Launcher for the CALENDAR step (sign-in → authorize → create).
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) vm.setUpCalendar()
        else vm.fail("Calendar permission is needed to find the Alarms calendar.")
    }
    // Launcher for the LOCATE step (Decision #3 reinstall path): re-try scan after granting permission.
    val locateCalendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.runLocate() // runLocate() checks calendarAvailable() internally; falls to SIGN_IN if still denied
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.advance() }

    LaunchedEffect(ui.consent) {
        ui.consent?.let {
            consent.launch(IntentSenderRequest.Builder(it).build())
            vm.consentLaunched()
        }
    }
    LaunchedEffect(ui.finished) { if (ui.finished) onDone() }
    LifecycleResumeEffect(Unit) {
        vm.advance()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Set up Mustafa Alarm") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.email?.let {
                Text(
                    "Signed in as $it",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("setup-email"),
                )
            }
            SetupStep.entries.forEach { step ->
                StepRow(
                    step = step,
                    done = ui.finished || step.ordinal < ui.step.ordinal,
                    current = !ui.finished && step == ui.step,
                )
            }
            ui.error?.let { Banner(it, null, "setup-error") {} }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                enabled = !ui.busy,
                onClick = {
                    when (ui.step) {
                        SetupStep.LOCATE ->
                            // Auto-scans on init; this button is a manual retry if needed.
                            if (hasCalendarPermission(context)) vm.runLocate()
                            else locateCalendarPermission.launch(
                                arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR,
                                ),
                            )
                        SetupStep.SIGN_IN -> vm.signIn(activity)
                        SetupStep.AUTHORIZE -> vm.authorize(activity)
                        SetupStep.CALENDAR ->
                            if (hasCalendarPermission(context)) vm.setUpCalendar()
                            else calendarPermission.launch(
                                arrayOf(
                                    Manifest.permission.READ_CALENDAR,
                                    Manifest.permission.WRITE_CALENDAR,
                                ),
                            )
                        SetupStep.NOTIFICATIONS ->
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        SetupStep.FULL_SCREEN ->
                            activity.startActivity(graph.checks.fixIntent(Check.FULL_SCREEN))
                        SetupStep.OVER_OTHER_APPS ->
                            activity.startActivity(graph.checks.fixIntent(Check.OVER_OTHER_APPS))
                        SetupStep.BATTERY ->
                            activity.startActivity(graph.checks.fixIntent(Check.BATTERY))
                    }
                },
                // Minor 2 fix: testTagsAsResourceId is already set on the NavHost in AppNav;
                // no need to repeat it on individual composables.
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("setup-next"),
            ) {
                Text(if (ui.error != null) "Retry" else ui.step.title)
            }
            if (ui.step.ordinal >= SetupStep.NOTIFICATIONS.ordinal) {
                TextButton(
                    onClick = vm::skip,
                    modifier = Modifier.testTag("setup-skip"),
                ) {
                    Text("Skip for now")
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: SetupStep, done: Boolean, current: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = when {
                done    -> Icons.Filled.CheckCircle
                current -> Icons.Filled.RadioButtonChecked
                else    -> Icons.Filled.RadioButtonUnchecked
            },
            contentDescription = if (done) "Done" else null,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(step.title, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
            Text(step.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun hasCalendarPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
