package com.atatuzun.mustafaalarm.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.atatuzun.mustafaalarm.domain.AlarmContact
import com.atatuzun.mustafaalarm.domain.WhatsAppNumber
import java.util.Locale

/** Intents for reaching an alarm's contact. Neither places a call nor sends a message by itself. */
object ContactActions {
    fun dial(contact: AlarmContact): Intent =
        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Opens the WhatsApp chat with [contact]; null when the number can't be made international.
     * Aims at this profile's WhatsApp (then Business) by component: a package-only or plain link makes Samsung
     * ask every time when Dual Messenger has a second WhatsApp copy.
     */
    fun whatsApp(context: Context, contact: AlarmContact): Intent? {
        val phone = context.getSystemService(TelephonyManager::class.java)
        val country = (phone?.networkCountryIso?.ifEmpty { null } ?: phone?.simCountryIso?.ifEmpty { null } ?: Locale.getDefault().country)
            .uppercase(Locale.ROOT)
        val digits = WhatsAppNumber.digits(contact.number) { PhoneNumberUtils.formatNumberToE164(it, country) } ?: return null
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = WHATSAPP_PACKAGES.firstNotNullOfOrNull { pkg ->
            context.packageManager.queryIntentActivities(Intent(link).setPackage(pkg), 0).firstOrNull()?.activityInfo
        }
        if (target != null) link.setClassName(target.packageName, target.name)
        return link
    }

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun start(context: Context, intent: Intent?, log: (String) -> Unit) {
        if (intent == null) {
            log("contact action: no usable number")
            return
        }
        runCatching { context.startActivity(intent) }.onFailure { log("contact action: cannot open ${intent.data}: $it") }
    }
}
