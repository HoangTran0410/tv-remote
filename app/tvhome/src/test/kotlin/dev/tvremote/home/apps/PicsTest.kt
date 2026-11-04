package dev.tvremote.home.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PicsTest {
    @Test fun aPictureFitsTheBoxAndIsNeverEnlarged() {
        assertEquals(480 to 270, Pics.fit(480, 270))
        assertEquals(480 to 270, Pics.fit(1920, 1080))
        assertEquals(480 to 270, Pics.fit(4000, 2250))
        assertEquals(200 to 100, Pics.fit(200, 100))                      // small stays small
        assertEquals(135 to 270, Pics.fit(1000, 2000))                    // tall: the height limits
        val (w, h) = Pics.fit(8192, 16)
        assertTrue(w <= 480 && h >= 1)
        assertEquals(1 to 1, Pics.fit(0, 10))
    }

    @Test fun memoryStaysUnderTheBudget() {
        val (w, h) = Pics.fit(6000, 4000)
        assertTrue("RGB_565 bytes", w * h * 2 <= 300_000)
    }
}
