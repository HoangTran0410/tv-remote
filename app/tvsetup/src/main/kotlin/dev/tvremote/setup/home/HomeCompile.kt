package dev.tvremote.setup.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import dev.tvremote.home.HomeConfig
import dev.tvremote.setup.RemoteService

/**
 * TV Home compiled ahead again after it was updated by itself (its "Update now", Android's installer: no shell to run the
 * compile the clients run after their own install, Home.kt). An update leaves it at "verify", interpreted until the JIT
 * warms up: on an FPT box its start went 457-1332 ms -> ~410 ms once compiled, and the compile took 1 s.
 * Without -f: already compiled by the client that installed it, nothing is done twice.
 */
internal class HomeCompile(private val c: Context) {
    private val replaced = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Thread({
                Thread.sleep(15_000)                                  // the installer done and the home started again first
                try { RemoteService.adb()?.shell(HomeConfig.GRANT_WIFI + "cmd package compile -m speed ${HomeConfig.PKG} >/dev/null 2>&1", 120000) } catch (e: Exception) { }
            }, "home-compile").apply { isDaemon = true }.start()
        }
    }

    fun start() {
        try {
            c.registerReceiver(replaced, IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply {
                addDataScheme("package"); addDataSchemeSpecificPart(HomeConfig.PKG, PatternMatcher.PATTERN_LITERAL)
            })
        } catch (e: Exception) { }
    }

    fun stop() { try { c.unregisterReceiver(replaced) } catch (e: Exception) { } }
}
