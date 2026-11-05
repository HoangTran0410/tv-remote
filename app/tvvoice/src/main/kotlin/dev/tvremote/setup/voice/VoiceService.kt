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
    private val lang by lazy { VoiceProtocol.langOf(packageManager, packageName) ?: "vi" }   // this APK's model: Vietnamese numbers or English ones
    private var job: Listen? = null

    override fun onStartListening(intent: Intent, cb: Callback) {
        job?.cancel()
        val test = if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) java.io.File(filesDir, "test.wav").takeIf { it.isFile } else null
        if (test != null) Log.i("TvVoice", "test audio ${test.length()} bytes")
        job = Listen(intent.getStringArrayListExtra(VoiceProtocol.WORDS).orEmpty(), cb, test, intent.getBooleanExtra(VoiceProtocol.FREE_SPEECH, false)).also { it.start() }
    }
    override fun onStopListening(cb: Callback) { job?.finish() }
    override fun onCancel(cb: Callback) { job?.cancel(); job = null }
    override fun onDestroy() { job?.cancel(); job = null; super.onDestroy() }

    /** [test]: a debug build's files/test.wav (16 kHz mono) played as if said, in real time: the tests' way to speak to a TV. */
    private inner class Listen(private val words: List<String>, private val cb: Callback, private val test: java.io.File?, private val freeSpeech: Boolean) : Thread("voice-listen") {
        @Volatile private var stop = false                            // finish: the sentence as heard so far
        @Volatile private var dead = false                            // cancel: nothing more is said
        @Volatile var written: List<Pair<String, String>> = emptyList()   // said -> written ("vê tê vê" -> "vtv"), see [grammar]
        @Volatile private var model: org.vosk.Model? = null          // for [free]
        private var second: Free? = null                             // the free-speech pass, started with the first sound of speech
        private val pre = java.util.ArrayDeque<ByteArray>()           // the last chunks before speech was noticed: a word's start is quiet
        fun finish() { stop = true }
        fun cancel() { dead = true; stop = true }

        override fun run() {
            val rate = 16000
            val chunk = rate / 10 * 2                                 // 100 ms of 16-bit mono
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunk * 4))
            } catch (e: Exception) { null }
            if (test == null && (rec == null || rec.state != AudioRecord.STATE_INITIALIZED)) { rec?.release(); say { cb.error(SpeechRecognizer.ERROR_AUDIO) }; return }
            var vosk: Recognizer? = null
            var wav: java.io.InputStream? = null
            val failed = java.util.concurrent.atomic.AtomicBoolean(false)  // the model or the list could not load
            val held = ArrayList<ByteArray>()                             // what was said before the model was ready
            val t0 = System.currentTimeMillis()
            VoiceModel.get(this@VoiceService) { m, vocab ->
                if (m == null) { failed.set(true); return@get }
                model = m
                val (g, back) = grammar(words, vocab)
                written = back
                val list = JSONArray(g)
                // setWords below: each word's confidence says whether the sound needs a second look ([answer])
                list.put("[unk]")                                     // anything else: heard as unknown, not forced into a command word
                try { synchronized(held) { vosk = Recognizer(m, rate.toFloat(), list.toString()).also { it.setWords(true) } } } catch (e: Exception) { failed.set(true) }
                Log.i("TvVoice", "ready +${System.currentTimeMillis() - t0} ms, ${list.length()} words")
            }
            try {
                if (test == null) rec!!.startRecording()
                wav = test?.inputStream()?.also { it.skip(44) }
                say { cb.readyForSpeech(Bundle()) }
                val buf = ByteArray(chunk)
                val tw = System.currentTimeMillis(); var nchunk = 0; var ended = false
                var spoke = false; var last = ""
                var fed = false; var quiet = 0; val gap = java.util.ArrayDeque<ByteArray>()   // free speech: fed from the first word on; a long silence held back
                while (!stop && !failed.get()) {
                    val n = if (wav == null) rec!!.read(buf, 0, buf.size) else { val due = tw + 100L * ++nchunk; Thread.sleep(maxOf(0L, due - System.currentTimeMillis())); java.util.Arrays.fill(buf, 0); if (wav.read(buf) < 0 && !ended) { ended = true; Log.i("TvVoice", "test audio ended") }; buf.size }   // paced by the clock, not by sleep+decode (a slow box would play it slower than said, a mic is buffered); after its end: silence
                    if (n <= 0) { say { cb.error(SpeechRecognizer.ERROR_AUDIO) }; return }
                    val db = level(buf, n)
                    say { cb.rmsChanged(db) }
                    if (!spoke && db > 5f) { spoke = true; say { cb.beginningOfSpeech() } }
                    if (freeSpeech && model != null) {                            // free speech hears the same sound in a thread of its own; its recognizer is built while the person is still silent (0.5 s of CPU on a TV box)
                        val f = second ?: Free().also { second = it; it.start() }
                        if (!fed) {
                            pre.addLast(buf.copyOf(n)); if (pre.size > 3) pre.removeFirst()
                            if (spoke) { fed = true; for (c in pre) f.feed(c); pre.clear() }
                        } else if (db > 5f) { for (c in gap) f.feed(c); gap.clear(); quiet = 0; f.feed(buf.copyOf(n)) }
                        else if (++quiet <= 3) f.feed(buf.copyOf(n))              // a word's end and a short pause go in; a long silence is held back (and added only if speech returns): it costs as much to decode as speech and the answer waits for it
                        else { gap.addLast(buf.copyOf(n)); if (gap.size > 10) gap.removeFirst() }
                    }
                    val r = synchronized(held) { vosk.also { if (it == null) held += buf.copyOf(n) } } ?: continue
                    val feed = synchronized(held) { ArrayList(held).also { held.clear() } } + buf.copyOf(n)
                    var end = false
                    for (b in feed) if (r.acceptWaveForm(b, b.size)) end = true
                    if (end) {
                        val json = r.result
                        if (text(json, "text", written).isNotEmpty()) { say { cb.endOfSpeech() }; val b = answer(json); say { cb.results(b) }; return }
                        last = ""                                     // a pause after noise: still listening
                    } else {
                        val p = text(r.partialResult, "partial", written)
                        if (p.isNotEmpty() && p != last) { last = p; if (!spoke) { spoke = true; say { cb.beginningOfSpeech() } }; say { cb.partialResults(bundle(p)) } }
                    }
                }
                if (failed.get()) { say { cb.error(VoiceProtocol.ERROR_NO_MODEL) }; return }
                say { cb.endOfSpeech() }
                val b = vosk?.let { answer(it.finalResult) }
                say { if (b != null) cb.results(b) else cb.error(SpeechRecognizer.ERROR_NO_MATCH) }
            } catch (e: Exception) {
                Log.w("TvVoice", "listen: $e"); say { cb.error(SpeechRecognizer.ERROR_CLIENT) }
            } finally {
                try { rec?.stop() } catch (e: Exception) { }
                wav?.close()
                rec?.release()
                second?.stop(); second = null                         // before the model is let go
                synchronized(held) { vosk?.close(); vosk = null }
                VoiceModel.done()
            }
        }

        /**
         * The results for the commands' list's [json]: its text alone when every word was heard clearly (a command: exact, no extra work),
         * else also what free speech hears in the same sound ([free]), as [VoiceProtocol.FREE]. Null: nothing heard at all.
         */
        private fun answer(json: String): Bundle? {
            val g = text(json, "text", written)
            val confs = try { JSONObject(json).optJSONArray("result")?.let { a -> List(a.length()) { a.getJSONObject(it).optDouble("conf", 0.0) } } } catch (e: Exception) { null }.orEmpty()
            if (g.isNotEmpty() && VoiceProtocol.sure(confs)) { second?.stop(); return bundle(g) }   // a command heard clearly: the second look is dropped
            val f = second?.result().orEmpty()
            if (f.isEmpty() || f == g) return if (g.isEmpty()) null else bundle(g)
            return Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(g, f)); putBoolean(VoiceProtocol.FREE, true) }
        }

        /**
         * The same sound through the model's own language model (Gr.fst), no list: any words. On a TV box it runs at about real time
         * (FPT 1.07x, BRAVIA 2x), so it listens from the first word on in its own thread and is mostly done when the person stops; a
         * command heard clearly drops it ([stop]), and [result] gives up after [FREE_MS] (the commands' text is then used alone).
         */
        private inner class Free {
            private val q = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
            private val end = ByteArray(0)
            @Volatile private var quit = false
            @Volatile private var text = ""
            private val th = Thread({ run() }, "voice-free")
            fun start() { th.start() }
            fun feed(b: ByteArray) { q.offer(b) }
            private fun run() {
                val t0 = System.currentTimeMillis(); var ms = 0
                val m = model ?: return
                val r = VoiceModel.takeSpare() ?: try { Recognizer(m, 16000f) } catch (e: Exception) { return }
                val made = System.currentTimeMillis() - t0
                try {
                    val out = ArrayList<String>()
                    while (!quit) {
                        val b = q.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        if (b === end) { out += text(r.finalResult, "text", emptyList()); text = out.filter { it.isNotEmpty() }.joinToString(" "); break }
                        ms += b.size / 32
                        if (r.acceptWaveForm(b, b.size)) out += text(r.result, "text", emptyList())
                    }
                    Log.i("TvVoice", "free: ${if (quit) "dropped" else "\"$text\""} after ${System.currentTimeMillis() - t0} ms, ${android.os.Debug.threadCpuTimeNanos() / 1_000_000} ms of cpu (recognizer $made ms) for $ms ms of sound")   // cpu vs wall: a busy or swapping box shows in the gap
                } catch (e: Exception) { Log.w("TvVoice", "free: $e"); try { r.close() } catch (x: Exception) { }; return }
                try { if (quit) r.finalResult; r.reset(); VoiceModel.putSpare(r) }   // a cut-off one ends its sentence first: reset alone left it garbling the next (tested)
                catch (e: Exception) { r.close() }
            }
            /** What free speech heard, waiting for it to catch up with the sound; "" when it did not in time. */
            fun result(): String { q.offer(end); th.join(FREE_MS); if (th.isAlive) { quit = true; return "" }; return text }
            fun stop() { quit = true; th.join(1500) }
        }

        private fun say(f: () -> Unit) { if (!dead) try { f() } catch (e: Exception) { dead = true; stop = true } }   // TV Setup went away
    }

    private companion object {
        const val FREE_MS = 6_000L                                    // the second look is dropped (the first answer kept) when it needs longer than this after the sound ended
    }

    private fun bundle(t: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(t)) }

    /** Vosk's JSON to the words: "[unk]" out, what was spelled back to how it is written, number words as digits. */
    private fun text(json: String, key: String, written: List<Pair<String, String>>): String = try {
        var t = " " + JSONObject(json).optString(key).split(' ').filter { it.isNotEmpty() && it != "[unk]" }.joinToString(" ") + " "
        for ((said, w) in written) t = t.replace(" $said ", " $w ")
        VoiceProtocol.digits(t.trim(), lang)
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
