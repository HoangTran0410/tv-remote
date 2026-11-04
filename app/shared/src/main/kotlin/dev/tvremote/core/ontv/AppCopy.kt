package dev.tvremote.core.ontv

import com.android.apksig.ApkSigner
import dev.tvremote.core.SelfSigned
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * "Install as a separate app": an APK whose package is built into the TV (a system app cannot be replaced or removed) is
 * rewritten as `<package>.copy` ([Axml.clone]) and signed again with a key of ours (Google's apksig, v1 + v2), then installed
 * beside the built-in one. Why `.copy` and one key per client: dev/decisions/apps.md "The apps leaning on each other".
 */
object AppCopy {
    const val SUFFIX = ".copy"
    /** The message codes the pages turn into words (core.js friendly, Errors.kt). */
    const val SPLIT = "cannot copy: split"
    const val ODD = "cannot copy: manifest"

    /** One key per client, made once and kept beside the ADB keys, so a later copy of the same app is an update of the first. Never a release key of this repo. */
    @Synchronized
    fun signer(dir: File?): Pair<PrivateKey, X509Certificate> {
        val kf = File(dir ?: File(System.getProperty("java.io.tmpdir"), "tvr-appcopy"), "appcopy.key").also { it.parentFile?.mkdirs() }
        val cf = File(kf.parentFile, "appcopy.crt")
        val fac = CertificateFactory.getInstance("X.509")
        if (kf.isFile && cf.isFile) try {
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes())) to
                cf.inputStream().use { fac.generateCertificate(it) as X509Certificate }
        } catch (ignored: Exception) { }                              // unreadable: a new one (the copies made before cannot then be updated, only replaced)
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = SelfSigned.certificate(kp.public.encoded, kp.private, "TV Remote app copy")
        kf.writeBytes(kp.private.encoded); cf.writeBytes(cert)
        kf.setReadable(false, false); kf.setReadable(true, true)
        return kp.private to cf.inputStream().use { fac.generateCertificate(it) as X509Certificate }
    }

    /** Writes [out]: [input] with the new package name, signed. Returns the new package. [check] throws when the person pressed Stop. */
    @Throws(IOException::class)
    fun make(input: File, out: File, dir: File?, check: () -> Unit = {}): String {
        val zip = try { ZipFile(input) } catch (e: Exception) { throw IOException("not an APK file") }
        zip.use { z ->
            val manifest = z.getEntry("AndroidManifest.xml")
            if (manifest == null) throw IOException(SPLIT)            // an .apks / .xapk: zips of APKs
            val r = try { Axml.clone(z.getInputStream(manifest).use { it.readBytes() }, SUFFIX) }
                    catch (e: Axml.Unusual) { throw IOException("$ODD: ${e.message}") }
            val unsigned = File(out.path + ".unsigned")
            try {
                CountingOut(unsigned.outputStream().buffered()).use { co ->
                    val zo = ZipOutputStream(co)
                    for (e in z.entries()) {
                        check()
                        val n = e.name
                        if (n.startsWith("META-INF/") && Regex(".*\\.(SF|RSA|DSA|EC)|.*/MANIFEST\\.MF", RegexOption.IGNORE_CASE).matches(n)) continue   // the old signature
                        val ne = ZipEntry(n)
                        val data: ByteArray? = if (n == "AndroidManifest.xml") r.bytes else null
                        if (e.method == ZipEntry.STORED) {
                            val bytes = data ?: z.getInputStream(e).use { it.readBytes() }
                            ne.method = ZipEntry.STORED; ne.size = bytes.size.toLong(); ne.compressedSize = bytes.size.toLong()
                            ne.crc = CRC32().also { it.update(bytes) }.value
                            val align = if (n.endsWith(".so")) 4096 else 4   // resources.arsc and libraries kept in the file are read in place
                            val pad = ((align - (co.count + 30 + n.toByteArray().size + 4) % align) % align).toInt()
                            ne.extra = ByteArray(4 + pad).also { it[0] = 0x35; it[1] = 0xD9.toByte(); it[2] = (pad and 0xff).toByte(); it[3] = (pad shr 8).toByte() }
                            zo.putNextEntry(ne); zo.write(bytes)
                        } else {
                            ne.method = ZipEntry.DEFLATED
                            zo.putNextEntry(ne)
                            if (data != null) zo.write(data) else if (!e.isDirectory) z.getInputStream(e).use { it.copyTo(zo) }
                        }
                        zo.closeEntry()
                    }
                    zo.finish()
                }
                check()
                val (key, cert) = signer(dir)
                val cfg = ApkSigner.SignerConfig.Builder("tvr", key, listOf(cert)).build()
                ApkSigner.Builder(listOf(cfg)).setInputApk(unsigned).setOutputApk(out)
                    .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false).setMinSdkVersion(21).build().sign()
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("$ODD: ${e.message}")
            } finally { unsigned.delete() }
            if (!dev.tvremote.core.adb.Pkg.RE.matches(r.newPkg)) throw IOException("not an APK file")   // it goes into monkey -p / pidof
            return r.newPkg
        }
    }

    private class CountingOut(o: OutputStream) : FilterOutputStream(o) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }
}
