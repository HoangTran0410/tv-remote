package dev.tvremote.setup

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.tvremote.core.T
import dev.tvremote.home.HomeConfig
import dev.tvremote.tvui.TvUi

/** The tester is open: the key filter ([KeysBridge]) reports every key it is asked about here, and eats it (Home must not leave). */
internal object KeyTest {
    @Volatile var sink: ((code: Int, action: Int, repeat: Int, downTime: Long, eventTime: Long) -> Unit)? = null
    @Volatile var beat = 0L                                           // a tester that died without letting go stops eating keys after a few seconds

    fun live(): Boolean = sink != null && SystemClock.elapsedRealtime() - beat < 6000
}

/**
 * "Test remote keys": shows live every key the TV gets from its remote: its name, code, scan code, device, down / up / long / repeat,
 * a history of the last 12, and whether TV Setup changes it now ([KeyFix], [HoldKeys], [MicVoice]). Keys that reach the screen are
 * read here; the ones the system takes before any app (Home, an app button) come from TV Setup's key filter ([KeysBridge] through
 * the add-on), which this screen switches on while it is open ([KeyFix.sync] counts [KeyTest.live]). Everything is eaten, so there is
 * no focus to move: Back leaves when held 2 s or pressed twice ([BackExit]); holding OK 2 s opens TV Home's Quick keys for the key
 * pressed before it; taps do the same on a phone. Started by this app, TV Home (a PendingIntent: [HomeControl]), or the shell.
 */
class KeyTestActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val log = KeyLog()
    private val back = BackExit()
    private lateinit var big: TextView
    private lateinit var phase: TextView
    private lateinit var codeLine: TextView
    private lateinit var fromLine: TextView
    private lateinit var remapLine: TextView
    private lateinit var assignHint: TextView
    private lateinit var filterLine: TextView
    private lateinit var exitLine: TextView
    private lateinit var assign: TextView
    private val rows = ArrayList<TextView>()
    private var shown: KeyPress? = null                               // the press on the big card
    private var before = -1                                           // the key shown before the one now: what a hold of OK assigns
    private var plan: Map<Int, KeyFix.Rule> = emptyMap()              // the rules in force, read off the main thread
    private var assignable: Set<Int> = emptySet()
    private var wanted = true
    @Volatile private var gone = false

    private val tick = object : Runnable {
        override fun run() {
            KeyTest.beat = SystemClock.elapsedRealtime()
            refresh()
            ui.postDelayed(this, 2500)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(build())
        paintFilter(); paintIdle()
    }

    private fun dp(v: Int) = TvUi.dp(this, v).toInt()

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(TvUi.BG); setPadding(dp(48), dp(27), dp(48), dp(20))
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TvUi.text(this, T("keytest.tile"), 24f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val quit = TvUi.button(this, T("keytest.exit_btn")) { finish() }.apply { isFocusable = false }
        head.addView(quit)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })

        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = TvUi.round(TvUi.CARD, TvUi.dp(context, 16)); setPadding(dp(28), dp(22), dp(28), dp(20)) }
        big = TvUi.text(this, "", 52f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        phase = TvUi.text(this, "", 20f, TvUi.ACCENT, bold = true)
        codeLine = TvUi.text(this, "", 16f)
        fromLine = TvUi.text(this, "", 16f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        remapLine = TvUi.text(this, "", 16f, TvUi.DIM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        card.addView(big, LinearLayout.LayoutParams(-1, -2))
        card.addView(phase, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        for (v in listOf(codeLine, fromLine, remapLine)) card.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        assign = TvUi.button(this, T("keytest.assign")) { assignShown() }.apply { isFocusable = false }
        card.addView(assign, LinearLayout.LayoutParams(-2, -2))
        assignHint = TvUi.text(this, T("keytest.assign_hint"), 13f, TvUi.DIM)
        card.addView(assignHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        body.addView(card, LinearLayout.LayoutParams(0, -1, 3f))

        val hist = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = TvUi.round(TvUi.CARD, TvUi.dp(context, 16)); setPadding(dp(20), dp(16), dp(20), dp(12)) }
        repeat(12) {
            val r = TvUi.text(this, "", 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            rows += r; hist.addView(r, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        body.addView(hist, LinearLayout.LayoutParams(0, -1, 2f).apply { leftMargin = dp(16) })

        filterLine = TvUi.text(this, "", 14f, TvUi.DIM)
        exitLine = TvUi.text(this, T("keytest.exit"), 14f, TvUi.DIM)
        root.addView(filterLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(exitLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return root
    }

    override fun onResume() {
        super.onResume()
        gone = false
        KeyTest.beat = SystemClock.elapsedRealtime()
        KeyTest.sink = { code, action, repeat, downTime, eventTime ->
            ui.post { feed(code, 0, T("keytest.from_filter"), action, repeat, downTime, eventTime, false) }
        }
        ui.removeCallbacks(tick); tick.run()
    }

    override fun onPause() {
        KeyTest.sink = null
        ui.removeCallbacks(tick)
        gone = true
        Thread({ KeyFix.sync(applicationContext) }, "keytest-sync").apply { isDaemon = true }.start()   // the filter goes back to what the rules need
        super.onPause()
    }

    /** The rules in force, and the key filter turned on for as long as this is open; then the lines again. */
    private fun refresh() {
        Thread({
            val c = applicationContext
            try {
                KeyFix.sync(c)
                val map = KeyFix.keyMap(c)
                val p = HashMap(KeyFix.current(c))
                for ((k, v) in KeyFix.digits(c)) p.getOrPut(k) { KeyFix.userRule(v) }
                val a = KeyFix.allowed(map) { KeyFix.alive(c, it) } + HomeKey.DIGITS
                val w = KeyFix.wanted(c)
                ui.post { if (!gone) { plan = p; assignable = a; wanted = w; paintFilter(); shown?.let { paintRemap(it.code) } } }
            } catch (e: Exception) { }
        }, "keytest-refresh").apply { isDaemon = true }.start()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val d = e.device
        val from = if (d == null) T("keytest.from_system") else d.name + (if (d.isVirtual) " · " + T("keytest.virtual") else "")
        feed(e.keyCode, e.scanCode, from, e.action, e.repeatCount, e.downTime, e.eventTime, e.flags and KeyEvent.FLAG_LONG_PRESS != 0)
        return true                                                   // everything is a test, nothing moves a focus or leaves
    }

    private fun feed(code: Int, scan: Int, from: String, action: Int, repeat: Int, downTime: Long, eventTime: Long, longFlag: Boolean) {
        val p = log.feed(code, scan, from, action, repeat, downTime, eventTime, longFlag)
        if (action != 1 && repeat == 0 && shown?.code != code) before = shown?.code ?: -1
        if (code == KeyEvent.KEYCODE_BACK && back.onKey(action, repeat, downTime, eventTime)) { finish(); return }
        shown = p
        paint(p)
        exitLine.text = T(if (back.armed(SystemClock.uptimeMillis())) "keytest.back_again" else "keytest.exit")
        if (code in OK && action == 0 && repeat > 0 && eventTime - downTime >= 2000 && !assigned) {
            assigned = true
            assignFor(if (before >= 0 && before !in OK) before else code)
        }
        if (action == 1 && code in OK) assigned = false
    }
    private var assigned = false

    private fun name(code: Int): String = KeyNames.label(code, KeyEvent.keyCodeToString(code)) { id -> T(id) }

    private fun paintIdle() {
        big.text = T("keytest.press_any"); big.textSize = 30f; phase.text = ""; codeLine.text = ""; fromLine.text = ""; remapLine.text = ""
        assign.visibility = View.GONE
    }

    private fun paint(p: KeyPress) {
        big.textSize = 52f; big.text = name(p.code)
        phase.text = phaseText(p)
        codeLine.text = T("keytest.code", "code" to p.code, "raw" to KeyEvent.keyCodeToString(p.code), "scan" to p.scan)
        fromLine.text = T("keytest.from", "name" to p.source)
        paintRemap(p.code)
        assign.visibility = View.VISIBLE
        for ((i, r) in rows.withIndex()) {
            val h = log.items.getOrNull(i)
            r.text = if (h == null) "" else "${name(h.code)}  ·  ${h.code}  ·  ${phaseText(h)}"
            r.setTextColor(if (i == 0) TvUi.TEXT else TvUi.DIM)
        }
    }

    private fun phaseText(p: KeyPress): String = when (p.phase) {
        KeyPress.Phase.DOWN -> T("keytest.ph_down")
        KeyPress.Phase.HELD -> T("keytest.ph_held", "n" to p.repeats, "ms" to p.heldMs)
        KeyPress.Phase.LONG -> T("keytest.ph_long", "n" to p.repeats, "ms" to p.heldMs)
        KeyPress.Phase.UP -> T(if (p.long) "keytest.ph_up_long" else "keytest.ph_up", "ms" to p.heldMs)
    }

    /** Whether TV Setup changes the key now, in words. */
    private fun remapText(code: Int): String {
        val r = if (wanted) plan[code] else null
        if (r != null) return when (r.act) {
            KeyFix.INPUT -> T("keytest.remap_input")
            KeyFix.APP -> T(if (r.mine) "keytest.remap_mine" else "keytest.remap_app", "app" to appName(r.pkgs[0]))
            KeyFix.HOME -> T("keytest.remap_home")
            KeyFix.SETTINGS -> T("keytest.remap_settings")
            KeyFix.CONTROL -> T("keytest.remap_cc")
            else -> T("keytest.remap_launcher")
        }
        if (code in HoldKeys.KEYS && HoldKeys.on(this)) return T("keytest.remap_hold")
        if (code in MicVoice.KEYS && MicVoice.on(this)) return T("keytest.remap_mic")
        return T("keytest.remap_none")
    }

    private fun paintRemap(code: Int) { remapLine.text = remapText(code) }

    private fun appName(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }

    /** One line on whether the filter that sees Home and the app buttons is running. */
    private fun paintFilter() {
        val live = KeysBridge.bound()
        filterLine.setTextColor(if (live) TvUi.ACCENT else WARN)
        filterLine.text = T(if (live) "keytest.filter_on" else if (!KeyFix.can(this)) "keytest.filter_need_adb" else "keytest.filter_wait")
    }

    private fun assignShown() { shown?.let { assignFor(it.code) } }

    /** Opens TV Home's Quick keys, where the key gets its app; only the keys TV Setup lets a person give an app. */
    private fun assignFor(code: Int) {
        if (code !in assignable && code !in KeyFix.COLOUR) { toast(T("keytest.assign_na", "key" to name(code))); return }
        val home = HomeKey.TV_HOME
        val has = try { packageManager.getPackageInfo(home, 0); true } catch (e: Exception) { false }
        if (!has) { toast(T("keytest.assign_nohome")); return }
        try {
            startActivity(Intent(HomeConfig.QUICK_KEYS).setClassName(home, "$home.HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("code", code))
            finish()
        } catch (e: Exception) { toast(T("keytest.assign_nohome")) }
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private companion object {
        val OK = setOf(23, 66, 160)
        const val WARN = 0xFFF0B34A.toInt()                           // = MainActivity's WARN
    }
}
