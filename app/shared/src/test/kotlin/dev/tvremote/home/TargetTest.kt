package dev.tvremote.home

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** One string says what to open: an app, a shortcut or a live widget; js/target.js must agree (tests/target.test.js reads the same table). */
class TargetTest {
    /** The page's Target (js/target.js) is held to the same table: tests/target_cases.json. */
    @Test fun kinds() {
        val cases = JSONObject(java.io.File("tests/target_cases.json").readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) cases.getJSONObject(i).let { c ->
            val t = c.getString("t")
            assertEquals(t, c.getString("kind"), Target.kind(t)?.name?.lowercase() ?: "")
            assertEquals(t, c.getString("pkg"), Target.pkg(t))
            assertEquals(t, c.getString("id"), Target.id(t))
        }
    }

    @Test fun partsAndCleaning() {
        assertEquals("dev.tvremote.channels", Target.pkg("pin:dev.tvremote.channels/keep"))
        assertEquals("keep", Target.id("pin:dev.tvremote.channels/keep"))
        assertEquals("dev.tvremote.setup", Target.pkg("tile:dev.tvremote.setup/ram"))
        assertEquals("a.b", Target.pkg("a.b")); assertEquals("", Target.id("a.b"))
        assertEquals("", Target.clean("not a target")); assertEquals("a.b", Target.clean("a.b"))
    }

    /** An older config holds plain package names in keyapps / autoapp; they read as apps, unchanged. */
    @Test fun oldConfigsStillWork() {
        val c = HomeConfig.clean(JSONObject().put("keyon", true).put("autoon", true).put("autoapp", "com.netflix.ninja")
            .put("keyapps", JSONObject().put("183", "com.netflix.ninja").put("184", "pin:dev.tvremote.channels/keep").put("185", "tile:dev.tvremote.setup/ram").put("186", "pin:bad")))
        assertEquals("com.netflix.ninja", c.getString("autoapp"))
        assertEquals(mapOf(183 to "com.netflix.ninja", 184 to "pin:dev.tvremote.channels/keep", 185 to "tile:dev.tvremote.setup/ram"), HomeConfig.keyApps(c.getJSONObject("keyapps")))
        assertEquals("pin:dev.tvremote.games/library", HomeConfig.clean(JSONObject().put("autoon", true).put("autoapp", "pin:dev.tvremote.games/library")).getString("autoapp"))
    }

    @Test fun gridHoldsShortcutsAndTiles() {
        val t = HomeConfig.clean(JSONObject().put("tiles", JSONArray(listOf("tile:dev.tvremote.setup/ram", "pin:dev.tvremote.channels/keep", "com.plain.app", "pin:bad", "pin:dev.tvremote.channels/keep"))))
        assertEquals(listOf("tile:dev.tvremote.setup/ram", "pin:dev.tvremote.channels/keep"), t.getJSONArray("tiles").let { a -> List(a.length()) { a.getString(it) } })
    }
}
