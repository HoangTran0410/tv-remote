package dev.tvremote.core.remote
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import dev.tvremote.core.B64

/**
 * A small WebSocket client (RFC 6455), text frames only: what LG's and Samsung's TVs speak for their remotes (Lg, Samsung).
 * Written here rather than pulled in: the core has no HTTP library and runs on Android 5 phones and the computer alike.
 * [secure]: TLS without checking the certificate, as Atv does; the TVs sign their own, and the TV's prompt is what pairs.
 */
class WebSocket private constructor(private val sock: Socket) : Closeable {
    private val input: InputStream = sock.getInputStream().buffered()
    private val output: OutputStream = sock.getOutputStream()
    @Volatile var closed = false
        private set
    /** The close frame's code and reason ("1008 policy"), "" when the TV just dropped the line: the log says why it hung up. */
    @Volatile var closeWhy = ""
        private set

    private fun closeText(b: ByteArray): String =
        if (b.size < 2) "" else (((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)).toString() + " " + String(b, 2, b.size - 2, Charsets.UTF_8)

    /** The next text message; null once the TV has closed the connection. A ping is answered on the way. */
    @Throws(IOException::class)
    fun read(): String? {
        val msg = ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            if (b0 < 0) { closed = true; return null }
            val b1 = input.read()
            if (b1 < 0) { closed = true; return null }
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = ((byte() shl 8) or byte()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or byte().toLong() } }
            if (len < 0 || len > 16L shl 20 || msg.size() + len > 16L shl 20) throw IOException("message too big")   // an app list with icons is far below this; < 0: the top bit of the 8-byte form
            val mask = if (b1 and 0x80 != 0) ByteArray(4) { byte().toByte() } else null
            val body = ByteArray(len.toInt())
            var got = 0
            while (got < body.size) { val n = input.read(body, got, body.size - got); if (n < 0) throw IOException("cut short"); got += n }
            if (mask != null) for (i in body.indices) body[i] = (body[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (b0 and 0x0f) {
                0x8 -> { closed = true; closeWhy = closeText(body); try { frame(0x8, ByteArray(0)) } catch (ignored: IOException) { }; return null }
                0x9 -> frame(0xA, body)
                0xA -> { }
                else -> { msg.write(body); if (b0 and 0x80 != 0) return String(msg.toByteArray(), Charsets.UTF_8) }   // text or its continuation
            }
        }
    }

    private fun byte(): Int { val b = input.read(); if (b < 0) throw IOException("cut short"); return b }

    @Throws(IOException::class)
    fun send(text: String) = frame(0x1, text.toByteArray(Charsets.UTF_8))

    /** Client frames are masked, as the protocol wants (the TVs drop unmasked ones). One writer at a time. */
    @Synchronized private fun frame(op: Int, body: ByteArray) {
        val o = ByteArrayOutputStream(body.size + 14)
        o.write(0x80 or op)
        when {
            body.size < 126 -> o.write(0x80 or body.size)
            body.size < 65536 -> { o.write(0x80 or 126); o.write(body.size shr 8); o.write(body.size and 0xff) }
            else -> { o.write(0x80 or 127); for (i in 7 downTo 0) o.write(((body.size.toLong() shr (8 * i)) and 0xff).toInt()) }
        }
        val mask = ByteArray(4).also { RANDOM.nextBytes(it) }
        o.write(mask)
        for (i in body.indices) o.write(body[i].toInt() xor mask[i and 3].toInt())
        output.write(o.toByteArray())
        output.flush()
    }

    /** Waits for a message for at most [ms] (0: for ever); SocketTimeoutException past that. */
    fun timeout(ms: Int) { sock.soTimeout = ms }

    override fun close() {
        if (!closed) try { frame(0x8, byteArrayOf(0x03, 0xe8.toByte())) } catch (ignored: Exception) { }
        closed = true
        try { sock.close() } catch (ignored: Exception) { }
    }

    companion object {
        private val RANDOM = SecureRandom()

        /** Connects and upgrades to [path] on [host]:[port]. IOException with the TV's status line when it says no. */
        @Throws(IOException::class)
        fun open(host: String, port: Int, path: String, secure: Boolean, timeoutMs: Int = 4000): WebSocket {
            val plain = dev.tvremote.core.Lan.connect(host, port, timeoutMs)
            var s: Socket = plain                                      // the plain one is closed on any failure below, handshake included
            try {
                plain.soTimeout = timeoutMs
                if (secure) {
                    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TRUST_ANY), RANDOM) }
                    s = (ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket).also { it.startHandshake() }
                }
                val nonce = B64.encode(ByteArray(16).also { RANDOM.nextBytes(it) })
                val req = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $nonce\r\nSec-WebSocket-Version: 13\r\nOrigin: http://$host\r\n\r\n"
                s.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                s.getOutputStream().flush()
                val head = StringBuilder()
                val inp = s.getInputStream()
                while (!head.endsWith("\r\n\r\n")) {                   // byte by byte: what follows the headers is the first frame
                    val b = inp.read()
                    if (b < 0) throw IOException("the TV closed the connection")
                    head.append(b.toChar())
                    if (head.length > 16384) throw IOException("bad answer")
                }
                val status = head.toString().substringBefore("\r\n")
                if (!status.contains(" 101")) throw IOException("the TV said: $status")
                val accept = Regex("(?im)^sec-websocket-accept:\\s*(\\S+)").find(head)?.groupValues?.get(1)
                val want = B64.encode(MessageDigest.getInstance("SHA-1").digest((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                if (accept != null && accept != want) throw IOException("bad websocket answer")
                return WebSocket(s)
            } catch (e: Exception) {
                try { s.close() } catch (ignored: Exception) { }
                try { plain.close() } catch (ignored: Exception) { }
                throw if (e is IOException) e else IOException(e.message ?: e.javaClass.simpleName, e)
            }
        }

        val TRUST_ANY = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }
}
