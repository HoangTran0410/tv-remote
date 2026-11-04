package dev.tvremote.core

import dev.tvremote.core.adb.Adb
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/** An Adb whose connect never got through (failed, or still dialing): asking it is an IOException, never a crash (census b4de9bca). */
class AdbNotConnectedTest {
    @Test fun aliveBeforeConnectIsFalse() {
        val a = Adb(Files.createTempDirectory("adbnc").toFile())
        assertFalse(a.alive(200))
    }

    @Test(expected = IOException::class) fun shellBeforeConnectThrowsIOException() {
        Adb(Files.createTempDirectory("adbnc").toFile()).shell("echo", 200)
    }
}
