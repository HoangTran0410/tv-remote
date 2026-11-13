package dev.tvremote.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoShowTest {
    @Test fun picturesByNameAndNeverHiddenOnes() {
        assertTrue(PhotoShow.isPicture("/sdcard/DCIM/Camera/IMG_1.JPG"))
        assertTrue(PhotoShow.isPicture("a.webp"))
        assertFalse(PhotoShow.isPicture("/sdcard/Pictures/.thumbnails/1.jpg"))
        assertFalse(PhotoShow.isPicture("/sdcard/Pictures/.trashed-1.jpg"))
        assertFalse(PhotoShow.isPicture("movie.mp4"))
        assertFalse(PhotoShow.isPicture("noext"))
    }

    @Test fun orderShowsEveryPictureOnceAndRepeatsForASeed() {
        val a = PhotoShow.order(50, 7)
        assertEquals((0 until 50).toList(), a.sorted())
        assertEquals(a, PhotoShow.order(50, 7))
        assertTrue(PhotoShow.order(0, 1).isEmpty())
    }

    @Test fun rootsPerMode() {
        assertEquals(listOf("/sdcard/DCIM"), PhotoShow.roots("dcim", listOf("emulated")))
        assertEquals(listOf("/storage/1A2B-3C4D"), PhotoShow.roots("usb", listOf("emulated", "self", "1A2B-3C4D", ".x")))
        assertTrue(PhotoShow.roots("", listOf("1A2B-3C4D")).isEmpty())
    }
}
