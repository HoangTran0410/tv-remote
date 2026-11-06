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
import dev.tvremote.setup.voice.VoiceProtocol
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
 * screen is even drawn. One step at a time, each said on the card: listening (the sound graph moves, what is heard shows as it comes), heard
 * (the graph lies flat: "working on it"), doing (what it does), then it closes. The end of the sentence is ours, not the recognizer's: the
 * partial text unchanged for [SETTLE_MS] stops the listening (a TV's sound kept Google's endpointer waiting 10 s, FPT 2026-11-01), and the
 * final text is not waited for longer than [LATE_MS]. Playing media pauses while listening (audio focus, as Google's own voice does).
 * Back cancels, OK ends the listening.
 */
class MicActivity : Activity(), RecognitionListener {
    private val ui = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private lateinit var level: MicLevel
    private lateinit var heard: TextView
    private lateinit var act: TextView
    private var done = false
    private var partial = ""
    private val earlyRun = Runnable { finish(partial, cut = true) }
    private val settle = Runnable { if (!done) { log("settled"); sr?.stopListening(); think() } }   // the sentence stopped changing: that is its end
    private val late = Runnable { log("late"); finish(partial, cut = true) }                         // no final text in time: the partial one
    private var thinking = false
    private var ready = false                                         // the recognizer said it is ready (its service is connected)
    private var again: (() -> Unit)? = null                       // the start to repeat while the service is still binding
    private var binds = 0
    private var retried = false
    private var offline = false                                       // TV Setup Voice is listening, not the TV's own recognizer
    private var noModel = false                                       // TV Setup Voice could not load its model: the TV's own from now on (flow row 8)
    private val tvFirst by lazy { MicVoice.engineTv(this) }           // TV Setup Voice picked over Google's (rule "mic_engine")
    /** Speech began but no word came back (Google's server stalled: 7 s of nothing, FPT 2026-11-01): a fresh session once, said on the card, then give up. */
    private val stall = Runnable {
        if (done || partial.isNotEmpty()) return@Runnable
        if (retried) { giveUp.run(); return@Runnable }
        retried = true
        if (!offline && MicVoice.offline(this) != null) offline = true    // Google's server gave nothing: TV Setup Voice needs none
        log("stall: again")
        swap(act, T("mic.again"), TvUi.DIM)
        level.go()
        ui.removeCallbacks(giveUp); ui.postDelayed(giveUp, 7000)
        listen()
    }
    private var rmsMax = -99f; private var rmsMin = 99f; private var rmsN = 0
    private var rmsAt = 0L
    private val t0 = android.os.SystemClock.uptimeMillis()
    private var focus: Any? = null
    private var asking = false
    private val giveUp = Runnable { sr?.cancel(); say(T("mic.nothing"), T("mic.try"), 1800) }
    private val close = Runnable { finish() }

    companion object {
        private const val SHADE = 0x66000000                              // the screen under the card, a little dimmed (TvUi.SCRIM hides the video the person is speaking about)
        private const val SETTLE_MS = 900L
        private const val LATE_MS = 1200L
        private const val LATE_FREE_MS = 8000L
        private const val STALL_MS = 3500L                            // speech began, no word yet by then
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
        val assist = intent.component?.className.let { it?.endsWith(".MicAssist") == true || it?.endsWith(".MicSearch") == true }   // anyone may start the alias: it only listens while the rule is on, and never takes "say"
        if (assist && !MicVoice.on(this)) { finish(); return }
        if (assist) MicVoice.noteKey(this)                                                  // the mic key really got here (not the page's test button): MicVoice.block stays quiet
        Thread({ MicVoice.warm(this); if (MicVoice.offline(this) != null) MicVoice.words(this) }, "mic-warm").apply { isDaemon = true }.start()   // the sentences' list (~1.4 s cold on an FPT box) and the app names, while the recognizer starts
        val fake = if (assist) null else intent.getStringExtra("say")                      // `am start -n .../.MicActivity --es say "mở youtube"` (the shell only): a test with no microphone
        if (fake != null) { build(); for (i in 0 until 12) ui.postDelayed({ level.feed(if (i % 3 == 0) 2f else 7f + i % 4) }, i * 100L)
            ui.postDelayed({ onPartialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(fake)) }) }, 500)
            ui.postDelayed({ finish(fake, cut = false) }, 1300); return }
        build()                                                       // before listen(): a recognizer that fails at once says so on the card
        if (!MicVoice.granted(this)) {                                // allowed through ADB when the switch went on; else the TV asks
            ask()
        } else listen()
        if (!MicVoice.granted(this)) say(T("mic.allow"), "", 0)
        ui.postDelayed(giveUp, 8000)                                  // nothing understood by then: say so rather than wait
    }

    private fun log(s: String) = android.util.Log.i("MicVoice", "+${android.os.SystemClock.uptimeMillis() - t0} ms $s")   // adb logcat -s MicVoice: where the time goes

    /** Playing media pauses while we listen (the TV's sound is no longer heard as speech), and plays again once the sentence is in. */
    private fun quiet(on: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        try {
            if (on && focus == null) {
                focus = if (Build.VERSION.SDK_INT >= 26) android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build().also { am.requestAudioFocus(it) }
                    else android.media.AudioManager.OnAudioFocusChangeListener { }.also { @Suppress("DEPRECATION") am.requestAudioFocus(it, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) }
            } else if (!on) {
                val f = focus ?: return
                focus = null
                if (Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(f as android.media.AudioFocusRequest)
                else @Suppress("DEPRECATION") am.abandonAudioFocus(f as android.media.AudioManager.OnAudioFocusChangeListener)
            }
        } catch (e: Exception) { }
    }

    /** [v] shows [s], the old words fading out and the new in (the TV's animation scale applies: at once when off): one step of the card to the next. */
    private fun swap(v: TextView, s: String, color: Int = v.currentTextColor) {
        if (v.text == s && v.currentTextColor == color) return
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(90).withEndAction { v.text = s; v.setTextColor(color); v.animate().alpha(1f).setDuration(140).start() }.start()
    }

    /** Heard: the graph lies flat, the card says it is working on it, and the final text is not waited for long. */
    private fun think() {
        if (thinking || done) return
        thinking = true
        level.stop()
        ui.removeCallbacks(settle)
        swap(act, T("mic.thinking"), TvUi.DIM)
        ui.postDelayed(late, if (offline && MicVoice.freeSpeech(this)) LATE_FREE_MS else LATE_MS)   // TV Setup Voice may take a second look at the sound (free speech, up to 6 s on a weak box)
    }

    private fun listen() {
        quiet(true)
        sr?.let { try { it.destroy() } catch (e: Exception) { } }    // the stalled session, on a retry
        val off = if (MicVoice.useAddon(offline, tvFirst, MicVoice.own(this), noModel)) MicVoice.offline(this) else null   // the TV's own first, unless TV Setup Voice was picked; it where the TV has none: dev/flows/voice.md
        offline = off != null
        log(if (off != null) "engine offline" else "engine own")
        val r = try { if (off != null) SpeechRecognizer.createSpeechRecognizer(this, off) else SpeechRecognizer.createSpeechRecognizer(this) } catch (e: Exception) { null }
        if (r == null) { fail(); return }
        sr = r; ready = false; binds = 0
        r.setRecognitionListener(this)
        if (off == null) { start(r, null); return }
        Thread({                                                      // the list takes ~2 s on a TV box the first time: never on the main thread (the card froze, FPT)
            val words = MicVoice.words(this)
            ui.post { if (!done && sr === r) start(r, words) }
        }, "mic-words").apply { isDaemon = true }.start()
    }

    private fun start(r: SpeechRecognizer, words: ArrayList<String>?) {
        again = { if (!done && sr === r) start(r, words) }
        val lang = if (android.os.Build.VERSION.SDK_INT >= 24)            // the app's language with its region (vi-VN, fr-FR): recognizers want one
            android.icu.util.ULocale.addLikelySubtags(android.icu.util.ULocale(Words.lang)).let { it.language + "-" + it.country }
            else if (Words.lang == "en") "en-US" else Words.lang
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { if (words != null) putStringArrayListExtra(VoiceProtocol.WORDS, words); if (offline && MicVoice.freeSpeech(this@MicActivity)) putExtra(VoiceProtocol.FREE_SPEECH, true) }
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
        level = MicLevel(this, TvUi.ACCENT)
        card.addView(level, LinearLayout.LayoutParams(dp(96), dp(48)).apply { marginEnd = dp(24) })
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
        swap(heard, top); swap(act, below, TvUi.DIM)
        level.stop()
        ui.removeCallbacks(giveUp); ui.removeCallbacks(settle); ui.removeCallbacks(late); ui.removeCallbacks(stall)
        ui.removeCallbacks(close)
        if (closeIn > 0) { quiet(false); ui.postDelayed(close, closeIn) }   // an end: the TV's sound back
    }

    private fun fail() {
        MicVoice.broke()                                              // the next presses go to the system until this is put right
        say(T("mic.no_recognizer"), "", 3500)
    }

    // ---- RecognitionListener
    override fun onReadyForSpeech(p: Bundle?) { ready = true; log("ready"); heard.text = T("mic.listening") }
    override fun onBeginningOfSpeech() { log("speech"); level.speech(true); ui.removeCallbacks(stall); ui.postDelayed(stall, STALL_MS) }
    override fun onRmsChanged(rms: Float) {
        level.feed(rms)
        rmsMax = maxOf(rmsMax, rms); rmsMin = minOf(rmsMin, rms); rmsN++
        val now = android.os.SystemClock.uptimeMillis()
        if (now - rmsAt >= 500) { if (rmsAt > 0) log("level %.1f..%.1f x%d".format(rmsMin, rmsMax, rmsN)); rmsAt = now; rmsMax = -99f; rmsMin = 99f; rmsN = 0 }   // noise or silence, when a try stalls
    }
    override fun onBufferReceived(b: ByteArray?) { }
    override fun onEndOfSpeech() { log("end"); level.speech(false); if (partial.isNotEmpty()) think() }
    override fun onEvent(t: Int, b: Bundle?) { }

    override fun onPartialResults(r: Bundle?) {
        val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
        if (t.isEmpty() || done || t == partial) return
        log("partial \"$t\"")
        if (t.length > partial.length) level.word()
        partial = t
        heard.text = t
        ui.removeCallbacks(giveUp); ui.removeCallbacks(stall)
        val cmd = parse(t)
        if (!thinking) swap(act, if (cmd != null) "→ " + MicVoice.describe(cmd, this) else T("mic.speak"), if (cmd != null) TvUi.ACCENT else TvUi.DIM)
        ui.removeCallbacks(earlyRun); ui.removeCallbacks(settle)
        if (MicVoice.earlyOk(cmd, t)) ui.postDelayed(earlyRun, 700)        // a clear short command, then a pause: no need to wait for the recognizer's own end
        else if (!thinking) ui.postDelayed(settle, SETTLE_MS)
    }

    override fun onResults(r: Bundle?) {
        val all = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        // TV Setup Voice sends [commands' text, free text] when the commands were not heard clearly: a command is kept, else free speech (a search)
        val t = (if (r?.getBoolean(VoiceProtocol.FREE) == true) VoiceProtocol.pick(all.map { it.trim() }, { Voice.kind(it, parse(it, last = true)) }) { Voice.asSearch(it) } else all.firstOrNull()?.trim()).orEmpty()
        log("results \"$t\"" + if (all.size > 1) " of $all" else "")
        finish(t.ifEmpty { partial }, cut = false)
    }

    override fun onError(code: Int) {
        if (done) return
        log("error $code")
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                if (partial.isNotEmpty()) finish(partial, cut = false) else say(T("mic.nothing"), T("mic.try"), 2000)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                if (!offline && !noModel && MicVoice.offline(this) != null) {   // Google's needs the Internet, TV Setup Voice does not: dev/flows/voice.md row 3
                    offline = true; log("network: offline")
                    swap(act, T("mic.again"), TvUi.DIM)
                    ui.removeCallbacks(giveUp); ui.postDelayed(giveUp, 8000)
                    listen()
                } else { MicVoice.broke(); say(T("mic.no_net"), T("mic.use_phone"), 3500) }
            VoiceProtocol.ERROR_NO_MODEL ->                            // TV Setup Voice could not load its model: not the network (flow rows 8, 9)
                if (!noModel && MicVoice.own(this)) {                 // the TV's own recognizer works: ask it before giving up (flow row 8)
                    noModel = true; log("no model: own")
                    swap(act, T("mic.again"), TvUi.DIM)
                    ui.removeCallbacks(giveUp); ui.postDelayed(giveUp, 8000)
                    listen()
                } else { MicVoice.broke(); say(T("mic.no_model"), T("mic.use_phone"), 3500) }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                if (!MicVoice.granted(this)) ask() else { MicVoice.broke(); say(T("mic.no_perm"), T("mic.use_phone"), 3500) }
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> say(T("mic.busy"), "", 1800)
            SpeechRecognizer.ERROR_CLIENT ->                           // our own cancel (a stall, Back, the time up) echoed back: never "no recognizer", the timers end it
                if (!ready && ++binds <= 20) { log("service not bound yet: again"); ui.postDelayed({ again?.invoke() }, 150) }   // Android 9 (BRAVIA): the words were cached, so startListening came before the service was bound
            else -> fail()
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, g: IntArray) {
        asking = false
        if (MicVoice.granted(this)) { heard.text = T("mic.listening"); act.text = T("mic.speak"); level.go(); listen() }
        else { MicVoice.broke(); say(T("mic.no_perm"), T("mic.use_phone"), 3500) }
    }

    private fun parse(t: String, last: Boolean = false): Voice.Command? { MicVoice.warm(this); return Voice.parse(t, MicVoice.apps(this), MicVoice.scenes(this), MicVoice.pins(this, wait = last), MicVoice.sayings(this), MicVoice.prefs(this)) }   // waits for warm() only when a sentence comes before it is done

    /** The sentence is complete (or clear enough): carry it out, say so, close. */
    private fun finish(text: String, cut: Boolean) {
        if (done) return
        done = true
        ui.removeCallbacks(earlyRun); ui.removeCallbacks(giveUp); ui.removeCallbacks(settle); ui.removeCallbacks(late); ui.removeCallbacks(stall)
        if (cut) sr?.cancel()
        quiet(false)
        log("finish \"$text\"")
        MicVoice.fixed()
        level.stop()
        heard.text = text
        val cmd = if (text.isEmpty()) null else parse(text, last = true)   // the last words: the pins asked at the press (started seconds ago) are waited for
        if (cmd == null && text.isNotEmpty()) MicVoice.search(this, text)?.let { swap(act, it, TvUi.ACCENT); ui.postDelayed(close, 600); return }   // not a command: looked up, not a dead end
        if (cmd == null) { say(if (text.isEmpty()) T("mic.nothing") else T("mic.unknown", "text" to text), T("mic.try"), 2500); return }
        swap(act, "→ " + MicVoice.describe(cmd, this), TvUi.ACCENT)
        val app = applicationContext
        Thread({
            val err = MicVoice.run(app, cmd)
            ui.post { log("done ${err ?: ""}"); if (err != null) { swap(act, err, TvUi.BAD); ui.postDelayed(close, 2500) } else ui.postDelayed(close, 700) }
        }, "mic-run").apply { isDaemon = true }.start()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); sr?.stopListening() }   // the mic button again, through the assistant: that is enough

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
        quiet(false)
        try { sr?.destroy() } catch (e: Exception) { }
        sr = null
        if (live === this) live = null
        super.onDestroy()
    }
}
