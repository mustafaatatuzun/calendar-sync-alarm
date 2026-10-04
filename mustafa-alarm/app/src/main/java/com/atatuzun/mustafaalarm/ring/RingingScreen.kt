package com.atatuzun.mustafaalarm.ring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.data.settings.StopMethod
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.domain.RingingEntry
import com.atatuzun.mustafaalarm.domain.Texts
import kotlinx.coroutines.delay
import java.time.ZoneId

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RingingScreen(
    entries: List<RingingEntry>,
    settings: AlarmSettings,
    onAll: (String) -> Unit,
    onOne: (String, InstanceKey) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val pressesNeeded = if (settings.stopMethod == StopMethod.THREE_PRESSES) 3 else 1
    val stop by RingingService.stopPresses.collectAsState()
    val del by RingingService.deletePresses.collectAsState()
    var expanded by remember { mutableStateOf<InstanceKey?>(null) }

    Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Texts.clock(now, zone, settings.use24Hour), fontSize = 88.sp, fontWeight = FontWeight.Light, modifier = Modifier.testTag("ring-clock"))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(entries, key = { "${it.key.eventId}:${it.key.begin}" }) { entry ->
                    Card(
                        onClick = { expanded = if (expanded == entry.key) null else entry.key },
                        modifier = Modifier.fillMaxWidth().testTag("ring-row-${entry.key.eventId}"),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.headlineSmall, softWrap = true)
                            Text(Texts.clock(entry.ringAt, zone, settings.use24Hour), style = MaterialTheme.typography.bodyMedium)
                            if (expanded == entry.key) {
                                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (settings.showSnoozeButton) {
                                        OutlinedButton(onClick = { onOne(RingingService.ACTION_SNOOZE, entry.key) }, modifier = Modifier.testTag("row-snooze-${entry.key.eventId}")) { Text("Snooze") }
                                    }
                                    OutlinedButton(onClick = { onOne(RingingService.ACTION_TOMORROW, entry.key) }, modifier = Modifier.testTag("row-tomorrow-${entry.key.eventId}")) { Text("Tomorrow") }
                                    OutlinedButton(onClick = { onOne(RingingService.ACTION_STOP, entry.key) }, modifier = Modifier.testTag("row-stop-${entry.key.eventId}")) { Text("Stop") }
                                }
                            }
                        }
                    }
                }
            }
            if (settings.showSnoozeButton) BigButton("Snooze ${settings.snoozeMinutes} m", "ring-snooze") { onAll(RingingService.ACTION_SNOOZE) }
            BigButton("Tomorrow", "ring-tomorrow") { onAll(RingingService.ACTION_TOMORROW) }
            BigButton(
                if (stop == 0) "Stop" else "Stop (${pressesNeeded - stop} more)",
                "ring-stop",
            ) { onAll(RingingService.ACTION_STOP) }
            BigButton(
                if (del == 0) "Delete" else "Delete (${pressesNeeded - del} more)",
                "ring-delete",
            ) { onAll(RingingService.ACTION_DELETE) }
        }
    }
}

@Composable
private fun BigButton(label: String, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(64.dp).testTag(tag)) {
        Text(label, fontSize = 22.sp)
    }
}
