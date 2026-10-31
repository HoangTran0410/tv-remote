package dev.tvremote.setup.link

import android.content.Context
import dev.tvremote.core.SelfSigned
import java.io.File
import java.security.KeyPairGenerator
import javax.net.ssl.SSLContext

/**
 * The key and self-signed certificate the web remote's HTTPS port (8766) answers with, made once and kept in TV Setup's
 * private files. Nothing vouches for it: the Phone and Computer apps get its fingerprint over ADB and pin it, and a
 * browser's person can compare the short fingerprint shown on the TV with the one the browser shows.
 */
object Tls {
    private fun files(c: Context) = File(c.filesDir, "web.pk8") to File(c.filesDir, "web.crt")

    @Synchronized
    private fun material(c: Context): Pair<ByteArray, ByteArray> {
        val (k, crt) = files(c)
        if (!k.exists() || !crt.exists()) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            crt.writeBytes(SelfSigned.certificate(pair.public.encoded, pair.private, "TV Setup"))
            k.writeBytes(pair.private.encoded)
        }
        return k.readBytes() to crt.readBytes()
    }

    fun context(c: Context): SSLContext = material(c).let { SelfSigned.serverContext(it.first, it.second) }

    /** SHA-256 of the certificate in 64 hex digits. */
    fun fingerprint(c: Context): String = SelfSigned.fingerprint(material(c).second)

    /** The same, or null while the key is still being made (the TV's screen must not wait for it). */
    fun shortFingerprintIfReady(c: Context): String? = if (files(c).let { it.first.exists() && it.second.exists() }) shortFingerprint(c) else null

    /** "AB12 CD34 EF56 7890": for the TV's screen. */
    fun shortFingerprint(c: Context): String = SelfSigned.short(fingerprint(c))
}
