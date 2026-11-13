package dev.tvremote.core

import dev.tvremote.core.remote.Atv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

/**
 * The Android TV Remote pairing's proof (Atv.secret): wrong here and no TV pairs any more, with only "pairing refused" to go on.
 * The expected hash was worked out apart from the app (Python's hashlib over the numbers' bytes), not by this code.
 */
class AtvPairTest {
    private fun key(hex: String) = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(hex, 16), BigInteger.valueOf(65537))) as RSAPublicKey
    private val mine = key("C0FFEE" + "11".repeat(125))                // top bit set: Java adds a sign byte the TV does not hash
    private val theirs = key("7EADBEEF" + "22".repeat(124))
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun theCodeOnTheTvGivesTheKnownProof() {
        assertEquals("021d722f1825396a0a6bd5bdb0d11fdca70ea911934d4eb170050af0ca488a27", hex(Atv.secret(mine, theirs, "021A2B")!!))
    }

    @Test fun aMistypedCodeIsCaughtHere() {
        assertNull(Atv.secret(mine, theirs, "5B1A2B"))                // its first two characters do not match the hash
    }

    @Test fun ourKeyComesFirst() {
        assertNull(Atv.secret(theirs, mine, "021A2B"))
    }

    @Test fun onlyACertificateAlertMeansNotPaired() {
        assertTrue(Atv.certRejected(javax.net.ssl.SSLHandshakeException("Received fatal alert: certificate_unknown")))
        assertFalse(Atv.certRejected(javax.net.ssl.SSLException("Connection reset")))
        assertFalse(Atv.certRejected(javax.net.ssl.SSLException("Remote host terminated the handshake")))
    }

    @Test fun keysThatWaitedTooLongAreDropped() {
        assertTrue(Atv.isStale(0, 2_000_000_000L, Atv.KEY_STALE_MS))
        assertFalse(Atv.isStale(0, 1_000_000_000L, Atv.KEY_STALE_MS))
        assertFalse(Atv.isStale(0, 60_000_000_000L, 0))               // 0: never (voice, text, links)
    }
}
