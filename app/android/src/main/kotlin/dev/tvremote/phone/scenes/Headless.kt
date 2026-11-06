package dev.tvremote.phone.scenes

import dev.tvremote.phone.picker.message
import dev.tvremote.phone.picker.adbPortUp
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.picker.reachAroundVpn

import dev.tvremote.core.T
import android.content.Context
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.remote.Atv
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.remote.RemoteSteps
import dev.tvremote.core.scenes.Scenes
import org.json.JSONArray

/**
 * Doing something on the TV without the remote screen: for the widget, the Quick Settings tiles, the app icon's shortcuts and
 * the remote in the notification (NotificationRemote.kt). The TV is the one used last (first in the saved list), reached
 * through whichever of its addresses answers: its saved one, its Tailscale IP, its home IP, as the TV list does. ADB when it
 * lets us in; else the keys-only connection, when this phone is paired with it (keys and apps work there; volume levels and
 * timers need ADB).
 */
internal class Headless(private val c: Context) {
    private val prefs = c.getSharedPreferences("tvs", Context.MODE_PRIVATE)
    init { c.reachAroundVpn() }
    private fun pref(k: String) = prefs.getString(k, "") ?: ""

    /** The saved TV used last (first in the list), "" when none. */
    fun first(): String = pref("ips").split(",").firstOrNull { it.isNotEmpty() } ?: ""

    /** The TV's name for the words shown, and "" when none is saved. */
    fun tvName(): String = first().let { if (it.isEmpty()) "" else pref("name:$it").ifEmpty { it } }

    /** A connection to the TV, open until [close]: [run] closes it at once, the notification remote keeps it between presses. */
    internal class Held(val ip: String, val name: String, val tv: Scenes.Target, val adb: Boolean, private val up: () -> Boolean, private val end: () -> Unit) {
        fun alive(): Boolean = try { up() } catch (e: Exception) { false }
        fun close() { try { end() } catch (ignored: Exception) { } }
    }

    /** Why [open] could not reach the TV, in words to show as they are. */
    internal class Failed(words: String) : Exception(words)

    /**
     * Opens the TV used last through whichever of its addresses answers. Blocks for up to about 15 s (longer means the TV
     * is asking "Allow USB debugging?"). Throws [Failed].
     */
    fun open(): Held {
        val ip = first().ifEmpty { throw Failed(T("headless.no_tv_yet_open")) }
        val name = pref("name:$ip").ifEmpty { ip }
        val via = listOf(ip, pref("ts:$ip"), pref("lan:$ip")).filter { it.isNotEmpty() }.distinct()
        val kind = Remote.Kind.of(pref("kind:$ip"))
        val gone = { a: Remote -> Thread.sleep(400); a.close() }     // the last key leaves before the connection closes
        if (kind != Remote.Kind.ANDROID) {                            // LG / Samsung: their own remote link, already allowed on the TV
            val host = via.firstOrNull { h -> Remote.Kind.ports(kind).any { open(h, it, 1500) } }
                ?: throw Failed(T("headless.cannot_reach_the_tv", "name" to name))
            val a = Remote.of(kind, c.filesDir, host)
            try {
                a.connect { }
                for (i in 0 until 20) { if (a.ready()) break; Thread.sleep(100) }
            } catch (e: Remote.Refused) { a.close(); throw Failed(T("headless.the_tv_said_no", "name" to name))
            } catch (e: Exception) { a.close(); throw Failed(name + ": " + (e.message ?: e.javaClass.simpleName)) }
            return Held(ip, name, RemoteSteps(a) { }, false, { a.ready() }, { gone(a) })
        }
        dev.tvremote.core.adb.AdbPort.load(c.filesDir)                // a port typed with the address, also when the app is not open
        via.firstOrNull { adbPortUp(it, 2000) > 0 }?.let { host ->
            val link = AdbLink(c.filesDir, host)
            var failed = true
            var done = false
            val worker = Thread { failed = try { link.get(); false } catch (e: Exception) { true }; done = true }
            worker.start()
            worker.join(15000)                                        // connecting: longer means the TV is asking
            if (!done) {
                val asking = link.waitingForUser()
                link.close()
                throw Failed(T(if (asking) "headless.the_tv_is_asking" else "headless.the_tv_did_not", "name" to name))
            }
            if (!failed) return Held(ip, name, Scenes.Shell({ link.get().shell(it) }), true, { true }, { link.close() })
            link.close()                                              // ADB said no: the keys-only connection, if paired
        }
        if (prefs.getBoolean("atv:$ip", false)) via.firstOrNull { open(it, 6466, 1500) }?.let { host ->
            val a = Atv(c.filesDir, host)
            try {
                a.connect { }
                for (i in 0 until 20) { if (a.ready()) break; Thread.sleep(100) }
            } catch (e: Exception) { a.close(); throw Failed(name + ": " + (e.message ?: e.javaClass.simpleName)) }
            return Held(ip, name, Keys(a), false, { a.ready() }, { gone(a) })
        }
        throw Failed(T("headless.cannot_reach_the_tv", "name" to name))
    }

    /** Runs [what] on the TV; the answer is what to tell the person. Blocks for up to about 15 s, longer for a scene that waits. */
    fun run(what: (Scenes.Target, Boolean) -> String?): String {
        val h = try { open() } catch (e: Failed) { return e.message ?: "" }
        var said: String? = null
        val worker = Thread {
            said = try { what(h.tv, h.adb)?.let { "${h.name}: $it" } ?: "✓ ${h.name}" }
            catch (e: Remote.Refused) { T("headless.the_tv_said_no", "name" to h.name) }
            catch (e: Exception) { h.name + ": " + (e.message ?: e.javaClass.simpleName) }
        }
        worker.start()
        worker.join(120000)                                           // connected: a scene's waits take as long as they take
        h.close()
        return said ?: T("headless.the_tv_did_not", "name" to h.name)
    }

    /** A scene by id. */
    fun scene(id: String): String {
        val sc = SceneStore.find(c, id) ?: return T("headless.this_scene_is_gone")
        return run { tv, _ -> Scenes.run(sc.optJSONArray("steps") ?: JSONArray(), tv) }.let { if (it.startsWith("✓")) "✓ " + sc.optString("name") else it }
    }

    /** Standby when the TV is on, on when it is in standby: [Companion.power]. */
    fun power(): String = run { tv, adb -> power(tv, adb); null }

    fun key(code: Int): String = run { tv, _ -> tv.key(code); null }

    /** The keys-only connection as a scene target: keys and links; the rest needs ADB. */
    private class Keys(private val a: Atv) : Scenes.Target {
        override fun key(code: Int) { if (code in 19..23) a.pulse(code, 110) else a.key(code, Atv.SHORT) }
        override fun app(pkg: String) { a.launch(pkg) }
        override fun link(url: String) { a.launch(url) }
        override fun volume(level: Int) { throw IllegalArgumentException(T("headless.setting_the_volume_needs")) }
        override fun timer(minutes: Int) { throw IllegalArgumentException(T("headless.the_timer_needs_adb")) }
    }

    companion object {
        /** The keys-only connection as a scene target, for the remote screen too. */
        fun keysOnly(a: Atv): Scenes.Target = Keys(a)

        /** Standby when the TV is on, on when it is in standby (ADB can tell; over the keys-only connection the TV's own power key decides). */
        fun power(tv: Scenes.Target, adb: Boolean) {
            if (adb && tv is Scenes.Shell) {
                val awake = !(tv.sh("dumpsys power | grep -m1 mWakefulness=")).contains("Asleep")
                tv.key(if (awake) 223 else 224)
            } else tv.key(26)
        }
    }
}
