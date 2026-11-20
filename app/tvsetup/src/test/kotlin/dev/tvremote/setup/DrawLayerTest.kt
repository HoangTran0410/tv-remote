package dev.tvremote.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawLayerTest {
    @Test fun picturesPastTheBudgetDropTheOldest() {
        assertEquals(0, drawPicsOver(listOf(10, 10), 30))
        assertEquals(1, drawPicsOver(listOf(10, 10, 15), 30))
        assertEquals(2, drawPicsOver(listOf(20, 20, 20), 30))
        assertEquals(1, drawPicsOver(listOf(5, 50), 30))              // the newest stays even alone over the budget
        assertEquals(0, drawPicsOver(listOf(50), 30))
    }

    @Test fun bothSidesBoundTheDecode() {
        assertEquals(1, drawSample(1280, 720, 1920))
        assertEquals(2, drawSample(3840, 2160, 1920))
        assertEquals(4, drawSample(1000, 7000, 1920))                 // a tall picture: the width alone said 1
    }
}
