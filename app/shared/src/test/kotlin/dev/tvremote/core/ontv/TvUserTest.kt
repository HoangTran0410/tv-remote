package dev.tvremote.core.ontv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The command text for a Google TV with a profile switched in: every on/off write and read names the user in front. */
class TvUserTest {
    @Test fun writesAndReadsNameTheSameUser() {
        for (c in listOf(TvUser.disable("a.b"), TvUser.enable("a.b"), TvUser.list("-d"), TvUser.list("-3 -e"), TvUser.list()))
            assertTrue(c, c.contains("--user " + TvUser.ID))
        assertEquals("pm disable-user --user ${TvUser.ID} a.b", TvUser.disable("a.b"))
        assertEquals("pm list packages -d --user ${TvUser.ID}", TvUser.list("-d"))
        assertEquals("pm list packages --user ${TvUser.ID}", TvUser.list())
    }

    @Test fun noFixedUserZero() {
        assertFalse(TvUser.disable("a.b").contains("--user 0"))
        assertFalse(TvUser.enable("a.b").contains("--user 0"))
    }

    @Test fun theExpressionRunsInAShell() {
        // a real sh with `am` faked: a number passes, nothing or an error text (Android before 8) becomes 0
        fun run(amOut: String): String {
            val p = ProcessBuilder("sh", "-c", "am() { echo '$amOut'; }; echo " + TvUser.ID).start()
            return p.inputStream.bufferedReader().readText().trim()
        }
        assertEquals("10", run("10"))
        assertEquals("0", run("0"))
        assertEquals("0", run(""))
        assertEquals("0", run("Error: unknown command"))
    }
}
