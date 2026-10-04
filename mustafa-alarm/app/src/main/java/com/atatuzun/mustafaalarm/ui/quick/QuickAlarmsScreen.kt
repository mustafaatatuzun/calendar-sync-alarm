package com.atatuzun.mustafaalarm.ui.quick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.QuickPresets
import com.atatuzun.mustafaalarm.domain.Texts
import kotlinx.coroutines.delay
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAlarmsScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onCreateOwn: () -> Unit,
    onAddMessage: (Long) -> Unit,
    onDone: () -> Unit = {},
) {
    val vm = viewModel { QuickAlarmsViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(ui.result) {
        if (ui.result != null) {
            delay(6_000)
            vm.dismissResult()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick alarms") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            ui.result?.let { result ->
                ResultBar(
                    time = Texts.clock(result.at, ZoneId.systemDefault(), ui.use24h),
                    onAddMessage = { vm.dismissResult(); onAddMessage(result.eventId) },
                    onUndo = vm::undo,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("In", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickPresets.relativeMinutes.forEach { m ->
                    Preset(Texts.relative(m), "quick-in-$m") { vm.createIn(m) }
                }
            }

            Text("Morning", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickPresets.morning.forEach { t ->
                    Preset(Texts.clock(t, ui.use24h), "quick-morning-$t") { vm.createAt(t) }
                }
            }

            if (ui.frequent.isNotEmpty()) {
                Text("Your frequent alarms", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ui.frequent.forEach { t ->
                        Preset(Texts.clock(t, ui.use24h), "quick-frequent-$t") { vm.createAt(t) }
                    }
                }
            }

            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            OutlinedButton(
                onClick = onCreateOwn,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick-own"),
            ) {
                Text("Create my own")
            }
        }
    }
}

@Composable
private fun Preset(label: String, tag: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ResultBar(
    time: String,
    onAddMessage: () -> Unit,
    onUndo: () -> Unit,
) {
    Surface(
        color = Color(0xFF323232),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag("quick-result"),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Alarm set for $time",
                Modifier.weight(1f),
                color = Color.White,
            )
            TextButton(
                onClick = onAddMessage,
                modifier = Modifier.testTag("quick-add-message"),
            ) {
                Text("Add message")
            }
            TextButton(
                onClick = onUndo,
                modifier = Modifier.testTag("quick-undo"),
            ) {
                Text("Undo")
            }
        }
    }
}
