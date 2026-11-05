package dev.tvremote.home

import org.json.JSONObject

/**
 * The two look choices a theme can make beyond the wallpaper (HomeConfig "tstyle" and "accent", app/DESIGN-TV.md 2.5b), read once per
 * change of the settings by [read] and used by the tiles and the widgets built after it. The defaults are TV Home as it was.
 */
object Look {
    @Volatile var tstyle = "banner"
    /** The accent as a colour, 0 when none is chosen (today's colours). */
    @Volatile var accent = 0

    fun read(conf: JSONObject) {
        tstyle = conf.optString("tstyle").takeIf { it in HomeConfig.TSTYLES } ?: "banner"
        accent = HomeConfig.ACCENTS[conf.optString("accent")]?.let { argb(it) } ?: 0
    }

    /** "#RRGGBB" as an opaque colour. */
    fun argb(hex: String) = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()

    /** The accent, or [def] when none is chosen. */
    fun accent(def: Int) = if (accent != 0) accent else def
}
