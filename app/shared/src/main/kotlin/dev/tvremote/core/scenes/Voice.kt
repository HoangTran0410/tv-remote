package dev.tvremote.core.scenes
import java.text.Normalizer

/**
 * Spoken or typed sentences the remote carries out itself, before handing anything to the TV's Assistant: "mở YouTube",
 * "tắt tiếng", "hẹn giờ tắt 30 phút", the name of a scene. Faster than the Assistant, and it works on TVs without one.
 * The same rules as the web page's js/voice.js (tests on both sides).
 */
object Voice {
    sealed class Command {
        data class Key(val key: String, val times: Int) : Command()
        data class App(val pkg: String, val name: String) : Command()
        /** "mở vtv3 trên fpt play": [query] searched inside the app; [url] its web search for it (the alias's "search" with {q} filled), null: the app's own SEARCH or just opening it. */
        data class Search(val pkg: String, val name: String, val query: String, val url: String?) : Command()
        data class Channel(val name: String) : Command()                  // a TV channel by name: TV Channels finds it itself
        data class Close(val pkg: String, val name: String) : Command()     // am force-stop: needs ADB; never TV Setup / TV Home (phrases.json "never_close")
        data class Timer(val minutes: Int) : Command()
        data class Scene(val id: String) : Command()
        /** A tile pinned on TV Home's grid, by the name the person sees ([PinRef]): TV Home opens it ([link]: aimed at [app]). */
        data class Pin(val key: String, val name: String, val app: String, val link: String?) : Command()
        /** Two or more targets (apps, pins) matched the words equally well: at most [MAX_CHOICES] to pick from. Never made for a clear request. */
        /** A settings page by the list's id ("wifi", "bluetooth"): [actions] are tried in order until one starts, then the TV's settings. */
        data class Setting(val id: String, val actions: List<String>) : Command()
        data class Choice(val options: List<Command>) : Command()
    }
    /** A pinned tile as TV Home reports it ("pinned" in its config): [key] `pin:...`, [title] as the grid shows it, [app] what opens it, [link] of TV Home's own link pins. */
    data class PinRef(val key: String, val title: String, val app: String = "", val link: String? = null)
    const val MAX_CHOICES = 3

    /**
     * What the person picked for voice (TV Setup's rules "voice_search" / "voice_youtube"; "" = today's behaviour). [search]: the package a bare
     * "tìm X" opens and searches in (instead of the TV's own search); [youtube]: the package the word "YouTube" means. A package that is not
     * among the TV's apps is ignored, so an app that was removed falls back to today's behaviour.
     */
    data class Prefs(val search: String = "", val youtube: String = "")
    /** The prefs in use: each client sets it from TV Setup's rules (the TV's own screen from its rules, the phone when it asks for the app names). */
    @Volatile var prefs: Prefs = Prefs()

    /** A YouTube client installed on the TV that the person may pick ([query] false: it can only be opened, no search words reach it). */
    data class Tube(val pkg: String, val name: String, val query: Boolean, val main: Boolean)

    /** The aliases of the list marked "tube" whose packages are in [installed] (package to name), for the pickers. */
    fun tubes(installed: Map<String, String>): List<Tube> {
        val ph = phrases ?: return emptyList()
        return ph.apps.filter { it.tube != null }.flatMap { a -> a.pkgs.filter { it in installed }.map { Tube(it, installed[it] ?: a.label, a.search != null, a.tube == "main") } }
    }

    /** Lower case, no accents, no punctuation: "Mở YouTube!" and "mo youtube" read the same. */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").replace('đ', 'd').replace('Đ', 'd')
        .lowercase().replace(SIGNS, " ").replace(SPACES, " ").trim()
    private val MARKS = Regex("\\p{M}+")                              // compiled once: fold runs thousands of times for one word list (MicVoice.words)
    private val SIGNS = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")
    private val TIVI = Regex("\\bti ?vi\\b")

    /** The words in use ([VoicePhrases] fills it: the site's signed list, else the kept or packed copy); null before an app started it: nothing is understood. */
    @Volatile var phrases: Phrases? = null

    /** [fold] with "tivi" / "ti vi" read as "tv", the form every phrase of the list is kept in. */
    fun norm(s: String): String = fold(s).replace(TIVI, "tv")

    /** The sentence starts with a search word ("tìm …", "search …") or holds a connector ("… trên youtube"): free speech is worth more than the commands' text then. */
    fun looksLikeSearch(text: String): Boolean {
        val ph = phrases ?: return false
        val s = strip(norm(text), ph)
        return ph.searchBare?.matches(s) == true || ph.connect?.matches(s) == true
    }

    /** Politeness and filler at either end ("giúp tôi", "đi", "nhé", "please") taken off; a sentence that is only filler stays as it is. */
    private fun strip(s0: String, ph: Phrases): String {
        var s = s0
        var again = true
        while (again) {
            again = false
            for (f in ph.fillers) {
                if (s.startsWith("$f ")) { s = s.substring(f.length + 1); again = true; break }
                if (s.endsWith(" $f")) { s = s.substring(0, s.length - f.length - 1); again = true; break }
            }
        }
        return s
    }

    /** True when [sentence] asks to open or close something ("mo youtube"): the Phone app then asks the TV for its app names before giving up on it. */
    fun wantsApp(sentence: String): Boolean { val ph = phrases ?: return false; val s = strip(norm(sentence), ph); return ph.open.matches(s) || ph.close.matches(s) || prefs.search.isNotEmpty() && ph.searchBare?.matches(s) == true }

    /** The link TV Channels (dev.tvremote.channels) takes; spaces as %20, not +. */
    fun channelLink(name: String): String = "tvchannels://find/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /** An app named [want] among [apps] (package to name): its alias in the list (a package that is there), else the installed names, loosely. */
    private fun app(want: String, apps: Map<String, String>, ph: Phrases, loose: Boolean, prefs: Prefs = Voice.prefs): Pair<String, String>? {
        val alias = ph.apps.firstOrNull { want in it.names }
        if (alias?.tube == "main" && prefs.youtube.isNotEmpty() && prefs.youtube in apps) return prefs.youtube to apps.getValue(prefs.youtube)   // "YouTube" means the client the person picked
        val named = apps.entries.map { it to fold(it.value) }.filter { it.second.isNotEmpty() }
        fun fuzzy(w: String): Pair<String, String>? {
            val hit = named.firstOrNull { it.second == w } ?: named.firstOrNull { it.second.startsWith(w) || w.startsWith(it.second + " ") }
                ?: named.firstOrNull { w.length >= 3 && it.second.contains(w) } ?: named.firstOrNull { it.second.length >= 3 && w.contains(it.second) }
            return hit?.let { it.first.key to it.first.value }
        }
        if (alias != null) {
            alias.pkgs.firstOrNull { apps.isEmpty() || it in apps }?.let { return it to (apps[it] ?: alias.label) }
            return if (apps.isEmpty()) null else fuzzy(fold(alias.label))                // not there under the package we know: its name, as the TV calls it
        }
        return if (loose) fuzzy(want) else null
    }

    /** The words of [text] that [x] (folded) came from, with their accents ("sơn tùng"), else [x]: a search is better with the person's own spelling. */
    private fun spelled(text: String, x: String): String {
        val ws = x.split(' ')
        val toks = text.trim().split(Regex("\\s+")).map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }.filter { it.isNotEmpty() }
        val f = toks.map { fold(it) }
        for (i in 0..f.size - ws.size) if (f.subList(i, i + ws.size) == ws) return toks.subList(i, i + ws.size).joinToString(" ")
        return x
    }

    /** "<open verb>? X <connector> Y" with Y an app (an alias, or an installed name in full) and X more than that app: X is looked up inside Y. */
    private fun search(text: String, s: String, apps: Map<String, String>, ph: Phrases, prefs: Prefs): Command.Search? {
        val re = ph.connect ?: return null
        val rest = ph.open.matchEntire(s)?.groupValues?.get(1) ?: s
        val m = re.matchEntire(rest) ?: return null
        val x = ph.searchBare?.matchEntire(m.groupValues[1])?.groupValues?.get(1) ?: m.groupValues[1]   // "tìm sơn tùng trên youtube": the search word is not part of the query
        val y = m.groupValues[2]
        fun known(w: String) = app(w, apps, ph, false, prefs) ?: apps.entries.firstOrNull { fold(it.value) == w }?.let { it.key to it.value }
        val target = known(y) ?: return null
        if (known(x)?.first == target.first) return null                  // "mở youtube trên youtube"
        return searchCommand(target.first, target.second, spelled(text, x), ph.apps.firstOrNull { target.first in it.pkgs } ?: ph.apps.firstOrNull { y in it.names })
    }

    /** [query] in [pkg]: the web search of the alias [a] that owns it, null when it has none (the app can then only be opened). */
    private fun searchCommand(pkg: String, name: String, query: String, a: Phrases.Alias?) =
        Command.Search(pkg, name, query, a?.search?.replace("{q}", java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")))

    /** "tìm X" with no app said, when the person picked one to search in ([Prefs.search], among [apps]); else null: the TV's own search, as before. */
    private fun bareSearch(text: String, s: String, apps: Map<String, String>, ph: Phrases, prefs: Prefs): Command.Search? {
        val name = apps[prefs.search] ?: return null
        val x = ph.searchBare?.matchEntire(s)?.groupValues?.get(1) ?: return null
        return searchCommand(prefs.search, name, spelled(text, x), ph.apps.firstOrNull { prefs.search in it.pkgs })
    }

    /**
     * What [want] names among the installed apps and the pins, best match only. Quality: 0 the whole name, 1 a start of it, 2 [want] inside the name,
     * 3 the name inside [want]. One target at the best quality: it. A pin named like an app that matched too: the app. Several at the same quality:
     * a [Command.Choice] of up to [MAX_CHOICES]; this is the only place a list can come from.
     */
    private fun best(want: String, apps: Map<String, String>, pins: List<PinRef>, exactOnly: Boolean, withApps: Boolean = true): Command? {
        val heard = sound(want)
        fun quality(n: String): Int? = when {
            n.isEmpty() -> null
            n == want || heard.length >= 4 && sound(n) == heard -> 0
            exactOnly -> null
            n.startsWith(want) || want.startsWith("$n ") -> 1
            want.length >= 3 && n.contains(want) -> 2
            n.length >= 3 && want.contains(n) -> 3
            else -> null
        }
        val hits = ArrayList<Pair<Int, Command>>()
        if (withApps) for ((pkg, name) in apps) quality(fold(name))?.let { hits.add(it to Command.App(pkg, name)) }
        for (p in pins) quality(fold(p.title))?.let { hits.add(it to Command.Pin(p.key, p.title, p.app, p.link)) }
        val top = hits.minOfOrNull { it.first } ?: return null
        var at = hits.filter { it.first == top }.map { it.second }
        val appNames = at.filterIsInstance<Command.App>().map { fold(it.name) }.toSet()
        at = at.filter { it !is Command.Pin || fold(it.name) !in appNames }                // a pin named like an app is that app's twin: the app
        at = at.distinctBy { if (it is Command.App) it.pkg else (it as Command.Pin).key }.sortedWith(compareBy({ names1(it).length }, { names1(it) }))   // the shortest name first (the closest), and the same order on every client
        return if (at.size == 1) at[0] else Command.Choice(at.take(MAX_CHOICES))
    }

    /**
     * The installed apps whose name sounds closest to [want], a few letters off ("mở app việt mai tivi"), asked only after the word "app":
     * at most a quarter of the letters, one for a short name. Several as close: a [Command.Choice].
     */
    private fun near(want: String, apps: Map<String, String>): Command? {
        val w = sound(want); if (w.length < 3) return null
        val off = apps.map { (pkg, name) -> val n = sound(fold(name)); Triple(pkg, name, if (n.isEmpty()) 99 else edits(w, n)) }
            .filter { it.third <= maxOf(1, minOf(w.length, sound(fold(it.second)).length) / 4) }
        val top = off.minOfOrNull { it.third } ?: return null
        val at = off.filter { it.third == top }.sortedWith(compareBy({ it.second.length }, { it.second })).map { Command.App(it.first, it.second) }
        return if (at.size == 1) at[0] else Command.Choice(at.take(MAX_CHOICES))
    }

    /** Letters to add, drop or change to turn [a] into [b] (Levenshtein). */
    private fun edits(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** A name as it sounds: no spaces or marks, "y" as "i". The recognizer splits and respells brand names ("VietMITV" heard as "Việt MyTV"). */
    private fun sound(s: String) = s.lowercase().replace(NOT_ALNUM, "").replace('y', 'i')
    private val NOT_ALNUM = Regex("[^a-z0-9]")

    private fun channelName(w: String) = w.replace(Regex("([a-z]) (\\d)"), "\$1\$2")

    /** [apps]: package to name; [scenes]: id to name; [pins]: TV Home's pinned tiles. Null means: ask the TV's Assistant. */
    fun parse(text: String, apps: Map<String, String>, scenes: Map<String, String>, pins: List<PinRef> = emptyList(),
              sayings: Map<String, List<String>> = emptyMap(), prefs: Prefs = Voice.prefs): Command? {
        val raw = fold(text)
        if (raw.isEmpty()) return null
        scenes.entries.firstOrNull { (_, n) -> fold(n) == raw || fold("canh $n") == raw || fold("scene $n") == raw }?.let { return Command.Scene(it.key) }
        val ph = phrases ?: return null
        val s = strip(norm(text), ph)
        // a scene's own phrases ("Câu gọi"): exactly what the person typed for it, before the built-in commands, so theirs win
        if (sayings.isNotEmpty()) { val n = norm(text); sayings.entries.firstOrNull { (id, l) -> id in scenes && l.any { val p = norm(it); p.isNotEmpty() && (p == n || p == s) } }?.let { return Command.Scene(it.key) } }
        ph.keys[s]?.let { return Command.Key(it, 1) }
        ph.volUp.matchEntire(s)?.let { return Command.Key("VOL_UP", minOf(it.groupValues[1].toIntOrNull() ?: 3, 15)) }
        ph.volDown.matchEntire(s)?.let { return Command.Key("VOL_DOWN", minOf(it.groupValues[1].toIntOrNull() ?: 3, 15)) }
        if (s in ph.timerCancel) return Command.Timer(0)
        ph.timerSet.matchEntire(s)?.let { m ->
            val hours = m.groupValues[2] in ph.hours
            return Command.Timer(minOf((m.groupValues[1].toIntOrNull() ?: 720) * (if (hours) 60 else 1), 720))   // "99999999999 phút": the most, not a crash
        }
        ph.close.matchEntire(s)?.let { m ->
            val hit = app(m.groupValues[1], apps, ph, true, prefs)
            if (hit != null && hit.first !in ph.neverClose) return Command.Close(hit.first, hit.second)
        }
        search(text, s, apps, ph, prefs)?.let { return it }
        val verb = ph.open.matchEntire(s)?.groupValues?.get(1)
        val want = verb ?: s
        app(want, apps, ph, false, prefs)?.let { return Command.App(it.first, it.second) }
        // a name said in full: an installed app (after "open" only: a bare word is not asked to open anything), else a pin; this before the channels, a tile the person named "VTV3" is theirs
        best(want, apps, pins, true, withApps = verb != null)?.let { return it }
        ph.appWord.matchEntire(s)?.groupValues?.get(1)?.let { n -> (best(n, apps, pins, false) ?: near(n, apps))?.let { return it } }   // "app" said: a name close to one installed
        ph.settingWords[want]?.let { id -> return Command.Setting(id, ph.settings[id].orEmpty()) }   // after the apps: an app named "Wifi" is the app
        // before the apps by name: "mở kênh vtv1" is a channel, not an app called "kenh vtv1"
        ph.channel.matchEntire(s)?.let { m -> val n = m.groupValues[1]; return Command.Channel(if (ph.channelNames.matches(n)) channelName(n) else n) }
        if (ph.channelNames.matches(want)) return Command.Channel(channelName(want))
        ph.tuneRe.matchEntire(s)?.let { return Command.Channel(it.groupValues[1]) }
        if (verb != null) best(verb, apps, pins, false)?.let { return it }
        bareSearch(text, s, apps, ph, prefs)?.let { return it }          // last: "tìm vtv3" is a channel first, then a search in the app picked
        return null
    }

    /**
     * [cmd] (from [parse] of [text]) names the app in full, its alias or its whole name, not a start of it: the TV's voice may then open it
     * on a pause in the partial text, without waiting for the recognizer's end ("mở you" finds YouTube loosely, so it never counts).
     */
    fun whole(text: String, cmd: Command?): Boolean {
        if (cmd !is Command.App && cmd !is Command.Pin) return false
        val ph = phrases ?: return false
        val s = strip(norm(text), ph)
        val want = ph.open.matchEntire(s)?.groupValues?.get(1) ?: s
        if (cmd is Command.Pin) return norm(cmd.name) == want
        cmd as Command.App
        return ph.apps.any { want in it.names && cmd.pkg in it.pkgs } || norm(cmd.name) == want
    }

    /** What a sentence would do, in words ([id] in i18n with [args]), without doing it: the Phone app's hold-to-talk preview. */
    data class Say(val id: String, val args: Map<String, String> = emptyMap())
    /** [command] null: the sentence goes to the TV's search / Assistant, as [parse] says. */
    data class Plan(val command: Command?, val say: Say)

    // whole ids, not built from the key name: tests/i18n.test.js finds the words in use by reading them
    private val KEY_SAY = mapOf("MUTE" to "talk.k_mute", "PLAY_PAUSE" to "talk.k_play_pause", "HOME" to "talk.k_home", "BACK" to "talk.k_back",
        "NEXT" to "talk.k_next", "PREV" to "talk.k_prev", "CH_UP" to "talk.k_ch_up", "CH_DOWN" to "talk.k_ch_down", "SLEEP" to "talk.k_sleep",
        "WAKE" to "talk.k_wake", "SETTINGS" to "talk.k_settings", "OK" to "talk.k_ok", "UP" to "talk.k_up", "DOWN" to "talk.k_down", "LEFT" to "talk.k_left", "RIGHT" to "talk.k_right", "MENU" to "talk.k_menu", "VOL_UP" to "talk.k_vol_up", "VOL_DOWN" to "talk.k_vol_down")
    private val KEY_N = mapOf("VOL_UP" to "talk.k_vol_up_n", "VOL_DOWN" to "talk.k_vol_down_n")

    // whole ids as above; a page the list adds with no words here is said as "settings"
    val SETTING_SAY = mapOf("main" to "talk.s_settings", "wifi" to "talk.s_wifi", "bluetooth" to "talk.s_bluetooth", "sound" to "talk.s_sound", "date" to "talk.s_date", "apps" to "talk.s_apps",
        "storage" to "talk.s_storage", "language" to "talk.s_language", "keyboard" to "talk.s_keyboard", "captions" to "talk.s_captions", "developer" to "talk.s_developer",
        "accessibility" to "talk.s_accessibility")

    fun say(c: Command): Say = when (c) {
        is Command.Key -> if (c.times > 1) Say(KEY_N[c.key] ?: "talk.k_vol_up_n", mapOf("n" to c.times.toString())) else KEY_SAY[c.key]?.let { Say(it) } ?: Say("talk.key", mapOf("key" to c.key.lowercase().replace('_', ' ')))
        is Command.App -> Say("talk.open_app", mapOf("name" to c.name))
        is Command.Channel -> Say("talk.channel", mapOf("name" to c.name))
        is Command.Search -> Say("talk.search_in", mapOf("name" to c.name, "q" to c.query))
        is Command.Close -> Say("talk.close_app", mapOf("name" to c.name))
        is Command.Timer -> if (c.minutes == 0) Say("talk.timer_off") else Say("talk.timer", mapOf("n" to c.minutes.toString()))
        is Command.Scene -> Say("talk.scene")
        is Command.Setting -> Say(SETTING_SAY[c.id] ?: "talk.s_settings")
        is Command.Pin -> Say("talk.open_app", mapOf("name" to c.name))
        is Command.Choice -> Say("talk.pick_one", mapOf("names" to names(c)))
    }

    private fun names1(c: Command): String = when (c) { is Command.App -> c.name; is Command.Pin -> c.name; else -> "" }

    /** The names of a [Command.Choice]'s options, "A · B · C". */
    fun names(c: Command.Choice): String = c.options.joinToString(" · ") { names1(it) }

    /** [parse] plus its words ([adb] false: no ADB link, so closing an app is said to need it); null for nothing heard. A scene is named from [scenes]. Pure: the preview calls it on every partial result. */
    fun preview(text: String, apps: Map<String, String>, scenes: Map<String, String>, adb: Boolean = true, pins: List<PinRef> = emptyList(),
                sayings: Map<String, List<String>> = emptyMap()): Plan? {
        if (fold(text).isEmpty()) return null
        val c = parse(text, apps, scenes, pins, sayings) ?: return Plan(null, Say("talk.ask", mapOf("sentence" to text.trim())))
        val say = if (c is Command.Scene) Say("talk.scene", mapOf("name" to (scenes[c.id] ?: ""))) else if (c is Command.Close && !adb) Say("talk.close_needs_adb", mapOf("name" to c.name)) else say(c)
        return Plan(c, say)
    }
}
