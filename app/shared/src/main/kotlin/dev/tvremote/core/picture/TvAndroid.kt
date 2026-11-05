package dev.tvremote.core.picture
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.tvremote.core.WireLog
import dev.tvremote.core.ontv.Tailscale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A [Tv] session on Android: the key in the app's files, scrcpy-server from the APK's assets, the picture decoded straight
 * onto [surface] and "opus" sound played here ([AudioOut]) unless an [audioSink] takes it.
 */
fun Tv(
    ctx: Context, host: String, surface: Surface?, quality: Int, maxSide: Int, wake: Boolean, listener: Tv.Listener,
    sink: Tv.Sink? = null, audio: String? = null, audioSink: Tv.AudioSink? = null, wantControl: Boolean = true,
    audioShare: Boolean = false, audioBitRate: Int = 64000, fps: Int = 0, bitRate: Int = 0, encoder: String = "auto", hand: Boolean = false,
    quiet: Boolean = false, relay: String? = null,
): Tv {
    val app = ctx.applicationContext
    val hear = audioSink ?: if (audio == "opus") object : Tv.AudioSink {
        private val out = AudioOut(far = Tailscale.isAddress(host), host = host)
        override fun audio(config: Boolean, data: ByteArray) = out.feed(config, data)
        override fun end() = out.release()
        override val aac: Boolean get() = true
        override fun codec(name: String) = out.codec(name)
    } else null
    val screen = surface?.let { SurfaceScreen(it, host) }
    val tv = Tv(app.filesDir, { app.assets.open("scrcpy-server.jar").use { it.readBytes() } }, host, screen,
        quality, maxSide, wake, listener, sink, audio, hear, wantControl, audioShare, audioBitRate, fps, bitRate, encoder, hand, quiet, relay)
    screen?.onFail = { listener.onStatus("error", "decoder") }
    screen?.askKey = { Thread { try { tv.resetVideo() } catch (ignored: Exception) { } }.start() }   // off the reading thread
    return tv
}

private const val TAG = "TvrPic"

/**
 * Android's own H.264 decoder, drawing onto a Surface. Input and output are apart: the reading thread only hands packets over
 * ([feed]), the decoder's own callbacks draw what comes out as soon as it does, and skip a late frame when newer ones are
 * right behind it. [LiveGate] (LiveDecode.kt) decides, this carries it out. If the decoder falls too far behind it is made
 * again and the TV is asked ([askKey]) for a key frame. Debug: `adb shell setprop log.tag.TvrPic DEBUG` (a line a second:
 * frames in and out, age at the decoder's output; VERBOSE: one line per frame), then restart the picture.
 * A decoder that takes frames and gives none back (a black picture: a Poco F6, issue #10, while a Poco X7 Pro showed the same
 * TV) is made again more plainly ([mode]: without the vendor keys, then Android's software decoder); the log says each step.
 */
private class SurfaceScreen(private val surface: Surface, private val host: String) : Tv.Screen {
    /** how the TV is asked for a key frame (scrcpy: reset video); set by [Tv] right after the session exists */
    @Volatile var askKey: (() -> Unit)? = null
    /** set by [Tv] too: the decoder could not be made again, said once ("error", "decoder") instead of dropping every frame */
    @Volatile var onFail: (() -> Unit)? = null
    @Volatile private var failed = false

    private class Dec(val codec: MediaCodec, val thread: HandlerThread) { val inputs = LinkedBlockingQueue<Int>() }

    private val gate = LiveGate()
    private val stats = LatencyStats()
    private val debug = Log.isLoggable(TAG, Log.DEBUG)
    private val verbose = Log.isLoggable(TAG, Log.VERBOSE)
    @Volatile private var dec: Dec? = null
    @Volatile private var broken = false                              // a decoder error: made again at the next packet
    private var w = 0
    private var h = 0
    private var config: ByteArray? = null                             // the last codec config, fed again to a decoder made again
    @Volatile private var away = false                                // no surface to draw on: see pause()
    private var mode = 0                                              // 0 tuned + vendor keys, 1 no keys at all, 2 Android's software decoder
    @Volatile private var outs = 0                                    // frames out at this [mode] (any size); not reset by a catch-up,
    private var ins = 0                                               // which a decoder giving nothing triggers itself
    private var spsSaid = ""                                          // what the last SPS was (LiveDecode.fixConfig), logged once

    override fun start(w: Int, h: Int) {
        release()
        this.w = w; this.h = h
        gate.clear(); ins = 0; outs = 0; failed = false
        if (!away) open()                                             // away: made on resume(), at this size
    }

    @Synchronized private fun open() {
        // vendor keys first; a decoder that refuses them is made again without
        val d = if (mode > 0) build(vendor = false) else try { build(vendor = true) } catch (e: Exception) { Log.w(TAG, "decoder without vendor keys: $e"); build(vendor = false) }
        dec = d
        broken = false
        WireLog.all.add("·", "picture $host decoder ${d.codec.name}" + if (mode > 0) " (plain $mode)" else "")
    }

    private fun build(vendor: Boolean): Dec {
        val c = if (mode >= 2) soft() else MediaCodec.createDecoderByType("video/avc")
        val t = HandlerThread("tvr-decode")
        try {
            val lowOk = Build.VERSION.SDK_INT >= 30 && c.codecInfo.getCapabilitiesForType("video/avc")
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            val keys = if (mode > 0) emptyMap() else LiveDecode.tuning(Build.VERSION.SDK_INT, lowOk, vendor)
            val f = MediaFormat.createVideoFormat("video/avc", w, h)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 shl 20)
            for ((k, v) in keys) f.setInteger(k, v)
            t.start()
            val d = Dec(c, t)
            val cb = object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { d.inputs.add(index) }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) = shown(d, index, info)
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.w(TAG, "decoder error: $e"); WireLog.all.add("·", "picture $host decoder error ${e.diagnosticInfo}")
                    if (dec === d) broken = true
                }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            }
            if (Build.VERSION.SDK_INT >= 23) c.setCallback(cb, Handler(t.looper)) else c.setCallback(cb)   // Android 5: no handler argument, the caller's looper or the main one
            c.configure(f, surface, null, 0)
            c.start()
            if (debug) Log.d(TAG, "decoder ${c.name} lowLatencySupported=$lowOk keys=$keys")
            return d
        } catch (e: Exception) {
            try { c.release() } catch (ignored: Exception) { }
            t.quitSafely()
            throw e
        }
    }

    /** Android's own H.264 decoder by name (Codec2 from Android 10, OMX before), else whichever the type gives. */
    private fun soft(): MediaCodec {
        for (n in arrayOf("c2.android.avc.decoder", "OMX.google.h264.decoder")) try { return MediaCodec.createByCodecName(n) } catch (ignored: Exception) { }
        return MediaCodec.createDecoderByType("video/avc")
    }

    /** A frame came out (on the decoder's thread): drawn now, unless it is late and newer ones are right behind it. */
    private fun shown(d: Dec, index: Int, info: MediaCodec.BufferInfo) {
        // a frame drawn on a Surface may report size 0 (only its pixels matter): skipping those left a picture all black
        if (d !== dec || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { try { d.codec.releaseOutputBuffer(index, false) } catch (ignored: Exception) { }; return }
        outs++
        val now = SystemClock.elapsedRealtime()
        val (age, draw) = gate.out(info.presentationTimeUs, now)
        try { d.codec.releaseOutputBuffer(index, draw) } catch (ignored: IllegalStateException) { return }
        if (verbose) Log.v(TAG, "pts=${info.presentationTimeUs} age=${age}ms ${if (draw) "drawn" else "skipped"}")
        if (debug) stats.out(age, draw, now, gate.catchUps)?.let { Log.d(TAG, it) }
    }

    override fun feed(config: Boolean, pts: Long, raw: ByteArray): Boolean {
        val data = if (!config) raw else LiveDecode.fixConfig(raw).let { (d, said) ->
            if (said != spsSaid) { spsSaid = said; WireLog.all.add("·", "picture $host $said") }
            d
        }
        if (config) this.config = data
        if (away) return true
        if (failed) return false
        var now = SystemClock.elapsedRealtime()
        if (broken || (!config && gate.lagged(now))) catchUp(now)
        if (!gate.admit(config, !config && LiveDecode.isKey(data))) {
            if (gate.wantKey(now)) askKey?.invoke()                   // the key frame asked for did not come: ask again
            return true
        }
        val d = dec ?: return !failed
        val t0 = now
        var idx = -1
        while (idx < 0) {
            idx = d.inputs.poll(20, TimeUnit.MILLISECONDS) ?: -1
            if (idx >= 0) break
            if (dec !== d) return true                                // released meanwhile
            now = SystemClock.elapsedRealtime()
            if (!config && gate.lagged(now)) { catchUp(now); return true }   // the decoder takes nothing: this frame goes with the rest
            if (now - t0 > 10000) return false                        // stuck: the session ends
        }
        if (!queue(d, idx, data, pts, config)) return true
        if (!config) { gate.fed(pts, now); stats.fed(); if (++ins >= 30 && outs == 0 && mode < 2) plainer() }
        return true
    }

    private fun queue(d: Dec, idx: Int, data: ByteArray, pts: Long, config: Boolean): Boolean = try {
        val input = d.codec.getInputBuffer(idx)!!
        input.clear()
        input.put(data)
        d.codec.queueInputBuffer(idx, 0, data.size, pts, if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "queue failed: $e"); broken = true; false
    }

    /** 30 frames in, none out: the next plainer decoder, fed the config, and the TV asked for a key frame. */
    private fun plainer() {
        mode++; ins = 0; outs = 0
        WireLog.all.add("·", "picture $host no frame out of ${dec?.codec?.name}: trying a plainer decoder")
        catchUp(SystemClock.elapsedRealtime())
    }

    /** The decoder is far behind (or failed): made again, frames skipped up to the next key frame, which the TV is asked for. */
    private fun catchUp(now: Long) {
        if (debug) Log.d(TAG, "catch up: decoder made again")
        closeDec()
        try { open() } catch (e: Exception) {
            Log.w(TAG, "decoder not made again: $e"); WireLog.all.add("·", "picture $host decoder not made again: $e")
            if (!failed) { failed = true; onFail?.invoke() }          // once; feed() then ends the session, no retry per packet
            return
        }
        val d = dec
        val cfg = config
        if (d != null && cfg != null) {
            val idx = d.inputs.poll(1000, TimeUnit.MILLISECONDS)
            if (idx != null) queue(d, idx, cfg, 0, true)              // a new decoder needs the SPS / PPS before any frame
        }
        if (gate.caughtUp(now)) askKey?.invoke()
    }

    @Synchronized private fun closeDec() {
        val d = dec ?: return
        dec = null
        try { d.codec.stop() } catch (ignored: Exception) { }
        try { d.codec.release() } catch (ignored: Exception) { }
        d.thread.quitSafely()
    }

    override fun pause() { away = true; closeDec() }

    /** SurfaceView keeps its Surface object and fills it again, so a new decoder on it draws on the new one. */
    override fun resume() {
        if (!away) return
        if (w > 0) {
            try { open() } catch (e: Exception) { Log.w(TAG, "decoder not made again: $e"); broken = true }
            val d = dec
            val cfg = config
            if (d != null && cfg != null) d.inputs.poll(1000, TimeUnit.MILLISECONDS)?.let { queue(d, it, cfg, 0, true) }
            gate.fromKey()
        }
        away = false
        askKey?.invoke()
    }

    override fun release() = closeDec()
}
