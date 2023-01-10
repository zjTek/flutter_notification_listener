package im.zoe.labs.flutter_notification_listener_example

import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity

class MainActivity: FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)
        Log.d("AA","On Create ===================")
    }
}
