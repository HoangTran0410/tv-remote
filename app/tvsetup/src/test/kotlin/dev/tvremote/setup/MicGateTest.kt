package dev.tvremote.setup

import org.junit.Test

/** The sound graph shows a voice, not the room: measured levels from an FPT box (Google's recognizer, -2..10). */
class MicGateTest {
    private fun run(g: Gate, from: Long, ms: Long, level: (Long) -> Float): Float {
        var top = 0f
        var t = from
        while (t < from + ms) { top = maxOf(top, g.feed(level(t), t)); t += 20 }
        return top
    }

    @Test fun theRemoteMicAtTheTopIsNoVoice() {                        // 9.8-10 from the press on: no bars
        val g = Gate()
        assert(run(g, 1, 3000) { 9.8f + (it % 3) * 0.1f } < 0.05f)
    }

    @Test fun aFanSettlesWithinASecond() {
        val g = Gate()
        run(g, 1, 300) { -2f }                                        // the press: quiet
        run(g, 301, 1000) { if (it % 40 < 20) -2f else 5f }           // the fan starts
        assert(run(g, 1301, 2000) { if (it % 40 < 20) -2f else 5f } < 0.1f)
    }

    @Test fun aWordStandsUp() {
        val g = Gate()
        run(g, 1, 500) { -2f }
        assert(run(g, 501, 300) { 8f } > 0.6f)
    }
}
