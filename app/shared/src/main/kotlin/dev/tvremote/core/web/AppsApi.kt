package dev.tvremote.core.web
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.TvShell
import dev.tvremote.core.ontv.Meminfo
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.web.Procs.KEEP_NOW
import dev.tvremote.core.web.Procs.keepNow
import dev.tvremote.core.web.Procs.keepWhy
import dev.tvremote.core.web.Procs.memoryByApp
import dev.tvremote.core.web.Procs.procsOf
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** The Apps tab and the task manager: the TV's apps listed, opened, stopped, turned off, removed, backed up; the privacy check's actions. */
internal class AppsApi(core: ManageCore) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? {
        when (what) {
            "apps" -> return HttpServer.Response.json(apps(r.q("system") == "1"))
            "apps/report" -> return HttpServer.Response.json(appsReport())
            "privacy" -> if (!post) return HttpServer.Response.json(privacy.snapshot(r.q("since").toIntOrNull() ?: 0, r.q("id").toIntOrNull() ?: -1))   // the scan under way or the last one (Privacy.kt); a route nobody but the owner may use (Scope allows none of it)
            "recents" -> if (!post) return HttpServer.Response.json(recents())
            "app/meta" -> {                                       // a name and whether /api/app/icon has a picture (the computer app answers it first)
                val pkg = r.q("pkg")
                if (!PKG.matcher(pkg).matches()) return HttpServer.Response.error(404, "not found")
                val label = try { labels?.label(pkg) } catch (e: Exception) { null }
                val icons = try { labels?.icons() == true } catch (e: Exception) { false }
                return HttpServer.Response.json(JSONObject().put("label", label ?: pkg.substringAfterLast('.')).put("icon", icons).put("banner", icons))
            }
            "procs" -> return HttpServer.Response.json(procs())
            "apps/boot" -> return text(TvShell.BOOT, 20000)
            "app/info" -> return text(TvShell.appInfo(r.q("pkg")), 20000)
            "app/backup" -> return backup(r.q("pkg"))
        }
        if (!post) return null
        return when (what) {
            "app" -> { open(r.json().optString("link")); HttpServer.Response.json(JSONObject().put("ok", true).put("via", "adb")) }
            "privacy/scan" -> HttpServer.Response.json(privacy.start())
            "privacy/stop" -> HttpServer.Response.json(privacy.stop())
            "privacy/revoke", "privacy/grant" -> HttpServer.Response.json(privacyPerms(r.json().optString("pkg"), what.endsWith("revoke")))
            "privacy/a11y-off" -> HttpServer.Response.json(privacyA11yOff(r.json().optString("pkg")))
            "privacy/off", "privacy/on" -> HttpServer.Response.json(privacyOff(r.json().optString("pkg"), what.endsWith("off")))
            "app/launch", "app/stop", "app/uninstall", "app/disable", "app/enable", "app/clear" -> app(r.json().optString("pkg"), what.substring(4))
            "clean" -> text(TvShell.CLEAN, 90000)
            "rescue" -> {                                         // TV frozen: stop the app on screen, go Home
                val o = dev.tvremote.core.ontv.Rescue.run({ sh(it) })
                val pkg = o.optString("stopped")
                if (pkg.isNotEmpty()) o.put("label", try { labels?.label(pkg) } catch (e: Exception) { null } ?: pkg)
                HttpServer.Response.json(o)
            }
            "recents" -> {                                        // close one open app, those picked, or all but the one on screen
                val d = r.json()
                val list = d.optJSONArray("pkgs")
                val pkgs = if (list != null) (0 until list.length()).map { list.getString(it) }.distinct()
                    else if (d.optBoolean("all")) recents().getJSONArray("apps").let { a -> (0 until a.length()).map { a.getJSONObject(it) }   // what the TV needs stays, as on its own row
                    .filter { !it.optBoolean("top") && it.isNull("keep") }.map { it.getString("pkg") } }
                    else listOf(d.optString("pkg"))
                val keep = keepNow(sh(KEEP_NOW))
                for (p in pkgs) {
                    if (!PKG.matcher(p).matches()) throw IllegalArgumentException("bad package name")
                    if (keepWhy(p, keep) != null) throw IllegalArgumentException("protected: the TV needs this app")   // home, keyboard, Android's own, this remote
                    dev.tvremote.core.ontv.Recents.close({ sh(it) }, p, false)
                }
                HttpServer.Response.json(JSONObject().put("ok", true).put("closed", pkgs.size))
            }
            "app/perm" -> {
                val d = r.json(); val pkg = d.optString("pkg")
                if (Setup.PERMS_FIXED.matches(pkg)) throw IllegalArgumentException("protected: the TV needs this app")
                text(TvShell.perm(pkg, d.optString("perm"), d.optBoolean("on")), 20000)
            }
            "free" -> HttpServer.Response.json(free())
            "procs/kill" -> { val pkg = r.json().optString("pkg"); keepCheck(pkg); sh("am force-stop " + Adb.q(pkg)); HttpServer.Response.ok() }
            else -> null
        }
    }

    private val privacy = dev.tvremote.core.ontv.Privacy.Job(link)   // Privacy check: one scan at a time, its rows kept for the page

    // ---------------------------------------------------------------- apps
    /**
     * Everything an AI (or a person) needs to say which apps can go: the TV, its memory, the apps the user installed, the system
     * apps still on, the ones already off, and what is using memory right now. The page turns it into a prompt to copy.
     */
    private fun appsReport(): JSONObject {
        // one shell round trip: the parts come back separated by "#~#" lines (meminfo --oom: the full one asks every app, seconds on a slow TV)
        val p = sh("getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; grep MemTotal /proc/meminfo; " +
            "printf '\\043~\\043\\n'; pm list packages -3 -e; printf '\\043~\\043\\n'; pm list packages -s -e; printf '\\043~\\043\\n'; pm list packages -d; " +
            "printf '\\043~\\043\\n'; dumpsys meminfo --oom").split("#~#\n")
        val part = { i: Int -> p.getOrElse(i) { "" } }
        val list = { i: Int -> part(i).split("\n").filter { it.startsWith("package:") }.map { it.substring(8).trim() }.sorted() }
        val head = part(0).split("\n")
        val user = list(1)
        val o = JSONObject().put("device", (head.getOrElse(0) { "" }.trim() + " " + head.getOrElse(1) { "" }.trim()).trim())
            .put("android", head.getOrElse(2) { "" }.trim())
            .put("ram_mb", (Regex("MemTotal:\\s+(\\d+)").find(part(0))?.groupValues?.get(1)?.toLongOrNull() ?: 0L) / 1024)
            .put("user", JSONArray(user)).put("system", JSONArray(list(2))).put("disabled", JSONArray(list(3)))
            .put("memory", JSONArray(memoryByApp(part(4)).map { JSONObject().put("pkg", it.first).put("mb", it.second) }))
        val names = JSONObject()
        for (p in user) labels?.label(p)?.let { names.put(p, it) }
        return o.put("labels", names)
    }

    /** The user's apps plus every one with a tile on the home screen (YouTube, Netflix: preinstalled, so `-3` leaves them out), as TV Setup lists them. */
    private fun apps(system: Boolean): JSONObject {
        val pkgs = ArrayList<String>()
        // the apps turned off are always listed, so "Turn on" is at hand without showing every system app
        val listing = sh("pm list packages" + (if (system) "" else " -3") + "; echo '#~#'; pm list packages -d; echo '#~#'; " +
            "dumpsys package packages | grep -E '^  Package \\[|^    versionName='" +   // every version in one go: ~0.2 s
            (if (!system) "; echo '#~#'; for c in LEANBACK_LAUNCHER LAUNCHER; do cmd package query-activities --brief -a android.intent.action.MAIN" +
                " -c android.intent.category.\$c 2>/dev/null; done" else "")).split("#~#")
        for (line in listing.getOrElse(3) { "" }.jsplit("\n"))
            if (line.contains('/')) line.trim().substringBefore('/').let { if (it !in pkgs) pkgs.add(it) }
        val versions = HashMap<String, String>()
        var at: String? = null
        for (line in listing.getOrElse(2) { "" }.jsplit("\n")) {
            val t = line.trim()
            if (t.startsWith("Package [")) at = t.substring(9, t.indexOf(']').coerceAtLeast(9))
            else if (t.startsWith("versionName=") && at != null) { if (t != "versionName=null") versions.getOrPut(at) { t.substring(12) }; at = null }   // the first: an updated system app's own, not the factory one listed later
        }
        val off = (listing.getOrElse(1) { "" }).jsplit("\n").filter { it.startsWith("package:") }.map { it.substring(8).trim() }.toSet()
        for (line in listing[0].jsplit("\n") + (if (system) emptyList() else off.map { "package:$it" }))
            if (line.startsWith("package:") && line.substring(8).trim() !in pkgs) pkgs.add(line.substring(8).trim())
        pkgs.sort()
        val out = JSONArray()
        val icons = labels != null && labels.icons()
        val home = homeNow()
        for (p in pkgs) {
            val label = labels?.label(p)
            out.put(JSONObject().put("pkg", p).put("off", p in off).put("keep", Setup.mustKeep(p) || p == home).put("version", versions[p] ?: "")
                .put("meta", JSONObject().put("label", label ?: p.substring(p.lastIndexOf('.') + 1)).put("icon", icons).put("banner", icons)))
        }
        return JSONObject().put("apps", out)
    }

    /** "Open on the TV": the settings, an app by its package name, or a link for whatever app takes it. */
    private fun open(what: String) {
        val w = what.trim()
        if (w == "settings") { if (sh("am start -a android.settings.SETTINGS 2>&1").contains("Error")) throw IllegalArgumentException("the TV did not open its settings") }
        else if (PKG.matcher(w).matches()) app(w, "launch")
        else openLink(w)
    }

    private fun app(pkg: String, action: String): HttpServer.Response {
        if (!PKG.matcher(pkg).matches()) throw IllegalArgumentException("bad package name")
        when (action) {
            "launch" -> { Scenes.launch({ sh(it) }, pkg); return HttpServer.Response.ok() }
            "stop" -> { if (Setup.mustKeep(pkg)) throw IllegalArgumentException("protected: the TV needs this app"); keepCheck(pkg); sh("am force-stop $pkg"); return HttpServer.Response.ok() }
            "uninstall" -> {
                if (Setup.mustKeep(pkg) || pkg == homeNow() || Setup.inUse(::sh, pkg)) throw IllegalArgumentException("protected: the TV needs this app")
                val out = sh("pm uninstall $pkg 2>&1").trim()
                if (!out.contains("Success")) throw IllegalArgumentException(if (out.isEmpty()) "uninstall failed" else out)
                return HttpServer.Response.ok()
            }
            // off and on again, for the system apps nobody uses (Undo-able, unlike uninstalling); never what the TV or this project needs
            "disable" -> {
                if (Setup.mustKeep(pkg) || pkg == homeNow() || Setup.inUse(::sh, pkg)) throw IllegalArgumentException("protected: the TV needs this app")
                val out = sh("pm disable-user --user 0 $pkg 2>&1")
                if (!out.contains("disabled")) throw IllegalArgumentException(out.trim().ifEmpty { "disable failed" })
                return HttpServer.Response.ok()
            }
            "clear" -> {                                              // back to as just installed: logins, settings, downloads all go
                if (Setup.mustKeep(pkg) || pkg == homeNow() || Setup.inUse(::sh, pkg)) throw IllegalArgumentException("protected: the TV needs this app")
                val out = sh("pm clear $pkg 2>&1")
                if (!out.contains("Success")) throw IllegalArgumentException(out.trim().ifEmpty { "clear failed" })
                return HttpServer.Response.ok()
            }
            "enable" -> {
                val out = sh("pm enable $pkg 2>&1")
                if (!out.contains("enabled")) throw IllegalArgumentException(out.trim().ifEmpty { "enable failed" })
                return HttpServer.Response.ok()
            }
            else -> throw IllegalArgumentException("unknown action")
        }
    }

    // ---------------------------------------------------------------- privacy check
    /** Revoke (or give back) the permissions the scan saw an app hold; the TV keeps what was revoked, so Give back works after a restart. */
    private fun privacyPerms(pkg: String, revoke: Boolean): JSONObject {
        val P = dev.tvremote.core.ontv.Privacy
        if (!PKG.matcher(pkg).matches()) throw IllegalArgumentException("bad package name")
        if (Setup.mustKeep(pkg)) throw IllegalArgumentException("protected: the TV needs this app")
        val row = privacy.row(pkg) ?: throw IllegalArgumentException("scan again")
        val list = (row.optJSONArray(if (revoke) "perms" else "revoked") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } }
        val out = sh(if (revoke) P.revokeCmd(pkg, list) else P.grantCmd(pkg, list))
        val ok = out.lines().mapNotNull { it.trim().takeIf { l -> l.startsWith("ok:") }?.substring(3) }
        if (ok.isEmpty() && list.isNotEmpty()) throw IllegalArgumentException("locked: the system holds these permissions")
        val f = P.REVOKED_FILE
        for (p in ok) sh(if (revoke) "grep -qx '$pkg $p' $f 2>/dev/null || echo '$pkg $p' >> $f" else "grep -vx '$pkg $p' $f > $f.new 2>/dev/null; mv $f.new $f")
        fun names(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
        val now = privacy.change(pkg) { o ->
            val held = if (revoke) names(o.optJSONArray("perms")) - ok.toSet() else names(o.optJSONArray("perms")) + ok
            val gone = if (revoke) names(o.optJSONArray("revoked")) + ok else names(o.optJSONArray("revoked")) - ok.toSet()
            o.put("perms", JSONArray(held)).put("revoked", JSONArray(gone))
            val heldTags = held.mapNotNull { P.PERM_TAG[it] }.toSet(); val goneTags = gone.mapNotNull { P.PERM_TAG[it] }.toSet()
            val tags = names(o.optJSONArray("tags")).filter { t -> t !in goneTags || t in heldTags } + heldTags.filter { t -> names(o.optJSONArray("tags")).none { it == t } }
            o.put("tags", JSONArray(P.ORDER.filter { it in tags })).put("gone", JSONArray(P.ORDER.filter { it in goneTags - heldTags }))
        }
        return JSONObject().put("ok", true).put("done", ok.size).put("failed", list.size - ok.size).put("row", now ?: JSONObject.NULL)
    }

    /** Turn an app off (Optimize TV's way: `pm disable-user`, written to its Undo list) or back on. Refuses what the TV needs. */
    /** Its accessibility service off: what reads the screen and can press keys stops, the app stays. Turned on again in the TV's Settings. */
    private fun privacyA11yOff(pkg: String): JSONObject {
        if (!PKG.matcher(pkg).matches()) throw IllegalArgumentException("bad package name")
        if (Setup.mustKeep(pkg)) throw IllegalArgumentException("protected: the TV needs this app")
        val left = sh(dev.tvremote.core.ontv.Privacy.a11yOffCmd(pkg))
        if (left.split(':').any { it.trim().startsWith("$pkg/") }) throw IllegalArgumentException("locked: the system holds these permissions")
        return JSONObject().put("ok", true).put("row", privacy.change(pkg) { o ->
            fun drop(key: String, keep: (Any) -> Boolean) { val a = o.optJSONArray(key) ?: return; o.put(key, JSONArray((0 until a.length()).map { a.get(it) }.filter(keep))) }
            drop("tags") { it != "a11y" }; drop("ev") { (it as? JSONObject)?.optString("id") != "a11y" }
        } ?: JSONObject.NULL)
    }

    private fun privacyOff(pkg: String, off: Boolean): JSONObject {
        app(pkg, if (off) "disable" else "enable")
        if (off) sh("grep -qx '$pkg' ${Setup.OFF_LIST} 2>/dev/null || echo '$pkg' >> ${Setup.OFF_LIST}")
        return JSONObject().put("ok", true).put("row", privacy.change(pkg) { it.put("off", off) } ?: JSONObject.NULL)
    }

    /** Makes the TV open a link (a video, a web page) with whatever app takes it. */
    fun openLink(url: String) {
        val u = url.trim()
        if (!u.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:\\S+$"))) throw IllegalArgumentException("not a link")
        val out = sh("am start -a android.intent.action.VIEW -d " + Adb.q(u) + " 2>&1")
        if (out.contains("Error") || out.lowercase(Locale.ROOT).contains("unable to resolve")) throw IllegalArgumentException("the TV has no app that can open this")
    }

    /** 💾 one app as a .tar, made on the TV and streamed straight through (TvShell.backup says what is in it). */
    private fun backup(pkg: String): HttpServer.Response {
        TvShell.backup(pkg)                                           // checks the name before it reaches a header
        val adb = link.get()
        val out = HttpServer.Response.stream("application/x-tar", -1) { o -> pipe(adb.exec(TvShell.backup(pkg)), o) }.also { it.patient = true }
        out.headers["Content-Disposition"] = "attachment; filename*=UTF-8''$pkg-backup.tar"   // the phone's WebView reads this form
        return out
    }

    /**
     * The task manager (Health tab): the apps holding memory now, what each is doing ([Proc.state]) and whether it may be
     * stopped. One shell round trip; meminfo takes a second or more on a slow TV, so the page asks only while it is open.
     */
    private fun procs(): JSONObject {
        val p = sh("grep -E '^(MemTotal|MemAvailable):' /proc/meminfo; printf '\\043~\\043\\n'; dumpsys meminfo --oom; printf '\\043~\\043\\n'; " + KEEP_NOW).split("#~#\n")
        val kb = { k: String -> (Meminfo.kb(p[0], k) ?: 0L) * 1024 }
        val list = JSONArray()
        for (a in procsOf(p.getOrElse(1) { "" }, keepNow(p.getOrElse(2) { "" }))) list.put(JSONObject().put("pkg", a.pkg)
            .put("label", labels?.label(a.pkg) ?: a.pkg).put("mb", a.mb).put("state", a.state).put("keep", a.keep ?: JSONObject.NULL))
        return JSONObject().put("apps", list).put("mem_total", kb("MemTotal")).put("mem_free", kb("MemAvailable"))
    }

    /** The open apps ([Recents.json]), each with why it must stay running (null: may be closed), as [procs] says it. */
    private fun recents(): JSONObject {
        val p = sh(dev.tvremote.core.ontv.Recents.CMD + "; printf '\\043~\\043\\n'; " + KEEP_NOW).split("#~#\n")
        val o = dev.tvremote.core.ontv.Recents.json(p[0]); val keep = keepNow(p.getOrElse(1) { "" })
        val a = o.getJSONArray("apps")
        for (i in 0 until a.length()) a.getJSONObject(i).let { it.put("keep", keepWhy(it.getString("pkg"), keep) ?: JSONObject.NULL) }
        return o
    }

    /** Stopping [pkg] would hurt the TV (its home screen, keyboard, a part of Android) or cut this remote off: refused. */
    /** The launcher Home opens now: never removed, turned off or cleared (any other launcher may be). */
    private fun homeNow(): String = dev.tvremote.core.ontv.LauncherSwap.last(sh(dev.tvremote.core.ontv.LauncherSwap.RESOLVE_HOME)).substringBefore('/')

    private fun keepCheck(pkg: String) {
        if (!PKG.matcher(pkg).matches()) throw IllegalArgumentException("bad package name")
        val keep = keepNow(sh(KEEP_NOW))
        if (keepWhy(pkg, keep) != null) throw IllegalArgumentException("protected: the TV needs this app")
    }

    /** Closes the apps that are only waiting in the background (what is on screen stays); says how much memory came back, in MB. */
    private fun free(): JSONObject {
        val avail = { (Meminfo.kb(sh("cat /proc/meminfo"), "MemAvailable") ?: 0L) / 1024 }
        val before = avail()
        sh("am kill-all")
        Thread.sleep(1500)
        val after = avail()
        return JSONObject().put("before", before).put("after", after).put("freed", maxOf(0L, after - before))
    }
}
