package com.atatuzun.mustafaalarm.ui.edit

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.result.contract.ActivityResultContract
import com.atatuzun.mustafaalarm.domain.AlarmContact

/**
 * Opens the contacts app on phone numbers. The returned row comes with a one-off read grant,
 * so no READ_CONTACTS permission is needed.
 */
class PickPhoneNumber : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** Blocking — call off the main thread. */
fun readPickedContact(resolver: ContentResolver, uri: Uri): AlarmContact? =
    resolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { c ->
        if (!c.moveToFirst()) return null
        val number = c.getString(1)?.trim().orEmpty()
        if (number.isEmpty()) null else AlarmContact(c.getString(0)?.trim().orEmpty().ifEmpty { number }, number)
    }
