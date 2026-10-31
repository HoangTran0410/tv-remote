package dev.tvremote.phone

import dev.tvremote.phone.media.say
import dev.tvremote.phone.picker.reachAroundVpn
import dev.tvremote.phone.ui.loadLang
import dev.tvremote.phone.ui.note
import dev.tvremote.phone.ui.say

import dev.tvremote.core.T
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * While the Manage screen copies a file to the TV, installs an app or updates TV Setup: a notification, and Android lets the
 * app run on in the background (switching to another app would otherwise freeze it and cut the copy halfway). Only closing
 * the app itself ends the work.
 */
class WorkService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadLang(this)                                                // the notification's words
        val n = note(this, "")
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        synchronized(Companion) { up = true; if (stopAsked) { stopAsked = false; stopSelf() } }   // the work ended before this ran: see [stop]
        reachAroundVpn()
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvremote:work")
            .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }   // an hour at most, whatever happens
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        i?.getStringExtra(WHAT)?.let { show(this, it) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(Companion) { up = false }
        wake?.release()
        super.onDestroy()
    }

    companion object {
        private const val ID = 5
        const val WHAT = "what"
        private var up = false                                        // past startForeground; both under the Companion's lock
        private var stopAsked = false

        /** Asked by startForegroundService(): ends it. A stopService() before its onCreate ran makes Android 12+ throw "did not then call
         *  startForeground" (census 50c2bc6e, a job over in a moment), so a service not up yet stops itself once it is. */
        fun stop(c: Context) = synchronized(Companion) { if (up) c.stopService(Intent(c, WorkService::class.java)) else stopAsked = true }

        /** Before startForegroundService(): a stop asked of an earlier start that never came up does not end this one. */
        fun starting() = synchronized(Companion) { stopAsked = false }

        /** In words, what Manage.working() names. */
        fun say(what: String): String = when (what) {
            "upload" -> T("work_service.copying_a_file_to")
            "install" -> T("work_service.installing_an_app_on")
            "update" -> T("work_service.updating_tv_setup")
            "tailscale" -> T("work_service.setting_up_tailscale_on")
            else -> T("work_service.working_on_the_tv")
        }

        /** [pct] 0..100, or below 0 when the work does not tell (a bar that only moves). */
        fun show(c: Context, what: String, pct: Int = -1) {
            try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, note(c, what, pct)) } catch (ignored: Exception) { }
        }

        @Suppress("DEPRECATION")
        private fun note(c: Context, what: String, pct: Int = -1): Notification {
            val b = if (Build.VERSION.SDK_INT >= 26) {
                (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("work", T("work_service.work_on_the_tv"), NotificationManager.IMPORTANCE_LOW))
                Notification.Builder(c, "work")
            } else Notification.Builder(c)
            val open = PendingIntent.getActivity(c, 3, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return b.setSmallIcon(R.drawable.ic_tv).setContentTitle(say(what) + (if (pct >= 0) " · $pct%" else "..."))
                .setContentText(T("work_service.goes_on_while_you"))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, pct.coerceAtLeast(0), pct < 0).build()
        }
    }
}
