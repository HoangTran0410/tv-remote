package dev.tvremote.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.tvremote.setup.home.AutoOpen
import dev.tvremote.setup.home.HomeKey

/** Starts the web remote again after the TV restarts or this app is updated, if it was left on, or timers and limits need it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        // the TV started (not this app updated): the welcome screen, if it is set to come up then
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == "android.intent.action.QUICKBOOT_POWERON") { WelcomeState.trigger(ctx, "boot"); AutoOpen.trigger(ctx, "boot"); HomeKey.trigger(ctx, "boot") }
        // Android 12+ may refuse a foreground service at boot on some TVs: the next app open or TV Setup broadcast starts it
        if (RemoteService.wanted(ctx)) try { RemoteService.start(ctx, true) } catch (e: Exception) { }
    }
}
