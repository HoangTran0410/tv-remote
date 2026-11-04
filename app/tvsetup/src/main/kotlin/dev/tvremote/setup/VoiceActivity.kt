package dev.tvremote.setup

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.tvremote.core.T
import dev.tvremote.setup.rules.Rules
import dev.tvremote.tvui.TvUi
import dev.tvremote.tvui.panel
import org.json.JSONObject

/**
 * TV Setup Voice offered on the TV itself: what it does better, and one press to put it on the TV and make it the mic button's
 * listener ([MicVoice.useTv]); once there, the switch back to Google and Remove. Opened by TV Home's Control Center mic tile
 * ([dev.tvremote.setup.home.HomeControl] "mic:tv" / "voice") and TV Setup's More TV apps row (the user, 2026-11-03). Cases:
 * dev/flows/voice.md. Closing it while it installs leaves the install running ([MicVoice.syncOffline] is its own thread).
 */
class VoiceActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var wheel: ProgressBar
    private lateinit var bar: ProgressBar                             // the download's percent ([MicVoice.offlinePct])
    private lateinit var buttons: LinearLayout
    private lateinit var langs: LinearLayout                          // one button per language: on the TV, in use, or to add
    private var waiting = false                                       // Install pressed: the state polled until ready or failed

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val page = FrameLayout(this).apply { setBackgroundColor(TvUi.BG) }
        val card = panel().apply { gravity = Gravity.START; minimumWidth = dp(640) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mic); setColorFilter(Color.WHITE); setPadding(dp(14), dp(14), dp(14), dp(14))
            background = TvUi.round(ACCENT, dp(28).toFloat())
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        names.addView(TvUi.text(this, T("tvvoice.title"), TvUi.SP_PANEL_HEAD, bold = true))
        names.addView(TvUi.text(this, T("tvvoice.sub"), TvUi.SP_ROW, TvUi.DIM))
        head.addView(names)
        card.addView(head)
        for ((icon, title, sub) in listOf(
            Triple(R.drawable.ic_zap, "tvvoice.fast", "tvvoice.fast_sub"),
            Triple(R.drawable.ic_wifi, "tvvoice.offline", "tvvoice.offline_sub"),
            Triple(R.drawable.ic_shield, "tvvoice.private", "tvvoice.private_sub"),
            Triple(R.drawable.ic_apps, "tvvoice.knows", "tvvoice.knows_sub"))) card.addView(point(icon, T(title), T(sub)), lp(top = 18))
        card.addView(TvUi.text(this, T("tvvoice.size"), TvUi.SP_COUNT, TvUi.DIM), lp(top = 20))
        langs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        card.addView(langs, lp(top = 12))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        wheel = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(ACCENT); visibility = View.GONE }
        row.addView(wheel, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
        state = TvUi.text(this, "", TvUi.SP_ROW, TvUi.TEXT)
        row.addView(state)
        card.addView(row, lp(top = 14))
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(TvUi.FOCUS_FILL)
        }
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) })
        buttons = LinearLayout(this)
        card.addView(buttons, lp(top = 18))
        page.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(page)
        paint(true)
    }

    /** One of the reasons to want it: its icon in the accent, a bold line, a dim one under it. */
    private fun point(icon: Int, title: String, sub: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply { setImageResource(icon); setColorFilter(ACCENT) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        words.addView(TvUi.text(context, title, TvUi.SP_ROW + 2, bold = true))
        words.addView(TvUi.text(context, sub, TvUi.SP_COUNT + 1, TvUi.DIM).apply { maxWidth = dp(560) })
        addView(words)
    }

    /** The state line and the buttons for where it stands now; [focus] the first button. */
    private fun paint(focus: Boolean) {
        val voice = MicVoice.offlineState(this)
        val r = try { Rules.load(this) } catch (e: Exception) { JSONObject() }
        val used = voice == "ready" && r.optBoolean("mic") && r.optString("mic_engine") == "tv"
        wheel.visibility = if (voice == "busy") View.VISIBLE else View.GONE
        val pct = MicVoice.offlinePct
        bar.visibility = if (voice == "busy" && pct >= 0) View.VISIBLE else View.GONE
        if (pct >= 0) bar.progress = pct
        state.setTextColor(if (voice.startsWith("failed:") && r.optBoolean("mic_offline")) TvUi.BAD else TvUi.TEXT)
        state.text = when {
            voice == "busy" -> T("tvvoice.installing") + if (pct in 0..99) "  $pct%" else ""
            used -> T(if (waiting) "tvvoice.done" else "tvvoice.used")
            voice == "ready" -> T("tvvoice.ready_unused")
            voice.startsWith("failed:") && r.optBoolean("mic_offline") ->
                T("mic.offline_failed", "why" to MicVoice.why(this, voice))
            else -> ""
        }
        state.visibility = if (state.text.isEmpty()) View.GONE else View.VISIBLE
        paintLangs(voice)
        buttons.removeAllViews()
        when {
            voice == "busy" -> add(T("common.close")) { finish() }  // the install goes on: its own thread
            used -> { add(T("tvvoice.use_google")) { work { Rules.change(this, JSONObject().put("do", "mic_engine").put("engine", "auto")) } }; addFreeSpeech(r)
                add(T("tvvoice.remove_lang", "lang" to NAMES.getValue(MicVoice.active(this) ?: MicVoice.lang(this))), danger = true) { remove() }; add(T("common.close")) { finish() } }
            voice == "ready" -> { add(T("tvvoice.use")) { work { MicVoice.useTv(this) } }; addFreeSpeech(r)
                add(T("tvvoice.remove_lang", "lang" to NAMES.getValue(MicVoice.active(this) ?: MicVoice.lang(this))), danger = true) { remove() }; add(T("common.close")) { finish() } }
            else -> { add(T("tvvoice.install")) { waiting = true; work { MicVoice.useTv(this) } }; add(T("common.later")) { finish() } }
        }
        if (focus || buttons.findFocus() == null) buttons.getChildAt(0)?.let { it.isFocusableInTouchMode = true; it.requestFocus() }   // opened by TV Home: OK must land somewhere
        ui.removeCallbacks(poll)
        if (voice == "busy" || waiting && voice == "none") ui.postDelayed(poll, 1000)   // "none" for a moment after Install: the thread not started yet
    }

    /** The button for the other of the two ways to listen (Rules "mic_freespeech"): commands only, or commands + free speech. */
    private fun addFreeSpeech(r: JSONObject) {
        val on = r.optBoolean("mic_freespeech")
        add(T(if (on) "mic.free_cmd" else "mic.free_on")) { work { Rules.change(this, JSONObject().put("do", "mic_freespeech").put("on", !on)) } }
    }

    private val poll = Runnable { if (!isFinishing) paint(false) }

    /** The languages, each its own download (the user, 2026-11-03: pick it here, add one without losing the other): the one listening
     *  lit, one on the TV plain, one not there with its size. OK on one not there downloads it beside the others. */
    private fun paintLangs(voice: String) {
        val had = langs.findFocus() != null
        langs.removeAllViews()
        langs.addView(TvUi.text(this, T("tvvoice.lang"), TvUi.SP_ROW, TvUi.DIM), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        val has = MicVoice.installed(this)
        val now = MicVoice.active(this)                              // the one listening is lit (the one picked may still be downloading)
        for (l in dev.tvremote.core.ontv.VoiceAddon.LANGS) {
            val on = l in has
            val label = NAMES.getValue(l) + if (on) "" else "  ↓ " + SIZES.getValue(l)
            val b = TvUi.button(this, label) {
                if (voice == "busy") return@button
                waiting = !on
                work { Rules.change(this, JSONObject().put("do", "mic_lang").put("lang", l)); if (!on) MicVoice.useTv(this) }
            }
            if (l == now) b.setTextColor(ACCENT)
            b.alpha = if (voice == "busy") 0.5f else 1f
            langs.addView(b, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            if (had && l == now) b.post { b.requestFocus() }
        }
    }

    private fun add(label: String, danger: Boolean = false, go: () -> Unit) {
        val btn = TvUi.button(this, label, go).also { if (danger) it.setTextColor(TvUi.DANGER) }
        buttons.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { if (buttons.childCount > 0) leftMargin = dp(12) })
    }

    /** The rules changed off the main thread (they write prefs and may start the key filter), then painted again. */
    private fun work(run: () -> Unit) {
        Thread({ try { run() } catch (e: Exception) { }; ui.post { if (!isFinishing) paint(true) } }, "voice-offer").apply { isDaemon = true }.start()
    }

    private fun remove() {
        TvUi.confirm(this, R.style.TvSheet, T("tvvoice.remove_ask", "lang" to NAMES.getValue(MicVoice.active(this) ?: MicVoice.lang(this))), T("tvvoice.remove_note"), T("tvvoice.remove"), T("common.cancel"), danger = true, accent = ACCENT) {
            waiting = false
            work { Rules.change(this, JSONObject().put("do", "mic_lang_remove").put("lang", MicVoice.active(this) ?: MicVoice.lang(this))) }   // that language only; the last one = all off
        }
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun dp(v: Int) = TvUi.dp(this, v).toInt()
    private fun lp(top: Int) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(top) }

    private companion object {
        val ACCENT = Color.rgb(59, 115, 246)                          // = MainActivity's: TV Setup's blue
        val NAMES = dev.tvremote.core.ontv.VoiceAddon.LANGS.associateWith { dev.tvremote.i18n.Langs.NAMES[it] ?: it }   // each in its own language (langs.json)
        val SIZES = mapOf("vi" to "39 MB", "en" to "47 MB")            // the APKs' sizes (app/tvvoice/AGENTS.md)
    }
}
