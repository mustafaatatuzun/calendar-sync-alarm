package com.atatuzun.mustafaalarm.ui.list

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.style.TextOverflow
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmContact
import com.atatuzun.mustafaalarm.domain.AlarmItem
import com.atatuzun.mustafaalarm.domain.AlarmKind
import com.atatuzun.mustafaalarm.domain.AlarmListState
import com.atatuzun.mustafaalarm.domain.RecurrenceRule
import com.atatuzun.mustafaalarm.domain.Texts
import com.atatuzun.mustafaalarm.domain.Times
import com.atatuzun.mustafaalarm.system.ContactActions
import com.atatuzun.mustafaalarm.ui.Banner
import com.atatuzun.mustafaalarm.ui.WhatsAppGreen
import com.atatuzun.mustafaalarm.ui.NextAlarmBar
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun AlarmListScreen(
    graph: AppGraph,
    onNew: () -> Unit,
    onEdit: (Long) -> Unit,
    onQuick: () -> Unit,
    onSettings: () -> Unit,
    onSetup: (() -> Unit)? = null,
) {
    val vm = viewModel { AlarmListViewModel(graph) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var pendingDelete by remember { mutableStateOf<AlarmItem?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Alarms") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag("search-field"),
                    singleLine = true,
                )
                IconButton(onClick = onQuick, modifier = Modifier.testTag("btn-quick")) {
                    Icon(Icons.Filled.Bolt, contentDescription = "Quick alarms", tint = Color(0xFFFFC107))
                }
                FilledIconButton(onClick = onNew, modifier = Modifier.testTag("btn-add")) {
                    Icon(Icons.Filled.Add, contentDescription = "New alarm")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.banners.volumeLow) item {
                Banner(
                    text = "Alarm volume is low. Raise it or turn on \"Increase device volume\".",
                    action = "Settings",
                    tag = "banner-volume",
                    onAction = onSettings,
                )
            }
            if (ui.banners.missingPermission) item {
                Banner(
                    text = "Some permissions are missing — alarms may not ring.",
                    action = "Fix",
                    tag = "banner-permission",
                    onAction = onSettings,
                )
            }
            if (ui.banners.syncOff) item {
                Banner(
                    text = "Google sync is off — your PC won't see changes",
                    action = "Fix",
                    tag = "banner-sync",
                    onAction = {
                        context.startActivity(
                            Intent(Settings.ACTION_SYNC_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
            when (val state = ui.state) {
                null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                AlarmListState.NoCalendar -> item {
                    Banner(
                        text = "No Alarms calendar yet. Finish the Google setup.",
                        action = onSetup?.let { "Set up" },
                        tag = "banner-calendar",
                        onAction = { onSetup?.invoke() },
                    )
                }
                AlarmListState.CalendarMissing -> item {
                    Banner(
                        text = "Alarms calendar is missing",
                        action = onSetup?.let { "Recreate" },
                        tag = "banner-calendar",
                        onAction = { onSetup?.invoke() },
                    )
                }
                AlarmListState.Unavailable -> item {
                    Banner(
                        text = "Calendar access is off — alarms can't be listed.",
                        action = "Fix",
                        tag = "banner-permission",
                        onAction = onSettings,
                    )
                }
                is AlarmListState.Ready -> {
                    state.nextRing?.let { next ->
                        item { NextAlarmBar(Texts.untilNext(next, ui.now)) }
                    }
                    if (state.sections.isEmpty()) {
                        item { Text("No alarms. Tap + to add one.", Modifier.padding(24.dp)) }
                    }
                    val today = Times.localDate(ui.now, zone)
                    state.sections.forEach { section ->
                        val filteredItems = if (searchQuery.isNotBlank()) {
                            section.items.filter { it.title.contains(searchQuery, ignoreCase = true) }
                        } else {
                            section.items
                        }
                        if (filteredItems.isNotEmpty()) {
                            stickyHeader(key = "day-${section.day}") {
                                // Sticky day header: stays pinned at the top until the next
                                // section's header reaches it. Solid background so cards
                                // scrolling behind don't bleed through.
                                Text(
                                    text = Texts.dayLabel(section.day, today),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(top = 8.dp, bottom = 4.dp),
                                )
                            }
                        }
                        items(filteredItems, key = { it.eventId }) { item ->
                            AlarmRow(
                                item = item,
                                use24h = ui.use24h,
                                zone = zone,
                                onToggle = { vm.setEnabled(item.eventId, it) },
                                onEdit = { onEdit(item.eventId) },
                                onDelete = { pendingDelete = item },
                                onCall = { ContactActions.start(context, ContactActions.dial(it), graph.log::log) },
                                onWhatsApp = { ContactActions.start(context, ContactActions.whatsApp(context, it), graph.log::log) },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete alarm?") },
            text = { Text("\"${item.title}\" will also be removed from Google Calendar.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(item.eventId)
                        pendingDelete = null
                    },
                    modifier = Modifier.testTag("confirm-delete"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AlarmRow(
    item: AlarmItem,
    use24h: Boolean,
    zone: ZoneId,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCall: (AlarmContact) -> Unit,
    onWhatsApp: (AlarmContact) -> Unit,
) {
    val container = if (item.on) Color(0xFF4A5670) else Color(0xFF5F6368)
    val content = if (item.on) Color.White else Color(0xFF2E3135)
    // Spec §9 binding: whole-row tap opens edit; Edit icon button kept as visual affordance.
    Card(
        onClick = onEdit,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("alarm-${item.eventId}"),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        ) {
            // Full-width title (no leading icon — gives Turkish messages the whole row).
            // maxLines = 3 before truncating; long messages show in full on the Edit screen.
            Text(
                text = item.title,
                softWrap = true,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            // Bottom row: time (left) + switch/edit/delete (right).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = Texts.clock(item.shownAt, zone, use24h),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (item.kind == AlarmKind.SERIES) {
                        Text(
                            text = Texts.recurrenceSummary(item.recurrence, Times.localTime(item.shownAt, zone), Times.localDate(item.shownAt, zone), use24h, Locale.ENGLISH),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item.call?.let { contact ->
                    IconButton(onClick = { onCall(contact) }, modifier = Modifier.testTag("call-${item.eventId}")) {
                        Icon(Icons.Filled.Call, contentDescription = "Call ${contact.name}")
                    }
                }
                item.whatsApp?.let { contact ->
                    IconButton(onClick = { onWhatsApp(contact) }, modifier = Modifier.testTag("whatsapp-${item.eventId}")) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "WhatsApp ${contact.name}", tint = WhatsAppGreen)
                    }
                }
                Switch(
                    checked = item.on,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("switch-${item.eventId}"),
                )
                // Edit icon dropped — tapping anywhere on the card already opens the edit screen.
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete-${item.eventId}"),
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        }
    }
}
