package dev.tvremote.core.ontv
import org.json.JSONObject
import java.io.IOException
import dev.tvremote.core.B64
import dev.tvremote.core.Words

/**
 * A short message on the TV's screen, over whatever is playing: a small card (icon, title, text) in a corner that takes no focus
 * and goes away by itself. TV Setup draws it (NotifyLayer there); a phone or computer with ADB asks with a broadcast only the
 * shell can send, the way [RulesClient] does. The TV Setup app reads the message with [clean] too, so the clamps (corner,
 * seconds, lengths) are the same on both sides:
 *   {"title": "Dinner", "text": "Come down", "corner": "top-right", "seconds": 5}
 */
object Notify {
    const val RECEIVER = RulesClient.SETUP + "/.NotifyReceiver"
    val CORNERS = listOf("top-left", "top-right", "bottom-left", "bottom-right")
    const val CORNER = "top-right"
    const val SECONDS = 5
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 60
    const val MAX_DELAY = 86400                                        // a day, in seconds: "remind me in ..."
    const val MAX_TITLE = 60
    const val MAX_TEXT = 300

    /** Whether the sender asked for the message to be read aloud: true, "true", "1", "on", "yes" or 1; anything else is no. */
    fun speak(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() == 1
        is String -> v.trim().lowercase() in setOf("true", "1", "on", "yes")
        else -> false
    }

    // letters only Vietnamese has: ă ơ ư đ and the tone-marked vowels (U+1EA0..1EF9); â ê ô alone could be French
    private val VIETNAMESE = Regex("[ăơưđĂƠƯĐ\u1EA0-\u1EF9]")

    /** Scripts that name one language (the first letter found in a block decides); Latin and Han-only text stay with the hint. */
    private val SCRIPTS = listOf(
        0x0400..0x04FF to "ru", 0x0370..0x03FF to "el", 0x0590..0x05FF to "he", 0x0600..0x06FF to "ar", 0x0E00..0x0E7F to "th",
        0x0900..0x097F to "hi", 0x3040..0x30FF to "ja", 0xAC00..0xD7AF to "ko", 0x0530..0x058F to "hy", 0x10A0..0x10FF to "ka")

    /** The text's language when its letters say it by themselves (Vietnamese letters, Cyrillic, Thai ...), else null. */
    fun scriptLang(text: String): String? {
        if (VIETNAMESE.containsMatchIn(text)) return "vi"
        for (ch in text) { val cp = ch.code; SCRIPTS.firstOrNull { cp in it.first }?.let { return it.second } }
        return null
    }

    /** A language tag as its 2-letter code ("vi-VN", "pt_BR", " EN " -> "vi", "pt", "en"), or "" when it is not one. */
    fun langCode(tag: String?): String = (tag ?: "").trim().lowercase().split('-', '_').first().takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' } } ?: ""

    /** The languages to try reading [text] in, best first: its own script, the sender's [hint], the TV's [tvLang], then "en". */
    fun speechLangs(text: String, hint: String?, tvLang: String): List<String> =
        listOfNotNull(scriptLang(text), langCode(hint).ifEmpty { null }, langCode(tvLang).ifEmpty { null }, "en").distinct()

    /** The first of [speechLangs]. */
    fun speechLang(text: String, hint: String?, tvLang: String): String = speechLangs(text, hint, tvLang).first()

    /** What is read: the title, then the text (a full stop between), whichever there is. */
    fun spoken(title: String, text: String): String = listOf(title, text).map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(" ") { if (it.last() in ".!?…") it else "$it." }

    fun corner(s: String?): String = s?.trim()?.lowercase()?.takeIf { it in CORNERS } ?: CORNER

    /** Whole seconds in [MIN_SECONDS]..[MAX_SECONDS]; anything that is not a number is the default. */
    fun seconds(v: Any?): Int {
        val d = when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }
        if (d == null || d.isNaN()) return SECONDS
        return Math.round(d.coerceIn(MIN_SECONDS.toDouble(), MAX_SECONDS.toDouble())).toInt()
    }

    /** Whole seconds to wait before showing, 0..[MAX_DELAY]; anything that is not a positive number is 0 (now). */
    fun delay(v: Any?): Int {
        val d = when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }
        if (d == null || d.isNaN() || d < 1) return 0
        return Math.round(d.coerceAtMost(MAX_DELAY.toDouble())).toInt()
    }

    /** The message as TV Setup takes it, or IllegalArgumentException("empty") when there is neither title nor text. */
    fun clean(o: JSONObject): JSONObject {
        val title = o.optString("title").trim().take(MAX_TITLE)
        val text = o.optString("text").trim().take(MAX_TEXT)
        if (title.isEmpty() && text.isEmpty()) throw IllegalArgumentException("empty")
        val m = JSONObject().put("title", title).put("text", text).put("corner", corner(o.opt("corner") as? String)).put("seconds", seconds(o.opt("seconds")))
        delay(o.opt("delay")).takeIf { it > 0 }?.let { m.put("delay", it) }   // only when asked: an old TV Setup would show it at once, so [send] asks for "notifylater" first
        if (speak(o.opt("speak"))) {                                   // only when asked: an old TV Setup and today's messages stay as they were
            m.put("speak", true)
            langCode(o.opt("lang") as? String).takeIf { it.isNotEmpty() }?.let { m.put("lang", it) }   // the sender's language; an old TV Setup ignores the key
        }
        return m
    }

    /**
     * The card that asks whoever is at the TV to leave its remote alone while a job presses keys on its screen ([what]: what
     * the job does), or, with null, the one that says it is over (it takes the first one's place). Best effort: a TV without
     * TV Setup, or one that will not let it draw, just goes without.
     */

    fun busy(sh: (String) -> String, lang: String, what: String?) {
        fun t(id: String, vararg a: Pair<String, Any?>) = Words.get(lang, id, *a)
        val msg = if (what != null) JSONObject().put("title", t("notify_kt.tv_remote_working_tv"))
            .put("text", t("notify_kt.leave_tv_s_remote", "what" to what)).put("seconds", MAX_SECONDS)
        else JSONObject().put("title", t("notify_kt.tv_remote_done")).put("seconds", 3)
        try { send(sh, msg) } catch (e: Exception) { }                // top right: sign-in screens keep their buttons low
    }

    /**
     * Shows [msg] on the TV. Throws [SetupNeed.Missing] when TV Setup is absent or too old to have the receiver (or to read aloud).
     * The answer is TV Setup's: {"ok":true}, plus {"speak":"noengine"} or {"speak":"nolang","lang":"vi"} when the card showed but
     * the TV could not read it (no text-to-speech engine, or none with that language): the sender passes it on.
     */
    @Throws(IOException::class)
    fun send(sh: (String) -> String, msg: JSONObject): JSONObject {
        val clean = clean(msg)
        if (clean.optBoolean("speak")) { val have = SetupNeed.have(sh); if (!SetupNeed.ok(have, "speak")) throw SetupNeed.Missing("speak", have) }
        if (clean.has("delay")) { val have = SetupNeed.have(sh); if (!SetupNeed.ok(have, "notifylater")) throw SetupNeed.Missing("notifylater", have) }
        val arg = B64.encode(clean.toString().toByteArray(Charsets.UTF_8))
        // the card is a window over other apps: the shell may allow that for TV Setup, where the TV has no screen for it
        val out = sh("appops set ${RulesClient.SETUP} SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1; am broadcast --include-stopped-packages -n $RECEIVER --es n $arg 2>&1")
        val m = Regex("data=\"([A-Za-z0-9+/=]+)\"").find(out) ?: throw SetupNeed.Missing("notify", SetupNeed.have(sh))
        val r = JSONObject(String(B64.decode(m.groupValues[1]), Charsets.UTF_8))
        if (r.has("error")) throw IOException(if (r.optString("error") == "overlay") "notify:nooverlay" else r.optString("error"))
        return r
    }
}
