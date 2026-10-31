package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.media.NowApp
import dev.tvremote.phone.media.say
import dev.tvremote.phone.ui.say

import dev.tvremote.core.T
import dev.tvremote.core.ontv.Rescue
import java.io.File

/** "Unfreeze TV" (Tools): the app on the TV's screen is stopped unless the TV needs it, then Home ([Rescue], as `/api/rescue` on the page). */
internal fun MainActivity.rescueTv() {
    if (needsAdb()) return
    val tv = tv
    val host = host
    if (tv == null || !tv.ready() || host == null) return
    Thread {
        val r = try { Rescue.run({ tv.shell(it) }) } catch (e: Exception) { null }
        ui.post {
            if (r == null) { say(T("rescue.failed"), true); return@post }
            val pkg = r.optString("stopped")
            // the name from the Manage screen's copy for this TV (NowApp.cacheDir); without it the package says which app
            val name = try { org.json.JSONObject(File(cacheDir, "meta/" + host.replace(Regex("[^\\w.-]"), "_") + "/names.json").readText()).optString(pkg) } catch (e: Exception) { "" }
            say(if (pkg.isEmpty()) T("rescue.home") else T("rescue.closed", "name" to name.ifEmpty { pkg }), false)
        }
    }.start()
}
