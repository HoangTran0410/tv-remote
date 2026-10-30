package dev.tvremote.home.wall

import android.content.Context
import dev.tvremote.home.Sun
import org.json.JSONObject
import java.util.TimeZone

/**
 * The "sky" background ([dev.tvremote.home.HomeConfig.SKY]): the colours of [Sun.sky] at the place TV Home already knows for its
 * weather (the weather widget's saved place), else the time zone's rough longitude. Nothing is read or worked out until the sky is the
 * background; it is a three-colour gradient like the others (no per-frame work), repainted by HomeActivity every [Sun.REPAINT_MS].
 */
object Sky {
    @Volatile private var place: Pair<Double, Double>? = null

    /** Reads the saved weather place (one small prefs file); call before painting. */
    fun load(c: Context) {
        place = try {
            val o = JSONObject(c.getSharedPreferences("weather", Context.MODE_PRIVATE).getString("place", null) ?: "")
            if (o.has("lat") && o.has("lon")) o.getDouble("lat") to o.getDouble("lon") else null
        } catch (e: Exception) { null }
    }

    private fun where() = place ?: Sun.guess(TimeZone.getDefault().rawOffset.toLong())

    /** Top, middle, bottom of the sky now. */
    fun colors(now: Long = System.currentTimeMillis()): IntArray = where().let { Sun.sky(it.first, it.second, now) }

    /** Changes when the picture should be painted again (every [Sun.REPAINT_MS], and when the place changes). */
    fun stamp(now: Long = System.currentTimeMillis()) = "sky" + now / Sun.REPAINT_MS + place
}
