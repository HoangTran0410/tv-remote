package dev.tvremote.phone.picker

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.remote.keys
import dev.tvremote.phone.remote.kindOf
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.CARD
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.OK_TEXT
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.gap
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.row
import dev.tvremote.phone.ui.setOn

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.TextView
import android.graphics.Typeface
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import dev.tvremote.core.FleetStatus
import dev.tvremote.core.T
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.picture.Thumb
import dev.tvremote.core.remote.Remote
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

// 📺 The saved TVs at a glance, on the list of TVs (the computer's page: js/tvstatus.js): under each TV whether it is on, in standby
// or not answering, its model and the app on screen, read every 10 s while the list shows (FleetStatus.kt: one light shell per TV on
// a link kept for it, a TCP connect first so a TV that is off costs a second). "Show screens" adds a small picture of each TV
// that is on, every 5 s; off until asked, it costs the TVs' CPU. Paused with the list (onPause) and while a TV is open.

private const val EVERY_MS = 10_000L
private const val SHOT_MS = 5_000L

/** What the list keeps between rounds; the readings themselves are the Board's. */
internal object TvStatus {
    @Volatile var shots = false
    val last = ConcurrentHashMap<String, FleetStatus.Status>()
    val thumbs = HashMap<String, ImageView>()                         // UI thread only
    val cards = HashMap<String, Card>()                               // "Show screens": each saved TV's card (UI thread only)
    private val links = ConcurrentHashMap<String, Pair<String, AdbLink>>()   // saved TV -> (the address dialled, its link)
    private val used = ConcurrentHashMap<String, Long>()
    @Volatile var board: FleetStatus.Board? = null
    @Volatile var resolve: (String) -> Triple<String, String, java.io.File>? = { null }   // saved TV -> (address now, kind, key folder)
    val busy = java.util.concurrent.atomic.AtomicBoolean()
    val gate = Semaphore(2)                                           // a picture costs a TV a PNG: two TVs at a time
    val pool = Executors.newCachedThreadPool { Thread(it, "tv-status").apply { isDaemon = true } }
    private val sweep = Executors.newSingleThreadScheduledExecutor { Thread(it, "tv-status-sweep").apply { isDaemon = true } }.also {
        it.scheduleWithFixedDelay({ idle() }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun link(ip: String, addr: String, keys: java.io.File): AdbLink {
        used[ip] = System.currentTimeMillis()
        val have = links[ip]
        if (have != null && have.first == addr) return have.second
        have?.second?.close()
        return AdbLink(keys, addr).also { links[ip] = addr to it }
    }

    /** A link the list has not used for 40 s (the list is closed, or a TV is open) goes: no connection kept for a screen nobody looks at. */
    private fun idle() {
        val now = System.currentTimeMillis()
        for ((ip, t) in used) if (now - t > 40_000) { used.remove(ip); links.remove(ip)?.second?.close() }
    }

    fun read(ip: String): FleetStatus.Status {
        val (addr, kind, keys) = resolve(ip) ?: return FleetStatus.Status(ip)
        val link = if (kind == Remote.Kind.ANDROID) link(ip, addr, keys) else null
        return FleetStatus.read(addr, kind, { FleetStatus.reach(addr, it) }, link).copy(host = ip)
    }

    fun board(): FleetStatus.Board = board ?: FleetStatus.Board({ read(it) }).also { board = it }
}

/** The list shows (called by each probe): reads now if the last round was long ago, then every 10 s on its own. */
internal fun MainActivity.statusStart() {
    TvStatus.resolve = { ip -> Triple(route[ip] ?: ip, kindOf(ip), filesDir) }
    if (TvStatus.busy.get() || host != null) return
    ui.removeCallbacks(statusTick)
    statusTick.run()
}

private val tickFor = java.util.WeakHashMap<MainActivity, Runnable>()
private val shotFor = java.util.WeakHashMap<MainActivity, Runnable>()
private val MainActivity.statusTick: Runnable get() = tickFor.getOrPut(this) { Runnable { statusRound() } }
private val MainActivity.shotTick: Runnable get() = shotFor.getOrPut(this) { Runnable { shotRound() } }

/** The list is left (the app is paused or a TV opens): the loops stop; the links close by themselves a little later. */
internal fun MainActivity.statusPause() { ui.removeCallbacks(statusTick); ui.removeCallbacks(shotTick) }

private fun MainActivity.statusRound() {
    if (host != null || isFinishing) return
    val ips = savedIps()
    if (ips.isEmpty() || !TvStatus.busy.compareAndSet(false, true)) { ui.postDelayed(statusTick, EVERY_MS); return }
    Thread {
        val out = try { TvStatus.board().round(ips, 9000) } catch (e: Exception) { emptyList() } finally { TvStatus.busy.set(false) }
        ui.post {
            if (isFinishing) return@post
            for (s in out) if (s.state != FleetStatus.UNKNOWN) TvStatus.last[s.host] = s
            for (ip in ips) showReach(ip, reach[ip])
            showThumbs()
            if (host == null) {
                ui.removeCallbacks(statusTick); ui.postDelayed(statusTick, EVERY_MS)
                if (TvStatus.shots) { ui.removeCallbacks(shotTick); ui.post(shotTick) }
            }
        }
    }.start()
}

// ---------------------------------------------------------------- what a row says
/** The state and the facts under a TV's name, or null to keep the plain "reachable" / "no answer" line: only on and standby add to it. */
internal fun MainActivity.statusLines(ip: String): Pair<String, String>? {
    val s = TvStatus.last[ip] ?: return null
    if (s.state != FleetStatus.ON && s.state != FleetStatus.STANDBY) return null
    val state = if (s.state == FleetStatus.STANDBY) T("tvstat.standby")
        else listOf(T("tvstat.on"), if (s.app.isEmpty()) "" else if (s.home) T("tvstat.home") else T("tvstat.showing", "app" to s.appName)).filter { it.isNotEmpty() }.joinToString(" · ")
    val model = if (s.model.length > 24) s.model.take(23).trimEnd() + "…" else s.model   // one line: a long model name wrapped to three
    return "● $state" to listOf(model, if (s.android.isEmpty()) "" else "Android ${s.android}").filter { it.isNotEmpty() }.joinToString(" · ")
}

/** One TV's card while "Show screens" is on: its picture (or why there is none) with its name and state under it. */
internal class Card(val pic: ImageView, val blank: TextView, val wake: View, val name: TextView, val state: TextView, val more: TextView)

/** 16:9 whatever the card's width: the TV's own shape, so a picture is never cut. */
private class Screen(c: android.content.Context) : android.widget.FrameLayout(c) {
    override fun onMeasure(w: Int, h: Int) {
        val wide = View.MeasureSpec.getSize(w)
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(wide * 9 / 16, View.MeasureSpec.EXACTLY))
    }
}

/** The saved TVs as a grid of cards, two a row (a camera app's wall, smaller): tap one to open it, hold it or ✕ to remove it. */
internal fun MainActivity.screenCards(ips: List<String>) {
    for (pair in ips.chunked(2)) {
        val line = LinearLayout(this)
        line.layoutParams = gap(8)
        for ((k, ip) in pair.withIndex()) line.addView(screenCard(ip), LinearLayout.LayoutParams(0, -2, 1f).apply { if (k == 1) leftMargin = dp(8) })
        if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { leftMargin = dp(8) })
        savedList.addView(line)
    }
}

private fun MainActivity.screenCard(ip: String): View {
    val card = LinearLayout(this)
    card.orientation = LinearLayout.VERTICAL
    card.background = box(CARD, 14)
    card.clipToOutline = true
    card.isClickable = true
    if (android.os.Build.VERSION.SDK_INT >= 23) card.foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, box(Color.WHITE, 14))
    card.setOnClickListener { connectSaved(ip) }
    card.setOnLongClickListener { askRemove(ip); true }

    val screen = Screen(this)
    screen.setBackgroundColor(0xFF0B0C0F.toInt())
    val pic = ImageView(this)
    pic.scaleType = ImageView.ScaleType.CENTER_CROP
    screen.addView(pic, android.widget.FrameLayout.LayoutParams(-1, -1))
    val blank = label("", 12, MUTED)
    blank.gravity = android.view.Gravity.CENTER
    screen.addView(blank, android.widget.FrameLayout.LayoutParams(-1, -1))
    val wake = icon("@power", T("remote.wake"), BTN)
    wake.setOnClickListener { wake(ip) }
    screen.addView(wake, android.widget.FrameLayout.LayoutParams(dp(44), dp(44), android.view.Gravity.CENTER))
    val del = icon("@close", T("picker.remove_this_tv_from_the"), 0x99000000.toInt())
    del.size = 16f
    del.setOnClickListener { askRemove(ip) }
    screen.addView(del, android.widget.FrameLayout.LayoutParams(dp(30), dp(30), android.view.Gravity.END or android.view.Gravity.TOP).apply { setMargins(0, dp(6), dp(6), 0) })
    card.addView(screen, LinearLayout.LayoutParams(-1, -2))

    val words = LinearLayout(this)
    words.orientation = LinearLayout.VERTICAL
    words.setPadding(dp(10), dp(8), dp(10), dp(10))
    val one = { v: TextView -> v.setSingleLine(true); v.ellipsize = android.text.TextUtils.TruncateAt.END; v }
    val name = one(label(name(ip).ifEmpty { ip }, 15, TEXT)).apply { typeface = Typeface.DEFAULT_BOLD }
    val state = one(label("", 12, MUTED))
    val more = one(label("", 12, MUTED))
    words.addView(name); words.addView(state); words.addView(more)
    card.addView(words)

    val c = Card(pic, blank, wake, name, state, more)
    TvStatus.cards[ip] = c
    TvStatus.thumbs[ip] = pic
    showCard(ip, c, reach[ip], reachWords(ip, reach[ip]))
    return card
}

/** A card's words and what fills its picture: the TV's screen when it is on, else what it is doing, with Wake when it does not answer. */
internal fun MainActivity.showCard(ip: String, c: Card, up: Boolean?, plain: String) {
    val s = TvStatus.last[ip]?.takeIf { up != false && (it.state == FleetStatus.ON || it.state == FleetStatus.STANDBY) }
    val on = s?.state == FleetStatus.ON
    c.state.text = when {
        s == null -> plain
        on -> "● " + T("tvstat.on")
        else -> "● " + T("tvstat.standby")
    }
    c.state.setTextColor(if (on) OK_TEXT else MUTED)
    c.more.text = when {
        s == null -> if (name(ip).isEmpty()) "" else ip
        on && s.app.isNotEmpty() -> if (s.home) T("tvstat.home") else T("tvstat.showing", "app" to s.appName)
        else -> listOf(s.model, if (s.android.isEmpty()) "" else "Android ${s.android}").filter { it.isNotEmpty() }.joinToString(" · ")
    }
    c.more.visibility = if (c.more.text.isEmpty()) View.GONE else View.VISIBLE
    c.wake.visibility = if (up == false) View.VISIBLE else View.GONE
    c.pic.visibility = if (on) View.VISIBLE else View.GONE
    if (!on) c.pic.setImageDrawable(null)
    c.blank.text = when {
        up == false -> ""
        on -> if (c.pic.drawable == null) "…" else ""
        s != null -> T("tvstat.standby")
        up == true -> T("tvstat.no_picture")
        else -> "…"
    }
}

/** The pictures follow the TVs' states (each round). */
internal fun MainActivity.showThumbs() {
    for (ip in TvStatus.cards.keys) showReach(ip, reach[ip])
}

private fun MainActivity.shotRound() {
    if (host != null || isFinishing || !TvStatus.shots) return
    for ((ip, view) in TvStatus.thumbs) {
        if (TvStatus.last[ip]?.state != FleetStatus.ON || view.tag == "busy") continue
        view.tag = "busy"
        TvStatus.pool.execute {
            var bmp: Bitmap? = null
            try {
                TvStatus.gate.acquire()
                try {
                    val (addr, kind, keys) = TvStatus.resolve(ip) ?: return@execute
                    if (kind != Remote.Kind.ANDROID) return@execute
                    val png = Thumb.screen(TvStatus.link(ip, addr, keys).get())?.let { Thumb.small(it, 240) }
                    if (png != null) bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
                } finally { TvStatus.gate.release() }
            } catch (ignored: Exception) {
            } finally {
                ui.post { view.tag = null; bmp?.let { if (TvStatus.shots && view.visibility == View.VISIBLE) { view.setImageBitmap(it); TvStatus.cards[ip]?.blank?.text = "" } } }
            }
        }
    }
    ui.removeCallbacks(shotTick); ui.postDelayed(shotTick, SHOT_MS)
}

/** The "Show screens" button beside the heading of the saved TVs, only its eye (words beside it crowded "Several TVs" out):
 *  on (blue edge) while the pictures are asked for; a long press says what it does. */
internal fun MainActivity.screensButton(): android.widget.Button {
    TvStatus.shots = prefs.getBoolean("tvshots", false)
    val b = button("@eye|", BTN)
    b.textSize = 14f
    b.minWidth = 0; b.minimumWidth = 0
    b.setPadding(0, 0, 0, 0)
    b.compoundDrawablePadding = 0
    b.contentDescription = T("tvstat.show_screens").removePrefix("👁️").trim() + ". " + T("tvstat.show_screens_tip")
    if (android.os.Build.VERSION.SDK_INT >= 26) b.tooltipText = T("tvstat.show_screens_tip")
    b.setOn(TvStatus.shots)
    b.setOnClickListener {
        TvStatus.shots = !TvStatus.shots
        prefs.edit().putBoolean("tvshots", TvStatus.shots).apply()
        b.setOn(TvStatus.shots)
        refreshSaved()                                                // the list becomes a grid of pictures, or back
        ui.removeCallbacks(shotTick)
        if (TvStatus.shots) ui.post(shotTick)
    }
    return b
}
