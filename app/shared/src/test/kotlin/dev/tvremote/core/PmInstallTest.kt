package dev.tvremote.core

import dev.tvremote.core.adb.PmInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PackageInstaller session that stands in for `pm install` on firmware that refuses it (cleanyourtv #37 / #98), with a fake shell. */
class PmInstallTest {
    private val REFUSAL = "Get install_switch_flag: 0\ninstall apk has be disabled from pm by system default!"

    /** Answers like pm does and keeps every command, so the order and the "one pm per shell call" rule can be checked. */
    private class Shell(val create: String = "Success: created install session [1234]", val write: String = "Success: streamed 2048 bytes",
                        val commit: String = "Success") : (String) -> String {
        val calls = ArrayList<String>()
        override fun invoke(cmd: String): String {
            calls.add(cmd)
            return when {
                cmd.startsWith("pm install-create") -> create
                cmd.contains("pm install-write") -> write
                cmd.startsWith("pm install-commit") -> commit
                else -> ""
            }
        }
    }

    @Test fun onlyTheFirmwaresRefusalStartsASession() {
        assertTrue(PmInstall.refused(REFUSAL))
        assertFalse(PmInstall.refused("Success"))
        assertFalse(PmInstall.refused("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))      // a session would meet the same
        assertFalse(PmInstall.refused(""))
        val sh = Shell()
        assertEquals("Success", PmInstall.fallback("Success", sh, "'/x.apk'", 10))
        assertEquals("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", PmInstall.fallback("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", sh, "'/x.apk'", 10))
        assertTrue(sh.calls.isEmpty())
    }

    @Test fun createWriteCommitOnePmPerCall() {
        val sh = Shell()
        val out = PmInstall.fallback(REFUSAL, sh, "'/data/local/tmp/tvr-setup.apk'", 2048)
        assertEquals("Success", out)
        assertEquals(listOf(
            "pm install-create -r -S 2048 2>&1",
            "cat '/data/local/tmp/tvr-setup.apk' | pm install-write -S 2048 1234 base.apk - 2>&1",
            "pm install-commit 1234 2>&1"), sh.calls)
        for (c in sh.calls) assertEquals(c, 1, Regex("pm install-").findAll(c).count())      // never two pm in one shell call
    }

    @Test fun aFailedStepAbandonsTheSessionAndSaysBothFailed() {
        val sh = Shell(write = "Failure [INSTALL_FAILED_INVALID_APK]")
        val out = PmInstall.fallback(REFUSAL, sh, "'/x.apk'", 5)
        assertTrue(out, out.startsWith(PmInstall.BLOCKED))
        assertTrue(out, out.contains("INSTALL_FAILED_INVALID_APK"))
        assertEquals("pm install-abandon 1234 2>&1", sh.calls.last())                      // no half-written session left on the TV
        assertTrue(sh.calls.none { it.startsWith("pm install-commit") })

        val none = Shell(create = "Error: can't create session")
        val out2 = PmInstall.fallback(REFUSAL, none, "'/x.apk'", 5)
        assertTrue(out2, out2.startsWith(PmInstall.BLOCKED) && out2.contains("can't create session"))
        assertEquals(1, none.calls.size)                                                   // no session id: nothing to write or abandon

        val bad = Shell(commit = "Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: disable install launcher app not signature by tcl]")
        val out3 = PmInstall.fallback(REFUSAL, bad, "'/x.apk'", 5)
        assertTrue(out3, out3.startsWith(PmInstall.BLOCKED) && out3.contains("signature by tcl"))
    }

    @Test fun theErrorHasWordsInBothLanguages() {
        val e = java.io.IOException(PmInstall.BLOCKED + ": Failure")
        val vi = Errors.say(e, "vi")
        val en = Errors.say(e, "en")
        assertTrue(en, en.contains("firmware") && en != vi)
        assertTrue(Errors.say(java.io.IOException(REFUSAL), "en").contains("firmware"))   // pm's own refusal, were it ever to reach a person
    }

    /** An install that printed nothing (ADB dropped under it) names that, not an empty "could not install: " (support 12596839). */
    @Test fun noAnswerIsWorded() {
        assertEquals(dev.tvremote.core.adb.PmInstall.NO_ANSWER, dev.tvremote.core.adb.PmInstall.why(""))
        assertEquals(dev.tvremote.core.adb.PmInstall.NO_ANSWER, dev.tvremote.core.adb.PmInstall.why("\n  \n"))
        assertEquals("Failure [INSTALL_FAILED_OLDER_SDK]", dev.tvremote.core.adb.PmInstall.why("Performing Streamed Install\nFailure [INSTALL_FAILED_OLDER_SDK]\n"))
        val said = Errors.say(java.io.IOException("launcher:install:" + dev.tvremote.core.adb.PmInstall.why("")), "en")
        assertTrue(said, said.contains("stopped answering"))
    }
}
