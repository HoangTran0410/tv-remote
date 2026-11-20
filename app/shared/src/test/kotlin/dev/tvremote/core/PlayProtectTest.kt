package dev.tvremote.core

import dev.tvremote.core.ontv.PlayProtect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class PlayProtectTest {
    private val refused = "Failure [INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:///data/app/vmdl48284915.tmp]"

    @Test fun aFileRefusedOnceIsNotTriedAgainButTheNextBuildIs() {
        val dir = Files.createTempDirectory("pp").toFile()
        PlayProtect.check(dir, "aaa")                                 // nothing kept: goes on
        PlayProtect.check(dir, "aaa", "Success")
        assertFalse(PlayProtect.blocked(dir, "aaa"))
        PlayProtect.check(dir, "aaa", refused)
        PlayProtect.check(dir, "aaa", refused)                        // kept once
        assertEquals(1, File(dir, "play-protect-blocked.txt").readLines().size)
        try { PlayProtect.check(dir, "aaa"); fail() } catch (e: IOException) { assertTrue(e.message!!.contains(PlayProtect.REFUSED)) }
        PlayProtect.check(dir, "bbb")                                 // the next release's file
        PlayProtect.check(dir, "bbb", "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]")
        assertFalse(PlayProtect.blocked(dir, "bbb"))                  // only Play Protect's refusal is kept
    }

    @Test fun noFolderKeepsNothing() {
        PlayProtect.check(null, "aaa", refused)
        PlayProtect.check(null, "aaa")
        assertFalse(PlayProtect.blocked(null, "aaa"))
    }

    @Test fun playProtectsQuestionIsToldFromAnyOtherWindow() {
        assertTrue(PlayProtect.asking("  mCurrentFocus=Window{1a2b u0 com.android.vending/com.google.android.finsky.verifier.impl.v2.PlayProtectDialogsActivity}"))
        assertTrue(PlayProtect.asking("mCurrentFocus=Window{1 u0 com.google.android.gms/com.google.android.gms.security.verifier.ApkUploadActivity}"))
        assertFalse(PlayProtect.asking("mCurrentFocus=Window{1 u0 com.android.vending/com.google.android.finsky.tvmainactivity.TvMainActivity}"))
        assertFalse(PlayProtect.asking("mCurrentFocus=Window{1 u0 dev.tvremote.home/dev.tvremote.home.HomeActivity}"))
        assertFalse(PlayProtect.asking(""))
    }

    @Test fun theWatchSaysItOnceAndAsksNoMoreAfter() {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val said = java.util.concurrent.atomic.AtomicInteger()
        val sh = { c: String -> assertEquals(PlayProtect.FOCUS, c); asked.incrementAndGet(); "mCurrentFocus=Window{1 u0 com.android.vending/x.PlayProtectDialogsActivity}" }
        PlayProtect.watch(sh, { said.incrementAndGet() }, first = 10, every = 10) { Thread.sleep(300) }
        assertEquals(1, said.get())
        assertEquals(1, asked.get())
    }

    @Test fun anInstallThatEndsFirstIsNeverAsked() {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val r = PlayProtect.watch({ asked.incrementAndGet(); "" }, { fail() }, first = 5000) { "Success" }
        assertEquals("Success", r)
        Thread.sleep(50)
        assertEquals(0, asked.get())
    }

    @Test fun theWatchStopsWhenTheInstallThrowsAndWhenTheShellFails() {
        val asked = java.util.concurrent.atomic.AtomicInteger()
        try { PlayProtect.watch({ asked.incrementAndGet(); "" }, { fail() }, first = 5, every = 5) { Thread.sleep(40); throw IOException("boom") }; fail() }
        catch (e: IOException) { assertEquals("boom", e.message) }
        val n = asked.get()
        Thread.sleep(60)
        assertEquals(n, asked.get())                                  // no ask after the block ended
        val bad = java.util.concurrent.atomic.AtomicInteger()
        PlayProtect.watch({ bad.incrementAndGet(); throw IOException("no shell") }, { fail() }, first = 1, every = 1) { Thread.sleep(150) }
        assertEquals(3, bad.get())                                    // gives up after 3 in a row; the install went on
    }
}
