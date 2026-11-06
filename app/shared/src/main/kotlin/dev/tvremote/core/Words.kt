package dev.tvremote.core

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The apps' words by id, from the files the page reads too (app/shared/i18n/<lang>.json, js/i18n.js): vi.json lists every
 * id, each other language the same ids; a missing one falls back to English, then Vietnamese, as on the page. `{name}` in the
 * words is filled from the args; an entry `{ "one": ..., "few": ..., "other": ... }` is picked by the arg `n` ([plural]).
 * A new language is a file there, nothing in the code.
 */
object Words {
    private val books = ConcurrentHashMap<String, Map<String, Any>>()   // first: lang below reads it while the object starts

    /** Opens i18n/<lang>.json: the APKs' assets (packed there next to the page), the computer app's app/shared/i18n. */
    @Volatile var open: (String) -> InputStream? = { name ->                         // unit tests: run in app/shared or app/<app>
        listOf(File(name), File("../shared", name)).firstOrNull { it.isFile }?.inputStream() }
        set(v) { field = v; books.clear() }

    /** The language the app's own screens speak (the phone's Settings choice, the TV's language). */
    @Volatile var lang: String = pick(java.util.Locale.getDefault().language)

    /** The app's language is written right to left (Arabic): the Phone app turns its Settings, About and dialogs (not the remote). */
    val rtl: Boolean get() = Character.getDirectionality((dev.tvremote.i18n.Langs.NAMES[lang] ?: "").firstOrNull { Character.isLetter(it) } ?: 'a').let {
        it == Character.DIRECTIONALITY_RIGHT_TO_LEFT || it == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC }

    private fun book(l: String): Map<String, Any> = books.getOrPut(l) {
        try {
            val j = open("i18n/$l.json")?.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } ?: return@getOrPut emptyMap()
            j.keys().asSequence().toList().associateWith { k ->                    // keys(): Android's org.json has no keySet()
                j.get(k).let { v -> if (v is JSONObject) v.keys().asSequence().toList().associateWith { v.getString(it) } else v.toString() } }
        } catch (e: Exception) { emptyMap() }
    }

    /** A language the files have, else English: what a page or a phone asking for [l] ("vi", "en", "pt-BR"...) gets. */
    fun pick(l: String?): String {
        val c = (l ?: "").lowercase().substringBefore('-').substringBefore('_')
        return if (c == "vi" || c == "en" || (c.matches(Regex("[a-z]{2,3}")) && book(c).isNotEmpty())) c else "en"
    }

    /** The words of [id] in [lang]. */
    fun get(lang: String, id: String, vararg args: Pair<String, Any?>): String {
        var s: Any? = null
        var from = lang                                               // the language the words came from: its own plural rule
        for (l in linkedSetOf(lang, "en", "vi")) { s = book(l)[id]; from = l; if (s != null) break }
        if (s == null) return id
        val n = args.firstOrNull { it.first == "n" }?.second?.toString()?.toDoubleOrNull()
        val text = if (s is Map<*, *>) (s[if (n == null) "other" else plural(from, n)] ?: s["other"]).toString() else s.toString()
        if (args.isEmpty()) return text
        val m = args.toMap()
        return Regex("\\{(\\w+)\\}").replace(text) { r -> if (r.groupValues[1] in m) m[r.groupValues[1]].toString() else r.value }
    }

    /**
     * CLDR's plural category of [n] in [lang] (zero, one, two, few, many, other), as Intl.PluralRules gives the page (js/i18n.js):
     * Android 21 has no PluralRules, so the rules of the languages in langs.json are here. A language not listed: one for 1, else other.
     */
    fun plural(lang: String, n: Double): String {
        val i = n.toLong()
        val whole = n == i.toDouble()
        val m10 = i % 10; val m100 = i % 100
        return when (lang) {
            "vi" -> "other"
            "ru" -> when {
                !whole -> "other"
                m10 == 1L && m100 != 11L -> "one"
                m10 in 2..4 && m100 !in 12..14 -> "few"
                else -> "many"
            }
            "ar" -> when {
                !whole -> "other"
                i == 0L -> "zero"; i == 1L -> "one"; i == 2L -> "two"
                m100 in 3..10 -> "few"
                m100 in 11..99 -> "many"
                else -> "other"
            }
            "fr" -> if (n >= 0 && n < 2) "one" else "other"           // 0 and 1.5 are singular in French
            else -> if (n == 1.0) "one" else "other"
        }
    }

    /** In the language of the app's own screens. */
    fun t(id: String, vararg args: Pair<String, Any?>): String = get(lang, id, *args)
}

/** [Words.t]: the words of [id] in the app's language. */
fun T(id: String, vararg args: Pair<String, Any?>): String = Words.t(id, *args)
