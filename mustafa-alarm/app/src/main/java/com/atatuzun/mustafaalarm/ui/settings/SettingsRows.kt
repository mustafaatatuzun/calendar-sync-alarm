package com.atatuzun.mustafaalarm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.atatuzun.mustafaalarm.system.Check

@Composable
fun Section(title: String) {
    Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
fun InfoRow(label: String, value: String, tag: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ActionRow(label: String, value: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp).testTag(tag)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SwitchRow(label: String, description: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChoiceRow(label: String, current: T, options: List<Pair<T, String>>, tag: String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ActionRow(label, options.firstOrNull { it.first == current }?.second ?: current.toString(), tag) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column {
                    options.forEach { (value, text) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(value); open = false }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == current, onClick = { onPick(value); open = false })
                            Text(text)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
fun SliderRow(label: String, percent: Int, tag: String, onDone: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(tag)) {
        Text("$label: ${value.toInt()} %", style = MaterialTheme.typography.titleMedium)
        Slider(value = value, onValueChange = { value = it }, valueRange = 10f..100f, onValueChangeFinished = { onDone(value.toInt()) })
    }
}

@Composable
fun CheckRow(check: Check, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("check-${check.name}"), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = if (ok) "OK" else "Missing",
            tint = if (ok) Color(0xFF4CAF50) else Color(0xFFE53935),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(check.label, style = MaterialTheme.typography.titleMedium)
            Text(check.reason, style = MaterialTheme.typography.bodySmall)
        }
        if (!ok) TextButton(onClick = onFix, modifier = Modifier.testTag("fix-${check.name}")) { Text("Fix") }
    }
}
