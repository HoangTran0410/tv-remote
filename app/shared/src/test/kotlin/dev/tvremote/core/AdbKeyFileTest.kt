package dev.tvremote.core

import dev.tvremote.core.adb.Adb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The app's ADB key on disk: a key file cut short must not stop every start (census aa5953e5, a Fire TV). */
class AdbKeyFileTest {
    private fun dir(): File = Files.createTempDirectory("adbkey").toFile()

    @Test fun keptKeyIsReadBack() {
        val d = dir()
        assertEquals(Adb(d).fingerprint(), Adb(d).fingerprint())
    }

    @Test fun cutShortPublicKeyMakesANewOne() {
        val d = dir()
        val old = Adb(d).fingerprint()
        val pub = File(d, "adbkey.x509")
        pub.writeBytes(pub.readBytes().copyOf(40))                   // what a power cut mid-write leaves
        val now = Adb(d).fingerprint()                                // threw InvalidKeySpecException before
        assertNotEquals(old, now)
        assertEquals(now, Adb(d).fingerprint())                       // and the new one is kept whole
    }

    @Test fun mismatchedPairMakesANewOne() {
        val d = dir(); val other = dir()
        val old = Adb(d).fingerprint(); Adb(other)
        File(other, "adbkey.pk8").copyTo(File(d, "adbkey.pk8"), overwrite = true)   // a new private key, the old public one: both parse
        assertNotEquals(old, Adb(d).fingerprint())
    }

    @Test fun garbagePrivateKeyMakesANewOne() {
        val d = dir()
        Adb(d)
        File(d, "adbkey.pk8").writeBytes(byteArrayOf(1, 2, 3))
        Adb(d).fingerprint()
    }
}
