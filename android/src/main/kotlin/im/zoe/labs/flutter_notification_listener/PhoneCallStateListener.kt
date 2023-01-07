package im.zoe.labs.flutter_notification_listener

import android.content.Context
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

class PhoneCallStateListener(val mContext: Context): PhoneStateListener() {
    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        super.onCallStateChanged(state, phoneNumber)
        var callStatus = ""
        val numPhone = phoneNumber?: ""
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
               callStatus = "CALL_STATE_IDLE"
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                callStatus = "CALL_STATE_RINGING"
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                callStatus = "CALL_STATE_OFFHOOK"
            }
        }
        val map = mapOf("title" to "Call", "package_name" to "call.status","text" to callStatus)
        NotificationsHandlerService.sendNotification(mContext,map)
    }

}