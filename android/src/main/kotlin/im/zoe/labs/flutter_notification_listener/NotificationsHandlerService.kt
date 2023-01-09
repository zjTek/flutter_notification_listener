package im.zoe.labs.flutter_notification_listener

import android.annotation.SuppressLint
import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashMap
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

class NotificationsHandlerService : MethodChannel.MethodCallHandler, NotificationListenerService() {
    private val queue = ArrayDeque<NotificationEvent>()
    private lateinit var mBackgroundChannel: MethodChannel
    private lateinit var mContext: Context
    private var notifyList = arrayOf(
        "call.status",
        "contacts",
        "incallui",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.tencent.mobileqq",
        "com.tencent.mm",
        "com.facebook",
        "com.kakao.talk",
        "jp.naver.line.android",
        "com.instagram.android",
        "com.whatsapp",
        "com.twitter.android",
        "com.skype",
        "com.youtube.android"
    )

    // notification event cache: packageName_id -> event
    private val eventsCache = HashMap<String, NotificationEvent>()
    private lateinit var phoneCallStateListener: PhoneStateListener
    private lateinit var telephonyManager: TelephonyManager
    private var phoneListenStarted = false
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "service.initialized" -> {
                initFinish()
                return result.success(true)
            }
            // this should move to plugin
            "service.promoteToForeground" -> {
                // add data
                val cfg = Utils.PromoteServiceConfig.fromMap(call.arguments as Map<*, *>).apply {
                    foreground = true
                }
                return result.success(promoteToForeground(cfg))
            }
            "service.demoteToBackground" -> {
                return result.success(demoteToBackground())
            }
            "service.tap" -> {
                // tap the notification
                Log.d(TAG, "tap the notification")
                val args = call.arguments<ArrayList<*>?>()
                val uid = args!![0]!! as String
                return result.success(tapNotification(uid))
            }
            "service.tap_action" -> {
                // tap the action
                Log.d(TAG, "tap action of notification")
                val args = call.arguments<ArrayList<*>?>()
                val uid = args!![0]!! as String
                val idx = args[1]!! as Int
                return result.success(tapNotificationAction(uid, idx))
            }
            "service.send_input" -> {
                // send the input data
                Log.d(TAG, "set the content for input and the send action")
                val args = call.arguments<ArrayList<*>?>()
                val uid = args!![0]!! as String
                val idx = args[1]!! as Int
                val data = args[2]!! as Map<*, *>
                return result.success(sendNotificationInput(uid, idx, data))
            }
            "service.get_full_notification" -> {
                val args = call.arguments<ArrayList<*>?>()
                val uid = args!![0]!! as String
                if (!eventsCache.contains(uid)) {
                    return result.error("notFound", "can't found this notification $uid", "")
                }
                return result.success(Utils.Marshaller.marshal(eventsCache[uid]?.mSbn))
            }
            else -> {
                Log.d(TAG, "unknown method ${call.method}")
                result.notImplemented()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "notification listener service onStartCommand")
        registerPhoneListener()
        // if get shutdown release the wake lock
        when (intent?.action) {
            ACTION_SHUTDOWN -> {
                (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                        if (isHeld) release()
                    }
                }
                Log.i(TAG, "stop notification handler service!")
                disableServiceSettings(mContext)
                stopForeground(true)
                stopSelf()
            }
            else -> {

            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        mContext = this

        // store the service instance
        instance = this
        Log.d(TAG, "notification listener service onCreate")
        startListenerService(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "notification listener service onDestroy")
        unregisterPhoneListener()
        val bdi = Intent(mContext, RebootBroadcastReceiver::class.java)
        // remove notification
        sendBroadcast(bdi)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "notification listener service onTaskRemoved")
    }

    private fun onPhoneStatePosted(context: Context, map: Map<String, Any?>) {
        Log.d(TAG, "onPhoneStatePosted")
        synchronized(sServiceStarted) {
            if (sServiceStarted.get()) {
                Log.d(TAG, "send event to flutter side immediately!")
                Handler(context.mainLooper).post { sendEvent(map) }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        if (!isTargetNotification(sbn.packageName)) {
            return
        }

        FlutterInjector.instance().flutterLoader().startInitialization(mContext)
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(mContext, null)
        val evt = NotificationEvent(mContext, sbn)

        // store the evt to cache
        eventsCache[evt.uid] = evt

        synchronized(sServiceStarted) {
            if (!sServiceStarted.get()) {
                Log.d(TAG, "service is not start try to queue the event")
                queue.add(evt)
            } else {
                Log.d(TAG, "send event to flutter side immediately!")
                Handler(mContext.mainLooper).post { sendEvent(evt.data) }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        val evt = NotificationEvent(mContext, sbn)
        // remove the event from cache
        eventsCache.remove(evt.uid)
        Log.d(TAG, "notification removed: ${evt.uid}")
    }

    private fun isTargetNotification(packName: String): Boolean {
        return notifyList.any { packName.contains(it) }
    }

    private fun initFinish() {
        Log.d(TAG, "service's flutter engine initialize finished")
        synchronized(sServiceStarted) {
            while (!queue.isEmpty()) sendEvent(queue.remove().data)
            sServiceStarted.set(true)
        }
    }

    private fun promoteToForeground(cfg: Utils.PromoteServiceConfig? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.e(TAG, "promoteToForeground need sdk >= 26")
            return false
        }

//        if (cfg?.foreground != true) {
//            Log.i(TAG, "no need to start foreground: ${cfg?.foreground}")
//            return false
//        }

        // first is not running already, start at first
//        if (!FlutterNotificationListenerPlugin.isServiceRunning(mContext, this.javaClass)) {
//            Log.e(TAG, "service is not running")
//            return false
//        }

        // get args from store or args
        val cfgHere = cfg ?: Utils.PromoteServiceConfig()
        // make the service to foreground

        // take a wake lock
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(5 * 60 * 1000L /*10 minutes*/)
            }
        }

        // create a channel for notification
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Power band Notifications Listener Plugin",
            NotificationManager.IMPORTANCE_HIGH
        )
        val imageId = resources.getIdentifier("ic_launcher", "mipmap", packageName)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            channel
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(cfgHere.title)
            .setContentText(cfgHere.description)
            .setShowWhen(cfgHere.showWhen ?: false)
            .setSubText(cfgHere.subTitle)
            .setSmallIcon(imageId)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        Log.d(TAG, "promote the service to foreground")
        startForeground(ONGOING_NOTIFICATION_ID, notification)

        return true
    }

    private fun demoteToBackground(): Boolean {
        Log.d(TAG, "demote the service to background")
        (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                if (isHeld) release()
            }
        }
        stopForeground(true)
        return true
    }

    private fun tapNotification(uid: String): Boolean {
        Log.d(TAG, "tap the notification: $uid")
        if (!eventsCache.containsKey(uid)) {
            Log.d(TAG, "notification is not exits: $uid")
            return false
        }
        val n = eventsCache[uid] ?: return false
        n.mSbn.notification.contentIntent.send()
        return true
    }

    private fun tapNotificationAction(uid: String, idx: Int): Boolean {
        Log.d(TAG, "tap the notification action: $uid @$idx")
        if (!eventsCache.containsKey(uid)) {
            Log.d(TAG, "notification is not exits: $uid")
            return false
        }
        val n = eventsCache[uid]
        if (n == null) {
            Log.e(TAG, "notification is null: $uid")
            return false
        }
        if (n.mSbn.notification.actions.size <= idx) {
            Log.e(
                TAG,
                "tap action out of range: size ${n.mSbn.notification.actions.size} index $idx"
            )
            return false
        }

        val act = n.mSbn.notification.actions[idx]
        if (act == null) {
            Log.e(TAG, "notification $uid action $idx not exits")
            return false
        }
        act.actionIntent.send()
        return true
    }

    private fun sendNotificationInput(uid: String, idx: Int, data: Map<*, *>): Boolean {
        Log.d(TAG, "tap the notification action: $uid @$idx")
        if (!eventsCache.containsKey(uid)) {
            Log.d(TAG, "notification is not exits: $uid")
            return false
        }
        val n = eventsCache[uid]
        if (n == null) {
            Log.e(TAG, "notification is null: $uid")
            return false
        }
        if (n.mSbn.notification.actions.size <= idx) {
            Log.e(
                TAG,
                "send inputs out of range: size ${n.mSbn.notification.actions.size} index $idx"
            )
            return false
        }

        val act = n.mSbn.notification.actions[idx]
        if (act == null) {
            Log.e(TAG, "notification $uid action $idx not exits")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            if (act.remoteInputs == null) {
                Log.e(TAG, "notification $uid action $idx remote inputs not exits")
                return false
            }

            val intent = Intent()
            val bundle = Bundle()
            act.remoteInputs.forEach {
                if (data.containsKey(it.resultKey as String)) {
                    Log.d(TAG, "add input content: ${it.resultKey} => ${data[it.resultKey]}")
                    bundle.putCharSequence(it.resultKey, data[it.resultKey] as String)
                }
            }
            RemoteInput.addResultsToIntent(act.remoteInputs, intent, bundle)
            act.actionIntent.send(mContext, 0, intent)
            Log.d(TAG, "send the input action success")
            return true
        } else {
            Log.e(TAG, "not implement :sdk < KITKAT_WATCH")
            return false
        }
    }

    companion object {

        var callbackHandle = 0L

        @SuppressLint("StaticFieldLeak")
        @JvmStatic
        var instance: NotificationsHandlerService? = null

        @JvmStatic
        private val TAG = "NotifiListenerService"

        private const val ONGOING_NOTIFICATION_ID = 100

        @JvmStatic
        private val WAKELOCK_TAG = "IsolateHolderService::WAKE_LOCK"

        @JvmStatic
        val ACTION_SHUTDOWN = "SHUTDOWN"

        private const val CHANNEL_ID = "flutter_notifications_listener_channel"

        @JvmStatic
        private var sBackgroundFlutterEngine: FlutterEngine? = null

        @JvmStatic
        private val sServiceStarted = AtomicBoolean(false)

        private const val BG_METHOD_CHANNEL_NAME = "flutter_notification_listener/bg_method"

        private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
        const val NOTIFICATION_INTENT_KEY = "object"

        fun permissionGiven(context: Context): Boolean {
            val packageName = context.packageName
            val flat =
                Settings.Secure.getString(context.contentResolver, ENABLED_NOTIFICATION_LISTENERS)
            if (!TextUtils.isEmpty(flat)) {
                val names = flat.split(":").toTypedArray()
                for (name in names) {
                    val componentName = ComponentName.unflattenFromString(name)
                    val nameMatch = TextUtils.equals(packageName, componentName?.packageName)
                    if (nameMatch) {
                        return true
                    }
                }
            }

            return false
        }

        @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
        fun openPermissionSettings(context: Context): Boolean {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
            return true
        }

        fun openAppSettings(context: Context): Boolean {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
            return true
        }

        fun openBatterySettings(context: Context): Boolean {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
            return true
        }

        fun getManufacture(): String {
            return Build.MANUFACTURER
        }

        fun openAppLaunchSettings(context: Context): Boolean {
            val mobileType = Build.MANUFACTURER
            val intent = Intent()
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Log.e("HLQ_Struggle", "******************当前手机型号为：" + mobileType)
                var componentName: ComponentName? = null
                if (mobileType == "Xiaomi") { // 红米Note4测试通过
                    componentName = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                } else if (mobileType == "Letv") { // 乐视2测试通过
                    intent.action = "com.letv.android.permissionautoboot"
                } else if (mobileType == "samsung") { // 三星Note5测试通过
                    componentName = ComponentName(
                        "com.samsung.android.sm_cn",
                        "com.samsung.android.sm.ui.ram.AutoRunActivity"
                    )
                } else if (mobileType == "HUAWEI") { // 华为测试通过
                    componentName =
                        ComponentName.unflattenFromString("com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity") //跳自启动管理
                    //SettingOverlayView.show(context);
                } else if (mobileType == "vivo") { // VIVO测试通过
                    componentName =
                        ComponentName.unflattenFromString("com.iqoo.secure/.safeguard.PurviewTabActivity")
                } else if (mobileType == "Meizu") {
                    // 针对魅族，我们只能通过魅族内置手机管家去设置自启动，所以我在这里直接跳转到魅族内置手机管家界面，具体结果请看图
                    componentName =
                        ComponentName.unflattenFromString("com.meizu.safe/.permission.PermissionMainActivity")
                } else if (mobileType == "OPPO") { // OPPO R8205测试通过
                    componentName =
                        ComponentName.unflattenFromString("com.oppo.safe/.permission.startup.StartupAppListActivity")
                } else if (mobileType == "ulong") { // 360手机 未测试
                    componentName = ComponentName(
                        "com.yulong.android.coolsafe",
                        ".ui.activity.autorun.AutoRunListActivity"
                    )
                }
                intent.component = componentName
                context.startActivity(intent)
            } catch (e: Exception) { //抛出异常就直接打开设置页面
                return false
            }
            return true
        }

        fun enableServiceSettings(context: Context) {
            toggleServiceSettings(context, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        }

        fun disableServiceSettings(context: Context) {
            toggleServiceSettings(context, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        }

        private fun toggleServiceSettings(context: Context, state: Int) {
            val receiver = ComponentName(context, NotificationsHandlerService::class.java)
            val pm = context.packageManager
            pm.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
        }

        fun updateFlutterEngine(context: Context) {
            Log.d(TAG, "call instance update flutter engine from plugin init ${instance}")
            instance?.updateFlutterEngine(context)
            // we need to `finish init` manually
            instance?.initFinish()
        }

        fun sendNotification(context: Context, map: Map<String, Any?>) {
            Log.d(TAG, "send call to flutter side immediately!")
            instance?.onPhoneStatePosted(context, map)
        }
    }

    private fun getFlutterEngine(context: Context): FlutterEngine {
        var eng = FlutterEngineCache.getInstance()
            .get(FlutterNotificationListenerPlugin.FLUTTER_ENGINE_CACHE_KEY)
        if (eng != null) return eng

        Log.i(TAG, "flutter engine cache is null, create a new one")
        eng = FlutterEngine(context)

        // ensure initialization
        FlutterInjector.instance().flutterLoader().startInitialization(context)
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(context, arrayOf())

        // call the flutter side init
        // get the call back handle information
        val cb = context.getSharedPreferences(
            FlutterNotificationListenerPlugin.SHARED_PREFERENCES_KEY,
            Context.MODE_PRIVATE
        )
            .getLong(FlutterNotificationListenerPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0)

        if (cb != 0L) {
            Log.d(TAG, "try to find callback: $cb")
            val info = FlutterCallbackInformation.lookupCallbackInformation(cb)
            val args = DartExecutor.DartCallback(
                context.assets,
                FlutterInjector.instance().flutterLoader().findAppBundlePath(), info
            )
            // call the callback
            eng.dartExecutor.executeDartCallback(args)
        } else {
            Log.e(TAG, "Fatal: no callback register")
        }

        FlutterEngineCache.getInstance()
            .put(FlutterNotificationListenerPlugin.FLUTTER_ENGINE_CACHE_KEY, eng)
        return eng
    }

    private fun updateFlutterEngine(context: Context) {
        Log.d(TAG, "update the flutter engine of service")
        // take the engine
        val eng = getFlutterEngine(context)
        sBackgroundFlutterEngine = eng

        // set the method call
        mBackgroundChannel = MethodChannel(eng.dartExecutor.binaryMessenger, BG_METHOD_CHANNEL_NAME)
        mBackgroundChannel.setMethodCallHandler(this)
    }

    private fun registerPhoneListener() {
        Log.d(TAG, "updatePhoneListener")
        if (phoneListenStarted) {
            return
        }
        phoneListenStarted = true
        phoneCallStateListener = PhoneCallStateListener(mContext)
        telephonyManager =
            mContext.getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(phoneCallStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun unregisterPhoneListener() {
        phoneListenStarted = false
        telephonyManager.listen(phoneCallStateListener, PhoneStateListener.LISTEN_NONE)
    }

    private fun startListenerService(context: Context) {
        Log.d(TAG, "start listener service")
        synchronized(sServiceStarted) {
            // promote to foreground
            // TODO: take from intent, currently just load form store
            promoteToForeground(Utils.PromoteServiceConfig.load(this))
            // we should to ee
            Log.d(TAG, "service's flutter engine is null, should update one")
            updateFlutterEngine(context)

            sServiceStarted.set(true)
        }
        Log.d(TAG, "service start finished")
    }

    private fun sendEvent(data: Map<String, Any?>) {
        Log.d(TAG, "send notification event: ${data}")
        if (callbackHandle == 0L) {
            callbackHandle = mContext.getSharedPreferences(
                FlutterNotificationListenerPlugin.SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE
            )
                .getLong(FlutterNotificationListenerPlugin.CALLBACK_HANDLE_KEY, 0)
        }

        // why mBackgroundChannel can be null?

        try {
            // don't care about the method name
            mBackgroundChannel.invokeMethod("sink_event", listOf(callbackHandle, data))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}

