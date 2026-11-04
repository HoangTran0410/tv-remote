package dev.tvremote.core.scenes

import dev.tvremote.update.SignedList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * The words of the spoken commands, from the site's signed list (docs/voice/phrases.json; js/voice.js reads the same file). What a key or a
 * verb DOES is code in [Voice]; the phrases that mean it are data, so a new way of saying "go home" reaches every app without an update.
 * Everything is folded (no accents, no case) and "tivi" / "ti vi" read as "tv", so a list is written the way people write.
 */
class Phrases(json: String) {
    class Alias(val label: String, val pkgs: List<String>, val names: Set<String>, val search: String? = null, val tube: String? = null)   // search: a link with {q}, the app's own web search; tube: "main" = the word YouTube, "alt" = another YouTube client

    private val o = JSONObject(json)
    val rev: Int = o.getInt("rev")
    private val langs = o.getJSONObject("lang").let { l -> l.keys().asSequence().map { l.getJSONObject(it) }.toList() }

    private fun words(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { Voice.norm(a.getString(it)) }.filter { it.isNotEmpty() }
    private fun all(vararg path: String): List<String> = langs.flatMap { words(at(it, path)) }.distinct()
    private fun at(o: JSONObject, path: Array<out String>): JSONArray? {
        var c: JSONObject = o
        for (i in 0 until path.size - 1) c = c.optJSONObject(path[i]) ?: return null
        return c.optJSONArray(path.last())
    }
    /** Longest first, so "chuyen sang" wins over "chuyen". */
    private fun alt(l: List<String>) = l.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    val fillers: List<String> = all("fillers").sortedByDescending { it.length }
    val keys: Map<String, String> = HashMap<String, String>().also { m ->
        for (l in langs) { val k = l.optJSONObject("keys") ?: continue; for (key in k.keys()) for (p in words(k.getJSONArray(key))) m[p] = key }
    }
    val volUp = Regex("^(?:" + alt(all("volume", "up")) + ")(?: (?:(?:len|xuong|them|by|to) )?(\\d+))?$")
    val volDown = Regex("^(?:" + alt(all("volume", "down")) + ")(?: (?:(?:len|xuong|them|by|to) )?(\\d+))?$")
    val timerCancel: Set<String> = all("timer", "cancel").toSet()
    val timerSet = Regex("^(?:" + alt(all("timer", "set")) + ") (\\d+) ?(" + alt(all("timer", "hours") + all("timer", "minutes")) + ")?$")
    val hours: Set<String> = all("timer", "hours").toSet()
    private val tune = all("channel", "tune")
    private val noise = alt(all("open", "noise"))
    val open = Regex("^(?:" + alt(all("open", "verbs") + tune) + ")(?: (?:$noise))? (.+)$")
    val close = Regex("^(?:" + alt(all("close", "verbs")) + ") (?:(?:$noise) )?(.+)$")
    /** "mở ứng dụng X", "app X": the word "app" said, so X is an app's name and may be matched loosely ([Voice] near). */
    val appWord = Regex("^(?:(?:" + alt(all("open", "verbs")) + ") )?(?:$noise) (.+)$")
    /** "X <connector> Y" ("vtv3 trên fpt play"): X and Y, split at the last connector word; null before the list has any. */
    val connect: Regex? = all("connect").takeIf { it.isNotEmpty() }?.let { Regex("^(.+) (?:" + alt(it) + ") (.+)$") }
    /** "tìm X", "search X": X looked up in the app the person picked ([Voice.Prefs.search]); null before the list has any such verb. */
    val searchBare: Regex? = all("search", "verbs").takeIf { it.isNotEmpty() }?.let { Regex("^(?:" + alt(it) + ") (.+)$") }
    /** The first search verb of the list ("tim kiem", "search for"), for [Voice.asSearch]; null before the list has any. */
    val searchVerb: String? = all("search", "verbs").firstOrNull()
    val tuneRe = Regex("^(?:" + alt(tune) + ") (.+)$")
    val channel = Regex("^(?:(?:" + alt(all("channel", "verbs")) + ") )?(?:" + alt(all("channel", "words")) + ") (.+)$")
    val channelNames = Regex("^(?:" + (0 until (o.optJSONArray("channel_names")?.length() ?: 0)).joinToString("|") { o.getJSONArray("channel_names").getString(it) } + ")$")
    /** Settings pages by id: the intent actions to try in order (the list's "settings"); [settingWords]: the folded phrase of any language -> that id. Data only: a new page is a new line in the list. */
    val settings: Map<String, List<String>> = o.optJSONObject("settings")?.let { m -> m.keys().asSequence().associateWith { k -> m.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } } } ?: emptyMap()
    val settingWords: Map<String, String> = HashMap<String, String>().also { m ->
        for (l in langs) { val k = l.optJSONObject("settings") ?: continue; for (id in k.keys()) if (id in settings) for (p in words(k.getJSONArray(id))) m[p] = id }
    }
    val neverClose: Set<String> = o.optJSONArray("never_close")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    val apps: List<Alias> = o.getJSONArray("apps").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { x ->
            val p = x.optJSONArray("pkgs")
            Alias(x.getString("label"), if (p == null) emptyList() else (0 until p.length()).map { p.getString(it) }, words(x.getJSONArray("names")).toSet(), x.optString("search").ifEmpty { null }, x.optString("tube").ifEmpty { null })
        }
    }
    /**
     * What an offline recognizer that hears only what it is told (TV Setup Voice) listens for, in [lang]: every command phrase, channel
     * name and app name, said as people say it ("spoken" in the list: the accents, names spelled letter by letter), plus the number words.
     * One entry per way of saying it; "said=written" where the words heard differ from the written ones ("vê tê vê=vtv"), so the
     * recognizer hands back what [Voice.parse] reads. [more]: names only this TV has (its apps, scenes). Empty when the list has no
     * "spoken" for [lang].
     */
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    fun spoken(lang: String, more: Collection<String> = emptyList()): List<String> {
        val sp = o.optJSONObject("spoken")?.optJSONObject(lang) ?: return emptyList()
        val accents = sp.optJSONObject("words")
        val letters = sp.optJSONObject("letters")
        val spell = sp.optJSONArray("spell").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet() }
        fun ways(w: String): List<Pair<String, Boolean>> = when {             // (said, differs from written)
            w in spell && letters != null -> listOf(w.map { letters.optString(it.toString(), it.toString()) }.joinToString(" ") to true, w to false)
            accents?.has(w) == true -> accents.getString(w).split('|').map { it to false }
            else -> listOf(w to false)
        }
        val out = LinkedHashSet<String>()
        fun add(text: String) {
            val ws = Voice.norm(text).split(' ').filter { it.isNotEmpty() }
            if (ws.isEmpty()) return
            val typed = text.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }   // a name written with accents keeps them ("Phim tối")
            var combos = listOf(listOf<Pair<String, Boolean>>())
            for ((i, w) in ws.withIndex()) {
                val t = typed.getOrNull(i)?.takeIf { typed.size == ws.size && Voice.fold(it) == w && it != w }
                combos = combos.flatMap { c -> (if (t != null) listOf(t to false) else ways(w)).map { c + it } }.take(8)   // "bat tat" x accents: a few ways, not hundreds
            }
            for (c in combos) { val said = c.joinToString(" ") { it.first }; out += if (c.any { it.second }) "$said=${ws.joinToString(" ")}" else said }
        }
        fun texts(v: Any?, to: MutableList<String>) {
            when (v) {
                is JSONObject -> for (k in v.keys()) texts(v.get(k), to)
                is JSONArray -> for (i in 0 until v.length()) texts(v.get(i), to)
                is String -> to += v
            }
        }
        val all = ArrayList<String>()
        texts(o.optJSONObject("lang")?.optJSONObject(lang), all)
        for (a in apps) all += a.names
        // channel names are patterns: "htv (?:phim|key)" is "htv phim" and "htv key"; "?\d{1,2}" and the like are the number said after
        val ch = o.optJSONArray("channel_names")
        for (i in 0 until (ch?.length() ?: 0)) {
            val r = ch!!.getString(i)
            val g = Regex("""\(\?:([^)]*)\)""").find(r)
            val names = if (g == null) listOf(r) else g.groupValues[1].split('|').map { r.replaceRange(g.range, it) }
            for (n in names) all += n.replace(Regex("""\\d(\{[\d,]*\}|[*+?])?|[?*+]"""), " ")
        }
        for (t in all + more) add(t)
        sp.optJSONArray("numbers")?.let { a -> for (i in 0 until a.length()) out += a.getString(i) }
        return out.toList()
    }
}

/**
 * Which copy of the list is in use: the site's / GitHub's when read (signature checked), else the one kept from the last read, else the
 * one packed in the app (the page's own files, `voice/phrases.json` + `.sig`). A copy that fails its signature or is older than the packed
 * one (a rolled-back list) is skipped. The page asks the app for the same bytes ([json]), so every screen speaks the same phrases.
 */
object VoicePhrases {
    const val NAME = "voice/phrases.json"
    private val BASES = listOf("https://tvremote.pages.dev/voice/", "https://raw.githubusercontent.com/HoangTran0410/tv-remote/main/docs/voice/")
    private const val DAY = 24 * 3600_000L
    @Volatile private var bytes: ByteArray? = null
    @Volatile private var started = false
    private var kept: File? = null

    /** The first of [fetched] / [kept] / [packed] that reads as a list, never one with a lower rev than [packed]. Pure: the order and the rollback rule are tested. */
    fun choose(fetched: ByteArray?, kept: ByteArray?, packed: ByteArray?): Pair<ByteArray, Phrases>? {
        fun read(b: ByteArray?): Phrases? = try { b?.let { Phrases(String(it, Charsets.UTF_8)) } } catch (e: Exception) { null }
        val base = read(packed)
        for (b in listOf(fetched, kept)) { val p = read(b) ?: continue; if (b != null && (base == null || p.rev >= base.rev)) return b to p }
        return if (packed != null && base != null) packed to base else null
    }

    /** Once per run: the kept or packed list now, the site's in the background (at most once a day). [open] reads the page's files (assets, or the web folder), [dir] is where the copy is kept. */
    @Synchronized fun start(open: (String) -> InputStream, dir: File?) {
        if (started) return
        started = true
        val f = dir?.let { File(it, "voice-phrases.json") }
        kept = f
        use(choose(null, f?.let { SignedList.kept(it) }, SignedList.packed(open, NAME)))
        if (f != null && !(f.isFile && dev.tvremote.guard.Age.fresh(f.lastModified(), DAY))) Thread({ refresh() }, "voice-phrases").apply { isDaemon = true }.start()
    }

    /** Reads the site's list now (blocks). True when it replaced what was in use. */
    fun refresh(): Boolean = try {
        var sig = ""
        val list = SignedList.fetch(BASES, "phrases.json", 1 shl 20, connectMs = 6000, readMs = 8000) { l, s, _ -> sig = s; l }
        val packedRev = Voice.phrases?.rev ?: 0
        val got = choose(list, null, null)
        if (got != null && got.second.rev >= packedRev) { kept?.let { SignedList.keep(it, list, sig) }; use(got); true } else false
    } catch (e: Exception) { false }

    private fun use(p: Pair<ByteArray, Phrases>?) { if (p != null) { bytes = p.first; Voice.phrases = p.second } }

    /** The list in use, as the page asks for it; null before [start]. */
    fun json(): ByteArray? = bytes
}
