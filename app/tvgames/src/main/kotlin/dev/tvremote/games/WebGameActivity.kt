package dev.tvremote.games

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.tvremote.core.web.Qr
import dev.tvremote.tvui.dp
import dev.tvremote.tvui.label
import dev.tvremote.tvui.menuButton
import org.json.JSONArray
import org.json.JSONObject

/**
 * A web game: a page in a full-screen WebView. The phones drive it three ways at once (WEBGAMES.md):
 *  - keyboard, player 1 only: D-pad = arrows, A = Z, B = X, X = A, Y = S, L = Q, R = W, L2 = E, R2 = R, C = C, D = V, Start = Enter, Select = Shift
 *    (the TV remote's own keys reach the page as they are: arrows, OK = Enter);
 *  - the Gamepad API: navigator.getGamepads() gives one standard-mapping pad per phone (tvpad.js);
 *  - window.tvpad: join / leave / button / text events, layout(), vibrate(), say().
 * The bridge and the shim exist only for the page the game was opened with (its origin); anything else the page navigates to gets neither.
 */
class WebGameActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var server: GameServer
    private lateinit var cards: GameCards
    private var origin = ""
    @Volatile private var pageOrigin = ""

    override fun attachBaseContext(b: android.content.Context) = super.attachBaseContext(Lang.wrap(b))
    private var errorRetry: View? = null
    private var errorCard: View? = null                         // our own card for a page that cannot load (offline, 404, too slow), over the lobby and the WebView
    private val timeout = Runnable { if (!pageDone) { Log.w(TAG, "no page in ${LOAD_MS / 1000} s"); failCard(R.string.web_fail_net) } }
    private lateinit var loader: LinearLayout                      // a spinner and "Loading the game… n %" over the page until it is in
    private lateinit var loadTv: TextView
    private var loadPct = 0
    private var loadAt = 0L
    private var gone = false                                       // the WebView's renderer was killed (low memory): Retry starts over
    private val LOADER_BG = 0xFF0B0D12.toInt()
    private val loadTick = object : Runnable { override fun run() { if (!pageDone && errorCard == null) { showLoading(); web.postDelayed(this, 1000) } } }
    private val pageIn = Runnable { if (!pageDone) loaded() }      // shown but a picture or script still coming: let it be played
    private var pageDone = false                                   // the lobby freezes the page only once it has loaded, or it would never get to draw
    private var players = listOf<Int>()
    private var names = mapOf<Int, String>()
    private val nameOf = GameServer.KEYS.entries.associate { it.value to it.key }
    private val keyOf = mapOf(
        "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN, "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "A" to KeyEvent.KEYCODE_Z, "B" to KeyEvent.KEYCODE_X, "X" to KeyEvent.KEYCODE_A, "Y" to KeyEvent.KEYCODE_S,
        "L" to KeyEvent.KEYCODE_Q, "R" to KeyEvent.KEYCODE_W, "C" to KeyEvent.KEYCODE_C, "D" to KeyEvent.KEYCODE_V,
        "L2" to KeyEvent.KEYCODE_E, "R2" to KeyEvent.KEYCODE_R, "START" to KeyEvent.KEYCODE_ENTER, "SELECT" to KeyEvent.KEYCODE_SHIFT_LEFT,
    )

    // no ":-1" for a link without a port: that crashed the origin rule
    private fun originOf(u: String): String = try { val x = java.net.URI(u); "${x.scheme}://${x.host}${if (x.port > 0) ":${x.port}" else ""}" } catch (e: Exception) { "" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name") ?: "Web"
        val link = intent.getStringExtra("url") ?: return finish()
        if (!dev.tvremote.tvui.NoWebView.ok(this)) { dev.tvremote.tvui.NoWebView.show(this) { recreate() }; return }   // switched off on this TV (2da5cf8b): TV Setup turns it back on
        server = GameServer.acquire(this)
        val builtin = link.startsWith("builtin:")
        val url = if (builtin) "${server.base()}/webgames/${link.removePrefix("builtin:")}/?k=${server.token}" else link
        origin = originOf(url)
        val shim = assets.open("tvpad.js").use { String(it.readBytes()) }

        web = dev.tvremote.tvui.WebEnd.made(WebView(this)).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false; allowContentAccess = false; mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            addJavascriptInterface(Bridge(), "TVPadNative")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, p: Int) {
                    if (pageDone || errorCard != null) return
                    if (p >= 100) { loaded(); web.removeCallbacks(timeout); return }   // onPageFinished came 3-4 s after 100 % on a box: the page is there
                    loadPct = p; showLoading()
                }
                override fun onConsoleMessage(m: ConsoleMessage): Boolean { Log.d(TAG, "console ${m.messageLevel()}: ${m.message()} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})"); return true }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) { if (errorCard != null) return; loaded(); web.removeCallbacks(timeout) }
                override fun onPageCommitVisible(v: WebView, u: String) { web.removeCallbacks(timeout); web.postDelayed(pageIn, 5000) }   // the page answered: a slow picture or script is no network failure
                @android.annotation.TargetApi(26)   // the system calls this only on API 26+
                override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {   // low memory killed it: a card, not the app closing
                    Log.w(TAG, "renderer gone, crash=${d.didCrash()}")
                    failCard(R.string.web_fail_gone); gone = true
                    dev.tvremote.tvui.WebEnd.end(web)
                    return true
                }
                @android.annotation.TargetApi(23)   // the system calls this only on API 23+
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) { if (r.isForMainFrame) { Log.w(TAG, "page error ${e.errorCode} ${e.description} ${r.url}"); failCard(R.string.web_fail_net) } }
                override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                    if (r.isForMainFrame) Log.w(TAG, "page http ${e.statusCode} ${r.url}")
                    if (r.isForMainFrame) failCard(if (e.statusCode == 404 || e.statusCode == 410) R.string.web_fail_404 else R.string.web_fail_net)
                }
                override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                    pageOrigin = originOf(u)
                    padKeys = true                                         // a new page: keys again until it says it reads the pads
                    // no document-start support (old WebView): the shim goes in as the page starts, which is early enough for most pages
                    if (pageOrigin == origin && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) v.evaluateJavascript(shim, null)
                }
            }
            // WebViewCompat can limit the script to the game's origin; the bridge itself also checks pageOrigin on every call
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(this, shim, setOf(origin))
            isFocusable = true; isFocusableInTouchMode = true
        }
        loadTv = label("", 26f)
        loader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setBackgroundColor(LOADER_BG); isClickable = true
            addView(android.widget.ProgressBar(this@WebGameActivity, null, android.R.attr.progressBarStyleLarge), LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(20) })
            addView(label(name, 34f))
            addView(loadTv)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(web, FrameLayout.LayoutParams(-1, -1)); addView(loader, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        cards = GameCards(this, root, server, name, if (builtin) null else url,
            onPause = { p -> if (p) { if (pageDone) { web.onPause(); web.pauseTimers() } } else { web.onResume(); web.resumeTimers(); web.requestFocus() } },
            onSave = null, onLoad = null, onQuit = { finish() }, alone = false, quiet = link == "builtin:padtest",
            withLobby = Catalog.lobbyOn(this, link), kind = "Web", art = lobbyArt(link, url), maxPlayers = counts.first, minPlayers = counts.second)
        this.url = url; this.root = root
        if (intent.getBooleanExtra("fresh", false)) fresh()
        web.loadUrl(url); loading()
        web.postDelayed(timeout, LOAD_MS)                         // no page at all in time: the same card as offline
    }

    private var url = ""
    private lateinit var root: FrameLayout

    /** The page cannot be had: a card with the reason, Retry and Back (D-pad focus), instead of the WebView's own error page. */
    private fun failCard(msg: Int) {
        if (errorCard != null || isFinishing) return
        web.removeCallbacks(timeout); web.removeCallbacks(loadTick); web.removeCallbacks(pageIn)
        loader.visibility = View.GONE; if (::cards.isInitialized) cards.loading("")
        if (::cards.isInitialized) cards.close()                    // a lobby under the card must not start anything
        web.stopLoading(); web.visibility = View.INVISIBLE
        val retry = menuButton(getString(R.string.retry), 300, 58, 24f) { retryLoad() }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setBackgroundColor(0xFF0B0D12.toInt()); isClickable = true
            addView(label(getString(R.string.web_fail_title), 34f))
            addView(label(getString(msg), 22f, 0xFFAAB0C0.toInt()).apply { setPadding(dp(60), dp(10), dp(60), dp(18)) })
            addView(retry); addView(menuButton(getString(R.string.back), 300, 58, 24f) { finish() })
        }
        errorRetry = retry; errorCard = col; root.addView(col, FrameLayout.LayoutParams(-1, -1)); retry.requestFocus()
    }

    private fun retryLoad() {
        if (gone) { recreate(); return }                              // a new WebView: the old one is dead
        errorCard?.let { root.removeView(it) }; errorCard = null
        pageDone = false; web.visibility = View.VISIBLE; web.loadUrl(url); loading()
        web.removeCallbacks(timeout); web.postDelayed(timeout, LOAD_MS)
        if (::cards.isInitialized) cards.reopen()                       // the failure closed the lobby: OK there must start the game again
    }

    private fun loading() { loadPct = 0; loadAt = System.currentTimeMillis(); loader.visibility = View.VISIBLE; web.removeCallbacks(loadTick); web.post(loadTick) }
    private fun showLoading() {
        val slow = System.currentTimeMillis() - loadAt > 8000            // past 8 s say why it takes long, so it does not look stuck
        val t = getString(if (slow) R.string.loading_slow else R.string.loading_game, loadPct)
        loadTv.text = t; if (::cards.isInitialized) cards.loading(t)
    }
    private fun loaded() {
        pageDone = true; web.removeCallbacks(loadTick); web.removeCallbacks(pageIn)
        loader.visibility = View.GONE; if (::cards.isInitialized) cards.loading("")
        if (::cards.isInitialized && cards.lobby) { web.onPause(); web.pauseTimers() }   // the lobby keeps it frozen until the game starts
    }

    private val counts by lazy { Catalog.players(this, null, intent.getStringExtra("url"), 4) }

    /** The picture the library tile has (same cache key), for the lobby. */
    private fun lobbyArt(link: String, url: String) = when (link) {
        "builtin:padtest" -> Thumbs.Src("w:$link", asset = "thumbs/padtest.jpg")
        else -> if (link.startsWith("builtin:")) null else Catalog.entryOf(this, null, link)?.optString("img")?.takeIf { it.isNotEmpty() }?.let { Thumbs.Src("w:$link", img = it) } ?: Thumbs.Src("w:$link", page = url)   // as the library tile
    }

    override fun onResume() {
        super.onResume()
        if (!::server.isInitialized) return
        server.inGame = true; server.webMode = true
        server.buttons = listOf("A", "B", "START", "SELECT")
        server.clearLayouts(); server.setSystem(intent.getStringExtra("pad")?.takeIf { it.isNotEmpty() } ?: "web"); server.analog = true
        server.onAxis = { port, x, y -> runOnUiThread { axis(port, x, y) } }
        server.onKey = { port, action, code -> runOnUiThread { input(port, action == KeyEvent.ACTION_DOWN, nameOf[code] ?: return@runOnUiThread) } }
        server.onKeyLat = { port, action, code, lat -> runOnUiThread { input(port, action == KeyEvent.ACTION_DOWN, nameOf[code] ?: return@runOnUiThread, lat) } }
        server.onPing = { p, ms -> runOnUiThread { cards.setPing(p, ms); js("__tvpadIn('ping',$p,$ms)") } }   // the cards' labels and the page
        for (p in 1..4) server.pingOf(p).let { if (it >= 0) js("__tvpadIn('ping',$p,$it)") }
        server.onPointer = { p, dx, dy, c -> runOnUiThread { js(if (c) "__tvpadIn('pointer',$p,'c')" else "__tvpadIn('pointer',$p,$dx,$dy)") } }   // the pad's pointer layout (touchpad)
        server.onMotion = { p, v -> runOnUiThread { js("__tvpadIn('motion',$p,[${v.joinToString(",")}])") } }
        server.onText = { p, t -> runOnUiThread { js("__tvpadIn('text',$p,${JSONObject.quote(t)})") } }
        server.onPlayers = { l -> runOnUiThread { cards.setPlayers(l); diff(l) } }
        server.playersNow().let { cards.setPlayers(it); diff(it) }                         // phones that were in before this game started
        server.onAdded = { }
        server.onReload = { runOnUiThread { fresh(); web.reload() } }
        server.onPlay = { awake.poke() }
    }

    /** No cached copy for the rest of this game: the page's server may not say when it changed (a Python test server, a NAS),
     *  and the WebView then kept running an old version of a game fixed since. */
    private fun fresh() { web.settings.cacheMode = WebSettings.LOAD_NO_CACHE }

    override fun onPause() { awake.stop(); if (::server.isInitialized) { server.onKeyLat = null; server.onPlay = {} }; super.onPause() }
    private val awake by lazy { Awake(this) }               // the screen stays on while phones play

    override fun onDestroy() {
        if (::cards.isInitialized) cards.close()
        if (::web.isInitialized && !gone) { web.removeJavascriptInterface("TVPadNative"); dev.tvremote.tvui.WebEnd.end(web) }   // its renderer too: ~100 MB kept after the game otherwise
        if (::server.isInitialized) { server.webMode = false; server.clearLayouts(); GameServer.release() }
        super.onDestroy()
    }

    private fun js(code: String) { if (pageOrigin == origin) web.evaluateJavascript("window.__tvpadIn&&$code", null) }

    private fun diff(now: List<Int>) {
        val nn = now.associateWith { server.nameOf(it) ?: "" }
        (now - players.toSet()).forEach { js("__tvpadIn('join',$it,${JSONObject.quote(nn[it])})") }
        (players - now.toSet()).forEach { js("__tvpadIn('leave',$it)") }
        now.filter { it in players && nn[it] != names[it] }.forEach { js("__tvpadIn('rename',$it,${JSONObject.quote(nn[it])})") }
        players = now; names = nn
    }

    private val held = HashMap<Int, Set<String>>()
    /** A phone's stick: the Gamepad API axes 0 / 1 of its pad, and for player 1 also the arrow keys. */
    private fun axis(port: Int, x: Float, y: Float) {
        js("__tvpadIn('axis',${port + 1},$x,$y)")
        if (port != 0) return
        val now = GameServer.dirs(x, y); val before = held[0] ?: emptySet(); held[0] = now
        if (!padKeys) return
        (before - now).forEach { keyOf[it]?.let { c -> web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, c)) } }
        (now - before).forEach { keyOf[it]?.let { c -> web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, c)) } }
    }

    // Player 1's buttons are also keys (arrows, Enter) for a page that only knows the keyboard. A page that reads the pads
    // itself says so (tvpad.ownKeys()) and gets each press once: both moved caro's cursor two cells per press
    @Volatile private var padKeys = true

    /** One pad button of player port+1: to the Gamepad API / tvpad, and for player 1 also as a key. */
    private fun input(port: Int, down: Boolean, name: String, lat: Int = -1) {
        js("__tvpadIn('button',${port + 1},'$name',$down,$lat)")
        if (port == 0 && padKeys) keyOf[name]?.let { web.dispatchKeyEvent(KeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, it)) }
    }

    inner class Bridge {
        private fun ok() = pageOrigin == origin
        @JavascriptInterface fun players(): String = JSONArray(players).toString()
        @JavascriptInterface fun ping(player: Int): Int = server.pingOf(player)
        @JavascriptInterface fun name(player: Int): String = server.nameOf(player) ?: ""
        @JavascriptInterface fun joinUrl(): String = if (ok()) server.url() else ""
        @JavascriptInterface fun qr(px: Int): String = if (ok()) qrPng(server.url(), px.coerceIn(64, 1024)) else ""
        @JavascriptInterface fun layout(player: Int, json: String) { if (ok()) server.setLayout(player, json) }
        @JavascriptInterface fun vibrate(player: Int, ms: Int) { if (ok()) server.vibrate(player, ms) }
        @JavascriptInterface fun say(player: Int, text: String) { if (ok()) server.say(player, text) }
        @JavascriptInterface fun ownKeys() { if (ok()) padKeys = false }
    }

    /** The join QR as a data: URL (PNG, crisp squares), for a page that wants to draw it. */
    private fun qrPng(url: String, px: Int): String = try {
        val b = Qr.bitmap(url, 1) ?: return ""
        val s = android.graphics.Bitmap.createScaledBitmap(b, px, px, false)    // no smoothing: modules stay sharp
        val o = java.io.ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.PNG, 100, o)
        "data:image/png;base64," + android.util.Base64.encodeToString(o.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (e: Exception) { "" }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (errorCard != null) {                                           // the failure card: D-pad moves between Retry / Back, Back leaves
            if (ev.keyCode == KeyEvent.KEYCODE_BACK) { if (ev.action == KeyEvent.ACTION_UP) finish(); return true }
            if (errorCard?.findFocus() == null) { if (ev.action == KeyEvent.ACTION_DOWN) errorRetry?.requestFocus(); return true }   // first key after a tap / touch mode: focus Retry
            return super.dispatchKeyEvent(ev)
        }
        if (!::cards.isInitialized) return super.dispatchKeyEvent(ev)   // the no-WebView screen (NoWebView): plain D-pad
        if (cards.key(ev)) return true
        if (cards.paused) return super.dispatchKeyEvent(ev)
        if (GameServer.isPad(ev) && nameOf.containsKey(ev.keyCode)) {      // a physical pad: its own slot, like a phone
            if (ev.repeatCount == 0) { val port = server.padPort(ev.deviceId); if (port >= 0) input(port, ev.action == KeyEvent.ACTION_DOWN, nameOf[ev.keyCode]!!) }
            return true
        }
        if (!web.hasFocus()) web.requestFocus()
        return super.dispatchKeyEvent(ev)                                    // the TV remote goes to the page as it is
    }

    companion object {
        private const val TAG = "WebGame"
        private const val LOAD_MS = 20_000L                        // a weak box (FPT Play, 2 GB) took over 10 s to start the WebView and answer
    }
}
