package im.zoe.labs.flutter_notification_listener

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.util.ArrayList

class FlutterNotificationListenerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, ActivityAware, PluginRegistry.RequestPermissionsResultListener {
    private var eventSink: EventChannel.EventSink? = null
    private lateinit var mContext: Context
    var plugBinding: ActivityPluginBinding? = null
    var binaryMessenger: BinaryMessenger? = null
    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        Log.i(TAG, "on attached to engine")
        mContext = flutterPluginBinding.applicationContext
        binaryMessenger = flutterPluginBinding.binaryMessenger

        // event stream channel
        EventChannel(
            flutterPluginBinding.binaryMessenger, EVENT_CHANNEL_NAME
        ).setStreamHandler(this)
        // method channel
        MethodChannel(
            flutterPluginBinding.binaryMessenger, METHOD_CHANNEL_NAME
        ).setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // methodChannel.setMethodCallHandler(null)
        stopService(mContext)
    }

    override fun onListen(o: Any?, eventSink: EventChannel.EventSink?) {
        this.eventSink = eventSink
    }

    override fun onCancel(o: Any?) {
        eventSink = null
    }
    private fun registerResultListener(){
        if (plugBinding != null) {
            plugBinding?.addRequestPermissionsResultListener(this)
        }
    }
    private fun deRegisterResultListener() {
        if (plugBinding != null) {
            plugBinding?.removeRequestPermissionsResultListener(this)
        }
    }
    companion object {
        const val TAG = "ListenerPlugin"
        private const val EVENT_CHANNEL_NAME = "flutter_notification_listener/events"
        private const val METHOD_CHANNEL_NAME = "flutter_notification_listener/method"

        const val SHARED_PREFERENCES_KEY = "flutter_notification_cache"

        private const val CALLBACK_DISPATCHER_HANDLE_KEY = "callback_dispatch_handler"
        const val PROMOTE_SERVICE_ARGS_KEY = "promote_service_args"
        const val CALLBACK_HANDLE_KEY = "callback_handler"

        private val sNotificationCacheLock = Object()

        fun registerAfterReboot(context: Context) {
            synchronized(sNotificationCacheLock) {
                Log.i(TAG, "try to start service after reboot")
                internalStartService(context, null,plugbin)
            }
        }

        private fun initialize(context: Context, cbId: Long) {
            Log.d(
                TAG, "plugin init: install callback and notify the service flutter engine changed"
            )
            context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE).edit()
                .putLong(CALLBACK_DISPATCHER_HANDLE_KEY, cbId).apply()

            // TODO: update the flutter engine
            // call the service to update the flutter engine
            NotificationsHandlerService.updateFlutterEngine()
        }

        private fun internalStartService(
            context: Context, cfg: Utils.PromoteServiceConfig?
        methodChannel: MethodChannel
        ): Boolean {
            if (!NotificationsHandlerService.permissionGiven(context)) {
                Log.e(TAG, "can't get permission to start service.")
                return false
            }

            Log.d(TAG, "start service with args: $cfg")

            // and try to toggle the service to trigger rebind
            with(NotificationsHandlerService) {

                /* Start the notification service once permission has been given. */
                val intent = Intent(context, NotificationsHandlerService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.i(TAG, "start service foreground")
                    context.startForegroundService(intent)
                } else {
                    Log.i(TAG, "start service normal")
                    context.startService(intent)
                }

                // and try to toggle the service to trigger rebind
                disableServiceSettings(context)
                enableServiceSettings(context)
            }

            return true
        }

        fun startService(context: Context, cfg: Utils.PromoteServiceConfig, methodChannel: MethodChannel): Boolean {
            // store the config
            cfg.save(context)
            return internalStartService(context, cfg)
        }

        fun stopService(context: Context): Boolean {
            if (!isServiceRunning()) return true

            val intent = Intent(context, NotificationsHandlerService::class.java)
            intent.action = NotificationsHandlerService.ACTION_SHUTDOWN
            context.startService(intent)
            return true
        }

        fun isServiceRunning(): Boolean {
            return NotificationsHandlerService.instance != null
        }

        fun registerEventHandle(context: Context, cbId: Long): Boolean {
            context.getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE).edit()
                .putLong(CALLBACK_HANDLE_KEY, cbId).apply()
            return true
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "plugin.initialize" -> {
                val cbId = call.arguments<Long?>()!!
                initialize(mContext, cbId)
                return result.success(true)
            }
            "plugin.startService" -> {
                val cfg = Utils.PromoteServiceConfig.fromMap(call.arguments as Map<*, *>)
                return result.success(startService(mContext, cfg))
            }
            "plugin.stopService" -> {
                return result.success(stopService(mContext))
            }
            "plugin.hasPermission" -> {
                return result.success(NotificationsHandlerService.permissionGiven(mContext))
            }
            "plugin.openPermissionSettings" -> {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    result.success(NotificationsHandlerService.openPermissionSettings(mContext))
                } else {
                    result.success(false)
                }
            }
            "plugin.openAppSettings" -> {
                return result.success(NotificationsHandlerService.openAppSettings(mContext))
            }
            "plugin.getManufacture" -> {
                return result.success(NotificationsHandlerService.getManufacture())
            }
            "plugin.openBatterySettings" -> {
                return result.success(NotificationsHandlerService.openBatterySettings(mContext))
            }
            "plugin.openAppLaunchSettings" -> {
                return result.success(NotificationsHandlerService.openAppLaunchSettings(mContext))
            }
            "plugin.isServiceRunning" -> {
                return result.success(isServiceRunning())
            }
            "plugin.registerEventHandle" -> {
                val cbId = call.arguments<Long?>()!!
                registerEventHandle(mContext, cbId)
                return result.success(true)
            }
            "plugin.registerCallListener" -> {
                return result.success(NotificationsHandlerService.registerCallListener(plugBinding?.activity))
            }
            // this should move to plugin
            "plugin.promoteToForeground" -> {
                // add data
                val cfg = Utils.PromoteServiceConfig.fromMap(call.arguments as Map<*, *>).apply {
                    foreground = true
                }
                return result.success(NotificationsHandlerService.showForegroundNotification(cfg, plugBinding?.activity))
            }
            "plugin.demoteToBackground" -> {
                return result.success(NotificationsHandlerService.hideForegroundNotification())
            }
            "plugin.tap" -> {
                // tap the notification
                Log.d(TAG, "tap the notification")
                val args = call.arguments<ArrayList<*>?>()
                val uid = args!![0]!! as String
                return result.success(NotificationsHandlerService.notificationTapped(uid))
            }
            "plugin.tap_action" -> {
                // tap the action
                Log.d(TAG, "tap action of notification")
                val args = call.arguments<ArrayList<*>?>()
                val uid = args!![0]!! as String
                val idx = args[1]!! as Int
                return result.success(NotificationsHandlerService.notificationActionTapped(uid, idx))
            }
            "plugin.send_input" -> {
                // send the input data
                Log.d(TAG, "set the content for input and the send action")
                val args = call.arguments<ArrayList<*>?>()
                val uid = args!![0]!! as String
                val idx = args[1]!! as Int
                val data = args[2]!! as Map<*, *>
                return result.success(NotificationsHandlerService.sendNotificationInputted(uid, idx, data))
            }
            "plugin.get_full_notification" -> {
                val args = call.arguments<ArrayList<*>?>()
                val uid = args!![0]!! as String
                return result.success(NotificationsHandlerService.fullNotification(uid))
            }
            else -> result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        plugBinding = binding
        registerResultListener()
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        deRegisterResultListener()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ): Boolean {

        if (grantResults.isNotEmpty() && requestCode == NotificationsHandlerService.PHONE_STATE_PERMISSION_CODE && grantResults.first() == PackageManager.PERMISSION_GRANTED) {
            NotificationsHandlerService.registerCallListener(plugBinding?.activity)
        }
        return true
    }

}
