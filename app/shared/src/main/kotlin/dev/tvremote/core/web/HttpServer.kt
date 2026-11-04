package dev.tvremote.core.web
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import dev.tvremote.core.WireLog

/**
 * A very small HTTP server (one request per connection; TLS when start() is given a context) for the pages the apps serve themselves.
 * It only knows how to read a request and write a reply; what the paths mean is up to the Handler.
 */
class HttpServer(private val handler: Handler) {
    class Request {
        var method = ""
        var path = ""
        val query: MutableMap<String, String> = HashMap()
        val headers: MutableMap<String, String> = HashMap()

        /** The request body: read it as a stream (uploads), or call json() for a small JSON body. */
        var body: InputStream = ByteArrayInputStream(ByteArray(0))
        var length = 0L
        var tls = false                                           // arrived over TLS (the secure port)
        var peer = ""                                             // the address it came from
        private var small: ByteArray? = null

        fun q(name: String): String = query[name] ?: ""

        /** The page's language (the cookie js/i18n.js sets, ?lang=, else the browser's): what words the answer is in (Words). */
        val lang: String by lazy {
            dev.tvremote.core.Words.pick(cookie("tvr-lang").ifEmpty { q("lang") }.ifEmpty { headers["accept-language"].orEmpty().substringBefore(',') })
        }

        fun json(max: Int = 65536): JSONObject = jsonOrNull(max) ?: JSONObject()

        /** null for a body that is not one JSON object (or too big): a caller that saves what it got must not save "nothing". */
        fun jsonOrNull(max: Int = 65536): JSONObject? {
            try {
                val bytes = small ?: run {
                    if (length > max) return null
                    val b = ByteArray(length.toInt())
                    var off = 0
                    val until = System.currentTimeMillis() + JSON_MS            // a whole small body, not each read: a byte every 29 s held a slot for hours
                    while (off < b.size) {
                        val n = body.read(b, off, b.size - off)
                        if (n < 0) break
                        off += n
                        if (off < b.size && System.currentTimeMillis() > until) return null
                    }
                    small = b
                    b
                }
                return JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                return null
            }
        }

        fun cookie(name: String): String {
            for (part in headers["cookie"].toString().split(";")) {
                val kv = part.trim().split("=", limit = 2)
                if (kv.size == 2 && kv[0] == name) return kv[1]
            }
            return ""
        }
    }

    /** Writes a long reply (a file) straight to the browser. */
    fun interface Body {
        @Throws(IOException::class)
        fun write(out: OutputStream)
    }

    class Response {
        var status = 200
        var type = "application/json; charset=utf-8"
        var body = ByteArray(0)
        var stream: Body? = null                                      // instead of body; length < 0 = unknown, sent until the connection closes
        var length = -1L
        /** A file the person asked for (a download, a video played from the TV): a paused or backgrounded player stops reading for minutes, so the stall limit is [fileStallMs], not [writeStallMs]. */
        var patient = false
        var sendBuffer = 0                                            // > 0: the socket's send buffer, kept small for a live stream (Feeds)
        val headers: MutableMap<String, String> = HashMap()

        companion object {
            fun json(status: Int, o: JSONObject) = Response().apply { this.status = status; body = bytes(o.toString()) }
            fun json(o: JSONObject) = json(200, o)
            fun ok() = of("application/json; charset=utf-8", bytes("{\"ok\":true}"))
            fun error(status: Int, message: String) = json(status, JSONObject().put("error", message))
            fun of(type: String, body: ByteArray) = Response().apply { this.type = type; this.body = body }
            fun stream(type: String, length: Long, body: Body) = Response().apply { this.type = type; this.length = length; stream = body }
        }
    }

    fun interface Handler {
        @Throws(Exception::class)
        fun handle(r: Request): Response
    }

    private val pool = Executors.newCachedThreadPool()
    // TV Setup listens on the whole network: a host that opens connections without end must not use up the TV's threads and
    // file descriptors (the scrcpy and ADB sockets need them too). A page uses a handful at once; video and sound one each.
    private val slots = Semaphore(48)
    // and one host alone may not take them all (a slow sender per connection): the page, its pictures, video and sound fit in 16.
    // This device itself (the computer app's page, the TV's Manage screen) is not counted.
    private val perPeer = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var secure = false

    /** localOnly: reachable from this device alone (127.0.0.1); otherwise from every network the device is on. [tls]: serve HTTPS. */
    @Throws(IOException::class)
    fun start(port: Int, localOnly: Boolean, tls: SSLContext? = null): Int {
        val s = if (tls == null) ServerSocket() else tls.serverSocketFactory.createServerSocket().also {
            val ok = setOf("TLSv1.2", "TLSv1.3")                  // nothing older, whatever the platform still offers
            (it as SSLServerSocket).enabledProtocols = it.supportedProtocols.filter { p -> p in ok }.toTypedArray()
        }
        secure = tls != null
        s.reuseAddress = !System.getProperty("os.name", "").startsWith("Windows")   // there it lets a second server take a live port
        s.bind(if (localOnly) InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port) else InetSocketAddress(port))
        server = s
        Thread({
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    val peer = c.inetAddress
                    val n = if (peer == null || trusted(peer)) null else (peer.hostAddress ?: "").let { k -> perPeer[k] ?: java.util.concurrent.atomic.AtomicInteger().let { perPeer.putIfAbsent(k, it) ?: it } }   // no computeIfAbsent: API 24
                    if (n != null && n.incrementAndGet() > PER_PEER) { n.decrementAndGet(); try { c.close() } catch (ignored: IOException) { }; continue }
                    if (!slots.tryAcquire()) { n?.decrementAndGet(); try { c.close() } catch (ignored: IOException) { }; continue }
                    pool.execute { try { serve(c) } finally { slots.release(); n?.decrementAndGet() } }
                } catch (e: IOException) {
                    if (s.isClosed) break
                    try { Thread.sleep(100) } catch (ignored: InterruptedException) { }   // out of descriptors: don't spin
                }
            }
        }, "http-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        val s = server
        server = null
        try { s?.close() } catch (ignored: IOException) { }
    }

    fun running(): Boolean = server != null

    private fun serve(c: Socket) {
        try {
            c.use { sock ->
                sock.soTimeout = 10000
                val r = read(sock.getInputStream())?.also { it.tls = secure; it.peer = sock.inetAddress?.hostAddress ?: "" }
                sock.soTimeout = 30000
                val out = if (r == null) Response.error(400, "bad request") else try {
                    handler.handle(r)
                } catch (e: Exception) {
                    // a bug, not a TV or a person saying no: where it happened goes in the log (About -> Send the log), the message alone said nothing
                    if (e is RuntimeException && e !is IllegalArgumentException && e !is IllegalStateException)
                        WireLog.all.add("·", "bug $e at " + e.stackTrace.take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
                    Response.error(500, e.message ?: e.javaClass.simpleName)
                }
                if (out.status >= 400 && r != null) WireLog.all.add("·", "api " + r.method + " " + r.path + " " + out.status + " " + String(out.body.take(300).toByteArray(), Charsets.UTF_8))
                val stream = out.stream
                val len = if (stream == null) out.body.size.toLong() else out.length
                val h = StringBuilder("HTTP/1.1 ").append(out.status).append(when (out.status) { 200 -> " OK"; 206 -> " Partial Content"; 302 -> " Found"; else -> " Error" }).append("\r\n")
                    .append("Content-Type: ").append(out.type).append("\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n")
                if (len >= 0) h.append("Content-Length: ").append(len).append("\r\n")
                for ((k, v) in out.headers) h.append(k).append(": ").append(v).append("\r\n")
                if (out.sendBuffer > 0) try { sock.sendBufferSize = out.sendBuffer } catch (ignored: Exception) { }
                val o = Stall(sock, out.patient)                               // a browser that stops reading must not hold this thread and slot for ever
                try {
                    o.write(bytes(h.append("\r\n").toString()))
                    if (stream != null) stream.write(o) else o.write(out.body)
                    o.flush()
                } finally { o.done() }
                // the body a handler did not want (POST /api/pair/start's "{}"), read before closing: a socket closed with bytes
                // unread sends a reset, and over a real network that throws away the reply the browser had not taken in yet
                try { (r?.body as? Left)?.let { sock.soTimeout = 2000; it.skipAll(30_000) } } catch (ignored: IOException) { }
                sock.shutdownOutput()
            }
        } catch (ignored: IOException) {
            // the browser went away
        } catch (e: Throwable) {
            // anything else from a reply being written ends this connection only: an uncaught exception would end the app
        }
    }

    /**
     * The reply's output: a write that makes no progress for [writeStallMs] (the peer's window stays shut) gets the socket closed,
     * so the write throws and the handler's own cleanup runs (Feeds.pump stops the viewer). Time between writes does not count
     * (a handler slow to make its next bytes), only a write call that is blocked; big writes go in 16 KB pieces so a slow
     * reader that keeps taking bytes is never cut.
     */
    private class Stall(private val sock: Socket, private val patient: Boolean = false) : OutputStream() {
        private val o = sock.getOutputStream()
        @Volatile var since = 0L                                      // nanoTime when the piece being written began, 0 = not writing
        @Volatile var cut = false

        init { watch(this) }

        override fun write(b: Int) = piece { o.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var i = 0
            while (i < len) { val n = minOf(16384, len - i); piece { o.write(b, off + i, n) }; i += n }
        }

        override fun flush() = piece { o.flush() }

        private inline fun piece(f: () -> Unit) {
            since = System.nanoTime().coerceAtLeast(1)
            try { f() } finally { since = 0 }
        }

        fun done() { live.remove(this) }

        fun check(now: Long) {
            val limit = (if (patient) fileStallMs else writeStallMs) * 1_000_000L
            val s = since
            if (s != 0L && now - s > limit) { cut = true; try { sock.close() } catch (ignored: Exception) { } }
        }
    }

    companion object {
        /** Connections one other host may have open at once. */
        internal const val PER_PEER = 16
        /** Not counted: this device (a test counts its own connections). */
        @Volatile internal var trusted: (java.net.InetAddress) -> Boolean = { it.isLoopbackAddress }
        /** How long a small JSON body may take to arrive, all of it. */
        @Volatile internal var JSON_MS = 10_000L

        /** A request another page made through the browser: its Origin is not this host, or the browser says the site differs. */
        fun crossOrigin(r: Request): Boolean {
            val origin = r.headers["origin"]
            if (origin != null && origin.substringAfter("//") != r.headers["host"]) return true
            val site = r.headers["sec-fetch-site"]
            return site != null && site != "same-origin" && site != "none"
        }

        /** How long one write may be blocked before the connection is cut (a variable so a test need not wait 30 s). */
        @Volatile internal var writeStallMs = 30_000L

        /** The same for a reply marked [Response.patient]. There is no Range support, so a cut file is lost, not resumed. */
        @Volatile internal var fileStallMs = 600_000L

        // not ConcurrentHashMap.newKeySet(): API 24, and minSdk is 21 (:shared:lintDebug NewApi, the companion would crash on Android 5/6)
        private val live: MutableSet<Stall> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Stall, Boolean>())
        private val armed = AtomicBoolean(false)
        // one thread for all connections, started on the first reply and gone again 10 s after the last one: nothing runs with no connection
        private val sweeper: ScheduledThreadPoolExecutor by lazy {
            ScheduledThreadPoolExecutor(1) { r -> Thread(r, "http-stall").apply { isDaemon = true } }.apply {
                setKeepAliveTime(10, TimeUnit.SECONDS); allowCoreThreadTimeOut(true)
            }
        }

        private fun watch(c: Stall) {
            live.add(c)
            if (armed.compareAndSet(false, true)) sweep()
        }

        private fun sweep() {
            val every = (writeStallMs / 4).coerceIn(50, 5000)
            sweeper.schedule({
                val now = System.nanoTime()
                for (c in live) c.check(now)
                if (live.isEmpty()) { armed.set(false); if (live.isNotEmpty() && armed.compareAndSet(false, true)) sweep() } else sweep()
            }, every, TimeUnit.MILLISECONDS)
        }

        /** Request line and headers; the body stays in the stream for the handler. */
        private fun read(input: InputStream): Request? {
            val head = ByteArrayOutputStream()
            val until = System.nanoTime() + 15_000_000_000L               // a head sent a byte at a time doesn't hold a thread
            var state = 0
            while (state < 4) {                                       // until the blank line
                val b = input.read()
                if (b < 0 || head.size() > 16384 || System.nanoTime() > until) return null
                head.write(b)
                state = when {
                    (b == '\r'.code && (state == 0 || state == 2)) || (b == '\n'.code && (state == 1 || state == 3)) -> state + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
            val lines = head.toString("ISO-8859-1").split("\r\n")
            val first = lines[0].split(" ")
            if (first.size < 2) return null
            val r = Request()
            r.method = first[0]
            val target = first[1]
            val q = target.indexOf('?')
            r.path = if (q < 0) target else target.substring(0, q)
            if (q >= 0) for (kv in target.substring(q + 1).split("&")) {
                val p = kv.split("=", limit = 2)
                try {
                    r.query[URLDecoder.decode(p[0], "UTF-8")] = if (p.size > 1) URLDecoder.decode(p[1], "UTF-8") else ""
                } catch (e: IllegalArgumentException) {                // "%zz": a bad request, not a reason to crash
                    return null
                }
            }
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) r.headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            }
            r.length = r.headers["content-length"]?.toLongOrNull() ?: 0
            if (r.length < 0) return null
            r.body = Left(input, r.length)
            return r
        }

        fun bytes(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
    }

    /** The request body: [left] bytes of [input] and no more, so what a handler leaves of it can be read away before closing. */
    private class Left(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b >= 0) left-- else left = 0
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n else left = 0
            return n
        }

        override fun available(): Int = minOf(input.available().toLong(), left).toInt()

        /** Reads away what is left, for [ms] at most: an upload refused before its first byte (ADB not allowed, no room) still
         *  gets its answer to the browser, and a sender that never stops does not hold the thread. */
        fun skipAll(ms: Long) {
            val until = System.currentTimeMillis() + ms
            val buf = ByteArray(65536)
            while (System.currentTimeMillis() < until && read(buf, 0, buf.size) > 0) { }
        }
    }
}
