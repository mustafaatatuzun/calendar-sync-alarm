package com.atatuzun.mustafaalarm.ui.edit

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.domain.AlarmContact
import com.atatuzun.mustafaalarm.domain.RecurrenceRule
import com.atatuzun.mustafaalarm.domain.Texts
import com.atatuzun.mustafaalarm.ui.WhatsAppGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val WEEK = listOf(
    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
)
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

/** "Add a person to …" row; shows "<verb> <name>" + number with a remove button once picked. */
@Composable
private fun PersonRow(
    contact: AlarmContact?,
    verb: String,
    empty: String,
    tag: String,
    icon: @Composable () -> Unit,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    OutlinedCard(onClick = onPick, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(contact?.let { "$verb ${it.name}" } ?: empty)
                if (contact != null) Text(contact.number, style = MaterialTheme.typography.bodySmall)
            }
            if (contact != null) {
                IconButton(onClick = onClear, modifier = Modifier.testTag("$tag-clear")) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove person")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EditAlarmScreen(graph: AppGraph, eventId: Long?, onDone: () -> Unit) {
    val vm = viewModel(key = "edit-${eventId ?: "new"}") { EditAlarmViewModel(graph, eventId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showPad by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.closed) { if (ui.closed) onDone() }
    LaunchedEffect(ui.error) { ui.error?.let { snackbar.showSnackbar(it); vm.clearError() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            vm.setSound(uri?.takeUnless { it == Settings.System.DEFAULT_ALARM_ALERT_URI }?.toString())
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun onPicked(set: (AlarmContact) -> Unit): (Uri?) -> Unit = { uri ->
        if (uri != null) scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching { readPickedContact(context.contentResolver, uri) }
                    .onFailure { graph.log.log("contact pick: cannot read $uri: $it") }
                    .getOrNull()
            }
            if (picked != null) set(picked) else graph.log.log("contact pick: no number in $uri")
        }
    }
    val callPicker = rememberLauncherForActivityResult(PickPhoneNumber(), onPicked(vm::setContact))
    val whatsAppPicker = rememberLauncherForActivityResult(PickPhoneNumber(), onPicked(vm::setWhatsApp))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui.editing) "Edit Alarm" else "New Alarm") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = vm::save, enabled = ui.isDirty, modifier = Modifier.testTag("save-top")) { Icon(Icons.Filled.Check, contentDescription = "Save") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (ui.loading) {
            LinearProgressIndicator(Modifier.padding(padding).fillMaxWidth())
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(onClick = { showPad = true }, modifier = Modifier.fillMaxWidth().testTag("time")) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(Texts.clock(ui.time, ui.use24h), style = MaterialTheme.typography.displayLarge)
                }
            }
            if (ui.otherRepeat) {
                Text(
                    "Repeats as set in Google Calendar",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                var showRepeatsSheet by remember { mutableStateOf(false) }

                OutlinedCard(
                    onClick = { showRepeatsSheet = true },
                    modifier = Modifier.fillMaxWidth().testTag("repeats"),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Repeat, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            Texts.recurrenceSummary(ui.recurrence, ui.time, ui.date, ui.use24h, Locale.ENGLISH),
                            Modifier.weight(1f),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                }

                // Conditional sub-section
                when (val r = ui.recurrence) {
                    RecurrenceRule.Once -> OneOffDateCard(
                        date = ui.date,
                        onPick = { showDate = true },
                        onClear = { vm.setDate(null) },
                    )
                    is RecurrenceRule.Weekly -> WeeklyDayChipRow(ui = ui, onToggle = vm::toggleDay)
                    is RecurrenceRule.MonthlyDay -> MonthlyDayStepper(r.day, vm::setMonthlyDay)
                    is RecurrenceRule.MonthlyNthWeekday ->
                        MonthlyNthRow(r.nth, r.weekday, vm::setMonthlyNth, vm::setMonthlyWeekday)
                    RecurrenceRule.Yearly -> YearlyDateCard(ui.date, onPick = { showDate = true })
                    RecurrenceRule.Daily, RecurrenceRule.EveryWeekday -> { /* no sub-section */ }
                }

                if (showRepeatsSheet) RepeatsSheet(
                    current = ui.recurrence,
                    time = ui.time,
                    date = ui.date,
                    onPick = { choice -> vm.setRecurrence(choice); showRepeatsSheet = false },
                    onDismiss = { showRepeatsSheet = false },
                )
            }
            OutlinedTextField(
                value = ui.message,
                onValueChange = vm::setMessage,
                label = { Text("Alarm message") },
                modifier = Modifier.fillMaxWidth().testTag("message"),
            )
            OutlinedCard(onClick = { picker.launch(ringtoneIntent(ui.soundUri)) }, modifier = Modifier.fillMaxWidth().testTag("sound")) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(ui.soundName)
                }
            }
            PersonRow(
                contact = ui.contact, verb = "Call", empty = "Add a person to call", tag = "contact",
                icon = { Icon(Icons.Filled.Call, contentDescription = null) },
                onPick = { runCatching { callPicker.launch(Unit) } }, onClear = { vm.setContact(null) },
            )
            PersonRow(
                contact = ui.whatsApp, verb = "WhatsApp", empty = "Add a person to WhatsApp", tag = "whatsapp",
                icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = WhatsAppGreen) },
                onPick = { runCatching { whatsAppPicker.launch(Unit) } }, onClear = { vm.setWhatsApp(null) },
            )
            Button(onClick = vm::save, enabled = ui.isDirty, modifier = Modifier.fillMaxWidth().testTag("save")) { Text("SAVE") }
            if (ui.editing) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().testTag("delete")) { Text("DELETE") }
            }
        }
    }

    if (showPad) TimePadDialog(ui.time, onDismiss = { showPad = false }, onConfirm = { vm.setTime(it); showPad = false })
    if (showDate) AlarmDatePicker(ui.date, onDismiss = { showDate = false }, onPick = { vm.setDate(it); showDate = false })
    if (confirmDelete) {
        AlertDialog(
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete alarm?") },
            text = { Text("It will also be removed from Google Calendar.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }, modifier = Modifier.testTag("confirm-delete")) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Sub-section composables
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun WeeklyDayChipRow(ui: EditUi, onToggle: (DayOfWeek) -> Unit) {
    val days = (ui.recurrence as? RecurrenceRule.Weekly)?.days ?: emptySet()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        WEEK.forEach { day -> DayBox(day, day in days) { onToggle(day) } }
    }
}

@Composable
private fun MonthlyDayStepper(day: Int, onDayChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("monthly-day"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = { onDayChange(day - 1) },
            enabled = day > 1,
            modifier = Modifier.testTag("monthly-day-dec"),
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(16.dp))
        Text("Day $day", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("monthly-day-value"))
        Spacer(Modifier.width(16.dp))
        IconButton(
            onClick = { onDayChange(day + 1) },
            enabled = day < 31,
            modifier = Modifier.testTag("monthly-day-inc"),
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun MonthlyNthRow(
    nth: Int,
    weekday: DayOfWeek,
    onNthChange: (Int) -> Unit,
    onWeekdayChange: (DayOfWeek) -> Unit,
) {
    val nthLabels = listOf(1 to "First", 2 to "Second", 3 to "Third", 4 to "Fourth", -1 to "Last")
    var nthExpanded by remember { mutableStateOf(false) }
    var dayExpanded by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().testTag("monthly-nth"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Nth selector
        Box(Modifier.weight(1f)) {
            OutlinedCard(
                onClick = { nthExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nthLabels.firstOrNull { it.first == nth }?.second ?: "$nth", Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
            if (nthExpanded) {
                androidx.compose.material3.DropdownMenu(
                    expanded = true,
                    onDismissRequest = { nthExpanded = false },
                ) {
                    nthLabels.forEach { (value, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onNthChange(value); nthExpanded = false },
                        )
                    }
                }
            }
        }
        // Weekday selector
        Box(Modifier.weight(1f)) {
            OutlinedCard(
                onClick = { dayExpanded = true },
                modifier = Modifier.fillMaxWidth().testTag("monthly-weekday"),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH), Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
            if (dayExpanded) {
                androidx.compose.material3.DropdownMenu(
                    expanded = true,
                    onDismissRequest = { dayExpanded = false },
                ) {
                    DayOfWeek.entries.forEach { dow ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(dow.getDisplayName(TextStyle.FULL, Locale.ENGLISH)) },
                            onClick = { onWeekdayChange(dow); dayExpanded = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OneOffDateCard(date: LocalDate?, onPick: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(date?.let { "Date: ${it.format(DATE)}" } ?: "Date", Modifier.weight(1f))
        if (date != null) {
            IconButton(onClick = onClear, modifier = Modifier.testTag("clear-date")) {
                Icon(Icons.Filled.Close, contentDescription = "Clear date")
            }
        }
        IconButton(onClick = onPick, modifier = Modifier.testTag("pick-date")) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = "Pick date")
        }
    }
}

@Composable
private fun YearlyDateCard(date: LocalDate?, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).testTag("yearly-date"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(date?.let { "On ${it.format(DATE)}" } ?: "Pick a date", Modifier.weight(1f))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatsSheet(
    current: RecurrenceRule,
    time: LocalTime,
    date: LocalDate?,
    onPick: (RecurrenceRule) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    /** Produces sensible defaults for each variant when picked from the sheet. */
    fun materialise(choice: RecurrenceRule): RecurrenceRule = when (choice) {
        RecurrenceRule.Once -> RecurrenceRule.Once
        RecurrenceRule.Daily -> RecurrenceRule.Daily
        RecurrenceRule.EveryWeekday -> RecurrenceRule.EveryWeekday
        is RecurrenceRule.Weekly -> {
            // Keep existing days if currently Weekly, otherwise default to Monday
            val existingDays = (current as? RecurrenceRule.Weekly)?.days
                ?: setOf(DayOfWeek.MONDAY)
            RecurrenceRule.Weekly(existingDays)
        }
        is RecurrenceRule.MonthlyDay -> {
            // Default to day 1, or keep existing day
            val existingDay = (current as? RecurrenceRule.MonthlyDay)?.day ?: 1
            RecurrenceRule.MonthlyDay(existingDay)
        }
        is RecurrenceRule.MonthlyNthWeekday -> {
            val existing = current as? RecurrenceRule.MonthlyNthWeekday
            RecurrenceRule.MonthlyNthWeekday(
                nth = existing?.nth ?: 1,
                weekday = existing?.weekday ?: DayOfWeek.MONDAY,
            )
        }
        RecurrenceRule.Yearly -> RecurrenceRule.Yearly
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .padding(bottom = 24.dp)
                .semantics { testTagsAsResourceId = true },
        ) {
            listOf(
                Triple("Does not repeat", "repeat-once", RecurrenceRule.Once),
                Triple(
                    Texts.recurrenceSummary(RecurrenceRule.Daily, time, date, true, Locale.ENGLISH),
                    "repeat-daily",
                    RecurrenceRule.Daily,
                ),
                Triple(
                    Texts.recurrenceSummary(RecurrenceRule.EveryWeekday, time, date, true, Locale.ENGLISH),
                    "repeat-weekday",
                    RecurrenceRule.EveryWeekday,
                ),
                Triple(
                    "Weekly",
                    "repeat-weekly",
                    RecurrenceRule.Weekly(setOf(DayOfWeek.MONDAY)),  // placeholder — materialise() corrects it
                ),
                Triple(
                    "Monthly on day…",
                    "repeat-monthly-day",
                    RecurrenceRule.MonthlyDay(1),  // placeholder — materialise() corrects it
                ),
                Triple(
                    "Monthly on the Nth weekday…",
                    "repeat-monthly-nth",
                    RecurrenceRule.MonthlyNthWeekday(1, DayOfWeek.MONDAY),  // placeholder
                ),
                Triple(
                    Texts.recurrenceSummary(RecurrenceRule.Yearly, time, date, true, Locale.ENGLISH),
                    "repeat-yearly",
                    RecurrenceRule.Yearly,
                ),
            ).forEach { (label, tag, choice) ->
                ListItem(
                    headlineContent = { Text(label) },
                    trailingContent = if (
                        choice::class == current::class &&
                        (choice !is RecurrenceRule.Once || current is RecurrenceRule.Once)
                    ) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(materialise(choice)) }
                        .testTag(tag),
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun DayBox(day: DayOfWeek, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .testTag("day-$day"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase(),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun ringtoneIntent(current: String?): Intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.let(Uri::parse) ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
