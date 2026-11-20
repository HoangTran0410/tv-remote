package dev.tvremote.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import dev.tvremote.home.wall.WallArt
import dev.tvremote.home.wall.WebSnap
import dev.tvremote.home.wall.Backdrop
import dev.tvremote.home.wall.Walls
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The small pictures behind the theme previews ([ThemePreview]), kept in files/themeshots/ (about 6-15 KB each):
 *  - a ready-made theme's `thumb` (a 330 px https picture from the signed list), fetched once off the main thread, named by id + address
 *    so a new address is a new file;
 *  - a theme the person saved: its background taken when it was saved (a photo shrunk, a video's first frame, the Bing picture kept, the
 *    web wall's last shot or its page as drawn now), named by the theme's id, deleted with the theme. A theme saved before this existed gets
 *    one the next time it is applied, once the wall has drawn. A drawn wall needs none: it is drawn by code.
 */
object ThemeShots {
    const val W = 224; const val H = 126
    @Volatile var live: (() -> Bitmap?)? = null                     // HomeActivity: the web wall's page as it is drawn now (main thread)
    private val main = Handler(Looper.getMainLooper())

    private fun dir(c: Context) = File(c.filesDir, "themeshots").also { it.mkdirs() }
    fun mine(c: Context, id: String) = File(dir(c), "m-$id.jpg")
    fun ready(c: Context, r: HomeConfig.Ready): File? =
        if (r.thumb.isEmpty()) null else File(dir(c), "r-${r.id}-${Integer.toHexString(r.thumb.hashCode())}.jpg").takeIf { it.isFile }

    /** Whether [conf]'s background is one a picture must be taken of (a drawn wall is not). */
    private fun needs(conf: JSONObject) = conf.optString("wall").let { !WallArt.has(it) && (Walls.isPicture(it) || it == HomeConfig.WEB || HomeConfig.isSug(it)) }

    /** The ready-made themes' pictures that are not here yet, fetched (the list of the site is signed, the address https): true when any came. */
    fun fetch(c: Context, list: List<HomeConfig.Ready>): Boolean {
        var got = false
        val keep = HashSet<String>()
        for (r in list) {
            if (r.thumb.isEmpty()) continue
            val f = File(dir(c), "r-${r.id}-${Integer.toHexString(r.thumb.hashCode())}.jpg")
            keep += f.name
            if (f.isFile) continue
            try {
                val k = URL(r.thumb).openConnection() as HttpURLConnection
                k.connectTimeout = 6000; k.readTimeout = 8000; k.instanceFollowRedirects = true
                k.setRequestProperty("User-Agent", "TV-Home/1 (+https://github.com/HoangTran0410/tv-remote)")
                if (k.responseCode != 200) continue
                val bytes = k.inputStream.use { s -> s.readNBytesCapped(300_000) } ?: continue
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                if (save(b, f)) got = true
            } catch (e: Exception) { }
        }
        dir(c).listFiles { x -> x.name.startsWith("r-") && x.name !in keep }?.forEach { it.delete() }
        return got
    }

    private fun java.io.InputStream.readNBytesCapped(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (true) { val n = read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) return null }
        return out.toByteArray()
    }

    /** [b] cropped from its middle to W x H and written as a small JPEG beside the file's name, renamed in one step. */
    private fun save(b: Bitmap, f: File, W: Int = ThemeShots.W, H: Int = ThemeShots.H): Boolean {
        val k = maxOf(W.toFloat() / b.width, H.toFloat() / b.height)
        val s = Bitmap.createScaledBitmap(b, maxOf(W, (b.width * k).toInt()), maxOf(H, (b.height * k).toInt()), true)
        val o = Bitmap.createBitmap(s, (s.width - W) / 2, (s.height - H) / 2, W, H)
        val tmp = File(f.parentFile, f.name + ".part")
        return try {
            tmp.outputStream().use { o.compress(Bitmap.CompressFormat.JPEG, 62, it) }
            tmp.renameTo(f).also { if (!it) tmp.delete() }
        } catch (e: Exception) { tmp.delete(); false }
    }

    /** The picture of [conf]'s background, written for saved theme [id]; false when there is nothing to take (a drawn wall, a page not drawn yet). Off the main thread. */
    fun capture(c: Context, id: String, theme: JSONObject): Boolean {
        val conf = Store.shownCopy(c, theme)                          // a site suggestion: its page's picture
        if (!needs(conf)) return false
        val wall = conf.optString("wall")
        val b: Bitmap? = if (wall == HomeConfig.WEB) {
            WebSnap.file(c, conf.optString("wurl")).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                ?: onMain { live?.invoke() }
        } else Walls.small(c, wall, W, H, false)
        return b != null && save(b, mine(c, id))
    }

    /** The background in use now, for the Customize page's live preview (the web UI's homeprev), as [wall key, file]: twice a theme
     *  picture's size (it fills a phone's width). Taken off the main thread once per wall (a web wall's every 10 min, its shots move
     *  on), so the read after a change has it. Null for a drawn wall. */
    @Volatile private var nowKey = ""
    @Volatile private var nowBusy = false
    fun current(c: Context, saved: JSONObject): Pair<String, File?>? {
        if (!needs(saved)) return null
        val key = saved.optString("wall") + "|" + saved.optString("wurl") + "|" + saved.optString("wfit")   // wfit: the page's key has it when "fitOk" (the saved ids, as the page reads them)
        val conf = Store.shownCopy(c, saved)                          // a site suggestion: its page's picture
        val f = File(dir(c), "now.jpg")
        val fresh = nowKey == key && f.isFile && (conf.optString("wall") != HomeConfig.WEB || dev.tvremote.guard.Age.fresh(f.lastModified(), 600_000))
        if (!fresh && !nowBusy) {
            nowBusy = true
            val app = c.applicationContext
            Thread({
                try {
                    val wall = conf.optString("wall")
                    val b: Bitmap? = if (wall == HomeConfig.WEB) WebSnap.file(app, conf.optString("wurl")).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                        ?: onMain { live?.invoke() } else Walls.small(app, wall, W * 2, H * 2, false)?.let { Backdrop.fitted(it, W * 2, H * 2, conf.optString("wfit")) }
                    if (b != null && save(b, f, W * 2, H * 2)) nowKey = key
                } finally { nowBusy = false }
            }, "themeshot").apply { isDaemon = true }.start()
        }
        return key to f.takeIf { nowKey == key && it.isFile }           // no file yet: the page asks again; a web wall's older shot shows while the next is taken
    }

    private fun <T> onMain(f: () -> T?): T? {
        if (Looper.myLooper() === Looper.getMainLooper()) return f()
        var r: T? = null; val l = CountDownLatch(1)
        main.post { try { r = f() } catch (e: Throwable) { } finally { l.countDown() } }
        l.await(2, TimeUnit.SECONDS)
        return r
    }

    /** What a "theme" change did, for the pictures ([n] is the config after it): a saved theme gets its picture, a deleted one loses it, and a theme applied without one is photographed once its wall has drawn. */
    fun after(c: Context, op: JSONObject, n: JSONObject) {
        val app = c.applicationContext
        val mineList = n.optJSONArray("themes")
        fun themes() = (0 until (mineList?.length() ?: 0)).map { mineList!!.getJSONObject(it) }
        val keep = themes().map { "m-" + it.optString("id") + ".jpg" }.toSet()
        Thread({
            app.filesDir.let { File(it, "themeshots").listFiles { x -> x.name.startsWith("m-") && x.name !in keep }?.forEach { x -> x.delete() } }   // deleted themes, and the cap: only the ones that exist
            op.optString("save").takeIf { it.isNotEmpty() }?.let { name ->
                themes().firstOrNull { it.optString("name").equals(name, ignoreCase = true) }?.let { t ->
                    mine(app, t.optString("id")).delete()
                    capture(app, t.optString("id"), t.getJSONObject("conf"))
                }
            }
        }, "themeshot").apply { isDaemon = true }.start()
        op.optString("apply").takeIf { it.isNotEmpty() }?.let { id ->
            val t = themes().firstOrNull { it.optString("id") == id } ?: return
            val conf = t.getJSONObject("conf")
            if (!needs(conf) || mine(app, id).isFile) return
            for (delay in longArrayOf(10_000, 45_000)) main.postDelayed({
                Thread({
                    val now = Store.get(app)
                    if (!mine(app, id).isFile && now.optString("wall") == conf.optString("wall") && now.optString("wurl") == conf.optString("wurl")) capture(app, id, conf)
                }, "themeshot").apply { isDaemon = true }.start()
            }, delay)
        }
    }
}
