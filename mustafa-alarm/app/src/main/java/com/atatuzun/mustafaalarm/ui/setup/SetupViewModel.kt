package com.atatuzun.mustafaalarm.ui.setup

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atatuzun.mustafaalarm.AppGraph
import com.atatuzun.mustafaalarm.google.GoogleSetup
import com.atatuzun.mustafaalarm.system.Check
import com.atatuzun.mustafaalarm.watch.CalendarChangeJob
import com.atatuzun.mustafaalarm.watch.SafetyCheckWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SetupStep(val title: String, val reason: String) {
    /**
     * Decision #3 reinstall path: before showing sign-in, check the provider for an existing
     * "Alarms" calendar. If found, skip sign-in entirely.  Auto-advances; no user input needed.
     */
    LOCATE("Looking for your Alarms calendar", "Checking if the Alarms calendar already exists on this device."),
    SIGN_IN("Sign in with Google", "Your alarms are kept in your Google Calendar."),
    AUTHORIZE("Allow Google Calendar", "Lets Mustafa Alarm create its own Alarms calendar."),
    CALENDAR("Alarms calendar", "Creates or finds the Alarms calendar and turns on its sync."),
    NOTIFICATIONS("Notifications", "Needed to show a ringing alarm."),
    FULL_SCREEN(
        "Full-screen alarms",
        "Shows the alarm over the lock screen. On Samsung the toggle may already look turned on " +
            "but is really in the system's default state — flip it off and back on to confirm it.",
    ),
    BATTERY("Battery: Unrestricted", "Stops Samsung from putting the app to sleep."),
}

data class SetupUi(
    val step: SetupStep = SetupStep.LOCATE,
    val email: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val consent: PendingIntent? = null,
    val finished: Boolean = false,
)

class SetupViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = mutable.asStateFlow()
    private var token: String? = null
    private val skipped = mutableSetOf<SetupStep>()
    private val permissionSteps = listOf(
        SetupStep.NOTIFICATIONS to Check.NOTIFICATIONS,
        SetupStep.FULL_SCREEN to Check.FULL_SCREEN,
        SetupStep.BATTERY to Check.BATTERY,
    )

    init {
        // Decision #3 (binding): scan the provider for an existing "Alarms" calendar BEFORE
        // showing sign-in. Handles the reinstall-with-expired-grant case: no calendarId in
        // settings, but the calendar row is still in the provider from the previous install.
        runLocate()
    }

    /**
     * Scan all calendars in the provider for one named "Alarms".
     * If found: persist the id, reschedule, advance past sign-in to permission steps.
     * If not found (or calendar permissions not yet granted): advance to SIGN_IN.
     * Safe to call multiple times (re-scans on button retry after error).
     */
    fun runLocate() {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                val found = withContext(Dispatchers.IO) {
                    if (graph.calendarAvailable()) {
                        graph.google.locateAlarmsCalendar(graph.context)
                    } else null
                }
                if (found != null) {
                    withContext(Dispatchers.IO) {
                        graph.calendar.enableSyncAndVisibility(found.id)
                        graph.settings.update {
                            it.copy(
                                calendarId = found.id,
                                accountEmail = found.accountName,
                                calendarSyncId = found.syncId,
                            )
                        }
                        CalendarChangeJob.schedule(graph.context)
                        SafetyCheckWorker.schedule(graph.context)
                        graph.scheduler.reschedule("setup-locate")
                        graph.log.log("setup: located existing Alarms calendar id=${found.id}")
                    }
                    mutable.update { it.copy(step = SetupStep.NOTIFICATIONS) }
                    advance()
                } else {
                    // Nothing in the provider — proceed to the standard sign-in flow.
                    mutable.update { it.copy(step = SetupStep.SIGN_IN) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.log.log("setup: locate scan failed: $e")
                // Failure is non-fatal: fall through to sign-in.
                mutable.update { it.copy(step = SetupStep.SIGN_IN) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun signIn(activity: Activity) = attempt {
        val email = graph.google.signIn(activity)
        mutable.update { it.copy(email = email, step = SetupStep.AUTHORIZE) }
    }

    fun authorize(activity: Activity) = attempt {
        when (val result = graph.google.authorize(activity, checkNotNull(mutable.value.email))) {
            is GoogleSetup.AuthResult.Authorized -> {
                token = result.token
                mutable.update { it.copy(step = SetupStep.CALENDAR) }
            }
            is GoogleSetup.AuthResult.NeedsConsent ->
                mutable.update { it.copy(consent = result.pendingIntent) }
        }
    }

    fun consentLaunched() = mutable.update { it.copy(consent = null) }

    fun onConsentResult(activity: Activity, data: Intent?) = attempt {
        token = graph.google.tokenFromConsent(activity, data)
        mutable.update { it.copy(step = SetupStep.CALENDAR) }
    }

    fun setUpCalendar() = attempt {
        withContext(Dispatchers.IO) {
            graph.google.ensureAlarmsCalendar(checkNotNull(mutable.value.email), token)
            CalendarChangeJob.schedule(graph.context)
            SafetyCheckWorker.schedule(graph.context)
            graph.scheduler.reschedule("setup")
        }
        mutable.update { it.copy(step = SetupStep.NOTIFICATIONS) }
        advance()
    }

    fun fail(message: String) = mutable.update { it.copy(error = message) }

    fun skip() {
        skipped += mutable.value.step
        advance()
    }

    /** Moves past permission steps that are granted or skipped; runs on resume and after each request. */
    fun advance() {
        if (mutable.value.step.ordinal < SetupStep.NOTIFICATIONS.ordinal) return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { graph.checks.status() }
            val next = permissionSteps.firstOrNull { (step, check) ->
                status[check] != true && step !in skipped
            }?.first
            mutable.update {
                if (next == null) it.copy(finished = true, error = null)
                else it.copy(step = next, error = null)
            }
        }
    }

    private fun attempt(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.log.log("setup failed: $e")
                mutable.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
