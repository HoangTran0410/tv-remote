package dev.tvremote.phone.scenes

import dev.tvremote.phone.picker.message
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
 * Doing something on the TV without the remote screen: for the widget, the Quick Settings tiles and the app icon's shortcuts.
 * The TV is the one used last (first in the saved list), reached through whichever of its addresses answers: its saved one,
 * its Tailscale IP, its home IP, as the TV list does. ADB when it lets us in; else the keys-only connection, when this phone
 * is paired with it (keys and apps work there; volume levels and timers need ADB).
 */
internal class Headless(private val c: Context) {
    private val prefs = c.getSharedPreferences("tvs", Context.MODE_PRIVATE)
    init { c.reachAroundVpn() }
    private fun pref(k: String) = prefs.getString(k, "") ?: ""

    /** The TV's name for the words shown, and "" when none is saved. */
    fun tvName(): String = pref("ips").split(",").firstOrNull { it.isNotEmpty() }?.let { pref("name:$it").ifEmpty { it } } ?: ""

    /** Runs [what] on the TV; the answer is what to tell the person. Blocks for up to about 15 s, longer for a scene that waits. */
    fun run(what: (Scenes.Target, Boolean) -> String?): String {
        val ip = pref("ips").split(",").firstOrNull { it.isNotEmpty() } ?: return T("headless.no_tv_yet_open")
        val name = pref("name:$ip").ifEmpty { ip }
        val via = listOf(ip, pref("ts:$ip"), pref("lan:$ip")).filter { it.isNotEmpty() }.distinct()
        val kind = Remote.Kind.of(pref("kind:$ip"))
        if (kind != Remote.Kind.ANDROID) {                            // LG / Samsung: their own remote link, already allowed on the TV
            val host = via.firstOrNull { h -> Remote.Kind.ports(kind).any { open(h, it, 1500) } }
                ?: return T("headless.cannot_reach_the_tv", "name" to name)
            val a = Remote.of(kind, c.filesDir, host)
            return try {
                a.connect { }
                for (i in 0 until 20) { if (a.ready()) break; Thread.sleep(100) }
                val e = what(RemoteSteps(a) { }, false)
                Thread.sleep(400)                                     // the last key leaves before the connection closes
                if (e == null) "✓ $name" else "$name: $e"
            } catch (e: Remote.Refused) { T("headless.the_tv_said_no", "name" to name)
            } catch (e: Exception) { name + ": " + (e.message ?: e.javaClass.simpleName) } finally { a.close() }
        }
        via.firstOrNull { open(it, 5555, 2000) }?.let { host ->
            val link = AdbLink(c.filesDir, host)
            var err: String? = null
            var done = false
            var linked = false
            val worker = Thread {
                err = try { link.get(); linked = true; what(Scenes.Shell({ link.get().shell(it) }), true) } catch (e: Exception) { "adb:" + (e.message ?: "") }
                done = true
            }
            worker.start()
            worker.join(15000)                                        // connecting: longer means the TV is asking
            if (linked && !done) worker.join(120000)                  // connected: a scene's waits take as long as they take
            if (!done && link.waitingForUser()) { link.close(); return T("headless.the_tv_is_asking", "name" to name) }
            link.close()
            if (!done) return T("headless.the_tv_did_not", "name" to name)
            val e = err
            if (e == null) return "✓ $name"
            if (!e.startsWith("adb:")) return name + ": " + e
        }
        if (prefs.getBoolean("atv:$ip", false)) via.firstOrNull { open(it, 6466, 1500) }?.let { host ->
            val a = Atv(c.filesDir, host)
            return try {
                a.connect { }
                for (i in 0 until 20) { if (a.ready()) break; Thread.sleep(100) }
                val e = what(Keys(a), false)
                Thread.sleep(400)                                     // the last key leaves before the connection closes
                if (e == null) "✓ $name" else "$name: $e"
            } catch (e: Exception) { name + ": " + (e.message ?: e.javaClass.simpleName) } finally { a.close() }
        }
        return T("headless.cannot_reach_the_tv", "name" to name)
    }

    /** A scene by id. */
    fun scene(id: String): String {
        val sc = SceneStore.find(c, id) ?: return T("headless.this_scene_is_gone")
        return run { tv, _ -> Scenes.run(sc.optJSONArray("steps") ?: JSONArray(), tv) }.let { if (it.startsWith("✓")) "✓ " + sc.optString("name") else it }
    }

    /** Standby when the TV is on, on when it is in standby (ADB can tell; over the keys-only connection the TV's own power key decides). */
    fun power(): String = run { tv, adb ->
        if (adb && tv is Scenes.Shell) {
            val awake = !(tv.sh("dumpsys power | grep -m1 mWakefulness=")).contains("Asleep")
            tv.key(if (awake) 223 else 224)
        } else tv.key(26)
        null
    }

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
    }
}
