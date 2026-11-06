package dev.tvremote.core

import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.adb.NotSent
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRetryTest {
    private val lost = IOException("adb connection lost")

    @Test fun neverSentRunsAgainForAnyCommand() {
        assertTrue(AdbLink.retryable(NotSent("x"), true, "pm install -r /sdcard/a.apk"))
        assertTrue(AdbLink.retryable(NotSent("x"), false, "input keyevent 26"))
    }

    @Test fun diedMidAnswerOnlyReadsRunAgain() {
        assertTrue(AdbLink.retryable(lost, true, "dumpsys battery | grep level"))
        assertTrue(AdbLink.retryable(lost, true, "pm list packages -3 2>/dev/null"))
        assertTrue(AdbLink.retryable(lost, true, "settings get global device_name"))
        assertFalse(AdbLink.retryable(lost, true, "input keyevent 26"))
        assertFalse(AdbLink.retryable(lost, true, "am start -n a/b"))
        assertFalse(AdbLink.retryable(lost, true, "pm disable-user x"))
        assertFalse(AdbLink.retryable(lost, true, "pm install -r a.apk"))
        assertFalse(AdbLink.retryable(lost, true, "cat a > b"))
        assertFalse(AdbLink.retryable(lost, true, "ls; reboot"))
        assertFalse(AdbLink.retryable(lost, true, "ls \$(reboot)"))
        assertFalse(AdbLink.retryable(lost, true, "getprop | sh"))
    }

    @Test fun aLiveLinkOrAskingTvIsNotRetried() {
        assertFalse(AdbLink.retryable(lost, false, "getprop"))
        assertFalse(AdbLink.retryable(IOException(AdbLink.ASKING), true, "getprop"))
    }
}
