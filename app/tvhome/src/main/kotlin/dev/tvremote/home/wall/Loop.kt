package dev.tvremote.home.wall

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.io.File

/**
 * A file's picture played round and round with no hold at the turn: MediaExtractor -> MediaCodec -> the surface, on a thread of its own.
 * At the end of the file the extractor is sought back and its samples go on into the same decoder, their times moved on by one clip, so
 * the decoder never sees an end, a flush or a seek. MediaPlayer's `isLooping` flushes the decoder and seeks at each turn: the picture
 * stood ~100 ms on the FPT box (dev/decisions/tv-home.md "TV Home: video"). Only the video track is read: the sound is never decoded,
 * and nothing paces the picture by an audio clock (why [VideoWall] used an audio-less copy with MediaPlayer).
 *
 * Frames are let go at their own time (`releaseOutputBuffer(i, ns)`, the compositor shows each on its vsync); one already two frames
 * late is dropped rather than shown, and counted. [first] runs once the first frame is out, [size] with the picture's size, [error] when
 * the decoder fails; all three on this thread.
 */
class Loop(private val f: File, private val surface: Surface, private val size: (Int, Int) -> Unit,
           private val first: () -> Unit, private val error: (Throwable) -> Unit) {
    @Volatile private var going = true
    @Volatile var shown = 0; private set
    @Volatile var dropped = 0; private set
    private val thread = Thread({ run() }, "videowall").apply { priority = Thread.MAX_PRIORITY - 1 }

    fun start() = thread.start()

    /** Stops and waits for the decoder to let the surface go (it must before the surface is destroyed); a few frames' time at most. */
    fun stop() { going = false; try { thread.join(500) } catch (e: InterruptedException) { } }

    private fun run() {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(f.path)
            val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val fmt = ex.getTrackFormat(t)
            ex.selectTrack(t)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            c.configure(fmt, surface, null, 0)                          // the track's rotation, if any, is in fmt: the codec turns the surface's picture
            c.start()
            val frameUs = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) 1_000_000L / maxOf(1, fmt.getInteger(MediaFormat.KEY_FRAME_RATE)) else 33_333L
            val info = MediaCodec.BufferInfo()
            var shift = 0L                                              // added to the file's times: one clip per turn
            var last = 0L                                               // the latest time read this turn
            var t0 = -1L; var at0 = 0L                                  // the first frame's time and when it was shown (ns)
            var told = false
            while (going) {
                val i = c.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    var n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                    if (n < 0) {                                        // the end: the next turn's first sample, at its moved-on time
                        shift += last + frameUs; last = 0L
                        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) throw IllegalStateException("empty video")
                    }
                    last = maxOf(last, ex.sampleTime)
                    c.queueInputBuffer(i, 0, n, ex.sampleTime + shift, 0)
                    ex.advance()
                }
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) c.outputFormat.let { m ->
                    val w = if (m.containsKey("crop-right")) m.getInteger("crop-right") - m.getInteger("crop-left") + 1 else m.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (m.containsKey("crop-bottom")) m.getInteger("crop-bottom") - m.getInteger("crop-top") + 1 else m.getInteger(MediaFormat.KEY_HEIGHT)
                    size(w, h)
                }
                if (o < 0) continue
                val now = System.nanoTime()
                if (t0 < 0) { t0 = info.presentationTimeUs; at0 = now }
                val due = at0 + (info.presentationTimeUs - t0) * 1000
                if (now > due + 2 * frameUs * 1000) { c.releaseOutputBuffer(o, false); dropped++; continue }   // late: the next one is nearer its time
                val wait = (due - now) / 1_000_000 - 10                 // hand it over ~10 ms ahead; the compositor keeps the time
                if (wait > 0) SystemClock.sleep(wait)
                if (!going) { c.releaseOutputBuffer(o, false); break }
                c.releaseOutputBuffer(o, due)
                shown++
                if (!told) { told = true; first() }
            }
        } catch (e: Throwable) {
            if (going) error(e)
        } finally {
            try { codec?.stop() } catch (e: Throwable) { }
            try { codec?.release() } catch (e: Throwable) { }
            ex.release()
        }
    }
}
