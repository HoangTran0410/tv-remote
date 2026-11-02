package dev.tvremote.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.tvremote.home.apps.InputOpen
import dev.tvremote.home.apps.SystemSettings
import dev.tvremote.home.ui.ControlCenter

/**
 * The Control Center over any app: TV Setup's key filter starts this for a remote key given the action [Target.CONTROL]. A see-through
 * activity (Ghost) holding the same panel the top bar opens, so the app beneath stays visible and running; Back, or any choice, closes it.
 */
class ControlCenterActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))   // TV Home's own language, not the TV's: the panel over an app was in the TV's
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val go = { i: Intent -> try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { SystemSettings.open(this) } }
        ControlCenter.show(this, null, { go(Intent(it)) },
            { go(Intent(this, HomeActivity::class.java).setAction(InputOpen.INPUTS)) },          // Home's own inputs menu, as the Input key over an app: the list, its folding, every fallback
            { go(Intent(this, HomeActivity::class.java).setAction(HomeConfig.CUSTOMIZE)) }, { finish() })   // the panel itself, not only the home screen under it
    }
}
