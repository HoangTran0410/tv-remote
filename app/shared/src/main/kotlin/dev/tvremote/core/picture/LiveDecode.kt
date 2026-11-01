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
            m["vendor.qti-ext-dec-picture-order.enable"] = 1          // Qualcomm: out in decode order, not held for a reorder
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

    /**
     * The codec config with each SPS told "no frame is held for reordering" (VUI bitstream_restriction: max_num_reorder_frames 0,
     * max_dec_frame_buffering = its reference frames) when the encoder left that out. Without it a decoder may keep up to 16
     * frames before giving the first one back, as the H.264 spec allows: a Qualcomm decoder on a phone did, with an A95X F3 Air's
     * Amlogic encoder (issue #16): nothing came out for 600 ms, read as lag, the decoder rebuilt and the session restarted every
     * 5 s. Moonlight patches the SPS the same way. Screen encoders send no B-frames, so the order is already the shown one.
     * Returns the packet (the same object when nothing changed) and what the SPS said, for the log.
     */
    fun fixConfig(d: ByteArray): Pair<ByteArray, String> {
        val starts = ArrayList<Int>()                                 // where each NAL's first byte is
        var i = 0
        while (i + 3 <= d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) { starts.add(i + 3); i += 3 } else i++
        }
        var said = ""
        var changed = false
        val out = java.io.ByteArrayOutputStream(d.size + 8)
        var from = 0
        for ((n, s) in starts.withIndex()) {
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else d.size
            if (n + 1 < starts.size && end > s && d[end - 1].toInt() == 0) end--   // the 4-byte start code's leading zero
            if (s >= d.size || d[s].toInt() and 0x1f != 7) continue
            val r = try { Sps.restrict(d.copyOfRange(s, end)) } catch (e: RuntimeException) { null }   // not understood: as it came
            said = r?.second ?: "sps unread"
            val fixed = r?.first ?: continue
            out.write(d, from, s - from); out.write(fixed); from = end; changed = true
        }
        if (!changed) return d to said
        out.write(d, from, d.size - from)
        return out.toByteArray() to said
    }
}

/** Just enough of an H.264 sequence parameter set to add a VUI bitstream_restriction: see [LiveDecode.fixConfig]. */
internal object Sps {
    private class Bits(private val b: ByteArray) {
        var pos = 0
        fun u(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or ((b[pos shr 3].toInt() shr (7 - (pos and 7))) and 1); pos++ }; return v }
        fun ue(): Int { var z = 0; while (u(1) == 0) { z++; require(z < 32) }; return ((1 shl z) - 1) + (if (z > 0) u(z) else 0) }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    private class Out {
        private val o = java.io.ByteArrayOutputStream(); private var cur = 0; private var n = 0
        fun u(bits: Int, v: Int) { for (k in bits - 1 downTo 0) { cur = (cur shl 1) or ((v shr k) and 1); if (++n == 8) { o.write(cur); cur = 0; n = 0 } } }
        fun ue(v: Int) { val x = v + 1; val len = 32 - Integer.numberOfLeadingZeros(x); u(len - 1, 0); u(len, x) }
        fun copy(r: Bits, from: Int, to: Int) { r.pos = from; while (r.pos < to) u(1, r.u(1)) }
        fun bytes(): ByteArray { u(1, 1); while (n != 0) u(1, 0); return o.toByteArray() }   // rbsp_trailing_bits
    }

    /** The NAL (header byte first, emulation-prevention bytes in) with the restriction added; null when it already has one. */
    fun restrict(nal: ByteArray): Pair<ByteArray?, String> {
        val rb = unescape(nal)
        val r = Bits(rb); r.pos = 8                                   // past the NAL header
        val profile = r.u(8); r.u(16); r.ue()                         // constraint flags, level, sps id
        if (profile in HIGH) {
            val chroma = r.ue(); if (chroma == 3) r.u(1)
            r.ue(); r.ue(); r.u(1)
            if (r.u(1) == 1) for (k in 0 until if (chroma == 3) 12 else 8) if (r.u(1) == 1) {
                var last = 8; var next = 8
                repeat(if (k < 6) 16 else 64) { if (next != 0) { next = (last + r.se() + 256) % 256 }; if (next != 0) last = next }
            }
        }
        r.ue()                                                        // log2_max_frame_num
        val poc = r.ue()
        if (poc == 0) r.ue() else if (poc == 1) { r.u(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        val refs = r.ue(); r.u(1); r.ue(); r.ue()
        if (r.u(1) == 0) r.u(1)                                       // not frame_mbs_only: mb_adaptive
        r.u(1)
        if (r.u(1) == 1) repeat(4) { r.ue() }                         // cropping
        val said = "profile $profile poc $poc refs $refs"
        val w = Out()
        val vuiAt = r.pos
        if (r.u(1) == 0) {                                            // no VUI: one with only the restriction
            w.copy(r, 0, vuiAt); w.u(1, 1)
            repeat(8) { w.u(1, 0) }                                   // aspect, overscan, signal type, chroma loc, timing, nal hrd, vcl hrd, pic struct
        } else {
            if (r.u(1) == 1 && r.u(8) == 255) r.u(32)                 // aspect ratio
            if (r.u(1) == 1) r.u(1)                                   // overscan
            if (r.u(1) == 1) { r.u(4); if (r.u(1) == 1) r.u(24) }     // video signal type, colour description
            if (r.u(1) == 1) { r.ue(); r.ue() }                       // chroma location
            if (r.u(1) == 1) { r.u(32); r.u(32); r.u(1) }             // timing
            val nal = r.u(1) == 1; if (nal) hrd(r)
            val vcl = r.u(1) == 1; if (vcl) hrd(r)
            if (nal || vcl) r.u(1)
            r.u(1)                                                    // pic_struct_present
            val at = r.pos
            if (r.u(1) == 1) return null to "$said, restricted"
            w.copy(r, 0, at)
        }
        w.u(1, 1); w.u(1, 1); w.ue(2); w.ue(1); w.ue(16); w.ue(16)    // restriction: mv over boundaries, the spec's defaults
        w.ue(0); w.ue(maxOf(refs, 1))                                 // nothing held for reordering; the DPB its references need
        return escape(w.bytes()) to "$said, restriction added"      // the copy began at the NAL header
    }

    private fun hrd(r: Bits) { val n = r.ue(); r.u(8); repeat(n + 1) { r.ue(); r.ue(); r.u(1) }; r.u(20) }

    private val HIGH = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    fun unescape(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(b.size); var zeros = 0
        for (x in b) { val v = x.toInt() and 0xff; if (zeros >= 2 && v == 3) { zeros = 0; continue }; o.write(v); zeros = if (v == 0) zeros + 1 else 0 }
        return o.toByteArray()
    }

    fun escape(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(b.size + 4); var zeros = 0
        for (x in b) { val v = x.toInt() and 0xff; if (zeros >= 2 && v <= 3) { o.write(3); zeros = 0 }; o.write(v); zeros = if (v == 0) zeros + 1 else 0 }
        return o.toByteArray()
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
