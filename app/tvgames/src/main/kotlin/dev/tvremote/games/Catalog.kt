package dev.tvremote.games

import android.content.Context
import android.content.pm.ApplicationInfo
import dev.tvremote.update.SignedList
import org.json.JSONObject
import java.io.File

/**
 * What the site tells the app without a release: docs/games/games.json (dev/sign/sign_games.py), the same way TV Channels reads its
 * providers: the site, else GitHub, else the last good copy in files/, else the copy packed in assets. Signed (Ed25519, the
 * launcher list's key): a bad signature is ignored. Kept a day. "cores" (downloaded on first use, [Cores]), "web" (featured
 * games), "homebrew" (free ROMs), "layouts" (pad overrides, served to the phones at /layouts.json).
 */
object Catalog {
    private val BASES = listOf("https://tvremote.pages.dev/games/", "https://raw.githubusercontent.com/HoangTran0410/tv-remote/main/docs/games/")
    private const val DAY = 24 * 3600_000L

    /** Debug builds only: another address and key to test with (`--es cat_base http://10.0.2.2:8800/docs/games/ --es cat_key <hex>` on the library). */
    private fun dbg(c: Context): Pair<String, String>? {
        if (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val p = c.getSharedPreferences("games", Context.MODE_PRIVATE)
        val b = p.getString("cat_base", "") ?: ""
        return if (b.isEmpty()) null else b to (p.getString("cat_key", "") ?: "")
    }

    /** Where the catalog and what it points to (pictures) are read from, in order: the site, then GitHub (a debug build's override alone). */
    fun bases(c: Context): List<String> = dbg(c)?.let { listOf(it.first) } ?: BASES

    fun setDebug(c: Context, base: String?, key: String?) {
        if (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        c.getSharedPreferences("games", Context.MODE_PRIVATE).edit().putString("cat_base", base ?: "").putString("cat_key", key ?: "").apply()
        File(c.filesDir, "games.json").delete(); mem = null
    }

    /** The keys a list may be signed with: a debug override trusts only its own key. */
    private fun keys(c: Context): List<String> = dbg(c)?.second?.takeIf { it.length == 64 }?.let { listOf(it) } ?: SignedList.KEYS

    @Volatile private var mem: JSONObject? = null

    private fun parse(text: String?): JSONObject? = try { text?.let { JSONObject(it) }?.takeIf { it.optInt("v") == 1 } } catch (e: Exception) { null }

    /** The newest list already on the device, never the network: the kept copy (checked again), else the one in the APK. */
    fun peek(c: Context): JSONObject {
        mem?.let { return it }
        val kept = SignedList.kept(File(c.filesDir, "games.json"), keys(c))?.let { parse(String(it)) }
        val packed = kept ?: SignedList.packed({ c.assets.open(it) }, "games.json", keys(c))?.let { parse(String(it)) }
        return (packed ?: JSONObject("{\"v\":1}")).also { mem = it }
    }

    /** Asks the site when the kept copy is over a day old (or [force]); true when the list changed. Off the main thread. */
    /** True when the last [refresh] heard from a site (the list may still be the same), false when none answered. */
    @Volatile var reached = false

    fun refresh(c: Context, force: Boolean = false): Boolean {
        reached = false
        val f = File(c.filesDir, "games.json")
        if (!force && f.exists() && dev.tvremote.guard.Age.fresh(f.lastModified(), DAY) && dbg(c) == null) return false
        return try {
            SignedList.fetch(bases(c), "games.json", 1 shl 20, keys(c), connectMs = 6000, readMs = 8000) { list, sig, _ ->
                val o = parse(String(list, Charsets.UTF_8)) ?: return@fetch null
                val changed = mem?.toString() != o.toString()
                SignedList.keep(f, list, sig)
                mem = o; reached = true
                changed
            }
        } catch (e: Exception) { false }
    }

    fun layouts(c: Context): String = peek(c).optJSONObject("layouts")?.toString() ?: "{}"

    private val NOT_FILE = Regex("[^A-Za-z0-9 ._-]")                  // built once: it ran for every homebrew entry on every lookup

    /** The entries by the file name they get on the device (the first of two alike wins, as the old scan did). */
    internal fun <T> byFile(entries: List<Pair<String, T>>): Map<String, T> = HashMap<String, T>().also { m -> entries.forEach { (n, e) -> n.replace(NOT_FILE, "").let { k -> if (k !in m) m[k] = e } } }

    /** The homebrew entries by file name, rebuilt when [peek] hands a new list. */
    private fun homebrewByFile(cat: JSONObject): Map<String, JSONObject> {
        hb?.let { if (it.first === cat) return it.second }
        val a = cat.optJSONArray("homebrew")
        val m = byFile((0 until (a?.length() ?: 0)).mapNotNull { i -> a?.optJSONObject(i)?.let { e -> listOfNotNull(e.optString("name") to e, e.optString("file").takeIf { it.isNotEmpty() }?.let { it.substringBeforeLast('.') to e }) }.orEmpty() }.flatten())
        return m.also { hb = cat to it }
    }
    @Volatile private var hb: Pair<JSONObject, Map<String, JSONObject>>? = null   // tied to the list object, so a new [mem] drops it

    /** The entry a downloaded homebrew ROM came from (its file is the sanitised name + extension, see LibraryActivity.getRom), or a web game by its address. */
    fun entryOf(c: Context, rom: String?, url: String?): JSONObject? {
        val cat = peek(c)
        if (rom != null) homebrewByFile(cat)[rom.substringBeforeLast('.').removeSuffix(" (homebrew)")]?.let { return it }
        if (url != null) cat.optJSONArray("web")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (it.optString("url") == url) return it }
        }
        return null
    }

    /** How many can play (max) and how many the game needs (min, default 1): the catalog's "players" / "min", else [dflt] for the system. */
    fun players(c: Context, rom: String?, url: String?, dflt: Int): Pair<Int, Int> {
        val e = entryOf(c, rom, url)
        val mx = (e?.optInt("players", dflt) ?: dflt).coerceIn(1, 4)
        return mx to (e?.optInt("min", 1) ?: 1).coerceIn(1, mx)
    }

    /** A web game's lobby switch: "lobby": false in its catalog entry, or in assets/webgames/<dir>/game.json for a built-in one. */
    fun lobbyOn(c: Context, url: String): Boolean {
        if (url.startsWith("builtin:")) return try {
            !JSONObject(c.assets.open("webgames/${url.removePrefix("builtin:")}/game.json").use { String(it.readBytes()) }).let { it.has("lobby") && !it.getBoolean("lobby") }
        } catch (e: Exception) { true }
        return entryOf(c, null, url)?.optBoolean("lobby", true) ?: true
    }
}
