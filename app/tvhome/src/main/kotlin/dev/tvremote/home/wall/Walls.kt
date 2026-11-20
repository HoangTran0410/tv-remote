package dev.tvremote.home.wall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import dev.tvremote.home.HomeConfig
import java.io.File

/** Which background is a file, which is drawn, and what can be shown right now. Everything here may run off the main thread. */
object Walls {
    /** The picture file of [id]: one of the person's photos, or the day's Bing picture; null for the drawn ones. */
    fun file(c: Context, id: String): File? = when {
        HomeConfig.isPhoto(id) -> File(c.getExternalFilesDir(null), HomeConfig.photoName(id))
        id == HomeConfig.BING -> Online.file(c)
        HomeConfig.isVideo(id) -> Videos.poster(c, id)             // the video's first frame: the picture everything but the player uses
        else -> null
    }

    fun isPicture(id: String) = HomeConfig.isPhoto(id) || id == HomeConfig.BING || HomeConfig.isVideo(id)

    /** The ones of the person's photos that are on this TV, in order. */
    fun photos(c: Context): List<String> = HomeConfig.PHOTOS.filter { file(c, it)?.isFile == true }

    /** Whether [id] can be shown now: drawn and plain ones always, a photo while its file is there, Bing once a picture has come. */
    fun usable(c: Context, id: String) = (file(c, id)?.isFile ?: true) && (!HomeConfig.isVideo(id) || Videos.file(c, id).isFile)
        && (!HomeConfig.isSug(id) || dev.tvremote.home.Suggest.wall(c, id) != null)

    /** The picture of [id] for a screen of [w] x [h] (null for a drawn wall, or when it cannot be read). */
    fun bitmap(c: Context, id: String, w: Int, h: Int): Bitmap? = file(c, id)?.let { Wall.load(it, w, h) }

    /** A small picture of [id], [w] x [h], for the TV's panel and the blurred copy: a scene is drawn at that size, a photo read shrunk. */
    fun small(c: Context, id: String, w: Int, h: Int, veil: Boolean = true): Bitmap? {
        if (WallArt.has(id)) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            WallArt.paint(cv, w, h, id)
            if (veil) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Backdrop.shade(WallArt.shade(id))).apply { setBounds(0, 0, w, h); draw(cv) }
            return b
        }
        val f = file(c, id)?.takeIf { it.isFile } ?: return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val read = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = Wall.sample(o.outWidth, o.outHeight, w, h) })
                ?: return null
            val k = maxOf(w.toFloat() / read.width, h.toFloat() / read.height)
            val out = Bitmap.createScaledBitmap(read, maxOf(w, (read.width * k).toInt()), maxOf(h, (read.height * k).toInt()), true)
            if (out !== read) read.recycle()
            out
        } catch (e: Throwable) { null }
    }

    /** The blurred copy shown behind the dock band: a photo shrunk to 1/16 ([Wall.soft]), a scene drawn that small. Never the gradients. */
    fun soft(c: Context, id: String, full: Bitmap?, w: Int, h: Int): Bitmap? =
        if (full != null) Wall.soft(full) else if (WallArt.has(id)) small(c, id, maxOf(1, w / 16), maxOf(1, h / 16), false) else null
}
