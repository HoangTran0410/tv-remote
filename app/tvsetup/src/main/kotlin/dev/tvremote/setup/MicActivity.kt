package dev.tvremote.setup

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tvremote.core.T
import dev.tvremote.core.Words
import dev.tvremote.core.scenes.Voice
import dev.tvremote.tvui.TvUi

/**
 * The voice screen the TV remote's mic button opens ([MicVoice]): plain Views over whatever is on, the recognizer started in onCreate before the
 * screen is even drawn. The live text and what it will do show as it comes; a sentence that [Voice.parse] knows is carried out at once, a key or a
 * scene also after a short silence in the partial text. Back cancels, OK ends the listening.
 */
class MicActivity : Activity(), RecognitionListener {
    private val ui = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private lateinit var dot: View
    private lateinit var heard: TextView
    private lateinit var act: TextView
    private var done = false
    private var partial = ""
    private val earlyRun = Runnable { finish(partial, cut = true) }
    private var asking = false
    private val giveUp = Runnable { sr?.cancel(); say(T("mic.nothing"), T("mic.try"), 1800) }
    private val close = Runnable { finish() }

    companion object {
        private const val SHADE = 0x66000000                              // the screen under the card, a little dimmed (TvUi.SCRIM hides the video the person is speaking about)
        @Volatile var live: MicActivity? = null
        /** The key came again, or was let go after a hold: stop listening and work with what was heard. */
        fun enough() { live?.let { a -> a.ui.post { a.sr?.stopListening() } } }

        fun open(c: android.content.Context) {
            c.startActivity(Intent(c, MicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        live = this
        dev.tvremote.core.scenes.VoicePhrases.start({ assets.open(it) }, filesDir)
        val fake = intent.getStringExtra("say")                        // `am start -n .../.MicActivity --es say "mở youtube"` (the shell only): a test with no microphone
        if (fake != null) { build(); ui.postDelayed({ onPartialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(fake)) }) }, 500)
            ui.postDelayed({ finish(fake, cut = false) }, 1300); return }
        if (!MicVoice.granted(this)) {                                // allowed through ADB when the switch went on; else the TV asks
            ask()
        } else listen()
        build()
        if (!MicVoice.granted(this)) say(T("mic.allow"), "", 0)
        ui.postDelayed(giveUp, 12000)
        Thread({ MicVoice.apps(this) }, "mic-apps").apply { isDaemon = true }.start()   // the names are ready before the sentence ends
    }

    private fun listen() {
        val r = try { SpeechRecognizer.createSpeechRecognizer(this) } catch (e: Exception) { null }
        if (r == null) { fail(); return }
        sr = r
        r.setRecognitionListener(this)
        val lang = if (Words.lang == "vi") "vi-VN" else "en-US"
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1).putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200))   // an Int: Google TV's recognizer reads it so and dropped the Long the docs name (its own longer wait, FPT 2026-11-01)
    }

    private fun build() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(TvUi.CARD); cornerRadius = TvUi.dp(this@MicActivity, TvUi.R_SHEET); setStroke(TvUi.dp(this@MicActivity, 1).toInt(), TvUi.SHEET_EDGE) }
            setPadding(dp(28), dp(22), dp(32), dp(22))
        }
        dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TvUi.ACCENT) } }
        card.addView(dot, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(22); marginStart = dp(6) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heard = TvUi.text(this, T("mic.listening"), 26f, TvUi.TEXT, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        act = TvUi.text(this, T("mic.speak"), TvUi.SP_STATE, TvUi.DIM).apply { maxLines = 2 }
        col.addView(heard); col.addView(act, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        card.addView(col, LinearLayout.LayoutParams(-2, -2))
        val root = FrameLayout(this).apply { setBackgroundColor(SHADE) }
        root.addView(card, FrameLayout.LayoutParams(dp(620), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(48) })
        setContentView(root)
    }

    private fun ask() { if (Build.VERSION.SDK_INT >= 23) { asking = true; requestPermissions(arrayOf(MicVoice.PERM), 1) } }   // below 23 permissions are granted at install

    private fun dp(n: Int) = TvUi.dp(this, n).toInt()

    private fun say(top: String, below: String, closeIn: Long) {
        heard.text = top; act.text = below
        ui.removeCallbacks(giveUp)
        ui.removeCallbacks(close)
        if (closeIn > 0) ui.postDelayed(close, closeIn)
    }

    private fun fail() {
        MicVoice.broke()                                              // the next presses go to the system until this is put right
        say(T("mic.no_recognizer"), "", 3500)
    }

    // ---- RecognitionListener
    override fun onReadyForSpeech(p: Bundle?) { heard.text = T("mic.listening") }
    override fun onBeginningOfSpeech() { }
    override fun onRmsChanged(rms: Float) { val s = 1f + (rms.coerceIn(0f, 10f) / 10f); dot.scaleX = s; dot.scaleY = s }
    override fun onBufferReceived(b: ByteArray?) { }
    override fun onEndOfSpeech() { }
    override fun onEvent(t: Int, b: Bundle?) { }

    override fun onPartialResults(r: Bundle?) {
        val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (t.isEmpty() || done) return
        partial = t
        heard.text = t
        val cmd = parse(t)
        act.text = if (cmd != null) "→ " + MicVoice.describe(cmd, this) else T("mic.speak")
        ui.removeCallbacks(earlyRun)
        if (MicVoice.earlyOk(cmd, t)) ui.postDelayed(earlyRun, 700)        // a clear short command, then a pause: no need to wait for the recognizer's own end
    }

    override fun onResults(r: Bundle?) {
        val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        finish(t.ifEmpty { partial }, cut = false)
    }

    override fun onError(code: Int) {
        if (done) return
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                if (partial.isNotEmpty()) finish(partial, cut = false) else say(T("mic.nothing"), T("mic.try"), 2000)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> { MicVoice.broke(); say(T("mic.no_net"), T("mic.use_phone"), 3500) }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                if (!MicVoice.granted(this)) ask() else { MicVoice.broke(); say(T("mic.no_perm"), T("mic.use_phone"), 3500) }
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> say(T("mic.busy"), "", 1800)
            else -> fail()
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, g: IntArray) {
        asking = false
        if (MicVoice.granted(this)) { heard.text = T("mic.listening"); act.text = T("mic.speak"); listen() }
        else { MicVoice.broke(); say(T("mic.no_perm"), T("mic.use_phone"), 3500) }
    }

    private fun parse(t: String): Voice.Command? = Voice.parse(t, MicVoice.apps(this), MicVoice.scenes(this))

    /** The sentence is complete (or clear enough): carry it out, say so, close. */
    private fun finish(text: String, cut: Boolean) {
        if (done) return
        done = true
        ui.removeCallbacks(earlyRun); ui.removeCallbacks(giveUp)
        if (cut) sr?.cancel()
        MicVoice.fixed()
        heard.text = text
        val cmd = if (text.isEmpty()) null else parse(text)
        if (cmd == null && text.isNotEmpty()) MicVoice.search(this, text)?.let { act.text = it; ui.postDelayed(close, 600); return }   // not a command: looked up, not a dead end
        if (cmd == null) { say(if (text.isEmpty()) T("mic.nothing") else T("mic.unknown", "text" to text), T("mic.try"), 2500); return }
        act.text = MicVoice.describe(cmd, this)
        val app = applicationContext
        Thread({
            val err = MicVoice.run(app, cmd)
            ui.post { if (err != null) { act.setTextColor(TvUi.BAD); act.text = err; ui.postDelayed(close, 2500) } else ui.postDelayed(close, 900) }
        }, "mic-run").apply { isDaemon = true }.start()
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        when (k) {
            KeyEvent.KEYCODE_BACK -> { sr?.cancel(); finish(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { sr?.stopListening(); return true }
        }
        return super.onKeyDown(k, e)
    }

    override fun onStop() { super.onStop(); if (!isFinishing && !asking) finish() }   // another app came up (Home key): nothing to keep listening for

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { sr?.destroy() } catch (e: Exception) { }
        sr = null
        if (live === this) live = null
        super.onDestroy()
    }
}
