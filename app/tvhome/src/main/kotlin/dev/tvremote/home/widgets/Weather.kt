package dev.tvremote.home.widgets

import android.content.Context
import dev.tvremote.guard.Age
import android.location.Geocoder
import android.location.LocationManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The weather widget's data: Open-Meteo (no key), the place worked out by itself. Everything here runs off the main thread; the widget
 * ([Widgets]) only reads [cached]. Kept in prefs with its time, so a restart or a dead network still shows the last one.
 */
object Weather {
    /** The sky, what a WMO weather code comes down to: one icon and one sentence each. */
    enum class Kind { CLEAR, PARTLY, CLOUD, FOG, DRIZZLE, RAIN, SNOW, STORM }

    class Place(val name: String, val lat: Double, val lon: Double)
    class Day(val date: String, val kind: Kind, val min: Int, val max: Int)
    class Forecast(val place: String, val temp: Int, val kind: Kind, val night: Boolean, val days: List<Day>, val at: Long)

    /** The air now at the place: the US air quality index and PM2.5 in µg/m³ (Open-Meteo's air-quality API, same place as the weather). */
    class Air(val aqi: Int, val pm25: Int, val at: Long)

    const val FRESH_MS = 45 * 60_000L                                  // asked again after this, while the home is shown
    const val RETRY_MS = 5 * 60_000L                                   // after a failure: not on every minute's tick
    private const val DAYS = 4                                          // today and the three after it

    fun kind(code: Int) = when (code) {
        0, 1 -> Kind.CLEAR
        2 -> Kind.PARTLY
        3 -> Kind.CLOUD
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57 -> Kind.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.CLOUD
    }

    /** Open-Meteo's forecast answer as a [Forecast] for [place] at [now]; null when it is not one (missing parts, a "reason" error). */
    fun parse(place: String, json: String, now: Long): Forecast? = try {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val time = d.getJSONArray("time"); val code = d.getJSONArray("weather_code")
        val hi = d.getJSONArray("temperature_2m_max"); val lo = d.getJSONArray("temperature_2m_min")
        val days = (0 until minOf(DAYS, time.length())).map { Day(time.getString(it), kind(code.getInt(it)), Math.round(lo.getDouble(it)).toInt(), Math.round(hi.getDouble(it)).toInt()) }
        if (days.isEmpty()) null
        else Forecast(place, Math.round(cur.getDouble("temperature_2m")).toInt(), kind(cur.getInt("weather_code")), cur.optInt("is_day", 1) == 0, days, now)
    } catch (e: Exception) { null }

    fun toJson(f: Forecast) = JSONObject().put("place", f.place).put("temp", f.temp).put("kind", f.kind.name).put("night", f.night).put("at", f.at)
        .put("days", JSONArray(f.days.map { JSONObject().put("date", it.date).put("kind", it.kind.name).put("min", it.min).put("max", it.max) }))

    fun fromJson(s: String): Forecast? = try {
        val o = JSONObject(s); val a = o.getJSONArray("days")
        Forecast(o.getString("place"), o.getInt("temp"), Kind.valueOf(o.getString("kind")), o.getBoolean("night"),
            (0 until a.length()).map { a.getJSONObject(it).let { d -> Day(d.getString("date"), Kind.valueOf(d.getString("kind")), d.getInt("min"), d.getInt("max")) } }, o.getLong("at"))
    } catch (e: Exception) { null }

    /** Open-Meteo's air-quality answer as an [Air] at [now]; null when it has no index (an error, or a place with no data). */
    fun parseAir(json: String, now: Long): Air? = try {
        val cur = JSONObject(json).getJSONObject("current")
        Air(Math.round(cur.getDouble("us_aqi")).toInt(), Math.round(cur.optDouble("pm2_5", 0.0)).toInt(), now)
    } catch (e: Exception) { null }

    /** The index as one of six levels, 0 good .. 5 hazardous (the US EPA's bands). */
    fun airLevel(aqi: Int) = when { aqi <= 50 -> 0; aqi <= 100 -> 1; aqi <= 150 -> 2; aqi <= 200 -> 3; aqi <= 300 -> 4; else -> 5 }

    fun airToJson(a: Air) = JSONObject().put("aqi", a.aqi).put("pm25", a.pm25).put("at", a.at)
    fun airFromJson(s: String): Air? = try { JSONObject(s).let { Air(it.getInt("aqi"), it.getInt("pm25"), it.getLong("at")) } } catch (e: Exception) { null }

    /** The first geocoding hit for a typed city (`results[0]`), null when there is none. */
    fun parseGeocode(json: String): Place? = try {
        val r = JSONObject(json).getJSONArray("results").getJSONObject(0)
        Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    } catch (e: Exception) { null }

    /** An IP-location answer: ipwho.is (`success`, `city`) or geojs.io (the numbers as strings); null when it has no coordinates. */
    fun parseIp(json: String): Place? = try {
        val o = JSONObject(json)
        if (o.has("success") && !o.optBoolean("success")) null
        else {
            val lat = o.get("latitude").toString().toDouble(); val lon = o.get("longitude").toString().toDouble()
            if (lat == 0.0 && lon == 0.0) null else Place(o.optString("city").ifEmpty { o.optString("region") }, lat, lon)
        }
    } catch (e: Exception) { null }

    /** Where the places come from, so the order below is testable without a network or a TV. */
    interface Sources {
        fun geocode(text: String): Place?
        fun device(): Place?
        fun ip(): Place?
    }

    /**
     * The place to ask for: the city typed in the settings; else the device's own last known location (a TV with none gives nothing);
     * else the internet address's. A typed city that is not found is not replaced by a guess: the widget says so rather than show
     * another city's weather.
     */
    fun resolve(typed: String, src: Sources): Place? =
        if (typed.isNotBlank()) src.geocode(typed.trim()) else src.device() ?: src.ip()

    // ---- the network and the prefs, the thin part ----

    private fun prefs(c: Context) = c.getSharedPreferences("weather", Context.MODE_PRIVATE)

    /** The place the weather found (the typed city, the device's location or the internet address's), kept with the forecast; null before the first answer. */
    fun place(c: Context): Place? = prefs(c).getString("place", null)?.let { s -> runCatching { JSONObject(s).let { Place(it.optString("name"), it.getDouble("lat"), it.getDouble("lon")) } }.getOrNull() }

    /** True when [place] is missing or is for another typed city than [typed]: the prayer widget alone asks the weather's resolver again. */
    fun placeStale(c: Context, typed: String) = place(c) == null || prefs(c).getString("typed", "") != typed.trim()

    fun cached(c: Context): Forecast? = prefs(c).getString("fc", null)?.let { fromJson(it) }

    fun cachedAir(c: Context): Air? = prefs(c).getString("aq", null)?.let { airFromJson(it) }

    /** True when the widget should ask again now: no answer yet, or an old one, and not just after a failure. [air]: the air widget is on too. */
    fun due(c: Context, typed: String, now: Long = System.currentTimeMillis(), air: Boolean = false): Boolean {
        val p = prefs(c); val f = cached(c)
        if (now - p.getLong("tried", 0) < RETRY_MS && now >= p.getLong("tried", 0)) return false
        return stale(f?.at, if (air) cachedAir(c)?.at else null, air, p.getString("typed", "") == typed.trim(), now)
    }

    /** The pure part of [due]: a stamp ahead of [now] is stale too (a box that booted with its clock a year ahead saved it). */
    internal fun stale(fcAt: Long?, airAt: Long?, air: Boolean, sameCity: Boolean, now: Long) =
        fcAt == null || !Age.fresh(fcAt, FRESH_MS, now) || !sameCity   // a new city asked for: at once
            || (air && (airAt == null || !Age.fresh(airAt, FRESH_MS, now)))

    private var busy = false

    /** Asks the network (a thread of its own, the widgets' reading must not wait for it) and calls [done] on that thread when something changed. */
    fun refresh(c: Context, typed: String, lang: String, air: Boolean = false, done: () -> Unit) {
        synchronized(this) { if (busy) return; busy = true }
        Thread {
            try {
                val ctx = c.applicationContext; val now = System.currentTimeMillis(); val p = prefs(ctx)
                val place = resolve(typed, object : Sources {
                    override fun geocode(text: String) = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=$lang&name=" + URLEncoder.encode(text, "UTF-8"))?.let { parseGeocode(it) }
                    override fun device() = deviceLocation(ctx)
                    override fun ip() = get("https://ipwho.is/?fields=success,city,region,latitude,longitude")?.let { parseIp(it) }
                        ?: get("https://get.geojs.io/v1/ip/geo.json")?.let { parseIp(it) }
                }) ?: p.getString("place", null)?.takeIf { typed.trim() == p.getString("typed", "") }?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { Place(it.optString("name"), it.optDouble("lat"), it.optDouble("lon")) }
                val f = place?.let { w ->
                    get("https://api.open-meteo.com/v1/forecast?latitude=${w.lat}&longitude=${w.lon}&current=temperature_2m,weather_code,is_day" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=$DAYS&timezone=auto")?.let { parse(w.name, it, now) }
                }
                val aq = if (air) place?.let { w ->
                    get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${w.lat}&longitude=${w.lon}&current=us_aqi,pm2_5&timezone=auto")?.let { parseAir(it, now) }
                } else null
                val e = p.edit().putLong("tried", if (f == null || (air && aq == null)) now else 0)
                if (aq != null) e.putString("aq", airToJson(aq).toString())
                if (f != null) e.putString("fc", toJson(f).toString()).putString("typed", typed.trim())
                    .putString("place", JSONObject().put("name", place.name).put("lat", place.lat).put("lon", place.lon).toString())
                else if (typed.trim() != p.getString("typed", "")) e.remove("fc")      // a new city that was not found: not the old city under its name
                e.apply()
                done()
            } catch (e: Exception) {
            } finally { synchronized(this) { busy = false } }
        }.start()
    }

    /** The network's or passive last fix, when the location permission was granted over ADB; null on a TV without a provider. */
    private fun deviceLocation(c: Context): Place? = try {
        if (c.packageManager.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, c.packageName) != android.content.pm.PackageManager.PERMISSION_GRANTED
            && c.packageManager.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, c.packageName) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
        else {
            val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val fix = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
                .filter { System.currentTimeMillis() - it.time < 7 * 86_400_000L }.maxByOrNull { it.time }
            fix?.let {
                val name = try { if (Geocoder.isPresent()) Geocoder(c).getFromLocation(it.latitude, it.longitude, 1)?.firstOrNull()?.let { a -> a.locality ?: a.subAdminArea ?: a.adminArea } else null } catch (e: Exception) { null }
                Place(name.orEmpty(), it.latitude, it.longitude)
            }
        }
    } catch (e: Exception) { null }

    private fun get(url: String): String? = try {
        val u = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "TV-Home") }
        try { if (u.responseCode == 200) u.inputStream.use { it.readBytes().take(200_000).toByteArray().toString(Charsets.UTF_8) } else null } finally { u.disconnect() }
    } catch (e: Exception) { null }
}
