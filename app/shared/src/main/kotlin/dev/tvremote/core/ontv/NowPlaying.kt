package dev.tvremote.core.ontv
import org.json.JSONObject
import java.io.IOException
import dev.tvremote.core.adb.Adb

/**
 * What the TV plays, for the now playing bar of every remote: [Playing] runs on the TV while someone looks (asked within the
 * last 20 s), and says each change on one line. [state] answers at once from the last line, the position moved on by the time
 * since; [command] plays, pauses or seeks. [adb]: the TV's connection; [helper] gives assets/tvr-helper.jar.
 */
class NowPlaying(private val adb: () -> Adb, private val helper: () -> ByteArray) {
    @Volatile private var stream: Adb.Stream? = null
    @Volatile private var last: JSONObject = JSONObject().put("state", "none")
    @Volatile private var at = 0L                                     // when [last] came
    @Volatile private var asked = 0L
    @Volatile private var failedAt = 0L
    private val lock = Any()

    /** {state: none | playing | paused | buffering, pkg, app (its name), title (null when the player tells none), sub, pos, dur (ms, 0 unknown), seek (it can be sought)}. */
    fun state(): JSONObject {
        asked = System.currentTimeMillis()
        start()
        val o = JSONObject(last.toString())
        if (o.optString("state") == "playing" && o.has("pos")) {
            val pos = o.getLong("pos") + ((System.currentTimeMillis() - at) * o.optDouble("speed", 1.0)).toLong()
            o.put("pos", if (o.optLong("dur") > 0) minOf(pos, o.getLong("dur")) else pos)
        }
        return o
    }

    /** play, pause, next, prev, seek with [ms], or resume [pkg] (TV Home's widget: the app in "held"). */
    fun command(what: String, ms: Long = 0, pkg: String = "") {
        val line = when (what) {
            "play", "pause", "next", "prev" -> what
            "seek" -> "seek ${ms.coerceAtLeast(0)}"
            "resume" -> "play " + pkg.takeIf { Regex("[A-Za-z0-9_.]+").matches(it) }.let { it ?: throw IllegalArgumentException("bad package") }   // an app paused behind Home (state "none" with "held")
            else -> throw IllegalArgumentException("unknown: $what")
        }
        asked = System.currentTimeMillis()
        start()
        val s = stream ?: throw IOException("the TV did not answer")
        s.write("$line\n".toByteArray())
        if (what == "seek") synchronized(lock) { last = JSONObject(last.toString()).put("pos", ms); at = System.currentTimeMillis() }   // the bar does not jump back meanwhile
    }

    private fun start() = synchronized(lock) {
        if (stream != null || System.currentTimeMillis() - failedAt < 10000) return
        val s = try {
            val a = adb()
            a.open("shell:CLASSPATH=${RemoteLabels.install(a, helper())} app_process / dev.tvremote.core.Playing 2>/dev/null")
        } catch (e: Exception) {
            failedAt = System.currentTimeMillis()
            return
        }
        stream = s
        Thread({ read(s) }, "now-playing").apply { isDaemon = true }.start()
        Thread({                                                      // the helper quits after 30 s without a word from here
            try {
                while (stream === s && System.currentTimeMillis() - asked < 20000) { s.write("\n".toByteArray()); Thread.sleep(8000) }
            } catch (gone: Exception) { }
            s.close()
        }, "now-playing-beat").apply { isDaemon = true }.start()
    }

    private fun read(s: Adb.Stream) {
        val buf = ByteArray(8192)
        val line = java.io.ByteArrayOutputStream()                    // bytes, not chars: a read may end inside a Vietnamese letter
        try {
            while (true) {
                val n = s.read(buf, 0, buf.size, 0)
                if (n < 0) break
                for (i in 0 until n) {
                    if (buf[i] != '\n'.code.toByte()) { line.write(buf[i].toInt()); continue }
                    val text = line.toString("UTF-8").trim()
                    line.reset()
                    val o = try { JSONObject(text) } catch (e: Exception) { continue }
                    synchronized(lock) { last = o; at = System.currentTimeMillis() }
                }
            }
        } catch (e: IOException) {
        } finally {
            synchronized(lock) {
                if (stream === s) stream = null
                if (last.has("error")) failedAt = System.currentTimeMillis()   // this TV cannot: not asked again at every poll
                last = JSONObject().put("state", "none")
            }
            s.close()
        }
    }
}
