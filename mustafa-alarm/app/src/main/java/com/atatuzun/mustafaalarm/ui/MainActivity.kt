package com.atatuzun.mustafaalarm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.atatuzun.mustafaalarm.graph
import com.atatuzun.mustafaalarm.ui.theme.MustafaAlarmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = applicationContext.graph
        setContent {
            val settings = graph.settings.flow.collectAsStateWithLifecycle(initialValue = null).value
            if (settings != null) {
                val startAtSetup = remember { settings.calendarId == null }
                MustafaAlarmTheme(dark = settings.darkTheme) {
                    Surface(Modifier.fillMaxSize()) { AppNav(graph, startAtSetup) }
                }
            }
        }
    }
}
