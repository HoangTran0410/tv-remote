package dev.tvremote.phone

import dev.tvremote.core.T
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.web.HttpServer
import dev.tvremote.core.manage.Manage
import dev.tvremote.core.ontv.RemoteLabels
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.regex.Pattern
import dev.tvremote.core.scenes.Scenes
import org.json.JSONArray
import org.json.JSONObject

/**
 * The same page the TV app serves, run inside this app: a server on 127.0.0.1 that only this app's web view can use
 * (it must present a random cookie), talking to the TV over its own ADB connection.
 */
internal class Managing(private val a: MainActivity, root: FrameLayout) : HttpServer.Handler {
    private val view: LinearLayout = a.col()
    val shown get() = view.visibility == View.VISIBLE
    private var web: WebView
    private val token = UUID.randomUUID().toString().replace("-", "")
    private var http: HttpServer? = null
    private var port = 0
    private var link: AdbLink? = null
    private var manage: Manage? = null
    private var labels: RemoteLabels? = null
    private var forHost: String? = null
    private var lastTab: String? = null                               // open's tab and "then", replayed when the WebView is rebuilt
    private var lastThen: String? = null
    private var loaded = false                                        // the page is loaded for [forHost]
    private var picking: ValueCallback<Array<Uri>>? = null
    private var destroyed = false
    private var pending: String? = null                               // what to do on the page once it has loaded (open's "then")
    private var coverB = 0                                            // CSS px of the page's bottom under the small player (Pip.kt)
    private val webPage by lazy { dev.tvremote.core.web.WebPage({ a.assets.open(it) }, "phone", a.version() + "-" + (try { a.packageManager.getPackageInfo(a.packageName, 0).lastUpdateTime / 1000 } catch (e: Exception) { 0L })) }

    private fun makeWeb(): WebView {
        val web = WebView(a)
        web.setBackgroundColor(BG)
        @Suppress("SetJavaScriptEnabled")
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {            // the page's own links stay inside; the rest (Tailscale sign-in) go to the browser
            override fun shouldOverrideUrlLoading(w: WebView?, req: android.webkit.WebResourceRequest?): Boolean {
                val u = req?.url ?: return false
                if (u.host == "127.0.0.1") return false
                a.web(u.toString())
                return true
            }
            override fun onRenderProcessGone(w: WebView?, d: android.webkit.RenderProcessGoneDetail?): Boolean {   // killed (low memory) or crashed: the default answer kills the whole app
                if (w === this@Managing.web) a.ui.post { rebuild() }
                return true
            }
            override fun onPageFinished(w: WebView?, url: String?) { pending?.let { pending = null; this@Managing.web.evaluateJavascript(it, null) }; cover(coverB, true) }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                picking?.onReceiveValue(null)
                picking = cb
                try {
                    @Suppress("DEPRECATION")
                    a.startActivityForResult(params!!.createIntent(), PICK_FILE)
                } catch (e: Exception) {
                    picking = null
                    return false
                }
                return true
            }
        }
        web.setDownloadListener { url, _, disposition, _, _ -> saveToPhone(url, disposition) }
        return web
    }

    /** The page's renderer is gone and its WebView is dead (API 26+): a fresh one, the page loaded again when it was on screen. */
    private fun rebuild() {
        if (destroyed) return                                         // the post came after the activity let go of this
        val old = web
        val at = view.indexOfChild(old)
        if (at < 0) return
        view.removeViewAt(at)
        try { old.destroy() } catch (e: Exception) { }
        web = makeWeb()
        view.addView(web, at, LinearLayout.LayoutParams(-1, 0, 1f))
        loaded = false
        pending = null
        try { picking?.onReceiveValue(null) } catch (e: Exception) { }   // the dead page's file chooser would wait for ever
        picking = null
        if (view.visibility == View.VISIBLE) open(forHost, lastTab, lastThen)   // the tab and the action it was opened with come back
    }

    /** The small player floats over the page's bottom: its toast, error box and jobs card rise above it (`--cover-b`, css/page.css). */
    fun cover(px: Int, again: Boolean = false) {
        if (px == coverB && !again) return
        coverB = px
        web.evaluateJavascript("document.documentElement.style.setProperty('--cover-b','${px}px')", null)
    }

    init {
        view.setBackgroundColor(BG)
        view.isClickable = true
        val title = a.label(T("managing.manage_the_tv"), 20, TEXT)
        title.typeface = Typeface.DEFAULT_BOLD
        val top = LinearLayout(a)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(a.dp(16), a.dp(10), a.dp(12), a.dp(6))
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val done = a.icon("@close", T("common.close"), BTN)
        done.setOnClickListener { hide() }
        top.addView(done, LinearLayout.LayoutParams(a.dp(48), a.dp(40)))
        view.addView(top)
        web = makeWeb()
        view.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        view.visibility = View.GONE
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * The TV's ADB connection dialled ahead, once the remote's own session is up (its key let in already): opening Manage
     * then waits only for the page, not for a connection (a second or more on a slow network).
     */
    fun prepare(tvHost: String) {
        try { if (tvHost != forHost) bind(tvHost); link?.state() } catch (e: Exception) { }
    }

    private fun bind(tvHost: String) {
        link?.close()
        val l = AdbLink(a.filesDir, tvHost)
        link = l
        val names = RemoteLabels(l, { a.assets.open("tvr-helper.jar").use { it.readBytes() } }, File(a.cacheDir, "meta/" + tvHost.replace(Regex("[^\\w.-]"), "_")))   // app names and pictures, asked from the TV itself, kept per TV
        labels = names
        dev.tvremote.core.ontv.Launchers.useTestFile(a.getExternalFilesDir(null))   // a test list, when one was put there
        dev.tvremote.core.ontv.Launchers.dir = java.io.File(a.cacheDir, "launchers")   // the gallery's list and pictures kept: it opens without waiting on the network
        manage = Manage(l, names, a.cacheDir) { a.assets.open("tvr-helper.jar").use { it.readBytes() } }
            .apply { canPair = true; onWork = { w -> a.ui.post { a.working(w) } } }
        forHost = tvHost
        loaded = false
    }

    /** Shows the page for [tvHost], on [tab] when one is named ("auto", "apps"...). */
    fun open(tvHost: String?, tab: String? = null, then: String? = null) {
        if (tvHost == null) return
        lastTab = tab; lastThen = then
        try {
            if (http == null) {
                val server = HttpServer(this)
                http = server
                val last = a.prefs.getInt("managePort", 0)               // the same port as last time: the page's origin, so what it keeps (localStorage: Notify's corner, the tab) stays
                port = (if (last > 0) try { server.start(last, true) } catch (e: java.io.IOException) { 0 } else 0).takeIf { it > 0 } ?: server.start(0, true)
                a.prefs.edit().putInt("managePort", port).apply()
            }
            if (tvHost != forHost) bind(tvHost)
            if (!loaded) {
                loaded = true
                val origin = "http://127.0.0.1:$port"
                CookieManager.getInstance().setCookie(origin, "tvr=$token")
                pending = then
                web.loadUrl(origin + "/?embed=1&lang=" + dev.tvremote.core.Words.lang + "&theme=" + (if (LIGHT) "light" else "dark") +   // the resolved choice: a WebView reports the system mode unreliably
                     (if (tab != null) "#$tab" else ""))
            } else {
                link?.check()                                         // the same TV again: make sure its connection still answers
                if (tab != null) web.evaluateJavascript("showTab('" + tab.filter { it.isLetter() } + "')", null)
                if (then != null) web.evaluateJavascript(then, null)
            }
            view.visibility = View.VISIBLE
            web.onResume()
        } catch (e: Exception) {
            a.message(T("managing.could_not_open_the", "error" to e.message), true)
        }
    }

    /** The app is on screen again (back from another app): a connection that died meanwhile is dialled again before the page asks. */
    fun resume() {
        if (view.visibility == View.VISIBLE) { link?.check(); web.evaluateJavascript("typeof refresh === 'function' && refresh()", null) }
    }

    fun back(): Boolean {
        if (view.visibility != View.VISIBLE) return false
        hide()
        return true
    }

    /** Back to the remote, which then shows what came of a TV Setup update started here (SetupBanner.kt). */
    private fun hide() {
        view.visibility = View.GONE
        web.onPause()                                                 // a GONE WebView still says document.hidden = false: its polls (Health every 5 s) would go on asking the TV
        web.evaluateJavascript("(function(){ try { return localStorage.getItem('setupUndo') || '' } catch (e) { return '' } })()") { v ->
            val seen = v?.trim('"') ?: ""                             // "Got it" on the page counts on the remote too
            if (seen.isNotEmpty()) a.prefs.edit().putString("setupUndo", seen).apply()
            a.setupAgain()
        }
    }

    /** What the page is doing on the TV (Manage.working), "" when nothing. */
    fun working(): String = manage?.working() ?: ""
    fun progress(): Int = manage?.progress() ?: -1

    /** Leaving this TV: drop its ADB connection; the next TV gets a fresh page. */
    fun close() {
        manage?.onWork = null
        a.working("")
        view.visibility = View.GONE
        web.onPause()
        link?.close()
        link = null
        labels = null
        manage = null
        forHost = null
        loaded = false
    }

    fun destroy() {
        destroyed = true
        close()
        http?.stop()
        web.destroy()
    }

    fun picked(result: Int, data: Intent?) {
        val cb = picking ?: return
        cb.onReceiveValue(pickedUris(result, data))
        picking = null
    }

    // parseResult reads only getData(): a picker that answers a multiple pick with ClipData alone (a POCO's gallery) gave the
    // page null, and the page heard nothing, not even an error
    private fun pickedUris(result: Int, data: Intent?): Array<Uri>? {
        if (result != Activity.RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
        return WebChromeClient.FileChooserParams.parseResult(result, data)
    }

    override fun handle(r: HttpServer.Request): HttpServer.Response {
        if (token != r.cookie("tvr")) return HttpServer.Response.error(403, "this page belongs to the TV Remote app")
        webPage.serve(r.path, r.headers["accept-encoding"].orEmpty())?.let { return it }
        if (r.path == "/api/state" || r.path == "/api/about")      // the page only needs to know it may show itself, and for which TV
            return HttpServer.Response.json(JSONObject().put("core", "phone").put("host", forHost ?: "").put("name", a.name(forHost ?: "")).put("connected", true).put("is_on", true).put("paired", true)
                .put("adb", JSONObject().put("state", manage?.adbState() ?: "unknown")).put("app_name", "TV Remote").put("version", a.version()).put("released", a.released()))
        if (r.path == "/api/support/app")                              // About: let support look at this phone app (PhoneSupport)
            return HttpServer.Response.json(PhoneSupport.act(a, if (r.method == "POST") r.json().optString("act") else "state"))
        if (r.path == "/api/phone/back" && r.method == "POST") {       // a dialog opened from the remote (AdbHelp.kt adbSteps) was closed: straight back to it
            a.ui.post { back() }
            return HttpServer.Response.json(JSONObject())
        }
        // this phone's scenes (the widget, tiles and shortcuts use them too); "Export" lands in the phone's Download folder
        Scenes.Routes({ SceneStore.all(a) }, { SceneStore.save(a, it) }, { "adb: not connected" }) { link?.let { l -> Scenes.Shell({ l.get().shell(it) }) } }.also { rt -> link?.let { l -> rt.pin = { sc -> Scenes.pinThroughSetup({ l.get().shell(it) }, sc) }; rt.publish = { sl -> Scenes.publishThroughSetup({ l.get().shell(it) }, sl) } } }.handle(r)?.let { return it }
        if (r.path == "/api/watchers") {                               // the TV's own count: this phone's remote screen and PiP too
            val c = try { link?.get()?.shell(dev.tvremote.core.picture.Watchers.CMD)?.let { dev.tvremote.core.picture.Watchers.parse(it) } } catch (e: Exception) { null }
            return HttpServer.Response.json(if (c == null) JSONObject() else JSONObject().put("watch", c.watch).put("listen", c.listen))
        }
        if (r.path == "/api/app/icon") {
            val png = labels?.icon(r.query["pkg"].toString()) ?: return HttpServer.Response.error(404, "no icon")
            val icon = HttpServer.Response.of("image/png", png)
            icon.headers["Cache-Control"] = "max-age=86400"
            return icon
        }
        return manage?.handle(r) ?: HttpServer.Response.error(404, "not found")
    }

    private fun size(n: Long): String = if (n >= 1 shl 20) String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0) else "${n / 1024} KB"

    /** "Download to this device": copy the file from our own server into the phone's Download folder. */
    private fun saveToPhone(url: String, disposition: String?) {
        var fileName = "file"
        try {
            val m = Pattern.compile("filename\\*=UTF-8''([^;]+)").matcher(disposition.toString())
            if (m.find()) fileName = URLDecoder.decode(m.group(1), "UTF-8")
        } catch (ignored: Exception) {
        }
        // its progress goes to the page's list of uploads and installs (window.nativeJob), the one place a person looks
        val id = "dl" + System.nanoTime()
        fun report(status: String, pct: Int, msg: String) {
            val j = org.json.JSONObject().put("id", id).put("label", fileName).put("status", status).put("pct", pct).put("msg", msg)
            a.ui.post { web.evaluateJavascript("window.nativeJob && nativeJob($j)", null) }
        }
        report("running", -1, T("managing.downloading_to_the_phone"))
        Thread {
            var result: String
            var ok = false
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("Cookie", "tvr=$token")
                val out: OutputStream
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = ContentValues()
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    out = a.contentResolver.openOutputStream(a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!)!!
                    result = T("managing.saved_to_download", "name" to fileName)
                } else {
                    val f = File(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                    out = FileOutputStream(f)
                    result = T("pc.saved_file", "path" to f.absolutePath)
                }
                val total = c.getHeaderField("content-length")?.toLongOrNull() ?: -1L
                out.use { o -> c.inputStream.use { input ->
                    val b = ByteArray(65536)
                    var done = 0L
                    var shown = 0L
                    while (true) {
                        val n = input.read(b)
                        if (n < 0) break
                        o.write(b, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - shown > 300) {                      // a few times a second is enough to look alive
                            shown = now
                            report("running", if (total > 0) (done * 100 / total).toInt() else -1,
                                size(done) + if (total > 0) " / " + size(total) else "")
                        }
                    }
                } }
                ok = true
            } catch (e: Exception) {
                result = T("managing.download_failed", "error" to e.message)
            }
            report(if (ok) "done" else "error", 100, result)
        }.start()
    }
}
