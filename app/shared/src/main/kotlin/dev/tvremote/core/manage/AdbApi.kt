package dev.tvremote.core.manage
import dev.tvremote.core.ontv.AdbAccess
import dev.tvremote.core.web.HttpServer
import org.json.JSONObject

/** "Who may use ADB": the keys the TV allows, who is connected, and the TV's own revoke. Owner only (Scope allows no route it does not list). */
internal class AdbApi(core: ManageCore) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? = when {
        what == "adb/access" && !post -> HttpServer.Response.json(AdbAccess.snapshot(link.get()))
        what == "adb/revoke" && post -> HttpServer.Response.json(JSONObject().put("result", AdbAccess.revoke(link.get())))   // the page asked first: it removes every key, ours too
        what == "adb/revoke-one" && post -> HttpServer.Response.json(JSONObject().put("result",     // one key, picked and confirmed on the page
            AdbAccess.revokeOne(link.get(), r.json().optString("fp"))))
        else -> null
    }
}
