package dev.tvremote.phone

import dev.tvremote.phone.scenes.Headless
import dev.tvremote.phone.ui.loadLang

import dev.tvremote.core.T
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The keys of the remote in the notification, and when it goes away: no Android here, so the unit tests read it as it is.
 */
internal object NotificationKeys {
    class Key(val id: String, val code: Int, val icon: Int, val words: String)

    const val POWER = 26
    /** Volume and play / pause first: the three the folded notification shows; Mute and Power when it is pulled open. */
    val ALL = listOf(
        Key("vol_down", 25, R.drawable.ic_vol_down, "remote.volume_down"),
        Key("play_pause", 85, R.drawable.ic_play_pause, "rkey.playpause"),
        Key("vol_up", 24, R.drawable.ic_vol_up, "remote.volume_up"),
        Key("mute", 164, R.drawable.ic_mute, "rkey.mute"),
        Key("power", POWER, R.drawable.ic_power, "remote.power"),
    )
    val COMPACT = intArrayOf(0, 1, 2)

    /** The connection is closed this long after the last press: an open one keeps the Wi-Fi and the TV's side awake;
     *  opening it again costs about a second on the next press. */
    const val LINK_IDLE_MS = 2 * 60_000L
    /** The notification goes this long after the last press (and the app's opening brings it back): about a film, so a
     *  pause in the middle still finds it. With no connection open it costs nothing (no thread runs, no wake lock, no
     *  socket), so this only keeps a remote nobody uses from staying on the lock screen for days. */
    const val STOP_IDLE_MS = 120 * 60_000L

    fun of(id: String?): Key? = ALL.firstOrNull { it.id == id }
    fun linkIdle(lastPress: Long, now: Long) = now - lastPress >= LINK_IDLE_MS
    fun stopIdle(lastPress: Long, now: Long) = now - lastPress >= STOP_IDLE_MS
    /** On in Settings and at least one saved TV ([ips]: the saved list, comma separated). */
    fun wanted(on: Boolean, ips: String) = on && ips.split(",").any { it.isNotEmpty() }
}

/**
 * The remote in the notification shade and on the lock screen (Settings, off by default): Vol−, Play / Pause, Vol+, Mute,
 * Power for the TV used last, its name as the title, a tap opens the app. A key goes the remote screen's way when that
 * screen is live on the same TV ([screen]); else through [Headless], as the widget's, on a connection opened at the first
 * press and kept for [NotificationKeys.LINK_IDLE_MS]. It ends itself, and its connection, when the switch is turned off,
 * the list of TVs is empty, or after [NotificationKeys.STOP_IDLE_MS] without a press. No MediaSession: it would take the
 * phone's own media buttons (a headset's) for the TV.
 */
class NotificationRemote : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private val headless by lazy { Headless(this) }
    private var work: ExecutorService? = null
    @Volatile private var held: Headless.Held? = null
    @Volatile private var lastPress = 0L
    private var started = false
    private var shownName: String? = null
    private val watch = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k == PREF || k == "ips" || k?.startsWith("name:") == true) refresh()
    }
    private val dropLink = Runnable { work?.execute { if (NotificationKeys.linkIdle(lastPress, SystemClock.elapsedRealtime())) closeHeld() } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadLang(this)
        prefs = getSharedPreferences("tvs", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, T("notification_remote.channel"), NotificationManager.IMPORTANCE_LOW).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC; setShowBadge(false)
                })
        prefs.registerOnSharedPreferenceChangeListener(watch)
        work = Executors.newSingleThreadExecutor()                  // presses in order, one at a time
        lastPress = SystemClock.elapsedRealtime()
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (!started) {                                               // once: started again from the background, only notify
            started = true
            val n = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(ID, n)
        }
        if (!wanted()) { stopSelf(); return START_NOT_STICKY }
        val a = i?.action ?: ""
        when {
            a.startsWith(KEY) -> NotificationKeys.of(a.removePrefix(KEY))?.let { press(it) }
            a == IDLE -> if (NotificationKeys.stopIdle(lastPress, SystemClock.elapsedRealtime())) stopSelf() else idleAlarm()
            a == GONE -> stopSelf()                                   // swiped away (Android 14+ lets an ongoing one go)
            else -> { lastPress = SystemClock.elapsedRealtime(); idleAlarm() }   // started, or the app opened: counted as a use
        }
        return START_NOT_STICKY
    }

    private fun wanted() = NotificationKeys.wanted(prefs.getBoolean(PREF, false), prefs.getString("ips", "") ?: "")

    private fun press(k: NotificationKeys.Key) {
        lastPress = SystemClock.elapsedRealtime()
        idleAlarm()
        ui.removeCallbacks(dropLink)
        work?.execute {
            send(k)
            ui.postDelayed(dropLink, NotificationKeys.LINK_IDLE_MS)
        }
    }

    /** On the worker: the remote screen when it is live on this TV, else the held connection (opened, or opened again once). */
    private fun send(k: NotificationKeys.Key) {
        if (screen?.invoke(k.code) == true) { closeHeld(); return }
        var h = held
        if (h != null && (h.ip != headless.first() || !h.alive())) { closeHeld(); h = null }   // another TV picked, or the link died
        for (attempt in 0..1) {
            if (h == null) h = try { headless.open().also { held = it } } catch (e: Headless.Failed) { say(e.message ?: ""); return }
            try {
                if (k.code == NotificationKeys.POWER) Headless.power(h.tv, h.adb) else h.tv.key(k.code)
                return
            } catch (e: Exception) {
                closeHeld(); val name = h.name; h = null
                if (attempt == 1) say(name + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun closeHeld() { held?.close(); held = null }

    private fun say(words: String) = ui.post { if (words.isNotEmpty()) Toast.makeText(this, dev.tvremote.tvui.Marks.plain(words), Toast.LENGTH_SHORT).show() }

    /** The TV's name or the switch changed (prefs, on the UI thread): a new title, or the end. */
    private fun refresh() {
        if (!wanted()) { stopSelf(); return }
        if (headless.tvName() != shownName) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification())
    }

    private fun idlePending() = PendingIntent.getService(this, 2, Intent(this, NotificationRemote::class.java).setAction(IDLE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Not a wakeup alarm: it fires when the phone is next awake, which is when a notification can be seen anyway. */
    private fun idleAlarm() {
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.ELAPSED_REALTIME, lastPress + NotificationKeys.STOP_IDLE_MS, idlePending())
    }

    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        val name = headless.tvName()
        shownName = name
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val gone = PendingIntent.getService(this, 3, Intent(this, NotificationRemote::class.java).setAction(GONE), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_remote).setContentTitle(name.ifEmpty { "TV Remote" }).setContentIntent(open).setDeleteIntent(gone)
            .setOngoing(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW)
        NotificationKeys.ALL.forEachIndexed { n, k ->
            val pi = PendingIntent.getService(this, 10 + n, Intent(this, NotificationRemote::class.java).setAction(KEY + k.id), flags)
            b.addAction(if (Build.VERSION.SDK_INT >= 23) Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, k.icon), T(k.words), pi).build()
                else Notification.Action.Builder(k.icon, T(k.words), pi).build())   // the Icon constructor is 23+
        }
        b.setStyle(Notification.MediaStyle().setShowActionsInCompactView(*NotificationKeys.COMPACT))
        return b.build()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(watch)
        ui.removeCallbacks(dropLink)
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(idlePending())
        work?.let { w -> w.execute { closeHeld() }; w.shutdown() }    // the connection closes after a press under way
        work = null
        synchronized(Companion) { running = false }
        super.onDestroy()
    }

    companion object {
        const val PREF = "notifyRemote"                              // Settings' switch, in the "tvs" prefs
        const val ASK = 33                                            // requestPermissions' code (31 Quick talk, 32 the camera)
        private const val ID = 5
        private const val CHANNEL = "remote"
        private const val KEY = "dev.tvremote.phone.NOTIFY_KEY."
        private const val IDLE = "dev.tvremote.phone.NOTIFY_IDLE"
        private const val GONE = "dev.tvremote.phone.NOTIFY_GONE"
        private var running = false                                   // asked to start and not destroyed since: under the Companion's lock

        /** Set by the remote screen while it lives: sends the key the screen's way and says true when its connection is live on this TV. */
        @Volatile var screen: ((Int) -> Boolean)? = null

        /** Android 13+ shows nothing without it, so the service is not started without it. */
        fun allowed(c: Context) = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /**
         * Starts it when the switch is on, a TV is saved and notifications are allowed; from a screen the person sees (the
         * app's), as Android 12+ wants. Turning it off needs nothing here: the service watches the switch and ends itself.
         */
        fun sync(c: Context) {
            val p = c.getSharedPreferences("tvs", Context.MODE_PRIVATE)
            if (!NotificationKeys.wanted(p.getBoolean(PREF, false), p.getString("ips", "") ?: "") || !allowed(c)) return
            val up = synchronized(Companion) { running.also { running = true } }
            try {
                val i = Intent(c, NotificationRemote::class.java)
                if (up) c.startService(i)                             // already shown: the app's opening counts as a use
                else if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) { if (!up) synchronized(Companion) { running = false } }
        }
    }
}
