package dev.tvremote.home

import android.os.Bundle
import android.widget.Toast
import dev.tvremote.home.apps.LinkApps

/**
 * The target of a pinned link's shortcut ([LinkApps.intent]): finds the app that opens the link now and starts it. Not exported: only
 * TV Home's own shortcuts reach it. Nothing installed that takes the link: a word saying what to install.
 */
class LinkActivity : android.app.Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))   // TV Home's own language, not the TV's: the panel over an app was in the TV's
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val url = intent.dataString.orEmpty()
        val g = LinkApps.groupFor(url)
        if (LinkUrl.clean(url) == url && !LinkApps.open(this, url, g, browser = g != "youtube")) Toast.makeText(this, R.string.link_no_app, Toast.LENGTH_LONG).show()
        finish()
    }
}
