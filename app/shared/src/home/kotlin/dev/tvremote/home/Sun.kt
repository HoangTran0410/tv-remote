package dev.tvremote.home

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun is, worked out on the TV with no network (NOAA's solar-position formulas, the ones behind their sunrise
 * calculator; good to about a minute away from the poles), and the sky colours that go with its height: the "sky" background
 * ([HomeConfig.SKY]). Pure maths, no Android, so one unit test checks it against known sunrise times (SunTest).
 * Latitude north and longitude east are positive; times are epoch milliseconds.
 */
object Sun {
    /** The sun's centre this far below the horizon is the rim at sunrise (refraction and the disc's radius included). */
    private const val RISE_ZENITH = 90.833

    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI

    /** Declination (degrees) and the equation of time (minutes) at [t]. */
    private class Pos(val decl: Double, val eqTime: Double)

    private fun pos(t: Long): Pos {
        val jd = t / 86_400_000.0 + 2440587.5
        val jc = (jd - 2451545.0) / 36525.0
        val l0 = (280.46646 + jc * (36000.76983 + jc * 0.0003032)).mod(360.0)
        val m = rad(357.52911 + jc * (35999.05029 - 0.0001537 * jc))
        val e = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc)
        val c = sin(m) * (1.914602 - jc * (0.004817 + 0.000014 * jc)) + sin(2 * m) * (0.019993 - 0.000101 * jc) + sin(3 * m) * 0.000289
        val omega = 125.04 - 1934.136 * jc
        val app = rad(l0 + c - 0.00569 - 0.00478 * sin(rad(omega)))
        val obliq = 23 + (26 + (21.448 - jc * (46.815 + jc * (0.00059 - jc * 0.001813))) / 60) / 60 + 0.00256 * cos(rad(omega))
        val decl = asin(sin(rad(obliq)) * sin(app))
        val y = tan(rad(obliq) / 2).let { it * it }
        val l0r = rad(l0)
        val eq = 4 * deg(y * sin(2 * l0r) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0r) - 0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * m))
        return Pos(deg(decl), eq)
    }

    /** The sun's height above the horizon in degrees at [t] over ([lat], [lon]): negative at night (-6 civil, -12 nautical, -18 astronomical dark). */
    fun elevation(lat: Double, lon: Double, t: Long): Double {
        val p = pos(t)
        val minutes = (t / 60_000.0).mod(1440.0)
        val ha = rad(((minutes + p.eqTime + 4 * lon).mod(1440.0)) / 4 - 180)
        val la = rad(lat); val de = rad(p.decl)
        return 90 - deg(acos((sin(la) * sin(de) + cos(la) * cos(de) * cos(ha)).coerceIn(-1.0, 1.0)))
    }

    /** True from solar midnight to solar noon (the sun is climbing): the dawn side of the sky. */
    fun rising(lon: Double, t: Long): Boolean {
        val p = pos(t)
        val minutes = (t / 60_000.0).mod(1440.0)
        return ((minutes + p.eqTime + 4 * lon).mod(1440.0)) < 720
    }

    /**
     * Sunrise and sunset (epoch ms) of the calendar day [year]-[month]-[day] AT the place (its own local date), or null in the
     * polar day or night when the sun does not cross the horizon. Two passes: the sun's declination is taken at the event itself.
     */
    fun riseSet(lat: Double, lon: Double, year: Int, month: Int, day: Int): Pair<Long, Long>? {
        val midnight = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month - 1, day) }.timeInMillis
        fun event(rise: Boolean): Long? {
            var t = midnight + 720L * 60_000                          // solar noon at the Greenwich meridian: a first guess
            repeat(3) {
                val p = pos(t)
                val c = cos(rad(RISE_ZENITH)) / (cos(rad(lat)) * cos(rad(p.decl))) - tan(rad(lat)) * tan(rad(p.decl))
                if (abs(c) > 1) return null
                val ha = deg(acos(c))
                val min = 720 - 4 * (lon + if (rise) ha else -ha) - p.eqTime   // from UTC midnight of that date, may fall in the day before or after
                t = midnight + (min * 60_000).toLong()
            }
            return t
        }
        return (event(true) ?: return null) to (event(false) ?: return null)
    }

    /** A guess for a TV that does not know where it is: east-west from the time zone (15 degrees an hour), a mild north. */
    fun guess(zoneOffsetMs: Long): Pair<Double, Double> = 20.0 to (zoneOffsetMs / 3_600_000.0 * 15).coerceIn(-180.0, 180.0)

    // ---- the colours ----

    /** Top, middle and bottom of the sky at one sun height. */
    private class Stop(val el: Double, val top: Int, val mid: Int, val bot: Int)

    // Night to day. Deep enough everywhere for the white words of Home; the low-sun stops differ by side (morning cooler and
    // pinker, evening redder and gold), the rest is shared. Heights in degrees.
    private fun s(el: Double, top: Long, mid: Long, bot: Long) = Stop(el, (0xFF000000 or top).toInt(), (0xFF000000 or mid).toInt(), (0xFF000000 or bot).toInt())
    private val NIGHT = s(-18.0, 0x05081A, 0x0A1030, 0x131C44)
    private val DAWN = listOf(NIGHT, s(-10.0, 0x0B1238, 0x241E5C, 0x5A3A78), s(-4.0, 0x1C2A66, 0x6B4A88, 0xC0647A), s(0.0, 0x2A4A8A, 0xB06A80, 0xF0A070),
        s(8.0, 0x24508F, 0x4D7EB8, 0xB8B0A0), s(25.0, 0x1A4A8C, 0x3873BC, 0x6FA3D8))
    private val DUSK = listOf(NIGHT, s(-10.0, 0x0B1238, 0x2A1C58, 0x6A3470), s(-4.0, 0x1E2A66, 0x7A3E78, 0xD0585A), s(0.0, 0x2A4484, 0xB4585E, 0xF08A50),
        s(8.0, 0x24508F, 0x5A7AB0, 0xC4A088), s(25.0, 0x1A4A8C, 0x3873BC, 0x6FA3D8))

    private fun mix(a: Int, b: Int, k: Double): Int {
        fun ch(sh: Int) = (((a shr sh and 255) * (1 - k) + (b shr sh and 255) * k) + 0.5).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The sky for a sun [elevation] (degrees) on the dawn side ([morning]) or the dusk side: three colours, top to bottom. */
    fun sky(elevation: Double, morning: Boolean): IntArray {
        val t = if (morning) DAWN else DUSK
        if (elevation <= t.first().el) return intArrayOf(t.first().top, t.first().mid, t.first().bot)
        if (elevation >= t.last().el) return intArrayOf(t.last().top, t.last().mid, t.last().bot)
        val i = t.indexOfLast { it.el <= elevation }
        val a = t[i]; val b = t[i + 1]
        val k = (elevation - a.el) / (b.el - a.el)
        return intArrayOf(mix(a.top, b.top, k), mix(a.mid, b.mid, k), mix(a.bot, b.bot, k))
    }

    /** The sky at [t] over ([lat], [lon]). */
    fun sky(lat: Double, lon: Double, t: Long): IntArray = sky(elevation(lat, lon, t), rising(lon, t))

    /** How often the sky is painted again while it is on screen: the colours move a step in this long, never visibly. */
    const val REPAINT_MS = 5L * 60_000
}
