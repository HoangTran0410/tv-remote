package dev.tvremote.core.adb

import dev.tvremote.core.ontv.AppRestore
import dev.tvremote.core.ontv.LauncherSwap
import dev.tvremote.core.ontv.SetupNeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** A pm that could not ask system_server must never read as an answer (weak boxes stall 10-40 s). */
class PkgAskTest {
    private val failures = listOf(
        "cmd: Failure calling service package: Failed transaction (2147483646)",
        "Could not access the Package Manager.  Is the system running?",
        "Error: java.lang.NullPointerException",
        "",
    )

    private fun throwsIO(f: () -> Unit) { try { f(); fail("should throw") } catch (e: IOException) { assertTrue(e.message!!.startsWith("pm: could not ask")) } }

    @Test fun pathFailureIsNotInstalled() {
        for (bad in failures.dropLast(1)) throwsIO { Pkg.installed({ bad }, "a.b") }      // the first one contains "package:" yet is no answer
        assertFalse(Pkg.installed({ "" }, "a.b"))                                         // empty = pm path of a missing app
        assertTrue(Pkg.installed({ "package:/data/app/a/base.apk" }, "a.b"))
    }

    @Test fun listFailureThrows() {
        for (bad in failures) throwsIO { Pkg.list({ bad }) }
        throwsIO { Pkg.list({ "some text without a list" }, "-d", empty = true) }
        assertEquals(setOf("a.b", "c.d"), Pkg.list({ "package:a.b\npackage:c.d\n" }))
        assertEquals(emptySet<String>(), Pkg.list({ "" }, "-d", empty = true))            // nothing disabled is an answer
    }

    @Test fun twiceRetriesOnce() {
        Pkg.pause = { }
        var n = 0
        assertEquals("ok", Pkg.twice { if (n++ == 0) throw IOException("x") else "ok" })
        n = 0
        throwsIO { Pkg.twice<Unit> { n++; throw IOException("pm: could not ask: x") } }
        assertEquals(2, n)
    }

    @Test fun appRestoreEnabledNeedsRealAnswers() {
        assertTrue(AppRestore.enabled({ if (it.startsWith("pm path")) "package:/x/base.apk" else "package:a.b" }, "a.b"))
        assertFalse(AppRestore.enabled({ if (it.startsWith("pm path")) "package:/x/base.apk" else "" }, "a.b"))
        throwsIO { AppRestore.enabled({ failures[0] }, "a.b") }
    }

    @Test fun setupVersionNeverMeansInstallFromAFailedRead() {
        Pkg.pause = { }
        // not installed at all: pm path is empty -> ""
        assertEquals("", SetupNeed.have { "" })
        // installed (pm path answers) but dumpsys stalls to nothing, twice -> could not ask, no "" that would say Install
        throwsOther { SetupNeed.have { if (it.startsWith("pm path")) "package:/x/base.apk" else "" } }
        // pm itself is down
        throwsIO { SetupNeed.have { failures[0] } }
        // a stall that passes by the second try
        var n = 0
        assertEquals("1.2.3", SetupNeed.have { if (it.startsWith("pm path")) "package:/x" else if (n++ == 0) "" else "versionName=1.2.3" })
    }

    private fun throwsOther(f: () -> Unit) { try { f(); fail("should throw") } catch (e: IOException) { } }

    @Test fun rollbackWithoutRecordDoesNotActOnAFailedRead() {
        Pkg.pause = { }
        val ran = mutableListOf<String>()
        val sw = LauncherSwap({ c -> ran += c; if (c.startsWith("pm list packages")) failures[0] else "" })
        try { sw.rollback("timeout"); fail("should throw") } catch (e: IOException) { assertTrue(e.message!!.startsWith("rollback not done")) }
        assertTrue(ran.none { it.contains("pm enable") || it.contains("set-home-activity") })
        assertEquals(2, ran.count { it.startsWith("pm list packages -d") })              // the one retry
    }
}
