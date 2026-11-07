package dev.tvremote.core.ontv
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.B64

/**
 * A video in a box over the TV's screen, moved and resized while it plays (VideoLayer in TV Setup draws it): a video file, or a
 * phone's or computer's camera. The way in is Draw's: the shell asks TV Setup (a broadcast only the shell may send) for a port
 * on 127.0.0.1 and a secret, then ADB opens that port on the TV ("tcp:"), so nothing listens beyond the TV itself.
 *   control: [op] sends one JSON operation (VideoLayer lists them) and gives back the state the TV answered
 *   a file: [file] copies one already on the TV into TV Setup's own folder (TV Setup has no storage permission) and plays it
 *   a camera: [start] opens a second connection for its frames, [frame] sends each one, [stop] ends it (the box goes)
 */
class Video(private val adb: () -> Adb) {
    companion object {
        const val RECEIVER = RulesClient.SETUP + "/.VideoReceiver"
        const val MAX_FRAME = 4_000_000
    }

    private class Way(val port: Int, val token: String, val dir: String)
    private var way: Way? = null
    private var ctl: Adb.Stream? = null
    private var from: Adb? = null
    private var cam: Adb.Stream? = null
    private val camLock = Any()

    /** Sends [o] and gives the state after it; once more on a fresh connection if the old one broke. */
    @Synchronized @Throws(IOException::class)
    fun op(o: JSONObject): JSONObject {
        val bytes = (o.toString() + "\n").toByteArray(Charsets.UTF_8)
        for (attempt in 0..1) {
            try {
                val s = control()
                s.write(bytes)
                return answer(s)
            } catch (e: IOException) {
                closeControl()
                way = null                                            // TV Setup may have restarted (an update, low memory) on another port
                if (attempt == 1 || e is SetupNeed.Missing || e.message == "video:nooverlay") throw e
            }
        }
        throw IOException("video: no answer")
    }

    /** Plays [path] (a file on the TV) in the box at [o]'s place; o.sent: the page sent it there only for this. */
    @Throws(IOException::class)
    fun file(path: String, o: JSONObject): JSONObject {
        val w = open()
        val ext = path.substringAfterLast('.', "mp4").filter { it.isLetterOrDigit() }.take(5).ifEmpty { "mp4" }
        val name = "v.$ext"
        val cp = if (o.optBoolean("sent")) "mv" else "cp"               // sent just for this: moved, so no copy is left in Download
        val out = adb().shell("mkdir -p ${Adb.q(w.dir)} && rm -f ${Adb.q(w.dir)}/* && $cp ${Adb.q(path)} ${Adb.q(w.dir + "/" + name)} && " +
            "chmod 644 ${Adb.q(w.dir + "/" + name)} && echo ok 2>&1", 60000)  // cp makes it 600, the shell's: TV Setup could not read it
        if (!out.contains("ok")) {                                     // Android 11 on a TCL: Android/data is shut to the shell; TV Setup writes it itself
            val w2 = adb().shell("content write --uri content://${RulesClient.SETUP}.home/video/$name < ${Adb.q(path)} 2>&1 && " +
                (if (cp == "mv") "rm -f ${Adb.q(path)}; " else "") + "echo ok", 60000)
            if (w2.contains("No files supported") || w2.contains("FileNotFound")) throw SetupNeed.Missing("videocopy", SetupUpdate.installed(adb()))   // a TV Setup before HomeInfo.openFile
            if (w2.trim() != "ok") throw IOException("video:copy " + out.trim().take(200))
        }
        return op(JSONObject(o.toString()).put("o", "file").put("name", name))
    }

    /** A camera's stream starts: [head] has its width and height ("w", "h") and the box's place. */
    @Throws(IOException::class)
    fun start(head: JSONObject) {
        synchronized(camLock) {
            stopCam()
            val s = try { adb().open("tcp:" + open().port) } catch (e: IOException) {
                if (e is SetupNeed.Missing) throw e
                way = null; adb().open("tcp:" + open().port)          // a port from before TV Setup restarted: asked again once
            }
            val w = open()
            s.write((w.token + "\n" + JSONObject(head.toString()).put("o", "live").toString() + "\n").toByteArray(Charsets.UTF_8))
            cam = s
        }
    }

    /** One encoded frame (Annex B); false when no stream is open (it ended on the TV: the page starts again). */
    @Throws(IOException::class)
    fun frame(data: ByteArray, len: Int, key: Boolean, us: Long): Boolean {
        synchronized(camLock) {
            val s = cam ?: return false
            if (len <= 0 || len > MAX_FRAME) throw IllegalArgumentException("bad frame")
            val h = java.nio.ByteBuffer.allocate(13).putInt(len).put(if (key) 1 else 0).putLong(us).array()
            try { s.write(h); s.write(data, 0, len) } catch (e: IOException) { stopCam(); return false }
            return true
        }
    }

    fun stop() = synchronized(camLock) { stopCam() }

    private fun stopCam() {
        try { cam?.close() } catch (e: Exception) { }
        cam = null
    }

    @Synchronized private fun closeControl() {
        try { ctl?.close() } catch (e: Exception) { }
        ctl = null
    }

    private fun control(): Adb.Stream {
        val a = adb()
        ctl?.let { if (from === a && !a.isDead()) return it }
        closeControl()
        val w = open()
        val s = a.open("tcp:" + w.port)
        s.write((w.token + "\n").toByteArray(Charsets.UTF_8))
        ctl = s
        return s
    }

    /** The port and secret, asked afresh when the link changed (TV Setup may have been updated or removed since). */
    @Synchronized private fun open(): Way {
        val a = adb()
        way?.let { if (from === a && !a.isDead()) return it }
        // the box is a window over other apps: the shell may allow that for TV Setup, where the TV has no screen for it
        val out = a.shell("appops set ${RulesClient.SETUP} SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1; " +
            "am broadcast --include-stopped-packages -n $RECEIVER 2>&1", 20000)
        val m = Regex("data=\"([A-Za-z0-9+/=]+)\"").find(out) ?: throw SetupNeed.Missing("video", SetupUpdate.installed(a))
        val o = JSONObject(String(B64.decode(m.groupValues[1]), Charsets.UTF_8))
        if (!o.optBoolean("overlay")) throw IOException("video:nooverlay")
        val w = Way(o.getInt("port"), o.getString("token"), o.optString("dir").ifEmpty { "/sdcard/Android/data/${RulesClient.SETUP}/files/video" })
        way = w
        from = a
        return w
    }

    private fun answer(s: Adb.Stream): JSONObject {
        val b = ByteArrayOutputStream()
        val one = ByteArray(1)
        val until = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < until) {
            val n = s.read(one, 0, 1, 8000)
            if (n < 0) throw IOException("video: closed")
            if (n == 0) continue
            if (one[0] == '\n'.code.toByte()) return JSONObject(b.toString("UTF-8"))
            b.write(one[0].toInt())
        }
        throw IOException("video: no answer")
    }
}
