package dev.tvremote.core.remote
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import dev.tvremote.core.SelfSigned
import dev.tvremote.core.WireLog

/**
 * Keys only, for a TV whose ADB debugging is still off: the "Android TV Remote" protocol every Android TV answers on
 * ports 6467 (pairing, once, with the code the TV shows) and 6466 (keys and "open this"). It is what the Google TV phone
 * app speaks, and what the computer web uses through androidtvremote2. No picture, no apps, no files: those need ADB.
 */
class Atv(dir: File, private val host: String) : Remote, CodePairing {
    override val kind = Remote.Kind.ANDROID

    class NotPaired : Exception("not paired")

    private val key: PrivateKey
    private val cert: X509Certificate
    @Volatile private var sock: SSLSocket? = null
    private val out = Executors.newSingleThreadExecutor()
    @Volatile private var live = false
    private val SILENT_MS = 20_000                                    // 4 missed pings; a read timeout drops the link and connect() dials it again
    @Volatile private var closed = false

    /** What the TV calls itself, known once connected. */
    @Volatile override var model = ""
        private set
    @Volatile override var vendor = ""
        private set
    @Volatile override var version = ""                               // of the TV's remote service
        private set
    /** The screen is on (remote_start); null until the TV says. */
    @Volatile override var isOn: Boolean? = null
        private set
    /** level, max, muted, as the TV reports them on connecting and at each change; null until then. */
    @Volatile override var volume: Triple<Int, Int, Boolean>? = null
        private set

    init {
        val k = File(dir, "atv.pk8")
        val c = File(dir, "atv.crt")
        if (!k.exists() || !c.exists()) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            c.writeBytes(SelfSigned.certificate(pair.public.encoded, pair.private))
            k.writeBytes(pair.private.encoded)
        }
        key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(k.readBytes()))
        cert = CertificateFactory.getInstance("X.509").generateCertificate(c.inputStream()) as X509Certificate
    }

    override fun ready(): Boolean = live && !closed

    // ---------------------------------------------------------------- pairing (port 6467)
    /** Asks the TV to show its code. Then [finishPairing] with what the person reads there. */
    override fun startPairing() {
        WireLog.all.add("·", "remote $host pairing")
        try { sock?.close() } catch (ignored: Exception) { }              // a retry must not leave the last attempt's socket open
        val s = dial(6467)
        sock = s
        try { s.soTimeout = 15000 } catch (ignored: Exception) { }        // the pairing path only: a TV that stops answering must not hold this for ever
        fun step(field: Int, body: ByteArray, expect: Int) {
            send(s, Pb().int(1, 2).int(2, 200).msg(field, body).bytes())
            answer(s, expect)
        }
        try {
            step(10, Pb().str(1, "atvremote").str(2, "TV Remote").bytes(), 11)
            val hex6 = Pb().int(1, 3).int(2, 6).bytes()                    // the code: 6 hexadecimal characters
            step(20, Pb().msg(1, hex6).int(3, 1).bytes(), 20)
            step(30, Pb().msg(1, hex6).int(2, 1).bytes(), 31)
        } catch (e: Exception) { try { s.close() } catch (ignored: Exception) { }; throw e }   // refused: the TV waits on a dead attempt otherwise
    }

    /** Throws when the code is wrong; the TV then drops the attempt and [startPairing] has to be run again. */
    override fun finishPairing(code: String) {
        val s = sock ?: throw IllegalStateException("startPairing first")
        val c = code.trim().uppercase()
        require(c.length == 6 && c.all { it in "0123456789ABCDEF" }) { "bad code" }
        val secret = secret(cert.publicKey as RSAPublicKey, s.session.peerCertificates[0].publicKey as RSAPublicKey, c) ?: throw IllegalArgumentException("bad code")
        send(s, Pb().int(1, 2).int(2, 200).msg(40, Pb().raw(1, secret).bytes()).bytes())
        answer(s, 41)
        close()
    }

    private fun answer(s: SSLSocket, field: Int) {
        val m = Pb.read(frame(s.inputStream) ?: throw java.io.IOException("the TV closed the pairing"))
        if ((m[2]?.firstOrNull() as? Long ?: 0L) != 200L || m[field] == null) throw java.io.IOException("pairing refused")
    }

    // ---------------------------------------------------------------- remote (port 6466)
    @Volatile private var ime = 0L                                    // the TV's counters for its text field, echoed back with the text
    @Volatile private var field = 0L

    /** The app in front has told us about a text field that takes text from the TV's keyboard service; see [text] and [keys]. */
    @Volatile var hasField = false
        private set

    /** The package on the TV's screen, as the TV reports it ("" until it does). */
    @Volatile override var app = ""
        private set

    /**
     * Blocks until the TV accepts keys. [NotPaired]: the TV does not know this phone yet. [onClosed] is called once, later, if
     * the connection ends for good: a TV that drops it (some do after a link they cannot open) is simply dialled again.
     */
    override fun connect(onClosed: () -> Unit) {
        var s = try { open() } catch (e: Exception) { WireLog.all.add("·", "remote $host $e"); throw e }
            ?: run { WireLog.all.add("·", "remote $host not paired"); throw NotPaired() }
        WireLog.all.add("·", "remote $host connected: $vendor $model $version")
        Thread({
            while (true) {
                try { while (true) handle(s, frame(s.inputStream) ?: break) } catch (e: Exception) { WireLog.all.add("·", "remote $host $e") }
                WireLog.all.add("·", "remote $host dropped, dialling again")
                live = false
                try { s.close() } catch (ignored: Exception) { }   // the dropped link, or its descriptor stays until the GC
                var again: SSLSocket? = null
                for (i in 0 until 4) {
                    if (closed) break
                    try { Thread.sleep(600) } catch (ignored: InterruptedException) { }
                    again = try { open() } catch (e: Exception) { null }
                    if (again != null) break
                }
                if (again != null && closed) { try { again.close() } catch (ignored: Exception) { }; break }   // closed while dialling
                s = again ?: break
            }
            if (!closed) { WireLog.all.add("·", "remote $host gone"); onClosed() }
        }, "atv").apply { isDaemon = true }.start()
    }

    /** Dials and answers the TV's greeting. null when the TV lets us in and then drops us without a word: it does not know this phone. */
    private fun open(): SSLSocket? {
        val s = try { dial(6466) } catch (e: javax.net.ssl.SSLException) { return null }
        sock = s
        s.soTimeout = 8000
        try {
            while (!live) handle(s, frame(s.inputStream) ?: break)
        } catch (slow: java.net.SocketTimeoutException) {             // a weak link is not a reason to ask for a new code
            try { s.close() } catch (ignored: Exception) { }
            throw java.io.IOException("timed out")
        } catch (ignored: Exception) {
        }
        if (!live) { try { s.close() } catch (ignored: Exception) { }; return null }
        s.soTimeout = SILENT_MS                                       // the TV pings every 5 s: silence = a pulled plug or a lost Wi-Fi with no FIN; connect()'s reader dials again
        return s
    }

    private fun handle(s: SSLSocket, body: ByteArray) {
        val m = Pb.read(body)
        if (8 !in m) WireLog.all.add("←", "remote $host " + m.keys.sorted().joinToString(",", "message ") + " (${body.size} B)")   // pings: every 5 s
        m[1]?.let {                                                   // remote_configure: who the TV is; answer with who we are
            val info = Pb.read(it[0] as? ByteArray ?: ByteArray(0))[2]?.firstOrNull() as? ByteArray
            if (info != null) {
                val d = Pb.read(info)
                fun str(f: Int) = (d[f]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8) ?: ""
                model = str(1); vendor = str(2); version = str(6)
            }
            val me = Pb().str(1, "TV Remote").str(2, "tv-remote").int(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0").bytes()
            send(s, Pb().msg(1, Pb().int(1, 622).msg(2, me).bytes()).bytes())
        }
        m[2]?.let {                                                   // remote_set_active
            send(s, Pb().msg(2, Pb().int(1, 622).bytes()).bytes())
            live = true
        }
        m[8]?.let {                                                   // ping
            val n = Pb.read(it[0] as? ByteArray ?: ByteArray(0))[1]?.firstOrNull() as? Long ?: 0L
            send(s, Pb().msg(9, Pb().int(1, n).bytes()).bytes())
        }
        m[40]?.let { isOn = (Pb.read(it[0] as? ByteArray ?: ByteArray(0))[1]?.firstOrNull() as? Long ?: 0L) != 0L }   // remote_start: screen on or off
        m[50]?.let {                                                  // remote_set_volume_level
            val v = Pb.read(it[0] as? ByteArray ?: ByteArray(0))
            fun n(f: Int) = (v[f]?.firstOrNull() as? Long ?: 0L).toInt()
            volume = Triple(n(7), n(6), n(8) != 0)
        }
        m[21]?.let {                                                  // remote_ime_batch_edit: a text field on the TV has the focus
            val e = Pb.read(it[0] as? ByteArray ?: ByteArray(0))
            ime = e[1]?.firstOrNull() as? Long ?: 0L
            field = e[2]?.firstOrNull() as? Long ?: 0L
            hasField = true
        }
        m[30]?.let {                                                  // remote_voice_begin: the Assistant listens, under this session
            voiceWait?.offer(Pb.read(it[0] as? ByteArray ?: ByteArray(0))[1]?.firstOrNull() as? Long ?: 0L)
        }
        m[20]?.let {                                                  // remote_ime_key_inject: sent whenever another app comes to the front
            val info = Pb.read(it[0] as? ByteArray ?: ByteArray(0))[1]?.firstOrNull() as? ByteArray
            val pkg = info?.let { i -> Pb.read(i)[12]?.firstOrNull() as? ByteArray }?.toString(Charsets.UTF_8)
            if (!pkg.isNullOrEmpty()) { if (pkg != app) hasField = false; app = pkg }
        }
    }

    /** [direction]: [DOWN] and [UP] for a held key, [SHORT] for a tap. */
    override fun key(code: Int, direction: Int) {
        WireLog.all.add("→", "remote $host key $code " + (if (direction == SHORT) "tap" else if (direction == DOWN) "down" else "up"))
        post(Pb().msg(10, Pb().int(1, code.toLong()).int(2, direction.toLong()).bytes()).bytes())
    }

    /**
     * The key held down for [ms], as a finger on a real remote holds it. Apps that move a pointer with the arrows (TV browsers)
     * move it for as long as the key is down, so a key that goes down and up in the same instant moves nothing there.
     */
    override fun pulse(code: Int, ms: Long) {
        if (closed || sock == null) return
        val down = Pb().msg(10, Pb().int(1, code.toLong()).int(2, DOWN.toLong()).bytes()).bytes()
        val up = Pb().msg(10, Pb().int(1, code.toLong()).int(2, UP.toLong()).bytes()).bytes()
        out.execute {
            val s = sock
            if (!live || s == null) return@execute
            try {
                send(s, down)
                try { Thread.sleep(ms) } catch (ignored: InterruptedException) { }
                send(s, up)
            } catch (e: Exception) { try { s.close() } catch (ignored: Exception) { } }
        }
    }

    /** Fills the text field that has the focus on the TV with [t] (it replaces what is there). Nothing happens when no field has it. */
    override fun text(t: String) {
        if (t.isEmpty()) return
        val at = (t.length - 1).toLong()
        val edit = Pb().int(1, 1).msg(2, Pb().int(1, at).int(2, at).str(3, t).bytes()).bytes()
        post(Pb().msg(21, Pb().int(1, ime).int(2, field).msg(3, edit).bytes()).bytes())
    }

    /**
     * Types [t] as key presses, one per character, the way a keyboard plugged into the TV would: for apps with a keyboard of
     * their own (Downloader), whose fields the TV's keyboard service never hears about. Only what has a key without Shift
     * gets through: capitals arrive in lower case, and characters such as ":" are left out (see [typable]).
     */
    fun keys(t: String) {
        for (c in t) (code(c).takeIf { it != 0 } ?: code(c.lowercaseChar())).let { if (it != 0) key(it, SHORT) }
    }

    // ---------------------------------------------------------------- voice (the 622 we send includes VOICE, 8)
    // What is said into this device goes to the TV's Assistant, as into a voice remote's microphone: the search key, the TV
    // answers with a session (remote_voice_begin), we echo it, then 16-bit PCM, 8 kHz, mono, then remote_voice_end
    // (tronikos/androidtvremote2 found the flow). Its samples go big-endian: a TCL (Google TV 12) heard nothing in
    // little-endian ("no speech detected") and every word in big-endian, measured. The TV closes the connection on a piece
    // over 20 KB; pieces under 8 KB are padded with silence, so the caller sends at least that much at a time but the last.
    @Volatile private var voiceWait: java.util.concurrent.ArrayBlockingQueue<Long>? = null   // not a CompletableFuture: API 24, phones run Android 5

    /** The Assistant listening to this device: its session, or null when the TV named none within 3 s (no Assistant there). */
    fun voiceBegin(): Long? {
        if (!ready()) return null
        val f = java.util.concurrent.ArrayBlockingQueue<Long>(1)
        voiceWait = f
        key(KEYCODE_SEARCH, SHORT)
        val id = try { f.poll(3, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { null } finally { voiceWait = null }
        if (id != null) post(Pb().msg(30, Pb().int(1, id).bytes()).bytes())
        return id
    }

    /** [pcm]: 16-bit little-endian samples (as recorded), 8 kHz, mono. */
    fun voiceSend(id: Long, pcm: ByteArray) {
        for (i in 0 until pcm.size - 1 step 2) { val t = pcm[i]; pcm[i] = pcm[i + 1]; pcm[i + 1] = t }
        var at = 0
        while (at < pcm.size) {
            val n = minOf(VOICE_MAX, pcm.size - at)
            val piece = ByteArray(maxOf(n, VOICE_MIN)).also { System.arraycopy(pcm, at, it, 0, n) }
            post(Pb().msg(31, Pb().int(1, id).raw(2, piece).bytes()).bytes())
            at += n
        }
    }

    fun voiceEnd(id: Long) = post(Pb().msg(32, Pb().int(1, id).bytes()).bytes())

    /** Opens a link on the TV, or an app by its package name: the app when it is installed, its Play Store page when it is not. */
    override fun launch(link: String) {
        val full = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link)) link else "market://launch?id=$link"
        post(Pb().msg(90, Pb().str(1, full).bytes()).bytes())
    }

    private fun post(message: ByteArray) {
        if (closed || sock == null) return
        out.execute {
            for (i in 0 until 30) { if (live || closed) break; try { Thread.sleep(200) } catch (e: InterruptedException) { return@execute } }   // being dialled again
            val s = sock
            if (!live || s == null) return@execute
            try { send(s, message) } catch (e: Exception) { try { s.close() } catch (ignored: Exception) { } }
        }
    }

    override fun close() {
        closed = true
        out.shutdown()
        val s = sock
        Thread { try { s?.close() } catch (ignored: Exception) { } }.start()   // may be called on the UI thread
    }

    // ---------------------------------------------------------------- plumbing
    private fun dial(port: Int): SSLSocket {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setKeyEntry("k", key, CharArray(0), arrayOf(cert)) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val anyTv = object : X509TrustManager {                       // the TV's certificate is self-made too; the pairing code is what ties the two together
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf(anyTv), null) }
        val plain = dev.tvremote.core.Lan.connect(host, port, 5000)
        val s = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        try {
            s.soTimeout = 15000
            s.startHandshake()
            s.soTimeout = 0
        } catch (e: Exception) { try { s.close() } catch (ignored: Exception) { }; throw e }   // a refused handshake (not paired) must not leave the socket open
        return s
    }

    @Synchronized
    private fun send(s: SSLSocket, body: ByteArray) {
        val o = ByteArrayOutputStream()
        Pb.varint(o, body.size.toLong())
        o.write(body)
        s.outputStream.write(o.toByteArray())
        s.outputStream.flush()
    }

    /** Just enough protocol buffers: numbers, strings/bytes and nested messages. */
    internal class Pb {
        private val o = ByteArrayOutputStream()
        fun int(field: Int, v: Long): Pb { varint(o, (field shl 3).toLong()); varint(o, v); return this }
        fun raw(field: Int, v: ByteArray): Pb { varint(o, (field shl 3 or 2).toLong()); varint(o, v.size.toLong()); o.write(v); return this }
        fun str(field: Int, v: String) = raw(field, v.toByteArray(Charsets.UTF_8))
        fun msg(field: Int, v: ByteArray) = raw(field, v)
        fun bytes(): ByteArray = o.toByteArray()

        companion object {
            fun varint(o: ByteArrayOutputStream, value: Long) {
                var v = value
                while (v and 0x7fL.inv() != 0L) { o.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
                o.write(v.toInt())
            }

            /** field number -> its values in order: Long for numbers, ByteArray for everything with a length. */
            /** A broken message throws IOException, never an index error that would end the reader thread. */
            fun read(b: ByteArray): Map<Int, List<Any>> {
                val out = HashMap<Int, MutableList<Any>>()
                var p = 0
                fun bad(): Nothing = throw java.io.IOException("bad message")
                fun num(): Long { var v = 0L; var s = 0; while (true) { if (p >= b.size || s > 63) bad(); val x = b[p++].toInt(); v = v or ((x and 0x7f).toLong() shl s); if (x and 0x80 == 0) return v; s += 7 } }
                while (p < b.size) {
                    val tag = num().toInt()
                    val v: Any = when (tag and 7) {
                        0 -> num()
                        2 -> { val n = num(); if (n < 0 || n > b.size - p) bad(); b.copyOfRange(p, p + n.toInt()).also { p += n.toInt() } }
                        1 -> { p += 8; if (p > b.size) bad(); 0L }
                        5 -> { p += 4; if (p > b.size) bad(); 0L }
                        else -> bad()
                    }
                    out.getOrPut(tag ushr 3) { ArrayList() }.add(v)
                }
                return out
            }
        }
    }

    companion object {
        /** One message: its length, then that many bytes. null at the end of the stream. */
        internal fun frame(i: InputStream): ByteArray? {
            var len = 0
            var shift = 0
            while (true) {
                val b = i.read()
                if (b < 0) return null
                len = len or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 21) throw java.io.IOException("bad message")   // over 4 bytes: no real message, and the length would overflow
            }
            if (len > 1 shl 20) throw java.io.IOException("bad message")  // the TV's messages are a few KB: never allocate what a broken one claims
            val buf = ByteArray(len)
            var got = 0
            while (got < len) {
                val n = i.read(buf, got, len - got)
                if (n < 0) return null
                got += n
            }
            return buf
        }

        const val DOWN = 1
        const val UP = 2
        const val SHORT = 3
        const val KEYCODE_SEARCH = 84
        const val VOICE_MAX = 20 * 1024
        const val VOICE_MIN = 8 * 1024

        /** The key that types [c] without Shift, or 0. */
        private fun code(c: Char): Int = when (c) {
            in 'a'..'z' -> 29 + (c - 'a')
            in '0'..'9' -> 7 + (c - '0')
            ' ' -> 62; '.' -> 56; '/' -> 76; '-' -> 69; ',' -> 55; '=' -> 70; ';' -> 74; '\'' -> 75; '@' -> 77; '+' -> 81
            '#' -> 18; '*' -> 17; '[' -> 71; ']' -> 72; '\\' -> 73; '`' -> 68
            else -> 0
        }

        /** True when [keys] types [t] exactly: every character has a key of its own. */
        fun typable(t: String): Boolean = t.all { code(it) != 0 }

        /**
         * What proves the code was read off the TV: SHA-256 of both keys (ours first) and the code's last four characters. Its
         * first byte is the code's first two: null when they differ, a mistyped code, caught before the TV drops the attempt.
         */
        internal fun secret(mine: RSAPublicKey, theirs: RSAPublicKey, code: String): ByteArray? {
            val h = MessageDigest.getInstance("SHA-256")
            for (n in listOf(mine.modulus, mine.publicExponent, theirs.modulus, theirs.publicExponent)) h.update(unsigned(n))
            h.update(hex(code.substring(2)))
            return h.digest().takeIf { it[0] == hex(code.substring(0, 2))[0] }
        }

        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        /** The number as the TV hashes it: its hexadecimal digits, padded to whole bytes, without a sign byte. */
        private fun unsigned(n: BigInteger): ByteArray = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    }
}
