package dev.tvremote.core.picture
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import dev.tvremote.core.WireLog

/**
 * Plays the TV's sound on this device: Opus (or AAC, on a TV with no Opus encoder: [codec]) packets from scrcpy, decoded by Android's own decoder, out through an AudioTrack.
 * How much is kept queued, and what waits or is dropped, is [SoundQueue]'s.
 */
/** 🔊 ×0.5 … ×10: the TV's sound louder (or softer) here than it is sent (a TV turned down sends it quiet). Read by every [AudioOut] as it plays. */
object SoundBoost { @Volatile var times = 1f }

/** How much sound is kept queued, per TV: what [SoundQueue] learnt last time ([learned], in ms, kept by the phone app across
 *  starts, [changed] after each change) and "Even" ([even]: half a second from the start, later behind the TV but no stall). */
object SoundPace {
    @Volatile var even = false
    val learned = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile var changed: (() -> Unit)? = null
}

class AudioOut(far: Boolean = false, private val host: String = "") {
    /** Kept queued at first: 0.15 s on the home network; through Tailscale ([far]: 4G, a relay) 0.4 s from the start, as
     *  even at home a busy TV (the FPT Play box) sends its sound up to 0.25 s late at times. */
    private val base = if (SoundPace.even) RATE / 2 else if (far) RATE * 4 / 10 else BASE
    private val first get() = maxOf(base, minOf(RATE * 6 / 10, (SoundPace.learned[host] ?: 0) * RATE / 1000))   // as the page: a whole second learnt once is not kept as lag
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var written = 0L                                          // frames given to the track, to tell how far behind it plays
    private var q = SoundQueue(base, MAX, first)
    private var room = 0                                              // the track's buffer asked for, in frames
    private val held = ArrayList<ByteArray>()                         // decoded while refilling after running dry
    private var drops = 0; private var dry = 0; private var told = 0L
    private val stamp = android.media.AudioTimestamp()
    private var loud: android.media.audiofx.LoudnessEnhancer? = null  // gain with its own limiter, so loud parts do not crack
    private var loudAt = 1f

    private fun boost(t: AudioTrack) {
        loudAt = SoundBoost.times
        try { t.setVolume(minOf(1f, loudAt)) } catch (ignored: Exception) { }   // softer: the track's own volume
        try {
            if (loudAt <= 1f && loud == null) return
            val l = loud ?: android.media.audiofx.LoudnessEnhancer(t.audioSessionId).also { loud = it }
            if (loudAt > 1f) l.setTargetGain((2000 * Math.log10(loudAt.toDouble())).toInt())   // in millibels: ×2 = +6 dB, ×10 = +20 dB
            l.enabled = loudAt > 1f
        } catch (ignored: Exception) { }                               // a phone without the effect: as sent
    }

    /** Frames the track has played. Its playback head moves in steps of 100 ms and more on some phones (Xiaomi): read
     *  alone it made the queue look full, and packets were dropped one after another. The timestamp, carried on by the
     *  clock, moves smoothly; the larger of the two counts. */
    private fun played(t: AudioTrack): Long {
        val head = t.playbackHeadPosition.toLong() and 0xffffffffL
        if (android.os.Build.VERSION.SDK_INT < 19 || !t.getTimestamp(stamp)) return head
        val now = stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * RATE / 1_000_000_000L
        return minOf(written, maxOf(head, now))
    }

    @Volatile private var mime = "audio/opus"

    /** What scrcpy was asked for, before its first packet: "aac" (an NVIDIA Shield has no Opus encoder), else Opus. */
    fun codec(name: String) { mime = if (name == "aac") "audio/mp4a-latm" else "audio/opus" }

    /** [config] is scrcpy's first packet, the OpusHead (AAC: its AudioSpecificConfig); then one packet at a time. */
    fun feed(config: Boolean, data: ByteArray) {
        if (config) { start(data); return }
        val c = codec ?: return
        val idx = c.dequeueInputBuffer(20000)
        if (idx < 0) return                                           // the decoder is behind: this packet is skipped
        val input = c.getInputBuffer(idx) ?: return
        input.clear()
        input.put(data)
        c.queueInputBuffer(idx, 0, data.size, 0, 0)
        drain(c)
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            if (out < 0) break
            val t = track
            val buf = c.getOutputBuffer(out)
            if (t != null && buf != null && info.size > 0) {
                if (loudAt != SoundBoost.times) boost(t)
                val behind = written - played(t)
                val pcm = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(pcm)
                val was = q.target
                when (q.next(pcm.size / 4, behind, System.nanoTime() / 1_000_000)) {   // not the wall clock: a phone asleep is not 30 s of steady sound
                    SoundQueue.Do.PLAY -> {
                        for (b in held) { t.write(b, 0, b.size); written += b.size / 4 }
                        held.clear()
                        t.write(pcm, 0, pcm.size); written += pcm.size / 4
                    }
                    SoundQueue.Do.HOLD -> held.add(pcm)
                    SoundQueue.Do.DROP -> drops++
                }
                val now = System.currentTimeMillis()
                if (now - told > 30000) {                             // About → Send the log: how the sound held up
                    val gaps = q.takeGaps()
                    if (drops > 0 || dry > 0 || gaps != null) WireLog.all.add("·", "sound: $drops packets dropped, ran dry $dry times, keeping ${q.target * 1000 / RATE} ms" + (gaps?.let { ", $it" } ?: ""))
                    told = now; drops = 0; dry = 0
                }
                if (q.target != was) {
                    if (q.target > was) dry++
                    if (host.isNotEmpty() && !SoundPace.even) { SoundPace.learned[host] = q.target * 1000 / RATE; SoundPace.changed?.invoke() }
                    if (android.os.Build.VERSION.SDK_INT >= 24 && q.room != room) room = q.room.also { t.setBufferSizeInFrames(it) }
                }
            }
            c.releaseOutputBuffer(out, false)
        }
    }

    @Suppress("DEPRECATION")
    private fun start(head: ByteArray) {
        release()
        val m = mime
        val f = MediaFormat.createAudioFormat(m, RATE, 2)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(head))
        if (m == "audio/opus") {
            val preSkip = if (head.size >= 12) (head[10].toInt() and 0xff) or ((head[11].toInt() and 0xff) shl 8) else 312
            f.setByteBuffer("csd-1", le64(preSkip * 1_000_000_000L / RATE))   // in nanoseconds, as MediaCodec wants it
            f.setByteBuffer("csd-2", le64(80_000_000L))               // the seek pre-roll Opus asks for
        }
        val c = MediaCodec.createDecoderByType(m)
        c.configure(f, null, null, 0)
        c.start()
        codec = c
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min * 2, if (android.os.Build.VERSION.SDK_INT >= 24) (MAX * 2 + RATE / 10) * 4 else RATE / 5 * 4)   // room to grow into
        val t = AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)
        q = SoundQueue(base, MAX, first); held.clear()
        room = q.room
        if (android.os.Build.VERSION.SDK_INT >= 24) t.setBufferSizeInFrames(room)
        t.play()
        track = t
        if (SoundBoost.times != 1f) boost(t)
        written = 0
    }

    fun release() {
        codec?.let { try { it.stop() } catch (ignored: Exception) { }; try { it.release() } catch (ignored: Exception) { } }
        codec = null
        loud?.let { try { it.release() } catch (ignored: Exception) { } }
        loud = null; loudAt = 1f
        track?.let { try { it.stop() } catch (ignored: Exception) { }; try { it.release() } catch (ignored: Exception) { } }
        track = null
    }

    private fun le64(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }

    private companion object {
        const val RATE = 48000
        const val BASE = RATE * 15 / 100                              // 0.15 s
        const val MAX = RATE                                          // 1 s: later than that the sound is too far behind the picture
    }
}
