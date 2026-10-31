package dev.tvremote.setup.link

import org.json.JSONObject

/**
 * What a paired browser may do here. The owner is whoever reached the TV itself: the Phone and Computer apps (let in over
 * ADB, TrustReceiver), the Manage screen on the TV, and a browser that typed the code shown on the TV's screen; they get
 * "full". A shared QR (Invite.kt) carries the scope its maker picked: "remote" (keys, typing, the picture, opening apps:
 * what a guest on the sofa needs) or "view" (the picture and what is playing, nothing pressed). The owner can change it
 * later in the list of paired devices.
 * An allow-list, so a route added later stays the owner's until it is put here.
 */
object Scope {
    const val FULL = "full"
    const val REMOTE = "remote"
    const val VIEW = "view"
    val ALL = listOf(FULL, REMOTE, VIEW)

    fun of(s: String?): String = if (s in ALL) s!! else FULL          // a pass from before scopes: its owner paired it with the code

    private val VIEW_GET = setOf("/api/state", "/api/about", "/api/setup/need", "/api/screen", "/api/video", "/api/audio", "/api/watchers",
        "/api/app/icon", "/api/app/meta", "/api/playing", "/api/caps", "/api/rules", "/api/scenes", "/api/paired", "/api/screen/state", "/api/dim", "/api/channels/manage", "/api/channels/categories", "/api/channels/export")
    private val VIEW_POST = setOf("/api/adb/connect", "/api/paired/forget", "/api/crash")   // forget: only itself (RemoteService.route)
    private val REMOTE_GET = VIEW_GET + setOf("/api/apps", "/api/clip", "/api/inputs", "/api/channels", "/api/tvkbd")
    private val REMOTE_POST = VIEW_POST + setOf("/api/key", "/api/type", "/api/touch", "/api/clip", "/api/app", "/api/app/launch",
        "/api/playing", "/api/search", "/api/draw", "/api/notify", "/api/screen/off", "/api/screen/on", "/api/dim", "/api/scene/run", "/api/rules", "/api/input", "/api/channels/open", "/api/channels/manage", "/api/channels/import")

    /** Whether [scope] may make this request; [body] is read only for the routes that look into it. */
    fun allows(scope: String, path: String, post: Boolean, query: Map<String, String>, body: () -> JSONObject): Boolean {
        if (scope == FULL) return true
        val remote = scope == REMOTE
        if (!(if (post) (if (remote) REMOTE_POST else VIEW_POST) else (if (remote) REMOTE_GET else VIEW_GET)).contains(path)) return false
        return when (path) {
            "/api/apps" -> query["system"] != "1"                     // the home screen's apps, to open one; not every package
            "/api/scenes" -> !post && query["download"] == null
            "/api/rules" -> !post || body().optString("do") in setOf("sleep", "sleep_add")   // the sleep timer, not bedtime or limits
            "/api/scene/run" -> !body().has("steps")                  // a saved scene by its id; not steps of its own making
            else -> true
        }
    }
}
