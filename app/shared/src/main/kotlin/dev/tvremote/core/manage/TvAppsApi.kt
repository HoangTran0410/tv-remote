package dev.tvremote.core.manage
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.web.Cancel
import dev.tvremote.core.web.HttpServer
import dev.tvremote.tvapps.AppList
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * TV Apps' /api for Manage on all three cores (`/api/tvapps`, `…/icon`, `…/install`): the signed list matched to this
 * TV (fits, on it already), and one app downloaded here from its author, checked against its author's certificate, and installed
 * over ADB the way an uploaded APK is ([install], Manage's). Opening one is the apps tab's own `app/<pkg>/launch`.
 */
internal class TvAppsApi(private val link: AdbLink, private val dir: File?, private val job: (String, (JSONObject) -> String) -> JSONObject,
                       private val cancel: () -> Cancel?, private val install: (FileInputStream, Long, Cancel?) -> String) {
    private fun sh(c: String) = link.get().shell(c)

    // one round trip: sdk, ABIs, the packages on the TV
    private class Tv(val sdk: Int, val abis: List<String>, val have: Set<String>)
    private fun tv(): Tv {
        val p = sh("getprop ro.build.version.sdk; getprop ro.product.cpu.abilist; echo @@; pm list packages").split("@@")
        val head = p[0].trim().lines()
        return Tv(head.getOrNull(0)?.trim()?.toIntOrNull() ?: 0, head.getOrNull(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            p.getOrElse(1) { "" }.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet())
    }

    fun list(fresh: Boolean): JSONObject {
        val l = AppList.fetch(dir, fresh)
        val t = tv()
        val apps = JSONArray()
        for (a in l.apps) apps.put(JSONObject().put("id", a.id).put("pkg", a.pkg).put("name", a.name).put("cat", a.cat)
            .put("desc", JSONObject(a.desc)).put("notes", JSONObject(a.notes)).put("source", a.source).put("license", a.license)
            .put("icon", if (a.icon.isNotEmpty()) "/api/tvapps/icon?id=" + a.id else "").put("min_sdk", a.minSdk).put("why", AppList.why(a, t.sdk, t.abis)).put("installed", a.pkg in t.have))
        val cats = JSONArray()
        for (c in l.cats) cats.put(JSONObject().put("id", c.id).put("name", JSONObject(c.name)))
        return JSONObject().put("updated", l.updated).put("cats", cats).put("apps", apps)
    }

    fun icon(id: String): HttpServer.Response {
        val l = AppList.fetch(dir)
        val a = l[id] ?: return HttpServer.Response.error(404, "not found")
        return HttpServer.Response.of("image/png", AppList.icon(l, a, dir))
    }

    /** Download (step "download", pct), check, install (step "install"): a job the page polls, Stop ends either part. */
    fun install(id: String): JSONObject {
        val a = AppList.fetch(dir)[id] ?: throw IllegalArgumentException("no such app")
        return job("app " + a.name) { j ->
            val d = dir ?: throw IOException("no folder for the download")
            val c = cancel()
            j.put("step", "download").put("pct", 0)
            val f = AppList.download(a, tv().abis, d, { j.put("pct", it) }, { c?.stopped == true })
            try {
                j.put("step", "install")
                FileInputStream(f).use { install(it, f.length(), c) }    // its copy to the TV has no bar of its own: "install" until done
            } finally { f.delete() }
        }
    }
}
