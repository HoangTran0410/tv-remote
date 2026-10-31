package dev.tvremote.core.web
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.web.TvPaths.TMP_DIR
import dev.tvremote.core.web.TvPaths.needed
import dev.tvremote.core.web.TvPaths.parseDfFree
import dev.tvremote.core.web.TvPaths.roomFor
import dev.tvremote.core.web.TvPaths.safePath
import java.io.IOException
import java.io.InputStream
import org.json.JSONObject

/** Files copied onto the TV: refused before a byte goes when there is no room, nothing half written left behind. */
internal class TvCopy(core: ManageCore) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? {
        when (what) {
            "space" -> return HttpServer.Response.json(space(r.q("path"), r.q("size").toLongOrNull() ?: 0L, r.q("apk") == "1"))
        }
        return null
    }

    /**
     * Copies `length` bytes into a file on the TV. Refused before a byte goes when the TV has no room for it ([ensureRoom]; `factor`
     * 2 for an APK, which is copied and then installed); stopped on the person's Stop, and then, or when the copy breaks, the
     * part that arrived is deleted, so no half file stays on the TV.
     */
    @Throws(IOException::class)
    fun push(input: InputStream, length: Long, dest: String, job: JSONObject?, factor: Int = 1) { at("upload") {
        val adb = link.get()
        val cancel = current.get()
        if (length > 0) ensureRoom(dest.substringBeforeLast('/', "/").ifEmpty { "/" }, length, factor)
        try {
            var sent = 0L
            val feed = { s: Adb.Stream ->
                cancel?.onStop { s.close() }                              // a write waiting on a slow TV lets go at once
                val b = ByteArray(65536)
                var done = 0L
                while (length < 0 || done < length) {
                    cancel?.check()
                    val n = input.read(b, 0, if (length < 0) b.size else minOf(b.size.toLong(), length - done).toInt())
                    if (n < 0) break
                    s.write(b, 0, n)
                    done += n; sent = done
                    if (job != null && length > 0) try { job.put("pct", (done * 90 / length).toInt()) } catch (ignored: Exception) { }
                }
                if (length >= 0 && done < length) throw IOException("upload cut short")
            }
            if (length >= 0) adb.put(dest, length, true, null, feed)                  // the size known: the stream stays open until it is all there
            else {                                                    // chunked: aside too, so a failure never truncates a good file at dest
                adb.writeTo("$dest.part").use(feed)
                var stored = false
                for (i in 0 until 40) {                               // cat finishes writing shortly after the stream closes
                    cancel?.check()
                    if (adb.shell("stat -c %s " + Adb.q("$dest.part") + " 2>/dev/null").trim() == sent.toString()) { stored = true; break }
                    try { Thread.sleep(150) } catch (e: InterruptedException) { break }
                }
                if (!stored) throw IOException("the TV did not store the whole file (is its storage full?)")
                if (!adb.shell("mv -f " + Adb.q("$dest.part") + " " + Adb.q(dest) + " && echo ok").trim().endsWith("ok")) throw IOException("the TV could not store the file")
                return
            }
            val want = length.toString()
            for (i in 0 until 40) {
                cancel?.check()
                val got = adb.shell("stat -c %s " + Adb.q(dest) + " 2>/dev/null").trim()
                if (got == want) return
                try { Thread.sleep(150) } catch (e: InterruptedException) { break }
            }
            throw IOException("the TV did not store the whole file (is its storage full?)")
        } catch (e: Exception) {                                          // stopped, cut, or the TV full: nothing half written is left
            // every upload goes to dest.part and moves in whole, so a good file already at dest is never ours to delete or truncate
            try { Thread.sleep(200); adb.shell("rm -f " + Adb.q("$dest.part")) } catch (ignored: Exception) { }
            if (cancel?.stopped == true && e !is Cancelled) throw Cancelled()   // the Stop broke the stream first: still a stop, not an error
            throw e
        }
    } }

    /** Bytes free on the storage holding `dir`, or null when the TV did not say. */
    fun freeOn(dir: String): Long? = try { parseDfFree(link.get().shell("df -k " + Adb.q(dir) + " 2>/dev/null | tail -2")) } catch (e: IOException) { null }

    /** Throws [NoRoom] when `size` bytes (times `factor`, plus a reserve) do not fit where `dir` is. An unknown amount lets it pass. */
    fun ensureRoom(dir: String, size: Long, factor: Int) {
        val free = freeOn(dir) ?: return
        if (!roomFor(size, factor, free)) throw NoRoom(size, free, needed(size, factor), factor > 1)
    }

    /** The page asks before it sends: {ok, free, need}; `error` is the message [NoRoom] carries when not ok. */
    fun space(path: String, size: Long, apk: Boolean): JSONObject {
        val dir = if (apk) TMP_DIR else safePath(path)
        val factor = if (apk) 2 else 1
        val free = freeOn(dir)
        val o = JSONObject().put("need", needed(size, factor))
        if (free == null) return o.put("ok", true)
        o.put("free", free).put("ok", roomFor(size, factor, free))
        return if (o.getBoolean("ok")) o else o.put("error", NoRoom(size, free, needed(size, factor), apk).message)
    }
}
