package com.atatuzun.mustafaalarm.google

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract.Calendars
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.core.database.getStringOrNull
import com.atatuzun.mustafaalarm.BuildConfig
import com.atatuzun.mustafaalarm.data.calendar.CalendarRow
import com.atatuzun.mustafaalarm.data.calendar.CalendarSetupAccess
import com.atatuzun.mustafaalarm.data.calendar.GOOGLE_ACCOUNT_TYPE
import com.atatuzun.mustafaalarm.data.settings.SettingsRepository
import com.atatuzun.mustafaalarm.log.EventLog
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.time.ZoneId

/** Spec §10.2: sign in, authorize the Calendar scope, create/locate "Alarms", enable its sync and visibility. */
class GoogleSetup(
    private val calendar: CalendarSetupAccess,
    private val rest: CalendarRestClient,
    private val settings: SettingsRepository,
    private val log: EventLog,
) {
    sealed interface AuthResult {
        data class Authorized(val token: String) : AuthResult
        data class NeedsConsent(val pendingIntent: PendingIntent) : AuthResult
    }

    suspend fun signIn(activity: Activity): String {
        require(BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()) {
            "The Google client ID is missing from this build."
        }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        check(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Unexpected credential type ${credential.type}" }
        val email = GoogleIdTokenCredential.createFrom(credential.data).id
        settings.update { it.copy(accountEmail = email) }
        log.log("setup: signed in as $email")
        return email
    }

    suspend fun authorize(activity: Activity, email: String): AuthResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        val consent = result.pendingIntent
        return if (result.hasResolution() && consent != null) {
            AuthResult.NeedsConsent(consent)
        } else {
            AuthResult.Authorized(result.accessToken ?: error("Google returned no access token"))
        }
    }

    fun tokenFromConsent(activity: Activity, data: Intent?): String =
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
            ?: error("Google returned no access token")

    /**
     * Blocking — call off the main thread.
     *
     * Decision 2026-10-02 #3 (binding): gate is "Alarms calendar located".
     *   1. Look for an existing "Alarms" calendar under account_type=com.google for [email].
     *   2. If found → enable sync + visibility and store the id; sign-in skipped.
     *   3. If missing → use [accessToken] to POST+PATCH the REST API, wait for the row to appear, then enable.
     *
     * Returns the provider row id of the "Alarms" calendar.
     */
    fun ensureAlarmsCalendar(email: String, accessToken: String?): Long {
        val existing = calendar.findCalendars(email).firstOrNull { it.displayName == CALENDAR_NAME }
        val row = existing ?: createAndWait(
            email,
            accessToken ?: error("Not authorized for Google Calendar"),
        )
        calendar.enableSyncAndVisibility(row.id)
        settings.updateBlocking { it.copy(accountEmail = email, calendarId = row.id, calendarSyncId = row.syncId) }
        log.log("setup: Alarms calendar ${row.id} (${row.syncId}) ready")
        return row.id
    }

    /**
     * Decision #3 reinstall path: scans ALL calendars in the provider (any account type) for one
     * named "Alarms". On a real device this finds the Google "Alarms" calendar from a prior install;
     * in emulator tests it finds the local test calendar injected by USE_LOCAL_CALENDAR.
     *
     * Blocking — call off the main thread.  Requires READ_CALENDAR permission.
     * Returns the first matching [CalendarRow], or null if not found.
     */
    fun locateAlarmsCalendar(context: Context): CalendarRow? {
        val cols = arrayOf(
            Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_DISPLAY_NAME, Calendars._SYNC_ID, Calendars.OWNER_ACCOUNT,
            Calendars.SYNC_EVENTS, Calendars.VISIBLE,
        )
        return context.contentResolver.query(
            Calendars.CONTENT_URI, cols,
            "${Calendars.CALENDAR_DISPLAY_NAME}=?",
            arrayOf(CALENDAR_NAME),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else CalendarRow(
                id = cursor.getLong(0),
                accountName = cursor.getString(1),
                accountType = cursor.getString(2),
                displayName = cursor.getStringOrNull(3),
                syncId = cursor.getStringOrNull(4),
                ownerAccount = cursor.getStringOrNull(5),
                syncEvents = cursor.getInt(6) != 0,
                visible = cursor.getInt(7) != 0,
            )
        }
    }

    private fun createAndWait(email: String, token: String): CalendarRow {
        val googleId = rest.createCalendar(token, CALENDAR_NAME, ZoneId.systemDefault().id)
        log.log("setup: created Google calendar $googleId")
        runCatching { rest.clearNotifications(token, googleId) }
            .onFailure { log.log("setup: could not clear notifications: $it") }
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            calendar.requestSync(email)
            calendar.findCalendars(email)
                .firstOrNull { it.syncId == googleId || it.ownerAccount == googleId }
                ?.let { return it }
            Thread.sleep(3_000)
        }
        error(
            "The new Alarms calendar did not reach the phone within 2 minutes. " +
                "Check that Google Calendar sync is on, then Retry.",
        )
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/calendar.app.created"
        const val CALENDAR_NAME = "Alarms"
    }
}
