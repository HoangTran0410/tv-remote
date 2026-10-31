package dev.tvremote.setup

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tvremote.core.T
import dev.tvremote.setup.health.Stats
import dev.tvremote.setup.rules.Clock
import dev.tvremote.setup.rules.Guard
import dev.tvremote.setup.rules.Kids
import dev.tvremote.setup.rules.Lockout
import dev.tvremote.setup.rules.Rules
import dev.tvremote.tvui.Marks
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Over a locked app ([Rules.needsPin]: a locked app, or any app in kids mode) the moment it comes to the front ([Guard]): its PIN on the
 * remote's number keys or the keypad here, checked as soon as it has its length. With Google Authenticator on, the same field also takes
 * the 6-digit code; "Use a backup code" switches it to one of the 8-digit recovery codes (spent once; a lost phone must not lock the parents out) (a PIN of 4 digits is tried at 4, the code at 6: only a wrong ending counts as a wrong try). Back, or ✕, goes to the
 * home screen. Opaque, so the app under it is stopped (a video pauses) and nothing of it shows. Five wrong tries in a row: a wait of a
 * minute, then 5, 15, 60 minutes ([Lockout], kept, so a restart gives no new tries). "Ask a parent" sends the question to Telegram
 * and the screen closes by itself once a parent has answered. Also the way in when the day's total is used up (extra `total`).
 */
class PinActivity : Activity() {
    companion object {
        /** The app this screen is over now, null when it is not shown: the Guard does not ask twice. */
        @Volatile var showing: String? = null
        /** A right code over the "time is up" screen opens the TV for this long (unlock_until). */
        const val TOTAL_UNLOCK_MIN = 30
        private val MUTED = Color.rgb(170, 178, 189)                         // the line under the dots
        private val BAD = Color.rgb(255, 120, 110)                           // wrong code, waiting
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var dots: TextView
    private lateinit var said: TextView
    private var pkg = ""
    private var typed = ""
    private var len = 4
    private var total = false                                       // opened over the "time is up" screen, not over an app
    private var kidsOff = false                                 // opened by TV Home's Control Center: a right code turns kids mode off
    private var grant = 0                                           // opened from a notice (bedtime, a limit): minutes the TV opens for on a right code
    private var ask: Button? = null
    private var useBackup: Button? = null
    /** Typing an 8-digit backup code (lost phone). Its own button, so a wrong 6-digit code is wrong at once; showing it gives a child nothing: 10^8 codes behind the wait after wrong tries. */
    private var backup = false
    private val stats by lazy { Stats(this) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        read(intent)
        val page = LinearLayout(this)
        page.gravity = Gravity.CENTER
        page.setBackgroundColor(Color.rgb(12, 14, 18))
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = GradientDrawable().apply { setColor(Color.rgb(25, 29, 35)); cornerRadius = dp(20).toFloat() }
        card.setPadding(dp(36), dp(20), dp(36), dp(20))
        card.addView(text("🔒", 36, false))
        card.addView(text(if (kidsOff) T("pin.kids_off_title") else if (total) T("kids.total_title") else if (grant > 0) T("pin.unlock_title") else T("pin.locked", "name" to name()), 24, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        said = text(enterText(), 16, false).apply { setTextColor(MUTED); maxWidth = dp(420) }
        card.addView(said, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        dots = text("", 30, true).apply { letterSpacing = 0.3f }
        card.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val pad = GridLayout(this).apply { columnCount = 3 }
        // Digits in a new order each time the screen opens: whoever watches which button is lit learns nothing of the code (the remote's number keys still work)
        val d = ('0'..'9').map { it.toString() }.shuffled(java.util.Random(java.security.SecureRandom().nextLong()))
        val keys = d.subList(0, 9) + "⌫" + d[9] + "✕"
        val buttons = keys.map { k -> button(k) { press(k) } }
        buttons.forEach { pad.addView(it, GridLayout.LayoutParams().apply { width = dp(72); height = dp(48); setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
        card.addView(pad, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        ask = button(T("pin.ask")) { askParent() }.apply { textSize = 16f; visibility = View.GONE }
        card.addView(ask, LinearLayout.LayoutParams(dp(224), dp(44)).apply { topMargin = dp(6) })
        useBackup = button(T("pin.use_backup")) { settle(); backup = !backup; typed = ""; useBackup?.text = T(if (backup) "pin.use_main" else "pin.use_backup"); said.text = enterText(); said.setTextColor(MUTED); draw() }
            .apply { textSize = 16f; visibility = View.GONE }
        card.addView(useBackup, LinearLayout.LayoutParams(dp(224), dp(44)).apply { topMargin = dp(6) })
        page.addView(card, LinearLayout.LayoutParams(-2, -2))
        setContentView(page)
        buttons[0].isFocusableInTouchMode = true                    // opened from the background, the TV may be in touch mode: OK must land somewhere
        buttons[0].requestFocus()
        draw()
        tick.run()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); settle(); read(i); typed = ""; backup = false; useBackup?.text = T("pin.use_backup"); draw() }

    private fun read(i: Intent) {
        pkg = i.getStringExtra("pkg") ?: ""
        total = i.getBooleanExtra("total", false)
        grant = i.getIntExtra("grant", 0)
        kidsOff = i.getBooleanExtra("kids_off", false)
        if (kidsOff) pkg = "kids_off"                               // not an app: only so the Guard does not ask over it too
        showing = pkg
        len = Rules.load(this).optJSONObject("lock")?.optInt("len", 4) ?: 4
    }

    override fun onDestroy() { settle(); if (showing == pkg) showing = null; ui.removeCallbacksAndMessages(null); super.onDestroy() }

    /** Once a second: closed by a grant from elsewhere (a parent over Telegram, the phone's "Open 30 min"), the wait counted down, "ask a parent" shown when it can be answered. */
    private val tick = object : Runnable {
        override fun run() {
            val o = Rules.load(this@PinActivity)
            val now = System.currentTimeMillis()
            val open = if (kidsOff) !Rules.kidsMode(o)
                else if (total) Rules.unlockUntil(o, now) > 0 || (Rules.totalLeftSec(o, try { stats.today(now, true).first } catch (e: Exception) { JSONObject() }, Kids.free(this@PinActivity, now), now) ?: 1L) > 0
                else if (grant > 0) Rules.unlockUntil(o, now) > 0
                else if (pkg == packageName) !Rules.hasCode(o)             // TV Setup's rule screens: free for kids mode, never without the code
                else !Rules.needsPin(o, pkg, now, Kids.free(this@PinActivity, now))
            if (open) { if (!total && !kidsOff) Guard.unlock(pkg); showing = null; finish(); return }
            val left = Lockout.until(this@PinActivity, now) - now
            if (left > 0) { said.text = T("pin.wait_long", "t" to clock(left)); said.setTextColor(BAD); waiting = true }
            else if (waiting) { waiting = false; said.text = enterText(); said.setTextColor(MUTED) }
            val codes = Rules.recoveryLeft(o) > 0
            if (!codes && backup) { backup = false; typed = ""; said.text = enterText(); draw() }
            useBackup?.visibility = if (codes) View.VISIBLE else View.GONE
            ask?.visibility = if (Rules.methods(o).optBoolean("ask") && RemoteService.canAsk()) View.VISIBLE else View.GONE
            ui.postDelayed(this, 1000)
        }
    }
    private var waiting = false

    private fun clock(ms: Long): String { val s = (ms + 999) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    /** The line over the dots: what may be typed. */
    private fun enterText(): String = Rules.methods(Rules.load(this)).let { m ->
        if (backup) T("pin.enter_backup") else if (total) T("pin.total_enter") else if (!m.optBoolean("pin") && m.optBoolean("totp")) T("pin.enter_totp") else if (m.optBoolean("totp")) T("pin.enter_any") else T("pin.enter")
    }

    private fun askParent() {
        val b = ask ?: return
        b.isEnabled = false
        said.setTextColor(MUTED)
        Thread {
            val r = try { RemoteService.askParent(pkg, name()) } catch (e: Exception) { "failed" }
            ui.post { b.isEnabled = true; said.text = T(when (r) { "sent" -> "pin.ask_sent"; "wait" -> "pin.ask_wait"; "nochat" -> "pin.ask_nochat"; else -> "pin.ask_failed" }) }
        }.start()
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { press((code - KeyEvent.KEYCODE_0).toString()); return true }
        if (code == KeyEvent.KEYCODE_DEL) { press("⌫"); return true }
        return super.onKeyDown(code, e)
    }

    @Suppress("OVERRIDE_DEPRECATION") override fun onBackPressed() { home() }

    private fun press(k: String) {
        when (k) {
            "✕" -> { settle(); home(); return }
            "⌫" -> { if (pending) { fail(); draw(); return }; typed = typed.dropLast(1) }       // a try already checked and given up on is a wrong one
            else -> {
                if (Lockout.until(this, System.currentTimeMillis()) > System.currentTimeMillis()) return   // the wait is on screen already (tick)
                if (typed.isEmpty() && !waiting) { said.text = enterText(); said.setTextColor(MUTED) }
                if (typed.length < maxLen()) typed += k
            }
        }
        draw()
        if (k == "⌫" || typed.length !in tryAt()) return
        val o = Rules.load(this)
        val how = if (Rules.pinOk(o, typed)) "pin" else if (Rules.totpOk(o, typed, System.currentTimeMillis(), Clock.driftMs(this))) "totp"
            else if (backup && typed.length == 8 && Rules.useRecovery(this, typed)) "recovery" else null
        if (how != null) {
            pending = false
            Lockout.right(this)
            RemoteService.unlocked(how, pkg)
            if (kidsOff) { Rules.change(this, JSONObject().put("do", "kids").put("mode", "list")); showing = null; finish(); return }
            if (total || grant > 0) Rules.change(this, JSONObject().put("do", "unlock").put("minutes", if (grant > 0) grant else TOTAL_UNLOCK_MIN)) else Guard.unlock(pkg)
            showing = null
            finish()
            return
        }
        if (typed.length < maxLen()) { pending = true; return }     // a PIN of 4 and a code of 6: not wrong yet, the code may be coming (but if it is given up on, it counts: see settle)
        fail()
    }

    /** The digits typed passed a check that failed and were then taken back, or the screen was left: a guess made, so counted like any wrong code (else typing 6 and backspacing is a free guess). */
    private var pending = false

    private fun settle() { if (pending) { pending = false; Lockout.wrong(this, System.currentTimeMillis()) } }

    private fun fail() {
        typed = ""; pending = false
        val wait = Lockout.wrong(this, System.currentTimeMillis())
        if (Lockout.streak(this) == 3) RemoteService.wrongCodes(name())                   // the parents hear it at the third, not after the lockout
        said.text = if (wait > 0) T("pin.wait_long", "t" to clock(wait)) else T(if (Rules.totpOn(Rules.load(this))) "pin.wrong_code" else "pin.wrong")   // "Wrong PIN" under an Authenticator code reads as the wrong field
        said.setTextColor(BAD)
        waiting = wait > 0
        ui.postDelayed({ draw() }, 150)
    }

    /** The lengths a try is made at: the PIN's own, and 6 for an Authenticator code. */
    private fun tryAt(): Set<Int> = Rules.load(this).let { o -> if (backup) setOf(8) else Rules.methods(o).let { m -> setOfNotNull(len.takeIf { m.optBoolean("pin") }, 6.takeIf { m.optBoolean("totp") }) } }

    /** The most digits it takes: past that a try is wrong. */
    private fun maxLen(): Int = tryAt().maxOrNull() ?: len

    private fun draw() { dots.text = "●".repeat(typed.length) + "○".repeat(maxOf(0, maxLen() - typed.length)) }

    private fun home() {
        showing = null
        try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        finish()
    }

    private fun name(): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun text(s: String, sp: Int, bold: Boolean): TextView = TextView(this).apply {
        text = s; textSize = sp.toFloat(); setTextColor(Color.rgb(233, 236, 241)); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, run: () -> Unit): Button = Button(this).apply {
        text = Marks.text(this@PinActivity, label)   // ✕ ⌫ as line icons
        if (label == "✕") contentDescription = T("common.close")
        isAllCaps = false
        textSize = 20f
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 0)
        stateListAnimator = null
        val idle = GradientDrawable().apply { setColor(Color.rgb(34, 39, 47)); cornerRadius = dp(14).toFloat() }
        val focus = GradientDrawable().apply { setColor(Color.rgb(59, 115, 246)); cornerRadius = dp(14).toFloat() }
        background = idle
        setOnFocusChangeListener { v, f -> v.background = if (f) focus else idle }
        setOnClickListener { run() }
    }
}
