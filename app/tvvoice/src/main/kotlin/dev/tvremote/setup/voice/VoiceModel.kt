package dev.tvremote.setup.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * The model: unpacked from the APK's assets to files/model once (Vosk reads files, ~27 MB), loaded on its own thread (1-4 s on a TV box)
 * while the microphone already records, kept for [KEEP_MS] after the last use and then freed (~100 MB a TV box needs back).
 */
object VoiceModel {
    private const val TAG = "TvVoice"
    private const val KEEP_MS = 5 * 60_000L
    private val ui = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var vocab: Vocab? = null
    private var loading = false
    private val waiting = ArrayList<(Model?, Vocab) -> Unit>()

    /** The model's words ([all]), and by folded spelling the likely ones that fold to it ([fold]: "tat" -> tất, tắt, tật; build.gradle.kts). */
    class Vocab(val all: Set<String>, val fold: Map<String, List<String>>)
    private var spare: Recognizer? = null                              // the free-speech recognizer of the last use, reset: its composed graph is already built (the first seconds of a new one cost 3x the sound)
    @Synchronized fun takeSpare(): Recognizer? = spare.also { spare = null }
    @Synchronized fun putSpare(r: Recognizer) { if (model == null || spare != null) r.close() else spare = r }
    private val free = Runnable { synchronized(this) { spare?.close(); spare = null; model?.close(); model = null; vocab = null; Log.i(TAG, "model freed") } }

    /** Copies the model out of the APK when files/model is not this APK's (VERSION differs); blocks. */
    @Synchronized fun unpack(c: Context): File {
        val dir = File(c.filesDir, "model")
        val want = c.assets.open("model/VERSION").use { it.readBytes().toString(Charsets.UTF_8) }
        if (File(dir, "VERSION").let { it.isFile && it.readText() == want }) return dir
        val t0 = System.currentTimeMillis()
        val tmp = File(c.filesDir, "model.tmp"); tmp.deleteRecursively()
        val names = c.assets.open("model/files.txt").use { it.readBytes().toString(Charsets.UTF_8) }.lines().filter { it.isNotBlank() }
        for (n in names) {
            val f = File(tmp, n); f.parentFile?.mkdirs()
            c.assets.open("model/$n").use { i -> f.outputStream().use { i.copyTo(it, 64 * 1024) } }
        }
        File(tmp, "VERSION").writeText(want)                          // last: a copy cut short is never taken for a whole one
        dir.deleteRecursively(); tmp.renameTo(dir)
        Log.i(TAG, "unpacked in ${System.currentTimeMillis() - t0} ms")
        return dir
    }

    /** Calls [then] (on the loading thread, or at once) with the model, null when it cannot load. */
    fun get(c: Context, then: (Model?, Vocab) -> Unit) {
        val ready: Model?
        synchronized(this) {
            ui.removeCallbacks(free)
            ready = model
            if (ready == null) {
                waiting += then
                if (loading) return
                loading = true
            }
        }
        if (ready != null) { then(ready, vocab ?: Vocab(emptySet(), emptyMap())); return }
        Thread({
            var m: Model? = null; var v = Vocab(emptySet(), emptyMap())
            try {
                val t0 = System.currentTimeMillis()
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val dir = unpack(c.applicationContext)
                v = Vocab(File(dir, "graph/words.txt").readLines().mapTo(HashSet()) { it.substringBefore(' ') },
                    File(dir, "graph/fold.txt").readLines().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t').split(' ') })
                m = Model(dir.path)
                Log.i(TAG, "model loaded in ${System.currentTimeMillis() - t0} ms, ${v.all.size} words")
            } catch (e: Throwable) { Log.w(TAG, "model: $e") }
            val calls: List<(Model?, Vocab) -> Unit>
            synchronized(this) { model = m; vocab = v; loading = false; calls = ArrayList(waiting); waiting.clear() }
            for (f in calls) f(m, v)
        }, "voice-model").start()
    }

    /** One use is over: the model goes after [KEEP_MS] without another. */
    fun done() { ui.removeCallbacks(free); ui.postDelayed(free, KEEP_MS) }
}
