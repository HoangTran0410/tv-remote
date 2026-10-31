package dev.tvremote.setup.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.ontv.Tailscale
import dev.tvremote.setup.health.Stats

/**
 * Switches Tailscale back on when it went away without being switched off: after the Play Store updates it, and after the TV,
 * short of memory, killed it (TCL's TGuard "lowSwapFree" kills it with a dozen other apps while YouTube plays, and often TV
 * Setup at the same moment). Nothing starts it again (always-on VPN only acts at boot), so a TV reached over Tailscale dropped
 * out until someone at home opened it. One switched off on purpose stays off: its process is still there, or it never ran.
 * "wanted" is kept in prefs so a TV Setup killed with it still knows when it starts again. From RemoteService, like [Guard].
 */
internal class TailscaleWatch(private val c: Context, private val adb: () -> Adb?) {
    private val ui = Handler(Looper.getMainLooper())
    private val cm = c.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var vpnUp = false
    @Volatile private var vpnLost = 0L                                 // when a VPN last went away: the update kills it first
    @Volatile private var stopped = false
    private val busy = java.util.concurrent.atomic.AtomicBoolean()      // one look or reconnect at a time: they take up to 30 s
    private var idle = 0                                               // looks in a row that found it running without its VPN

    private val prefs = c.getSharedPreferences("tswatch", Context.MODE_PRIVATE)
    private var wanted: Boolean
        get() = prefs.getBoolean("wanted", tailscaleVpn())          // never seen yet: wanted when it is the always-on VPN
        set(v) { prefs.edit().putBoolean("wanted", v).apply() }

    private val vpn = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { vpnUp = true; if (tailscaleVpn()) wanted = true; Stats.note(c, "vpn_up") }
        override fun onLost(n: Network) { vpnUp = false; vpnLost = System.currentTimeMillis(); ui.postDelayed(look, 15_000); Stats.note(c, "vpn_down") }
    }

    /** Every few minutes, and after a VPN went away: Tailscale wanted on, no VPN, and its app not running = it was killed. */
    private val look = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            ui.postDelayed(this, 3 * 60_000)
            if (vpnUp || !wanted || !busy.compareAndSet(false, true)) return
            Thread {
                try {
                    if (!installed()) wanted = false                  // removed: nothing to bring back
                    else when (dead()) {
                        false -> if (++idle >= 2) wanted = false      // running without its VPN twice in a row: switched off in its app
                        true -> { idle = 0; reconnect() }
                        null -> { }                                   // no ADB: cannot tell a kill from a stop, so leave it
                    }
                } finally { busy.set(false) }
            }.start()
        }
    }

    private fun tailscaleVpn(): Boolean = try {                        // the always-on VPN app; set by Tailscale.keepAlive
        android.provider.Settings.Secure.getString(c.contentResolver, "always_on_vpn_app") == Tailscale.PKG
    } catch (e: Exception) { false }

    private fun installed(): Boolean = try { c.packageManager.getPackageInfo(Tailscale.PKG, 0); true } catch (e: Exception) { false }

    /** True: not running (killed), false: running (switched off in its app, or still starting), null: cannot tell. */
    private fun dead(): Boolean? = try {
        adb()?.shell("pidof ${Tailscale.PKG}")?.trim()?.isEmpty()
    } catch (e: Exception) { null }

    private val replaced = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            if (i.data?.schemeSpecificPart != Tailscale.PKG) return
            Log.i("TailscaleWatch", "updated; vpn up=$vpnUp, lost ${(System.currentTimeMillis() - vpnLost) / 1000}s ago")
            if (vpnUp || System.currentTimeMillis() - vpnLost > 5 * 60_000) return   // still up, or it was off before the update
            ui.postDelayed({                                          // let the new version settle before poking it
                if (busy.compareAndSet(false, true)) Thread { try { reconnect() } finally { busy.set(false) } }.start()
            }, 3000)
        }
    }

    fun start() {
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), vpn)
        } catch (e: Exception) { }
        val f = IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply { addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(replaced, f, Context.RECEIVER_NOT_EXPORTED) else c.registerReceiver(replaced, f)
        ui.postDelayed(look, 20_000)                                   // started again after a kill: the VPN may have gone meanwhile
    }

    fun stop() {
        stopped = true
        try { cm.unregisterNetworkCallback(vpn) } catch (e: Exception) { }
        try { c.unregisterReceiver(replaced) } catch (e: Exception) { }
        ui.removeCallbacksAndMessages(null)
    }

    private fun reconnect() {
        if (vpnUp || stopped) return
        Stats.note(c, "ts_restart")
        for (attempt in 0 until 3) {
            if (vpnUp || stopped) return
            try {                                                      // the same nudge as Tailscale.start; without ADB the app sends it
                adb()?.shell("am broadcast -a com.tailscale.ipn.CONNECT_VPN -n ${Tailscale.PKG}/.IPNReceiver")
                    ?: c.sendBroadcast(Intent("com.tailscale.ipn.CONNECT_VPN").setClassName(Tailscale.PKG, Tailscale.PKG + ".IPNReceiver"))
            } catch (e: Exception) { }
            Log.i("TailscaleWatch", "reconnect $attempt")
            Thread.sleep(10_000)
        }
    }
}
