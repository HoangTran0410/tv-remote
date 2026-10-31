package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.R
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.media.say
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.ui.ACCENT
import dev.tvremote.phone.ui.BG
import dev.tvremote.phone.ui.GREEN
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.cell
import dev.tvremote.phone.ui.col
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.gap
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.menu
import dev.tvremote.phone.ui.note
import dev.tvremote.phone.ui.say
import dev.tvremote.phone.ui.setOn

import dev.tvremote.core.T
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tvremote.core.remote.WebSocket

// TV Games pointer: the phone's gyroscope aims the crosshair of a web game on the TV (Balloon Pop, Fruit Slice, ...), the big button fires.
// The phone joins TV Games' own server (port 8780) as one more pad. Its link code is not scanned: TV Games writes "port token" to
// files/pad.txt, which the ADB shell reads. Moves are the pad page's own `m dx dy` lines (pad pixels), summed and sent at most every
// 8 ms from their own thread, so the sensor never waits for the network. The browser cannot do this itself: it hides the gyroscope on a
// plain-http page.

private object Gp {
    var host: FrameLayout? = null
    var sensors: SensorManager? = null
    var listener: SensorEventListener? = null
    var sensorThread: android.os.HandlerThread? = null
    @Volatile var ws: WebSocket? = null
    @Volatile var alive = false
    @Volatile var touching = false                                   // the Fire button is down
    @Volatile var ready = false
    var status: TextView? = null
    var net: Thread? = null
    var pending = FloatArray(2)                                      // summed pad pixels not sent yet (guarded by this)
}

private const val GAIN = 540f              // pad pixels per radian: the page makes 2.4 canvas pixels of each, so about 1.3 screen widths per radian
private const val DEAD = 0.02f             // rad/s of hand tremor ignored
private const val BOOST = 0.35f            // a faster wrist goes further, slow stays fine
private const val FILE = "/sdcard/Android/data/dev.tvremote.games/files/pad.txt"

/** The chip beside "now open" while TV Games is on screen: its pad page (any browser, the touch pad) or this phone's gyroscope aim. */
internal fun MainActivity.gamePadMenu() {
    val t = tv ?: return
    val ip = host ?: return
    menu(T("rkey.pad_chip").replace(Regex("^[^\\p{L}]+"), ""), -1, listOf(
        Triple("@gamepad|" + T("rkey.pad_chip").replace(Regex("^[^\\p{L}]+"), ""), T("rkey.pad_page_sub")) {
            Thread {
                val url = try { dev.tvremote.core.ontv.Games.pad({ t.shell(it) }, ip) } catch (e: Exception) { null }
                runOnUiThread { if (url == null) say(T("gpad.off"), true) else startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            }.start()
        },
        Triple("@gamepad|" + T("rkey.gamespad"), T("rkey.gamespad_sub")) { gamesPointerOpen() }))
}

internal fun MainActivity.gamesPointerOpen() {
    val t = tv
    if (t == null || !t.ready()) { say(T("air.needs_adb"), true); return }
    val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    gamesPointerClose()
    Gp.sensors = sm; Gp.alive = true; Gp.touching = false; Gp.ready = false; synchronized(Gp) { Gp.pending = FloatArray(2) }
    val view = gpView(gyro != null)
    (findViewById<ViewGroup>(android.R.id.content)).addView(view, FrameLayout.LayoutParams(-1, -1))
    Gp.host = view
    val ip = host ?: return gamesPointerClose().let { Unit }
    Gp.net = Thread({ gpNet(t, ip) }, "gpad-net").also { it.isDaemon = true; it.start() }
    gyro?.let { gpListen(sm, it) }
}

internal fun MainActivity.gamesPointerClose(): Boolean {
    val view = Gp.host ?: return false
    Gp.alive = false
    Gp.listener?.let { Gp.sensors?.unregisterListener(it) }; Gp.listener = null
    Gp.sensorThread?.quitSafely(); Gp.sensorThread = null
    try { Gp.ws?.close() } catch (e: Exception) { }
    Gp.ws = null; Gp.net?.interrupt(); Gp.net = null
    (view.parent as? ViewGroup)?.removeView(view)
    Gp.host = null; Gp.status = null
    return true
}

internal fun MainActivity.gamesPointerBack(): Boolean = gamesPointerClose()

private fun MainActivity.gpStatus(s: String) { runOnUiThread { Gp.status?.text = s } }

/** The connection: reads the link from the TV, joins as a pad, flushes the summed moves every 8 ms, pings every 2 s. */
private fun MainActivity.gpNet(t: dev.tvremote.core.picture.Tv, ip: String) {
    var wait = 400L
    while (Gp.alive) {
        try {
            val f = t.shell("cat $FILE 2>/dev/null").trim().split(' ')
            if (f.size != 2 || f[0].toIntOrNull() == null || f[1].length < 16) { gpStatus(T("gpad.off")); Thread.sleep(2500); continue }
            val sock = WebSocket.open(ip, f[0].toInt(), "/ws?k=${f[1]}", false)
            Gp.ws = sock; wait = 400
            val cid = "gyro-" + android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty().take(8)
            sock.send("hello $cid")
            sock.timeout(500)
            val tick = Thread {
                var last = 0L
                try {
                    while (Gp.alive && Gp.ws === sock) {
                        Thread.sleep(8)
                        var x = 0; var y = 0
                        synchronized(Gp) {
                            x = Gp.pending[0].toInt(); y = Gp.pending[1].toInt()
                            Gp.pending[0] -= x; Gp.pending[1] -= y
                        }
                        if (x != 0 || y != 0) sock.send("m ${x.coerceIn(-400, 400)} ${y.coerceIn(-400, 400)}")
                        val now = System.currentTimeMillis()
                        if (now - last > 2000) { last = now; sock.send("p $now") }
                    }
                } catch (e: Exception) { }
            }
            tick.isDaemon = true; tick.start()
            while (Gp.alive && Gp.ws === sock) {
                val m = try { sock.read() } catch (e: java.net.SocketTimeoutException) { continue } ?: break
                if (m.startsWith("p ")) continue
                if (m.startsWith("{")) {
                    val j = try { org.json.JSONObject(m) } catch (e: Exception) { continue }
                    if (j.has("slot")) gpStatus(T("gpad.joined", "n" to j.getInt("slot")))
                    else if (j.optBoolean("full")) { gpStatus(T("gpad.full")); break }
                    else if (j.optBoolean("replaced")) { gpStatus(T("gpad.replaced")); Gp.alive = false; break }
                    else if (j.optBoolean("rescan") || j.optBoolean("closed")) break
                    else if (j.has("vibrate")) buzz()
                }
            }
            try { sock.close() } catch (e: Exception) { }
            if (Gp.alive) gpStatus(T("gpad.joining"))
        } catch (e: InterruptedException) { return } catch (e: Exception) { if (Gp.alive) gpStatus(T("gpad.joining")) }
        if (Gp.alive) try { Thread.sleep(wait); wait = (wait * 2).coerceAtMost(5000) } catch (e: InterruptedException) { return }
    }
}

private fun MainActivity.gpListen(sm: SensorManager, sensor: Sensor) {
    var last = 0L
    val l = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            if (last == 0L) { last = e.timestamp; return }
            val dt = (e.timestamp - last) / 1e9f; last = e.timestamp
            if (dt <= 0f || dt > 0.2f) return
            // held like a pointer, flat: turning left (z+) moves it left, tipping the top up (x+) moves it up (the same as the Air mouse)
            val wz = -e.values[2]; val wx = -e.values[0]
            val w = kotlin.math.hypot(wz, wx)
            if (w < DEAD) return
            val k = GAIN * dt * (1f + BOOST * w)
            synchronized(Gp) { Gp.pending[0] += wz * k; Gp.pending[1] += wx * k }
        }
    }
    Gp.listener = l
    val th = android.os.HandlerThread("gpad-sensor").apply { start() }; Gp.sensorThread = th
    sm.registerListener(l, sensor, 5_000, android.os.Handler(th.looper))
}

@SuppressLint("ClickableViewAccessibility")
private fun MainActivity.gpView(hasGyro: Boolean): FrameLayout {
    val root = FrameLayout(this)
    root.setBackgroundColor(BG)
    root.isClickable = true
    val page = col()
    page.setPadding(dp(16), dp(20), dp(16), dp(20))
    val top = LinearLayout(this)
    top.gravity = Gravity.CENTER_VERTICAL
    val head = label(T("gpad.title"), 24, TEXT)
    head.typeface = android.graphics.Typeface.DEFAULT_BOLD
    top.addView(head, LinearLayout.LayoutParams(0, -2, 1f))
    val done = icon("@check", T("drawing.done"), ACCENT)
    done.setOnClickListener { gamesPointerClose() }
    top.addView(done, LinearLayout.LayoutParams(dp(52), dp(44)))
    page.addView(top)
    page.addView(note(T(if (hasGyro) "gpad.hint" else "gpad.nogyro")), gap(6))
    val st = label(T("gpad.joining"), 16, MUTED)
    Gp.status = st
    page.addView(st, gap(10))

    val fire = button(T("gpad.fire"), ACCENT)
    fire.textSize = 28f
    fire.setOnTouchListener { v, ev ->
        val ws = Gp.ws
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { v.isPressed = true; Gp.touching = true; buzz(); Thread { try { ws?.send("d A ${System.currentTimeMillis()}") } catch (e: Exception) { } }.start() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; Gp.touching = false; Thread { try { ws?.send("u A ${System.currentTimeMillis()}") } catch (e: Exception) { } }.start() }
        }
        true
    }
    page.addView(fire, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = dp(12) })

    val row = LinearLayout(this)
    val center = button(T("gpad.center"), MUTED)
    center.setOnClickListener { val ws = Gp.ws; Thread { try { ws?.send("pc") } catch (e: Exception) { } }.start() }
    val rdy = button(T("gpad.ready"), GREEN)
    rdy.setOnClickListener {
        Gp.ready = !Gp.ready; rdy.setOn(Gp.ready)
        val ws = Gp.ws; val v = if (Gp.ready) 1 else 0
        Thread { try { ws?.send("ready $v") } catch (e: Exception) { } }.start()
    }
    row.addView(center, LinearLayout.LayoutParams(0, dp(52), 1f))
    row.addView(rdy, cell(dp(52), true))
    page.addView(row, gap(12))
    root.addView(page, FrameLayout.LayoutParams(-1, -1))
    return root
}
