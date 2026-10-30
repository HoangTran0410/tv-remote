package dev.tvremote.home.ui

/**
 * Burn-in care ("Move a little every minute"): one step a minute round the edge of a square, [SIDE] dp across, so Home never
 * sits on the same pixels for long and never strays more than 3 dp from its place (6 dp across at most). A step is 1 dp: too small to
 * see as a jump, so there is no animation (nothing drawn per frame). Stateless: the step comes from the clock, so a restart
 * carries on where it was, and a clock that jumps only lands on another point of the same square.
 */
object PixelShift {
    const val SIDE = 6                                             // dp, the whole span
    private const val R = SIDE / 2
    private const val PERIMETER = 4 * SIDE                          // 24 steps = 24 minutes a round

    /** The offset (dp, x then y) for the minute that holds [nowMs]. */
    fun at(nowMs: Long): Pair<Int, Int> {
        val i = Math.floorMod(Math.floorDiv(nowMs, 60_000L), PERIMETER.toLong()).toInt()
        val side = i / SIDE; val k = i % SIDE
        return when (side) {
            0 -> (-R + k) to -R                                    // along the top, left to right
            1 -> R to (-R + k)                                     // down the right edge
            2 -> (R - k) to R                                      // along the bottom, back
            else -> -R to (R - k)                                  // up the left edge
        }
    }

    fun untilNext(nowMs: Long = System.currentTimeMillis()) = 60_000L - Math.floorMod(nowMs, 60_000L) + 50
}
