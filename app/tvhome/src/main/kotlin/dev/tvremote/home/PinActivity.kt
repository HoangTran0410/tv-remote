package dev.tvremote.home

import android.annotation.TargetApi
import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import dev.tvremote.home.apps.Pins

/**
 * Android asks the default home to confirm a pin (CONFIRM_PIN_SHORTCUT). The app that asked has already asked the person (its
 * own "Pin to Home" button), so it is taken at once, with a word on screen; when the dock is full it is refused, and said.
 * App widgets (CONFIRM_PIN_APPWIDGET) are not taken yet: this home has no widget host.
 */
@TargetApi(26)
class PinActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))   // TV Home's own language, not the TV's: the panel over an app was in the TV's
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val req = try { getSystemService(LauncherApps::class.java)?.getPinItemRequest(intent) } catch (e: Exception) { null }
        val s = req?.takeIf { it.isValid && it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT }?.shortcutInfo
        when {
            s == null -> {}
            // some firmwares' system_server throws on accept (census d31494e5, NPE in ShortcutInfo): not pinned, no crash
            runCatching { req.accept() }.getOrDefault(false) -> {
                Pins.order(this, Pins.order(this).filter { it != Pins.key(s) } + Pins.key(s))
                Toast.makeText(this, getString(R.string.pinned_here, s.shortLabel ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
