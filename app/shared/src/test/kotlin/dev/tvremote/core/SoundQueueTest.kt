package dev.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import dev.tvremote.core.picture.SoundQueue

/**
 * SoundQueue against made-up networks: the TV sends a 20 ms Opus packet every 20 ms; each arrives late by a base delay,
 * random jitter and now and then a spike, in order as TCP hands them over (a late one holds up the ones behind it). The
 * phone plays 48 frames a millisecond whenever it has some. Counted: the gaps a person hears, how much was silent, and how
 * far behind the TV the sound plays.
 */
class SoundQueueTest {
    private class Heard(val gaps: Int, val silentPct: Double, val lateMs: Int, val droppedPct: Double)

    private fun listen(base: Int, jitter: Double, spikePct: Double, spikeMs: Int, minutes: Int = 5, far: Boolean = false, headStepMs: Int = 0, real: List<Int>? = null, start: Int = 0, even: Boolean = false): Heard {
        val rnd = Random(7)
        val first = if (even) 48000 / 2 else if (far) 48000 * 4 / 10 else 48000 * 15 / 100
        val q = SoundQueue(first, 48000, maxOf(first, start))
        val n = 960
        var arrived = 0.0
        var queued = 0.0                                              // frames in the track
        var held = 0
        var clock = 0.0
        var playedAll = 0.0                                           // frames the track really played
        var playing = false
        var gaps = 0; var silent = 0.0; var dropped = 0; var late = 0.0; var lateN = 0
        val packets = real?.size ?: (minutes * 60 * 50)
        val skip = if (real != null) 0 else 30 * 50                   // the first half minute: it is still learning
        for (i in 0 until packets) {
            val sent = i * 20.0
            val delay = if (real != null) real[i].toDouble() else base + (if (jitter > 0) -jitter * kotlin.math.ln(1 - rnd.nextDouble()) else 0.0) +
                (if (rnd.nextDouble() * 100 < spikePct) spikeMs.toDouble() else 0.0)
            arrived = maxOf(arrived, sent + delay)
            val dt = arrived - clock
            if (playing) {
                val left = queued - dt * 48
                playedAll += minOf(queued, dt * 48)
                if (left <= 0) {
                    if (i >= skip) { gaps++; silent += -left / 48 }
                    queued = 0.0; playing = false
                } else queued = left
            }
            clock = arrived
            val step = headStepMs * 48.0                              // a phone whose playback head moves in steps
            val seen = if (step > 0) queued + (playedAll - Math.floor(playedAll / step) * step) else queued
            when (q.next(n, seen.toLong(), arrived.toLong())) {
                SoundQueue.Do.PLAY -> { queued += held + n; held = 0; playing = true }
                SoundQueue.Do.HOLD -> held += n
                SoundQueue.Do.DROP -> if (i >= skip) dropped++
            }
            if (i >= skip && playing) { late += (arrived - sent) + queued / 48; lateN++ }
        }
        val counted = (packets - skip) * 20.0
        return Heard(gaps, silent * 100 / counted, (late / maxOf(lateN, 1)).toInt(), dropped * 100.0 / (packets - skip))
    }

    private fun show(what: String, h: Heard) =
        println("$what: ${h.gaps} gaps, %.2f%% silent, %.2f%% dropped, %d ms behind the TV".format(h.silentPct, h.droppedPct, h.lateMs))

    @Test fun home() {
        val h = listen(base = 5, jitter = 3.0, spikePct = 0.0, spikeMs = 0).also { show("home", it) }
        assertEquals(0, h.gaps)
        assertTrue(h.lateMs < 250)
    }

    @Test fun busyTvAtHome() {                                        // the FPT Play box: up to 0.25 s late at times
        val h = listen(base = 5, jitter = 15.0, spikePct = 1.0, spikeMs = 200).also { show("busy TV", it) }
        assertTrue(h.gaps <= 5)
        assertTrue(h.lateMs < 600)
    }

    @Test fun mobile() {
        val h = listen(base = 40, jitter = 30.0, spikePct = 1.0, spikeMs = 300, far = true).also { show("4G", it) }
        assertTrue(h.gaps <= 5)
        assertTrue(h.silentPct < 0.5)
        assertTrue(h.lateMs < 1100)
    }

    @Test fun badMobile() {
        val h = listen(base = 60, jitter = 60.0, spikePct = 3.0, spikeMs = 600, far = true).also { show("bad 4G", it) }
        assertTrue(h.silentPct < 2.0)
    }

    @Test fun phoneWithACoarsePlaybackHead() {                        // a Xiaomi: the head moves 200 ms at a time
        val h = listen(base = 5, jitter = 3.0, spikePct = 0.0, spikeMs = 0, headStepMs = 200).also { show("coarse head", it) }
        assertEquals(0, h.gaps)
        assertTrue(h.droppedPct < 1.0)                                // dropped one after another, the song sounded sped up
    }

    @Test fun tclAtHome() {                                           // a TCL (2 GB, Android 11) playing YouTube: a minute of how late each packet came, measured
        val late = javaClass.getResource("/sound_tcl.txt")!!.readText().trim().split(" ").map { it.toInt() }
        val h = listen(base = 0, jitter = 0.0, spikePct = 0.0, spikeMs = 0, real = late).also { show("TCL", it) }
        assertTrue(h.gaps <= 3)                                       // while it learns how late this TV can be
        assertTrue(h.droppedPct < 0.2)                                // was 12 packets a minute, a few seconds apart: a song played fast
    }

    @Test fun tclTheNextTime() {                                      // what the last session learnt, from the start: not again
        val late = javaClass.getResource("/sound_tcl.txt")!!.readText().trim().split(" ").map { it.toInt() }
        val learnt = SoundQueue(48000 * 15 / 100, 48000).also { q -> late.forEachIndexed { i, d -> q.next(960, 0, i * 20L + d) } }.target
        val first = listen(base = 0, jitter = 0.0, spikePct = 0.0, spikeMs = 0, real = late).also { show("TCL, first time", it) }
        val next = listen(base = 0, jitter = 0.0, spikePct = 0.0, spikeMs = 0, real = late, start = 48000 * 6 / 10).also { show("TCL, learnt", it) }
        val even = listen(base = 0, jitter = 0.0, spikePct = 0.0, spikeMs = 0, real = late, even = true).also { show("TCL, Even", it) }
        assertTrue(learnt >= 48000 * 15 / 100)
        assertTrue(next.gaps < maxOf(1, first.gaps)); assertTrue(even.gaps < maxOf(1, first.gaps))
    }

    @Test fun gapsAreCountedForTheLog() {
        val q = SoundQueue(4800, 48000)
        assertEquals(null, q.takeGaps())
        var t = 0L
        repeat(5) { q.next(960, 0, t); t += 20 }                      // the first packets are held until [target] is here
        repeat(60) { q.next(960, 960, t); t += 20 }                  // steady: the track always has a packet queued
        q.next(960, 0, t + 2000)                                     // the link stalled 2 s: the track ran dry
        val said = q.takeGaps()
        assertTrue(said, said != null && said.startsWith("1 gaps") && said.contains("longest 19"))
        assertEquals(null, q.takeGaps())
    }
}
