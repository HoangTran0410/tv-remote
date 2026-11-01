package dev.tvremote.home

import android.os.Bundle
import dev.tvremote.home.keys.Targets

/**
 * TV Setup hands a key or the power-on app that points at a shortcut or a live widget to this screen-less activity: only the home screen
 * may start a shortcut. It opens nothing it was not told to: [t] must be in the settings (a key's target, the power-on app, a grid tile).
 */
class OpenActivity : android.app.Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val t = intent.getStringExtra("t").orEmpty()
        val conf = Store.get(this)
        val known = HomeConfig.keyApps(conf.optJSONObject("keyapps")).values + conf.optString("autoapp") + Store.list(this, "tiles") + dev.tvremote.home.apps.Pins.list(this).map { it.pkg }   // a pinned shortcut is on the grid even when "tiles" does not list it (voice opens one by its name)
        if (t in known) Targets.open(this, t)
        finish()
    }
}
