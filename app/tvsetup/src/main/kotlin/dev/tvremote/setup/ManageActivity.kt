package dev.tvremote.setup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.tvremote.core.T

/**
 * The web UI on the TV itself, for when there is no phone or computer at hand: the page RemoteService serves, in a WebView,
 * let in by the pass MainActivity hands over (RemoteService.selfPass, in memory only). The page knows it is on the TV (?ontv=1,
 * js/ontv.js): no Remote tab, and the D-pad moves between its controls.
 * It runs in its own process (":web" in the manifest): a WebView takes 100 MB+ and, on a TV short of memory, can stall or be
 * killed; then only this screen goes, never the service phones and computers use.
 */
class ManageActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private var tries = 0
    private var picked: ValueCallback<Array<Uri>>? = null
    private var url = "http://127.0.0.1:${RemoteService.PORT}/?ontv=1"
    private var pass = ""

    companion object { const val PASS = "pass"; const val TAB = "tab"; const val SECTION = "card" }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pass = intent.getStringExtra(PASS) ?: run { finish(); return }   // only TV Setup's own screens open it, with the pass
        intent.getStringExtra(TAB)?.takeIf { Regex("[a-z]+").matches(it) }?.let { url += "&tab=$it" }   // the RAM notice: straight to Health
        intent.getStringExtra(SECTION)?.takeIf { Regex("[a-z]+").matches(it) }?.let { url += "&card=$it" }   // TV Home's kids lock: straight to the lock card (PIN)
        val box = android.widget.FrameLayout(this)
        box.setBackgroundColor(Color.rgb(13, 16, 20))
        val wait = android.widget.TextView(this)
        // the words on a thread too: the first T() in this new process parses i18n/vi.json + en.json (390 KB), 1 s on an FPT box,
        // and done here it held the WebView's start below back by that second
        Thread({ val w = T("manage.opening"); ui.post { wait.text = w } }, "words").start()
        wait.setTextColor(Color.rgb(160, 170, 185))
        wait.textSize = 18f
        box.addView(wait, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        setContentView(box)
        // WebView's first start in this process loads its library (seconds on a slow TV, 8 s on the emulator): done here on
        // a thread, so the screen keeps answering the remote meanwhile instead of Android calling TV Setup frozen
        Thread({
            try { android.webkit.WebSettings.getDefaultUserAgent(applicationContext) } catch (e: Exception) { }
            ui.post { if (!isFinishing && !isDestroyed) { box.removeView(wait); open(box) } }
        }, "webview").start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(box: android.widget.FrameLayout) {
        web = WebView(this)
        web.setBackgroundColor(Color.rgb(13, 16, 20))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        CookieManager.getInstance().setCookie("http://127.0.0.1:${RemoteService.PORT}",
            RemoteService.SELF_COOKIE + "=" + pass + "; Path=/; HttpOnly; SameSite=Strict")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.host == "127.0.0.1") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (e: Exception) { }   // a link out (GitHub, help): the TV's browser, if it has one
                return true
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                // the service is still starting (its first start makes a key): ask again for a while
                if (r.isForMainFrame && tries++ < 40) ui.postDelayed({ web.loadUrl(url) }, 500)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                picked?.onReceiveValue(null)
                picked = cb
                return try { startActivityForResult(p.createIntent(), 1); true } catch (e: Exception) { picked = null; false }   // a TV with no file picker: the page says so
            }
        }
        box.addView(web)
        // the D-pad's first press must reach the page (js/ontv.js); a WebView without focus would take it to pick its own first
        // control that way (Settings, top right) instead
        web.requestFocus()
        web.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        // parseResult reads only getData(): a picker answering a multiple pick with ClipData alone gave the page nothing
        val clip = data?.clipData
        picked?.onReceiveValue(if (res == RESULT_OK && clip != null && clip.itemCount > 0) Array(clip.itemCount) { clip.getItemAt(it).uri }
            else WebChromeClient.FileChooserParams.parseResult(res, data))
        picked = null
    }

    // A mouse (Air Mouse's, a USB one): this WebView drops mouse clicks and wheel right of x = 1080 px, the screen's height,
    // as if it were standing (Google TV emulator WebView 113, an FPT box 155: the page got no event at all). Touches reach
    // the whole page, so a click goes in as a touch, and a wheel it did not take scrolls the box under the pointer itself.
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_MOUSE) || e.pointerCount != 1) return super.dispatchTouchEvent(e)
        val p = MotionEvent.PointerProperties().also { e.getPointerProperties(0, it); it.toolType = MotionEvent.TOOL_TYPE_FINGER }
        val c = MotionEvent.PointerCoords().also { e.getPointerCoords(0, it) }
        val t = MotionEvent.obtain(e.downTime, e.eventTime, e.action, 1, arrayOf(p), arrayOf(c), e.metaState, 0,
            e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags, InputDevice.SOURCE_TOUCHSCREEN, e.flags)
        try { return super.dispatchTouchEvent(t) } finally { t.recycle() }
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if (!e.isFromSource(InputDevice.SOURCE_MOUSE)) return super.dispatchGenericMotionEvent(e)
        if (a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE) return true   // the touch above is the click
        if (super.dispatchGenericMotionEvent(e) || a != MotionEvent.ACTION_SCROLL || !::web.isInitialized) return true
        val d = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (d == 0f) return true
        val step = if (android.os.Build.VERSION.SDK_INT >= 26) android.view.ViewConfiguration.get(this).scaledVerticalScrollFactor   // the notch WebView scrolls itself, in px
            else 64 * resources.displayMetrics.density
        web.evaluateJavascript("(function(x,y,d){x/=devicePixelRatio;y/=devicePixelRatio;let b=document.elementFromPoint(x,y);" +
            "for(;b&&b!==document.body&&b!==document.documentElement;b=b.parentElement){const o=getComputedStyle(b).overflowY;" +
            "if((o=='auto'||o=='scroll')&&b.scrollHeight>b.clientHeight)break}" +
            "(b&&b!==document.body&&b!==document.documentElement?b:document.scrollingElement).scrollBy(0,d/devicePixelRatio)})(${e.x},${e.y},${-d * step})", null)
        return true
    }

    // Back: the page closes what is open (a dialog, a menu) first; only with nothing open does it leave
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (!::web.isInitialized) finish()
            else if (e.action == KeyEvent.ACTION_UP) web.evaluateJavascript("window.tvBack ? tvBack() : false") { if (it != "true") finish() }
            return true
        }
        // OK reaches the page as Enter: WebView keeps the remote's DPAD_CENTER to itself on a text field (to raise a keyboard
        // the page holds down), so js/ontv.js would never learn the field was picked
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) return super.dispatchKeyEvent(KeyEvent(e.downTime, e.eventTime, e.action,
            KeyEvent.KEYCODE_ENTER, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source))
        return super.dispatchKeyEvent(e)
    }

    // no MainActivity.shown here (another process): the page's requests keep RemoteService from updating meanwhile (inUse)
    override fun onResume() { super.onResume(); if (::web.isInitialized) { web.onResume(); web.resumeTimers() } }
    // out of sight (Home, another app): the page stops asking the TV for its state; timers are per process, and this one is ours
    override fun onPause() { super.onPause(); if (::web.isInitialized) { web.onPause(); web.pauseTimers() } }
    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
        // left for good: this process and its WebView renderer (about 145 MB together) go now, instead of lingering as a
        // cached process on a TV that is short of memory; nothing else lives in it
        if (isFinishing) ui.post { android.os.Process.killProcess(android.os.Process.myPid()) }
    }
}
