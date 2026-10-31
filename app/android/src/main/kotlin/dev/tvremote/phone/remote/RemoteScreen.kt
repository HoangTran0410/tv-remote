package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.manage.progress
import dev.tvremote.phone.media.NowApp
import dev.tvremote.phone.media.PlayingBar
import dev.tvremote.phone.media.listenMenu
import dev.tvremote.phone.media.micMenu
import dev.tvremote.phone.media.micTap
import dev.tvremote.phone.media.say
import dev.tvremote.phone.media.toggleEven
import dev.tvremote.phone.media.toggleHq
import dev.tvremote.phone.media.toggleListen
import dev.tvremote.phone.picker.adbHelp
import dev.tvremote.phone.picker.findSettings
import dev.tvremote.phone.picker.message
import dev.tvremote.phone.picker.name
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.picture.DrawView
import dev.tvremote.phone.picture.FLOAT_NONE
import dev.tvremote.phone.picture.VideoFrame
import dev.tvremote.phone.picture.airCan
import dev.tvremote.phone.picture.airMode
import dev.tvremote.phone.picture.airPad
import dev.tvremote.phone.picture.airPick
import dev.tvremote.phone.picture.airStart
import dev.tvremote.phone.picture.drawTools
import dev.tvremote.phone.picture.drawTouch
import dev.tvremote.phone.picture.picLive
import dev.tvremote.phone.picture.pipUpdate
import dev.tvremote.phone.picture.stopDrawing
import dev.tvremote.phone.picture.toggleFloat
import dev.tvremote.phone.scenes.voice
import dev.tvremote.phone.ui.ACCENT
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.CARD
import dev.tvremote.phone.ui.DIM
import dev.tvremote.phone.ui.GREEN
import dev.tvremote.phone.ui.HELD
import dev.tvremote.phone.ui.IconButton
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.RED
import dev.tvremote.phone.ui.StatusText
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.barFlags
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.cell
import dev.tvremote.phone.ui.chips
import dev.tvremote.phone.ui.col
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.gap
import dev.tvremote.phone.ui.head
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.inDialog
import dev.tvremote.phone.ui.input
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.menu
import dev.tvremote.phone.ui.oval
import dev.tvremote.phone.ui.say
import dev.tvremote.phone.ui.setLabel
import dev.tvremote.phone.ui.setOn

import dev.tvremote.core.T
import android.content.ContentValues
import android.content.res.Configuration
import android.view.Menu
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.tvremote.core.picture.Tv
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

// The remote screen: picture, keys, direction pad / touchpad, number keys; and laying it out for the current mode.

@Suppress("DEPRECATION")
internal fun MainActivity.layoutRemote() {
    val video = video && basic == null                            // keys only: there is no picture to show
    videoBtn.visibility = if (basic == null) View.VISIBLE else View.GONE
    basicBar.visibility = if (basic == null || kind() != dev.tvremote.core.remote.Remote.Kind.ANDROID) View.GONE else View.VISIBLE   // LG / Samsung have no ADB to turn on
    greyRows()
    val full = video && host != null && !floating && (fullscreen || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)   // landscape: the picture fills the screen
    (controls.parent as View).visibility = if (full) View.GONE else View.VISIBLE
    if (full) controls.findFocus()?.let { it.clearFocus(); (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }   // its box gone, so its keyboard
    topBar.visibility = if (full) View.GONE else View.VISIBLE
    stage.layoutParams = if (full) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, -2)   // weight: room for the drawing tools under it
    if ((!video || floating) && drawing) stopDrawing()                              // nothing to draw on
    if (floatMode == FLOAT_NONE) frame.layoutParams = FrameLayout.LayoutParams(-1, if (full) -1 else -2)   // floating: its player sizes it
    stage.visibility = if (video && !floating) View.VISIBLE else View.GONE   // floating: the picture is in the small player, the keys get the room
    listenBtn.visibility = if (basic == null) View.VISIBLE else View.GONE   // hearing the TV needs ADB too
    listenBtn.setOn(listen, 16)
    listenBtn.contentDescription = if (listen) T("remote_screen.stop_listening_on_the") else T("remote_screen.hear_the_tv_on")
    exitBtn.visibility = if (full) View.VISIBLE else View.GONE
    picBar.visibility = if (video && !full) View.VISIBLE else View.GONE   // the picture's and the sound's rows: not hidden in a menu
    soundBar.visibility = if (listen && basic == null && !full) View.VISIBLE else View.GONE
    qualBtn.visibility = if (video) View.VISIBLE else View.GONE
    fullBtn.visibility = qualBtn.visibility
    pipBtn.visibility = if (qualBtn.visibility == View.VISIBLE && Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) View.VISIBLE else View.GONE
    pipBtn.setOn(floating, 10)                                        // on while the picture floats
    pipBtn.alpha = if (picLive() || floating) 1f else 0.4f             // only a live picture can float
    listenChip.visibility = if (listen) View.VISIBLE else View.GONE  // the sound's own settings, only while it plays
    val share = prefs.getBoolean("listenShare", false)
    listenChip.text = (if (share || listenTvLoud) T("listen.both") else T("remote_screen.phone")) + " ▾"   // "phone" says the TV is quiet: not when the TV could not be silenced
    listenChip.setCompoundDrawables(listenArt(share), null, null, null)
    val hq = prefs.getBoolean("listenHq", false)
    hqBtn.visibility = listenChip.visibility
    hqBtn.setOn(hq, 10, DIM)                                          // on: the tint, like every on/off button (setOn)
    val even = prefs.getBoolean("listenEven", false)
    dev.tvremote.core.picture.SoundPace.even = even                   // read as the next sound session starts
    evenBtn.visibility = listenChip.visibility
    evenBtn.setOn(even, 10, DIM)
    val boost = listenGain()
    dev.tvremote.core.picture.SoundBoost.times = boost
    boostBtn.visibility = listenChip.visibility
    boostBtn.text = "🔊 " + gainWords(boost)
    boostBtn.setOn(boost != 1f, 10, DIM)
    qualBtn.text = Tv.QUALITY[prefs.getInt("quality", 1).coerceIn(0, Tv.QUALITY.size - 1)][0].toString() + "p ▾"
    window.decorView.systemUiVisibility = if (full) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else barFlags()
    videoBtn.setIcon(if (video) "@eye_off" else "@eye", TEXT)
    videoBtn.setOn(video, 16)                                         // on while the picture shows, like every on/off button
    videoBtn.contentDescription = if (video) T("remote_screen.hide_the_tv_picture") else T("remote_screen.show_the_tv_picture")
    pipUpdate()                                                       // the sound action of the PiP window follows 🎧
}

/** Where the sound plays, drawn: the phone alone, or the TV and the phone. */
internal fun MainActivity.listenArt(share: Boolean): android.graphics.drawable.Drawable {
    val art = { name: String -> resources.getDrawable(resources.getIdentifier("ic_$name", "drawable", packageName), null).mutate().apply { setTint(TEXT) } }
    val s = dp(18)
    val d = if (!share) art("phone") else android.graphics.drawable.LayerDrawable(arrayOf(art("tv"), art("phone"))).apply {
        setLayerInset(0, 0, 0, s + dp(2), 0); setLayerInset(1, s + dp(2), 0, 0, 0)
    }
    d.setBounds(0, 0, if (share) s * 2 + dp(2) else s, s)
    return d
}

private val PIC_FPS = intArrayOf(0, 15, 30, 60)                       // the same chips as the page's (js/screen.js picDialog)
private val PIC_RATE = intArrayOf(0, 1, 2, 4, 8)                      // Mbit/s

/**
 * The quality chip under the picture: one dialog with the levels, lightest first, then frames per second, bit rate and the TV's
 * encoder of the person's own, over the level's (0: the level's). Each tap restarts the picture.
 */
internal fun MainActivity.qualityMenu() {
    val body = col()
    val level = LinearLayout(this); val fps = LinearLayout(this); val rate = LinearLayout(this); val enc = LinearLayout(this)
    fun draw() {
        val words = { v: Int -> if (v == 0) T("screen.auto") else v.toString() }   // as the encoder row; "Mặc định" is cut in a fifth of a 360dp box
        val now = prefs.getInt("quality", 1).coerceIn(0, Tv.QUALITY.size - 1)
        level.removeAllViews(); fps.removeAllViews(); rate.removeAllViews(); enc.removeAllViews()
        level.addView(chips(Tv.LEVELS.map { it.toString() to Tv.QUALITY[it][0].toString() + "p" }, now.toString()) { setQuality(it.toInt()); draw() }, LinearLayout.LayoutParams(-1, -2))
        fps.addView(chips(PIC_FPS.map { it.toString() to words(it) }, prefs.getInt("picFps", 0).toString()) { tune("picFps", it.toInt()); draw() }, LinearLayout.LayoutParams(-1, -2))
        rate.addView(chips(PIC_RATE.map { it.toString() to words(it) }, prefs.getInt("picRate", 0).toString()) { tune("picRate", it.toInt()); draw() }, LinearLayout.LayoutParams(-1, -2))
        enc.addView(chips(listOf("auto" to T("screen.auto"), "hw" to T("screen.hardware"), "sw" to T("screen.software")),
            prefs.getString("picEnc", "auto")!!) { if (prefs.getString("picEnc", "auto") != it) { prefs.edit().putString("picEnc", it).apply(); if (pic != null) { stopPicture(); startPicture() } }; draw() }, LinearLayout.LayoutParams(-1, -2))
    }
    draw()
    body.addView(head(T("remote_screen.size")))                       // a smaller number is lighter on the TV and the network
    body.addView(level, gap(6))
    body.addView(head(T("remote_screen.frames_per_second")), gap(14))
    body.addView(fps, gap(6))
    body.addView(head(T("remote_screen.bit_rate_mbit_s")), gap(14))
    body.addView(rate, gap(6))
    body.addView(head(T("remote_screen.encoder")), gap(14))           // auto: the TV's hardware one, its software one when that sends nothing
    body.addView(enc, gap(6))
    Popup.Builder(this).setTitle(T("remote_screen.picture_quality")).setView(inDialog(body))
        .setPositiveButton(T("drawing.done"), null).show()
}

private fun MainActivity.tune(key: String, v: Int) {
    if (prefs.getInt(key, 0) == v) return
    prefs.edit().putInt(key, v).apply()
    if (pic != null) { stopPicture(); startPicture() }                // fixed when the picture's session starts
}

private fun MainActivity.setQuality(level: Int) {
    if (level == prefs.getInt("quality", 1).coerceIn(0, Tv.QUALITY.size - 1)) return
    prefs.edit().putInt("quality", level).apply()
    stopPicture()                                                     // the size is fixed when the picture's session starts
    layoutRemote()
    startPicture()
}

// ---------------------------------------------------------------- remote screen
internal fun MainActivity.buildRemote() {
    remote = col()
    status = StatusText(this)
    videoBtn = icon("@eye_off", "", BTN)
    videoBtn.setOnClickListener { toggleVideo() }
    basicBar = button(T("remote_screen.keys_only_turn_on"), ACCENT)
    basicBar.textSize = 13f
    basicBar.setOnClickListener { adbHelp() }
    topBar = LinearLayout(this)
    val bar = topBar
    bar.gravity = Gravity.CENTER_VERTICAL
    val head = LinearLayout(this)
    head.gravity = Gravity.CENTER_VERTICAL
    val leave = icon("@left", T("remote_screen.back_to_the_tvs"), BTN)   // where the logo was: the way out, without the phone's own Back
    leave.background = box(BTN, 16)
    @Suppress("DEPRECATION") leave.setOnClickListener { onBackPressed() }
    head.addView(leave, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(10) })
    val words = col()
    words.setPadding(dp(10), dp(4), dp(8), dp(4))
    words.addView((status as StatusText).name)
    words.addView(status)
    head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
    bar.addView(head, LinearLayout.LayoutParams(0, -2, 1f))
    val tools = LinearLayout(this)
    tools.gravity = Gravity.CENTER_VERTICAL or Gravity.END
    listenBtn = icon("@headphones", T("remote_screen.hear_the_tv_on"), BTN)
    listenBtn.setOnClickListener { toggleListen() }
    val apps = icon("@apps", T("remote_screen.manage"), BTN)          // apps, files, health, automation, setup: the web's tabs, up here as there
    apps.setOnClickListener {
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
        if (!needsAdb()) managing.open(host)
    }
    for (b in listOf(listenBtn, videoBtn, apps)) tools.addView(b, LinearLayout.LayoutParams(dp(46), dp(40)).apply { rightMargin = dp(if (b === apps) 10 else 6) })
    bar.addView(tools, LinearLayout.LayoutParams(-2, -2))
    bar.setPadding(0, dp(8), 0, dp(10))                               // room between the buttons and the picture
    remote.addView(bar)

    frame = VideoFrame(this)
    frame.setBackgroundColor(Color.BLACK)
    surface = SurfaceView(this)
    surface.holder.addCallback(this)
    frame.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    surface.setOnTouchListener { v, e ->
        val tv = pic                                                  // the picture's session: its size places the finger
        if (tv == null || !tv.ready()) return@setOnTouchListener true
        if (drawing) { drawTouch(e, v); return@setOnTouchListener true }
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 2
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1
            else -> -1
        }
        if (action >= 0) tv.touch(action, e.x / v.width, e.y / v.height)
        when (action) { 0 -> tapMark.down(e.x, e.y); 2 -> tapMark.move(e.x, e.y); 1 -> tapMark.up() }
        true
    }
    tapMark = TapMark(this)
    frame.addView(tapMark, FrameLayout.LayoutParams(-1, -1))
    drawView = DrawView(this)
    frame.addView(drawView, FrameLayout.LayoutParams(-1, -1))
    stage = FrameLayout(this)
    stage.setBackgroundColor(Color.BLACK)
    stage.addView(frame, FrameLayout.LayoutParams(-1, -2))
    exitBtn = icon("@close", T("remote_screen.leave_full_screen"), 0x66000000)   // the only thing on the picture, and only in full screen
    exitBtn.alpha = 0.5f
    exitBtn.setOnClickListener { setFullscreen(false) }
    val fp = FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP or Gravity.END)
    fp.setMargins(dp(8), dp(8), dp(8), dp(8))
    stage.addView(exitBtn, fp)
    remote.addView(stage, LinearLayout.LayoutParams(-1, -2))
    drawBar = drawTools()
    remote.addView(drawBar, LinearLayout.LayoutParams(-1, -2))

    controls = col()
    controls.setPadding(dp(14), dp(12), dp(14), dp(20))
    // the picture's quality and full screen, first in the scrolling part: not on the picture (it covers nothing) nor fixed under it
    picBar = LinearLayout(this)
    picBar.gravity = Gravity.CENTER_VERTICAL
    soundBar = LinearLayout(this)                                     // 🎧's own row: beside the picture's it ran off a phone's edge
    soundBar.gravity = Gravity.CENTER_VERTICAL
    qualBtn = android.widget.TextView(this).apply {
        setTextColor(TEXT); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
        background = box(BTN, 10); contentDescription = T("remote_screen.picture_quality"); setOnClickListener { qualityMenu() }
    }
    listenChip = android.widget.TextView(this).apply {                // how 🎧 plays: picked here, used by 🎧 on the top bar
        setTextColor(TEXT); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(12), 0); maxLines = 1; compoundDrawablePadding = dp(6)
        background = box(BTN, 10); contentDescription = T("remote.how_to_hear_the"); setOnClickListener { listenMenu() }
    }
    soundBar.addView(listenChip, LinearLayout.LayoutParams(-2, dp(34)))
    hqBtn = android.widget.TextView(this).apply {
        text = "HQ"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        contentDescription = T("remote.high_quality_sound_160"); setOnClickListener { toggleHq() }
    }
    soundBar.addView(hqBtn, LinearLayout.LayoutParams(dp(44), dp(34)).apply { leftMargin = dp(8) })
    evenBtn = android.widget.TextView(this).apply {                   // half a second later, never a stall (SoundPace)
        text = T("remote.even"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
        contentDescription = T("remote.steadier_sound_half_a"); setOnClickListener { toggleEven() }
    }
    soundBar.addView(evenBtn, LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(8) })
    boostBtn = android.widget.TextView(this).apply {                 // louder here, ×0.5 … ×10 on a slider (the page's 🔊)
        textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0)
        contentDescription = T("remote_screen.loudness_on_the_phone")
        setOnClickListener { gainDialog() }
    }
    soundBar.addView(boostBtn, LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(8) })
    picBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    picBar.addView(qualBtn, LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(8) })
    fullBtn = icon("@expand", T("remote.full_screen"), BTN)
    fullBtn.setOnClickListener { setFullscreen(true) }
    pipBtn = icon("@pip", T("float.pip"), BTN)                        // Pip.kt: the picture in a small window over other apps
    pipBtn.setOnClickListener { toggleFloat() }
    picBar.addView(pipBtn, LinearLayout.LayoutParams(dp(44), dp(34)).apply { leftMargin = dp(8) })
    picBar.addView(fullBtn, LinearLayout.LayoutParams(dp(44), dp(34)).apply { leftMargin = dp(8) })
    controls.addView(soundBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    controls.addView(picBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    val bb = LinearLayout.LayoutParams(-1, dp(44))
    bb.bottomMargin = dp(12)
    controls.addView(basicBar, bb)
    setupBar = button("", GREEN)                                      // SetupBanner.kt
    setupBar.textSize = 13f
    setupClose = icon("@close", T("setup_banner.no_thanks"), BTN)     // only on "Install TV Setup": hides it for good on this TV
    setupRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    setupRow.addView(setupBar, LinearLayout.LayoutParams(0, dp(44), 1f))
    setupRow.addView(setupClose, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
    controls.addView(setupRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    playing = PlayingBar(this)
    controls.addView(playing.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    nowApp = NowApp(this)                                             // NowApp.kt
    kbdWatch = KbdWatch(this).also { it.start() }                    // KbdWatch.kt
    controls.addView(nowApp.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    val first = LinearLayout(this)
    val power = icon("@power", T("remote.power"), BTN)
    power.setOnClickListener {
        if (basic != null) { press(26); return@setOnClickListener }
        val tv = tv
        if (tv == null || !tv.ready()) return@setOnClickListener
        Thread {
            val awake = try { !tv.shell("dumpsys power | grep -m1 mWakefulness=").contains("Asleep") } catch (e: Exception) { true }
            ui.post {
                if (isFinishing || this.tv !== tv) return@post
                if (!awake) {
                    tv.press(224)
                    Toast.makeText(this, T("remote_screen.waking_the_tv"), Toast.LENGTH_SHORT).show()
                } else Popup.Builder(this).setMessage(T("remote_screen.put_the_tv_in"))
                    .setPositiveButton(T("remote_screen.standby")) { _, _ -> if (tv.ready()) tv.press(223) }
                    .setNegativeButton(T("common.cancel"), null).show()
            }
        }.start()
    }
    first.addView(power, cell(58, false))
    val mic = icon("@mic", T("remote.speak"), BTN)
    mic.setOnClickListener { micTap() }                         // keys-only too: keys, apps and scenes still work (Automation.kt)
    mic.setOnLongClickListener { micMenu(); true }                // the other way to talk, and the TV's own Assistant screen
    first.addView(mic, cell(58, true))
    // The typing box and the "Hỏi TV" box fold out under this row, as the extra keys do: the arrows stay close (the web's ⌨️)
    val kbdBtn = icon("@keyboard", T("rkey.keyboard"), BTN)
    first.addView(kbdBtn, cell(58, true))
    // Everything used less often sits behind this button: one tap shows the extra keys, another hides them.
    val moreBtn = icon("@more", T("remote.more_keys"), BTN)
    first.addView(moreBtn, cell(58, true))
    controls.addView(captioned(first))
    moreBtn.caption = T("remote.more")                               // short under the icon; read out in full
    val more = col()
    for (i in remoteSpec().rows["more"]!!.indices) more.addView(remoteRow("more", i, 58), gap(8))    // remote.json (PhoneRemote.kt)
    val kbd = col()
    val ime = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    val fold = { which: View, btn: IconButton, key: String, on: Boolean ->
        if (!on && which.visibility == View.VISIBLE) which.findFocus()?.let { it.clearFocus(); ime.hideSoftInputFromWindow(it.windowToken, 0) }
        which.visibility = if (on) View.VISIBLE else View.GONE
        btn.setOn(on, 16)
        if (key == "more") prefs.edit().putBoolean(key, on).apply()
    }
    val showMore = { on: Boolean -> if (on) fold(kbd, kbdBtn, "kbd", false); fold(more, moreBtn, "more", on) }
    val showKbd = { on: Boolean -> if (on) fold(more, moreBtn, "more", false); fold(kbd, kbdBtn, "kbd", on) }   // one at a time
    prefs.edit().remove("kbd").apply()                                // it starts shut: open again by itself it looked like a keyboard nobody asked for
    fold(kbd, kbdBtn, "kbd", false)                                   // a new col() is shown: shut it before anyone sees it
    showMore(prefs.getBoolean("more", false))
    moreBtn.setOnClickListener { slide(controls); showMore(more.visibility != View.VISIBLE) }
    val kbdGo = { at: android.widget.EditText -> if (kbd.visibility != View.VISIBLE) { slide(controls); showKbd(true) }; at.requestFocus(); ime.showSoftInput(at, 0) }
    kbdBtn.setOnClickListener {
        if (kbd.visibility != View.VISIBLE) kbdGo(textBox)
        else { kbdWatch.closedByPerson(); slide(controls); showKbd(false) }
    }
    kbdOpen = { on -> if (on) kbdGo(textBox) else if (kbd.visibility == View.VISIBLE) { slide(controls); showKbd(false) } }   // showKbd hides the phone keyboard by the focus
    controls.addView(more)
    controls.addView(kbd)
    // The remote's middle, one of three: the arrows; a touchpad of keys (swipes become arrow keys, a tap is OK: lists, a pointer
    // that follows the arrows); the mouse pad (Air Mouse: a real pointer moved by the phone or the finger, for a browser, selecting
    // text, dragging). One button under it says which and opens the choice of the three.
    val arrows = dpad()
    val tpad = touchpad()
    val mpad = airPad()
    val modes = listOf("arrows" to "@move|" + T("remote_screen.mid_arrows"), "touch" to "@hand|" + T("remote_screen.mid_touch"), "mouse" to "@mouse|" + T("rkey.air"))
    val mode = button(modes[0].second, BTN)                       // the one in use, with its icon
    mode.textSize = 13f; mode.setPadding(dp(12), 0, dp(12), 0); mode.minHeight = 0; mode.minimumHeight = 0
    val pick = { m: String, asked: Boolean ->
        arrows.visibility = if (m == "arrows") View.VISIBLE else View.GONE
        tpad.visibility = if (m == "touch") View.VISIBLE else View.GONE
        mpad.visibility = if (m == "mouse") View.VISIBLE else View.GONE
        mode.setLabel(modes.first { it.first == m }.second + "  ▾")
        airBtn?.setOn(m == "mouse", 16)
        prefs.edit().putString("mid", m).apply()
        airStart(m)                                                   // the gyroscope only while the mouse pad is shown
        val pad = if (m == "mouse") mpad else if (m == "touch") tpad else null
        if (asked && pad != null) pad.post {                         // just switched on: the whole pad in sight, its chooser under it too
            val sv = pad.parent?.parent as? ScrollView ?: return@post
            sv.smoothScrollTo(0, pad.top - maxOf(0, (sv.height - pad.height - dp(60)) / 2))
        }
    }
    airPick = { m -> pick(if (m == airMode()) "arrows" else m, true) }
    pick(prefs.getString("mid", null) ?: if (prefs.getBoolean("pad", false)) "touch" else "arrows", false)   // "pad": the old touchpad switch
    mode.setOnClickListener {
        val subs = listOf(T("remote_screen.mid_arrows_sub"), T("remote_screen.mid_touch_sub"), T("remote_screen.mid_mouse_sub"))
        menu(T("remote_screen.mid_title"), modes.indexOfFirst { it.first == airMode() }, modes.mapIndexed { i, (m, t) ->
            Triple(t, subs[i]) { if (m == "mouse" && !airCan()) say(T("air.needs_adb"), true); pick(m, true) }
        })
    }
    controls.addView(arrows, gap(16))
    controls.addView(tpad, LinearLayout.LayoutParams(-1, dp(250)).also { it.topMargin = dp(16) })
    controls.addView(mpad, LinearLayout.LayoutParams(-1, dp(280)).also { it.topMargin = dp(16) })
    val chips = LinearLayout(this)
    chips.addView(mode, LinearLayout.LayoutParams(-2, dp(36)))
    val sp2 = LinearLayout.LayoutParams(-2, -2)
    sp2.gravity = Gravity.CENTER_HORIZONTAL
    sp2.topMargin = dp(8)
    controls.addView(chips, sp2)
    controls.addView(remoteRow("nav", 0, 52, 0), gap(10))                    // Apps, Back, Home, Settings (Home and Back in the middle, for either thumb) across the width, close under the D-pad
    // the same rows, in the same order, as the two web remotes: remote.json
    // Volume on the left, channels on the right, each one tall bar for the thumb; the media keys between them.
    // The same block, in the same order, as the two web remotes.
    val mid = col()
    for (i in remoteSpec().rows["media"]!!.indices) { val row = remoteRow("media", i, 58, 0); if (i == 0) mid.addView(row) else mid.addView(row, gap(8)) }   // two keys across the middle, not half of it
    val cluster = LinearLayout(this)
    cluster.addView(pill("Vol", "@plus", 24, T("remote.volume_up"), "@minus", 25, T("remote.volume_down")), LinearLayout.LayoutParams(0, -1, 1f))
    val mp = LinearLayout.LayoutParams(0, -2, 2.4f)
    mp.leftMargin = dp(10)
    mp.rightMargin = dp(10)
    cluster.addView(mid, mp)
    cluster.addView(pill(T("remote.ch_2"), "@up", 166, T("remote_screen.channel_up"), "@down", 167, T("remote_screen.channel_down")), LinearLayout.LayoutParams(0, -1, 1f))
    controls.addView(cluster, gap(16))

    val line = LinearLayout(this)
    liveBtn = icon("@zap", T("remote_screen.live_typing"), BTN)    // lit while on (Typing.kt)
    liveBtn.setPadding(0, 0, 0, 0)
    liveBtn.setOnClickListener { setLive(!live) }
    val lb = LinearLayout.LayoutParams(dp(48), dp(48))
    lb.rightMargin = dp(6)
    line.addView(liveBtn, lb)
    textBox = input("")
    textBox.imeOptions = EditorInfo.IME_ACTION_SEND
    textBox.setSingleLine(true)
    textBox.setOnEditorActionListener { _, _, _ ->
        sendText()
        true
    }
    textBox.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (!quiet && !s.isNullOrEmpty()) kbdWatch.typedByPerson()    // then the TV's keyboard going away must not take the box with it
            typedLive(s.toString())
        }
    })
    line.addView(textBox, LinearLayout.LayoutParams(0, dp(48), 1f))
    val send = icon("@send", T("notify.send"), ACCENT)
    send.setOnClickListener { sendText() }
    val lp = LinearLayout.LayoutParams(-2, dp(48))
    lp.leftMargin = dp(6)
    lp.width = dp(60)
    line.addView(send, lp)
    kbd.addView(line, gap(8))
    val edit = keys(48, "@backspace|" + T("auto.delete"), 67, "@enter|Enter", 66)
    val digits = button("123", BTN)                               // shows / hides the number keys (channels, PIN codes)
    digits.contentDescription = T("remote.number_keys")
    val dl = LinearLayout.LayoutParams(dp(64), dp(48))
    dl.rightMargin = dp(8)
    edit.addView(digits, 0, dl)
    kbd.addView(edit, gap(8))
    val pad = col()
    pad.addView(keys(48, "1", 8, "2", 9, "3", 10), gap(8))
    pad.addView(keys(48, "4", 11, "5", 12, "6", 13), gap(8))
    pad.addView(keys(48, "7", 14, "8", 15, "9", 16), gap(8))
    pad.addView(keys(48, "", 0, "0", 7, "", 0), gap(8))
    pad.visibility = if (prefs.getBoolean("numpad", false)) View.VISIBLE else View.GONE
    digits.setOnClickListener {
        val show = pad.visibility != View.VISIBLE
        slide(controls)
        pad.visibility = if (show) View.VISIBLE else View.GONE
        prefs.edit().putBoolean("numpad", show).apply()
    }
    kbd.addView(pad)
    // a sentence rather than letters: a command is done here, anything else is looked up (the web's "Hỏi TV" box)
    val askLine = LinearLayout(this)
    val ask = input(T("remote.ask_the_tv_open"))
    ask.imeOptions = EditorInfo.IME_ACTION_SEARCH
    ask.setSingleLine(true)
    val asked = { val s = ask.text.toString().trim(); if (s.isNotEmpty()) { ask.setText(""); voice(s) } }
    ask.setOnEditorActionListener { _, _, _ -> asked(); true }
    askLine.addView(ask, LinearLayout.LayoutParams(0, dp(48), 1f))
    val go = icon("@search", T("remote.commands_open_youtube_mute"), ACCENT)
    go.setOnClickListener { asked() }
    askLine.addView(go, LinearLayout.LayoutParams(dp(60), dp(48)).apply { leftMargin = dp(6) })
    kbd.addView(askLine, gap(8))
    askBox = { kbdGo(ask) }
    setLive(prefs.getBoolean("live", false))

    val bye = button(T("remote_screen.disconnect"), 0)
    bye.setTextColor(RED)
    bye.setOnClickListener { leave() }
    val bp = LinearLayout.LayoutParams(-1, dp(48))
    bp.topMargin = dp(10)
    controls.addView(bye, bp)

    val sv = ScrollView(this)
    sv.addView(controls)
    remote.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
    layoutRemote()
}

/** A row of equal-width remote keys: label, Android key code, label, key code... "@icon|words" shows an icon. An empty label leaves a gap. */
/** One tall bar with a key at each end and its name in the middle: volume, channels. */
internal fun MainActivity.pill(name: String, upIcon: String, up: Int, sayUp: String, downIcon: String, down: Int, sayDown: String): LinearLayout {
    val p = col()
    p.background = box(BTN, 26)
    val a = icon(upIcon, sayUp, 0)
    bindKey(a, up, box(HELD, 26))
    val b = icon(downIcon, sayDown, 0)
    bindKey(b, down, box(HELD, 26))
    val l = label(name, 13, MUTED)
    l.gravity = Gravity.CENTER
    l.typeface = Typeface.DEFAULT_BOLD
    p.addView(a, LinearLayout.LayoutParams(-1, 0, 1f))
    p.addView(l, LinearLayout.LayoutParams(-1, -2))
    p.addView(b, LinearLayout.LayoutParams(-1, 0, 1f))
    return p
}

/** Shows each icon button's name under its icon. */
internal fun MainActivity.captioned(row: LinearLayout): LinearLayout {
    for (i in 0 until row.childCount) (row.getChildAt(i) as? IconButton)?.let { it.caption = it.contentDescription?.toString() }
    return row
}

/** Saves what is on the TV's screen as a picture in this phone's Download folder. */
internal fun MainActivity.shot() {
    if (needsAdb()) return
    val tv = tv
    if (tv == null || !tv.ready()) { Toast.makeText(this, T("errors.still_connecting_tv_over"), Toast.LENGTH_SHORT).show(); return }
    val b = shotBtn
    if (b?.busy == true) return                                       // one at a time: a second tap is not a second picture
    b?.busy = true
    Toast.makeText(this, T("remote_screen.taking_a_screenshot_of"), Toast.LENGTH_SHORT).show()
    Thread {
        var result: String
        try {
            val png = tv.screenshot() ?: throw java.io.IOException(T("remote_screen.the_tv_sent_no"))
            val fileName = "tv-" + System.currentTimeMillis() / 1000 + ".png"
            val out: OutputStream
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                out = contentResolver.openOutputStream(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!)!!
                result = T("remote_screen.saved_to_download", "file" to fileName)
            } else {
                val f = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                out = FileOutputStream(f)
                result = T("remote_screen.saved", "file" to f.absolutePath)
            }
            out.use { it.write(png) }
        } catch (e: Exception) {
            result = T("remote_screen.screenshot_failed", "why" to e.message)
        }
        val msg = result
        ui.post { b?.busy = false; if (!isFinishing) Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }.start()
}

/** Menu, Input, TV settings, Guide, Live TV: keys that should open something. When the TV's screen ignores one, say so, so it does not look like a dead button. */
internal fun MainActivity.opens(code: Int) {
    if (basic != null) { if (code == 176) findSettings() else press(code); return }
    val tv = tv
    if (tv == null || !tv.ready()) return
    Thread {
        if (code in dev.tvremote.core.ontv.RulesClient.SETUP_KEYS) {   // an app key: TV Setup first, then "the app is not on the TV", as in press()
            if (dev.tvremote.core.ontv.RulesClient.key({ tv.shell(it) }, code)) return@Thread
            if (dev.tvremote.core.ontv.RulesClient.appMissing({ tv.shell(it) }, code)) { ui.post { Toast.makeText(this, T("remote.app_key_missing"), Toast.LENGTH_LONG).show() }; return@Thread }
        }
        if (!tv.pressSeen(code)) ui.post {
            if (!isFinishing && this.tv === tv) Toast.makeText(this, T("remote.key_no_reaction"), Toast.LENGTH_SHORT).show()
        }
    }.start()
}

internal fun MainActivity.keys(height: Int, vararg labelAndCode: Any): LinearLayout {
    val row = LinearLayout(this)
    for (i in labelAndCode.indices step 2) {
        var label = labelAndCode[i] as String
        var say = label
        val bar = label.indexOf('|')
        if (bar > 0) {
            say = label.substring(bar + 1)
            label = label.substring(0, bar)
        }
        val code = labelAndCode[i + 1] as Int
        val v: View
        if (label.isEmpty()) v = View(this)
        else {
            val b = if (bar > 0) icon(label, say, BTN) else button(label, BTN)
            if (bar < 0) b.textSize = 18f
            if (code in Tv.OPENERS) {
                val held = box(HELD, 16)
                b.setOnClickListener { flash(b, held); opens(code) }
            } else bindKey(b, code, box(HELD, 16))
            v = b
        }
        row.addView(v, cell(height, i > 0))
    }
    return row
}

/**
 * A tap counts when the finger lifts on the button: touching it sends nothing, and sliding off it (or starting to scroll)
 * cancels. Then the button flashes amber. Keeping the finger on it for 0.4 s is a long press on the TV: the key goes
 * down, repeats, and comes up when the finger lifts or leaves; the button stays amber meanwhile.
 */
internal fun MainActivity.bindKey(b: Button, code: Int, held: Drawable) {
    val normal = b.background
    var holding = false
    var count = 0
    var start: Runnable? = null
    val again = object : Runnable {
        override fun run() {
            if (!holding) return
            keyDown(code, ++count)
            ui.postDelayed(this, 80)
        }
    }
    val stop = {
        start?.let { ui.removeCallbacks(it) }
        start = null
        if (holding) {
            holding = false
            ui.removeCallbacks(again)
            keyUp(code)
            b.background = normal
        }
    }
    b.setOnTouchListener { v, e ->
        val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
        if (b.tag === KIND_OFF) { if (e.actionMasked == MotionEvent.ACTION_UP && inside) kindOff(); return@setOnTouchListener true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val go = Runnable {
                    if (!live()) return@Runnable
                    start = null
                    holding = true
                    count = 0
                    b.background = held
                    keyDown(code, 0)
                    ui.postDelayed(again, 80)
                }
                start = go
                ui.postDelayed(go, 400)
            }
            MotionEvent.ACTION_MOVE -> if (!inside) stop()
            MotionEvent.ACTION_CANCEL -> stop()
            MotionEvent.ACTION_UP -> {
                val tap = start != null && inside                 // lifted on the button before it became a hold
                stop()
                if (tap && live()) {
                    press(code)
                    flash(b, held)
                }
            }
        }
        true
    }
}

/** Amber for a moment: the press was taken. */
internal fun MainActivity.flash(b: Button, held: Drawable) {
    val normal = b.background
    if (normal === held) return
    b.background = held
    ui.postDelayed({ b.background = normal }, 160)
}

/** The round direction pad: four arrows on a disc, OK in the middle. */
/**
 * A touchpad made of keys: every bit of a swipe is one arrow key in that direction, a tap is OK, a tap with two fingers
 * is Back. It is not a mouse: it moves whatever the arrows move, which on a TV browser is the pointer.
 */
internal fun MainActivity.touchpad(): View {
    val pad = FrameLayout(this)
    pad.background = box(CARD, 28)
    val hint = label(T("remote_screen.swipe_move_tap_ok"), 13, MUTED)
    hint.gravity = Gravity.CENTER
    pad.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    val step = dp(20).toFloat()
    var ax = 0f
    var ay = 0f
    var moved = false
    var fingers = 0
    var downAt = 0L
    pad.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.parent.requestDisallowInterceptTouchEvent(true)  // the page must not scroll under the finger
                ax = e.x; ay = e.y; moved = false; fingers = 1; downAt = e.eventTime
            }
            MotionEvent.ACTION_POINTER_DOWN -> fingers = maxOf(fingers, e.pointerCount)
            MotionEvent.ACTION_MOVE -> if (fingers == 1 && live()) {
                while (Math.abs(e.x - ax) >= step) { val right = e.x > ax; press(if (right) 22 else 21); ax += if (right) step else -step; moved = true }
                while (Math.abs(e.y - ay) >= step) { val down = e.y > ay; press(if (down) 20 else 19); ay += if (down) step else -step; moved = true }
            }
            MotionEvent.ACTION_UP -> if (!moved && live() && e.eventTime - downAt < 400) {
                press(if (fingers >= 2) 4 else 23)
                val normal = pad.background
                pad.background = box(HELD, 28)
                ui.postDelayed({ pad.background = normal }, 120)
            }
        }
        true
    }
    return pad
}

internal fun MainActivity.dpad(): View {
    val pad = FrameLayout(this)
    pad.background = oval(CARD)
    val arrows = arrayOf(intArrayOf(19, Gravity.TOP or Gravity.CENTER_HORIZONTAL), intArrayOf(20, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        intArrayOf(21, Gravity.START or Gravity.CENTER_VERTICAL), intArrayOf(22, Gravity.END or Gravity.CENTER_VERTICAL))
    val names = arrayOf("@up", "@down", "@left", "@right")
    val says = arrayOf(T("remote.up"), T("remote.down"), T("remote.left"), T("remote.right"))
    for (i in 0 until 4) {
        val b = icon(names[i], says[i], 0)
        b.setIcon(names[i], MUTED)
        b.size = 26f
        b.background = oval(0)
        bindKey(b, arrows[i][0], oval(HELD))
        val lp = FrameLayout.LayoutParams(dp(72), dp(72), arrows[i][1])
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        pad.addView(b, lp)
    }
    val ok = button("OK", ACCENT)
    ok.typeface = Typeface.DEFAULT_BOLD
    ok.textSize = 18f
    ok.setPadding(0, 0, 0, 0)
    ok.background = oval(ACCENT)
    ok.elevation = dp(6).toFloat()
    bindKey(ok, 23, oval(HELD))
    pad.addView(ok, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
    val wrap = LinearLayout(this)
    wrap.gravity = Gravity.CENTER
    wrap.addView(pad, LinearLayout.LayoutParams(dp(216), dp(216)))
    return wrap
}

/** Where the finger is on the TV picture, as on the web page: a dot that follows a drag and spreads into a fading ring when let go. */
internal class TapMark(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = 0xE6FFFFFF.toInt(); setShadowLayer(4 * d, 0f, 0f, 0x80000000.toInt()) }
    private var x = 0f
    private var y = 0f
    private var held = false
    private var gone = 1f                                              // 0..1 through the fade after letting go; 1: nothing to draw
    private var anim: ValueAnimator? = null

    init { isClickable = false; isFocusable = false; setLayerType(LAYER_TYPE_SOFTWARE, null) }   // the touches go on to the picture; software: shadows on the ring

    fun down(x: Float, y: Float) { anim?.cancel(); this.x = x; this.y = y; held = true; gone = 0f; invalidate() }
    fun move(x: Float, y: Float) { if (held) { this.x = x; this.y = y; invalidate() } }
    fun up() {
        if (!held) return
        held = false
        anim = ValueAnimator.ofFloat(0f, 1f).setDuration(450).apply { addUpdateListener { gone = it.animatedValue as Float; invalidate() }; start() }
    }

    override fun onDraw(c: Canvas) {
        if (!held && gone >= 1f) return
        val r = 17 * d * (1 + 0.9f * gone)
        val a = 1 - gone
        fill.alpha = (0x4D * a * (1 - gone)).toInt(); ring.alpha = (0xE6 * a).toInt()
        c.drawCircle(x, y, r, fill)
        c.drawCircle(x, y, r, ring)
    }
}

/** The keys shown or hidden below [v] slide open / shut and what is under them follows, instead of jumping (as on the page). */
internal fun slide(v: View) {
    android.transition.TransitionManager.beginDelayedTransition(v.rootView as android.view.ViewGroup, android.transition.AutoTransition().setDuration(220))
}

internal fun MainActivity.listenGain(): Float = (Math.round(prefs.getFloat("listenGain", 1f) * 2) / 2f).coerceIn(0.5f, 10f)
private fun gainWords(g: Float) = "×" + (if (g % 1f == 0f) g.toInt().toString() else g.toString())

/** 🔊: a slider from ×0.5 to ×10 in halves, heard as it moves (nothing restarts), as the page's. */
internal fun MainActivity.gainDialog() {
    val out = label(gainWords(listenGain()), 17, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    val bar = android.widget.SeekBar(this)
    bar.max = 19
    bar.contentDescription = T("listen.loudness")
    val set = { g: Float -> prefs.edit().putFloat("listenGain", g).apply(); out.text = gainWords(g); layoutRemote() }
    bar.progress = ((listenGain() - 0.5f) * 2).toInt()
    bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(b: android.widget.SeekBar, p: Int, user: Boolean) { if (user) set(0.5f + p / 2f) }
        override fun onStartTrackingTouch(b: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(b: android.widget.SeekBar) {}
    })
    val row = LinearLayout(this)
    row.gravity = Gravity.CENTER_VERTICAL
    row.addView(bar, LinearLayout.LayoutParams(0, dp(48), 1f))
    row.addView(out, LinearLayout.LayoutParams(dp(56), -2))
    val wrap = LinearLayout(this)
    wrap.orientation = LinearLayout.VERTICAL
    wrap.addView(row)
    wrap.addView(label(T("listen.boost_note"), 14, DIM), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
    val p = Popup.Builder(this).setTitle(T("remote_screen.loudness_on_the_phone_2")).setView(inDialog(wrap))
        .setNeutralButton(T("listen.back_to_x1"), null).setPositiveButton(T("drawing.done"), null).show()
    p.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { bar.progress = 1; set(1f) }
}
