package com.atatuzun.mustafaalarm.data.calendar

const val GOOGLE_ACCOUNT_TYPE = "com.google"

data class CalendarRow(
    val id: Long,
    val accountName: String,
    val accountType: String,
    val displayName: String?,
    val syncId: String?,
    val ownerAccount: String?,
    val syncEvents: Boolean,
    val visible: Boolean,
)

/** Calendar-level operations used by first-run setup and the reliability check. */
interface CalendarSetupAccess {
    fun findCalendars(accountName: String, accountType: String = GOOGLE_ACCOUNT_TYPE): List<CalendarRow>
    fun calendarRow(calendarId: Long): CalendarRow?
    fun enableSyncAndVisibility(calendarId: Long)
    fun requestSync(accountName: String)

    /** Events changed on the phone and not yet uploaded; null when the provider will not say. */
    fun dirtyCount(calendarId: Long): Int?
}
