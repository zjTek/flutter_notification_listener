package im.zoe.labs.flutter_notification_listener

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

class PhoneCallStateListener: PhoneStateListener() {
    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        super.onCallStateChanged(state, phoneNumber)
        var callStatus = ""
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
        val map:Map<String,Any?> = mapOf("title" to "Call", "package_name" to "call.status","text" to callStatus)
        NotificationsHandlerService.sendNotification(map)
    }

}