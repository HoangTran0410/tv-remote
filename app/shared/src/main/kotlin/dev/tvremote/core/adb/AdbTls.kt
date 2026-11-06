package dev.tvremote.core.adb
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import dev.tvremote.core.SelfSigned
import dev.tvremote.core.WireLog

/**
 * What TLS needs that Java does not give: TLS 1.3 on every system, and the "exported keying material" adb's pairing mixes
 * into its password. Conscrypt does both on Android and the computer; each of those apps carries it
 * (`dev.tvremote.ConscryptBackend`) and the shared core finds it by name, so TV Setup (which never pairs) does not.
 */
interface TlsBackend {
    fun context(keys: Array<KeyManager>, trust: Array<TrustManager>): SSLContext
    fun exportKeyingMaterial(s: SSLSocket, label: String, length: Int): ByteArray
}

/**
 * Android 11's Wireless debugging: the TV shows a pairing port and a 6-digit code ("Pair device with pairing code"), and a
 * separate connect port that changes every time. Pairing is adb's own protocol (TLS 1.3, then SPAKE2 over the code, then both
 * sides swap the public key encrypted with AES-128-GCM); once paired, [Adb.connectTls] gets in with no "Allow USB debugging?".
 * The connect port is found by mDNS ([Mdns]) and remembered per TV next to the key (adbtls.json); when it stops working it is looked for again.
 */
object AdbTls {
    private val CLIENT_NAME = "adb pair client\u0000".toByteArray()
    private val SERVER_NAME = "adb pair server\u0000".toByteArray()
    private const val LABEL = "adb-label\u0000"
    private const val HKDF_INFO = "adb pairing_auth aes-128-gcm key"
    private const val SPAKE_MSG = 0
    private const val PEER_INFO = 1
    private const val PEER_SIZE = 8192
    private const val MAX_PAYLOAD = 16384

    private val backend: TlsBackend? by lazy {
        try { Class.forName("dev.tvremote.ConscryptBackend").getDeclaredConstructor().newInstance() as TlsBackend } catch (e: Throwable) { null }
    }

    /** True where this app can pair (Android 10+ and the computer; an older phone has no TLS 1.3). */
    fun available(): Boolean = backend != null && try { backend!!.context(emptyArray(), emptyArray()); true } catch (e: Throwable) { false }

    // ---------- what is remembered ----------

    private fun file(dir: File) = File(dir, "adbtls.json")

    @Synchronized
    fun saved(dir: File, host: String): Int = try { JSONObject(file(dir).readText()).optInt(host, 0) } catch (e: Exception) { 0 }

    @Synchronized
    fun save(dir: File, host: String, port: Int) {
        val o = try { JSONObject(file(dir).readText()) } catch (e: Exception) { JSONObject() }
        if (port > 0) o.put(host, port) else o.remove(host)
        file(dir).writeText(o.toString())
    }

    /** Port 5555 answers (so a TV whose Wireless debugging is off still connects the old way). */
    fun plainOpen(host: String): Boolean = AdbPort.find(host) > 0

    // ---------- finding the ports ----------

    /**
     * Whether a service heard at [ip] is [host]. TV Setup dials its own TV as 127.0.0.1, but the TV announces Wireless debugging
     * under its network address: on a TV with no port 5555 (a Chromecast with Google TV) that is the only way TV Setup gets ADB.
     */
    fun same(host: String, ip: String): Boolean = ip == host || (isLoopback(host) && ip in ownIps())

    private fun isLoopback(host: String) = host == "127.0.0.1" || host == "localhost" || host == "::1"

    private fun ownIps(): Set<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    } catch (e: Exception) { emptySet() }

    /** The connect port [host] announces, 0 when none was heard. */
    fun discoverConnect(host: String, ms: Int = 3000, bind: (java.net.DatagramSocket) -> Unit = {}): Int =
        Mdns.find(Mdns.CONNECT, ms, bind).firstOrNull { same(host, it.ip) }?.port ?: 0

    /** What a TV announces for Wireless debugging: the connect port and the pairing port (0: not heard). */
    class Wireless(val connect: Int, val pairing: Int) {
        val any get() = connect > 0 || pairing > 0
    }

    /**
     * Whether [host] has Wireless debugging on, by mDNS: Android 11+ TVs that have no port 5555 (cleanyourtv #101, the Sony in #24)
     * are only reachable this way. The ports change every time the switch is flipped, so this is asked again on each connect.
     * [find] is the mDNS browser (a test gives its own).
     */
    fun wireless(host: String, ms: Int = 1800, bind: (java.net.DatagramSocket) -> Unit = dev.tvremote.core.Diagnose.bindUdp,
                 find: (String) -> List<Mdns.Service> = { Mdns.find(it, ms, bind) }): Wireless {
        var pairing = 0
        val t = Thread { pairing = find(Mdns.PAIRING).firstOrNull { same(host, it.ip) }?.port ?: 0 }.apply { isDaemon = true; start() }
        val connect = find(Mdns.CONNECT).firstOrNull { same(host, it.ip) }?.port ?: 0
        t.join(ms + 1000L)
        return Wireless(connect, pairing)
    }

    /** Pairing and connect ports heard on the network, for the page to prefill. */
    fun discover(ms: Int = 2500, bind: (java.net.DatagramSocket) -> Unit = {}): Pair<List<Mdns.Service>, List<Mdns.Service>> {
        var pairing = emptyList<Mdns.Service>()
        val t = Thread { pairing = Mdns.find(Mdns.PAIRING, ms, bind) }.apply { isDaemon = true; start() }
        val connect = Mdns.find(Mdns.CONNECT, ms, bind)
        t.join(ms + 1000L)
        return pairing to connect
    }

    // ---------- TLS with our key ----------

    private class OurKeys(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("adb")
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "adb"
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
    }

    /** The TV's certificate is made up on the spot: what vouches for it is the pairing (or the key it already trusts), not a chain. */
    private class TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun context(adb: Adb): SSLContext {
        val be = backend ?: throw IOException("adb tls: this app cannot pair (no TLS 1.3)")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(SelfSigned.certificate(adb.key.public.encoded, adb.key.private, "adb"))) as X509Certificate
        return be.context(arrayOf(OurKeys(adb.key.private, arrayOf(cert))), arrayOf(TrustAll()))
    }

    /** TLS 1.3 over [plain], which has been talking ADB until the device asked for STLS. */
    internal fun handshake(adb: Adb, plain: Socket, host: String, port: Int): SSLSocket {
        val ssl = context(adb).socketFactory.createSocket(plain, host, port, true) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        ssl.soTimeout = 15000
        ssl.startHandshake()
        return ssl
    }

    // ---------- the pairing itself ----------

    fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 32)
        val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }.doFinal(ikm)
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }.doFinal(info + byteArrayOf(1)).copyOf(length)
    }

    /** adb's pairing packet: version 1, the type, and the length of what follows (4 bytes, big-endian). */
    fun packet(type: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(6 + payload.size).order(ByteOrder.BIG_ENDIAN).put(1).put(type.toByte()).putInt(payload.size).put(payload).array()

    private fun readPacket(inp: DataInputStream, expect: Int): ByteArray {
        val head = ByteArray(6)
        inp.readFully(head)
        val b = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        val version = b.get().toInt()
        val type = b.get().toInt()
        val len = b.int
        if (version != 1 || type != expect || len < 0 || len > MAX_PAYLOAD) throw IOException("adb pair: bad packet")
        return ByteArray(len).also { inp.readFully(it) }
    }

    /** AES-128-GCM with adb's nonce: a counter per direction, from 0, in the first 8 bytes (little-endian). */
    private fun gcm(mode: Int, key: ByteArray, counter: Long, data: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }.doFinal(data)
    }

    /**
     * Pairs this app's key with the device that shows [code] (6 digits) and listens for pairing on [host]:[port]. Throws an
     * IOException saying what went wrong ("adb pair: wrong code" when the device did not accept the code).
     */
    @Throws(Exception::class)
    fun pair(adb: Adb, host: String, port: Int, code: String) {
        val be = backend ?: throw IOException("adb tls: this app cannot pair (no TLS 1.3)")
        val plain = dev.tvremote.core.Lan.connect(host, port, 6000)
        try {
            plain.tcpNoDelay = true
            plain.soTimeout = 15000
            val ssl = context(adb).socketFactory.createSocket(plain, host, port, true) as SSLSocket
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            try { ssl.startHandshake() } catch (e: Exception) { throw IOException("adb pair: not a pairing port ($e)") }
            val ekm = be.exportKeyingMaterial(ssl, LABEL, 64)
            val out = ssl.outputStream
            val inp = DataInputStream(ssl.inputStream)

            val spake = Spake2(true, CLIENT_NAME, SERVER_NAME)
            out.write(packet(SPAKE_MSG, spake.generate(code.toByteArray() + ekm)))
            out.flush()
            val key = hkdf(spake.process(readPacket(inp, SPAKE_MSG)), (HKDF_INFO).toByteArray(), 16)

            val info = ByteArray(PEER_SIZE)                          // type 0: an RSA public key in adb's format, as text
            val text = (adb.publicKey() + "\u0000").toByteArray()
            System.arraycopy(text, 0, info, 1, text.size)
            out.write(packet(PEER_INFO, gcm(Cipher.ENCRYPT_MODE, key, 0, info)))
            out.flush()
            try {
                gcm(Cipher.DECRYPT_MODE, key, 0, readPacket(inp, PEER_INFO))   // the device's own: proof that it took the same code
            } catch (e: java.net.SocketTimeoutException) {
                throw e
            } catch (e: IOException) {
                throw IOException("adb pair: wrong code")             // the device hangs up on a code that does not fit
            } catch (e: java.security.GeneralSecurityException) {
                throw IOException("adb pair: wrong code")
            }
            WireLog.all.add("·", "adb pair $host:$port paired")
        } finally {
            try { plain.close() } catch (ignored: IOException) { }
        }
    }

    /** What a person typed as the code: six digits, or null. */
    fun cleanCode(s: String?): String? = s?.filter { it.isDigit() }?.takeIf { it.length == 6 }
}
