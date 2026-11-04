package dev.tvremote.setup.home

import android.content.Context
import android.content.Intent
import dev.tvremote.core.ontv.Setup

/**
 * Home with the option "homebar" ([HomeKeys.bar]): [dev.tvremote.setup.KeysBridge] eats the key and asks here what is on screen. Over
 * an app, TV Home's bar (DockBarActivity, see-through) opens; over a home screen, the bar itself (TV Home is in front then too) or the
 * system's bars, Home does what it did. A screen that is unknown (nothing seen in the usage events) is not an app either: Home as ever.
 * Flow: dev/flows/home-bar.md.
 */
internal object HomeBar {
    private val SYSTEM = setOf("com.android.systemui")

    /** True when [front] is an app a bar can go over: not empty, not a home screen in [homes], not the system's bars. */
    fun opens(front: String, homes: Set<String>): Boolean = front.isNotEmpty() && front !in homes && front !in SYSTEM

    /** Every screen that is "home": TV Home (and the bar), the TV's known launchers, the one picked, the one Android would open. */
    fun homes(c: Context): Set<String> {
        val out = HashSet<String>()
        out += HomeKey.TV_HOME; out += Setup.LAUNCHER
        HomeKey.pkg(c)?.let { out += it }
        try { c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName?.let { out += it } } catch (e: Exception) { }
        return out
    }
}
