package com.atatuzun.mustafaalarm.domain

data class Release(val version: String, val date: String, val changes: List<String>)

/** Shown in Settings → About → What's new; newest first. ChangelogTest keeps the top entry = versionName. */
object Changelog {
    val releases = listOf(
        Release(
            "0.3.1", "2026-10-06",
            listOf(
                "Fixed: after snoozing, \"Tomorrow\" moves the alarm to tomorrow at the time you set it for " +
                    "(e.g. 10:00), not at the snoozed time (e.g. 11:30). The same applies when an unanswered " +
                    "alarm moves itself to tomorrow.",
            ),
        ),
        Release(
            "0.3", "2026-10-06",
            listOf(
                "WhatsApp: pick a person to message for an alarm. A green chat icon on the alarm card and a " +
                    "\"WhatsApp <name>\" button on the ringing screen open the chat straight away.",
                "Call icon on the alarm card, next to the on/off switch.",
                "Press volume up or down while an alarm rings to mute it (sound and vibration) and read in peace. " +
                    "Works over the lock screen and in other apps; Snooze or Stop when you're ready.",
                "The notification pop-up no longer sits on top of the full-screen alarm.",
                "Fixed: snoozing a repeating alarm (or \"Tomorrow\") is now saved to Google Calendar. Before, it " +
                    "rang again on the phone but the calendar never changed.",
                "Settings → About with the version and this list.",
            ),
        ),
        Release(
            "0.2", "2026-10-06",
            listOf(
                "The alarm now opens full screen even while you are using the phone (allow \"Show over other " +
                    "apps\" — Samsung calls it \"Appear on top\").",
                "Edit the alarm message while it rings (pencil button).",
                "Call someone from the alarm: pick a contact on the edit screen; \"Call <name>\" on the ringing " +
                    "screen opens the dialer with the number filled in.",
            ),
        ),
        Release(
            "0.1", "2026-10-04",
            listOf(
                "Alarms live in a dedicated \"Alarms\" Google Calendar: set them from your PC, they ring on the phone.",
                "All Google Calendar repeat options, three-tap Stop and Delete, quick alarms, custom sounds.",
            ),
        ),
    )
}
