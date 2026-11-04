package dev.tvremote.home

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * TV Home's grid as a world of random edits: installed apps come and go, shortcuts are pinned through Android or + Add, live
 * widgets added, apps hidden, tiles removed, folders made and emptied, each change through [Tiles] / [Folders] and
 * [HomeConfig.merge] as the TV does. After every step the invariants below must hold; a failure prints the seed and the steps.
 */
class TilesTest {
    private val own = "dev.tvremote.home"
    private val allApps = listOf("com.a.one", "com.b.two", "com.c.three", "com.d.four", "com.e.five", own)
    private val allLive = listOf("tile:dev.tvremote.setup/cpu", "tile:dev.tvremote.setup/ram", "tile:dev.tvremote.channels/now")
    private val allPins = listOf("pin:com.a.one/x", "pin:com.b.two/y", "pin:dev.tvremote.home/pn1", "pin:com.c.three/ch:a@b")

    private fun list(c: JSONObject, k: String) = c.optJSONArray(k).let { a -> if (a == null) emptyList() else List(a.length()) { a.getString(it) } }

    private class World(val installed: MutableSet<String>, val pinned: MutableSet<String>, var conf: JSONObject)

    /** What HomeActivity has: [apps] = installed apps + what "tiles" resolves to (a shortcut only while pinned), [pins] = Android's pinned list. */
    private fun apps(w: World): List<String> {
        val folders = w.conf.optJSONObject("folders")
        val tiles = list(w.conf, "tiles").filter { when (Tiles.kind(it)) {
            Tiles.Kind.FOLDER -> folders?.has(HomeConfig.folderId(it)) == true
            Tiles.Kind.PIN -> it in w.pinned
            else -> true
        } }
        return w.installed.toList() + tiles
    }

    private fun check(w: World, log: List<String>) {
        val apps = apps(w)
        val bad = Tiles.problems(w.conf, apps, w.pinned.toList(), own) +
            w.installed.filter { it !in apps }.map { "installed but not among the apps: $it" } +
            Tiles.dock(w.conf, apps.toSet(), w.pinned.toList()).let { d ->   // HomeActivity.show splits the grid at the dock's size
                val g = Tiles.grid(w.conf, apps, w.pinned.toList()); if (g.take(d.size) != d || g.drop(d.size).any { it in d }) listOf("grid does not start with its dock: $g / $d") else emptyList() }
        if (bad.isNotEmpty()) fail(bad.joinToString("; ") + "\nconf=${w.conf}\ninstalled=${w.installed} pinned=${w.pinned}\nsteps:\n" + log.joinToString("\n"))
    }

    private fun apply(w: World, change: JSONObject?) { if (change != null) w.conf = HomeConfig.merge(w.conf, change) }

    @Test fun randomEditsLoseNoTile() {
        for (seed in 1..400) {
            val r = java.util.Random(seed.toLong())
            val w = World(allApps.filter { r.nextInt(4) > 0 || it == own }.toMutableSet(), mutableSetOf(), HomeConfig.clean(JSONObject()))
            val log = ArrayList<String>()
            repeat(60) {
                fun <T> pick(l: Collection<T>): T? = if (l.isEmpty()) null else l.elementAt(r.nextInt(l.size))
                val apps = apps(w); val grid = Tiles.grid(w.conf, apps, w.pinned.toList())
                val op = r.nextInt(14)
                val step: String? = when (op) {
                    0 -> pick(grid.filter { Tiles.Act.HIDE in Tiles.acts(w.conf, it, own) })?.also { apply(w, Tiles.hide(w.conf, it)) }?.let { "hide $it" }
                    1 -> pick(Tiles.hiddenShown(w.conf, apps))?.also { apply(w, Tiles.add(w.conf, it, false)) }?.let { "choose $it" }
                    2 -> pick(grid.filter { Tiles.Act.REMOVE in Tiles.acts(w.conf, it, own) })?.also {
                        if (Tiles.kind(it) == Tiles.Kind.PIN) w.pinned.remove(it)   // Pins.off: the system pin too
                        apply(w, Tiles.remove(w.conf, it))
                    }?.let { "remove $it" }
                    3 -> pick(allLive)?.also { apply(w, Tiles.add(w.conf, it, true)) }?.let { "add live $it" }
                    4 -> pick(allPins.filter { Target.pkg(it) in w.installed })?.also { w.pinned.add(it) }?.let { "android pins $it" }
                    5 -> pick(allPins.filter { Target.pkg(it) == own })?.also { w.pinned.add(it); apply(w, Tiles.add(w.conf, it, true)) }?.let { "+ Add pins $it" }
                    6 -> pick(Tiles.folderChoices(apps, w.pinned.toList(), own))?.let { k ->
                        val id = Folders.newId(w.conf, r); apply(w, Folders.create(w.conf, id, "F", k)); "new folder $id with $k"
                    }
                    7 -> { val id = pick(Folders.of(w.conf).keys); val k = pick(Tiles.folderChoices(apps, w.pinned.toList(), own))
                        if (id == null || k == null) null else { apply(w, Folders.add(w.conf, id, k)); "folder $id + $k" } }
                    8 -> pick(Folders.of(w.conf).entries)?.let { (id, f) -> pick(f.apps)?.let { k -> apply(w, Folders.remove(w.conf, id, k)); "folder $id - $k" } }
                    9 -> pick(Folders.of(w.conf).keys)?.also { apply(w, Folders.delete(w.conf, it)) }?.let { "delete folder $it" }
                    10 -> pick(grid.filter { Tiles.Act.MOVE in Tiles.acts(w.conf, it, own) && Tiles.kind(it) == Tiles.Kind.APP })?.also {
                        apply(w, JSONObject().put("dock", JSONArray(list(w.conf, "dock") - it + it)))
                    }?.let { "dock $it" }
                    11 -> pick(w.installed.filter { it !in Tiles.hiddenShown(w.conf, apps) })?.let { k ->   // Customize's app list: any app, one in a folder too
                        apply(w, Tiles.hide(w.conf, k))
                        if (k !in Tiles.hiddenShown(w.conf, apps(w))) fail("seed $seed: $k hidden but not in Choose apps\n" + log.joinToString("\n"))
                        "toggle off $k"
                    }
                    12 -> { val all = w.installed.toList(); val on = all.filter { r.nextBoolean() }   // Choose apps' Done
                        apply(w, Tiles.choose(w.conf, all, on))
                        for (k in all - on.toSet()) if (k !in Tiles.hiddenShown(w.conf, apps(w))) fail("seed $seed: $k not ticked but not in Choose apps\n" + log.joinToString("\n"))
                        "choose $on" }
                    else -> pick(w.installed - own)?.also { w.installed.remove(it); w.pinned.removeAll { p -> Target.pkg(p) == it } }?.let { "uninstall $it" }
                }
                if (step != null) { log.add(step); check(w, listOf("seed $seed") + log) }
            }
        }
    }

    @Test fun menuPerKind() {
        val c = HomeConfig.clean(JSONObject())
        assertEquals(setOf(Tiles.Act.MOVE, Tiles.Act.FOLDER_PUT, Tiles.Act.RENAME, Tiles.Act.PICTURE, Tiles.Act.HIDE, Tiles.Act.START, Tiles.Act.LOCK, Tiles.Act.MAINT, Tiles.Act.WATCH),
            Tiles.acts(c, "com.a.one", own))
        assertEquals(setOf(Tiles.Act.MOVE, Tiles.Act.FOLDER_PUT, Tiles.Act.REMOVE, Tiles.Act.START), Tiles.acts(c, allLive[0], own))
        assertEquals(setOf(Tiles.Act.MOVE, Tiles.Act.FOLDER_PUT, Tiles.Act.RENAME, Tiles.Act.PICTURE, Tiles.Act.REMOVE, Tiles.Act.START, Tiles.Act.LINK), Tiles.acts(c, allPins[0], own))
        assertTrue(Tiles.acts(c, "folder:ab12", own).isEmpty())
        assertTrue(Tiles.Act.LOCK !in Tiles.acts(c, own, own) && Tiles.Act.FOLDER_PUT !in Tiles.acts(c, own, own))
    }
}
