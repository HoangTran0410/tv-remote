package dev.tvremote.home

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folders on the grid: each operation gives what tests/home_folders_cases.json says (the page's js/home_folders.js reads the same table), and the store keeps only what is whole. */
class FoldersTest {
    private val file = JSONObject(java.io.File("tests/home_folders_cases.json").readText())

    private fun strs(a: JSONArray) = List(a.length()) { a.getString(it) }
    /** A change as comparable text: lists as they are, folders by id. */
    private fun norm(c: JSONObject?): String? = c?.let {
        val f = it.getJSONObject("folders")
        listOf("dock", "hidden", "order", "tiles").joinToString("|") { k -> strs(it.getJSONArray(k)).joinToString(",") } + "|" +
            f.keys().asSequence().sorted().joinToString(";") { id -> id + "=" + f.getJSONObject(id).getString("name") + ":" + strs(f.getJSONObject(id).getJSONArray("apps")).joinToString(",") }
    }

    @Test fun everyOperationFollowsTheSharedCases() {
        val cases = file.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val conf = file.getJSONObject(c.getString("from"))
            val a = c.getJSONArray("args")
            val got = when (c.getString("op")) {
                "create" -> Folders.create(conf, a.getString(0), a.getString(1), a.getString(2))
                "rename" -> Folders.rename(conf, a.getString(0), a.getString(1))
                "delete" -> Folders.delete(conf, a.getString(0))
                "add" -> Folders.add(conf, a.getString(0), a.getString(1), if (a.length() > 2) a.getInt(2) else Int.MAX_VALUE)
                "remove" -> Folders.remove(conf, a.getString(0), a.getString(1))
                "reorder" -> Folders.reorder(conf, a.getString(0), a.getString(1), a.getInt(2))
                else -> error("op")
            }
            assertEquals(c.getString("name"), norm(c.optJSONObject("change")), norm(got))
        }
    }

    private fun clean(c: JSONObject) = HomeConfig.clean(c)

    @Test fun anEmptiedFolderIsDeletedWithItsTileAndPlace() {
        val conf = file.getJSONObject("withFolder")
        val one = Folders.remove(conf, "f1", "com.b.two")!!              // two apps -> one
        val none = Folders.remove(clean(JSONObject(one.toString())), "f1", "com.e.four")!!
        assertEquals(0, none.getJSONObject("folders").length())
        assertFalse(strs(none.getJSONArray("tiles")).contains("folder:f1"))
        assertFalse(strs(none.getJSONArray("order")).contains("folder:f1"))
        assertTrue(strs(none.getJSONArray("order")).contains("com.e.four"))   // the last app is on the grid again
    }

    @Test fun movingTheOnlyAppOfAFolderToAnotherLeavesNoEmptyFolder() {
        val conf = JSONObject(file.getJSONObject("withFolder").toString())
        conf.getJSONObject("folders").put("f2", JSONObject().put("name", "B").put("apps", JSONArray(listOf("com.x.solo"))))
        conf.getJSONArray("tiles").put("folder:f2"); conf.getJSONArray("order").put("folder:f2")
        val ch = Folders.add(conf, "f1", "com.x.solo")!!
        assertEquals(listOf("f1"), ch.getJSONObject("folders").keys().asSequence().toList())
        assertFalse(strs(ch.getJSONArray("tiles")).contains("folder:f2"))
        assertFalse(strs(ch.getJSONArray("order")).contains("folder:f2"))
    }

    @Test fun cleanKeepsOnlyWholeFolders() {
        val c = JSONObject()
            .put("dock", JSONArray(listOf("com.d.dock")))
            .put("tiles", JSONArray(listOf("folder:f1", "folder:f2", "folder:f3", "folder:BAD", "tile:dev.tvremote.setup/ram", "pin:com.z/y")))
            .put("order", JSONArray(listOf("folder:f1", "folder:f9", "com.a.one")))
            .put("folders", JSONObject()
                .put("f1", JSONObject().put("name", "  A\n  B ").put("apps", JSONArray(listOf("com.a.one", "com.d.dock", "pin:com.z/y", "tile:dev.tvremote.setup/ram", "tile:dev.tvremote.setup/gone", "folder:f2", "com.a.one"))))
                .put("f2", JSONObject().put("name", "dup").put("apps", JSONArray(listOf("com.a.one"))))   // an app is in one folder: the first id wins
                .put("f3", JSONObject().put("name", "empty").put("apps", JSONArray()))
                .put("f4", JSONObject().put("name", "no tile").put("apps", JSONArray(listOf("com.k.kept")))))
        val o = clean(c)
        val f = o.getJSONObject("folders")
        assertEquals(listOf("f1"), f.keys().asSequence().toList())
        assertEquals("A B", f.getJSONObject("f1").getString("name"))
        assertEquals(listOf("com.a.one", "pin:com.z/y", "tile:dev.tvremote.setup/ram"), strs(f.getJSONObject("f1").getJSONArray("apps")))   // no dock app, folder, or shortcut / widget that is not a grid tile
        assertEquals(listOf("folder:f1", "tile:dev.tvremote.setup/ram", "pin:com.z/y"), strs(o.getJSONArray("tiles")))                    // f2 lost its only app, f3 is empty, BAD is no key
        assertEquals(listOf("folder:f1", "com.a.one"), strs(o.getJSONArray("order")))        // f9 is no folder
    }

    @Test fun uninstalledAppsLeaveEveryFolder() {
        val conf = file.getJSONObject("withFolder")
        val ch = Folders.forget(conf) { it == "com.e.four" }!!
        assertEquals(listOf("com.b.two"), strs(ch.getJSONObject("folders").getJSONObject("f1").getJSONArray("apps")))
        val all = Folders.forget(conf) { it.startsWith("com.") }!!
        assertEquals(0, all.getJSONObject("folders").length())
        assertFalse(strs(all.getJSONArray("tiles")).contains("folder:f1"))
        assertNull(Folders.forget(conf) { false })
    }

    @Test fun limitsHold() {
        var conf = JSONObject("{\"dock\":[],\"hidden\":[],\"order\":[],\"tiles\":[],\"folders\":{}}")
        for (i in 0 until HomeConfig.FOLDERS_MAX) conf = clean(JSONObject(Folders.create(conf, "f$i", "n$i", "com.a.app$i")!!.toString()).let { conf.merge(it) })
        assertEquals(HomeConfig.FOLDERS_MAX, conf.getJSONObject("folders").length())
        assertNull(Folders.create(conf, "fz", "n", "com.a.more"))
        assertFalse(Folders.canCreate(conf))
        assertTrue(Folders.newId(conf).matches(Regex("[a-z0-9]{6}")))
    }

    private fun JSONObject.merge(change: JSONObject) = JSONObject(toString()).also { for (k in change.keys()) it.put(k, change.get(k)) }
}
