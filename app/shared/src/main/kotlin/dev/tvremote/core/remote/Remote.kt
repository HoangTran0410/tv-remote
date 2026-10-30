package dev.tvremote.core.remote
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.ontv.Setup

/**
 * A TV's own remote-control link, whatever brand: the Android TV Remote protocol ([Atv]), LG webOS ([Lg]), Samsung Tizen
 * ([Samsung]), Roku ([Roku]) or Hisense VIDAA ([Vidaa]). The Phone app and the Computer app press keys, type, open apps and set the volume through this alone, so one
 * remote screen and one page drive all three; what a kind cannot do is in [caps] (and [Kind]), and the UIs hide or grey it.
 * Keys are Android key codes (the values of [Scenes.KEYS]); each adapter turns them into its TV's own names.
 */
interface Remote : Closeable {
    val kind: String
    /** What the TV calls itself, known once connected. */
    val model: String
    val vendor: String
    val version: String
    /** The screen is on; null until the TV says. */
    val isOn: Boolean?
    /** level, max, muted; null when the TV does not tell (Samsung) or has not yet. */
    val volume: Triple<Int, Int, Boolean>?
    /** The app on the TV's screen ("" until known). */
    val app: String
    /** The TV's network card, for Wake-on-LAN ("" when it did not say). */
    val mac: String get() = ""
    /** The TV is showing its "allow this device?" question for us right now (LG, Samsung). */
    val asking: Boolean get() = false

    /** Blocks until keys go through. [Atv.NotPaired] / [Refused] when the TV does not let us in; [onClosed] once, if the link ends for good. */
    fun connect(onClosed: () -> Unit)
    fun ready(): Boolean
    /** [direction]: [Atv.DOWN], [Atv.UP], or [Atv.SHORT] for a tap. A key this TV has no name for is ignored ([keys]). */
    fun key(code: Int, direction: Int)
    /** A tap held down for [ms] (Android TV browsers move their pointer only while an arrow is down); the others just tap. */
    fun pulse(code: Int, ms: Long) = key(code, Atv.SHORT)
    /** The focused text field on the TV gets [t] (it replaces what is there). */
    fun text(t: String)
    /**
     * Live typing, as the page and the phone's box send it: [del] characters erased before the cursor, then [add] typed, then
     * Enter. By keys and [text] here; LG and Samsung have better ways.
     */
    fun type(del: Int, add: String, enter: Boolean) {
        repeat(del) { key(67, Atv.SHORT) }
        if (add.isNotEmpty()) text(add)
        if (enter) key(66, Atv.SHORT)
    }
    /** A link, or an app by its id ([apps]). */
    fun launch(link: String)
    /** The TV's apps, for the quick launch tiles and scenes; empty when this kind cannot list them. */
    fun apps(): List<App> = emptyList()
    /** An app's picture (PNG or JPEG), null when there is none. */
    fun icon(id: String): ByteArray? = null
    /** [q] looked up on YouTube on the TV; false when this kind cannot (the "Hỏi TV" box). */
    fun search(q: String): Boolean = false
    /** Sets the volume to [level] where the TV takes a number; false when it only has the volume keys. */
    fun setVolume(level: Int): Boolean = false
    /** The TV's inputs (HDMI 1, AV...) by its remote link; empty when this kind cannot list them ("inputs" in [Kind.caps]). */
    fun inputs(): List<Inputs.Input> = emptyList()
    /** Switches to the input [id] of [inputs]; false when this kind cannot. */
    fun switchInput(id: String): Boolean = false
    /** The screen off with the sound playing on; false when this kind cannot ("screen_off" in [Kind.caps]). */
    fun screenOff(): Boolean = false
    /** A pointer on the TV moved by [dx], [dy] counts (Air Mouse); only the kinds with "mouse" in [Kind.caps] (LG's Magic Remote link; Android TVs use a virtual HID mouse over ADB, [dev.tvremote.core.picture.Tv.mouseOpen]). */
    fun pointer(dx: Int, dy: Int) {}
    /** A click at the pointer; [scroll] a wheel step (positive up). */
    fun pointerClick() {}
    fun pointerScroll(dy: Int) {}
    /** The pointer's button held down ([on]) or let go: moves while it is held drag what is under it. */
    fun pointerHold(on: Boolean) {}
    /** The keys this TV has, by Android code; null: all of them. */
    val keys: Set<Int>? get() = null

    class App(val id: String, val label: String, val icon: String = "")

    /** The TV said no to us (the person chose Deny, or blocked this device). */
    class Refused(message: String) : IOException(message)

    /**
     * What a kind of TV can do, the same words on the page (/api/state "caps", data-needs="...") and in the Phone app. Android
     * TVs can do everything, the rest only through ADB (whose state the page already follows); the others have no ADB at all.
     */
    object Kind {
        const val ANDROID = "android"
        const val LG = "lg"
        const val SAMSUNG = "samsung"
        const val ROKU = "roku"
        const val VIDAA = "vidaa"
        val ALL = listOf(ANDROID, LG, SAMSUNG, ROKU, VIDAA)

        /**
         * adb: everything that goes through ADB (the picture, touch, 🎧, drawing, now playing, the clipboard, the Apps / Files /
         * Health tabs, Optimize TV, TV Setup and its rules); pair_code: pairing with a code typed here; volume_set: a level, not
         * only steps; link: open a web link; store: the Play Store pages; screen_off: the screen off with the sound on; app_list:
         * the TV's apps come from its remote link (Android TVs list them over ADB, on the Apps tab); mouse: Air Mouse, the phone's motion as a pointer (LG by its link, Android TVs through ADB); inputs: the list of HDMI / AV
         * inputs and switching to one ([Inputs]; Android TVs over ADB, LG by its link; Samsung only has its source key).
         */
        fun caps(kind: String): Set<String> = when (kind) {
            LG -> setOf("keys", "text", "apps", "app_list", "volume_set", "link", "screen_off", "wol", "scenes", "inputs", "mouse")
            SAMSUNG -> setOf("keys", "text", "apps", "app_list", "wol", "scenes")
            ROKU -> setOf("keys", "text", "apps", "app_list", "wol", "scenes", "inputs")
            VIDAA -> setOf("keys", "text", "apps", "app_list", "volume_set", "screen_off", "wol", "scenes", "inputs", "pair_code")
            else -> setOf("keys", "text", "apps", "volume_set", "link", "screen_off", "wol", "scenes", "adb", "pair_code", "store", "inputs", "mouse")
        }

        fun name(kind: String): String = when (kind) { LG -> "LG"; SAMSUNG -> "Samsung"; ROKU -> "Roku"; VIDAA -> "Hisense VIDAA"; else -> "Android TV" }

        fun of(s: String?): String = s?.lowercase()?.takeIf { it in ALL } ?: ANDROID

        /** The key names (Scenes.KEYS) this kind has: the page greys the others. */
        fun keyNames(kind: String): List<String> {
            val have = when (kind) { LG -> Lg.KEYS.keys; SAMSUNG -> Samsung.KEYS.keys; ROKU -> Roku.KEYS.keys; VIDAA -> Vidaa.KEYS.keys; else -> null }
            return Scenes.KEYS.filter { have == null || it.value in have }.keys.sorted()
        }

        /**
         * What a TV at [host] is, from the ports it answers on: Android TV's remote (6466) or ADB (5555), LG's (3000 / 3001),
         * Samsung's (8001 / 8002), Roku's (8060), Hisense VIDAA's (36669). Null: none answer (off, or not a TV). Asked when an address is typed in with no kind.
         */
        fun detect(host: String, timeoutMs: Int = 800, wireless: (String) -> Boolean = ::wirelessOn): String? {
            val ports = listOf(6466 to ANDROID, 5555 to ANDROID, 3000 to LG, 3001 to LG, 8002 to SAMSUNG, 8001 to SAMSUNG, 8060 to ROKU, 36669 to VIDAA)
            val open = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
            ports.map { (p, _) -> Thread { if (open(host, p, timeoutMs)) open[p] = true }.apply { isDaemon = true; start() } }.forEach { it.join(timeoutMs + 300L) }
            ports.firstOrNull { open[it.first] == true }?.second?.let { return it }
            return if (wireless(host)) ANDROID else null          // no port answers, yet Wireless debugging is announced: an Android TV with only that
        }

        /** The TV announces Wireless debugging (mDNS); only where this app can pair. */
        private fun wirelessOn(host: String): Boolean = dev.tvremote.core.adb.AdbTls.available() && dev.tvremote.core.adb.AdbTls.wireless(host, 1500).any

        private fun open(host: String, port: Int, ms: Int): Boolean = try {
            dev.tvremote.core.Lan.connect(host, port, ms).close(); true
        } catch (e: Exception) { false }

        /** The ports that answer while the TV is on: Wake-on-LAN waits for one. */
        fun ports(kind: String): List<Int> = when (kind) { LG -> listOf(3000, 3001); SAMSUNG -> listOf(8001, 8002); ROKU -> listOf(8060); VIDAA -> listOf(36669); else -> listOf(6466) }

        /** The kind's caps and keys as /api/state carries them. */
        fun json(kind: String): JSONObject = JSONObject().put("kind", kind).put("caps", JSONArray(caps(kind).sorted())).put("keys", JSONArray(keyNames(kind)))
    }

    companion object {
        /** The adapter for [kind]; [dir] keeps its keys / client keys / tokens (with the ADB key and Atv's certificate). */
        fun of(kind: String, dir: File, host: String): Remote = when (kind) {
            Kind.LG -> Lg(dir, host)
            Kind.SAMSUNG -> Samsung(dir, host)
            Kind.ROKU -> Roku(dir, host)
            Kind.VIDAA -> Vidaa(dir, host)
            else -> Atv(dir, host)
        }
    }
}

/** Scene steps through a [Remote]: what LG and Samsung TVs run (Android TVs run them over ADB, [Scenes.Shell]). */
class RemoteSteps(private val r: Remote, private val wake: () -> Unit) : Scenes.Target {
    override fun key(code: Int) {
        if (code == 224) { if (r.isOn != true) wake(); return }      // wake: Wake-on-LAN, the TV's power key when it is still on the network
        if (code == 223) { if (r.isOn != false) r.key(26, Atv.SHORT); return }
        r.key(code, Atv.SHORT)
        Thread.sleep(150)
    }
    override fun app(pkg: String) = r.launch(pkg)
    override fun link(url: String) = r.launch(url)
    override fun volume(level: Int) {
        if (r.setVolume(level)) return
        throw IllegalArgumentException("this TV only has the volume keys")
    }
    override fun timer(minutes: Int) = throw IllegalArgumentException("the sleep timer needs TV Setup on an Android TV")
    override fun input(id: String) { if (!r.switchInput(id)) throw IllegalArgumentException("this TV cannot switch inputs from here") }
}

/** Looks up and saves small files beside the keys (an LG client key, a Samsung token), one per TV and port. */
internal class TvKeys(private val dir: File, private val name: String) {
    private fun file(host: String) = File(dir, name + "-" + host.replace(Regex("[^\\w.-]"), "_"))
    fun get(host: String): String = try { file(host).readText().trim() } catch (e: IOException) { "" }
    fun put(host: String, value: String) {
        try {
            dir.mkdirs()
            val f = file(host)
            f.writeText(value)
            f.setReadable(false, false); f.setReadable(true, true)     // like the ADB key: this user only
        } catch (ignored: IOException) { }
    }
}
