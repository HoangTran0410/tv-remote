package dev.tvremote.phone

import dev.tvremote.phone.picker.message
import dev.tvremote.phone.scenes.Headless
import dev.tvremote.phone.ui.loadLang

import dev.tvremote.core.T
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

/**
 * Where the widget, the Quick Settings tiles and the app icon's shortcuts land. It shows nothing: it hands the job to
 * [SceneService] and closes. (Android lets a service start in the foreground only from something the person can see, and
 * this is that something.) Extras: "scene" an id, or "do": power, mute, vol_up, vol_down.
 */
class SceneActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val job = Intent(this, SceneService::class.java).putExtras(intent.extras ?: Bundle())
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(job) else startService(job)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

/** Runs one job on the TV ([Headless]) with a small notification, says how it went, and stops. */
class SceneService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        loadLang(this)
        val n = notification(T("scene_activity.sending_to_the_tv"))
        if (Build.VERSION.SDK_INT >= 34) startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE) else startForeground(3, n)
        if (busy) return START_NOT_STICKY                              // one job at a time: a second tap while one runs is dropped
        busy = true
        val scene = i?.getStringExtra("scene")
        val what = i?.getStringExtra("do") ?: ""
        Thread {
            val h = Headless(this)
            val said = try {
                when {
                    scene != null -> h.scene(scene)
                    what == "power" -> h.power()
                    what == "mute" -> h.key(164)
                    what == "vol_up" -> h.key(24)
                    what == "vol_down" -> h.key(25)
                    else -> null
                }
            } catch (e: Exception) { e.message }
            ui.post {
                if (said != null && !(said.startsWith("✓") && scene == null)) Toast.makeText(this, dev.tvremote.tvui.Marks.plain(said), Toast.LENGTH_SHORT).show()   // keys say nothing when they worked
                busy = false
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) { stopSelf() }                // Android 14's limit for a short job

    @Suppress("DEPRECATION")
    private fun notification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("jobs", T("scene_activity.commands_to_the_tv"), NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, "jobs")
        } else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_tv).setContentTitle("TV Remote").setContentText(text).build()
    }
}
