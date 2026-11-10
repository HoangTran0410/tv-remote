package dev.tvremote.setup.health

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of support's reply thread: what is kept, which reply is new, what the card says. */
class SupportThreadTest {
    private fun m(id: Int, who: String, text: String = "t") = JSONObject().put("id", id).put("who", who).put("text", text).put("at", 1L)

    @Test fun mergeKeepsOldAndAddsOnlyHigherIds() {
        val old = JSONArray().put(m(1, "tv")).put(m(2, "us"))
        val got = SupportThread.merge(old, JSONArray().put(m(2, "us", "again")).put(m(3, "us", "<b>x</b>")))
        assertEquals(3, got.length()); assertEquals("<b>x</b>", got.getJSONObject(2).getString("text"))   // text stays text
        assertEquals(2, SupportThread.merge(old, null).length())
    }

    @Test fun newestUsAboveSeenOnly() {
        val t = JSONArray().put(m(1, "tv")).put(m(2, "us")).put(m(3, "tv")).put(m(4, "us"))
        assertEquals(4, SupportThread.lastUs(t, 2)!!.getInt("id"))
        assertNull(SupportThread.lastUs(t, 4))                      // already carded: no second card after a restart
    }

    @Test fun cardTextIsShort() {
        assertEquals("short", SupportThread.cut(" short "))
        val c = SupportThread.cut("word ".repeat(100))
        assertTrue(c.length <= 112 && c.endsWith("…"))
    }
}
