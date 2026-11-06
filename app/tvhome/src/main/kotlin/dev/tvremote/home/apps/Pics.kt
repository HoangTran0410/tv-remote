package dev.tvremote.home.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.wall.Wall
import java.io.File

/**
 * The person's own picture for an app's tile (HomeConfig "pics"): a small JPEG in TV Home's files folder, drawn in place of the app's
 * banner or icon wherever [App.load] makes one. Kept small for weak boxes: at most [HomeConfig.PIC_W] x [HomeConfig.PIC_H] in RGB_565
 * (253 KB in memory), read with inSampleSize, a file over [HomeConfig.PIC_BYTES] never used.
 */
object Pics {
    /** target -> picture id, as the settings say (HomeActivity keeps it in step, like [Apps.names]). */
    @Volatile var ids: Map<String, String> = emptyMap()
    /** The photo frame widget's photos (HomeConfig "frame"): the same kind of file, kept by the same sweep. */
    @Volatile var frame: List<String> = emptyList()

    private const val GRACE_MS = 10 * 60_000L                         // a client puts the file first and the setting after: a young file is never swept

    fun dir(c: Context) = File(c.getExternalFilesDir(null), "pics")
    fun file(c: Context, id: String) = File(dir(c), "$id.jpg")

    /** The size to draw a [w] x [h] picture at so it fits the box and is never enlarged. */
    fun fit(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1 to 1
        val k = minOf(1f, minOf(HomeConfig.PIC_W.toFloat() / w, HomeConfig.PIC_H.toFloat() / h))
        return maxOf(1, (w * k).toInt()) to maxOf(1, (h * k).toInt())
    }

    private fun sane(w: Int, h: Int) = w in 16..8192 && h in 16..8192

    /** The own picture of [pkg], or null (none set, the file gone or too big, not a picture). Off the main thread. */
    fun of(c: Context, pkg: String): Drawable? = ids[pkg]?.let { load(c, it) }

    fun load(c: Context, id: String): Drawable? = bitmap(c, id, HomeConfig.PIC_W, HomeConfig.PIC_H)?.let { BitmapDrawable(c.resources, it) }

    /**
     * Picture [id] no bigger than [bw] x [bh] (and never over [HomeConfig.PIC_W] x [HomeConfig.PIC_H]): read with inSampleSize, RGB_565;
     * null for a missing, oversized, corrupt or not-a-picture file. Off the main thread.
     */
    fun bitmap(c: Context, id: String, bw: Int, bh: Int): Bitmap? {
        if (!HomeConfig.PIC_ID.matches(id)) return null
        val f = file(c, id)
        if (!f.isFile || f.length() > HomeConfig.PIC_BYTES) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (!sane(o.outWidth, o.outHeight)) return null
            val (fw, fh) = fit(o.outWidth, o.outHeight)
            val k = minOf(1f, maxOf(bw.toFloat() / fw, bh.toFloat() / fh))   // covers the box (a frame crops to fill), never over the stored size
            val w = maxOf(1, (fw * k).toInt()); val h = maxOf(1, (fh * k).toInt())
            val read = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
                inSampleSize = Wall.sample(o.outWidth, o.outHeight, w, h); inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: return null
            if (read.width > w || read.height > h) Bitmap.createScaledBitmap(read, w, h, true).also { if (it !== read) read.recycle() } else read
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
    }

    /**
     * Takes a JPEG a client sent into a file of its own and answers its id; null when it is not a picture. Whatever its size it is
     * shrunk to the box and written under [HomeConfig.PIC_BYTES] (quality steps down), so no tile can cost more than the budget.
     */
    fun save(c: Context, jpeg: ByteArray): String? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            if (!sane(o.outWidth, o.outHeight)) return null
            val (w, h) = fit(o.outWidth, o.outHeight)
            val read = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
                inSampleSize = Wall.sample(o.outWidth, o.outHeight, w, h); inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: return null
            val b = if (read.width > w || read.height > h) Bitmap.createScaledBitmap(read, w, h, true).also { if (it !== read) read.recycle() } else read
            val id = String.format("%08x", java.security.SecureRandom().nextInt())
            val dst = file(c, id)
            dst.parentFile?.mkdirs()
            var q = 85
            val bytes = java.io.ByteArrayOutputStream()
            do { bytes.reset(); b.compress(Bitmap.CompressFormat.JPEG, q, bytes); q -= 15 } while (bytes.size() > HomeConfig.PIC_BYTES && q > 20)
            b.recycle()
            if (bytes.size() > HomeConfig.PIC_BYTES) return null
            val tmp = File(dst.parentFile, dst.name + ".part")
            tmp.writeBytes(bytes.toByteArray())
            if (!tmp.renameTo(dst)) { tmp.delete(); null } else id
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
    }

    /** The files no setting names any more (a picture replaced or put back), unless they are new. Off the main thread. */
    fun sweep(c: Context, keep: Collection<String>) {
        val now = System.currentTimeMillis()
        for (f in dir(c).listFiles().orEmpty()) {
            val id = f.name.removeSuffix(".jpg").removeSuffix(".part")
            if (id !in keep && now - f.lastModified() > GRACE_MS) f.delete()
        }
    }
}
