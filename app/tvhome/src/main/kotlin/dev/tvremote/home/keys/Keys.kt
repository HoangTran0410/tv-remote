package dev.tvremote.home.keys

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.R
import dev.tvremote.home.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote keys that open an app ("keyapps") and the app that opens when the TV is turned on ("autoapp"), both default off.
 * TV Home answers a key itself only on its own screen (colour keys and 0-9: [onKey]); anywhere else, and the power-on app, are
 * TV Setup's (its key filter, its wake-up hearing: TV Home is not running in standby), so the whole setting goes to it through the
 * HomeInfo provider ("keys", signed by this app's key) on every change and every start. What TV Setup says back (it is there,
 * which keys are dead on this TV) is kept for the setting's words and for the clients' page.
 */
object Keys {
    private val ui = Handler(Looper.getMainLooper())
    var onState: (() -> Unit)? = null                                  // the settings panel, while it is open: the hint under the switch

    private fun prefs(c: Context) = c.getSharedPreferences("home_keys", Context.MODE_PRIVATE)

    /** The power-on app, "" for none. Read and written only here: a tile's menu turned it off by "autoon" alone while Quick keys read "autoapp" alone, and showed an app that no longer opened. */
    fun powerApp(conf: JSONObject): String = if (conf.optBoolean("autoon")) conf.optString("autoapp") else ""
    fun setPowerApp(c: Context, target: String) { Store.change(c, JSONObject().put("autoapp", target).put("autoon", target.isNotEmpty()).put("autolast", false)) }
    /** Power-on opens the app that was open when the TV turned off (HomeConfig autolast); a fixed app and this are one choice. */
    fun powerLast(conf: JSONObject): Boolean = conf.optBoolean("autoon") && conf.optBoolean("autolast")
    fun setPowerLast(c: Context) { Store.change(c, JSONObject().put("autoapp", "").put("autoon", true).put("autolast", true)) }

    /** Tells TV Setup the setting as it is now and keeps its answer; on a thread of its own (a binder call to another app). */
    /** TV Setup lets the remote's mapped keys through to the screen while a page that assigns them is open (an older one: they open their apps as ever). */
    fun capture(c: Context, on: Boolean) {
        Thread({ try { c.contentResolver.call(android.net.Uri.parse(HomeConfig.USAGE), "capture", if (on) "1" else "0", null) } catch (e: Exception) { } }, "home-capture").apply { isDaemon = true }.start()
    }

    fun push(c: Context) {
        val app = c.applicationContext
        val conf = Store.get(app)
        val arg = JSONObject().also { o -> for (k in listOf("keyon", "keyapps", "autoon", "autoapp", "autoall", "autolast", "homebar")) o.put(k, conf.get(k)) }.toString()
        Thread({
            val said = try { app.contentResolver.call(Uri.parse(HomeConfig.USAGE), "keys", arg, null)?.getString("json")?.let { JSONObject(it) } } catch (e: Exception) { null }
            // null: no TV Setup, or one from before this (it answers nothing to a word it does not know)
            prefs(app).edit().putBoolean("setup", said != null).putString("free", (said?.optJSONArray("free") ?: JSONArray()).toString())
                .putBoolean("can", said?.optBoolean("can") ?: false).putBoolean("all", said?.optBoolean("all") ?: false).putBoolean("last", said?.optBoolean("last") ?: false).apply()
            ui.post { onState?.invoke() }
        }, "home-keys").apply { isDaemon = true }.start()
    }

    /** What the last answer said, for the config the clients read: {keysOk, keysSetup, keysDead [codes]}. */
    fun report(c: Context, conf: JSONObject): JSONObject {
        val p = prefs(c)
        return conf.put("keysOk", true).put("keysSetup", p.getBoolean("setup", false)).put("keysAll", p.getBoolean("all", false)).put("keysLast", p.getBoolean("last", false))
            .put("keysDead", try { JSONArray(p.getString("free", "[]")) } catch (e: Exception) { JSONArray() })
    }

    fun setup(c: Context) = prefs(c).getBoolean("setup", false)
    /** TV Setup may run its key filter (the grant over ADB): without it the keys work on TV Home's screen only. */
    fun can(c: Context) = prefs(c).getBoolean("can", false)
    /** TV Setup takes "autoall" (power-on opens its app even over the one the TV woke into): an older one ignores it. */
    fun all(c: Context) = prefs(c).getBoolean("all", false)
    /** TV Setup takes "autolast" (the power-on app is the last one used): an older one ignores it, so the choice is not offered. */
    fun last(c: Context) = prefs(c).getBoolean("last", false)

    /** The dead keys TV Setup found on this TV, which a person may give an app. */
    fun dead(c: Context): List<Int> = try { JSONArray(prefs(c).getString("free", "[]")).let { a -> List(a.length()) { a.getInt(it) } } } catch (e: Exception) { emptyList() }

    /** A key pressed on TV Home's own screen: opens its app when one is chosen and installed. True = handled. Numbers and colours only. */
    fun onKey(a: Activity, e: KeyEvent): Boolean {
        val code = e.keyCode
        val tcl = TclKeys.apps(code, android.os.Build.MANUFACTURER, android.os.Build.BRAND)
        if (tcl.isNotEmpty()) {                                        // a TCL app button nobody else answered (launcherx off)
            if (e.repeatCount > 0) return true
            return open(a, tcl.firstOrNull { Targets.there(a, it) } ?: return true)   // none installed: eaten, as the stock home did
        }
        if (code !in HomeConfig.KEY_COLOURS && code !in HomeConfig.KEY_NUMBERS) return false
        val conf = Store.get(a)
        if (!conf.optBoolean("keyon")) return false
        val target = HomeConfig.keyApps(conf.optJSONObject("keyapps"))[code] ?: return false
        if (e.repeatCount > 0) return true                             // a held key opens it once
        return open(a, target)
    }

    private fun open(a: Activity, target: String): Boolean {
        if (dev.tvremote.home.apps.HomeLock.isLocked(a, target)) {      // locked by TV Home itself: the PIN pad first
            (a as? dev.tvremote.home.HomeActivity)?.askPin(Targets.label(a, target)) { Targets.open(a, target, null, true) }
            return true
        }
        return Targets.open(a, target)
    }

    /**
     * The keys of [HomeConfig.KEY_COLOURS] / [HomeConfig.KEY_NUMBERS] a remote here has, as the codes: the union over the keyboards that
     * are not virtual (the TV's own buttons and a paired remote say what they can send, InputDevice.hasKeys). Null = not known (no
     * device answered): the screens then leave every key on rather than greying a working one.
     */
    fun have(c: Context): JSONArray? {
        val codes = (HomeConfig.KEY_COLOURS + HomeConfig.KEY_NUMBERS).toIntArray()
        val has = BooleanArray(codes.size)
        var seen = false
        for (id in android.view.InputDevice.getDeviceIds()) {
            val d = android.view.InputDevice.getDevice(id) ?: continue
            if (d.isVirtual || d.sources and android.view.InputDevice.SOURCE_KEYBOARD != android.view.InputDevice.SOURCE_KEYBOARD) continue
            seen = true
            val got = d.hasKeys(*codes)
            for (i in codes.indices) if (got[i]) has[i] = true
        }
        return if (!seen) null else JSONArray(codes.filterIndexed { i, _ -> has[i] })
    }

    /** The key's words in the setting: "Red", "Green"... a number as itself, others by what the stock remote called them. */
    fun name(a: Context, code: Int): String {
        val id = when (code) {
            183 -> R.string.k_red; 184 -> R.string.k_green; 185 -> R.string.k_yellow; 186 -> R.string.k_blue
            170 -> R.string.k_tv; 178 -> R.string.k_input; 190 -> R.string.k_btn3; 191 -> R.string.k_btn4; 195 -> R.string.k_btn8
            192 -> R.string.k_btn5; 284 -> R.string.k_allapps; 176 -> R.string.k_settings
            else -> return (code - KeyEvent.KEYCODE_0).toString()
        }
        return a.getString(id)
    }
}
