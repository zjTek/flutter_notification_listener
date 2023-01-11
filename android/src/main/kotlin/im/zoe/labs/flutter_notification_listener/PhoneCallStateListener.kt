package im.zoe.labs.flutter_notification_listener

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

class PhoneCallStateListener(private val mContext: Context) : PhoneStateListener() {
    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        super.onCallStateChanged(state, phoneNumber)
        var callStatus = ""
        var callName = ""
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                callStatus = "CALL_STATE_IDLE"
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                callStatus = "CALL_STATE_RINGING"
                if (phoneNumber != null) callName = getContactName(phoneNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                callStatus = "CALL_STATE_OFFHOOK"
            }
        }
        val map: Map<String, Any?> =
            mapOf("package_name" to callStatus, "title" to callName, "text" to (phoneNumber?:""))
        NotificationsHandlerService.sendNotification(mContext, map)
    }

    private fun getContactName(phone: String): String {
        var nameStr = ""
        val projections = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.NUMBER,
            ContactsContract.PhoneLookup.HAS_PHONE_NUMBER
        )
        val contactUri =
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
        val cur = mContext.contentResolver.query(contactUri, projections, null, null, null)
        if (cur != null && cur.moveToFirst()) {
            nameStr = cur.getString(cur.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME))
        }
        cur?.close()
        return nameStr
    }

}