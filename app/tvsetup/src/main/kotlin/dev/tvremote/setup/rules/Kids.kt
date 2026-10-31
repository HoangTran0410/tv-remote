package dev.tvremote.setup.rules

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * The always-free apps of kids mode, and the day's use of the TV for the total limit. Kids mode asks for a code over EVERY app, so the
 * list of what it must never cover matters more than the list it covers: a cut-off home screen, keyboard or PIN screen locks the parent
 * out too, with nobody left to fix it but ADB.
 */
internal object Kids {
    /**
     * Named, for TVs where the questions below come back empty (no package visibility, a launcher not yet installed). Google TV's home
     * (launcherx), Android TV's (tvlauncher, with its recommendations row), the system's windows (systemui, the "android" package that shows
     * the volume bar and dialogs), the TV's keyboards, TV Setup (the PIN screen is its own) with its Keys add-on, and TV Home. Settings is
     * NOT here: in kids mode it asks like any app.
     */
    val ALWAYS = setOf(
        "android", "com.android.systemui",
        "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.tvrecommendations",
        "com.google.android.leanbackime", "com.google.android.inputmethod.latin", "com.android.inputmethod.latin",
        "dev.tvremote.setup", "dev.tvremote.setup.keys", "dev.tvremote.home",
    )

    /** [ALWAYS] plus every home screen the TV has (the one in use and the ones to choose from), every keyboard on it, and the one in use. */
    fun assemble(homes: Collection<String>, keyboards: Collection<String>, inUse: String?): Set<String> =
        (ALWAYS + homes + keyboards + listOfNotNull(inUse?.substringBefore('/')?.takeIf { it.isNotEmpty() })) - SETTINGS

    /** Settings answers to the HOME intent on Google TV (its fallback home), yet in kids mode it asks like any app: the child must not reach Wi-Fi, accounts, installs. */
    private val SETTINGS = setOf("com.android.tv.settings", "com.android.settings", "com.google.android.tv.settings")

    @Volatile private var cached: Pair<Long, Set<String>>? = null

    /** The packages kids mode never asks for; looked up again every 30 s (a launcher or keyboard may be installed or chosen meanwhile). */
    fun free(c: Context, now: Long = System.currentTimeMillis()): Set<String> {
        cached?.let { if (now - it.first in 0..30_000L) return it.second }
        val pm = c.packageManager
        val homes = try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName } } catch (e: Exception) { emptyList() }
        val inUseHome = try { pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName } catch (e: Exception) { null }
        val keyboards = try {
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).inputMethodList.map { it.packageName }
        } catch (e: Exception) { emptyList() }
        val ime = try { android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) } catch (e: Exception) { null }
        return assemble(homes + listOfNotNull(inUseHome), keyboards, ime).also { cached = now to it }
    }

    /** Seconds of use today that count for the total: [used] (Stats.today) without the free apps. */
    fun usedToday(c: Context, used: JSONObject): Long = Rules.totalUsedSec(used, free(c))
}

/**
 * Wrong codes in a row, and how long the next try waits: 5 wrong cost a minute, the next 5 five minutes, then 15, then an hour
 * (and an hour after that). Kept in prefs, so switching the TV off or restarting the app does not give the child five new tries;
 * a right code forgets it all.
 */
internal object Lockout {
    private const val PREFS = "lockout"
    const val TRIES = 5
    val WAITS_MIN = longArrayOf(1, 5, 15, 60)

    /** [wrong] in a row now, [level] lockouts so far (which wait is next), [until] when the last wait ends. */
    data class S(val wrong: Int = 0, val level: Int = 0, val until: Long = 0L)

    fun fail(s: S, now: Long): S {
        if (now < s.until) return s                                    // still waiting: the try was never allowed
        val w = s.wrong + 1
        if (w < TRIES) return S(w, s.level, s.until)
        return S(0, minOf(s.level + 1, WAITS_MIN.size), now + WAITS_MIN[minOf(s.level, WAITS_MIN.size - 1)] * 60_000L)
    }

    fun left(s: S, now: Long): Long = maxOf(0L, s.until - now)

    @Synchronized private fun load(c: Context): S =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { S(it.getInt("wrong", 0), it.getInt("level", 0), it.getLong("until", 0L)) }

    @Synchronized private fun save(c: Context, s: S) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("wrong", s.wrong).putInt("level", s.level).putLong("until", s.until).commit() }

    /** Wrong codes in a row since the last wait or right code (3 is when the parents are told). */
    fun streak(c: Context): Int = load(c).wrong

    /** When the wait ends, 0 when not waiting. */
    fun until(c: Context, now: Long): Long = load(c).let { if (it.until > now) it.until else 0L }

    /** A wrong code: the wait it earned (ms, 0 for none yet). */
    @Synchronized fun wrong(c: Context, now: Long): Long = fail(load(c), now).also { save(c, it) }.let { left(it, now) }

    @Synchronized fun right(c: Context) { save(c, S()) }
}
