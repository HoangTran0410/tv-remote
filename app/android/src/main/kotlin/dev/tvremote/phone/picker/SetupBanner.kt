package dev.tvremote.phone.picker

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.manage.version
import dev.tvremote.phone.media.say
import dev.tvremote.phone.remote.keys
import dev.tvremote.phone.ui.ERR_BG
import dev.tvremote.phone.ui.ERR_TEXT
import dev.tvremote.phone.ui.GREEN
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.menu
import dev.tvremote.phone.ui.say
import dev.tvremote.phone.ui.setLabel

import dev.tvremote.core.T
import android.graphics.Color
import android.view.View
import dev.tvremote.core.Errors
import dev.tvremote.core.ontv.Home
import dev.tvremote.core.ontv.SetupNeed
import dev.tvremote.core.ontv.SetupUpdate
import dev.tvremote.core.picture.Tv
import dev.tvremote.update.Release

// A newer TV Setup and / or TV Home on the connected TV, offered on the remote screen itself in one line: most people manage
// the TV from here and never open the Manage page, where the web UI offers it too. The update itself runs there (js/auto.js
// updateAll: Manage.kt's /api/home/update and /api/setup/update, with its progress and the TV's rollback). "Later" is per
// version for a few days or for good, kept as the page keeps it ("updLater": {"tv:0.15.0": until}).

/** Once the session is up: asks the TV for its TV Setup and GitHub for the newest, at most every few hours per TV. */
internal fun MainActivity.checkSetup(tv: Tv, h: String) {
    val now = System.currentTimeMillis()
    if (now - (setupChecked[h] ?: 0L) < 6 * 3600_000L) { showSetup(h); return }
    setupChecked[h] = now
    Thread({
        val home = try { Home.call({ tv.shell(it) }, null) } catch (e: Exception) { null }   // no TV Home: installed false
        val homeNow = home?.takeIf { it.optBoolean("installed") }
        val found = try {
            val installed = Regex("versionName=(\\S+)").find(tv.shell("dumpsys package ${SetupUpdate.PKG} 2>/dev/null | grep -m1 versionName="))?.groupValues?.get(1) ?: ""
            if (installed.isEmpty()) SetupNews("", "", "", 0L, "", "", homeNow?.optString("version") ?: "", homeNow?.optString("latest") ?: "", missing = true)   // no TV Setup: offered, with ✕ to hide it for good
            else {
                val s = SetupUpdate.parse(tv.shell("cat ${SetupUpdate.DIR}/state 2>/dev/null; echo '|'; cat ${SetupUpdate.DIR}/bad 2>/dev/null"))
                val r = try { Release.newer(installed, "tv", "tv-setup.apk") } catch (e: Exception) { null }
                SetupNews(installed, if (r != null && !SetupUpdate.bad(s, r.version)) r.version else "", s.optString("state"), s.optLong("at"), s.optString("version"), s.optString("from"),
                    homeNow?.optString("version") ?: "", homeNow?.optString("latest") ?: "")
            }
        } catch (e: Exception) { setupChecked.remove(h); null }      // the session closed meanwhile: asked again next time
        ui.post {
            if (isFinishing) return@post
            if (found == null) setupNews.remove(h) else setupNews[h] = found
            showSetup(h)
        }
    }, "setup-check").start()
}

/** After the Manage page: an update may have run there. Asked of the TV only when the last look is stale or was cleared. */
internal fun MainActivity.setupAgain() {
    val tv = tv ?: return
    val h = host ?: return
    if (isFinishing || !tv.ready()) return
    checkSetup(tv, h)
}

internal class SetupNews(val installed: String, val latest: String, val state: String, val at: Long, val version: String, val from: String,
                         val home: String = "", val homeLatest: String = "", val missing: Boolean = false) {
    /** A new version broke on this TV in the last 3 days and was taken back: said once, until "Got it". Not when that version
     *  (or a later one) is on the TV now, put there another way since (Downloader): the old line was still read (a Chromecast, 0.24.0). */
    fun undone(seen: String) = state == "rolledback" && System.currentTimeMillis() - at < 3 * 86400_000L && seen != at.toString() &&
        dev.tvremote.home.HomeConfig.newer(version, installed)
}

private fun MainActivity.laterAll(): org.json.JSONObject = try { org.json.JSONObject(pref("updLater").ifEmpty { "{}" }) } catch (e: Exception) { org.json.JSONObject() }

/** What is on offer and not put off: (kind, name, installed, new); "none": no TV Setup on this TV, until hidden ("tv:none@host", as the page). */
private fun MainActivity.offers(n: SetupNews, h: String): List<List<String>> {
    val o = laterAll(); val now = System.currentTimeMillis()
    if (n.missing && o.optLong("tv:none@$h") <= now) return listOf(listOf("none", "TV Setup", "", ""))
    return listOfNotNull(
        listOf("tv", "TV Setup", n.installed, n.latest).takeIf { n.latest.isNotEmpty() && o.optLong("tv:" + n.latest) <= now },
        listOf("home", "TV Home", n.home, n.homeLatest).takeIf { n.homeLatest.isNotEmpty() && o.optLong("home:" + n.homeLatest) <= now })
}

private fun MainActivity.putOff(keys: List<String>, days: Int) {
    val o = laterAll(); val now = System.currentTimeMillis()
    for (k in o.keys().asSequence().toList()) if (o.optLong(k) < now) o.remove(k)
    for (k in keys) o.put(k, if (days > 0) now + days * 86400_000L else 8_640_000_000_000_000L)   // as the page: the last date a Date holds
    prefs.edit().putString("updLater", o.toString()).apply()
}

internal fun MainActivity.showSetup(h: String? = host) {
    val n = if (h != null && h == host && tv != null) setupNews[h] else null
    val undone = n != null && n.undone(pref("setupUndo"))
    val offer = if (n != null && h != null) offers(n, h) else emptyList()
    setupRow.visibility = if (undone || offer.isNotEmpty()) View.VISIBLE else View.GONE
    setupClose.visibility = View.GONE
    if (n == null || h == null || !(undone || offer.isNotEmpty())) return
    if (!undone && offer[0][0] == "none") {
        setupBar.background = box(GREEN, 14); setupBar.setTextColor(Color.WHITE)
        setupBar.setLabel("@download|" + T("setup_banner.offer"))
        setupBar.setOnClickListener {
            setupNews.remove(h); setupChecked.remove(h)                   // looked at again after: installed, or still missing
            showSetup(h)
            managing.open(h, then = "installSetupApk()")
        }
        setupClose.visibility = View.VISIBLE
        setupClose.setOnClickListener { putOff(listOf("tv:none@$h"), 0); showSetup(h) }
        return
    }
    setupBar.background = box(if (undone) ERR_BG else GREEN, 14)
    setupBar.setTextColor(if (undone) ERR_TEXT else Color.WHITE)       // white on the green fill, in both themes
    setupBar.setLabel(if (undone) "@alert|" + T("setup_banner.tv_setup_failed_the", "version" to n.version, "from" to n.from)
        else "@download|" + T("upd.new", "what" to offer.joinToString(" · ") { it[1] + " " + it[3] }) + "  ›")
    setupBar.setOnClickListener {
        if (undone) Popup.Builder(this).setTitle(T("setup_banner.tv_setup_failed_on", "version" to n.version))
            .setMessage(T("setup_banner.the_tv_went_back", "from" to n.from))
            .setPositiveButton(T("setup_banner.got_it")) { _, _ -> prefs.edit().putString("setupUndo", n.at.toString()).apply(); showSetup(h) }.show()
        else {
            val keys = offer.map { it[0] + ":" + it[3] }
            val off = { days: Int -> { putOff(keys, days); showSetup(h) } }
            menu(offer.joinToString("\n") { it[1] + " " + it[2] + " → " + it[3] }, 0, listOf(
                Triple("@download|" + T("setup.update_ok"), "") {
                    setupNews.remove(h); setupChecked.remove(h)            // asked again next time: done, or put back
                    showSetup(h)
                    managing.open(h, then = "updateAll(" + offer.joinToString(",", "[", "]") { "'" + it[0] + "'" } + ")")   // its bar shows how far it is
                },
                Triple(T("upd.in_days", "n" to 1), "", off(1)), Triple(T("upd.in_days", "n" to 7), "", off(7)),
                Triple(T("upd.in_days", "n" to 30), "", off(30)), Triple(T("upd.skip"), T("upd.skip_sub"), off(0))))
        }
    }
}

/** When [e] says TV Setup on the TV is missing or too old for a feature (SetupNeed), asks to install or update it, with the
 *  button that does it on the Manage page; true when it did. Every feature's errors go through here first. */
internal fun MainActivity.offerSetup(e: Throwable): Boolean {
    val say = SetupNeed.say(e, dev.tvremote.core.Words.lang) ?: return false
    if (isFinishing) return true
    val old = generateSequence(e) { it.cause }.any { "setup:old:" in (it.message ?: "") }
    val h = host
    Popup.Builder(this).setMessage(say)
        .setPositiveButton(if (old) T("setup.update_ok") else T("setup_banner.install_tv_setup")) { _, _ ->
            if (h != null) { setupNews.remove(h); setupChecked.remove(h) }      // asked again after: done, or put back
            managing.open(h, then = if (old) "updateSetup()" else "installSetupApk()")
        }
        .setNegativeButton(T("common.later"), null).show()
    return true
}

/** Runs [then] once TV Setup on the TV has [feature] (SetupNeed.MIN); otherwise offerSetup asks for it instead. */
internal fun MainActivity.withSetup(feature: String, then: () -> Unit) {
    val tv = tv ?: return
    Thread({
        val e = try { SetupNeed.check(tv.link() ?: throw java.io.IOException("no TV"), feature); null } catch (e: Exception) { e }
        ui.post { if (isFinishing) return@post; if (e == null) then() else if (!offerSetup(e)) android.widget.Toast.makeText(this, Errors.say(e, dev.tvremote.core.Words.lang), android.widget.Toast.LENGTH_LONG).show() }
    }, "setup-need").start()
}
