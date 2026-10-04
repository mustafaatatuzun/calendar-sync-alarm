package com.atatuzun.mustafaalarm.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.ui.edit.EditAlarmScreen
import com.atatuzun.mustafaalarm.ui.list.AlarmListScreen
import com.atatuzun.mustafaalarm.ui.quick.QuickAlarmsScreen
import com.atatuzun.mustafaalarm.ui.settings.SettingsScreen
import com.atatuzun.mustafaalarm.ui.setup.SetupScreen

object Routes {
    const val LIST = "list"
    const val NEW = "edit"
    const val EDIT = "edit?eventId={eventId}"
    const val QUICK = "quick"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    fun edit(eventId: Long) = "edit?eventId=$eventId"
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppNav(graph: AppGraph, startAtSetup: Boolean = false) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = if (startAtSetup) Routes.SETUP else Routes.LIST,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                graph = graph,
                onDone = {
                    nav.navigate(Routes.LIST) {
                        popUpTo(nav.graph.id) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LIST) {
            AlarmListScreen(
                graph = graph,
                onNew = { nav.navigate(Routes.NEW) },
                onEdit = { nav.navigate(Routes.edit(it)) },
                onQuick = { nav.navigate(Routes.QUICK) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onSetup = { nav.navigate(Routes.SETUP) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            EditAlarmScreen(
                graph = graph,
                eventId = entry.arguments?.getLong("eventId")?.takeIf { it > 0 },
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.QUICK) {
            QuickAlarmsScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onCreateOwn = {
                    // Replace QUICK in the back stack so that saving the new alarm
                    // returns directly to HOME rather than back to QUICK.
                    nav.navigate(Routes.NEW) {
                        popUpTo(Routes.QUICK) { inclusive = true }
                    }
                },
                onAddMessage = { nav.navigate(Routes.edit(it)) },
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                graph = graph,
                onBack = { nav.popBackStack() },
                onSwitchAccount = { nav.navigate(Routes.SETUP) },
            )
        }
    }
}
