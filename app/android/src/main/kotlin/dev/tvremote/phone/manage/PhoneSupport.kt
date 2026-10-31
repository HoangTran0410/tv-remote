package dev.tvremote.phone.manage

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.picker.lanNetwork
import dev.tvremote.phone.picker.name
import dev.tvremote.phone.picker.savedIps
import dev.tvremote.phone.picker.savedTv
import dev.tvremote.phone.remote.kind
import dev.tvremote.phone.remote.kindOf

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.tvremote.core.Diagnose
import dev.tvremote.core.Scrub
import dev.tvremote.core.SupportAndroid
import dev.tvremote.core.SupportRelay
import dev.tvremote.core.WireLog
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Support for this phone app (SupportRelay, as TV Setup's): a phone that lost its TVs mid-way (2026-10-29: mobile data as the way
 * out, the saved TVs "no answer" while a scan found them) could not be looked at from afar. Opened from About (native) or the
 * Manage screen's About; while it lasts the owner may read [ROUTES] only: what the TVs said, the app's log and settings, every
 * network the phone has, the saved TVs as the picker sees them, and the self-check against one of them. Nothing is changed.
 */
internal object PhoneSupport {
    private val ROUTES = listOf("/api/support/log", "/api/support/logcat", "/api/support/prefs", "/api/support/net", "/api/support/tvs", "/api/diagnose?ip=")
    @Volatile private var relay: SupportRelay? = null
    @Volatile private var activity = WeakReference<MainActivity>(null)

    /** At start: the activity the answers read, and support open before the app was closed goes on. */
    fun attach(a: MainActivity) {
        activity = WeakReference(a)
        relay(a).resume()
    }

    fun relay(c: Context): SupportRelay = relay ?: synchronized(this) {
        relay ?: SupportRelay("phone", (activity.get() ?: c as? MainActivity)?.version() ?: "", android.os.Build.MODEL, SupportAndroid.store(c),
            { report(c.applicationContext) }, { answer(c.applicationContext, it) }).also { relay = it }
    }

    /** "start", "stop" or "state" (the page's About, and the native row): `{open, code, min, left}` or `{failed: no_net | none_left}`. Blocks. */
    fun act(c: Context, act: String): JSONObject {
        val r = relay(c)
        return when (act) {
            "start" -> if (r.start() == null) JSONObject().put("failed", if (r.left == 0) "none_left" else "no_net") else r.state()
            "stop" -> { r.stop(); r.state() }
            else -> r.state()
        }
    }

    private fun report(c: Context): String = Scrub.blank(buildString {
        append(WireLog.app).append('\n')
        append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append(" · Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
        append(net(c)).append('\n')
        append(tvs().toString(1)).append('\n')
        append(SupportRelay.fit(SupportAndroid.logcat(), 12_000, tail = true)).append('\n')
        append(WireLog.all.report().takeLast(24_000))
    })

    private fun answer(c: Context, path: String): Pair<Int, String> {
        val route = path.substringBefore('?')
        return when (route) {
            "/api/support/log" -> 200 to WireLog.all.report()
            "/api/support/logcat" -> 200 to SupportAndroid.logcat()
            "/api/support/prefs" -> 200 to SupportAndroid.prefs(c)
            "/api/support/net" -> 200 to net(c)
            "/api/support/tvs" -> 200 to tvs().toString(1)
            "/api/diagnose" -> {                                       // the self-check the picker runs, against a saved TV or any address
                val ip = Regex("[?&]ip=([\\w.:%-]+)").find(path)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    ?: return 400 to "which TV: /api/diagnose?ip=192.168.1.20"
                val a = activity.get()
                val r = Diagnose.run(Diagnose.Real(), ip, a?.kindOf(ip) ?: "android", a?.name(ip) ?: "", "", "en")
                200 to Diagnose.json(r, "en").toString(1)
            }
            else -> 403 to "not allowed for support: $route (the phone answers ${ROUTES.joinToString(", ")})"
        }
    }

    /** Every network the phone has, which one is the way out, and whether the Wi-Fi was found to reach the Internet. */
    fun net(c: Context): String = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = if (android.os.Build.VERSION.SDK_INT >= 23) cm.activeNetwork else null
        buildString {
            append("[networks] way out: ").append(active).append(" · lan for TVs: ").append(c.lanNetwork()).append('\n')
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n); val lp = cm.getLinkProperties(n)
                val kinds = listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "mobile", NetworkCapabilities.TRANSPORT_VPN to "vpn",
                    NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet").filter { caps?.hasTransport(it.first) == true }.joinToString("+") { it.second }
                append(n).append(if (n == active) " (way out) " else " ").append(kinds)
                    .append(" internet=").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    .append(" validated=").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    .append(" iface=").append(lp?.interfaceName)
                    .append(" addr=").append(lp?.linkAddresses?.joinToString(","))
                    .append(" dns=").append(lp?.dnsServers?.joinToString(",") { it.hostAddress ?: "" })
                    .append(" routes=").append(lp?.routes?.joinToString(",") { it.destination.toString() + (it.gateway?.hostAddress?.let { g -> " via $g" } ?: "") })
                    .append('\n')
            }
        }
    } catch (e: Exception) { "[networks] $e" }

    /** The saved TVs as the picker sees them now: kind, the address that answered last, answering or not, being asked again. */
    fun tvs(): JSONObject {
        val a = activity.get() ?: return JSONObject().put("error", "the app's screen is closed")
        val out = JSONArray()
        for (ip in a.savedIps()) out.put(JSONObject().put("ip", ip).put("name", a.name(ip)).put("kind", a.kindOf(ip)).put("addresses", JSONArray(a.savedTv(ip).addresses))
            .put("route", a.route[ip]).put("reach", a.reach[ip]).put("trying", ip in a.trying))
        return JSONObject().put("connected_to", a.host).put("keys_only", a.basic?.kind).put("tvs", out)
    }
}
