package com.atatuzun.mustafaalarm.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

val WhatsAppGreen = Color(0xFF1DA851)

/** Red warning banner with an optional action, like Simple Alarm's volume warning. */
@Composable
fun Banner(text: String, action: String?, tag: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFA00000), contentColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f))
            if (action != null) {
                TextButton(onClick = onAction) { Text(action.uppercase(), color = Color(0xFFC8D7FF)) }
            }
        }
    }
}

@Composable
fun NextAlarmBar(text: String) {
    Surface(color = Color(0xFF3C3C3C), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), textAlign = TextAlign.Center)
    }
}
