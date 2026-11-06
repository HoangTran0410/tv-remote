package dev.tvremote.home.widgets

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.apps.Pics
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The photo frame: which photo comes next, when the timer may run, and bad files passed over without a crash, small pictures only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)                              // real decoding
class FrameShowTest {
    private val c get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun nextGoesRoundAndNoneIsMinusOne() {
        assertEquals(-1, FrameShow.next(0, 0))
        assertEquals(0, FrameShow.next(-1, 3))
        assertEquals(1, FrameShow.next(0, 3)); assertEquals(0, FrameShow.next(2, 3))
        assertEquals(0, FrameShow.next(0, 1))                                // one photo: it stays
    }

    @Test fun timerRunsOnlyWithTwoPhotosOnAShownResumedScreen() {
        assertTrue(FrameShow.runs(2, attached = true, visible = true, resumed = true))
        assertFalse(FrameShow.runs(1, true, true, true))                     // one photo is a still picture
        assertFalse(FrameShow.runs(0, true, true, true))
        assertFalse(FrameShow.runs(5, false, true, true))                    // widget off / not on screen
        assertFalse(FrameShow.runs(5, true, false, true))                    // screensaver over Home, screen off
        assertFalse(FrameShow.runs(5, true, true, false))                    // onPause
    }

    @Test fun intervalFromTheOption() {
        assertEquals(30_000L, FrameShow.everyMs("s30")); assertEquals(10_000L, FrameShow.everyMs("s10"))
        assertEquals(900_000L, FrameShow.everyMs("m15"))
        assertEquals(30_000L, FrameShow.everyMs("")); assertEquals(30_000L, FrameShow.everyMs("bogus"))
    }

    @Test fun keepStaysOnTheSamePhoto() {
        assertEquals(2, FrameShow.keep(listOf("a", "b"), 1, listOf("x", "y", "b")))
        assertEquals(0, FrameShow.keep(listOf("a", "b"), 1, listOf("a")))   // the one shown was removed: the first
        assertEquals(-1, FrameShow.keep(listOf("a"), 0, emptyList()))
        assertEquals(0, FrameShow.keep(emptyList(), -1, listOf("a")))
    }

    @Test fun firstGoodSkipsBadOnesAndGoesRound() {
        val ok = setOf("c", "a")
        assertEquals(2 to "c", FrameShow.firstGood(listOf("a", "b", "c"), 1) { it.takeIf { x -> x in ok } })
        assertEquals(0 to "a", FrameShow.firstGood(listOf("a", "b"), 1) { it.takeIf { x -> x in ok } })
        assertNull(FrameShow.firstGood(listOf("b", "d"), 0) { it.takeIf { x -> x in ok } })
        assertNull(FrameShow.firstGood(emptyList<String>(), 0) { it })
        var reads = 0
        FrameShow.firstGood(listOf("b", "d", "e"), 7) { reads++; null as String? }
        assertEquals("each tried once, no loop", 3, reads)
    }

    private fun jpeg(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565); b.eraseColor(0xFF884422.toInt())
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun put(id: String, bytes: ByteArray) { val f = Pics.file(c, id); f.parentFile!!.mkdirs(); f.writeBytes(bytes) }

    @Test fun badFilesArePassedOverWithoutACrash() {
        val good = jpeg(480, 270)
        put("00000001", good.copyOf(20))                                     // cut short
        put("00000002", "not a picture at all\n".repeat(40).toByteArray())
        put("00000003", ByteArray(0))
        val huge = good.copyOf().also { o ->                                 // header says 30000 x 30000
            for (i in 0 until o.size - 9) if (o[i] == 0xFF.toByte() && (o[i + 1] == 0xC0.toByte() || o[i + 1] == 0xC2.toByte())) {
                o[i + 5] = (30000 shr 8).toByte(); o[i + 6] = 30000.toByte(); o[i + 7] = (30000 shr 8).toByte(); o[i + 8] = 30000.toByte(); break
            }
        }
        put("00000004", huge)
        put("00000005", good + ByteArray(HomeConfig.PIC_BYTES))             // over the size limit on disk
        put("00000006", good)
        val ids = listOf("00000001", "00000002", "00000003", "00000004", "00000005", "nothere9", "../../x", "00000006")
        for (id in ids.dropLast(1)) assertNull(id, Pics.bitmap(c, id, 400, 225))
        val got = FrameShow.firstGood(ids, 0) { Pics.bitmap(c, it, 400, 225) }
        assertNotNull(got); assertEquals(7, got!!.first)
        assertNull(FrameShow.firstGood(ids.dropLast(1), 3) { Pics.bitmap(c, it, 400, 225) })   // none readable: null, the hint shows
    }

    @Test fun picturesAreReadNoBiggerThanTheWidget() {
        put("0000000a", jpeg(480, 270))
        val small = Pics.bitmap(c, "0000000a", 200, 100)!!
        assertTrue("${small.width}x${small.height}", small.width <= 480 && small.width >= 177 && small.height >= 100)   // covers the box
        val big = Pics.bitmap(c, "0000000a", 4000, 4000)!!
        assertTrue("never over the stored size", big.width <= HomeConfig.PIC_W && big.height <= HomeConfig.PIC_H)
        assertEquals(Bitmap.Config.RGB_565, big.config)
        put("0000000b", jpeg(3000, 3000))                                    // a file put there by hand, bigger than Pics.save makes
        val sq = Pics.bitmap(c, "0000000b", 400, 225)!!
        assertTrue("${sq.width}x${sq.height}", sq.byteCount <= HomeConfig.PIC_W * HomeConfig.PIC_H * 2)
    }
}
