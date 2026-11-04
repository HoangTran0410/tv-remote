package dev.tvremote.games

import org.junit.Assert.assertEquals
import org.junit.Test

/** The screen stays on for a while after a phone's press, then the TV's own sleep timer counts again. */
class AwakeTest {
    private val hold = Awake.HOLD_MS

    @Test fun noPressNoHold() = assertEquals(0L, Awake.left(5_000_000, 0))
    @Test fun justPressedHoldsAll() = assertEquals(hold, Awake.left(5_000_000, 5_000_000))
    @Test fun countsDown() = assertEquals(hold - 60_000, Awake.left(5_060_000, 5_000_000))
    @Test fun endsAfterHold() = assertEquals(0L, Awake.left(5_000_000 + hold + 1, 5_000_000))
}
