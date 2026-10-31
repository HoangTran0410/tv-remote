package dev.tvremote.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * Folders on TV Home's app grid: the operations the TV (long-press menus) and the clients' page do, as pure functions over the
 * settings. Each takes the settings ([HomeConfig.clean]ed) and returns the CHANGE to hand to `Store.change` / `/api/home`: always
 * the five fields a folder touches (dock, hidden, order, tiles, folders), so one write is one redraw. The page's `js/home_folders.js`
 * is the twin of these (tests/home_folders.test.js and FoldersTest hold the same cases).
 *
 * A folder is a grid tile ([HomeConfig.folderKey] in "tiles" and "order"); its members (apps, and the live widgets and shortcuts
 * already in "tiles", which stay there) are off the grid, out of the dock and out of
 * "hidden". Taking an app out puts it on the grid right after the folder; deleting a folder puts all its apps there. A folder with
 * no app is deleted by the same operation that emptied it.
 */
object Folders {
    class Folder(val name: String, val apps: MutableList<String>)

    private class State(c: JSONObject) {
        val dock = list(c.optJSONArray("dock"))
        val hidden = list(c.optJSONArray("hidden"))
        val order = list(c.optJSONArray("order"))
        val tiles = list(c.optJSONArray("tiles"))
        val folders = LinkedHashMap<String, Folder>()

        init {
            val g = c.optJSONObject("folders")
            if (g != null) for (id in g.keys().asSequence().sorted()) {
                val f = g.optJSONObject(id) ?: continue
                folders[id] = Folder(f.optString("name"), list(f.optJSONArray("apps")))
            }
        }

        fun key(id: String) = HomeConfig.folderKey(id)

        /** [pkg] out of the dock, the hidden list, the order and every folder (an emptied folder goes). */
        fun take(pkg: String) {
            dock.remove(pkg); hidden.remove(pkg); order.remove(pkg)
            for (f in folders.values) f.apps.remove(pkg)
            prune()
        }

        /** Folders with no app are no folders: their tile and place go. */
        fun prune() {
            for (id in folders.filterValues { it.apps.isEmpty() }.keys) { folders.remove(id); tiles.remove(key(id)); order.remove(key(id)) }
        }

        fun change(): JSONObject = JSONObject()
            .put("dock", JSONArray(dock)).put("hidden", JSONArray(hidden)).put("order", JSONArray(order)).put("tiles", JSONArray(tiles))
            .put("folders", JSONObject().also { o -> folders.forEach { (id, f) -> o.put(id, JSONObject().put("name", f.name).put("apps", JSONArray(f.apps))) } })
    }

    private fun list(a: JSONArray?) = MutableList(a?.length() ?: 0) { a!!.getString(it) }

    /** A name as stored: one line, [HomeConfig.NAME_MAX] characters. */
    fun cleanName(s: String) = s.replace(Regex("\\s+"), " ").trim().take(HomeConfig.NAME_MAX).trim()

    /** The folders of [c]: id to (name, apps), in id order. */
    fun of(c: JSONObject): Map<String, Folder> = State(c).folders

    /** The folder [pkg] is in, or null. */
    fun folderOf(c: JSONObject, pkg: String): String? = State(c).folders.entries.firstOrNull { pkg in it.value.apps }?.key

    /** Every package that is in some folder (off the grid because of it). */
    fun members(c: JSONObject): Set<String> = State(c).folders.values.flatMapTo(HashSet()) { it.apps }

    /** A free id: 6 lowercase letters and digits from [rnd]. */
    fun newId(c: JSONObject, rnd: java.util.Random = java.util.Random()): String {
        val used = State(c).folders.keys
        while (true) {
            val id = String(CharArray(6) { "abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)] })
            if (id !in used) return id
        }
    }

    /** True when one more folder fits. */
    fun canCreate(c: JSONObject) = State(c).let { it.folders.size < HomeConfig.FOLDERS_MAX && it.tiles.size < HomeConfig.TILES_MAX }

    /**
     * A new folder [id] named [name] holding [app] (a folder always holds an app). It takes [app]'s place in the grid's order (the
     * end when it has none). Null when it does not fit or [app] is not a [HomeConfig.folderMember].
     */
    fun create(c: JSONObject, id: String, name: String, app: String): JSONObject? {
        val s = State(c)
        if (s.folders.size >= HomeConfig.FOLDERS_MAX || s.tiles.size >= HomeConfig.TILES_MAX || id in s.folders || !HomeConfig.isFolder(s.key(id))) return null
        if (!HomeConfig.folderMember(app)) return null
        val at = s.order.indexOf(app)
        s.take(app)
        s.folders[id] = Folder(cleanName(name), mutableListOf(app))
        s.tiles.add(s.key(id))
        s.order.add(if (at in 0..s.order.size) at else s.order.size, s.key(id))
        return s.change()
    }

    fun rename(c: JSONObject, id: String, name: String): JSONObject? {
        val s = State(c)
        val f = s.folders[id] ?: return null
        f.let { s.folders[id] = Folder(cleanName(name), it.apps) }
        return s.change()
    }

    /** The folder goes; its apps come back to the grid where it was. */
    fun delete(c: JSONObject, id: String): JSONObject? {
        val s = State(c)
        val f = s.folders.remove(id) ?: return null
        val at = s.order.indexOf(s.key(id)).let { if (it < 0) s.order.size else it }
        s.order.remove(s.key(id)); s.tiles.remove(s.key(id))
        s.order.addAll(minOf(at, s.order.size), f.apps.filter { it !in s.order })
        return s.change()
    }

    /** [app] into folder [id] (from the grid, the dock, the hidden list or another folder; an emptied folder goes), at [to] (the end by default). */
    fun add(c: JSONObject, id: String, app: String, to: Int = Int.MAX_VALUE): JSONObject? {
        val s = State(c)
        if (id !in s.folders || !HomeConfig.folderMember(app)) return null
        if (app in s.folders[id]!!.apps) return if (to == Int.MAX_VALUE) s.change() else reorder(c, id, app, to)
        if (s.folders[id]!!.apps.size >= HomeConfig.FOLDER_APPS_MAX && app !in s.folders[id]!!.apps) return null
        s.take(app)
        val f = s.folders[id] ?: return null                       // [app] was its only one and it went with it
        f.apps.add(to.coerceIn(0, f.apps.size), app)
        return s.change()
    }

    /** [app] out of folder [id], onto the grid right after the folder's tile. Null when it is not there. */
    fun remove(c: JSONObject, id: String, app: String): JSONObject? {
        val s = State(c)
        val f = s.folders[id] ?: return null
        if (!f.apps.remove(app)) return null
        val at = s.order.indexOf(s.key(id))
        if (app !in s.order) s.order.add(if (at < 0) s.order.size else at + 1, app)
        s.prune()
        return s.change()
    }

    /** [app] moved to place [to] inside folder [id]. */
    fun reorder(c: JSONObject, id: String, app: String, to: Int): JSONObject? {
        val s = State(c)
        val f = s.folders[id] ?: return null
        if (!f.apps.remove(app)) return null
        f.apps.add(to.coerceIn(0, f.apps.size), app)
        return s.change()
    }

    /** Apps that are [gone] (uninstalled) out of every folder; null when none was in one. */
    fun forget(c: JSONObject, gone: (String) -> Boolean): JSONObject? {
        val s = State(c)
        var any = false
        for (f in s.folders.values) if (f.apps.removeAll { gone(it) }) any = true
        if (!any) return null
        s.prune()
        return s.change()
    }
}
