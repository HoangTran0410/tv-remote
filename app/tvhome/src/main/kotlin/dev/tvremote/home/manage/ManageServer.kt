package dev.tvremote.home.manage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import dev.tvremote.home.wall.Videos
import dev.tvremote.home.wall.Walls
import dev.tvremote.home.apps.Apps
import dev.tvremote.home.HomeConfig

/**
 * TV Home's own settings page for a phone ("Manage from phone"): a tiny HTTP server on the LAN that runs only while the QR screen
 * ([ManageScreen], or the web-page background's) is open. It serves the same web UI as TV Setup's settings page (assets/homeweb, copied
 * from app/shared/web by the Gradle task packHomeWeb) and answers it from [HomeApi], so the rules (HomeConfig.clean / merge) are the TV's
 * own whoever asks. No ADB, no TV Setup, no :shared.
 *
 * Who gets in ([Access]): the QR's address carries a key that works while the QR is on screen; each open exchanges it for a session cookie and the address
 * is replaced by a plain "/" (the key never stays in the phone's history). Everyone else on the Wi-Fi gets 401 for every path. The server
 * stops after [Access.IDLE_MS] without an authorised request, or when [stop] is called (the owner closed the screen). A post needs
 * the X-Tvh header, which another site's page cannot send without a preflight this server does not answer.
 */
object ManageServer {
    private const val PORT = 8790
    /** Added to the QR's address: the page opens on its "paste a YouTube link" box (the web page reads location.hash === "#pin"; the redirect to "/" keeps it). */
    const val PASTE_HASH = "#pin"
    private const val MAX_JSON = 64 * 1024
    private const val MAX_PHOTO = 6_000_000                              // as Manage.kt's /api/home/photo: a 1920x1080 JPEG is 0.3-1.2 MB
    private val PKG = Regex("[A-Za-z][\\w]*(\\.[\\w]+)+")
    // what a phone sees without a session: a bare "401" told nobody what to do
    private const val GONE = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>TV Home</title>" +
        "<body style='font:17px/1.5 system-ui,sans-serif;max-width:30em;margin:3em auto;padding:0 16px'><h2>Mã QR đã hết hạn</h2>" +
        "<p>Mở lại màn mã QR trên TV rồi quét mã mới.</p>" +
        "<p lang=en>This QR code has expired. Open the QR screen on the TV again and scan the new code.</p>"
    private val STATIC = Regex("/(css|js|i18n)/[A-Za-z0-9_.-]+")        // the packed page, nothing else of the assets

    class Session(val url: String, val shown: String)

    /** The screen's ears; called on the main thread. */
    interface Listener {
        fun connected(phones: Int)                                     // a phone opened the page (the key was used)
        fun changed()                                                  // a setting or a photo came from a phone
        fun stopped()                                                  // idle for 10 minutes: the server is down
    }

    @Volatile var listener: Listener? = null
    private var sock: ServerSocket? = null
    private var access = Access()
    private var pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop(); listener?.stopped() }

    /** Starts it if it is not running, with a new key either way (so an old QR dies, not the phones in); null when there is no LAN address to be reached on. */
    @Synchronized fun start(c: Context): Session? {
        val ip = lan(c) ?: return null
        val fresh = sock == null
        if (fresh) {
            val s = try { ServerSocket(PORT) } catch (e: Exception) { try { ServerSocket(0) } catch (e2: Exception) { return null } }
            sock = s; pool = Executors.newCachedThreadPool(); access = Access()
            val app = c.applicationContext; val a = access
            pool.execute { accept(app, s, a) }
        }
        val key = access.issue(keep = !fresh)                            // a phone still on the page from the last QR stays ([release])
        touch()
        val shown = "$ip:${sock!!.localPort}"
        return Session("http://$shown/?k=$key", shown)
    }

    @Synchronized fun stop() {
        main.removeCallbacks(stopper)
        try { sock?.close() } catch (e: Exception) { }
        sock = null
        pool.shutdownNow()
    }

    /**
     * The QR screen closed (or Home went away): with a phone on the page the server stays for it, only the key dies, so the owner can
     * close the QR to look at Home while the phone still changes it; it stops after [Access.IDLE_MS] idle or the session's hour.
     * With nobody on it, it stops now.
     */
    @Synchronized fun release() {
        if (sock != null && access.connected() > 0) access.closeKey() else stop()
    }

    fun running() = sock != null

    /** The idle timer starts again: one timer on the main thread, no polling. */
    private fun touch() { main.removeCallbacks(stopper); main.postDelayed(stopper, access.idleMs) }

    private fun accept(c: Context, s: ServerSocket, a: Access) {
        while (!s.isClosed) {
            val k = try { s.accept() } catch (e: Exception) { return }
            try { pool.execute { handle(c, k, a) } } catch (e: Exception) { try { k.close() } catch (e2: Exception) { } }
        }
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val cookie: String, val tvh: Boolean, val length: Int, val body: InputStream)

    private fun handle(c: Context, k: Socket, a: Access) = k.use {
        try {
            k.soTimeout = 15_000
            val inp = java.io.BufferedInputStream(k.getInputStream())
            val out = k.getOutputStream()
            val req = parse(inp) ?: return@use reply(out, 400, "text/plain", "bad request")
            val session = Regex("(?:^|;\\s*)tvh=([A-Za-z0-9]+)").find(req.cookie)?.groupValues?.get(1)
            if (!a.check(session)) {
                // the key in the QR: used once, it becomes the cookie, and the address goes back to a plain "/"
                val id = if (req.method == "GET" && req.path == "/") a.redeem(req.query["k"]) else null
                if (id == null) return@use reply(out, 401, "text/html; charset=utf-8", GONE)
                // Lax, not Strict: the QR is opened by another app (camera, Lens), a cross-site start, so the browser left a Strict cookie off
                // the redirect to "/" and the phone got 401. A post still needs X-Tvh, which no other site can send
                reply(out, 302, "text/plain", "", "Location: /\r\nSet-Cookie: tvh=$id; Path=/; HttpOnly; SameSite=Lax; Max-Age=${Access.SESSION_MS / 1000}\r\n")
                touch(); main.post { listener?.connected(a.connected()) }
                return@use
            }
            touch()
            val post = req.method == "POST"
            if (post && !req.tvh) return@use reply(out, 403, "application/json", err("header"))
            when {
                !post && req.path == "/" -> reply(out, 200, "text/html; charset=utf-8", asset(c, "home.html") ?: return@use reply(out, 404, "text/plain", "not found"))
                !post && STATIC.matches(req.path) -> {
                    val b = asset(c, req.path.removePrefix("/"))
                    if (b == null) reply(out, 404, "text/plain", "not found") else reply(out, 200, type(req.path), b)
                }
                !post && req.path == "/h/config" -> json(out, onMain { HomeApi.snapshot(c, null, null, req.query["thumbs"] == "1") })
                !post && req.path == "/h/media" -> {                      // one of the person's own photos / videos, for a theme file to carry (js/home_media.js); streamed, a video is big
                    val id = req.query["id"] ?: ""
                    val f = if (HomeConfig.isPhoto(id)) Walls.file(c, id) else if (HomeConfig.isVideo(id)) Videos.file(c, id) else null
                    if (f == null || !f.isFile) return@use reply(out, 404, "application/json", err("not found"))
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: ${if (f.name.endsWith(".mp4")) "video/mp4" else "image/jpeg"}\r\nContent-Length: ${f.length()}\r\nCache-Control: no-store\r\n" +
                        "X-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").toByteArray())
                    f.inputStream().use { it.copyTo(out, 65536) }
                    out.flush()
                }
                !post && req.path == "/h/web/check" -> json(out, dev.tvremote.home.WebCheck.check(req.query["u"] ?: ""))   // the background page, tried from the TV itself (this thread, not main)
                post && req.path == "/h/config" -> {
                    val change = JSONObject(String(read(req, MAX_JSON), Charsets.UTF_8))
                    val f = HomeConfig.forward(change)
                    val set = JSONObject().also { o -> for (key in change.keys()) if (f.has(key)) o.put(key, f.get(key)) }   // as core/ontv/Home.kt sends it
                    json(out, onMain {
                        // the screensaver is a system setting only ADB can grant: without it the rest still applies and the switch stays as it was
                        try { HomeApi.snapshot(c, set, null, false) } catch (e: SecurityException) { HomeApi.snapshot(c, set.apply { remove("dream") }, null, false) }
                    }, true)
                }
                post && req.path == "/h/pin" -> {                         // a YouTube link from the phone's page: same as HomeReceiver's "pin"
                    val code = HomeApi.pin(c, JSONObject(String(read(req, MAX_JSON), Charsets.UTF_8)))
                    if (code == "ok") { Thread.sleep(800); json(out, onMain { HomeApi.snapshot(c, null, null, false) }, true) }   // the system confirms the pin through PinActivity first
                    else reply(out, if (code == "pin_bad_link") 400 else if (code == "pin_no_app") 409 else 502, "application/json", err(code))
                }
                post && req.path == "/h/link" -> json(out, HomeApi.link(c, JSONObject(String(read(req, MAX_JSON), Charsets.UTF_8))), false)   // what opens a pasted link (WebLink.preview)
                post && req.path == "/h/photo/drop" -> {
                    val id = JSONObject(String(read(req, MAX_JSON), Charsets.UTF_8)).optString("id")
                    if (!HomeConfig.isPhoto(id) && !HomeConfig.isVideo(id)) return@use reply(out, 400, "application/json", err("photo id"))
                    val snap = try { onMain { HomeApi.snapshot(c, null, id, false) } } catch (e: IllegalStateException) { return@use reply(out, 409, "application/json", err("inuse")) }
                    json(out, snap, true)
                }
                post && req.path == "/h/video" -> {                       // a video or GIF: streamed to a file, never held whole in memory
                    val name = (req.query["name"] ?: "").take(120)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in HomeConfig.VIDEO_EXT) return@use reply(out, 415, "application/json", err("type"))
                    val why = Videos.admit(c, req.length.toLong())
                    if (why != null) return@use reply(out, if (why == "size") 413 else 409, "application/json", err(why))
                    val staged = File(Videos.staging(c), "in" + System.currentTimeMillis().toString(36) + "." + ext)
                    try {
                        k.soTimeout = 30_000
                        staged.outputStream().use { f ->
                            val chunk = ByteArray(65536); var left = req.length
                            while (left > 0) { val n = req.body.read(chunk, 0, minOf(chunk.size, left)); if (n < 0) throw java.io.IOException("cut"); f.write(chunk, 0, n); left -= n }
                        }
                    } catch (e: Exception) { staged.delete(); throw e }
                    touch()
                    val code = onMain { HomeApi.addVideo(c, staged, name) }
                    if (code != "ok") reply(out, 409, "application/json", err(code))
                    else json(out, onMain { HomeApi.snapshot(c, null, null, false) }, true)
                }
                post && req.path == "/h/photo" -> {
                    val jpeg = read(req, MAX_PHOTO)
                    val code = onMain { HomeApi.savePhoto(c, jpeg) }
                    if (code != "ok") reply(out, if (code == "full") 409 else 415, "application/json", err(code))
                    else json(out, onMain { HomeApi.snapshot(c, null, null, false) }, true)
                }
                post && req.path == "/h/apppic" -> {                      // the person's own picture for an app's tile (HomeConfig "pics"): a JPEG the page made 16:9
                    val target = req.query["pkg"] ?: ""
                    val jpeg = read(req, dev.tvremote.home.HomeConfig.PIC_BYTES * 6)
                    val code = onMain { HomeApi.saveAppPic(c, target, jpeg) }
                    if (code != "ok") reply(out, if (code == "full") 409 else if (code == "target") 400 else 415, "application/json", err(code))
                    else json(out, onMain { HomeApi.snapshot(c, null, null, false) }, true)
                }
                !post && req.path == "/h/app/meta" -> {
                    val app = req.query["pkg"]?.takeIf { it.length <= 200 && PKG.matches(it) }?.let { Apps.one(c, it) }
                    if (app == null) reply(out, 404, "application/json", err("not found"))
                    else reply(out, 200, "application/json", JSONObject().put("label", app.label).put("icon", app.info != null).toString().toByteArray())
                }
                !post && req.path == "/h/app/icon" -> {
                    val png = req.query["pkg"]?.takeIf { it.length <= 200 && PKG.matches(it) }?.let { icon(c, it) }
                    if (png == null) reply(out, 404, "text/plain", "not found") else reply(out, 200, "image/png", png, "Cache-Control: private, max-age=3600\r\n")
                }
                else -> reply(out, 404, "text/plain", "not found")
            }
        } catch (e: Exception) { }
    }

    /** Answers a call that changed something: the screen is told, too. */
    private fun json(out: java.io.OutputStream, o: JSONObject, changed: Boolean = false) {
        reply(out, 200, "application/json", o.put("installed", true).put("home", true).toString().toByteArray())
        if (changed) main.post { listener?.changed() }
    }

    private fun err(code: String) = JSONObject().put("error", code).toString()

    /** The request's body, at most [max] bytes (413-ish: the caller's exception ends the connection); exactly its Content-Length. */
    private fun read(r: Req, max: Int): ByteArray {
        if (r.length <= 0 || r.length > max) throw IllegalArgumentException("body")
        val buf = ByteArrayOutputStream(r.length)
        val chunk = ByteArray(16384); var got = 0
        while (got < r.length) { val n = r.body.read(chunk, 0, minOf(chunk.size, r.length - got)); if (n < 0) break; buf.write(chunk, 0, n); got += n }
        if (got != r.length) throw IllegalArgumentException("short")
        return buf.toByteArray()
    }

    /** Runs [f] on the main thread (Store and the screen live there) and waits for it. */
    private fun <T> onMain(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post { result = runCatching(f); done.countDown() }
        if (!done.await(10, TimeUnit.SECONDS)) throw java.io.IOException("busy")
        return result!!.getOrThrow()
    }

    private fun asset(c: Context, name: String): ByteArray? = try { c.assets.open("homeweb/$name").use { it.readBytes() } } catch (e: Exception) { null }

    private fun type(path: String) = when (path.substringAfterLast('.')) {
        "css" -> "text/css; charset=utf-8"; "js" -> "application/javascript; charset=utf-8"; "json" -> "application/json; charset=utf-8"; else -> "application/octet-stream"
    }

    /** An app's icon as a 96 px PNG; only for an app TV Home itself lists (a package name alone opens nothing else). */
    private fun icon(c: Context, pkg: String): ByteArray? = try {
        val d = Apps.one(c, pkg)?.info?.loadIcon(c.packageManager) ?: return null
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96); d.draw(Canvas(b))
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { b.recycle() }
    } catch (e: Throwable) { null }

    private fun parse(inp: InputStream): Req? {
        fun line(): String? {
            val sb = StringBuilder()
            while (sb.length < 8192) { val b = inp.read(); if (b < 0) return if (sb.isEmpty()) null else sb.toString(); if (b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }
            return sb.toString()
        }
        val first = line()?.split(' ') ?: return null
        if (first.size < 2) return null
        var len = 0; var cookie = ""; var tvh = false
        for (i in 0 until 60) {
            val h = line() ?: return null
            if (h.isEmpty()) break
            val p = h.indexOf(':'); if (p < 0) continue
            val n = h.substring(0, p).trim().lowercase(); val v = h.substring(p + 1).trim()
            if (n == "content-length") len = v.toIntOrNull() ?: return null
            if (n == "cookie") cookie = v
            if (n == "x-tvh") tvh = v == "1"
        }
        val target = first[1]
        val q = target.indexOf('?')
        val query = HashMap<String, String>()
        if (q >= 0) for (kv in target.substring(q + 1).split('&')) { val e = kv.indexOf('='); if (e > 0) query[kv.substring(0, e)] = java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8") }
        return Req(first[0], if (q >= 0) target.substring(0, q) else target, query, cookie, tvh, len, inp)
    }

    private fun reply(out: java.io.OutputStream, code: Int, type: String, body: String, extra: String = "") = reply(out, code, type, body.toByteArray(), extra)
    private fun reply(out: java.io.OutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        val name = when (code) { 200 -> "OK"; 302 -> "Found"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; else -> "Error" }
        val head = "HTTP/1.1 $code $name\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" + (if (extra.contains("Cache-Control")) "" else "Cache-Control: no-store\r\n") +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n$extra\r\n"
        out.write(head.toByteArray()); out.write(body); out.flush()
    }

    /** The TV's address on the LAN (a cable wins over Wi-Fi; a VPN is skipped), as Widgets reads it; null when there is none. */
    fun lan(c: Context): String? {
        var ip: String? = null
        try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION") for (n in cm.allNetworks) {
                val cap = cm.getNetworkCapabilities(n) ?: continue
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val v4 = cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: continue
                if (ip == null || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ip = (v4 as InetAddress).hostAddress
            }
        } catch (e: Exception) { }
        return ip
    }
}
