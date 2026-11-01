package dev.tvremote.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * The one table of TV Home's grid: what each kind of tile is, what its menu offers, where it shows and the one change each action
 * writes. HomeActivity, the pickers and the + Add counts read it, so a new kind or action is a row here, and TilesTest walks random
 * edits through it and fails when a tile can be lost (on no grid, in no folder, in no list that brings it back) or a menu offers
 * what the tile cannot do. dev/flows/home-tiles.md says it in words.
 *
 * Inputs are keys only: [apps] the grid's own items in the order shown (installed apps, then what "tiles" resolved to: live
 * widgets, shortcuts, folders), [pins] the shortcuts pinned through Android ([Target] "pin:" keys, not always in "tiles").
 */
object Tiles {
    enum class Kind { APP, LIVE, PIN, FOLDER }

    /** A row of the tile menu. MOVE / FOLDER_OUT: where it sits; HIDE: off the grid into Choose apps (apps only, that list has no
     *  other kind); REMOVE: out of "tiles" (a shortcut also unpinned); START: power-on app and remote keys (any [Target]);
     *  LOCK / MAINT: TV Setup's rules and the app's own pages (apps); WATCH: its Play Next items; LINK: a pinned link's address. */
    enum class Act { MOVE, FOLDER_OUT, FOLDER_PUT, RENAME, HIDE, REMOVE, START, LOCK, MAINT, WATCH, LINK }

    fun kind(key: String): Kind? = if (HomeConfig.isFolder(key)) Kind.FOLDER else when (Target.kind(key)) {
        Target.Kind.APP -> Kind.APP
        Target.Kind.TILE -> Kind.LIVE
        Target.Kind.PIN -> Kind.PIN
        else -> null
    }

    /** What the menu of tile [key] offers; [own] is TV Home's package. A folder has its own menu (none of these). WATCH and LINK
     *  are offered only when there is something to show (Play Next items, a link): the UI checks that on top. */
    fun acts(conf: JSONObject, key: String, own: String): Set<Act> {
        val k = kind(key)
        if (k == null || k == Kind.FOLDER) return emptySet()
        val out = LinkedHashSet<Act>()
        val inFolder = Folders.folderOf(conf, key) != null
        out.add(if (inFolder) Act.FOLDER_OUT else Act.MOVE)
        if (key != own && HomeConfig.folderMember(key)) out.add(Act.FOLDER_PUT)
        if (k != Kind.LIVE) out.add(Act.RENAME)                       // a live widget's name is its numbers' title
        if (!inFolder) out.add(if (k == Kind.APP) Act.HIDE else Act.REMOVE)
        out.add(Act.START)
        if (k == Kind.APP) { if (key != own) out.add(Act.LOCK); out.add(Act.MAINT); out.add(Act.WATCH) }
        if (k == Kind.PIN) out.add(Act.LINK)
        return out
    }

    private fun list(c: JSONObject, k: String) = c.optJSONArray(k).let { a -> if (a == null) emptyList() else List(a.length()) { a.optString(it) } }

    /** HIDE's change: in "hidden", out of the dock; null for anything but an app. */
    fun hide(conf: JSONObject, key: String): JSONObject? =
        if (kind(key) != Kind.APP) null else JSONObject().put("hidden", JSONArray(list(conf, "hidden") - key + key)).put("dock", JSONArray(list(conf, "dock") - key))

    /** REMOVE's change to the settings (a shortcut is also unpinned from Android by the caller): out of "tiles", the dock, hidden and the order. */
    fun remove(conf: JSONObject, key: String): JSONObject? {
        if (kind(key).let { it == null || it == Kind.APP }) return null
        fun without(k: String) = JSONArray(list(conf, k) - key)
        return JSONObject().put("tiles", without("tiles")).put("dock", without("dock")).put("hidden", without("hidden")).put("order", without("order"))
    }

    /** "+ Add"'s change for a live widget or shortcut ([own]: kept in "tiles"; a shortcut pinned through Android is on the grid without it). */
    fun add(conf: JSONObject, key: String, own: Boolean): JSONObject {
        fun l(k: String) = JSONArray(list(conf, k) - key)
        val c = JSONObject().put("hidden", l("hidden")).put("dock", l("dock")).put("order", JSONArray(list(conf, "order") - key + key))
        if (own) c.put("tiles", JSONArray(list(conf, "tiles") - key + key))
        return c
    }

    /** The grid in the order drawn: the dock's keys that are there, then the rest by "order" (unknown ones last, in [apps] order;
     *  [first]: unknown ones that go first instead). */
    fun grid(conf: JSONObject, apps: List<String>, pins: List<String>, first: (String) -> Boolean = { false }): List<String> {
        val have = apps.toSet()
        val dock = list(conf, "dock").filter { it in have || it in pins }
        val hidden = list(conf, "hidden").toSet()
        val inFolder = Folders.members(conf)
        val shownPins = pins.filter { it !in have && it !in dock && it !in inFolder }
        val place = list(conf, "order").withIndex().associate { it.value to it.index }
        val rest = (shownPins + apps.filter { it !in dock && it !in hidden && it !in inFolder }).sortedBy { place[it] ?: if (first(it)) -1 else Int.MAX_VALUE }
        return dock + rest
    }

    /** The hidden apps that "Choose apps" lists, and + Add counts: installed ones only. */
    fun hiddenShown(conf: JSONObject, apps: List<String>): List<String> { val have = apps.toSet(); return list(conf, "hidden").filter { it in have && kind(it) == Kind.APP } }

    /**
     * What is wrong with this grid, in words (empty: nothing): a tile that shows twice, one that is lost (an installed app on no
     * grid, not in Choose apps, in no folder shown; a live widget or shortcut nowhere), a folder holding tiles but on no grid, a
     * tile a folder cannot take, a menu that offers Hide / Remove / Move wrongly. TilesTest runs it after every random edit, the
     * TV runs it on its real grid (HomeReceiver "tiles", dev/test/home_tiles.sh).
     */
    fun problems(conf: JSONObject, apps: List<String>, pins: List<String>, own: String): List<String> {
        val out = ArrayList<String>()
        val grid = grid(conf, apps, pins)
        if (grid.size != grid.toSet().size) out.add("shows twice: " + grid.groupBy { it }.filterValues { it.size > 1 }.keys)
        val folders = Folders.of(conf)
        val inFolders = folders.filterKeys { HomeConfig.folderKey(it) in grid }.values.flatMap { it.apps }.toSet()
        val hidden = hiddenShown(conf, apps)
        for (k in (apps + pins).distinct()) {
            val kind = kind(k)
            if (kind == Kind.FOLDER || k in grid || k in inFolders) continue
            if (kind != Kind.APP || k !in hidden) out.add("lost: $k")
        }
        for (k in grid.filter { it in hidden }) out.add("on the grid and in Choose apps: $k")
        val there = (apps + pins).toSet()
        for ((id, f) in folders) if (HomeConfig.folderKey(id) !in grid && f.apps.any { it in there }) out.add("folder on no grid: $id")
        val choices = folderChoices(apps, pins, own).toSet()
        for (k in grid) if (kind(k) != Kind.FOLDER && k != own && k !in choices) out.add("a folder cannot take: $k")
        for (k in grid + inFolders) {
            val kind = kind(k) ?: continue
            if (kind == Kind.FOLDER) continue
            val a = acts(conf, k, own); val inF = k in inFolders
            if ((Act.HIDE in a) != (kind == Kind.APP && !inF)) out.add("Hide wrong: $k")
            if ((Act.REMOVE in a) != (kind != Kind.APP && !inF)) out.add("Remove wrong: $k")
            if ((Act.FOLDER_OUT in a) != inF || (Act.MOVE in a) == inF) out.add("Move / Out of the folder wrong: $k")
            if (k != own && Act.FOLDER_PUT !in a) out.add("no Put in a folder: $k")
        }
        return out
    }

    /** What a folder's picker offers: every app and every live widget or shortcut that is there (on the grid or in a folder). */
    fun folderChoices(apps: List<String>, pins: List<String>, own: String): List<String> =
        (apps + pins).distinct().filter { it != own && HomeConfig.folderMember(it) }
}
