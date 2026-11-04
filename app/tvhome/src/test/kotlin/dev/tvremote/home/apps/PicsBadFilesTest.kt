package dev.tvremote.home.apps

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import dev.tvremote.home.HomeConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A bad "own picture" must never crash or bloat TV Home: load answers null (the tile keeps the app's own picture) or a bitmap in budget. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)                              // real decoding; the default shadows accept any bytes
class PicsBadFilesTest {
    private val c get() = ApplicationProvider.getApplicationContext<Application>()
    private val budget = HomeConfig.PIC_W * HomeConfig.PIC_H * 2

    private fun jpeg(w: Int, h: Int, noisy: Boolean = false): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        if (noisy) {
            val r = Random(1); val px = IntArray(w)
            for (y in 0 until h) { for (x in 0 until w) px[x] = r.nextInt() or 0xFF000000.toInt(); b.setPixels(px, 0, w, 0, y, w, 1) }
        } else b.eraseColor(0xFF3366CC.toInt())
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    /** Puts [bytes] where a tile's picture lives and loads it. */
    private fun place(bytes: ByteArray, id: String = "0badf00d"): Any? {
        val f = Pics.file(c, id); f.parentFile!!.mkdirs(); f.writeBytes(bytes)
        return Pics.load(c, id)
    }

    private fun inBudget(d: Any?) {
        val bm = (d as BitmapDrawable).bitmap
        assertTrue("${bm.width}x${bm.height}", bm.width <= HomeConfig.PIC_W && bm.height <= HomeConfig.PIC_H)
        assertTrue(bm.byteCount <= budget)
    }

    private fun patchSize(j: ByteArray, w: Int, h: Int): ByteArray {         // the SOF marker holds height then width
        val o = j.copyOf()
        for (i in 0 until o.size - 9) if (o[i] == 0xFF.toByte() && (o[i + 1] == 0xC0.toByte() || o[i + 1] == 0xC2.toByte())) {
            o[i + 5] = (h shr 8).toByte(); o[i + 6] = h.toByte(); o[i + 7] = (w shr 8).toByte(); o[i + 8] = w.toByte(); return o
        }
        error("no SOF")
    }

    @Test fun goodPictureLoadsInBudget() { inBudget(place(jpeg(1920, 1080))) }

    @Test fun truncatedJpegNeverCrashes() {
        val j = jpeg(1000, 600, noisy = true)
        val d = place(j.copyOf(j.size / 3))
        if (d != null) inBudget(d)
        assertNull(place(j.copyOf(20)))                                    // header cut: no picture
    }

    @Test fun nonImageBytesGiveNull() {
        assertNull(place("hello, not a picture\n".repeat(50).toByteArray()))
        assertNull(place(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(5000) { it.toByte() }))
        assertNull(Pics.save(c, ByteArray(5000) { 7 }))
    }

    @Test fun zeroByteFileGivesNull() {
        assertNull(place(ByteArray(0)))
        assertNull(Pics.save(c, ByteArray(0)))
    }

    @Test fun svgAndHtmlGiveNull() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="480" height="270"><rect width="9" height="9"/></svg>""".toByteArray()
        assertNull(place(svg))
        assertNull(place("<html><script>alert(1)</script></html>".toByteArray()))
        assertNull(Pics.save(c, svg))
        assertNull(Pics.save(c, "<html></html>".toByteArray()))
        assertNull(Pics.save(c, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + svg))   // a JPEG signature in front changes nothing
    }

    @Test fun absurdDimensionsAreRefusedBeforeDecoding() {
        val big = patchSize(jpeg(64, 64), 20000, 20000)                    // header says 20000 x 20000 (800 MB as RGB_565)
        assertNull(place(big))
        assertNull(Pics.save(c, big))
        assertNull(place(patchSize(jpeg(64, 64), 8, 8)))                   // too small too
    }

    @Test fun largeValidImageIsShrunk() {
        val j = jpeg(8000, 4000)
        inBudget(place(j))
        val id = Pics.save(c, j)
        assertNotNull(id)
        assertTrue(Pics.file(c, id!!).length() <= HomeConfig.PIC_BYTES)
        inBudget(Pics.load(c, id))
    }

    @Test fun overSizeFileIsNotUsedAndSaveReencodes() {
        val noisy = jpeg(2000, 1200, noisy = true)
        assertTrue("fixture should be big: ${noisy.size}", noisy.size > 300_000)
        val id = Pics.save(c, noisy)
        if (id != null) { assertTrue(Pics.file(c, id).length() <= HomeConfig.PIC_BYTES); inBudget(Pics.load(c, id)) }
        assertNull(place(noisy + ByteArray(1_800_000)))                    // already on disk over the limit: skipped without decoding
    }

    @Test fun badIdsAreRefused() {
        assertNull(Pics.load(c, "../../etc/passwd"))
        assertNull(Pics.load(c, "nothere1"))
        assertEquals(false, File(Pics.dir(c), "nothere1.jpg").exists())
    }
}
