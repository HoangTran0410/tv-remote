package dev.tvremote.core.picture

/**
 * The phone's own picture, decided apart from Android so it can be tried against a made-up clock (LiveDecodeTest): what the
 * decoder is asked for ([tuning]), which packets are worth feeding ([LiveGate]), which decoded frames are worth drawing, and
 * the numbers for the debug log ([LatencyStats]). [SurfaceScreen] (TvAndroid.kt) only carries these out. All times are ms.
 */
object LiveDecode {
    /** The MediaFormat keys (their string names, so this runs in plain JVM tests) for the lowest delay the decoder offers. */
    fun tuning(sdk: Int, lowLatencyOk: Boolean, vendor: Boolean): Map<String, Int> {
        val m = LinkedHashMap<String, Int>()
        if (sdk >= 30 && lowLatencyOk) m["low-latency"] = 1          // FEATURE_LowLatency: output as soon as a frame is decoded
        if (sdk >= 23) {
            m["priority"] = 0                                         // 0 = realtime; the default may let other work go first
            m["operating-rate"] = Short.MAX_VALUE.toInt()             // "as fast as you can": stops a decoder pacing itself
        }
        if (vendor) {                                                 // other chips ignore these (retry without if configure refuses)
            m["vendor.qti-ext-dec-low-latency.enable"] = 1
            m["vendor.rtc-ext-dec-low-latency.enable"] = 1            // Exynos
        }
        return m
    }

    /** Annex-B H.264: true if the first picture NAL in [d] is an IDR slice (type 5). scrcpy marks key frames, the Screen is not told. */
    fun isKey(d: ByteArray): Boolean {
        var i = 0
        while (i + 3 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) {
                when (d[i + 3].toInt() and 0x1f) { 5 -> return true; 1 -> return false }
                i += 3
            } else i++
        }
        return false
    }
}

/**
 * Between the socket and the decoder. Every fed packet is remembered with the time it went in; when its frame comes out the
 * age is known. A frame that is old and has newer ones right behind it is decoded but not drawn (the decoder still needs it
 * for the next ones); never more than [maxRun] in a row so motion still shows. If the oldest frame still inside is [lagMs] old
 * ([lagged]), the caller rebuilds the decoder and [caughtUp] drops frames up to the next key frame, which it asks the TV for
 * (at most once in [RESET_MS]), like CatchUp.kt does for the browser.
 */
class LiveGate(private val lagMs: Long = 600, private val staleMs: Long = 70, private val maxRun: Int = 3) {
    private class In(val pts: Long, val at: Long)
    private val inside = ArrayDeque<In>()
    private var run = 0
    private var skipping = false
    private var askedAt = -RESET_MS
    var catchUps = 0; private set

    /** true if the packet goes to the decoder: config always, frames not while skipping to a key frame. */
    @Synchronized fun admit(config: Boolean, key: Boolean): Boolean {
        if (config) return true
        if (skipping && !key) return false
        if (key) skipping = false
        return true
    }

    @Synchronized fun fed(pts: Long, now: Long) { inside.addLast(In(pts, now)) }

    /** The decoder has a frame of [pts]: its age, and whether to draw it. */
    @Synchronized fun out(pts: Long, now: Long): Pair<Long, Boolean> {
        var at = -1L
        while (inside.isNotEmpty() && inside.first().pts <= pts) at = inside.removeFirst().at
        // a frame out whose pts matches none fed (an encoder that reorders or restamps them: an A95X F3, issue #16) still means
        // the oldest one in is done; matched by pts alone, the oldest stayed inside, read as lag, and the decoder was rebuilt each 0.6 s
        if (at < 0 && inside.isNotEmpty()) at = inside.removeFirst().at
        val age = if (at < 0) 0 else now - at
        val draw = inside.isEmpty() || age < staleMs || run >= maxRun
        run = if (draw) 0 else run + 1
        return age to draw
    }

    @Synchronized fun lagged(now: Long): Boolean = inside.isNotEmpty() && now - inside.first().at >= lagMs

    /** The decoder is made again: what was inside is gone. True: ask the TV for a key frame now. */
    @Synchronized fun caughtUp(now: Long): Boolean {
        inside.clear(); run = 0; skipping = true; catchUps++
        return ask(now)
    }

    /** While skipping and no key frame comes: true when it is time to ask again. */
    @Synchronized fun wantKey(now: Long): Boolean = skipping && ask(now)

    @Synchronized fun skipping(): Boolean = skipping

    @Synchronized fun clear() { inside.clear(); run = 0; skipping = false }

    /** A decoder made again for a surface that came back: nothing before the next key frame, asked for now. */
    @Synchronized fun fromKey() { inside.clear(); run = 0; skipping = true; askedAt = -RESET_MS }

    private fun ask(now: Long): Boolean { if (now - askedAt < RESET_MS) return false; askedAt = now; return true }

    companion object { const val RESET_MS = 2000L }
}

/** One line a second for the debug log: frames in and out, drawn and skipped, picture age at the decoder's output. */
class LatencyStats(private val windowMs: Long = 1000) {
    private var from = -1L
    private var fed = 0; private var shown = 0; private var skipped = 0; private var sum = 0L; private var max = 0L

    @Synchronized fun fed() { fed++ }

    /** Records one decoded frame; returns the line when the window is over, else null. */
    @Synchronized fun out(age: Long, drawn: Boolean, now: Long, catchUps: Int): String? {
        if (from < 0) from = now
        if (drawn) shown++ else skipped++
        sum += age; if (age > max) max = age
        if (now - from < windowMs) return null
        val n = shown + skipped
        val s = "in=$fed out=$n drawn=$shown skipped=$skipped age avg=${if (n > 0) sum / n else 0}ms max=${max}ms catchUps=$catchUps"
        from = now; fed = 0; shown = 0; skipped = 0; sum = 0; max = 0
        return s
    }
}
