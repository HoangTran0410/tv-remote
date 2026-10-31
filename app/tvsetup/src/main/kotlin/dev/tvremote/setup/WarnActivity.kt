package dev.tvremote.setup

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.tvremote.core.T
import dev.tvremote.setup.health.Stats
import dev.tvremote.setup.rules.Guard
import dev.tvremote.setup.rules.Kids
import dev.tvremote.setup.rules.Rules
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * Shown over whatever is on the TV by [Guard]: the sleep timer is about to end (with +15 minutes, now, or cancel), bedtime
 * has come (the TV goes to standby in a minute), or an app's minutes for today are used up, or an app was opened outside the days and hours set for it ("sched"). Bedtime and limits offer no way
 * out here, on purpose: more time is granted from a paired phone or computer. Or the memory has stayed nearly full: free it
 * (the apps waiting in the background closed), see what holds it (the Health tab, on the TV), or skip. Or ("total") the day's minutes on the
 * TV are used up (kids mode): the TV goes to standby in a minute unless a code is given here ([PinActivity]) or a parent answers over Telegram.
 */
class WarnActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var count: TextView
    private var kind = ""
    private var until = 0L
    private val shownAt = android.os.SystemClock.uptimeMillis()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        kind = intent.getStringExtra("kind") ?: "sleep"
        until = intent.getLongExtra("until", 0L)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val name = if (pkg.isEmpty()) "" else try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }

        val page = LinearLayout(this)
        page.gravity = Gravity.CENTER
        page.setBackgroundColor(Color.argb(200, 0, 0, 0))
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.background = GradientDrawable().apply { setColor(Color.rgb(25, 29, 35)); cornerRadius = dp(20).toFloat() }
        card.setPadding(dp(40), dp(32), dp(40), dp(28))
        val icon = when (kind) { "sleep" -> "😴"; "bed" -> "🌙"; "ram" -> "🧠"; "sched" -> "📅"; "total" -> "⏰"; else -> "⏳" }
        val (total, avail) = Guard.memory()
        card.addView(text(icon, 48, false))
        card.addView(text(when (kind) {
            "sleep" -> T("warn.the_tv_is_turning")
            "bed" -> T("warn.it_is_bedtime")
            "ram" -> T("warn.the_tv_s_memory")
            "sched" -> T("warn.sched_title", "name" to name)
            "total" -> T("kids.total_title")
            else -> T("warn.time_is_up_for", "name" to name)
        }, 28, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val sched = Rules.load(this).optJSONObject("sched")?.optJSONObject(pkg)
        val bed = Rules.bedNow(Rules.load(this), System.currentTimeMillis())
        card.addView(text(when (kind) {
            "sleep" -> T("warn.the_sleep_timer_is")
            "ram" -> T("warn.in_use", "pct" to (total - avail) * 100 / maxOf(1L, total), "used" to total - avail, "total" to total)
            "bed" -> T("warn.bed_range", "from" to (bed?.optString("from") ?: ""), "to" to (bed?.optString("to") ?: ""))
            "sched" -> if (sched == null || sched.optBoolean("block")) T("warn.sched_never")
                else T("warn.sched_body", "when" to Rules.windowsText(sched))
            "total" -> T("kids.total_body")
            else -> T("warn.the_app_was_closed")
        }, 16, false).apply { setTextColor(Color.rgb(170, 178, 189)); maxWidth = dp(560) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        count = text("", 22, true)
        if (kind != "limit" && kind != "sched") card.addView(count, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })

        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER
        val buttons = ArrayList<Button>()
        if (kind == "sleep") {
            buttons.add(button(T("auto.plus_15")) { Rules.change(this, JSONObject().put("do", "sleep_add").put("minutes", 15)); finish() })
            buttons.add(button(T("warn.turn_off_now")) { Rules.change(this, JSONObject().put("do", "sleep").put("minutes", 0)); standby(); finish() })
            buttons.add(button(T("warn.cancel_the_timer")) { Rules.change(this, JSONObject().put("do", "sleep").put("minutes", 0)); finish() })
        } else if (kind == "ram") {
            buttons.add(button(T("warn.free_ram")) { freeRam(buttons) })
            buttons.add(button(T("warn.see_the_apps")) {
                startActivity(android.content.Intent(this, ManageActivity::class.java).putExtra(ManageActivity.PASS, RemoteService.selfPass).putExtra(ManageActivity.TAB, "health"))
                finish()
            })
            buttons.add(button(T("warn.skip")) { finish() })
        } else if (kind == "total") {
            buttons.add(button(T("kids.enter_code")) {
                startActivity(android.content.Intent(this, PinActivity::class.java).putExtra("total", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            })
            if (Rules.methods(Rules.load(this)).optBoolean("ask") && RemoteService.canAsk()) buttons.add(button(T("pin.ask")) { asked(buttons.last()) })
        } else {
            buttons.add(button(T("common.ok")) { finish() })
            if (kind == "bed" || kind == "limit" || kind == "sched") buttons.add(button(T("warn.unlock")) { unlock() })
        }
        buttons.forEachIndexed { i, b -> row.addView(b, LinearLayout.LayoutParams(-2, dp(52)).apply { if (i > 0) leftMargin = dp(12) }) }
        card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
        page.addView(card, LinearLayout.LayoutParams(-2, -2))
        setContentView(page)
        buttons[0].isFocusableInTouchMode = true                    // opened by ADB, the TV is still in touch mode: OK must land somewhere
        buttons[0].requestFocus()
        tick.run()
    }

    /** The countdown; the screen goes away by itself when the time is up, or the timer or bedtime was changed from a remote. */
    private val tick = object : Runnable {
        override fun run() {
            if (kind == "ram") return
            if (kind != "sleep" && kind != "total" && Rules.unlockUntil(Rules.load(this@WarnActivity), System.currentTimeMillis()) > 0) { finish(); return }   // a code was given
            if (kind == "limit" || kind == "sched") return
            val left = until - System.currentTimeMillis()
            val o = Rules.load(this@WarnActivity)
            if (kind == "total" && (Rules.unlockUntil(o, System.currentTimeMillis()) > 0 || totalLeft(o) > 0)) { finish(); return }   // a code, a parent's "open", or more minutes granted
            val off = kind == "sleep" && o.optLong("sleep_at") != until
                || kind == "bed" && !(Rules.inBedtime(o, System.currentTimeMillis()) && System.currentTimeMillis() >= o.optLong("snooze_until"))   // more time granted, bedtime off or moved: the Guard's own test
            if (left <= 0 || off) { finish(); return }
            val s = ((left + 999) / 1000).toInt()
            count.text = T("warn.off_in", "time" to "%d:%02d".format(s / 60, s % 60))
            ui.postDelayed(this, 500)
        }
    }

    /** "Unlock": the same code screen as an app's ([PinActivity] with the grant), or at once for 30 minutes when nothing is locked (no PIN, no Authenticator: nothing to prove). No other way past: bedtime still ends the TV a minute after. */
    private fun unlock() {
        val o = Rules.load(this)
        val now = System.currentTimeMillis()
        val minutes = if (!Rules.hasLock(o)) NO_LOCK_MIN else if (kind == "bed") Rules.bedLeftMin(o, now) else APP_MIN
        if (!Rules.hasLock(o)) { Rules.change(this, JSONObject().put("do", "unlock").put("minutes", minutes)); finish(); return }
        startActivity(android.content.Intent(this, PinActivity::class.java).putExtra("grant", minutes).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private val stats by lazy { Stats(this) }

    /** Seconds left of today's total (more than 0 once a parent granted minutes); read at most every 3 s, the usage events cost. */
    private var leftAt = 0L
    private var left = 0L
    private fun totalLeft(o: JSONObject): Long {
        val now = System.currentTimeMillis()
        if (now - leftAt > 3000) { leftAt = now; left = try { Rules.totalLeftSec(o, stats.today(now, true).first, Kids.free(this, now), now) ?: 1L } catch (e: Exception) { 0L } }
        return left
    }

    /** "Ask a parent" from here: the same question as the code screen's, sent off the main thread. */
    private fun asked(b: Button) {
        b.isEnabled = false
        Thread {
            val r = try { RemoteService.askParent("", "") } catch (e: Exception) { "failed" }
            ui.post { b.isEnabled = true; b.text = T(when (r) { "sent" -> "pin.ask_sent_short"; "wait" -> "pin.ask_wait_short"; "nochat" -> "pin.ask_nochat_short"; else -> "pin.ask_failed_short" }) }
        }.start()
    }

    /** Free RAM: the Guard closes what waits in the background, then the screen says how much came back and goes. */
    private fun freeRam(buttons: List<Button>) {
        buttons.forEach { it.isEnabled = false }
        count.text = T("warn.freeing")
        Thread {
            val got = try { Guard.freeNow() } catch (e: Exception) { null }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                count.text = if (got == null) T("warn.could_not_tv_setup")
                    else T("warn.got_back_got_mb", "got" to got)
                ui.postDelayed({ finish() }, if (got == null) 4000 else 2500)
            }
        }.start()
    }

    override fun onDestroy() { ui.removeCallbacks(tick); super.onDestroy() }

    private companion object {
        const val NO_LOCK_MIN = 30                                  // nothing protects the rules: a snooze, like the notice's own "+15"
        const val DEAF_MS = 1500L                                   // presses meant for the app underneath
        const val APP_MIN = 60                                      // an app's limit or window: an hour to go on
    }

    /** "Turn off now" from this screen: the Guard would do it within a minute, this is sooner. */
    private fun standby() { Thread { try { Guard.standbyNow() } catch (ignored: Exception) { } }.start() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun text(s: String, sp: Int, bold: Boolean): TextView = TextView(this).apply {
        text = s; textSize = sp.toFloat(); setTextColor(Color.rgb(233, 236, 241)); gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, run: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        setPadding(dp(22), 0, dp(22), 0)
        stateListAnimator = null
        val idle = GradientDrawable().apply { setColor(Color.rgb(34, 39, 47)); cornerRadius = dp(14).toFloat() }
        val focus = GradientDrawable().apply { setColor(Color.rgb(59, 115, 246)); cornerRadius = dp(14).toFloat() }
        background = idle
        setOnFocusChangeListener { v, f -> v.background = if (f) focus else idle }
        setOnClickListener { if (android.os.SystemClock.uptimeMillis() - shownAt >= DEAF_MS) run() }   // opened over a game: an OK still held or pressed there took the first button, the screen gone in half a second (FPT, 2026-10-27)
        visibility = View.VISIBLE
    }
}
