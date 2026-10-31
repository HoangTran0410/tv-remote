package dev.tvremote.phone.media

import dev.tvremote.phone.ListenService
import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.manage.progress
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.picker.wake
import dev.tvremote.phone.remote.kind
import dev.tvremote.phone.ui.ACCENT
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.CARD
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.PILL
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.col
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.input
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.runs
import dev.tvremote.phone.ui.say

import dev.tvremote.core.T
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.tvremote.core.ontv.NowPlaying
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.ontv.ScreenOff
import dev.tvremote.core.picture.Tv
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The now playing strip above the remote's keys, the same as the web page's (js/playing.js): what the TV plays, from its media
 * session (NowPlaying, tvr-helper's Playing on the TV), with ⏮ ⏯ ⏭ and a bar that seeks. Asked once a second while the remote
 * is on screen with ADB; hidden when nothing plays.
 */
internal class PlayingBar(private val a: MainActivity) {
    val view = a.col()
    private val title = a.label("", 15, TEXT)
    private val sub = a.label("", 12, MUTED)
    private val play = a.icon("@pause", T("remote.play_pause"), ACCENT)
    private val seek = SeekBar(a)
    private val pos = a.label("0:00", 12, MUTED)
    private val dur = a.label("", 12, MUTED)
    private val times = LinearLayout(a)
    private val work = Executors.newSingleThreadExecutor()            // NowPlaying starts its helper over ADB: never on the UI thread
    private var np: NowPlaying? = null
    private var npFor: Tv? = null
    private var o = JSONObject().put("state", "none")
    private var at = 0L                                               // when [o] came
    private var oHost: String? = null                                // the TV [o] is about
    private var fine = 0L                                             // last tick that could ask, or was away
    private var dragging = false
    private var asking = false
    private val spins = HashMap<android.view.View, android.widget.ProgressBar>()

    init {
        view.background = a.box(CARD, 16)
        view.setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(6))
        view.visibility = View.GONE
        val head = LinearLayout(a)
        head.gravity = Gravity.CENTER_VERTICAL
        val words = a.col()
        for (w in listOf(title, sub)) { w.runs(); words.addView(w) }  // a long title or channel runs (the page's .run)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val prev = a.icon("@prev", T("remote.previous"), BTN)
        val next = a.icon("@next", T("remote.next"), BTN)
        prev.setOnClickListener { send("prev", 0, prev) }
        next.setOnClickListener { send("next", 0, next) }
        play.setOnClickListener { send(if (o.optString("state") == "playing") "pause" else "play", 0, play) }   // the icon turns when the TV says so
        for (b in listOf(prev, play, next)) {
            val spin = android.widget.ProgressBar(a).apply { isIndeterminate = true; visibility = View.GONE; spins[b] = this }
            val cell = FrameLayout(a)
            cell.addView(b, FrameLayout.LayoutParams(-1, -1))
            cell.addView(spin, FrameLayout.LayoutParams(a.dp(20), a.dp(20), Gravity.CENTER))
            head.addView(cell, LinearLayout.LayoutParams(a.dp(44), a.dp(40)).apply { leftMargin = a.dp(6) })
        }
        view.addView(head)
        times.gravity = Gravity.CENTER_VERTICAL
        seek.max = 1000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) pos.text = clock(p / 1000.0 * o.optLong("dur")) }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                val ms = (s.progress / 1000.0 * o.optLong("dur")).toLong()
                if (busy) return
                o.put("pos", ms); at = System.currentTimeMillis()
                send("seek", ms, null)
            }
        })
        times.addView(pos)
        times.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(dur)
        view.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(4) })
        a.ui.post { tick.run() }                                      // declared below the init block
        ListenService.control = { what ->                             // the buttons of the listening notification
            if (what == ListenService.STOP) a.stopListen(null)
            else if (a.tv != null) {
                send(what, 0, null)
            }
        }
    }

    /** Where it is now: the last answer moved on by the time since, as NowPlaying does between the helper's lines. */
    private fun now(): Long {
        var p = o.optLong("pos")
        if (o.optString("state") == "playing") p += ((System.currentTimeMillis() - at) * o.optDouble("speed", 1.0)).toLong()
        val d = o.optLong("dur")
        return if (d > 0) minOf(p, d) else p
    }

    /** Back on screen: ask the TV now rather than at the next 2 s tick. */
    fun wake() { a.ui.removeCallbacks(tick); a.ui.post(tick) }

    private val tick = object : Runnable {
        override fun run() {
            val tv = a.tv
            val looking = a.foreground && a.remote.visibility == View.VISIBLE && !a.covered() && a.basic == null && tv != null && tv.ready()
            val listening = a.listen && a.basic == null && tv != null && tv.ready()   // the phone's media controls show it too
            if (looking || !a.foreground) fine = System.currentTimeMillis()  // away counts as fine: the 5 s start on return
            if (!looking && !listening) {
                // kept while away from the same TV, so it is there at once on return; dropped for another TV, or once the
                // remote is on screen and the TV has not answered for 5 s
                if (a.host != oHost || (a.foreground && System.currentTimeMillis() - fine > 5000)) o = JSONObject().put("state", "none")
                draw(); a.ui.postDelayed(this, 2000); return
            }
            if (!asking) {
                asking = true
                work.execute {
                    val got = try { player(tv!!).state() } catch (e: Exception) { JSONObject().put("state", "none") }
                    a.ui.post { asking = false; o = got; oHost = a.host; at = System.currentTimeMillis(); draw(); ListenService.show(o, a.title(a.host ?: "")) }
                }
            }
            draw()
            checkBlack()
            a.ui.postDelayed(this, 1000)
        }
    }

    /** As the web page's (screen.js): some TVs draw a video on a plane no capture sees (the FPT Play box), and protected
     *  films are never captured, so the picture is black but for subtitles and logos. Said once a film is open and the
     *  picture has stayed nearly all black for 4 s, so nobody takes it for a fault. PixelCopy reads a SurfaceView from 7.0 on. */
    private var blackFor = 0
    private val blackBmp by lazy { android.graphics.Bitmap.createBitmap(32, 18, android.graphics.Bitmap.Config.ARGB_8888) }
    private val blackHint by lazy {
        a.label(T("now_playing.the_tv_does_not"), 13, TEXT).apply {
            gravity = Gravity.CENTER; background = a.box(PILL, 10); setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8)); visibility = View.GONE
            a.frame.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { leftMargin = a.dp(24); rightMargin = a.dp(24) })
        }
    }
    @android.annotation.TargetApi(24)   // the caller below turns it off under 24
    private fun checkBlack() {
        val shown = o.optString("state", "none") != "none" && a.stage.visibility == View.VISIBLE && a.surface.visibility == View.VISIBLE &&
            a.surface.holder.surface.isValid && android.os.Build.VERSION.SDK_INT >= 24
        if (!shown) { blackFor = 0; blackHint.visibility = View.GONE; return }
        try {
            android.view.PixelCopy.request(a.surface, blackBmp, { r ->
                var dark = 0
                if (r == android.view.PixelCopy.SUCCESS) for (y in 0 until 18) for (x in 0 until 32) {
                    val c = blackBmp.getPixel(x, y)
                    if ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255) < 60) dark++
                }
                blackFor = if (r == android.view.PixelCopy.SUCCESS && dark > 32 * 18 * 9 / 10) blackFor + 1 else 0
                blackHint.visibility = if (blackFor >= 4) View.VISIBLE else View.GONE
            }, a.ui)
        } catch (e: Exception) { blackFor = 0 }
    }

    private fun player(tv: Tv): NowPlaying = synchronized(this) {
        if (npFor !== tv) { np = NowPlaying({ tv.adb() }) { a.assets.open("tvr-helper.jar").use { it.readBytes() } }; npFor = tv }
        np!!
    }

    @Volatile private var busy = false                                // a press is out until the TV's own state shows it done

    private fun mark(b: View?, on: Boolean) {
        if (b == null) return
        b.alpha = if (on) 0.25f else 1f
        spins[b]?.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun sig(j: JSONObject) = "${j.optString("state")}|${j.opt("title")}|${j.optString("pkg")}"

    /** Sends [what] and keeps [btn] busy (further presses are ignored) until the TV's state or track has changed, 2.5 s at most,
     *  so presses cannot pile up faster than its media session follows. Nothing is flipped before the TV says so. */
    private fun send(what: String, ms: Long, btn: View?) {
        val tv = a.tv ?: return
        if (busy) return
        busy = true; mark(btn, true)
        val before = sig(o)
        work.execute {
            var failed = false
            try {
                player(tv).command(what, ms)
                val end = System.currentTimeMillis() + if (what == "seek") 600 else 2500
                while (System.currentTimeMillis() < end) {
                    Thread.sleep(400)
                    val got = try { player(tv).state() } catch (e: Exception) { continue }
                    a.ui.post { o = got; at = System.currentTimeMillis(); draw() }
                    if (what == "seek" || sig(got) != before) break
                }
            } catch (e: Exception) { failed = true }
            a.ui.post {
                busy = false; mark(btn, false)
                if (failed) a.say(T("now_playing.the_tv_did_not"), true)
                else ListenService.show(o, a.title(a.host ?: ""))
            }
        }
    }

    private fun draw() {
        val text = { k: String -> if (o.isNull(k)) "" else o.optString(k) }   // optString of a JSON null is "null"
        val name = text("title").ifEmpty { text("app") }              // a player with no title (FPT Play): its app's name
        val on = o.optString("state", "none") != "none" && name.isNotEmpty()
        view.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        title.say(name); sub.say(text("sub").takeIf { text("title").isNotEmpty() } ?: "")   // draw() runs every second
        sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
        play.setIcon(if (o.optString("state") == "paused") "@play" else "@pause", TEXT)
        val d = o.optLong("dur")
        times.visibility = if (d > 0) View.VISIBLE else View.GONE
        seek.isEnabled = o.optBoolean("seek")
        if (d > 0 && !dragging) { seek.progress = (now() * 1000 / d).toInt(); pos.text = clock(now().toDouble()); dur.text = clock(d.toDouble()) }
    }

    private fun clock(ms: Double): String {
        val s = (ms / 1000).toLong()
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }
}

/** The screen off while the sound, the picture and the keys go on (ScreenOff in the shared core); pressed again, or the TV's own remote, turns it on. */
internal fun MainActivity.screenOff() {
    basic?.takeIf { it.kind != Remote.Kind.ANDROID }?.let { b ->       // LG: by its remote link, no ADB; any key turns it on
        Thread {
            val ok = b.screenOff()
            ui.post { say(if (ok) T("playing.screen_off_any") else T("now_playing.this_tv_cannot_turn"), !ok) }
        }.start()
        return
    }
    if (needsAdb()) return
    val tv = tv
    if (tv == null || !tv.ready()) return
    Thread {
        var on = false
        val err = try {
            val a = tv.adb()
            val jar = assets.open("tvr-helper.jar").use { it.readBytes() }
            on = ScreenOff.isOff { a.shell(it) }
            if (on) ScreenOff.on(a, jar) else ScreenOff.off(a, jar)
            null
        } catch (e: Exception) { e }
        ui.post { say(if (err != null) T("now_playing.this_tv_cannot_turn") else if (on) T("playing.screen_on") else T("playing.screen_off"), err != null) }
    }.start()
}

/** The TV's clipboard (the "clip" entry of Open on the TV): shown in a box, then copied to the phone, set on the TV, or pasted there. */
internal fun MainActivity.clipDialog() {
    if (needsAdb()) return
    val tv = tv
    if (tv == null || !tv.ready()) return
    Thread {
        val text = try { tv.clipboard() } catch (e: Exception) { null }
        ui.post {
            if (isFinishing) return@post
            if (text == null) { say(T("now_playing.the_tv_did_not_2"), true); return@post }
            val box = input(T("playing.clip_empty"))
            box.setText(text)
            box.setSingleLine(false)                                  // a box to read and edit, not input()'s one line
            box.minLines = 4
            box.maxLines = 8
            box.gravity = Gravity.TOP or Gravity.START
            box.background = box(BTN, 12)                             // the dialog is CARD: the box must stand out from it
            val body = col()
            body.setPadding(dp(24), dp(12), dp(24), dp(20))           // no dialog buttons under it: the row is the last thing
            body.addView(box, LinearLayout.LayoutParams(-1, -2))
            val acts = LinearLayout(this)
            body.addView(acts, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) })
            val d = Popup.Builder(this).setTitle(T("now_playing.the_tvs_clipboard")).setView(body).create()
            val toTv = { paste: Boolean ->
                val s = box.text.toString()
                if (s.isEmpty()) say(T("playing.no_text"), false)
                else {
                    d.dismiss()
                    Thread {
                        val ok = try { tv.setClipboard(s.take(100000), paste); true } catch (e: Exception) { false }
                        ui.post { say(if (!ok) T("now_playing.the_tv_did_not_3") else if (paste) T("playing.pasted") else T("playing.copied_to_tv"), !ok) }
                    }.start()
                }
            }
            // one row of short words: the title says whose clipboard it is; what each does in full goes to screen readers
            for ((i, b) in listOf(
                Triple(T("now_playing.copy"), T("now_playing.copy_to_the_phone"), {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TV", box.text.toString()))
                    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, T("playing.copied_here"), Toast.LENGTH_SHORT).show()   // Android 13+ says it itself
                    d.dismiss()
                }),
                Triple(T("now_playing.send"), T("now_playing.put_on_the_tvs"), { toTv(false) }),
                Triple(T("now_playing.paste"), T("now_playing.paste_where_the_tv"), { toTv(true) }),
            ).withIndex()) {
                val btn = button(b.first, if (i == 2) ACCENT else BTN)
                btn.contentDescription = b.second
                btn.setPadding(dp(4), 0, dp(4), 0)
                btn.setOnClickListener { b.third() }
                acts.addView(btn, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            d.show()
        }
    }.start()
}
