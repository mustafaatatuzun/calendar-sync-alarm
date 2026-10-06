package com.atatuzun.mustafaalarm.ring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.atatuzun.mustafaalarm.data.settings.AlarmSettings
import com.atatuzun.mustafaalarm.data.settings.StopMethod
import com.atatuzun.mustafaalarm.domain.AlarmContact
import com.atatuzun.mustafaalarm.domain.AlarmPeople
import com.atatuzun.mustafaalarm.domain.InstanceKey
import com.atatuzun.mustafaalarm.ui.WhatsAppGreen
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
    people: Map<InstanceKey, AlarmPeople> = emptyMap(),
    onRename: (InstanceKey, String) -> Unit = { _, _ -> },
    onCall: (AlarmContact) -> Unit = {},
    onWhatsApp: (AlarmContact) -> Unit = {},
) {
    var editing by remember { mutableStateOf<RingingEntry?>(null) }
    editing?.let { entry ->
        EditMessageDialog(
            initial = entry.title,
            onSave = { text -> onRename(entry.key, text); editing = null },
            onDismiss = { editing = null },
        )
    }
    val muted by RingingService.muted.collectAsState()
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
            if (muted) {
                Text(
                    "Muted — snooze or stop when you're ready",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("ring-muted"),
                )
            }
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
                            Row(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, style = MaterialTheme.typography.headlineSmall, softWrap = true)
                                    Text(Texts.clock(entry.ringAt, zone, settings.use24Hour), style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { editing = entry }, modifier = Modifier.testTag("ring-edit-${entry.key.eventId}")) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit message")
                                }
                            }
                            people[entry.key]?.call?.let { contact ->
                                Button(
                                    onClick = { onCall(contact) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(56.dp).testTag("ring-call-${entry.key.eventId}"),
                                ) {
                                    Icon(Icons.Filled.Call, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Call ${contact.name}", fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            people[entry.key]?.whatsApp?.let { contact ->
                                Button(
                                    onClick = { onWhatsApp(contact) },
                                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen, contentColor = Color.White),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp).testTag("ring-whatsapp-${entry.key.eventId}"),
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("WhatsApp ${contact.name}", fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
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
private fun EditMessageDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = onDismiss,
        title = { Text("Alarm message") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().testTag("ring-edit-text"),
                minLines = 2,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, modifier = Modifier.testTag("ring-edit-save")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BigButton(label: String, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(64.dp).testTag(tag)) {
        Text(label, fontSize = 22.sp)
    }
}
