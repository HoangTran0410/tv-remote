package dev.tvremote.core.adb
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import dev.tvremote.core.B64
import dev.tvremote.core.WireLog

/**
 * A small ADB client over TCP: authenticates with this app's own RSA key and multiplexes several streams (shell
 * commands, sockets on the TV) over the one connection. The first connection makes the TV ask "Allow USB debugging?".
 */
/** The link was already gone, or the write failed, before the TV got the command: it never ran, so asking again is always safe. */
class NotSent(msg: String) : IOException(msg)

class Adb(private val dir: File) : Closeable {
    fun interface Prompt { fun waitingForUser() }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** One ADB stream. Reads block until data, end of stream or the timeout; writes block until the TV acknowledges. */
    inner class Stream internal constructor(internal val local: Int) : Closeable {
        @Volatile internal var remote = 0
        @Volatile private var closed = false
        internal val incoming = LinkedBlockingQueue<ByteArray>()
        internal val opened = Semaphore(0)
        internal val acked = Semaphore(0)                            // one-at-a-time TVs: a permit per packet the TV took
        internal val credit = Semaphore(0)                           // delayed_ack TVs: bytes we may still send (the TV's window)
        internal val delayed = this@Adb.delayed                      // fixed when the stream opens: both sides must agree for its whole life
        private var cur = EMPTY
        private var pos = 0
        internal var pace: ((Long) -> Unit)? = null                  // set by writeTo: called every PACE_STEP bytes with the count sent
        private var sent = 0L
        private var paced = PACE_STEP

        /** Reads up to len bytes; -1 at end of stream; 0 if nothing arrived within timeoutMs (0 = wait for ever). */
        @Throws(IOException::class)
        fun read(buf: ByteArray, off: Int, len: Int, timeoutMs: Long): Int {
            if (pos == cur.size) {
                if (cur === EOF) return -1
                try {
                    val next = (if (timeoutMs > 0) incoming.poll(timeoutMs, TimeUnit.MILLISECONDS) else incoming.take()) ?: return 0
                    cur = next
                    pos = 0
                    if (cur === EOF) return -1
                    if (!closed) ackTo(this, next.size)                // the TV sends its next packet only after this (delayed_ack: within its window)
                } catch (e: InterruptedException) {
                    throw IOException("interrupted")
                }
            }
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, buf, off, n)
            pos += n
            return n
        }

        @Throws(IOException::class)
        fun readFully(buf: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                val n = read(buf, at, left, 0)
                if (n < 0) throw EOFException("stream closed")
                at += n
                left -= n
            }
        }

        /** Everything until the end of the stream. With [quietMs], gives up when nothing has come for that long (a command that hangs). */
        @Throws(IOException::class)
        fun readAll(quietMs: Long = 0): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = read(buf, 0, buf.size, if (quietMs > 0) quietMs else 0)
                if (n < 0) break
                if (n == 0) { kill(); throw IOException("the TV stopped answering") }
                out.write(buf, 0, n)
            }
            return out.toString("UTF-8").replace("\r\n", "\n").trim()
        }

        @Synchronized
        @Throws(IOException::class)
        fun write(data: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                if (closed) throw IOException("stream closed")
                val n = minOf(left, maxData)
                // delayed_ack: wait for room in the TV's window, then send and go on (the OKAYs hand the room back as the TV
                // takes the data); otherwise one packet in flight: send, then wait for its OKAY.
                if (delayed) waitFor { credit.tryAcquire(n, 1, TimeUnit.SECONDS) }
                send(WRTE, local, remote, data.copyOfRange(at, at + n))
                if (!delayed) waitFor { acked.tryAcquire(1, TimeUnit.SECONDS) }
                at += n
                left -= n
                sent += n
                if (sent >= paced) { paced = sent + PACE_STEP; pace?.invoke(sent) }
            }
        }

        @Throws(IOException::class)
        fun write(data: ByteArray) = write(data, 0, data.size)

        private inline fun waitFor(got: () -> Boolean) {
            try {
                var waited = 0
                while (!got()) {
                    if (closed) throw IOException("stream closed")
                    if (++waited >= WRITE_WAIT) { kill(); throw IOException("the TV stopped taking data") }   // never wait for ever
                }
            } catch (e: InterruptedException) {
                throw IOException("interrupted")
            }
        }

        val isClosed: Boolean get() = closed

        override fun close() {
            if (closed) return
            end()
            try { send(CLSE, local, remote, EMPTY) } catch (ignored: IOException) { }
        }

        internal fun end() {
            closed = true
            streams.remove(local)
            incoming.add(EOF)
            opened.release()
        }
    }

    internal val key: KeyPair
    private val streams = ConcurrentHashMap<Int, Stream>()
    private var sock: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var nextId = 1
    private var maxData = 4096
    /** The TV and this client both listed `delayed_ack` (see [connected]): a stream may have a window of bytes in flight. */
    @Volatile internal var delayed = false
    @Volatile private var dead = false
    @Volatile private var heard = System.nanoTime()                   // the last packet from the TV, whatever its stream
    private val users = java.util.concurrent.atomic.AtomicInteger(1)  // whoever made it, plus each one sharing it (see connectOrShare)
    @Volatile private var at = ""                                     // the host, once connected: the key in LIVE

    init {
        // one key per app, made once: on the first start TV Setup's screen, its service and its Manage process (another
        // process) all reach here at the same moment; each made its own, one overwrote the others', and the rest kept
        // asking the TV with keys nobody would ever use again (the strange extra "Allow USB debugging?" questions)
        key = synchronized(KEY_LOCK) {
            java.io.RandomAccessFile(File(dir, "adbkey.lock"), "rw").channel.use { ch ->
                ch.lock().use {
                    val priv = File(dir, "adbkey.pk8")
                    val pub = File(dir, "adbkey.x509")
                    val kf = KeyFactory.getInstance("RSA")
                    // a file cut short (power lost mid-write, a full disk) made every start throw "Error parsing public key" for
                    // good (census aa5953e5, a Fire TV, 2026-11-06): a key that cannot be read is no key, so a new one is made
                    // (the TV asks "Allow USB debugging?" once more) and written whole or not at all
                    val kept = if (priv.exists() && pub.exists()) try {
                        KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))).takeIf { pair(it) }
                    } catch (e: java.security.GeneralSecurityException) { null } else null
                    kept ?: KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().also { k ->
                        whole(priv, k.private.encoded)
                        whole(pub, k.public.encoded)
                    }
                }
            }
        }
    }

    /** The two files are one key: a power cut between writing them leaves a new private key with the old public one, which both
     *  parse but the TV would ask to "Allow" for ever (it stores the public key, the signature never matches). */
    private fun pair(k: KeyPair): Boolean = try {
        val b = "tvr".toByteArray()
        val s = java.security.Signature.getInstance("SHA256withRSA")
        s.initSign(k.private); s.update(b); val sig = s.sign()
        s.initVerify(k.public); s.update(b); s.verify(sig)
    } catch (e: java.security.GeneralSecurityException) { false }

    /** Connects and authenticates. If the TV does not know this app's key yet, waits (3 minutes) for the person to allow it. */
    @Throws(Exception::class)
    fun connect(host: String, port: Int, prompt: Prompt) {
        dial(host, port)
        if (authenticate() != null) try {
            if (!askOnce(host, port, prompt)) {
                ASKED[host] = System.currentTimeMillis()
                send(AUTH, AUTH_RSAPUBLICKEY, 0, (publicKey() + "\u0000").toByteArray())   // unknown key: the TV asks the person
                prompt.waitingForUser()
                awaitApproval(host, port)
            }
        } finally {
            ASKING.remove("$host:$port", this)                        // the others waiting on this one go on, whatever happened to it
        }
        sock!!.soTimeout = 0
        sock!!.keepAlive = true
        Thread(::pump, "adb-reader").apply { isDaemon = true }.start()
        at = host; LIVE[host] = this
        // the first connection in after we asked, whichever it is (the one that asked may have been let go and another got in)
        val asked = ASKED.remove(host)
        if (asked != null && System.currentTimeMillis() - asked < 600000) Thread({
            try { AdbPrompt.leftovers(this, fingerprint()) } catch (ignored: Exception) { }
            try { AdbPrompt.helpSetup(this) } catch (ignored: Exception) { }
        }, "adb-after-question").apply { isDaemon = true }.start()
    }

    /**
     * Connects to a TV the way [connect] does, but first through the TLS port Wireless debugging gave it (see [AdbTls]) when
     * this TV was paired that way; the port changes whenever Wireless debugging is switched on again, so a refused one is
     * looked for again (mDNS) before giving up on it. A TV never paired this way, or whose TLS port is gone, is dialled on 5555.
     */
    @Throws(Exception::class)
    fun connectTo(host: String, prompt: Prompt) {
        val saved = AdbTls.saved(dir, host)
        val opened = saved > 0 && plainForSetup(host, saved)
        if (opened) Thread({                                          // TV Setup asks for its own key on 5555 now: answered by this app
            Thread.sleep(4000)
            try { if (!dead) AdbPrompt.helpSetup(this) } catch (ignored: Exception) { }
        }, "adb-after-tcpip").apply { isDaemon = true }.start()
        if (saved > 0) {
            var failure: Exception? = null
            var gone = true                                           // every TLS port refused (or the key is no longer trusted)
            for (round in 0..1) {
                val port = if (round == 0) saved else AdbTls.discoverConnect(host)
                if (port <= 0 || (round == 1 && port == saved)) continue
                try {
                    connectTls(host, port)
                    if (port != saved) AdbTls.save(dir, host, port)
                    return
                } catch (e: Exception) {
                    if (failure == null) failure = e
                    if (e !is java.net.ConnectException && e.message?.contains("not paired") != true) gone = false
                    WireLog.all.add("·", "adb $host tls port $port: $e")
                    close0()
                }
            }
            // the saved port is dead (Wireless debugging was switched off or moved, and mDNS finds no new one): forget it, so the
            // tries below say what is really missing (5555 / pairing) instead of dialling a stale port forever (census: a TX5 with
            // Wireless debugging off still dialled port 42203 on every try). Wireless debugging on again is found by wirelessOnly().
            if (gone) AdbTls.save(dir, host, 0)
            else if (!AdbTls.plainOpen(host)) throw failure ?: IOException("adb tls: no port")   // nothing on 5555 to fall back to: say what the TLS port said
        }
        try {
            AdbPort.load(dir)
            connect(host, AdbPort.of(host), prompt)
        } catch (e: IOException) {
            wirelessOnly(host)?.let { throw it }                      // no 5555, but Wireless debugging: pairing is what is missing
            throw e
        }
    }

    /**
     * A TV that offers only Wireless debugging (a Chromecast with Google TV on Android 14, the TCL China sets) has no port 5555, and
     * TV Setup on it, which dials its own TV on 127.0.0.1:5555 and cannot pair with itself, had no ADB: no key mapping, no app on
     * power-on, no timers. This app, paired, asks adbd to listen on 5555 too (the `tcpip:5555` service, as `adb tcpip 5555`), on
     * a connection of its own: adbd restarts, so this one dials again after it (the TLS port may move: found again by mDNS). Lost
     * at the TV's next restart, so done on each connection that finds 5555 closed with TV Setup installed; at most every 10 min.
     */
    private fun plainForSetup(host: String, port: Int): Boolean {
        val now = System.currentTimeMillis()
        if (AdbTls.same(host, "127.0.0.1")) return false               // TV Setup itself, paired: it stays on the TLS port
        if (now - (PLAIN_ASKED[host] ?: 0L) < 600_000L || AdbTls.plainOpen(host)) return false
        try {
            Adb(dir).use { t ->
                t.connectTls(host, port)
                if (!Pkg.installed({ t.shell(it, 10000) }, "dev.tvremote.setup")) return false
                PLAIN_ASKED[host] = now
                val said = try { t.open("tcpip:5555").use { it.readAll(3000) } } catch (e: IOException) { e.toString() }
                WireLog.all.add("·", "adb $host tcpip:5555 for TV Setup: " + said.trim().take(120))
            }
        } catch (e: Exception) { return false }                       // a stale TLS port: the normal way below finds the new one
        Thread.sleep(3000)                                            // adbd restarting
        return true
    }

    /**
     * A TV with no port 5555 that announces Wireless debugging (Android 11+, the TCL China sets): connects through it when this
     * key is already trusted there and returns null; otherwise the error that sends the person to pairing ("not paired").
     * Null as well where it does not apply (5555 answers, or this app cannot do TLS), so the caller's own error stands.
     */
    private fun wirelessOnly(host: String): IOException? {
        if (!AdbTls.available() || AdbTls.plainOpen(host)) return null
        val w = AdbTls.wireless(host)
        if (!w.any) return null
        WireLog.all.add("·", "adb $host: wireless debugging only (connect ${w.connect}, pairing ${w.pairing})")
        if (w.connect > 0) {
            try { connectTls(host, w.connect); AdbTls.save(dir, host, w.connect); return null } catch (e: Exception) {
                WireLog.all.add("·", "adb $host tls port ${w.connect}: $e")
                close0()
            }
        }
        return IOException("adb tls: this device is not paired")
    }

    /**
     * Each part of an app (the picture, Manage, the now playing bar...) has its own connection, and each would put its own
     * "Allow USB debugging?" on the TV, one after another, for the same key. So one connection per TV asks, and the others
     * wait for its answer and then just log in. Returns true when logged in that way; false when this one is to ask.
     * TV Setup's first question waits a little too ([holdFirstAsk]): a phone connecting at the same moment then asks first,
     * and once allowed it answers TV Setup's question itself, so the person sees one question instead of two.
     */
    private fun askOnce(host: String, port: Int, prompt: Prompt): Boolean {
        val k = "$host:$port"
        val end = System.currentTimeMillis() + 180000
        while (ASKING.putIfAbsent(k, this) != null) {
            prompt.waitingForUser()
            close0()
            while (ASKING[k] != null && !dead && System.currentTimeMillis() < end) Thread.sleep(400)
            if (dead) throw IOException("closed")
            if (System.currentTimeMillis() >= end) throw IOException(UNANSWERED)
            dial(host, port)
            if (authenticate() == null) return true
        }
        val hold = holdMs
        if (hold <= 0) return false
        holdMs = 0                                                    // the first question only: a later one is not kept waiting
        holding = true
        try { synchronized(poke) { if (!poked) (poke as Object).wait(hold) } } finally { holding = false }
        close0()                                                      // the socket sat unused meanwhile: a fresh one asks
        dial(host, port)
        return authenticate() == null
    }

    /** A new socket for the next try: this object is used once, so only the dead one is dropped. */
    private fun close0() { try { sock?.close() } catch (ignored: IOException) { } }

    /**
     * Connects to the port Wireless debugging announces for connecting (Android 11+): plain ADB first, then the device asks
     * for TLS (A_STLS) and both sides switch to TLS 1.3 with our key as the client certificate. The key was trusted at
     * pairing, so there is no "Allow USB debugging?" question; an unpaired key is refused here.
     */
    @Throws(Exception::class)
    fun connectTls(host: String, port: Int) {
        dial(host, port)
        send(CNXN, 0x01000001, 1 shl 18, BANNER)
        var m = read()
        if (m.cmd == STLS) {
            send(STLS, m.arg0, 0, EMPTY)
            val ssl = AdbTls.handshake(this, sock!!, host, port)
            sock = ssl
            input = DataInputStream(ssl.inputStream)
            output = ssl.outputStream
            // TLS 1.3: our side ends the handshake before the TV checks the key, so a key it no longer trusts (pairing forgotten
            // on the TV) comes back as an alert on this first read, "Read error: ssl=…" (census Chromecast 3f685f9c): unpaired
            m = try {
                try { read() } catch (quiet: SocketTimeoutException) { send(CNXN, 0x01000001, 1 shl 18, BANNER); read() }
            } catch (e: javax.net.ssl.SSLException) { throw IOException("adb tls: this device is not paired", e) }
        }
        if (m.cmd == AUTH) throw IOException("adb tls: this device is not paired")
        if (m.cmd != CNXN) throw IOException("adb tls: unexpected answer")
        connected(m)
        sock!!.soTimeout = 0
        sock!!.keepAlive = true
        Thread(::pump, "adb-reader").apply { isDaemon = true }.start()
        at = host; LIVE[host] = this
    }

    private fun awaitApproval(host: String, port: Int) {
        var asking = sock!!
        var askingIn = input
        val end = System.currentTimeMillis() + 180000
        var askingAlive = true
        var askAgainAt = 0L
        var asked = 1
        var askedAt = System.nanoTime() / 1_000_000
        while (System.currentTimeMillis() < end) {
            if (dead) { asking.close(); throw IOException("closed") }   // whoever wanted this connection has let go of it (and the TV's prompt with it)
            if (askingAlive) {
                sock = asking
                input = askingIn
                output = asking.getOutputStream()
                try {
                    asking.soTimeout = 3000
                    while (true) {
                        val m = read()
                        if (m.cmd == CNXN) { connected(m); return }
                    }
                } catch (waiting: SocketTimeoutException) {
                    // still undecided on that connection
                } catch (closedByTv: IOException) {
                    askingAlive = false
                    askAgainAt = System.nanoTime() / 1_000_000 + 6000
                }
            } else {
                Thread.sleep(2000)
            }
            // Some TVs never upgrade the connection that asked; once "Always allow" is ticked a fresh one just works.
            try {
                dial(host, port)
                if (authenticate() == null) { asking.close(); return }
                val now = System.nanoTime() / 1_000_000
                // the question went away on the TV without "Always allow" (Back, Home, Cancel, a timeout): ask it once more.
                // Or it never showed: the TV shows one question at a time and drops a new one while another (a dead
                // connection's, someone else's) is up; once that is answered, only asking again brings ours up.
                val stale = askingAlive && (reask || now - askedAt > 20000)
                if ((!askingAlive && asked < 4 && now > askAgainAt) || (stale && asked < 12)) {
                    reask = false; askedAt = now
                    send(AUTH, AUTH_RSAPUBLICKEY, 0, (publicKey() + "\u0000").toByteArray())
                    asking.close()
                    asking = sock!!
                    askingIn = input
                    askingAlive = true
                    asked++
                    continue
                }
                sock!!.close()
            } catch (retry: IOException) {
                val s = sock                                          // adbd busy, try again: not with this half-open socket
                if (s != null && s !== asking) try { s.close() } catch (ignored: IOException) { }
            }
        }
        asking.close()
        throw IOException(UNANSWERED)
    }

    private fun dial(host: String, port: Int) {
        val s = dev.tvremote.core.Lan.connect(host, port, 6000) { sock = it }         // set before it connects: a wait elsewhere can close it
        s.tcpNoDelay = true
        s.soTimeout = 15000
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()
    }

    /** Signs the TV's challenge with our key. Returns null when connected, or the next challenge if the key is not trusted yet. */
    private fun authenticate(): ByteArray? {
        send(CNXN, 0x01000000, 1 shl 18, BANNER)
        var signed = false
        while (true) {
            val m = read()
            if (m.cmd == CNXN) {
                connected(m)
                return null
            }
            if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) continue
            if (signed) return m.data
            if (m.data.size > 200) throw IOException("bad adb token")     // adb's are 20 bytes; a longer one makes RSA throw, not an IOException
            signed = true
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            c.init(Cipher.ENCRYPT_MODE, key.private)
            c.update(SHA1_PREFIX)
            send(AUTH, AUTH_SIGNATURE, 0, c.doFinal(m.data))
        }
    }

    /** Reader thread: routes every incoming packet to its stream. */
    private fun pump() {
        try {
            while (true) {
                val m = read()
                heard = System.nanoTime()
                val s = streams[m.arg1]
                if (s == null) {                                      // a late yes to an open given up on: the TV's side is let go
                    if (m.cmd == OKAY && m.arg0 != 0) try { send(CLSE, m.arg1, m.arg0, ByteArray(0)) } catch (ignored: IOException) { }
                    continue
                }
                when (m.cmd) {
                    OKAY -> if (s.remote == 0) {
                        s.remote = m.arg0
                        if (s.delayed) s.credit.release(window(m.data))     // the answer to OPEN carries the room the TV has for us
                        s.opened.release()
                    } else if (s.delayed) s.credit.release(window(m.data)) else s.acked.release()
                    WRTE -> s.incoming.add(m.data)                    // acknowledged when read, so a slow reader slows the TV down instead of filling memory
                    CLSE -> s.end()
                }
            }
        } catch (e: IOException) {
            if (!dead) WireLog.all.add("·", "adb connection lost: $e")
            dead = true
            LIVE.remove(at, this)
            for (s in ArrayList(streams.values)) s.end()                 // toList() counts, then walks: a stream closing between ran out (census 1eaf9599)
        }
    }

    /** Opens an ADB service, e.g. "shell:ls" or "localabstract:name". Throws if the TV refuses it. */
    @Throws(IOException::class)
    fun open(service: String, answerMs: Long = 10000): Stream {
        if (dead) throw NotSent("adb connection lost")
        val s = synchronized(this) { Stream(nextId++) }
        streams[s.local] = s
        try { send(OPEN, s.local, if (s.delayed) RECV_WINDOW else 0, (service + "\u0000").toByteArray()) } catch (e: IOException) { streams.remove(s.local); throw NotSent(e.message ?: "adb write failed") }
        try {
            // no answer at all: the connection died quietly (the phone slept, Wi-Fi or VPN changed); drop it so the next call dials
            // again. A TV still sending on other streams is only slow: a weak box writing two APKs at once took over 10 s to open a
            // shell, and closing then cut both copies halfway ("lost the TV midway", again on every retry). That one call fails alone.
            val t0 = System.nanoTime()
            while (!s.opened.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
                val now = System.nanoTime()
                if ((now - heard) / 1_000_000 >= answerMs && (now - t0) / 1_000_000 >= answerMs) { s.end(); kill(); throw IOException("no answer from the TV") }
                if ((now - t0) / 1_000_000 >= maxOf(answerMs, BUSY_OPEN_MS)) { s.end(); throw IOException("the TV is busy") }
            }
        } catch (e: InterruptedException) {
            throw IOException("interrupted")
        }
        if (s.remote == 0) throw IOException("refused: $service")     // closed already is fine: a quick command can finish before we look, its output is waiting
        return s
    }

    /** Like shell(), but without a terminal in between, so binary output is not altered (file contents). */
    @Throws(IOException::class)
    fun exec(cmd: String): Stream = open("exec:$cmd")

    /**
     * Writes [size] bytes from [feed] to [path] on the TV, the stream left open until the file holds them all: some TVs (a
     * Sony on Android 9) end cat as the stream closes and lose what it had not written yet, all of a small file. True once whole.
     */
    /**
     * A stream whose bytes `cat` writes to [path], kept at most [AHEAD] bytes in front of what the file holds. Without that
     * the TV's adbd (Android 14 at least) acknowledges a fast sender long before a slow disk takes the data, and once some
     * 70 MB wait in it, it stops answering for good: an upload to /sdcard died at 75 MB, every time.
     */
    @Throws(IOException::class)
    fun writeTo(path: String): Stream {
        val s = exec("cat > " + q(path))
        s.pace = { sent ->
            var got = -1L
            var still = 0                                             // checks in a row without the file growing
            while (true) {
                if (s.isClosed) throw IOException("stream closed")    // a Stop closes it
                val now = shell("stat -c %s " + q(path) + " 2>/dev/null").trim().toLongOrNull() ?: 0
                if (now >= sent - AHEAD) break
                still = if (now == got) still + 1 else 0
                got = now
                if (still >= WRITE_WAIT * 5) { s.close(); throw IOException("the TV stopped taking data") }
                try { Thread.sleep(200) } catch (e: InterruptedException) { throw IOException("interrupted") }
            }
        }
        return s
    }

    @Throws(IOException::class)
    fun put(path: String, size: Long, readable: Boolean = false, md5: String? = null, feed: (Stream) -> Unit): Boolean {
        val tmp = "$path.part"                                        // written aside and moved in whole: a cut transfer never leaves a half file under the real name
        val s = writeTo(tmp)
        var ok = false
        try {
            feed(s)
            var got = ""
            var still = 0                                             // checks in a row without the file growing
            while (still < 40) {                                      // 8 s without growing: it will not finish
                val now = shell("stat -c %s " + q(tmp) + " 2>/dev/null").trim()
                if (now == size.toString()) { ok = true; break }
                still = if (now == got) still + 1 else 0
                got = now
                try { Thread.sleep(200) } catch (e: InterruptedException) { throw IOException("interrupted") }
            }
            if (ok && md5 != null) {                                  // where the TV has md5sum (toybox since Android 6; some boxes only busybox's)
                val sum = shell("(md5sum " + q(tmp) + " || toybox md5sum " + q(tmp) + ") 2>/dev/null").trim().substringBefore(' ').lowercase()
                if (sum.length == 32 && sum != md5.lowercase()) ok = false
            }
            if (ok) {
                // chmod: a file copied by the shell can be unreadable to the apps (Android 14: EACCES); only for what an app reads
                val r = shell("mv -f " + q(tmp) + " " + q(path) + (if (readable) " && chmod a+r " + q(path) else "") + " && echo ok").trim()
                ok = r.endsWith("ok")
            }
            return ok
        } finally {
            s.close()
            if (!ok) try { shell("rm -f " + q(tmp)) } catch (e: IOException) { }
        }
    }

    fun put(path: String, data: ByteArray, readable: Boolean = false): Boolean =
        put(path, data.size.toLong(), readable, java.security.MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }) { it.write(data) }

    /**
     * Runs a command in the TV's shell (as the `shell` user) and returns everything it printed. Throws when it prints nothing
     * for [quietMs] (2 minutes; longer for installs), so no caller waits for ever on a command that hangs.
     */
    @Throws(IOException::class)
    fun shell(cmd: String, quietMs: Long = 120000): String = open("shell:$cmd").use { it.readAll(quietMs) }

    fun isDead(): Boolean = dead

    /** Asks the TV for a trivial answer within [ms]; a connection that died quietly is closed by the asking. */
    fun alive(ms: Long): Boolean = try { open("shell:echo", ms).use { it.readAll(ms) }; !dead } catch (e: IOException) { false }

    /** Closes it once the last one sharing it lets go (see [connectOrShare]); a connection nobody shares closes at once. */
    override fun close() { if (users.decrementAndGet() <= 0) kill() }

    /** The connection is broken (the TV stopped answering): it goes for everyone sharing it, and the next call dials again. */
    private fun kill() {
        dead = true
        LIVE.remove(at, this)
        closeSock(sock)
    }

    /** One more user of this live connection, or null when it is closing or gone. */
    private fun share(): Adb? {
        while (!dead) { val n = users.get(); if (n <= 0) return null; if (users.compareAndSet(n, n + 1)) return this }   // no getAndUpdate: API 24
        return null
    }

    /**
     * [connectTo] for a new user, which takes what comes back: this, or this app's live connection to the same TV when the TV
     * serves one client at a time. Some adbd do (an RCA set, Realtek RTD284x, Android 7.1.1, found by the kevscec/tv-remote-rca
     * fork): a second client's handshake times out, and the remote, the picture, Manage and the list's status line each dial
     * their own. A dial that fails while another connection to that TV is alive with the same key can only be that: the TV
     * is remembered (a file beside the key) and shares the one connection from then on, its streams side by side. TVs that
     * take several clients keep one connection per user, so a big upload never holds up the keys.
     */
    @Throws(Exception::class)
    fun connectOrShare(host: String, dial: (() -> Unit)? = null, prompt: Prompt): Adb {   // dial: a test's own
        val one = File(dir, ONE_FILE)
        val single = synchronized(ONE) { if (ONE.isEmpty() && one.exists()) ONE.addAll(one.readLines().filter { it.isNotBlank() }); host in ONE }
        if (single) LIVE[host]?.share()?.let { close(); return it }
        try {
            if (dial != null) dial() else connectTo(host, prompt)
            return this
        } catch (e: Exception) {
            val other = (if (e.message == UNANSWERED) null else LIVE[host]?.share()) ?: throw e
            close()
            WireLog.all.add("·", "adb $host takes one client at a time ($e): sharing the open connection")
            synchronized(ONE) { if (ONE.add(host)) try { one.writeText(ONE.joinToString("\n") + "\n") } catch (ignored: IOException) { } }
            return other
        }
    }

    /** TLS closing writes close_notify: on the app's main thread (StrictMode, NetworkOnMainThreadException) another thread does it. */
    internal fun closeSock(s: Socket?) {
        if (s == null) return
        if (Thread.currentThread().name != "main") { try { s.close() } catch (ignored: IOException) { }; return }
        Thread({ try { s.close() } catch (ignored: IOException) { } }, "adb-close").apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val sum = data.sumOf { it.toInt() and 0xff }
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(cmd.inv()).put(data)
        if (!::output.isInitialized) throw IOException("not connected")   // alive() on one whose connect never got through: callers catch IOException, not this (crash b4de9bca)
        output.write(b.array())
        output.flush()
    }

    /** The TV's CNXN: the biggest packet it takes, and whether it speaks delayed_ack (adb's `features=` list in its banner). */
    private fun connected(m: Msg) {
        maxData = m.arg1.coerceIn(4096, 1 shl 18)
        val features = String(m.data, Charsets.UTF_8).substringAfter("features=", "").substringBefore(';').trimEnd('\u0000')
        delayed = BURST && features.split(',').contains("delayed_ack")
    }

    /** What an OKAY carries with delayed_ack: a little-endian int, the bytes the sender may send (or, for OPEN, its whole window). */
    private fun window(data: ByteArray): Int =
        if (data.size == 4) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int.coerceAtLeast(0) else 0

    /** Tells the TV we took [bytes]: an empty OKAY, or with delayed_ack one carrying the count (adb's `send_ready`). */
    private fun ackTo(s: Stream, bytes: Int) {
        send(OKAY, s.local, s.remote, if (s.delayed) ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes).array() else EMPTY)
    }

    private fun read(): Msg {
        val head = ByteArray(24)
        input.readFully(head)
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int
        val arg0 = b.int
        val arg1 = b.int
        val len = b.int
        if (len < 0 || len > (1 shl 20)) throw IOException("bad adb packet")
        val data = ByteArray(len)
        input.readFully(data)
        return Msg(cmd, arg0, arg1, data)
    }

    /** What the TV's "Allow USB debugging?" question shows for this key: MD5 of the key, as AB:CD:... */
    fun fingerprint(): String {
        val blob = B64.decode(publicKey().substringBefore(' '))
        return java.security.MessageDigest.getInstance("MD5").digest(blob).joinToString(":") { String.format("%02X", it) }
    }

    /** The public key in adb's own format: little-endian words of n, R^2 mod n and -1/n mod 2^32, base64 encoded. */
    internal fun publicKey(): String {
        val k = key.public as RSAPublicKey
        val n = k.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(2048)
        val b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(64).putInt(n.mod(r32).modInverse(r32).negate().mod(r32).toInt())
        putWords(b, n)
        putWords(b, r.multiply(r).mod(n))
        b.putInt(k.publicExponent.toInt())
        return B64.encode(b.array()) + " tvremote@phone"
    }

    companion object {
        /** The "Allow USB debugging?" question sat 3 minutes with no answer: not a network fault (Errors.say, TV Setup's LinkFailure). */
        const val UNANSWERED = "the TV's allow question was not answered"
        private val ASKING = ConcurrentHashMap<String, Adb>()         // host:port -> the connection of this app whose question is on the TV
        private val PLAIN_ASKED = ConcurrentHashMap<String, Long>()   // host -> when 5555 was opened there for TV Setup (plainForSetup)
        private val ASKED = ConcurrentHashMap<String, Long>()         // host -> when this app last put its question on that TV
        private val LIVE = ConcurrentHashMap<String, Adb>()           // host -> this app's newest live connection there (connectOrShare)
        private val ONE = HashSet<String>()                           // TVs that take one client at a time (connectOrShare), in ONE_FILE
        private const val ONE_FILE = "adb-one-client.txt"
        private val KEY_LOCK = Any()                                  // a file lock is per process: this keeps threads apart

        /** Writes [f] through a file beside it and a rename: a reader sees the old file or the whole new one, never half. */
        private fun whole(f: File, bytes: ByteArray) {
            val tmp = File(f.parentFile, f.name + ".new")
            java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("cannot write ${f.name}") }
        }
        @Volatile private var holdMs = 0L
        @Volatile private var holdSet = false
        @Volatile private var holding = false
        @Volatile private var poked = false
        private val poke = Any()

        /** TV Setup: its first "Allow USB debugging?" waits up to [ms] (see [askOnce]), or until [askNow]. Once per process. */
        fun holdFirstAsk(ms: Long) { if (!holdSet) { holdSet = true; holdMs = ms } }

        /** A device with ADB is ready to answer the question for this app: ask it now. */
        fun askNow() = synchronized(poke) { poked = true; reask = true; (poke as Object).notifyAll() }
        @Volatile private var reask = false                           // askNow while asking: a helper just cleared the TV's screen, ask again

        /** "hold" (waiting to ask), "ask" (the question is on the TV) or "" (nothing asked: trusted, or not connecting). */
        fun askState(): String = if (holding) "hold" else if (ASKING.isNotEmpty()) "ask" else ""

        /** Seconds a write may wait for the TV to take it. */
        private const val WRITE_WAIT = 30
        private const val BUSY_OPEN_MS = 60_000L                      // open() on a TV that answers other streams meanwhile
        /** How far a file upload may run ahead of the TV's disk, and how often that is checked (see [writeTo]). */
        private const val AHEAD = 8L shl 20
        private const val PACE_STEP = 2L shl 20
        /** What we tell the TV about ourselves; adb's own is `host::features=shell_v2,cmd,...,delayed_ack`. TVR_ADB_BURST=0 leaves delayed_ack out. */
        private val BURST = System.getenv("TVR_ADB_BURST") != "0"
        // No closing NUL after a feature: adbd takes the payload as it is, and "delayed_ack\0" is no feature (the TV then refuses
        // every OPEN that asks for it). The old plain banner keeps its NUL.
        private val BANNER = (if (BURST) "host::features=delayed_ack" else "host::\u0000").toByteArray()
        /** How many bytes the TV may send a stream before we have read some (adb's own is 32 MiB; ours stay in memory till read). */
        private const val RECV_WINDOW = 1 shl 20
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val STLS = 0x534c5453
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private val EMPTY = ByteArray(0)
        private val EOF = ByteArray(0)                                // told apart from EMPTY by identity

        /** ASN.1 DigestInfo header for SHA-1: adbd treats the 20-byte token as a SHA-1 digest to be PKCS#1 v1.5 signed. */
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        /** Quotes a string for the TV's shell. */
        fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        private fun putWords(b: ByteBuffer, v: BigInteger) {
            for (i in 0 until 64) b.putInt(v.shiftRight(32 * i).toInt())
        }
    }
}
