package dev.tvremote.setup.voice

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Android's speech service, offline: TV Setup's voice card asks it as it asks Google's ([VoiceProtocol.WORDS]: the words to listen for).
 * The microphone starts at once and its sound is kept while the model loads, so nothing said before it is ready is lost; then
 * words come as they are said (partial results, ~0.25 s on a TV box) and the sentence ends on Vosk's own pause (~1 s) or when
 * TV Setup stops it.
 */
class VoiceService : RecognitionService() {
    private var job: Listen? = null

    override fun onStartListening(intent: Intent, cb: Callback) {
        job?.cancel()
        val test = if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) java.io.File(filesDir, "test.wav").takeIf { it.isFile } else null
        job = Listen(intent.getStringArrayListExtra(VoiceProtocol.WORDS).orEmpty(), cb, test).also { it.start() }
    }
    override fun onStopListening(cb: Callback) { job?.finish() }
    override fun onCancel(cb: Callback) { job?.cancel(); job = null }
    override fun onDestroy() { job?.cancel(); job = null; super.onDestroy() }

    /** [test]: a debug build's files/test.wav (16 kHz mono) played as if said, in real time: the tests' way to speak to a TV. */
    private inner class Listen(private val words: List<String>, private val cb: Callback, private val test: java.io.File?) : Thread("voice-listen") {
        @Volatile private var stop = false                            // finish: the sentence as heard so far
        @Volatile private var dead = false                            // cancel: nothing more is said
        @Volatile var written: List<Pair<String, String>> = emptyList()   // said -> written ("vê tê vê" -> "vtv"), see [grammar]
        fun finish() { stop = true }
        fun cancel() { dead = true; stop = true }

        override fun run() {
            val rate = 16000
            val chunk = rate / 10 * 2                                 // 100 ms of 16-bit mono
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunk * 4))
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); say { cb.error(SpeechRecognizer.ERROR_AUDIO) }; return }
            var vosk: Recognizer? = null
            var wav: java.io.InputStream? = null
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)  // the model or the list could not load
            val held = ArrayList<ByteArray>()                             // what was said before the model was ready
            val t0 = System.currentTimeMillis()
            VoiceModel.get(this@VoiceService) { m, vocab ->
                if (m == null) { failed.set(true); return@get }
                val (g, back) = grammar(words, vocab)
                written = back
                val list = JSONArray(g)
                list.put("[unk]")                                     // anything else: heard as unknown, not forced into a command word
                try { synchronized(held) { vosk = Recognizer(m, rate.toFloat(), list.toString()) } } catch (e: Exception) { failed.set(true) }
                Log.i("TvVoice", "ready +${System.currentTimeMillis() - t0} ms, ${list.length()} words")
            }
            try {
                rec.startRecording()
                wav = test?.inputStream()?.also { it.skip(44) }
                say { cb.readyForSpeech(Bundle()) }
                val buf = ByteArray(chunk)
                var spoke = false; var last = ""
                while (!stop && !failed.get()) {
                    val n = if (wav == null) rec.read(buf, 0, buf.size) else { Thread.sleep(100); java.util.Arrays.fill(buf, 0); wav.read(buf); buf.size }   // after its end: silence
                    if (n <= 0) { say { cb.error(SpeechRecognizer.ERROR_AUDIO) }; return }
                    val db = level(buf, n)
                    say { cb.rmsChanged(db) }
                    if (!spoke && db > 5f) { spoke = true; say { cb.beginningOfSpeech() } }
                    val r = synchronized(held) { vosk.also { if (it == null) held += buf.copyOf(n) } } ?: continue
                    val feed = synchronized(held) { ArrayList(held).also { held.clear() } } + buf.copyOf(n)
                    var end = false
                    for (b in feed) if (r.acceptWaveForm(b, b.size)) end = true
                    if (end) {
                        val t = text(r.result, "text", written)
                        if (t.isNotEmpty()) { say { cb.endOfSpeech(); cb.results(bundle(t)) }; return }
                        last = ""                                     // a pause after noise: still listening
                    } else {
                        val p = text(r.partialResult, "partial", written)
                        if (p.isNotEmpty() && p != last) { last = p; if (!spoke) { spoke = true; say { cb.beginningOfSpeech() } }; say { cb.partialResults(bundle(p)) } }
                    }
                }
                if (failed.get()) { say { cb.error(SpeechRecognizer.ERROR_SERVER) }; return }
                val t = vosk?.let { text(it.finalResult, "text", written) }.orEmpty()
                say { cb.endOfSpeech(); if (t.isNotEmpty()) cb.results(bundle(t)) else cb.error(SpeechRecognizer.ERROR_NO_MATCH) }
            } catch (e: Exception) {
                Log.w("TvVoice", "listen: $e"); say { cb.error(SpeechRecognizer.ERROR_CLIENT) }
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                wav?.close()
                rec.release()
                synchronized(held) { vosk?.close(); vosk = null }
                VoiceModel.done()
            }
        }

        private fun say(f: () -> Unit) { if (!dead) try { f() } catch (e: Exception) { dead = true; stop = true } }   // TV Setup went away
    }

    private fun bundle(t: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(t)) }

    /** Vosk's JSON to the words: "[unk]" out, what was spelled back to how it is written, number words as digits. */
    private fun text(json: String, key: String, written: List<Pair<String, String>>): String = try {
        var t = " " + JSONObject(json).optString(key).split(' ').filter { it.isNotEmpty() && it != "[unk]" }.joinToString(" ") + " "
        for ((said, w) in written) t = t.replace(" $said ", " $w ")
        VoiceProtocol.digits(t.trim())
    } catch (e: Exception) { "" }

    /**
     * TV Setup's entries ([VoiceProtocol.WORDS]: "said" or "said=written") as Vosk's list: a word the model has stays; one it lacks is
     * replaced by the likely words of the same folded spelling (an app's name typed without accents); one with neither is left out.
     * Longest "said" first in the back map, so "hát tê vê" wins over "tê vê".
     */
    private fun grammar(entries: List<String>, v: VoiceModel.Vocab): Pair<List<String>, List<Pair<String, String>>> {
        val out = LinkedHashSet<String>()
        val back = ArrayList<Pair<String, String>>()
        for (e in entries) {
            val said = e.substringBefore('=')
            val ws = said.split(' ').mapNotNull { w -> if (w in v.all) w else v.fold[VoiceProtocol.fold(w)]?.firstOrNull() }
            if (ws.isEmpty()) continue
            val s = ws.joinToString(" ")
            out += s
            if ('=' in e) back += s to e.substringAfter('=')
        }
        return out.toList() to back.sortedByDescending { it.first.length }
    }

    /** The level in about Google's scale (-2 quiet to 10 loud): the voice card's sound graph reads it the same. */
    private fun level(b: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) { val s = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8); sum += s.toDouble() * s; i += 2 }
        val rms = sqrt(sum / maxOf(1, n / 2))
        return (20 * log10(maxOf(rms, 1.0)) - 50).toFloat().coerceIn(-2f, 10f)
    }
}
