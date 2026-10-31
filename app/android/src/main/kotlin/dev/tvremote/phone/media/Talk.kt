package dev.tvremote.phone.media

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.R
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.scenes.SceneStore
import dev.tvremote.phone.scenes.voice
import dev.tvremote.phone.ui.ACCENT
import dev.tvremote.phone.ui.ACCENT_TEXT
import dev.tvremote.phone.ui.BG
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.RED
import dev.tvremote.phone.ui.SEL
import dev.tvremote.phone.ui.SOFT
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.col
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.gap
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.menu
import dev.tvremote.phone.ui.onBox
import dev.tvremote.phone.ui.oval
import dev.tvremote.phone.ui.say
import dev.tvremote.phone.ui.setMarked

import dev.tvremote.core.T
import dev.tvremote.core.scenes.Voice
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// "Nói nhanh" (Quick talk): hold a big button and speak, see what the sentence would do ABOVE the finger, let go to send.
// The old mic button (speak() in MainActivity.kt: Google's dialog) is untouched; this is a second way, in Tools. It listens with
// SpeechRecognizer (no dialog), shows each partial result, and Voice.preview says what the text would do without doing it.
// Denied microphone, or no recognizer on the phone: the old way. The web page has no such flow yet.

private const val ASK_MIC = 31
private const val CANCEL_DP = 110                                  // further than this from the button's centre: let go = cancel
private const val FINAL_WAIT_MS = 300L                             // after letting go: the final result, else the last partial
private const val FINAL_WAIT_EMPTY_MS = 1000L                      // nothing heard yet at that moment: give the recognizer a little more

/** One Quick talk session on top of the remote; [open] shows it, [close] ends it. State lives here, the views are rebuilt each time. */
internal class Talk(private val a: MainActivity) {
    private var overlay: FrameLayout? = null
    private var rec: SpeechRecognizer? = null                      // kept between presses (warm), released a little after closing
    private var live: TextView? = null
    private var willDo: TextView? = null
    private var plans: LinearLayout? = null
    private var state: TextView? = null
    private var btn: FrameLayout? = null
    private var ring: View? = null
    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    private var holding = false
    private var cancelling = false
    private var releasedAt = 0L
    private var settle: Runnable? = null
    private var lastPartial = ""
    private var alternatives: List<String> = emptyList()
    private var fake = false                                       // debug hook: results injected, no recognizer
    private var offline = false
    private var scenes: Map<String, String> = emptyMap()
    private val release = Runnable { rec?.destroy(); rec = null }

    // ------------------------------------------------------------ open / close
    fun open() {
        if (overlay != null) return
        if (a.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, a.packageName) != PackageManager.PERMISSION_GRANTED) {
            Popup.Builder(a).setTitle(T("talk.title")).setMessage(T("talk.why_mic"))
                .setPositiveButton(T("common.ok")) { _, _ -> if (Build.VERSION.SDK_INT >= 23) a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), ASK_MIC) }   // below 23 it is granted at install
                .setNegativeButton(T("common.cancel"), null).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(a) && !debuggable()) { a.speak(); return }   // no speech service: the old way
        h.removeCallbacks(release)
        scenes = SceneStore.list(a).associate { it.optString("id") to it.optString("name") }
        a.warmAppNames()                                           // the TV's app names, once per TV, so "mở <app>" previews at once
        val v = build()
        overlay = v
        a.findViewById<ViewGroup>(android.R.id.content).addView(v, ViewGroup.LayoutParams(-1, -1))
        idle()
        if (!debuggable() || SpeechRecognizer.isRecognitionAvailable(a)) warm()
        if (debuggable()) hook()
    }

    fun close() {
        cancelNow()
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        h.postDelayed(release, 30000)                              // the recognizer stays warm for a while for the next press
    }

    fun isOpen() = overlay != null

    fun destroy() { close(); h.removeCallbacks(release); release.run(); unhook() }

    /** Answer of the system's microphone question (MainActivity.onRequestPermissionsResult). */
    fun permission(granted: Boolean) {
        if (granted) open()
        else { Toast.makeText(a, T("talk.mic_denied"), Toast.LENGTH_LONG).show(); a.speak() }   // denied: the old dialog flow
    }

    // ------------------------------------------------------------ the recognizer
    private fun lang(): String = when (dev.tvremote.core.Words.lang) { "vi" -> "vi-VN"; "en" -> "en-US"; else -> dev.tvremote.core.Words.lang }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)  // only when the pack is there; the service falls back to online by itself
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, a.packageName)

    private fun warm() {
        if (rec == null) rec = SpeechRecognizer.createSpeechRecognizer(a).also { it.setRecognitionListener(listener) }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                rec?.checkRecognitionSupport(intent(), a.mainExecutor, object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(s: android.speech.RecognitionSupport) { offline = s.installedOnDeviceLanguages.any { it.startsWith(lang().substringBefore('-')) } }
                    override fun onError(error: Int) { }
                })
            } catch (e: Exception) { }
        } else offline = true
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { }
        override fun onBeginningOfSpeech() { }
        override fun onRmsChanged(rms: Float) { pulse(rms) }
        override fun onBufferReceived(b: ByteArray?) { }
        override fun onEndOfSpeech() { }
        override fun onEvent(t: Int, p: Bundle?) { }
        override fun onPartialResults(p: Bundle) { heard(p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() } ?: return, false) }
        override fun onResults(p: Bundle) { heard(p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.filter { it.isNotBlank() } ?: emptyList(), true) }
        override fun onError(error: Int) { failed(error) }
    }

    // ------------------------------------------------------------ the hold
    private fun begin(isFake: Boolean = false) {
        if (holding || overlay == null) return
        holding = true; cancelling = false; fake = isFake; lastPartial = ""; alternatives = emptyList()
        settle?.let { h.removeCallbacks(it) }; settle = null
        a.buzz(); beep()
        liveText(T("talk.listening"), false)
        plans?.removeAllViews(); willDo?.visibility = View.GONE
        state?.text = T("talk.release")
        paintButton()
        if (!isFake) try { rec?.startListening(intent()) } catch (e: Exception) { rec = null; warm(); try { rec?.startListening(intent()) } catch (e2: Exception) { failed(-1) } }
    }

    /** Partial or final text, best guess first. */
    private fun heard(list: List<String>, final: Boolean) {
        if (!holding && settle == null) return                     // a late result of a press already sent or cancelled
        if (list.isNotEmpty()) { lastPartial = list[0]; alternatives = list }
        if (holding) { show(); return }
        if (final) { settle?.let { h.removeCallbacks(it) }; settle = null; send(if (list.isNotEmpty()) list[0] else lastPartial) }
    }

    private fun failed(code: Int) {
        if (!holding && settle == null) return
        if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { end(); a.speak(); return }
        if (holding && code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && code != SpeechRecognizer.ERROR_CLIENT) {
            end(); say(T("talk.error", "code" to code)); return
        }
        if (holding) return                                        // quiet so far: the finger is still down, nothing to report yet
        settle?.let { h.removeCallbacks(it) }; settle = null
        if (lastPartial.isNotBlank()) send(lastPartial) else { end(); say(T("talk.heard_nothing")) }
    }

    private fun let_go(cancel: Boolean) {
        if (!holding) return
        holding = false
        if (cancel) { cancelNow(); say(T("talk.cancelled")); return }
        releasedAt = android.os.SystemClock.uptimeMillis()
        paintButton()
        if (!fake) try { rec?.stopListening() } catch (e: Exception) { }
        val r = Runnable { settle = null; if (lastPartial.isNotBlank()) send(lastPartial) else { end(); say(T("talk.heard_nothing")) } }
        settle = r
        h.postDelayed(r, if (lastPartial.isNotBlank()) FINAL_WAIT_MS else FINAL_WAIT_EMPTY_MS)
    }

    private fun cancelNow() {
        holding = false; cancelling = false
        settle?.let { h.removeCallbacks(it) }; settle = null
        if (!fake) try { rec?.cancel() } catch (e: Exception) { }
        lastPartial = ""
        idle()
    }

    private fun send(sentence: String) {
        end()
        if (sentence.isBlank()) { say(T("talk.heard_nothing")); return }
        liveText("“${sentence.trim()}”", true)
        Voice.preview(sentence, a.appNames, scenes, a.tv?.ready() == true)?.let { listPlans(listOf(it), true) }
        state?.text = T("talk.sent", "sentence" to sentence.trim())
        a.buzz()
        a.voice(sentence)
    }

    private fun end() { holding = false; settle?.let { h.removeCallbacks(it) }; settle = null; if (!fake) try { rec?.cancel() } catch (e: Exception) { }; paintButton() }

    // ------------------------------------------------------------ what the screen shows
    /** The line under the title: big and bold for what was heard, small and quiet for a hint or a message. */
    private fun liveText(s: String, heard: Boolean) {
        live?.apply { text = s; setTextColor(if (heard) TEXT else MUTED); textSize = if (heard) 24f else 16f; typeface = if (heard) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
    }

    private fun idle() {
        liveText(T("talk.hint"), false)
        plans?.removeAllViews(); willDo?.visibility = View.GONE
        state?.text = T("talk.hold")
        paintButton()
    }

    private fun say(s: String) { liveText(s, false); plans?.removeAllViews(); willDo?.visibility = View.GONE; state?.text = T("talk.hold"); paintButton() }

    private fun show() {
        if (lastPartial.isBlank()) liveText(T("talk.listening"), false) else liveText(lastPartial, true)
        val ps = alternatives.distinct().take(3).mapNotNull { Voice.preview(it, a.appNames, scenes, a.tv?.ready() == true) }.distinctBy { it.say }
        listPlans(ps, false)
    }

    /** One row per action the text could mean (the first is what a release sends). */
    private fun listPlans(ps: List<Voice.Plan>, sent: Boolean) {
        val box = plans ?: return
        box.removeAllViews()
        willDo?.visibility = if (ps.isEmpty()) View.GONE else View.VISIBLE
        ps.forEachIndexed { i, p ->
            val row = TextView(a)
            row.text = T(p.say.id, *p.say.args.map { it.key to it.value as Any? }.toTypedArray())
            row.textSize = if (i == 0) 17f else 14f
            row.typeface = if (i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val doing = p.command != null
            row.setTextColor(if (i == 0 && doing) ACCENT_TEXT else MUTED)
            row.setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(10))
            row.background = if (i == 0 && doing) a.onBox(true, 14) else a.box(BTN, 14)
            box.addView(row, a.gap(if (i == 0) 0 else 6))
        }
    }

    private fun paintButton() {
        val b = btn ?: return
        val col = when { cancelling -> 0xffc62828.toInt(); holding -> ACCENT; else -> SOFT }
        b.background = a.oval(col)
        (b.getChildAt(0) as? ImageView)?.setColorFilter(if (holding || cancelling) Color.WHITE else ACCENT_TEXT)
        state?.text = when { cancelling -> T("talk.release_cancel"); holding -> T("talk.release"); else -> T("talk.hold") }
        state?.setTextColor(if (cancelling) RED else MUTED)
        if (!holding) ring?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
    }

    private fun pulse(rms: Float) {
        if (!holding) return
        val s = 1f + (rms.coerceIn(0f, 10f) / 10f) * 0.35f         // the ring grows with the voice
        ring?.animate()?.scaleX(s)?.scaleY(s)?.setDuration(90)?.start()
    }

    private fun beep() {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 35).also { t -> t.startTone(ToneGenerator.TONE_PROP_ACK, 80); h.postDelayed({ t.release() }, 300) } } catch (e: Exception) { }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): FrameLayout {
        val root = FrameLayout(a)
        root.setBackgroundColor(BG)
        root.isClickable = true                                    // the remote underneath takes no touches
        root.fitsSystemWindows = true
        val c = a.col()
        c.setPadding(a.dp(18), a.dp(8), a.dp(18), a.dp(28))
        // top: title and close
        val top = LinearLayout(a)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(a.label(T("talk.title"), 20, TEXT).apply { typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(a).apply {                              // the other way (the old dialog), remembered
            text = T("talk.old_chip"); textSize = 13f; setTextColor(TEXT); gravity = Gravity.CENTER; background = a.box(BTN, 14); setPadding(a.dp(12), 0, a.dp(12), 0)
            contentDescription = T("talk.mode_old"); setOnClickListener { a.setTalkMode("old"); close(); a.speak() }
        }, LinearLayout.LayoutParams(-2, a.dp(40)).apply { rightMargin = a.dp(8) })
        top.addView(TextView(a).apply {
            setMarked("✕"); textSize = 20f; setTextColor(TEXT); gravity = Gravity.CENTER; background = a.box(BTN, 14)
            contentDescription = T("common.close"); setOnClickListener { close() }
        }, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
        c.addView(top, LinearLayout.LayoutParams(-1, -2))
        // middle, pushed down to sit just above the button: the live text, then what it would do (the hand covers the bottom, not this)
        val mid = a.col()
        mid.gravity = Gravity.BOTTOM
        live = TextView(a).apply { gravity = Gravity.CENTER; maxLines = 4 }
        mid.addView(live, a.gap(0))
        willDo = a.label(T("talk.will_do"), 12, MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        mid.addView(willDo, a.gap(18))
        plans = a.col()
        mid.addView(plans, a.gap(6))
        c.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = a.dp(24) })
        // bottom: the big button with its ring
        val stage = FrameLayout(a)
        ring = View(a).apply { background = a.oval(SEL) }
        stage.addView(ring, FrameLayout.LayoutParams(a.dp(150), a.dp(150), Gravity.CENTER))
        btn = FrameLayout(a)
        val mic = ImageView(a)
        mic.setImageResource(a.resources.getIdentifier("ic_mic", "drawable", a.packageName))
        btn!!.addView(mic, FrameLayout.LayoutParams(a.dp(48), a.dp(48), Gravity.CENTER))
        btn!!.contentDescription = T("talk.hold")
        stage.addView(btn, FrameLayout.LayoutParams(a.dp(120), a.dp(120), Gravity.CENTER))
        btn!!.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.parent.requestDisallowInterceptTouchEvent(true); begin() }
                MotionEvent.ACTION_MOVE -> {
                    val cx = v.width / 2f; val cy = v.height / 2f
                    val far = Math.hypot((e.x - cx).toDouble(), (e.y - cy).toDouble()) > a.dp(CANCEL_DP)
                    if (far != cancelling && holding) { cancelling = far; paintButton(); if (far) a.buzz() }
                }
                MotionEvent.ACTION_UP -> let_go(cancelling)
                MotionEvent.ACTION_CANCEL -> let_go(true)
            }
            true
        }
        c.addView(stage, LinearLayout.LayoutParams(-1, a.dp(170)))
        state = a.label(T("talk.hold"), 14, MUTED).apply { gravity = Gravity.CENTER }
        c.addView(state, a.gap(6))
        root.addView(c, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    // ------------------------------------------------------------ debug hook (debuggable builds only)
    // Real speech cannot be fed to an emulator: `adb shell am broadcast -a dev.tvremote.phone.TALK --es op down|partial|up|cancel|final --es text "mở youtube"`
    // runs the same code the touch and the recognizer do.
    private fun debuggable() = (a.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var rx: BroadcastReceiver? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun hook() {
        if (rx != null) return
        rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val text = i.getStringExtra("text").orEmpty()
                when (i.getStringExtra("op")) {
                    "down" -> begin(true)
                    "partial" -> heard(listOf(text) + (i.getStringExtra("alt")?.split("|") ?: emptyList()), false)
                    "final" -> heard(listOf(text), true)
                    "far" -> { cancelling = true; paintButton() }
                    "up" -> let_go(false)
                    "cancel" -> let_go(true)
                }
            }
        }
        val f = IntentFilter("dev.tvremote.phone.TALK")
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(rx, f, Context.RECEIVER_EXPORTED) else a.registerReceiver(rx, f)
    }

    private fun unhook() { rx?.let { try { a.unregisterReceiver(it) } catch (e: Exception) { } }; rx = null }
}

/** The names of this TV's apps, asked once per TV, so a spoken "mở <app>" is known before it is said (Voice.wantsApp). */
internal fun MainActivity.warmAppNames(then: (() -> Unit)? = null) {
    val ip = host
    val tv = tv
    if (appNamesFor == ip || ip == null || tv == null || !tv.ready()) { then?.invoke(); return }
    Thread {
        val link = dev.tvremote.core.adb.AdbLink(filesDir, ip)
        val names = try { dev.tvremote.core.ontv.RemoteLabels(link, { assets.open("tvr-helper.jar").use { it.readBytes() } }).names() } catch (e: Exception) { emptyMap() } finally { link.close() }
        ui.post { if (!isFinishing && host == ip) { appNames = names; appNamesFor = ip }; then?.invoke() }
    }.start()
}

internal fun MainActivity.setTalkMode(m: String) { prefs.edit().putString("talkMode", m).apply() }

/** The mic button: Quick talk or the old dialog, as chosen; the first time it asks (and remembers). */
internal fun MainActivity.micTap() {
    when (pref("talkMode")) {
        "quick" -> talk.open()
        "old" -> speak()
        else -> menu(T("talk.choose_title"), 0, listOf(
            Triple("@mic|" + T("talk.mode_quick"), T("talk.mode_quick_sub")) { setTalkMode("quick"); talk.open() },
            Triple("@mic|" + T("talk.mode_old"), T("talk.mode_old_sub")) { setTalkMode("old"); speak() }))
    }
}

/** Long-press on the mic: the other way, and the TV's own Assistant screen (the old long-press). */
internal fun MainActivity.micMenu() {
    val quick = pref("talkMode") == "quick"
    menu(T("remote.speak"), -1, listOf(
        Triple("@mic|" + T(if (quick) "talk.to_old" else "talk.to_quick"), T(if (quick) "talk.mode_old_sub" else "talk.mode_quick_sub")) { setTalkMode(if (quick) "old" else "quick") },
        Triple("@tv|" + T("talk.assistant"), "") { tv?.let { if (it.ready()) it.press(219) } }))
}
