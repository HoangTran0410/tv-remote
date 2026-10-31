package dev.tvremote.setup.home

import android.content.Context
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.ontv.ScreenOff
import dev.tvremote.setup.RemoteService

/**
 * What TV Home's power button may ask of this app (HomeInfo "power"): four fixed actions, named, never a command line, so a
 * caller (even one that got past the key check) can only do these. The ADB loopback session runs them; TV Home has no ADB.
 */
object HomePower {
    const val SCREEN_OFF = "screen_off"
    const val STANDBY = "standby"
    const val POWER_OFF = "power_off"
    const val REBOOT = "reboot"

    /** The shell line of an action, null for any other word. [SCREEN_OFF] has none: it starts the helper (ScreenOff.kt). */
    fun command(action: String): String? = when (action) {
        STANDBY -> "input keyevent 223"                                // KEYCODE_SLEEP, what Guard uses too: the TV wakes on its remote
        // `reboot -p` is allowed to the shell on Android 9-14 (it only sets sys.powerctl); some builds lack the flag, `svc power shutdown` is the same by the framework
        POWER_OFF -> "reboot -p || svc power shutdown"
        REBOOT -> "reboot"                                           // allowed to the shell like `reboot -p`; the Control Center asks twice first
        else -> null
    }

    /** "ok", or "noadb" / "failed". Blocks a few seconds (the helper), so never on the main thread. */
    fun run(c: Context, action: String): String {
        if (action != SCREEN_OFF && command(action) == null) return "failed"
        val live = try { RemoteService.adb() } catch (e: Exception) { null }
        // the service is not running (web remote off, or the process was killed and only this call woke it): a link of its
        // own on the same key, closed after. The screen helper is nohup setsid, so it outlives the link
        val own = if (live == null) try { AdbLink(c.filesDir, "127.0.0.1").get() } catch (e: Exception) { return "noadb" } else null
        val adb = live ?: own!!
        return try {
            if (action == SCREEN_OFF) ScreenOff.off(adb, c.assets.open("tvr-helper.jar").use { it.readBytes() })
            else adb.shell(command(action)!!, 3000)                    // the link may drop as the TV goes down: not waited for
            "ok"
        } catch (e: Exception) { "failed" }
        finally { try { own?.close() } catch (e: Exception) { } }
    }
}
