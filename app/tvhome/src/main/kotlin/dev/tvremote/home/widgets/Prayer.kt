package dev.tvremote.home.widgets

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Prayer times and the Hijri date, offline. The sun's declination and the equation of time come from the Astronomical Almanac's low
 * precision series (good to about a minute), the hour angle from spherical trigonometry; written here from those formulas, not from
 * another library (praytimes.js is LGPL). The list of methods and the idea of the widget follow egyjs/afnon-android-tv-launcher (MIT).
 * Where the sun never reaches the Fajr / Isha angle (high latitudes) the "angle-based" rule is used: the night's share is angle / 60.
 * The Hijri date is the tabular (civil) calendar, the same every year; [hijri] takes an adjust of a day either way, as the moon is
 * seen on the day (the Umm al-Qura calendar differs from the tabular by up to a day).
 */
object Prayer {
    /** A method: the sun's angle below the horizon for Fajr and Isha (or [ishaMin] minutes after Maghrib), Maghrib's angle when it is not the sunset, [tune] minutes added to fajr, sunrise, dhuhr, asr, maghrib, isha. */
    class Method(val fajr: Double, val isha: Double, val ishaMin: Int = 0, val maghribAngle: Double = 0.0, val tune: IntArray = IntArray(6))

    val METHODS: Map<String, Method> = linkedMapOf(
        "mwl" to Method(18.0, 17.0),                                    // Muslim World League
        "isna" to Method(15.0, 15.0),                                   // Islamic Society of North America
        "egypt" to Method(19.5, 17.5),                                  // Egyptian General Authority of Survey
        "ummalqura" to Method(18.5, 0.0, ishaMin = 90),                 // Makkah: Isha 90 min after Maghrib (the 120 of Ramadan is not applied)
        "karachi" to Method(18.0, 18.0),                                // University of Islamic Sciences, Karachi
        "jakim" to Method(20.0, 18.0),                                  // Malaysia
        "kemenag" to Method(20.0, 18.0),                                // Indonesia
        "singapore" to Method(20.0, 18.0),                              // MUIS
        "turkey" to Method(18.0, 17.0, tune = intArrayOf(0, -7, 5, 4, 7, 0)),   // Diyanet: its published times carry these minutes
        "tehran" to Method(17.7, 14.0, maghribAngle = 4.5),             // Institute of Geophysics, University of Tehran
    )
    val IDS = METHODS.keys.toList()

    /** The method most people in this time zone / country follow; MWL where nothing is known. [tz] a zone id ("Asia/Jakarta"), [country] ISO 3166 ("ID"). */
    fun defaultMethod(tz: String, country: String): String = when {
        tz in setOf("Asia/Riyadh", "Asia/Mecca", "Asia/Kuwait", "Asia/Qatar", "Asia/Bahrain", "Asia/Dubai", "Asia/Muscat", "Asia/Aden") || country in setOf("SA", "KW", "QA", "BH", "AE", "OM", "YE") -> "ummalqura"
        tz in setOf("Africa/Cairo", "Africa/Tripoli", "Africa/Khartoum", "Asia/Damascus", "Asia/Beirut", "Asia/Baghdad", "Asia/Amman") || country in setOf("EG", "LY", "SD", "SY", "LB", "IQ", "JO") -> "egypt"
        tz == "Asia/Kuala_Lumpur" || tz == "Asia/Kuching" || tz == "Asia/Brunei" || country in setOf("MY", "BN") -> "jakim"
        tz in setOf("Asia/Jakarta", "Asia/Pontianak", "Asia/Makassar", "Asia/Jayapura") || country == "ID" -> "kemenag"
        tz == "Asia/Singapore" || country == "SG" -> "singapore"
        tz == "Europe/Istanbul" || tz == "Asia/Istanbul" || country == "TR" -> "turkey"
        tz == "Asia/Tehran" || country == "IR" -> "tehran"
        tz in setOf("Asia/Karachi", "Asia/Dhaka", "Asia/Kabul", "Asia/Kolkata", "Asia/Calcutta", "Asia/Colombo") || country in setOf("PK", "BD", "AF", "IN", "LK") -> "karachi"
        tz.startsWith("America/") || country in setOf("US", "CA") -> "isna"
        else -> "mwl"
    }

    /** One day's times as hours of the local clock (0 until 24, may pass 24 for an Isha after midnight); NaN where the sun does not do it. */
    class Times(val fajr: Double, val sunrise: Double, val dhuhr: Double, val asr: Double, val maghrib: Double, val isha: Double) {
        fun all() = doubleArrayOf(fajr, sunrise, dhuhr, asr, maghrib, isha)
    }

    private const val RAD = PI / 180
    private fun fix(a: Double, b: Double): Double { val r = a - b * floor(a / b); return r }

    /** The sun's declination (degrees) and the equation of time (hours) at Julian date [jd]. */
    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0) * RAD
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360.0) * RAD
        val e = (23.439 - 0.00000036 * d) * RAD
        val ra = fix(atan2(cos(e) * sin(l), cos(l)) / RAD / 15, 24.0)
        val eqt = q / 15 - ra
        return asin(sin(e) * sin(l)) / RAD to if (eqt > 12) eqt - 24 else if (eqt < -12) eqt + 24 else eqt   // around the equinox the two angles sit on different sides of 0 / 24 h
    }

    /**
     * The five prayers and the sunrise of the day [d]/[m]/[y] at [lat], [lon] (degrees, east positive), the clock [tz] hours from UTC.
     * [hanafi]: the Asr when a shadow is twice the object (Hanafi), else once (the other schools).
     */
    fun times(y: Int, m: Int, d: Int, lat: Double, lon: Double, tz: Double, method: Method, hanafi: Boolean): Times {
        val base = Lunar.jd(d, m, y) - 0.5 - lon / 360                    // 0h at this longitude, as a Julian date
        fun mid(t: Double) = 12 - sun(base + t / 24).second
        // the hour at which the sun is [alt] degrees above the horizon, before (-1) or after (+1) noon
        fun at(alt: Double, t: Double, dir: Int): Double {
            val decl = sun(base + t / 24).first * RAD
            val c = (sin(alt * RAD) - sin(lat * RAD) * sin(decl)) / (cos(lat * RAD) * cos(decl))
            return if (c < -1 || c > 1) Double.NaN else mid(t) + dir * acos(c) / RAD / 15
        }
        fun asr(t: Double): Double {
            val decl = sun(base + t / 24).first
            val alt = atan(1 / ((if (hanafi) 2 else 1) + tan(abs(lat - decl) * RAD))) / RAD
            return at(alt, t, 1)
        }
        val horizon = -0.833                                              // the sun's edge and the air's bending
        var fajr = 5.0; var rise = 6.0; var noon = 12.0; var a = 15.0; var set = 18.0; var isha = 19.0
        repeat(3) {
            fajr = at(-method.fajr, fajr.orNoon(), -1); rise = at(horizon, rise.orNoon(), -1); noon = mid(noon)
            a = asr(a.orNoon()); set = at(if (method.maghribAngle > 0) -method.maghribAngle else horizon, set.orNoon(), 1)
            isha = if (method.ishaMin > 0) set + method.ishaMin / 60.0 else at(-method.isha, isha.orNoon(), 1)
        }
        // the angle-based rule: the night is shared out in proportion to the angle (angle / 60), and a Fajr / Isha that is further from the sun than that is moved in
        val night = 24 - (set - rise)
        if (!night.isNaN()) {
            val fp = method.fajr / 60 * night
            if (fajr.isNaN() || rise - fajr > fp) fajr = rise - fp
            if (method.ishaMin == 0) { val ip = method.isha / 60 * night; if (isha.isNaN() || isha - set > ip) isha = set + ip }
        }
        val shift = tz - lon / 15
        val t = method.tune
        return Times(fajr + shift + t[0] / 60.0, rise + shift + t[1] / 60.0, noon + shift + t[2] / 60.0, a + shift + t[3] / 60.0, set + shift + t[4] / 60.0, isha + shift + t[5] / 60.0)
    }

    private fun Double.orNoon() = if (isNaN()) 12.0 else this

    /** A Hijri date: the day, the month (1-12) and the year. */
    data class Hijri(val day: Int, val month: Int, val year: Int)

    /** The tabular (civil) Hijri date of a Gregorian day, [adjust] days (-1, 0, 1) added first. */
    fun hijri(d: Int, m: Int, y: Int, adjust: Int = 0): Hijri {
        var l = Lunar.jd(d, m, y) + adjust - 1948440 + 10632                  // the days since the Hijri epoch, in whole 30-year cycles of 10631 days
        val n = (l - 1) / 10631
        l = l - 10631 * n + 354
        val j = ((10985 - l) / 5316) * ((50 * l) / 17719) + (l / 5670) * ((43 * l) / 15238)
        l = l - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29
        val mo = (24 * l) / 709
        return Hijri(l - (709 * mo) / 24, mo, 30 * n + j - 30)
    }

    /** What the widget shows at a moment: the index of the next prayer (0 fajr, 2 dhuhr, 3 asr, 4 maghrib, 5 isha; the sunrise is not one), minutes to it, and whether it is tomorrow's. */
    class Next(val index: Int, val minutes: Int, val tomorrow: Boolean)

    /** The next prayer after [now] (hours of the local day) from [today]'s times; after the Isha it is [tomorrow]'s Fajr (null when that does not exist). */
    fun next(today: Times, tomorrow: Times?, now: Double): Next? {
        val ts = today.all()
        for (i in intArrayOf(0, 2, 3, 4, 5)) if (!ts[i].isNaN() && ts[i] > now) return Next(i, Math.ceil((ts[i] - now) * 60 - 1e-6).toInt(), false)
        val f = tomorrow?.fajr?.takeIf { !it.isNaN() } ?: return null
        return Next(0, Math.ceil((f + 24 - now) * 60 - 1e-6).toInt(), true)
    }

    /** "05:41" from hours; "--:--" for NaN. 24-hour [h24], else "5:41" (the am / pm sign is not drawn: the five times of one day are never confused). */
    fun clock(h: Double, h24: Boolean = true): String {
        if (h.isNaN()) return "--:--"
        val mins = Math.floorMod(Math.round(h * 60).toInt(), 24 * 60)
        val hh = mins / 60; val mm = mins % 60
        val shown = if (h24) hh else (hh % 12).let { if (it == 0) 12 else it }
        return "%s:%02d".format(if (h24) "%02d".format(shown) else shown.toString(), mm)
    }
}
