package dev.tvremote.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.tvremote.home.apps.Inputs
import dev.tvremote.home.apps.SystemSettings
import dev.tvremote.home.ui.ControlCenter

/**
 * The Control Center over any app: TV Setup's key filter starts this for a remote key given the action [Target.CONTROL]. A see-through
 * activity (Ghost) holding the same panel the top bar opens, so the app beneath stays visible and running; Back, or any choice, closes it.
 */
class ControlCenterActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val go = { i: Intent -> try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { SystemSettings.open(this) } }
        ControlCenter.show(this, null, { go(Intent(it)) },
            { if (Inputs.liveApp(this)?.let { p -> dev.tvremote.home.apps.Apps.launch(this, p) } != true && !Inputs.panel(this)) SystemSettings.open(this) },   // the sources: a Live TV app or the TV's own panel, else Settings
            { go(Intent(this, HomeActivity::class.java)) }, { finish() })                                                                                             // Customize lives on the home screen
    }
}
