package dev.tvremote.channels

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * The app's language: "" follows the TV, or "vi" / "en". Plain Activities (no AppCompat: it would add ~1 MB to a 1 GB box), so
 * each one wraps its base context in [wrap]; the chosen locale is also the JVM default, which the country names ([Catalog])
 * reads.
 */
object Lang {
    val CHOICES = listOf("") + dev.tvremote.i18n.Langs.CODES

    fun pref(c: Context): String = c.getSharedPreferences("channels", Context.MODE_PRIVATE).getString("lang", "") ?: ""
    fun set(c: Context, l: String) = c.getSharedPreferences("channels", Context.MODE_PRIVATE).edit().putString("lang", l).apply()

    /** The TV's own locale, whatever the app shows. */
    fun system(): Locale = Resources.getSystem().configuration.let { if (android.os.Build.VERSION.SDK_INT >= 24) it.locales[0] else @Suppress("DEPRECATION") it.locale }

    /** The language words come in now: "vi" or anything else (English is every other one's fallback here). */
    fun code(): String = locale().language

    /** What [wrap] chose. The JVM default is not trusted: Android sets it back to the system's on a configuration change (the keyboard opening). */
    @Volatile private var chosen: Locale? = null
    fun locale(): Locale = chosen ?: Locale.getDefault()

    /** [base] with the chosen language, for an Activity's attachBaseContext. */
    fun wrap(base: Context): Context {
        val l = pref(base)
        val loc = if (l.isEmpty()) system() else dev.tvremote.i18n.Langs.locale(l)
        Locale.setDefault(loc); chosen = loc
        if (l.isEmpty()) return base
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(loc) })
    }
}
