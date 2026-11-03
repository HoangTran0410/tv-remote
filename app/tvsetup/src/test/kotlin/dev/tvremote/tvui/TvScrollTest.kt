package dev.tvremote.tvui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvScrollTest {
    // a 500 px window with 10 px padding over 2000 px of content: scrolls 0..1520
    private fun to(a: Int, b: Int, now: Int = 0) = centered(a, b, now, 500, 10, 1520)?.let { it + now }

    @Test fun aRowMidListLandsInTheMiddle() = assertEquals(1000 + 50 - 10 - 250, to(1000, 1100))
    @Test fun theFirstRowsStayAtTheTop() = assertEquals(0, to(20, 80))
    @Test fun theLastRowsStayAtTheBottom() = assertEquals(1520, to(1950, 2000))
    @Test fun itIsAChangeFromWhereItIs() = assertEquals(790 - 300, centered(1000, 1100, 300, 500, 10, 1520))
    @Test fun aRowTallerThanTheViewKeepsAndroidsWay() = assertNull(to(100, 700))
    @Test fun notLaidOutYet() = assertNull(centered(0, 10, 0, 0, 0, 0))
}
