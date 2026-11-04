package dev.tvremote.setup

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.tvremote.core.ontv.Notify
import java.util.Locale

/**
 * Reads a message aloud when its sender asked (Notify.kt "speak"). Off = nothing: the TextToSpeech engine is bound only for one
 * message and shut down the moment it is read (or after [LIMIT_MS] if the engine never says), and the audio focus it took is
 * given back then, so the music that was ducked comes back. Main thread only.
 */
internal object NotifySpeak {
    private const val TAG = "NotifySpeak"
    private const val INIT_MS = 8000L                           // the broadcast's answer cannot wait longer; a cold engine (7 s seen) goes on starting
    private const val START_MS = 30_000L                        // an engine that has not come up by then is given up
    private const val LIMIT_MS = 90_000L                        // 300 characters never take this long to read
    private val ui = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var audio: AudioManager? = null
    private var gen = 0                                         // one number per message: an engine that comes up for an older one shuts itself down
    private val focus = AudioManager.OnAudioFocusChangeListener { }
    private val giveUp = Runnable { stop() }

    /** "noengine" / "nolang" are the reasons it can not be read (given to [done] once, then null when it has begun to speak). */
    fun speak(c: Context, title: String, text: String, hint: String, done: (why: String?, lang: String) -> Unit) {
        stop()                                                  // a newer message takes the place of the one being read
        val mine = gen
        val ctx = c.applicationContext
        val words = Notify.spoken(title, text)
        val langs = Notify.speechLangs(words, hint, Locale.getDefault().language)
        var lang = langs.first()                                // the first the TV has a voice for, once the engine is up
        var answered = false
        fun answer(why: String?) { if (!answered) { answered = true; ui.removeCallbacks(giveUp); done(why, langs.first()) } }
        // a missing engine fails at once (status ERROR); a slow one is only waited for: the sender hears "ok" and it reads when ready
        val slow = Runnable { Log.w(TAG, "the engine is slow to start"); answer(null) }
        val timeout = Runnable { if (mine == gen) { Log.w(TAG, "the engine did not start"); stop() }; answer("noengine") }
        ui.postDelayed(slow, INIT_MS); ui.postDelayed(timeout, START_MS)
        lateinit var engine: TextToSpeech
        engine = try {
            TextToSpeech(ctx) { status ->
                ui.removeCallbacks(timeout); ui.removeCallbacks(slow)
                if (mine != gen) { engine.shutdown(); return@TextToSpeech }       // a newer message, or given up, in the meantime
                if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "no text-to-speech engine: $status"); stop(); return@TextToSpeech answer("noengine") }
                val found = langs.firstOrNull { engine.isLanguageAvailable(Locale(it)) >= TextToSpeech.LANG_AVAILABLE }
                Log.i(TAG, "language: wanted ${langs.joinToString()} (hint '$hint', TV ${Locale.getDefault().language}), voice: ${found ?: "none"}")
                if (found == null) { Log.w(TAG, "no voice for ${langs.joinToString()}"); stop(); return@TextToSpeech answer("nolang") }
                lang = found
                engine.language = Locale(lang)
                engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { Log.i(TAG, "speaking in $lang: ${words.length} characters") }
                    override fun onDone(id: String?) { ui.post { Log.i(TAG, "done"); stop() } }
                    @Deprecated("old callback") override fun onError(id: String?) { ui.post { Log.w(TAG, "speech failed"); stop() } }
                })
                audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                @Suppress("DEPRECATION") audio?.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                val r = engine.speak(words, TextToSpeech.QUEUE_FLUSH, Bundle(), "tvr-notify")
                if (r != TextToSpeech.SUCCESS) { Log.w(TAG, "speak refused: $r"); stop(); return@TextToSpeech answer("noengine") }
                ui.postDelayed(giveUp, LIMIT_MS)
                answer(null)
            }
        } catch (e: Exception) { Log.w(TAG, "text-to-speech failed: $e"); ui.removeCallbacks(timeout); ui.removeCallbacks(slow); return answer("noengine") }
        tts = engine
    }

    private fun stop() {
        gen++
        ui.removeCallbacks(giveUp)
        tts?.let { try { it.stop(); it.shutdown() } catch (e: Exception) { } }
        tts = null
        @Suppress("DEPRECATION") audio?.abandonAudioFocus(focus)
        audio = null
    }
}
