package dev.tvremote.phone.media

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.manage.Managing
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.picker.wake
import dev.tvremote.phone.remote.gamePadMenu
import dev.tvremote.phone.remote.kind
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.runs
import dev.tvremote.phone.ui.say

import dev.tvremote.core.T
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.ontv.Foreground
import dev.tvremote.core.ontv.RemoteLabels
import dev.tvremote.core.remote.Remote
import java.io.File
import java.util.concurrent.Executors

/**
 * The remote's "now open" line, as the web page's (apps.js showNowApp): the app on the TV's screen, from the TV's own remote link
 * (Android TV paired, LG, Samsung, Roku, VIDAA) or else over ADB. Hidden when the TV does not say; kept beside the now playing
 * strip, as on the page. Names and pictures come from the Manage screen's copy per TV (RemoteLabels' cache), which
 * this fills when ADB is there; without ADB only that copy is read, so the TV is never asked "Allow USB debugging?" for it.
 */
internal class NowApp(private val a: MainActivity) {
    val view = LinearLayout(a)
    private val icon = ImageView(a)
    private val name = a.label("", 14, TEXT)
    private var front = Foreground()
    private var frontFor: String? = null                              // its last reading is about this TV only
    private val work = Executors.newSingleThreadExecutor()
    private var labels: RemoteLabels? = null
    private var labelsFor = ""
    private var link: AdbLink? = null
    private var remoteNames: Map<String, String> = emptyMap()        // an LG / Roku / Samsung's apps by id
    private var namesFrom: Remote? = null
    private var shown = ""
    private var asking = false
    private var shownFor: String? = null                             // the TV [shown] is about
    private var fine = 0L                                             // last answer from a TV that could be asked

    // TV Games on screen: its pad page and the gyroscope aim are one tap away (they were under Tools)
    private val chip = a.button("@gamepad|" + T("rkey.pad_chip").replace(Regex("^[^\\p{L}]+"), ""), BTN)

    init {
        view.gravity = Gravity.CENTER
        view.visibility = View.GONE
        view.addView(a.label(T("apps.now_open"), 13, MUTED))
        view.addView(icon, LinearLayout.LayoutParams(-2, a.dp(24)).apply { leftMargin = a.dp(8) })   // its own shape: a wide banner is not cut to a square
        icon.outlineProvider = object : android.view.ViewOutlineProvider() {   // rounded like the page's app pictures
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, a.dp(6).toFloat())
        }
        icon.clipToOutline = true
        icon.adjustViewBounds = true
        icon.maxWidth = a.dp(96)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        name.typeface = android.graphics.Typeface.DEFAULT_BOLD
        name.runs()
        view.addView(name, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.dp(8) })   // its own gap: the picture may be missing
        view.addView(chip, LinearLayout.LayoutParams(-2, a.dp(32)).apply { leftMargin = a.dp(12) })
        chip.visibility = View.GONE
        chip.textSize = 13f
        chip.setOnClickListener { a.gamePadMenu() }
        a.ui.post { tick.run() }
    }


    /** Back on screen: ask now rather than at the next 2 s tick. */
    fun wake() { a.ui.removeCallbacks(tick); a.ui.post(tick) }

    fun close() { work.shutdownNow(); link?.close(); link = null }

    private fun cacheDir(host: String) = File(a.cacheDir, "meta/" + host.replace(Regex("[^\\w.-]"), "_"))   // the Manage screen's (Managing.kt)

    private val tick = object : Runnable {
        override fun run() {
            if (work.isShutdown) return
            a.ui.postDelayed(this, 2000)
            val host = a.host
            val tv = a.tv
            val basic = a.basic
            if (!a.foreground) fine = System.currentTimeMillis()      // away counts as fine: the 5 s start on return
            if (!a.foreground || a.remote.visibility != View.VISIBLE || a.covered() || host == null || asking) { if (host == null) show("", "", null); return }
            asking = true
            if (host != frontFor) { front = Foreground(); frontFor = host }
            val canAsk = basic != null || tv?.ready() == true
            work.execute {
                var id = basic?.app ?: ""
                var label = ""
                var png: ByteArray? = null
                try {
                    if (id.isEmpty()) id = front.get(tv?.takeIf { it.ready() }?.let { t -> { c: String -> t.shell(c) } })
                    if (id.isNotEmpty() && basic != null && basic.kind != Remote.Kind.ANDROID) {   // an id: its name from the TV's list
                        if (namesFrom !== basic || id !in remoteNames) { remoteNames = try { basic.apps().associate { it.id to it.label } } catch (e: Exception) { emptyMap() }; namesFrom = basic }
                        label = remoteNames[id] ?: if (basic.kind == Remote.Kind.VIDAA) id else ""
                    } else if (id.isNotEmpty()) {
                        val dir = cacheDir(host)
                        if (tv != null && tv.ready() && basic == null) {
                            if (labelsFor != host) {
                                link?.close()
                                val l = AdbLink(a.filesDir, host).also { link = it }
                                labels = RemoteLabels(l, { a.assets.open("tvr-helper.jar").use { it.readBytes() } }, dir); labelsFor = host
                            }
                            label = labels?.label(id) ?: ""
                            png = labels?.icon(id)
                        } else {
                            label = try { org.json.JSONObject(File(dir, "names.json").readText()).optString(id) } catch (e: Exception) { "" }
                            png = File(dir, "$id.png").takeIf { it.length() > 0 }?.readBytes()
                        }
                    }
                } catch (ignored: Exception) { }
                a.ui.post {
                    asking = false
                    // back from away, the picture's session starts again and cannot be asked yet: keep what was open, 5 s at most
                    if (canAsk) fine = System.currentTimeMillis()
                    if (id.isNotEmpty() || canAsk || host != shownFor || System.currentTimeMillis() - fine > 5000) { show(id, label, png); shownFor = host }
                }
            }
        }
    }

    private fun show(id: String, label: String, png: ByteArray?) {
        val hide = id.isEmpty() || label.isEmpty() && '.' in id && png == null   // a bare package name says nothing to most people
        view.visibility = if (hide) View.GONE else View.VISIBLE
        chip.visibility = if (id == dev.tvremote.core.ontv.Games.PKG && a.tv?.ready() == true) View.VISIBLE else View.GONE
        if (hide || "$id|$label|${png?.size}" == shown) return
        shown = "$id|$label|${png?.size}"
        name.say(label.ifEmpty { id })
        val bmp = png?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        icon.setImageBitmap(bmp)
        icon.visibility = if (bmp == null) View.GONE else View.VISIBLE
    }
}
