package dev.tvremote.setup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import dev.tvremote.core.ontv.Welcome
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject

/**
 * The welcome screen's settings and file, kept here on the TV (prefs "welcome", the file in `welcome/` of this app's folder on shared
 * storage, where the shell of a phone or computer can write and this app reads with no permission). What is shown and when
 * is decided by [dev.tvremote.core.ontv.Welcome]; this is the TV's side: the file, the settings, the picture of it, and starting
 * [WelcomeActivity] when the TV starts ([BootReceiver]) or is turned on ([WelcomeWatch]).
 */
internal object WelcomeState {
    private const val PREFS = "welcome"
    private const val TAG = "Welcome"

    @Synchronized
    fun load(c: Context): JSONObject = try {
        JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    @Synchronized
    private fun save(c: Context, o: JSONObject) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", o.toString()).commit() }

    fun dir(c: Context): File? = c.getExternalFilesDir(null)?.let { File(it, "welcome") }

    /** The file that is shown, or null when none was set (or it was deleted from outside). */
    fun file(c: Context): File? {
        val name = load(c).optString("file")
        return if (name.isEmpty()) null else dir(c)?.let { File(it, name) }?.takeIf { it.isFile }
    }

    /** The settings with what a page needs; "has" is true only while the file is really there. */
    fun settings(c: Context): JSONObject {
        val o = load(c)
        val f = file(c)
        o.put("has", f != null).put("when", Welcome.whenOf(o.opt("when"))).put("seconds", Welcome.seconds(o.opt("seconds")))
        if (f == null) for (k in listOf("kind", "name", "size", "duration")) o.remove(k)
        return o
    }

    /** The service has to run to hear the TV being turned on: a welcome that is set and on counts like a timer ([Rules.active]). */
    fun active(c: Context): Boolean = settings(c).let { it.optBoolean("on") && it.optBoolean("has") }

    private fun canShow(c: Context) = android.os.Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)

    /** What the pages read: the settings, whether the TV lets this app come up over the others, and whether the service runs. */
    fun state(c: Context): JSONObject =
        settings(c).put("overlay", canShow(c)).put("running", RemoteService.isRunning()).put("wanted", RemoteService.wanted(c))

    /** One change from a page (see [Welcome.apply] and "adopt", "show", "thumb"). The answer is [state]. */
    @Synchronized
    fun change(c: Context, d: JSONObject): JSONObject {
        when (d.optString("do")) {
            "adopt" -> adopt(c, d.optString("ext"), d.optString("original"))
            "show" -> if (!show(c, false)) throw IllegalArgumentException("welcome:nothing")
            "remove" -> {
                val o = load(c)
                Welcome.apply(o, d)
                o.remove("file")
                save(c, o)
                dir(c)?.listFiles()?.forEach { it.delete() }
            }
            "thumb" -> return state(c).put("thumb", thumb(c) ?: "")
            else -> {
                val o = load(c)
                if (Welcome.apply(o, d)) save(c, o)
            }
        }
        return state(c)
    }

    /**
     * The file a page has just put in this app's folder as incoming.<ext> becomes the welcome: checked (a picture that decodes, a
     * video that has a length), the old one deleted, a small picture of it made for the pages, and the welcome switched on.
     */
    private fun adopt(c: Context, ext: String, original: String) {
        val d = dir(c) ?: throw IllegalArgumentException("welcome:nofolder")
        val kind = Welcome.kind("x.$ext") ?: throw IllegalArgumentException("welcome:kind")
        val incoming = File(d, "incoming.$ext")
        if (!incoming.isFile || incoming.length() <= 0) throw IllegalArgumentException("welcome:unreadable")   // the shell wrote where this app cannot see
        if (incoming.length() > Welcome.MAX_BYTES) { incoming.delete(); throw IllegalArgumentException("welcome:toobig") }
        var duration = 0L
        var thumb: Bitmap? = null
        try {
            if (kind == "image") {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(incoming.path, b)
                if (b.outWidth <= 0) throw IllegalArgumentException("welcome:unreadable")
                thumb = BitmapFactory.decodeFile(incoming.path, BitmapFactory.Options().apply { inSampleSize = sample(b.outWidth, 960) })
                    ?.let { dev.tvremote.core.Upright.apply(it, dev.tvremote.core.Upright.read(incoming.path)) }   // the page's preview as the TV shows it
            } else {
                val m = MediaMetadataRetriever()
                try {
                    m.setDataSource(incoming.path)
                    duration = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    thumb = m.getFrameAtTime(minOf(1_000_000L, duration * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (duration <= 0 || m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null) throw IllegalArgumentException("welcome:unreadable")
                } finally { try { m.release() } catch (e: Exception) { } }
            }
        } catch (e: IllegalArgumentException) { incoming.delete(); throw e
        } catch (e: Exception) { incoming.delete(); throw IllegalArgumentException("welcome:unreadable") }
        d.listFiles()?.forEach { if (it.name != incoming.name) it.delete() }
        val target = File(d, "welcome.$ext")
        if (!incoming.renameTo(target)) { incoming.delete(); throw IllegalArgumentException("welcome:nofolder") }
        if (thumb != null) try {
            val w = 480
            val s = if (thumb.width > w) Bitmap.createScaledBitmap(thumb, w, maxOf(1, thumb.height * w / thumb.width), true) else thumb
            File(d, "thumb.jpg").outputStream().use { s.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) { }
        val o = load(c)
        o.put("file", target.name).put("kind", kind).put("name", original.take(120).ifEmpty { target.name }).put("size", target.length())
            .put("at", System.currentTimeMillis()).put("duration", duration / 1000)
        if (!o.has("on")) o.put("on", true)                              // set a file for the first time: it is meant to work
        save(c, o)
    }

    private fun sample(w: Int, max: Int): Int { var s = 1; while (w / (s * 2) >= max) s *= 2; return s }

    /** The small picture of the welcome as base64 JPEG, for the pages to preview what is set; null when there is none. */
    fun thumb(c: Context): String? {
        val f = dir(c)?.let { File(it, "thumb.jpg") }?.takeIf { it.isFile } ?: return null
        return Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- when it is shown
    private var lastBoot = -1                                            // which run of the TV [lastAt] belongs to: the clock restarts with it
    private var lastAt = 0L

    private fun bootCount(c: Context): Int = try { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT) } catch (e: Exception) { 0 }

    /**
     * The TV started ("boot") or was turned on from standby ("on"): shows the welcome if [Welcome.due] says so. True when it was started.
     * [lastAt] is kept in prefs too, with the boot count it belongs to: a process killed in standby must not forget it just shown.
     */
    @Synchronized
    fun trigger(c: Context, event: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = bootCount(c)
        if (lastBoot < 0) { lastBoot = p.getInt("last_boot", -1); lastAt = p.getLong("last_at", 0L) }
        val ago = if (lastBoot == boot) now - lastAt else null
        if (!Welcome.due(event, settings(c), ago)) return false
        if (!show(c, true)) return false
        lastBoot = boot; lastAt = now
        p.edit().putInt("last_boot", boot).putLong("last_at", now).apply()
        return true
    }

    /** Starts [WelcomeActivity] ([home]: the TV goes to its home screen when it ends, rather than back to what it was on). */
    fun show(c: Context, home: Boolean): Boolean {
        if (file(c) == null) return false
        if (!canShow(c)) Log.w(TAG, "TV Setup may not draw over other apps: the TV may refuse to start the welcome from the background")
        return try {
            c.startActivity(Intent(c, WelcomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("home", home))
            true
        } catch (e: Exception) { Log.w(TAG, "could not start: $e"); false }
    }
}

/** The shell's way to [WelcomeState] (Welcome.call in the shared code): only the shell may send it (the manifest). Extra "set": one change as base64 JSON; none reads. */
class WelcomeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val answer = try {
            val set = i.getStringExtra("set")
            val change = if (set.isNullOrEmpty()) JSONObject() else JSONObject(String(Base64.decode(set, Base64.DEFAULT), Charsets.UTF_8))
            val state = WelcomeState.change(c, change)
            if (WelcomeState.active(c) && !RemoteService.isRunning()) {
                try { RemoteService.start(c, true) } catch (e: Exception) { }   // may be refused from the background: Welcome.call starts it through the shell then
            }
            state.put("running", RemoteService.isRunning())
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
        resultCode = Activity.RESULT_OK
        resultData = Base64.encodeToString(answer.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}

/**
 * Hears the TV being turned on from standby (the screen turning on) and shows the welcome. From RemoteService, like [KeepAwake]:
 * a manifest receiver cannot hear this broadcast. The TV starting is [BootReceiver]'s.
 */
internal class WelcomeWatch(private val c: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private val screen = object : BroadcastReceiver() {
        // a moment after: the screen is only just up, and the home screen the TV brings up with it must not land on top of the welcome
        override fun onReceive(ctx: Context, i: Intent) { ui.postDelayed({ WelcomeState.trigger(c, "on") }, 600) }
    }

    fun start() { c.registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_ON)) }
    fun stop() { try { c.unregisterReceiver(screen) } catch (e: Exception) { }; ui.removeCallbacksAndMessages(null) }
}
