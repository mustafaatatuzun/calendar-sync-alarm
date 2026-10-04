package com.atatuzun.mustafaalarm.ui.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.atatuzun.mustafaalarm.domain.Texts
import com.atatuzun.mustafaalarm.domain.TimeEntry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Number-pad time entry (spec §9.2): type 4 digits, e.g. 0730. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TimePadDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    var digits by remember { mutableStateOf("") }
    val parsed = TimeEntry.parse(digits)
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onConfirm) }, modifier = Modifier.testTag("pad-ok")) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (digits.isEmpty()) Texts.clock(initial, true) else TimeEntry.display(digits),
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.testTag("pad-display"),
                )
                Text("Type the time as 4 digits, e.g. 0730", style = MaterialTheme.typography.bodySmall)
                listOf("123", "456", "789", " 0<").forEach { row ->
                    Row {
                        row.forEach { key ->
                            when (key) {
                                ' ' -> Spacer(Modifier.size(72.dp))
                                '<' -> IconButton(onClick = { digits = TimeEntry.pop(digits) }, modifier = Modifier.size(72.dp).testTag("pad-back")) {
                                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Backspace")
                                }
                                else -> TextButton(onClick = { digits = TimeEntry.push(digits, key) }, modifier = Modifier.size(72.dp).testTag("pad-$key")) {
                                    Text(key.toString(), style = MaterialTheme.typography.headlineMedium)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Date picker limited to today and later (spec §9.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmDatePicker(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val todayUtc = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}
