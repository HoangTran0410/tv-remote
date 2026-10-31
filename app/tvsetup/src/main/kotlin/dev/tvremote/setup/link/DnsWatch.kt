package dev.tvremote.setup.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import dev.tvremote.core.T
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.TvShell

/**
 * A DNS filter (the Setup tab's 🛡️) that stops working leaves every app on the TV without names, while the TV still looks
 * online: YouTube says it cannot connect, and nobody thinks of the filter set weeks ago. It can work when it is turned on and
 * fail later, as on a Sony (Android 9) once Tailscale's VPN came up. So TV Setup checks it now and then, and on network
 * changes; when names fail while addresses still answer, it turns the filter off and notes it ([TvShell.DNS_OFF]) for the page.
 * From RemoteService, like [Guard].
 */
internal class DnsWatch(private val c: Context, private val adb: () -> Adb?) {
    private val ui = Handler(Looper.getMainLooper())
    private val cm = c.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var stopped = false
    @Volatile private var running = false

    private val tick = Runnable { check() }

    private val changed = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { soon() }                // a VPN or Wi-Fi coming up can change how names are asked
        override fun onLost(n: Network) { soon() }
    }

    fun start() {
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), changed)
        } catch (e: Exception) { }
        ui.postDelayed(tick, 2 * 60_000L)
    }

    fun stop() {
        stopped = true
        try { cm.unregisterNetworkCallback(changed) } catch (e: Exception) { }
        ui.removeCallbacks(tick)
    }

    private fun soon() { ui.removeCallbacks(tick); ui.postDelayed(tick, 45_000L) }   // once things have settled

    private fun check() {
        if (stopped || running) return
        running = true
        Thread({
            try { step() } catch (e: Exception) { }
            running = false
            ui.post { if (!stopped) { ui.removeCallbacks(tick); ui.postDelayed(tick, 10 * 60_000L) } }   // one next check, even after soon()
        }, "dns-watch").apply { isDaemon = true }.start()
    }

    private fun step() {
        val a = adb() ?: return
        if (a.shell("settings get global private_dns_mode").trim() != "hostname") return
        val host = a.shell("settings get global private_dns_specifier").trim().filter { it.isLetterOrDigit() || it == '.' || it == '-' }
        // three misses over about a minute, each time with the Internet itself answering: not a network that is just down
        for (i in 0 until 3) {
            if (i > 0) Thread.sleep(25_000L)
            val out = a.shell("${TvShell.NAMES_WORK} && echo names; for ip in 1.1.1.1 8.8.8.8 9.9.9.9; do timeout 5 ping -c 1 -W 3 \$ip >/dev/null 2>&1 && { echo net; break; }; done")
            if (out.contains("names") || !out.contains("net")) return
        }
        Log.i("DnsWatch", "names fail with $host while the Internet answers: turning the DNS filter off")
        a.shell("settings put global private_dns_mode opportunistic; settings delete global private_dns_specifier >/dev/null 2>&1; " +
            "settings put global ${TvShell.DNS_OFF} \"$host \$(date +%s)\"")
        ui.post {
            Toast.makeText(c, T("dns_watch.cut_off", "host" to host), Toast.LENGTH_LONG).show()
        }
    }
}
