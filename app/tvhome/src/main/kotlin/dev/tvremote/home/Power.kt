package dev.tvremote.home

import android.app.Activity
import android.net.Uri
import android.widget.Toast

/**
 * The power button's actions (and the Control Center's). TV Home has no ADB: it names one of a few words and TV Setup (its HomeInfo provider, which
 * answers only this app by its key) runs the fixed command for it. Never a command line from here.
 */
object Power {
    const val SCREEN_OFF = "screen_off"
    const val STANDBY = "standby"
    const val POWER_OFF = "power_off"
    const val REBOOT = "reboot"

    /** Asks TV Setup on a thread of its own (the screen helper takes a second or two) and says the answer in a toast. */
    fun run(a: Activity, action: String) {
        Thread({
            val said = try { a.contentResolver.call(Uri.parse(HomeConfig.USAGE), "power", action, null)?.getString("result") } catch (e: Exception) { null }
            val words = when (said) { "ok", "menu" -> 0; "keys_off" -> R.string.power_keys_off; "noadb" -> R.string.power_noadb; "failed" -> R.string.power_failed; "untrusted" -> R.string.power_untrusted; else -> R.string.power_missing }   // null: no TV Setup, or one without the button
            if (words != 0) a.runOnUiThread { if (!a.isDestroyed) Toast.makeText(a, words, Toast.LENGTH_LONG).show() }
        }, "home-power").apply { isDaemon = true }.start()
    }
}
