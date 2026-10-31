package dev.tvremote.home.wall

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.StatFs
import android.view.WindowManager
import dev.tvremote.home.HomeConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The media library behind the video and GIF backgrounds. One folder in TV Home's own files, a background is an id, never a
 * file name, so a later choice (a theme) can switch between them without anything being overwritten by the next upload:
 *
 *   <external files>/videos/<hex8>.mp4   what plays (a GIF was turned into this on arrival; a video that was light enough is kept as sent)
 *   <external files>/videos/<hex8>.jpg   the poster: the first frame at the screen's size, what shows while the video starts
 *   <external files>/videos/index.json   {"items":[{id, name, kind: video|gif, ms, size, w, h, added, orig}]}, one writer ([lock])
 *   <external files>/videos/in/          where a file lands before [ingest] (a client pushes there over ADB, the QR page writes there)
 *
 * The wall id is "vid:<hex8>" ([HomeConfig.isVideo]); [Walls.file] gives the poster for it, so everything that treats a wall as a
 * picture (the screensaver, the blurred widgets, the panel's thumbnails) works on it unchanged.
 * A theme says which backgrounds it keeps alive with [refs]; [delete] never removes one the current setting or a theme uses.
 */
object Videos {
    class Item(val id: String, val name: String, val kind: String, val ms: Long, val size: Long, val w: Int, val h: Int, val added: Long, val orig: Long) {
        val wall get() = HomeConfig.VIDEO_PREFIX + id
        fun json() = JSONObject().put("id", wall).put("name", name).put("kind", kind).put("ms", ms).put("size", size).put("w", w).put("h", h).put("added", added).put("orig", orig)
    }

    /** What is under way: a GIF or a heavy video being made light. [pct] 0..100; [error] set when it ended badly (cleared by the next ingest). */
    class Job(val name: String, @Volatile var pct: Int = 0, @Volatile var error: String? = null, @Volatile var id: String? = null) {
        fun json() = JSONObject().put("name", name).put("pct", pct).put("error", error ?: JSONObject.NULL).put("id", id?.let { HomeConfig.VIDEO_PREFIX + it } ?: JSONObject.NULL)
    }

    /** The hook for "this background is still wanted" (themes): each says whether it keeps wall id [String] alive. */
    val refs = CopyOnWriteArrayList<(String) -> Boolean>()

    @Volatile var job: Job? = null
    private val lock = Any()

    fun dir(c: Context) = File(c.getExternalFilesDir(null), "videos").also { it.mkdirs() }
    fun staging(c: Context) = File(dir(c), "in").also { it.mkdirs() }
    fun file(c: Context, id: String) = File(dir(c), idOf(id) + ".mp4")
    fun poster(c: Context, id: String) = File(dir(c), idOf(id) + ".jpg")
    private fun idOf(id: String) = id.removePrefix(HomeConfig.VIDEO_PREFIX)
    private fun index(c: Context) = File(dir(c), "index.json")

    fun list(c: Context): List<Item> = synchronized(lock) {
        try {
            val a = JSONObject(index(c).readText()).getJSONArray("items")
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                Item(idOf(o.getString("id")), o.optString("name"), o.optString("kind", "video"), o.optLong("ms"), o.optLong("size"), o.optInt("w"), o.optInt("h"), o.optLong("added"), o.optLong("orig"))
            }.filter { file(c, it.id).isFile }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(c: Context, items: List<Item>) {
        val f = index(c)
        val tmp = File(f.parentFile, "index.json.part")
        tmp.writeText(JSONObject().put("items", JSONArray(items.map { it.json().put("id", it.id) })).toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Whether the wall id [wall] is still wanted: the current setting (the wall on now or one of the set) or a theme ([refs]). */
    fun inUse(conf: JSONObject, wall: String): Boolean {
        if (conf.optString("wall") == wall) return true
        conf.optJSONArray("walls")?.let { a -> for (i in 0 until a.length()) if (a.optString(i) == wall) return true }
        return refs.any { it(wall) }
    }

    /** Whether a theme keeps [wall] (a video or a photo) alive. Themes are saved in the settings; [HomeApp] adds the check. */
    fun kept(wall: String) = refs.any { it(wall) }

    /** Removes [wall] from the library unless [inUse]; true when it is gone. */
    fun delete(c: Context, wall: String, conf: JSONObject): Boolean {
        if (!HomeConfig.isVideo(wall) || inUse(conf, wall)) return false
        synchronized(lock) {
            file(c, wall).delete(); poster(c, wall).delete()
            save(c, list(c).filter { it.wall != wall })
        }
        return true
    }

    fun free(c: Context): Long = try { StatFs(dir(c).path).availableBytes } catch (e: Exception) { 0L }

    /** Null when [bytes] may come in; else the reason: "size" (over [HomeConfig.VIDEO_MAX_BYTES]), "full" (the library has [HomeConfig.VIDEO_LIB_MAX]), "space" (not enough free room). */
    fun admit(c: Context, bytes: Long): String? = when {
        bytes <= 0 || bytes > HomeConfig.VIDEO_MAX_BYTES -> "size"
        list(c).size >= HomeConfig.VIDEO_LIB_MAX -> "full"
        free(c) < bytes * 2 + 100L * 1024 * 1024 -> "space"                    // the file as sent and the one made from it, with room to spare
        else -> null
    }

    /** The screen's physical size (landscape): what the panel really has, whatever the app's window says. Pages crop previews to it. */
    fun display(c: Context): Pair<Int, Int> {
        val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var w = 0; var h = 0
        try {
            if (Build.VERSION.SDK_INT >= 23) wm.defaultDisplay.mode.let { w = it.physicalWidth; h = it.physicalHeight }
        } catch (e: Throwable) { }
        if (w <= 0 || h <= 0) {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels; h = dm.heightPixels
        }
        return maxOf(w, h) to minOf(w, h)
    }

    // ---- what a file is and whether it is light enough to keep as sent ----

    class Probe(val kind: String, val mime: String, val w: Int, val h: Int, val ms: Long, val bitrate: Long, val fps: Int)

    /** Looks at [f] without decoding it: a GIF by its first bytes, a video by its container; null when it is neither. */
    fun probe(f: File): Probe? {
        val head = ByteArray(12)
        val n = try { f.inputStream().use { it.read(head) } } catch (e: Exception) { return null }
        if (n >= 6 && String(head, 0, 4, Charsets.ISO_8859_1) == "GIF8") {
            return try {
                val m = android.graphics.Movie.decodeFile(f.path) ?: return null
                Probe("gif", "image/gif", m.width(), m.height(), m.duration().toLong(), 0, 0)
            } catch (e: Throwable) { null }
        }
        return video(f.path)
    }

    /** [probe] of a video link, from its header alone (MediaExtractor reads the moov box, not the clip); null when it cannot be read. */
    fun probeUrl(u: String): Probe? = video(u)

    private fun video(src: String): Probe? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(src)
            val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            val fm = ex.getTrackFormat(t)
            val r = MediaMetadataRetriever()
            val (rot, br) = try {
                if (src.startsWith("http")) r.setDataSource(src, HashMap()) else r.setDataSource(src)
                (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) to (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L)
            } finally { r.release() }
            var w = fm.getInteger(MediaFormat.KEY_WIDTH); var h = fm.getInteger(MediaFormat.KEY_HEIGHT)
            if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
            val ms = if (fm.containsKey(MediaFormat.KEY_DURATION)) fm.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            val fps = if (fm.containsKey(MediaFormat.KEY_FRAME_RATE)) try { fm.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (e: Exception) { 0 } else 0
            Probe("video", fm.getString(MediaFormat.KEY_MIME) ?: "", w, h, ms, br, fps)
        } catch (e: Exception) { null } finally { ex.release() }
    }

    /** Whether this TV decodes [mime] at [w] x [h] in hardware (the software decoders are what lags a weak box). */
    fun hwDecodes(mime: String, w: Int, h: Int): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } && isHw(ci) &&
                (Build.VERSION.SDK_INT < 21 || ci.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(w, h) != false)
        }
    } catch (e: Throwable) { false }

    fun isHw(ci: android.media.MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated
        else !(ci.name.startsWith("OMX.google.") || ci.name.startsWith("c2.android.") || ci.name.contains(".sw.", true))

    /** What the person chose for the next uploads ("vfps", "vres" in [HomeConfig]); 0 = the source's own frame rate / the screen's own size. */
    class Opts(val fps: Int, val side: Int)
    fun opts(conf: JSONObject) = Opts(conf.optString("vfps").toIntOrNull() ?: 0, conf.optString("vres").toIntOrNull() ?: 0)

    /**
     * The frame rate a made video gets (0: the source's own): the one chosen, else the source's, down to 30 when this TV's encoder
     * or decoder cannot do it at [w] x [h] (making a 60 fps video again at 60 helps nobody whose decoder stops at 30).
     */
    fun outFps(p: Probe, o: Opts, w: Int, h: Int, enc: (Int, Int, Int) -> Boolean, dec: (String, Int, Int, Int) -> Boolean): Int {
        if (p.kind == "gif") return o.fps
        val f = if (o.fps > 0) o.fps else p.fps
        return if (f > 30 && (!enc(w, h, f) || !dec(MediaFormat.MIMETYPE_VIDEO_AVC, w, h, f))) 30 else o.fps
    }

    /** The biggest picture a video may be on a [sw] x [sh] screen: the screen's, or its shape with the short side at most [Opts.side]. */
    fun limit(sw: Int, sh: Int, o: Opts): Pair<Int, Int> =
        if (o.side <= 0 || o.side >= sh) sw to sh else (o.side.toLong() * sw / sh).toInt() / 2 * 2 to o.side

    /** Whether a hardware decoder of this TV does [w] x [h] at [fps] (a weak chip does 1080p30 and not 1080p60). */
    fun hwRate(mime: String, w: Int, h: Int, fps: Int): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } && isHw(ci) &&
                ci.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble()) != false
        }
    } catch (e: Throwable) { false }

    const val CAP_BPS = 8_000_000L                                  // above this a big file is a lot of reading for a weak box's storage and decoder

    /**
     * Why [p] must be made light before it can be the background (null: keep as sent). A GIF always (frame-by-frame on the CPU is the
     * heavy way; as an mp4 it is hardware-decoded and 5-20x smaller); a video when this TV does not decode it in hardware, when it
     * is bigger than the screen (or than the size chosen), over [CAP_BPS], over the frame rate chosen, or, with "Original", faster
     * than the decoder does at that size.
     */
    fun heavy(p: Probe, sw: Int, sh: Int, o: Opts = Opts(0, 0), hw: (String, Int, Int) -> Boolean = ::hwDecodes, rate: (String, Int, Int, Int) -> Boolean = ::hwRate): String? {
        val (lw, lh) = limit(sw, sh, o)
        return when {
            p.kind == "gif" -> "gif"
            !hw(p.mime, p.w, p.h) -> "codec"
            p.w > lw || p.h > lh -> "size"
            p.bitrate > CAP_BPS -> "bitrate"
            o.fps > 0 && p.fps > o.fps + 1 -> "fps"
            o.fps == 0 && p.fps > 0 && !rate(p.mime, p.w, p.h, p.fps) -> "fps"
            else -> null
        }
    }

    /** The size a made-light video is: the source's own shape within [sw] x [sh] (never bigger than it is), both even. */
    fun target(w: Int, h: Int, sw: Int, sh: Int): Pair<Int, Int> {
        val k = minOf(1f, sw.toFloat() / w, sh.toFloat() / h)
        fun even(v: Float) = maxOf(2, (v.toInt() / 2) * 2)
        return even(w * k) to even(h * k)
    }

    /** About 0.065 bits per pixel per frame: 1080p30 is 4 Mbps, 720p30 1.8 Mbps; enough for a wallpaper that moves slowly, light for the box. */
    fun bitrate(w: Int, h: Int, fps: Int): Int = (w.toLong() * h * fps * 0.065).toLong().coerceIn(600_000L, 6_000_000L).toInt()

    // ---- arrival ----

    /**
     * [staged] (in [staging]) becomes a library item: looked at, made light when it must be ([Transcode], progress in [job]), given its
     * poster, entered in the index. Blocking: run it off the main thread. Returns the new item or sets [job]'s error and returns null.
     * The staged file is always gone afterwards.
     */
    fun ingest(c: Context, staged: File, name: String, conf: JSONObject = JSONObject()): Item? {
        val j = Job(name.take(80)).also { job = it }
        val origSize = staged.length()
        var made: String? = null                                          // a half-made file and poster of a failed arrival are removed
        try {
            val p = probe(staged)
            if (p == null || p.w < 16 || p.h < 16 || (p.ms in 1..299)) throw Failure("video")
            if (p.kind == "gif" && p.ms <= 0) throw Failure("gif")           // a still picture: the photo upload is for those
            val (sw, sh) = display(c)
            val id = freshId(c); made = id
            val out = file(c, id)
            val o = opts(conf)
            val why = heavy(p, sw, sh, o)
            var ms = p.ms; var w = p.w; var h = p.h
            if (why == null) {
                if (!staged.renameTo(out)) { staged.copyTo(out, true); staged.delete() }
            } else {
                val m = makeLight(c, staged, out, p, sw, sh, o) { j.pct = it } ?: throw Failure("transcode")
                ms = m.ms; w = m.w; h = m.h
            }
            val q = probe(out) ?: run { out.delete(); throw Failure("video") }
            val shot = firstFrame(out, sw, sh) ?: run { out.delete(); throw Failure("video") }
            poster(c, id).outputStream().use { shot.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            shot.recycle()
            val item = Item(id, name.substringBeforeLast('.').take(60), p.kind, maxOf(ms, q.ms), out.length(), w, h, System.currentTimeMillis(), origSize)
            synchronized(lock) { save(c, list(c) + item) }
            j.id = id; j.pct = 100
            return item
        } catch (e: Failure) { j.error = e.code } catch (e: Throwable) { j.error = "video" } finally { staged.delete() }
        made?.let { file(c, it).delete(); poster(c, it).delete() }
        return null
    }

    private class Failure(val code: String) : Exception(code)

    class Made(val ms: Long, val w: Int, val h: Int)

    /**
     * [src] made light into [out] ([Transcode]) for a [sw] x [sh] screen, at the size and rate [o] allows; once more at 30 fps and at most
     * 720p when what came out is not sane. Null (and no [out]) when neither is. Blocking: an upload's arrival, a web wall's link ([NetVideo]).
     */
    fun makeLight(c: Context, src: File, out: File, p: Probe, sw: Int, sh: Int, o: Opts, progress: (Int) -> Unit): Made? {
        val (lw, lh) = limit(sw, sh, o)
        var (tw, th) = target(p.w, p.h, lw, lh)
        var fps = outFps(p, o, tw, th, Transcode::encodes, ::hwRate)
        var r = Transcode.run(c, src, out, p, tw, th, fps, progress)
        if (r == null || !Transcode.sane(out, tw, th, if (fps > 0) fps else p.fps.takeIf { it > 0 } ?: 30)) {   // once more, lighter: 30 fps, at most 720p
            out.delete()
            target(tw, th, 1280, 720).let { tw = it.first; th = it.second }; fps = 30
            r = Transcode.run(c, src, out, p, tw, th, fps, progress)
            if (r == null || !Transcode.sane(out, tw, th, fps)) { out.delete(); return null }
        }
        return Made(r.ms, tw, th)
    }

    /** An id nobody has (checked against the index and the folder: a half-made file never collides). */
    private fun freshId(c: Context): String {
        val rnd = java.util.Random()
        while (true) {
            val id = "%08x".format(rnd.nextInt())
            if (list(c).none { it.id == id } && !File(dir(c), "$id.mp4").exists()) return id
        }
    }

    /** The first frame of [f], covering [sw] x [sh] but not bigger than 1920x1080: what the video shows before it moves. */
    fun firstFrame(f: File, sw: Int, sh: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val (dw, dh) = Wall.want(sw, sh)
            val (w, h) = Wall.cover(b.width, b.height, dw, dh)
            if (w == b.width && h == b.height) b else Bitmap.createScaledBitmap(b, w, h, true).also { if (it !== b) b.recycle() }
        } catch (e: Throwable) { null } finally { try { r.release() } catch (e: Exception) { } }
    }

    // ---- how it plays on THIS TV: measured by VideoWall for [Verdict.WINDOW_MS] after each start, the last result kept in prefs ----

    private fun prefs(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** The last measurement {id, fps, want, cpu, at, level: ok|choppy|slow, slow, off} or null. [off]: Home shows the poster instead of this video. */
    fun perf(c: Context): JSONObject? = try { prefs(c).getString("vperf", null)?.let { JSONObject(it) } } catch (e: Exception) { null }

    fun measured(c: Context, id: String, fps: Float, want: Float, cpu: Int, at: Long): JSONObject =
        Verdict.next(perf(c), id, fps, want, cpu, at).also { prefs(c).edit().putString("vperf", it.toString()).apply() }

    /** The person chose [wall] again (or the rotation came back to it): the verdict is forgotten and it gets another try. */
    fun forgetPerf(c: Context, wall: String) { if (perf(c)?.optString("id") == wall) prefs(c).edit().remove("vperf").apply() }

    /** Whether Home should show the poster instead of the video [id] (it was too slow for this TV twice). */
    fun off(c: Context, id: String) = perf(c)?.let { it.optString("id") == id && it.optBoolean("off") } == true
}

/** The verdict on a measurement: pure, so it is tested without a TV. */
object Verdict {
    const val WINDOW_MS = 20_000L
    const val SMOOTH = 0.9f                                             // shown / wanted fps from here: smooth
    const val SLOW = 0.6f                                               // under this: too slow for this TV
    const val TWICE = 2                                                 // this many slow measurements in a row and Home stops playing it

    /** "ok", "choppy" (between [SLOW] and [SMOOTH]) or "slow". */
    fun level(fps: Float, want: Float): String {
        val r = if (want > 0) fps / want else 1f
        return if (r >= SMOOTH) "ok" else if (r >= SLOW) "choppy" else "slow"
    }

    /** The new record after one measurement; [prev] counts the slow ones in a row for the same video. */
    fun next(prev: JSONObject?, id: String, fps: Float, want: Float, cpu: Int, at: Long): JSONObject {
        val slow = if (level(fps, want) == "slow") (if (prev?.optString("id") == id) prev.optInt("slow") else 0) + 1 else 0
        return JSONObject().put("id", id).put("fps", Math.round(fps)).put("want", Math.round(want)).put("cpu", cpu).put("at", at)
            .put("level", level(fps, want)).put("slow", slow).put("off", slow >= TWICE)
    }
}
