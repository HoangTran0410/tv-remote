package dev.tvremote.core.manage
import dev.tvremote.core.adb.Pkg
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.ontv.TvUser
import dev.tvremote.core.web.HttpServer
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Move to a new TV", the TV's own part: `GET /api/backup` reads what only the TV's shell has (its model, the apps Optimize TV turned
 * off, the third-party apps with names); `POST /api/backup/restore` {optimize: [pkg…]} turns those off again, the way the Apps tab does.
 * Scenes, TV Home, rules and channels are read and written by the page through their own routes (js/backup.js).
 */
internal class BackupApi(core: ManageCore, private val apps: AppsApi) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? = when {
        what == "backup" && !post -> HttpServer.Response.json(read())
        what == "backup/restore" && post -> HttpServer.Response.json(restore(r.json(200_000)))
        else -> null
    }

    private fun read(): JSONObject {
        val out = sh("getprop ro.product.model; echo '#~#'; cat ${Setup.OFF_LIST} 2>/dev/null; echo '#~#'; ${TvUser.list("-3")}").split("#~#")
        val label = { p: String -> try { Manage.meta(labels, p).optString("label") } catch (e: Exception) { "" } }
        return JSONObject().put("model", out[0].trim()).put("optimize", JSONArray(Backup.offList(out.getOrElse(1) { "" })))
            .put("apps", Backup.appsOf(Pkg.parse(out.getOrElse(2) { "" }, empty = true), label))
    }

    private fun restore(d: JSONObject): JSONObject {
        val want = Backup.wanted(d.optJSONArray("optimize"))
        val o = sh("${TvUser.list()}; echo '#~#'; ${TvUser.list("-d")}").split("#~#")
        val plan = Backup.plan(want, Pkg.parse(o[0]), Pkg.parse(o.getOrElse(1) { "" }, empty = true))
        var done = 0
        val kept = ArrayList<String>(); val failed = ArrayList<String>()
        for (p in plan.todo) {
            try { apps.turnOff(p); done++ }
            catch (e: IllegalArgumentException) { kept.add(p) }   // protected: the TV needs it
            catch (e: IOException) { failed.add(p) }
        }
        return Backup.result(plan, done, kept, failed)
    }
}
