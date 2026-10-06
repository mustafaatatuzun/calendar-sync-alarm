package com.atatuzun.mustafaalarm.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.atatuzun.mustafaalarm.BuildConfig
import com.atatuzun.mustafaalarm.domain.Changelog

const val REPO_URL = "https://github.com/mustafaatatuzun/simple-alarm-android-google-calendar"

@Composable
fun AboutSection() {
    val context = LocalContext.current
    var whatsNew by remember { mutableStateOf(false) }
    Section("About")
    InfoRow("Version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", "about-version")
    ActionRow("What's new", "Changes in each version", "about-whats-new") { whatsNew = true }
    ActionRow("Source code", REPO_URL.removePrefix("https://"), "about-source") {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
    }
    if (whatsNew) WhatsNewDialog { whatsNew = false }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WhatsNewDialog(onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = onDismiss,
        title = { Text("What's new") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("whats-new-list")) {
                Changelog.releases.forEach { release ->
                    Text(
                        "Version ${release.version} — ${release.date}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    release.changes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("whats-new-close")) { Text("Close") } },
    )
}
