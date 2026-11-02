package dev.tvremote.home

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** TV Home in the language chosen for it ("lang", [HomeConfig.LANGS]) rather than the TV's, for a TV set to a language TV Home lacks. */
object Lang {
    /** The language the screen was last built in, so a change of it builds the screen again. */
    var shown = ""
        private set

    /** The last locale set as the default here, and the TV's own one seen before it. */
    private var mine: Locale? = null
    private var tvSeen: Locale? = null

    /**
     * The TV's language. Once TV Home set another default, the system Resources of this process report that one too (an English TV
     * read Vietnamese after Vietnamese -> "Same as the TV": the date stayed Vietnamese, the row said "Tiếng Việt" until the app was
     * restarted), so the TV's is the value seen when it differs from ours; a real change of the TV's language is a new value.
     */
    fun tv(): Locale {
        val now = Resources.getSystem().locale0()
        if (now != mine || tvSeen == null) tvSeen = now
        return tvSeen ?: now
    }

    fun wrap(base: Context): Context {
        shown = try { Store.get(base).optString("lang") } catch (e: Exception) { "" }
        // the default locale too: dates and numbers are formatted with it (DateFormat, String.format) all over the screen
        val loc = if (shown.isEmpty()) tv() else dev.tvremote.i18n.Langs.locale(shown)
        tv()                                                          // seen before ours is set
        Locale.setDefault(loc); mine = loc
        if (shown.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration).apply { setLocale(loc) }
        return base.createConfigurationContext(cfg)
    }
}

/** The first locale; Configuration.locales is API 24, the TV boxes on Android 5-6 have only .locale. */
fun Resources.locale0(): Locale = if (android.os.Build.VERSION.SDK_INT >= 24) configuration.locales[0] else @Suppress("DEPRECATION") configuration.locale
