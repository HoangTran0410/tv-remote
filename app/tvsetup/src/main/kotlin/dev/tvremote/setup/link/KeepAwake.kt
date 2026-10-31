package dev.tvremote.setup.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import dev.tvremote.setup.rules.Rules

/**
 * Keeps the TV reachable (Tailscale, the web remote, ADB) while it is "off": with the screen off, holds a wake lock so the
 * chip does not go into its deep sleep (TCL's STR, about 1.5 minutes after the power key) and the Wi-Fi stays up. A TV whose
 * maker powers the chip down regardless keeps sleeping; [Stats] notes each sleep, so the Health tab shows whether it worked.
 * Off by default (it costs power): the "awake" rule, set from the remotes like the timer. From RemoteService, like [Guard].
 */
internal class KeepAwake(private val c: Context) {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) { check(i.action == Intent.ACTION_SCREEN_OFF) }
    }

    fun start() {
        c.registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
        live = this
    }

    @Synchronized
    fun stop() {
        try { c.unregisterReceiver(screen) } catch (e: Exception) { }
        if (live === this) live = null
        release()
    }

    /** Called when the screen goes off or on, and when the rule changes ([off]: the screen is off). */
    @Synchronized                                                      // the screen's broadcasts and the rule's HTTP threads
    fun check(off: Boolean = !(c.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isInteractive) {
        if (off && on(c)) hold() else release()
    }

    @Suppress("DEPRECATION")
    private fun hold() {
        if (cpu?.isHeld == true) return
        cpu = (c.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvsetup:awake").apply { setReferenceCounted(false); acquire() }
        wifi = try { (c.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvsetup:awake").apply { setReferenceCounted(false); acquire() } } catch (e: Exception) { null }
        Log.i("KeepAwake", "holding")
    }

    private fun release() {
        if (cpu?.isHeld == true) Log.i("KeepAwake", "released")
        try { cpu?.release() } catch (e: Exception) { }
        try { wifi?.release() } catch (e: Exception) { }
        cpu = null; wifi = null
    }

    companion object {
        @Volatile var live: KeepAwake? = null                           // the running one: a rule changed while the screen is off
        fun on(c: Context): Boolean = Rules.load(c).optBoolean("awake")
    }
}
