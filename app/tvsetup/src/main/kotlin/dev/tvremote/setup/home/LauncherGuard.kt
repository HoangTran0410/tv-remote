package dev.tvremote.setup.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.Adb.Companion.q
import dev.tvremote.core.ontv.LauncherSwap
import dev.tvremote.core.ontv.LauncherWatch

/**
 * The watchdog of the launcher gallery, from RemoteService (like [Guard]): while a launcher swap is on record on the TV
 * ([LauncherSwap.REC]) it looks every few seconds, and a launcher that crashes twice in five minutes, a Home that lands on the setup
 * wizard or Android's picker, or a question nobody answered puts the TV's own home screens back ([LauncherWatch]) and says so on screen
 * ([LauncherConfirmActivity] in its notice form). With no swap on record it looks every half minute, which costs one shell call.
 * It runs here because the phone or computer that did the swap is often gone by the time the launcher breaks.
 */
internal class LauncherGuard(private val c: Context, private val adb: () -> Adb?) {
    companion object {
        @Volatile private var running: LauncherGuard? = null
        /** The question on the TV's screen was answered there. False when there is no ADB to ask. */
        fun answer(keep: Boolean): Boolean = running?.answerNow(keep) ?: false
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    @Volatile private var active = false

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            Thread({
                try { step() } catch (e: Exception) { }
                ui.post { if (!stopped) ui.postDelayed(this, if (active) 3_000L else 30_000L) }
            }, "launcher-guard").apply { isDaemon = true }.start()
        }
    }

    fun start() { running = this; ui.postDelayed(tick, 20_000L) }
    fun stop() { stopped = true; running = null; ui.removeCallbacks(tick) }

    private fun sh(cmd: String): String = adb()?.shell(cmd) ?: throw java.io.IOException("no adb")

    private fun step() {
        val w = LauncherWatch({ sh(it) })
        active = w.active()
        if (!active) return
        HomeKey.landed(c)                                             // the key way: the TV's own home came up by itself (no ADB: Usage)
        val why = w.check() ?: return
        active = false
        notice(why)
    }

    private fun answerNow(keep: Boolean): Boolean = try {
        val s = LauncherSwap({ sh(it) })
        if (keep) s.keep() else s.rollback("user")
        active = s.state()?.active == true
        true
    } catch (e: Exception) { false }

    /** "Went back to the TV's own launcher, because...", over whatever is on the TV (ADB's shell may open a screen from the background). */
    private fun notice(why: String) {
        try { sh("am start -n ${c.packageName}/.LauncherConfirmActivity --es notice " + q(why) + " 2>&1") } catch (e: Exception) { }
    }
}
