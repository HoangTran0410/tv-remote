package dev.tvremote.home.manage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import dev.tvremote.home.ThemeShots
import java.io.ByteArrayOutputStream
import java.io.File
import dev.tvremote.home.apps.Apps
import dev.tvremote.home.wall.Online
import dev.tvremote.home.apps.Pins
import dev.tvremote.home.Store
import dev.tvremote.home.apps.LiveWidgets
import dev.tvremote.home.wall.Walls
import dev.tvremote.home.wall.Videos
import dev.tvremote.home.HomeConfig

/**
 * What a client is told of TV Home and what it may change: one place for the two ways in, [HomeReceiver] (a phone or computer with ADB,
 * the answer in a broadcast) and [ManageServer] (the settings page TV Home serves itself). The answer: {version, config, apps:
 * [{pkg, label}], photo: whether wall.jpg is there, photos: [{id, v}] the ones there are (v: when each changed), framePics: [{id, v}] the photo frame's, thumbs: {id: base64
 * JPEG}, bing: {date, title, copyright} of the picture kept}. Always on the main thread: Store and the screen live there.
 */
object HomeApi {
    /**
     * "Pin from a link" (HomeReceiver extra "pin", POST /h/pin): [req] is {"url", "name"?, "img"?}. Returns "ok" or an error code
     * (pin_bad_link, pin_no_app, pin_failed). Blocking (the network, the system's pin call): never on the main thread.
     */
    fun pin(c: Context, req: JSONObject): String = try {
        val url = req.optString("url")
        if (dev.tvremote.home.apps.YouTube.parse(url) != null)
            dev.tvremote.home.apps.YouTube.pin(c, url, req.optString("name").ifBlank { null }, req.optString("img").ifBlank { null })
        else dev.tvremote.home.apps.WebLink.pin(c, url, req.optString("name").ifBlank { null }, req.optString("pkg").ifBlank { null })   // any other http(s) link or app deep link
    } catch (e: Exception) { dev.tvremote.home.apps.YouTube.FAILED }

    /** "What opens this link" (HomeReceiver extra "link", POST /h/link): [req] {"url"}; see [dev.tvremote.home.apps.WebLink.preview]. Quick, any thread. */
    fun link(c: Context, req: JSONObject): JSONObject = dev.tvremote.home.apps.WebLink.preview(c, req.optString("url"))

    /** [set] changed first (HomeConfig.merge cleans it), [drop] one photo deleted first, [thumbs] the photos' small pictures too. */
    fun snapshot(c: Context, set: JSONObject?, drop: String?, thumbs: Boolean): JSONObject {
        if (drop != null && HomeConfig.isPhoto(drop) && Videos.kept(drop)) throw IllegalStateException("inuse")   // a saved theme keeps this photo
        if (drop != null && HomeConfig.isPhoto(drop)) drop(c, drop)
        else if (drop != null && HomeConfig.isVideo(drop) && !Videos.delete(c, drop, Store.get(c))) throw IllegalStateException("inuse")   // the setting (or a theme) still shows it
        if (set == null) { LiveWidgets.refresh(c); Pins.refresh(c) }         // a client reading: what the providers and the apps offer is as true as this moment
        val config = Store.forClients(c, if (set == null) Store.get(c) else Store.change(c, set))
        val apps = JSONArray()
        for (a in Apps.list(c)) apps.put(JSONObject().put("pkg", a.pkg).put("label", a.label))
        val photos = JSONArray()
        val pictures = JSONObject()
        for (id in Walls.photos(c)) {
            val f = Walls.file(c, id)!!
            photos.put(JSONObject().put("id", id).put("v", f.lastModified()).put("size", f.length()))   // size: a theme file shows what its photos add up to
            if (thumbs) thumb(c, id)?.let { pictures.put(id, it) }
        }
        val frame = JSONArray()                                       // the photo frame's photos: id, stamp (the page's thumbnails are kept by it)
        for (id in HomeConfig.frame(config.optJSONArray("frame"))) {
            val f = dev.tvremote.home.apps.Pics.file(c, id)
            if (!f.isFile) continue
            frame.put(JSONObject().put("id", id).put("v", f.lastModified()))
            if (thumbs) picThumb(c, id, f)?.let { pictures.put(id, it) }
        }
        val library = JSONArray()
        for (v in Videos.list(c)) {
            library.put(v.json())
            if (thumbs) thumb(c, v.wall)?.let { pictures.put(v.wall, it) }
        }
        val (dw, dh) = Videos.display(c)
        // the theme previews' backgrounds (ThemeShots): the person's own by theme id, the ready-made ones by id; the page draws the rest
        val shots = JSONObject()
        if (thumbs) {
            fun add(kind: String, id: String, f: File?) { if (f != null && f.isFile) shots.optJSONObject(kind).let { o -> (o ?: JSONObject().also { shots.put(kind, it) }).put(id, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) } }
            for (t in config.optJSONArray("themes").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) } }) add("mine", t.optString("id"), ThemeShots.mine(c, t.optString("id")))
            for (r in HomeConfig.ready) add("ready", r.id, ThemeShots.ready(c, r))
            ThemeShots.current(c, Store.get(c))?.let { (k, f) -> shots.put("now", JSONObject().put("key", k).apply { if (f != null) put("jpg", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) }) }   // the live preview's background
        }
        return JSONObject().put("version", c.packageManager.getPackageInfo(c.packageName, 0).versionName)
            .put("config", config).put("apps", apps)
            .put("linkOk", true)                                    // takes any link and answers what opens it (/h/link): the page's preview asks first
            .put("pinOk", true)                                     // takes links to pin (/h/pin, HomeReceiver pin): clients check it, an older TV Home lacks it
            .put("photo", File(c.getExternalFilesDir(null), "wall.jpg").isFile)
            .put("photos", photos).put("framePics", frame).put("thumbs", pictures).put("shots", shots)
            // the media library (Videos.kt): its items, the work under way, the room left and the cap; the screen's real size for previews
            .put("video", JSONObject().put("items", library).put("job", Videos.job?.json() ?: JSONObject.NULL).put("free", Videos.free(c)).put("max", HomeConfig.VIDEO_MAX_BYTES).put("libmax", HomeConfig.VIDEO_LIB_MAX).put("perf", Videos.perf(c) ?: JSONObject.NULL))
            .put("display", JSONObject().put("w", dw).put("h", dh))
            .also { o -> Online.info(c)?.let { o.put("bing", it) } }
    }

    /** 96x54 JPEG of a photo as base64 (the page's chips show it), kept in the cache by the photo's time stamp. */
    private fun thumb(c: Context, id: String): String? {
        val f = Walls.file(c, id) ?: return null
        val cache = File(c.cacheDir, "t-$id-${f.lastModified()}.jpg")
        val bytes = if (cache.isFile) cache.readBytes() else {
            val b = Walls.small(c, id, 96, 54) ?: return null
            val out = ByteArrayOutputStream()
            Bitmap.createBitmap(b, (b.width - 96) / 2, (b.height - 54) / 2, 96, 54).compress(Bitmap.CompressFormat.JPEG, 70, out)
            c.cacheDir.listFiles { x -> x.name.startsWith("t-$id-") }?.forEach { it.delete() }
            out.toByteArray().also { cache.writeBytes(it) }
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 96x54 JPEG of a frame photo (pics/<id>.jpg, already small), cropped to fill, cached like [thumb]. */
    private fun picThumb(c: Context, id: String, f: File): String? = try {
        val cache = File(c.cacheDir, "t-$id-${f.lastModified()}.jpg")
        val bytes = if (cache.isFile) cache.readBytes() else {
            val b = (dev.tvremote.home.apps.Pics.load(c, id) as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return null
            val k = maxOf(96f / b.width, 54f / b.height)
            val s = Bitmap.createScaledBitmap(b, maxOf(96, Math.round(b.width * k)), maxOf(54, Math.round(b.height * k)), true)
            val out = ByteArrayOutputStream()
            Bitmap.createBitmap(s, (s.width - 96) / 2, (s.height - 54) / 2, 96, 54).compress(Bitmap.CompressFormat.JPEG, 70, out)
            c.cacheDir.listFiles { x -> x.name.startsWith("t-$id-") }?.forEach { it.delete() }
            out.toByteArray().also { cache.writeBytes(it) }
        }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    /**
     * [file] (in [Videos.staging]) is taken into the media library on a thread of its own (a GIF or a heavy video is made light first:
     * seconds to minutes, progress in `video.job`), then chosen like a new photo. "ok" (started), or why not: "size", "full", "space", "busy".
     */
    fun addVideo(c: Context, file: File, name: String): String {
        val why = Videos.admit(c, file.length())
        if (why != null) { file.delete(); return why }
        if (Videos.job?.let { it.error == null && it.pct < 100 } == true) { file.delete(); return "busy" }   // one at a time: the encoder is one
        val app = c.applicationContext
        val conf = Store.get(c)                                       // the quality chosen now (vfps, vres) is what this one is made with
        Videos.job = Videos.Job(name.take(80))
        Thread({
            val item = Videos.ingest(app, file, name, conf)
            if (item != null) android.os.Handler(android.os.Looper.getMainLooper()).post {
                val conf = Store.get(app)
                val set = conf.optJSONArray("walls")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
                val walls = if (conf.optString("wevery", "off") == "off") listOf(item.wall) else set + item.wall
                Store.change(app, JSONObject().put("walls", JSONArray(walls)).put("wall", item.wall))
                ManageServer.listener?.changed()
            }
        }, "video-ingest").apply { priority = Thread.MIN_PRIORITY; start() }
        return "ok"
    }

    /** A photo deleted: its file, its place in the set, and the background if it was showing (the next of the set, or Night). */
    fun drop(c: Context, id: String) {
        Walls.file(c, id)?.delete()
        val conf = Store.get(c)
        val a = conf.getJSONArray("walls")
        val walls = List(a.length()) { a.getString(it) }.filter { it != id }
        val wall = if (conf.optString("wall") != id) conf.optString("wall") else walls.firstOrNull { Walls.usable(c, it) } ?: "night"
        Store.change(c, JSONObject().put("walls", JSONArray(walls.ifEmpty { listOf(wall) })).put("wall", wall).put("photo", System.currentTimeMillis()))
    }

    /**
     * The JPEG (already the screen's size: the page crops it) into the first free photo slot (what `Home.photo` on the clients does:
     * "photo" is wall.jpg, then photo2 ...), written beside it and renamed so the launcher never reads half a file, then chosen (and added
     * to the rotation's set when that is on). "ok", or "full" (all slots used), or "image" (not a picture TV Home can read).
     */
    /**
     * The person's own picture for the tile [target] ("pics"): [jpeg] is shrunk and kept by [Pics.save], the setting names it.
     * "ok", "target" (not an app or shortcut), "full" (PICS_MAX others already) or "image" (not a picture). Main thread, like the rest.
     */
    fun saveAppPic(c: Context, target: String, jpeg: ByteArray): String {
        if (target == HomeConfig.FRAME) {                             // one more photo for the photo frame widget: same file, its id at the end of "frame"
            val have = HomeConfig.frame(Store.get(c).optJSONArray("frame"))
            if (have.size >= HomeConfig.FRAME_MAX) return "full"
            val id = dev.tvremote.home.apps.Pics.save(c, jpeg) ?: return "image"
            Store.change(c, JSONObject().put("frame", JSONArray(have + id)))
            return "ok"
        }
        if (!dev.tvremote.home.Target.valid(target) || HomeConfig.isLive(target) || HomeConfig.isFolder(target)) return "target"
        val have = Store.get(c).optJSONObject("pics") ?: JSONObject()
        if (!have.has(target) && have.length() >= HomeConfig.PICS_MAX) return "full"
        val id = dev.tvremote.home.apps.Pics.save(c, jpeg) ?: return "image"
        Store.change(c, JSONObject().put("pics", JSONObject(have.toString()).put(target, id)))
        return "ok"
    }

    fun savePhoto(c: Context, jpeg: ByteArray): String {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return "image"
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        if (o.outWidth < 16 || o.outHeight < 16 || o.outWidth > 8192 || o.outHeight > 8192) return "image"
        val have = Walls.photos(c)
        val id = HomeConfig.PHOTOS.firstOrNull { it !in have } ?: return "full"
        val dst = Walls.file(c, id) ?: return "image"
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".part")
        tmp.writeBytes(jpeg)
        if (!tmp.renameTo(dst)) { tmp.delete(); return "image" }
        val conf = Store.get(c)
        val set = conf.optJSONArray("walls")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val walls = if (conf.optString("wevery", "off") == "off") listOf(id) else set + id
        Store.change(c, JSONObject().put("walls", JSONArray(walls)).put("wall", id).put("photo", System.currentTimeMillis()))
        return "ok"
    }
}
