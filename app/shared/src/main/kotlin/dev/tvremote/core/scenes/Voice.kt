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
    fun wantsApp(sentence: String): Boolean { val ph = phrases ?: return false; val s = strip(norm(sentence), ph); return ph.open.matches(s) || ph.close.matches(s) }

    /** The link TV Channels (dev.tvremote.channels) takes; spaces as %20, not +. */
    fun channelLink(name: String): String = "tvchannels://find/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /** An app named [want] among [apps] (package to name): its alias in the list (a package that is there), else the installed names, loosely. */
    private fun app(want: String, apps: Map<String, String>, ph: Phrases, loose: Boolean): Pair<String, String>? {
        val alias = ph.apps.firstOrNull { want in it.names }
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
    private fun search(text: String, s: String, apps: Map<String, String>, ph: Phrases): Command.Search? {
        val re = ph.connect ?: return null
        val rest = ph.open.matchEntire(s)?.groupValues?.get(1) ?: s
        val m = re.matchEntire(rest) ?: return null
        val x = m.groupValues[1]; val y = m.groupValues[2]
        fun known(w: String) = app(w, apps, ph, false) ?: apps.entries.firstOrNull { fold(it.value) == w }?.let { it.key to it.value }
        val target = known(y) ?: return null
        if (known(x)?.first == target.first) return null                  // "mở youtube trên youtube"
        val url = ph.apps.firstOrNull { y in it.names }?.search?.replace("{q}", java.net.URLEncoder.encode(spelled(text, x), "UTF-8").replace("+", "%20"))
        return Command.Search(target.first, target.second, spelled(text, x), url)
    }

    private fun channelName(w: String) = w.replace(Regex("([a-z]) (\\d)"), "\$1\$2")

    /** [apps]: package to name; [scenes]: id to name. Null means: ask the TV's Assistant. */
    fun parse(text: String, apps: Map<String, String>, scenes: Map<String, String>): Command? {
        val raw = fold(text)
        if (raw.isEmpty()) return null
        scenes.entries.firstOrNull { (_, n) -> fold(n) == raw || fold("canh $n") == raw || fold("scene $n") == raw }?.let { return Command.Scene(it.key) }
        val ph = phrases ?: return null
        val s = strip(norm(text), ph)
        ph.keys[s]?.let { return Command.Key(it, 1) }
        ph.volUp.matchEntire(s)?.let { return Command.Key("VOL_UP", minOf(it.groupValues[1].toIntOrNull() ?: 3, 15)) }
        ph.volDown.matchEntire(s)?.let { return Command.Key("VOL_DOWN", minOf(it.groupValues[1].toIntOrNull() ?: 3, 15)) }
        if (s in ph.timerCancel) return Command.Timer(0)
        ph.timerSet.matchEntire(s)?.let { m ->
            val hours = m.groupValues[2] in ph.hours
            return Command.Timer(minOf((m.groupValues[1].toIntOrNull() ?: 720) * (if (hours) 60 else 1), 720))   // "99999999999 phút": the most, not a crash
        }
        ph.close.matchEntire(s)?.let { m ->
            val hit = app(m.groupValues[1], apps, ph, true)
            if (hit != null && hit.first !in ph.neverClose) return Command.Close(hit.first, hit.second)
        }
        search(text, s, apps, ph)?.let { return it }
        val verb = ph.open.matchEntire(s)?.groupValues?.get(1)
        val want = verb ?: s
        app(want, apps, ph, false)?.let { return Command.App(it.first, it.second) }
        // before the apps by name: "mở kênh vtv1" is a channel, not an app called "kenh vtv1"
        ph.channel.matchEntire(s)?.let { m -> val n = m.groupValues[1]; return Command.Channel(if (ph.channelNames.matches(n)) channelName(n) else n) }
        if (ph.channelNames.matches(want)) return Command.Channel(channelName(want))
        ph.tuneRe.matchEntire(s)?.let { return Command.Channel(it.groupValues[1]) }
        if (verb != null) app(verb, apps, ph, true)?.let { return Command.App(it.first, it.second) }
        return null
    }

    /**
     * [cmd] (from [parse] of [text]) names the app in full, its alias or its whole name, not a start of it: the TV's voice may then open it
     * on a pause in the partial text, without waiting for the recognizer's end ("mở you" finds YouTube loosely, so it never counts).
     */
    fun whole(text: String, cmd: Command?): Boolean {
        if (cmd !is Command.App) return false
        val ph = phrases ?: return false
        val s = strip(norm(text), ph)
        val want = ph.open.matchEntire(s)?.groupValues?.get(1) ?: s
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

    fun say(c: Command): Say = when (c) {
        is Command.Key -> if (c.times > 1) Say(KEY_N[c.key] ?: "talk.k_vol_up_n", mapOf("n" to c.times.toString())) else KEY_SAY[c.key]?.let { Say(it) } ?: Say("talk.key", mapOf("key" to c.key.lowercase().replace('_', ' ')))
        is Command.App -> Say("talk.open_app", mapOf("name" to c.name))
        is Command.Channel -> Say("talk.channel", mapOf("name" to c.name))
        is Command.Search -> Say("talk.search_in", mapOf("name" to c.name, "q" to c.query))
        is Command.Close -> Say("talk.close_app", mapOf("name" to c.name))
        is Command.Timer -> if (c.minutes == 0) Say("talk.timer_off") else Say("talk.timer", mapOf("n" to c.minutes.toString()))
        is Command.Scene -> Say("talk.scene")
    }

    /** [parse] plus its words ([adb] false: no ADB link, so closing an app is said to need it); null for nothing heard. A scene is named from [scenes]. Pure: the preview calls it on every partial result. */
    fun preview(text: String, apps: Map<String, String>, scenes: Map<String, String>, adb: Boolean = true): Plan? {
        if (fold(text).isEmpty()) return null
        val c = parse(text, apps, scenes) ?: return Plan(null, Say("talk.ask", mapOf("sentence" to text.trim())))
        val say = if (c is Command.Scene) Say("talk.scene", mapOf("name" to (scenes[c.id] ?: ""))) else if (c is Command.Close && !adb) Say("talk.close_needs_adb", mapOf("name" to c.name)) else say(c)
        return Plan(c, say)
    }
}
