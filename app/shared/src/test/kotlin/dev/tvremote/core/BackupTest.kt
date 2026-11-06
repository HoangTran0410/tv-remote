package dev.tvremote.core
import dev.tvremote.core.manage.Backup
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Move to a new TV": the TV's own part of the backup (Undo's list, the apps, what a restore asks for). */
class BackupTest {
    @Test fun offListKeepsPackagesOnceInOrder() {
        assertEquals(listOf("com.a.one", "com.b.two"), Backup.offList("com.a.one\r\n\n  com.b.two \ncom.a.one\nrm -rf /\nnot a package\n"))
        assertEquals(emptyList<String>(), Backup.offList(""))
    }

    @Test fun appsAreSortedByNameWithoutTheProjectsOwn() {
        val out = Backup.appsOf(listOf("dev.tvremote.setup", "com.z.zed", "com.a.beta", "bad name", "com.a.beta")) { if (it == "com.z.zed") "Alpha" else "" }
        assertEquals(listOf("com.z.zed", "com.a.beta"), (0 until out.length()).map { out.getJSONObject(it).getString("pkg") })
        assertEquals("com.a.beta", out.getJSONObject(1).getString("label"))   // no name: the package shows
    }

    @Test fun wantedTakesValidNamesOnly() {
        val l = JSONArray().put("com.a.one").put(5).put("x; reboot").put(" com.b.two ").put("com.a.one")
        assertEquals(listOf("com.a.one", "com.b.two"), Backup.wanted(l))
        assertEquals(emptyList<String>(), Backup.wanted(null))
        val many = JSONArray().also { a -> for (i in 0 until 600) a.put("com.app.n$i") }
        assertEquals(Backup.MAX_APPS, Backup.wanted(many).size)
    }

    @Test fun planSplitsWhatToDo() {
        val p = Backup.plan(listOf("com.a.on", "com.a.off", "com.a.gone"), have = setOf("com.a.on", "com.a.off"), off = setOf("com.a.off"))
        assertEquals(listOf("com.a.on"), p.todo)
        assertEquals(listOf("com.a.off"), p.already)
        assertEquals(listOf("com.a.gone"), p.missing)
        val r = Backup.result(p, 1, listOf("k.k"), emptyList())
        assertEquals(1, r.getInt("done")); assertEquals(1, r.getJSONArray("missing").length()); assertEquals(1, r.getJSONArray("kept").length())
    }
}
