package dev.tvremote.setup.rules

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Google Authenticator's codes (RFC 6238 over RFC 4226): HMAC-SHA1, 6 digits, a new code every 30 s. Plain Kotlin, so the RFC's own
 * test vectors check it (TotpTest). The TV checks a code against ITS clock corrected by [Clock] (a TV minutes off still works)
 * and accepts one step either side, since a person needs a few seconds to read the phone and type.
 */
internal object Totp {
    const val STEP_S = 30L
    const val DIGITS = 6
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Base32 (RFC 4648) to bytes; spaces, dashes, "=" and case are ignored (Authenticator shows the secret in groups). Null for another letter or nothing. */
    fun base32(s: String): ByteArray? {
        val t = s.uppercase().filter { it != ' ' && it != '-' && it != '=' }
        if (t.isEmpty()) return null
        var buf = 0; var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (ch in t) {
            val v = B32.indexOf(ch)
            if (v < 0) return null
            buf = (buf shl 5) or v; bits += 5
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) }
            buf = buf and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }

    /** The code of one 30 s step, [digits] long. */
    fun code(key: ByteArray, counter: Long, digits: Int = DIGITS): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array())
        val o = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        var m = 1; repeat(digits) { m *= 10 }
        return (bin % m).toString().padStart(digits, '0')
    }

    /** [given] is the code of the step [nowMs] falls in, or one step before or after it. [nowMs] is already corrected for the TV's clock. */
    fun verify(secretB32: String, given: String, nowMs: Long, window: Int = 1): Boolean {
        if (given.length != DIGITS || !given.all { it in '0'..'9' }) return false
        val key = base32(secretB32) ?: return false
        val step = Math.floorDiv(nowMs, STEP_S * 1000)
        var ok = false                                                  // every step is compared, so the time taken does not say which one was close
        for (d in -window..window) if (java.security.MessageDigest.isEqual(code(key, step + d).toByteArray(), given.toByteArray())) ok = true
        return ok
    }

    /** A fresh 20-byte secret is the clients' job; this is the check that what they send is one (16+ base32 letters). */
    fun validSecret(s: String): Boolean = (base32(s)?.size ?: 0) >= 10
}
