package dev.tvremote.core.ontv
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.B64

/**
 * Drawing on the TV's screen, over whatever is on it, to show the person watching where to go. TV Setup draws (DrawLayer
 * there); a phone or computer with ADB sends the strokes. Each line sent is one JSON operation, coordinates in 0..10000 of
 * the screen's width and height:
 *   {"o":"s","id":7,"c":"#ff3b30","w":60,"p":[x,y,x,y...]}   a stroke begins (w: thickness in 1/10000 of the width)
 *   {"o":"a","id":7,"p":[x,y...]}                              more of it
 *   {"o":"i","id":8,"b":"<base64 PNG or JPEG>","x":5000,"y":5000,"w":4000}   a picture centred at x,y, w wide, as tall as its shape
 *   {"o":"u"} the last stroke or picture away · {"o":"x"} all of them · {"o":"h"} all, and the layer itself
 *   "k" on "s" and "i": the minutes it all stays after this one, 0 = until cleared, even through a restart (10 without it)
 *   {"o":"k","m":0} the choice changed while drawing: what is on the screen follows it (SetupNeed "drawkeep")
 * The way in: the shell asks TV Setup (a broadcast only the shell may send) for its port on 127.0.0.1 and a secret, then
 * ADB opens that port on the TV ("tcp:"): quick enough to follow a finger, and nothing listens beyond the TV itself.
 */
class Draw(private val adb: () -> Adb) {
    companion object {
        const val RECEIVER = RulesClient.SETUP + "/.DrawReceiver"
        const val MAX_BODY = 6_000_000                                // a picture, sent at most 1280 pixels wide
        const val MINUTES = 10                                        // how long a drawing stays when nobody chose (TV Setup's DrawLayer)
    }

    private var stream: Adb.Stream? = null
    private var from: Adb? = null
    /** This TV Setup draws pictures too (SetupNeed "pic"); known once the way is open. */
    @Volatile var pics = false
        private set
    /** ... and takes how long it stays (SetupNeed "drawkeep"); an older one clears after 10 minutes whatever is asked. */
    @Volatile var keeps = false
        private set

    /** Sends the operations, opening the way first when needed; once more on a fresh connection if the old one broke. No
     *  operations only opens it: done as drawing starts, so an old or missing TV Setup is told before anything is drawn. */
    @Synchronized @Throws(IOException::class)
    fun send(ops: JSONArray) {
        val text = StringBuilder()
        for (i in 0 until ops.length()) text.append(ops.getJSONObject(i).toString()).append('\n')
        val bytes = text.toString().toByteArray(Charsets.UTF_8)
        val pic = (0 until ops.length()).any { ops.getJSONObject(it).optString("o") == "i" }
        val keep = (0 until ops.length()).any { ops.getJSONObject(it).let { o -> o.optString("o") == "k" || o.optInt("k", MINUTES) != MINUTES } }
        if (ops.length() == 0) close()                                // a check asks TV Setup afresh: it may have been removed or updated since
        for (attempt in 0..1) {
            val s = try { open().also { if (pic && !pics) throw SetupNeed.Missing("pic", SetupUpdate.installed(adb())); if (keep && !keeps) throw SetupNeed.Missing("drawkeep", SetupUpdate.installed(adb())) } } catch (e: IOException) { if (attempt == 1 || e is SetupNeed.Missing) throw e; close(); continue }
            try { if (bytes.isNotEmpty()) s.write(bytes); return } catch (e: IOException) { close(); if (attempt == 1) throw e }
        }
    }

    @Synchronized fun close() {
        try { stream?.close() } catch (e: Exception) { }
        stream = null
    }

    private fun open(): Adb.Stream {
        val a = adb()
        stream?.let { if (from === a && !a.isDead()) return it }
        close()
        // the layer is a window over other apps: the shell may allow that for TV Setup, where the TV has no screen for it
        val out = a.shell("appops set ${RulesClient.SETUP} SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1; " +
            "am broadcast --include-stopped-packages -n $RECEIVER 2>&1", 20000)
        val m = Regex("data=\"([A-Za-z0-9+/=]+)\"").find(out)
        if (m == null) throw SetupNeed.Missing("draw", SetupUpdate.installed(a))
        val o = JSONObject(String(B64.decode(m.groupValues[1]), Charsets.UTF_8))
        if (!o.optBoolean("overlay")) throw IOException("draw:nooverlay")
        pics = o.optBoolean("pic")
        keeps = o.optBoolean("keep")
        val s = a.open("tcp:" + o.getInt("port"))
        s.write((o.getString("token") + "\n").toByteArray(Charsets.UTF_8))
        stream = s
        from = a
        return s
    }
}
