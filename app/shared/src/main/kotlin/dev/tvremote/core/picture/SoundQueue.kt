package dev.tvremote.core.picture
/**
 * When to play each piece of the TV's sound (AudioOut.kt), apart from Android so it can be tried against made-up networks
 * (SoundQueueTest). Frames are at 48 kHz. [target] frames are kept queued: [base] at first, half as many again each time the
 * sound ran dry (4G, Tailscale through a relay: packets come in bursts), up to [max], back down after two calm minutes; it
 * starts at [start], what the last session with this TV ended on, so a TV that sends in bursts is not learnt again each time.
 * Once it ran dry nothing plays until [target] frames are here again: a stall is one clean gap, not the crackle of every
 * packet played the moment it arrives. Kept close to live: well past [target], the excess is skipped in one go, seldom, so
 * a skip is never heard as a faster song.
 */
class SoundQueue(private val base: Int, private val max: Int, start: Int = base) {
    enum class Do { PLAY, HOLD, DROP }

    var target = start.coerceIn(base, max); private set
    private var heldFrames = 0
    private var calm = 0L                                             // since when it has not run dry
    private var played = false
    private var lastDrop = 0L
    private var started = 0L                                          // when it first played
    private var cutting = false                                       // dropping back to [target]
    private var lastAt = 0L; private var lastBehind = 0L              // when the last packet came, and what the track then had
    /** Underruns since [takeGaps] last asked: how many times the track ran silent, their total and longest length in ms. */
    private var gaps = 0; private var gapMs = 0L; private var gapMax = 0L

    /** "3 gaps, 4200 ms in all, longest 2100 ms" for the log (and zeroes the count), null when there was none. */
    fun takeGaps(): String? {
        if (gaps == 0) return null
        return "$gaps gaps, $gapMs ms in all, longest $gapMax ms".also { gaps = 0; gapMs = 0; gapMax = 0 }
    }

    /** The track's buffer for this [target], two packets more. Android starts a track again (after a long stall) only once
     *  that is full: so [target] plus what arrives meanwhile fills it. Written while it is not full yet, even if the packet
     *  goes past it (the write then waits for room, under 20 ms), or it might never fill. */
    val room get() = target + RATE / 25

    /** [n] frames just decoded, [behind] frames waiting in the track, [now] in ms. PLAY: write what was held, then these. */
    fun next(n: Int, behind: Long, now: Long): Do {
        val wait = now - lastAt - lastBehind * 1000 / RATE            // how long the track has been silent, in ms
        lastAt = now; lastBehind = maxOf(0L, behind) + n
        if (heldFrames == 0 && behind <= 0) {                         // ran dry (or not started yet)
            // twice as much, or enough for a wait as long as this one; not in its first 3 s, while the decoder and the
            // connection warm up (it went up to 1 s there at once, on an emulator)
            if (played) { val g = maxOf(0L, wait); gaps++; gapMs += g; gapMax = maxOf(gapMax, g) }
            if (played && now - started > 3000) target = minOf(max, maxOf(target * 2, base + (wait * RATE / 1000).toInt()))
            calm = now
        } else if (heldFrames == 0) {
            if (target > base && now - calm > 120000) { target = maxOf(base, target * 4 / 5); calm = now }
            // Too far behind: what is over [target] is cut in one go, the packets that come next dropped back to back: one
            // skip. A packet now and then over seconds (as it was) sounded like a song played fast and stuttering, on a
            // TCL whose sound comes in bursts after a stall. Seldom: only well past [target], and not again within 10 s.
            if (cutting || behind >= room + maxOf(RATE / 5, target / 2) && now - lastDrop >= 10000) {
                cutting = behind > target; lastDrop = now
                if (cutting) return Do.DROP
            }
            return Do.PLAY
        }
        heldFrames += n
        if (heldFrames < target) return Do.HOLD
        heldFrames = 0; if (!played) started = now; played = true
        return Do.PLAY
    }

    companion object {
        const val RATE = 48000
    }
}
