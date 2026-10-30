package dev.tvremote.core.picture
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import java.util.regex.Pattern
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.AdbPrompt
import dev.tvremote.core.adb.TvShell
import dev.tvremote.core.WireLog
import dev.tvremote.core.ontv.Meminfo

/**
 * One session with a TV: ADB over the network, scrcpy-server started on the TV for the picture (H.264, shown by a [Screen]
 * or handed to a [Sink]) and for input (keys, touch, text). Everything runs off the UI thread.
 * With neither the TV is asked for input only: no picture is encoded, so the TV does no extra work.
 * Plain JVM, so the computer runs it too; on Android it is made by the Tv(context, …) in TvAndroid.kt.
 */
class Tv(
    /** where the ADB key lives */
    private val dir: File,
    /** scrcpy-server.jar, copied to the TV when it does not have this one */
    private val jar: () -> ByteArray,
    private val host: String,
    private val screen: Screen?,
    /** index into QUALITY */
    private val quality: Int,
    /** longest side of the phone screen, there is no point sending more pixels than it shows */
    private val maxSide: Int,
    /** turn the screen on first (the TV answered but is in standby) */
    private val wake: Boolean,
    private val listener: Listener,
    /** instead of a Screen: the encoded picture is handed over as it arrives (to pass on to a browser) */
    private val sink: Sink? = null,
    /**
     * The TV's sound (Android 11+; while it is captured the TV's own speakers are silent), handed to [audioSink]. null: none.
     * "opus": about 8 KB/s. "raw": 48 kHz 16-bit stereo PCM, no encoding at all on the TV.
     */
    private val audio: String? = null,
    private val audioSink: AudioSink? = null,
    /** false: a session that only watches or listens, with no keys or touch */
    private val wantControl: Boolean = true,
    /**
     * true: the TV keeps playing its sound too (scrcpy's playback capture with audio_dup; Android 13+). Older TVs fall back to
     * the private way (TV speakers silent) and say so with onStatus("audio", "noshare"). Apps that refuse playback capture
     * (some with DRM) are silent here; from Android 13 the private way uses it too (the whole output gave silence on some TVs,
     * such as the FPT Play box), so there they keep playing on the TV instead.
     */
    private val audioShare: Boolean = false,
    /** Opus bit rate: 64 kbit/s is plenty for speech and TV, 160 sounds like the source for music */
    private val audioBitRate: Int = 64000,
    /** the person's own frames per second and bit rate (Advanced picture), 0: the level's */
    private val fps: Int = 0,
    private val bitRate: Int = 0,
    /** "auto": the hardware encoder, the software one if it sends nothing ([watchFirstFrame]); "hw" or "sw": that one only */
    private val encoder: String = "auto",
    /** true: the person picked the level (or frames, rate) themselves: a TV with little memory ([LOW_RAM_KB]) is then not held to [LOW_SIDE] */
    private val hand: Boolean = false,
    /** true: a link nobody asked for (TV Setup's own, after every update or ADB reconnect, often at night) never turns the screen on */
    private val quiet: Boolean = false,
) {
    /** One H.264 packet as scrcpy sends it (Annex B). config: SPS/PPS, needed before the first frame; key: a key frame. */
    fun interface Sink { fun video(config: Boolean, key: Boolean, data: ByteArray) }

    /** One packet of sound as scrcpy sends it. config: the codec's header (Opus: OpusHead), before the first packet. */
    fun interface AudioSink {
        fun audio(config: Boolean, data: ByteArray)
        /** the sound has stopped for good */
        fun end() {}
    }

    /** Shows the picture: [start] at each new size (the decoder is made again), then one H.264 packet at a time. */
    interface Screen {
        fun start(w: Int, h: Int)
        /** false: the decoder took nothing for a long while, and the session ends */
        fun feed(config: Boolean, pts: Long, data: ByteArray): Boolean
        fun release()
        /** The surface went (the app left the screen): frames are dropped, the session goes on. */
        fun pause() {}
        /** The same surface is back: a decoder again, from the next key frame. */
        fun resume() {}
    }

    interface Listener {
        fun onStatus(key: String, detail: String)                     // connecting | allow | starting | live | closed | error; audio (detail "unsupported"); rate
        fun onVideoSize(w: Int, h: Int)
        fun onNetwork(mac: String, lan: String, tailscale: String)    // the TV's home-network address (for Wake-on-LAN), and its 100.x one if any
        fun wantsNetwork(): Boolean = true                            // false: nobody uses the address (TV Setup's own session), so skip the `ip addr show`
        fun wantsPass(): Boolean = false                              // no TV Setup pass for this TV yet: fetch one while ADB works
        fun onPass(token: String) {}                                  // lets TV Setup answer "Allow USB debugging?" for us later, see AdbPrompt
    }

    companion object {
        private const val MOUSE_ID: Short = 1
        private const val MOUSE_TICK_MS = 8                          // at most ~125 writes a second
        /** The boot mouse: 3 buttons, relative X / Y, wheel; the report is 4 bytes (buttons, dx, dy, wheel). */
        private val MOUSE_DESC = intArrayOf(0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95, 0x03, 0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x05, 0x81, 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0xC0, 0xC0).map { it.toByte() }.toByteArray()
        private const val VER = "5.0.1"
        private const val REMOTE = "/data/local/tmp/scrcpy-server.jar"
        /** TVs whose hardware encoder takes the picture and sends nothing back (a TCL's Realtek one): their software encoder */
        private val softEncoder = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** Each TV's software encoder by name, from list_encoders: asked once, whether chosen by hand or after a stall */
        private val softName = java.util.concurrent.ConcurrentHashMap<String, String>()
        private const val FIRST_FRAME_MS = 8000L
        /** How long a keys' session goes unused while another carries its keys before its own scrcpy-server rests: see [carry] */
        const val REST_MS = 60_000L

        /** The TV's screen as a PNG; null when it sent none. */
        @Throws(IOException::class)
        fun screencap(a: Adb): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(65536)
            a.open("exec:screencap -p").use {
                while (true) {
                    val n = it.read(buf, 0, buf.size, 15000)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
            return png(out.toByteArray())
        }

        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

        /** From the PNG's own start: some MediaTek TVs (Xiaomi) print a line of their own before it (cleanyourtv). */
        fun png(b: ByteArray): ByteArray? {
            for (i in 0..b.size - PNG.size - 1)
                if ((PNG.indices).all { b[i + it] == PNG[it] }) return if (i == 0) b else b.copyOfRange(i, b.size)
            return null
        }
        private const val POLL_MS = 25L
        private const val SEP = "#tvr-sep#"
        private const val QUIET_WINDOWS = 3                          // 3 windows of 3 s of nothing but silence from a captured TV that is playing: the app refuses capture
        /** Waits for the capture's virtual output to go, then starts Google's remote service: see endCapture */
        private const val REVIVE_REMOTE = "for i in 1 2 3 4 5 6 7 8 9 10; do dumpsys audio | grep -qi remote_submix || break; sleep 0.5; done; " +
            "pm path com.google.android.tv.remote.service >/dev/null 2>&1 && am startservice -n com.google.android.tv.remote.service/.RemoteService >/dev/null 2>&1; true"

        /**
         * Picture quality: longest side in pixels, bits per second, frames per second. The bit rate is what decides the data
         * used while the picture moves (a still picture sends almost nothing); the size mostly decides the load on the TV,
         * whose software encoder costs about 0.7 of 4 cores at 960 px and 2 Mbit/s, far more uncapped.
         */
        /** Keys that should bring up a screen of their own: Menu, TV settings, Input, Guide, Live TV. See [pressSeen]. */
        val OPENERS = setOf(82, 176, 178, 172, 170, 190, 191, 195)

        val QUALITY = arrayOf(intArrayOf(480, 300_000, 15), intArrayOf(720, 800_000, 20), intArrayOf(960, 2_000_000, 20),
            intArrayOf(360, 200_000, 15), intArrayOf(240, 120_000, 15),   // added later, at the end: the index is what people have saved
            intArrayOf(1280, 4_000_000, 30))                          // the computer's "sharpest, heavy on the TV" (not in LEVELS: the phone never offers it)

        /**
         * The frames a second a level's own rate gives way to on the TV's software encoder: twice the frames would be twice its
         * ~0.7 core. A hardware one costs about the same at 20 as at 10, and the data grows less than the frames (720 px
         * scrolling on the emulator: 22 KB/s at 10, 40 at 20).
         */
        const val SOFT_FPS = 10

        /** The frames a second asked of scrcpy: the person's own, else the level's, held to [SOFT_FPS] on a software encoder. */
        fun fpsFor(level: Int, asked: Int, soft: Boolean, capped: Boolean = false): Int =
            if (asked > 0) asked.coerceIn(1, 60) else QUALITY[level.coerceIn(0, QUALITY.size - 1)][2].let { if (soft) minOf(it, SOFT_FPS) else if (capped) minOf(it, LOW_FPS) else it }

        /** A TV with less than 1.5 GB (MemTotal, kB) runs the picture's encoder and the apps out of the same few hundred MB: see [videoOpts]. */
        const val LOW_RAM_KB = 1_572_864L
        const val LOW_SIDE = 720                                      // longest side, pixels
        const val LOW_RATE = 1_000_000                                // bits per second
        const val LOW_FPS = 20

        fun lowRam(memKb: Long?) = memKb != null && memKb in 1 until LOW_RAM_KB

        /**
         * scrcpy's size and bit rate for a level. [capped] (a low-memory TV, the level not picked by hand): never above
         * [LOW_SIDE] px and, unless the person set a rate in Advanced, [LOW_RATE]; lower levels stay as they are.
         * (scrcpy 5 would also take `video_codec_options=i-frame-interval:int=2`; not sent: more key frames cost bits at 1 Mbit/s.)
         */
        fun videoOpts(level: Int, maxSide: Int, bitRate: Int, capped: Boolean): String {
            val q = QUALITY[level.coerceIn(0, QUALITY.size - 1)]
            var side = minOf(q[0], maxSide)
            var rate = if (bitRate > 0) bitRate.coerceIn(100_000, 20_000_000) else q[1]
            if (capped) { side = minOf(side, LOW_SIDE); if (bitRate <= 0) rate = minOf(rate, LOW_RATE) }
            return "video_bit_rate=$rate max_size=" + maxOf(160, side / 32 * 32)
            // a multiple of 32, not of 8: some hardware encoders (TCL's Realtek) shear the picture into streaks at 720 or 240 wide
        }

        /** The levels from lightest to sharpest, as indexes into QUALITY. */
        val LEVELS = intArrayOf(4, 3, 0, 1, 2)
    }

    @Volatile private var video: String                               // scrcpy video options
    @Volatile private var capped = false                              // held to the low-memory level for this session
    @Volatile private var io = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    @Volatile private var stalled = false                             // the encoder sent no picture: once more with the software one
    @Volatile private var framed = false
    private val quietLock = Any()
    @Volatile private var quieted = false                             // this session turned the TV's volume to 0 (PrivateListen)
    @Volatile private var quietChecking = false

    /** The TV's volume as it was: at the end of the session, or at once when capture gives only silence (an app that refuses capture). Never throws. */
    private fun giveBackSound() {
        synchronized(quietLock) {
            if (!quieted) return
            quieted = false
            val a = adb ?: return
            try { PrivateListen.release({ c -> a.shell(c, 8000) }) } catch (ignored: Exception) { }   // a dead link: the mark stays, the next connection undoes it
        }
    }

    /** Capture has been silent for a while with the TV quiet: if something is playing there, the app refuses capture, so the TV gets its sound back. */
    private fun checkBlocked() {
        if (!quieted || quietChecking) return
        quietChecking = true
        Thread({
            try {
                val a = adb
                if (a != null && quieted && PrivateListen.playing { c -> a.shell(c, 8000) }) { giveBackSound(); status("audio", "tvloud") }
            } catch (ignored: Exception) { } finally { quietChecking = false }
        }, "tv-quiet-check").apply { isDaemon = true }.start()
    }

    @Volatile private var capture: List<Adb.Stream>? = null           // the sockets while the TV's sound is captured
    @Volatile private var adb: Adb? = null
    @Volatile private var control: Adb.Stream? = null
    @Volatile private var server: Adb.Stream? = null                  // the shell running scrcpy-server: closed, the TV ends it
    @Volatile private var carrier: Tv? = null                         // another session of this TV taking our keys: see carry()
    @Volatile private var resting = false                             // our scrcpy-server stopped meanwhile, the ADB link kept
    @Volatile private var wanted = false                              // asked back while resting
    @Volatile private var ownUse = 0L
    private val restLock = Object()
    private val restable: Boolean get() = wantControl && !wantVideo && audio == null
    @Volatile private var width = 960
    @Volatile private var height = 540
    private val wantVideo = screen != null || sink != null

    init {
        video = videoOpts(quality, maxSide, bitRate, false)
        Thread({ run() }, "tv-session").apply { isDaemon = true }.start()
    }

    /** To the listener, and into the log (not the rate: every second). */
    private fun status(key: String, detail: String) {
        WireLog.all.add("·", "picture $host $key $detail")
        listener.onStatus(key, detail)
    }

    private fun run() {
        session()
        if (stalled && !stopped) {
            stalled = false
            io = Executors.newSingleThreadExecutor()
            session()
        }
    }

    private fun session() {
        val socks = arrayListOf<Adb.Stream>()
        try {
            if (Trace.on) Trace.log("s.begin", host)
            status("connecting", host)
            val a = Adb(dir)
            adb = a
            a.connectTo(host) { status("allow", "") }
            if (stopped) return
            if (Trace.on) Trace.log("s.adb", host)
            // one round trip for what used to be five: the model, the Android version, the address, the jar on the TV
            val facts = startFacts(a, jar())
            if (Trace.on) Trace.log("s.facts")
            status("starting", facts.model)
            val tvShell = { c: String -> a.shell(c, 8000) }
            try { PrivateListen.recover(tvShell, facts.quietMark) } catch (ignored: Exception) { }   // a mute left by a crash or a dropped link
            if (!hand && wantVideo && lowRam(facts.memKb)) { capped = true; video = videoOpts(quality, maxSide, bitRate, true); status("lowram", "${facts.memKb!! / 1024} MB") }
            if (listener.wantsNetwork()) reportNetwork(facts.ipAddr)
            if (listener.wantsPass()) try { AdbPrompt.token(a, "phone")?.let { listener.onPass(it) } } catch (ignored: IOException) { }
            pushServer(a, facts.jarOk)
            if (Trace.on) Trace.log("s.pushed")
            var hearing = audio
            val sdk = facts.sdk
            val share = hearing != null && audioShare && sdk >= 33
            if (hearing != null && audioShare && !share && sdk >= 30) status("audio", "noshare")
            if (hearing != null && sdk < 30) {                        // scrcpy captures sound from Android 11
                status("audio", "unsupported")
                hearing = null
                if (!wantVideo && !wantControl) throw IOException("this TV cannot send its sound (Android 11 or newer is needed)")
            }
            val soft = if (!wantVideo) null else when (encoder) { "hw" -> null; "sw" -> softFor(a); else -> softEncoder[host] }
            /** Starts scrcpy-server and opens its sockets in the order it takes them; again after [rest]. */
            fun startServer(): List<Adb.Stream> {
                val got = arrayListOf<Adb.Stream>()
                val scid = String.format("%08x", Random().nextInt(0x7fffffff))
                val log = a.open("shell:CLASSPATH=" + REMOTE + " app_process / com.genymobile.scrcpy.Server " + VER + " scid=" + scid
                        + " tunnel_forward=true cleanup=false control=$wantControl send_dummy_byte=true send_device_meta=false clipboard_autosync=false "   // autosync on, GET_CLIPBOARD answers nothing
                        + (if (wantVideo) "video=true $video max_fps=" + fpsFor(quality, fps, soft != null, capped) + (soft?.let { " video_encoder=$it" } ?: "") else "video=false")
                        + (if (hearing == null) " audio=false" else " audio=true audio_codec=$hearing" + (if (hearing == "opus") " audio_bit_rate=$audioBitRate" else "") + (if (sdk >= 33) " audio_source=playback" else "") + (if (share) " audio_dup=true" else ""))
                        // scrcpy presses POWER at start when the screen is off: TV Setup's own link, made again after a quiet
                        // update at night, turned a TV in standby on (issue #6). A person's remote still wakes it, as before
                        + (if (quiet) " power_on=false" else "")
                        // scrcpy-server does not always notice that we left (a still picture sends nothing), and a leftover one keeps
                        // loading the TV. So a shell loop on the TV waits for our heartbeat and kills the server when it stops; 60 s,
                        // not less: a phone frozen in the background or a laptop that slept a moment is not a viewer gone.
                        + " & P=\$!; while read -t 60 x; do :; done; kill \$P")
                server = log
                if (Trace.on) Trace.log("s.launch")
                Thread({
                    try { while (!stopped) { log.write("\n".toByteArray()); Thread.sleep(8000) } } catch (gone: Exception) { }
                }, "scrcpy-heartbeat").apply { isDaemon = true }.start()
                Thread({ try { log.readAll() } catch (ignored: IOException) { } }, "scrcpy-log").apply { isDaemon = true }.start()
                var first: Adb.Stream? = null                             // the first of the sockets: video, then sound, then control, whichever are asked for
                val one = ByteArray(1)
                var i = 0
                val giveUp = System.nanoTime() + 20_000_000_000L
                while (System.nanoTime() < giveUp && first == null && !stopped) {   // until the server listens, the TV refuses the socket
                    try {
                        val s = a.open("localabstract:scrcpy_$scid")
                        if (s.read(one, 0, 1, 4000) == 1 && one[0].toInt() == 0) first = s else s.close()
                    } catch (notYet: IOException) {
                        if (a.isDead()) throw notYet
                    }
                    if (first == null) Thread.sleep(if (i < 60) POLL_MS else 100)   // a refused socket costs the TV nothing: tight for ~2 s, then easier
                    i++
                }
                if (first == null) throw IOException("scrcpy-server did not start on the TV")
                if (Trace.on) Trace.log("s.listening", "tries=$i")
                got.add(first)                                            // the server takes the connections in this order
                val count = (if (wantVideo) 1 else 0) + (if (hearing != null) 1 else 0) + (if (wantControl) 1 else 0)
                while (got.size < count) got.add(a.open("localabstract:scrcpy_$scid"))
                return got
            }
            socks.addAll(startServer())
            // "only this device" from Android 13: playback capture leaves the TV playing, so its music volume goes to 0 while we listen
            if (hearing != null && !share && sdk >= 33) Thread({
                synchronized(quietLock) {
                    if (stopped) return@Thread
                    quieted = try { PrivateListen.engage(tvShell) } catch (e: Exception) { false }
                }
                status("audio", if (quieted) "tvsilent" else "tvloud")
            }, "tv-quiet").apply { isDaemon = true }.start()
            var next = 0
            val pictureIn = if (wantVideo) socks[next++] else null
            val soundIn = if (hearing != null) socks[next++] else null
            val ctl = if (wantControl) socks[next] else null
            control = ctl
            if (hearing != null) capture = socks
            if (soundIn != null && (pictureIn != null || ctl != null))
                // a codec error ends the sound, not the app
                Thread({ try { hear(soundIn, hearing!!) } catch (ignored: Throwable) { } }, "scrcpy-audio").apply { isDaemon = true }.start()
            if (pictureIn != null) {
                if (encoder == "auto" && soft == null) watchFirstFrame(a, pictureIn)
                if (ctl != null) Thread({ try { deviceMessages(ctl) } catch (ignored: IOException) { } }, "scrcpy-ctl").apply { isDaemon = true }.start()
                play(pictureIn)
            } else if (ctl != null) {
                status("live", "")
                var c: Adb.Stream = ctl
                while (true) {
                    try { deviceMessages(c) } catch (ignored: IOException) { }   // returns when the TV closes the session, or rest() did
                    if (!backWanted(a)) break
                    c = startServer()[0]
                    synchronized(restLock) { control = c; resting = false; restLock.notifyAll() }
                }
            } else if (soundIn != null) {
                status("live", "")
                hear(soundIn, hearing!!)
            }
            if (!stopped && !stalled) status("closed", "")
        } catch (e: Throwable) {
            if (!stopped && !stalled) status("error", e.message ?: e.javaClass.simpleName)
        } finally {
            control = null
            resting = false
            screen?.release()
            if (capture != null) endCapture(socks)
            capture = null
            giveBackSound()
            adb?.close()                                              // the heartbeat stops, the loop on the TV kills scrcpy-server
            io.shutdownNow()
        }
    }

    /**
     * Some hardware encoders open, take the screen and never put out a frame (a TCL on Android 11: one session header after
     * five seconds, then nothing), and the picture waits forever. The TV's software H.264 encoder is found with scrcpy's
     * list_encoders, kept for this TV, and the session starts again with it.
     */
    private fun watchFirstFrame(a: Adb, video: Adb.Stream) {
        Thread({
            try { Thread.sleep(FIRST_FRAME_MS) } catch (e: InterruptedException) { return@Thread }
            if (stopped || framed) return@Thread
            val soft = softFor(a) ?: return@Thread
            if (stopped || framed) return@Thread
            WireLog.all.add("·", "picture $host no frame from the hardware encoder: $soft")
            softEncoder[host] = soft
            stalled = true
            video.close()
        }, "scrcpy-first-frame").apply { isDaemon = true }.start()
    }

    /** The TV's software H.264 encoder, from scrcpy's list_encoders (asked once per TV); null if it has none or did not say. */
    private fun softFor(a: Adb): String? {
        softName[host]?.let { return it }
        val list = try { a.shell("CLASSPATH=$REMOTE app_process / com.genymobile.scrcpy.Server $VER cleanup=false list_encoders=true 2>&1", 15000) } catch (e: IOException) { return null }   // cleanup=false: or scrcpy deletes its jar
        val soft = Regex("--video-codec=h264 --video-encoder=(\\S+)\\s+\\(sw\\)").find(list)?.groupValues?.get(1) ?: return null
        softName[host] = soft                                         // not [softEncoder]: "Software" picked once is not Auto's choice
        return soft
    }

    /**
     * The sound capture ends: closing the sockets lets scrcpy-server let it go itself (killed, Android notices the dead capture
     * seconds later and the TV's own sound stutters meanwhile). Then Google's remote service is started again: on some TVs it
     * crashes on the capture's virtual output and Android waits hours to restart it.
     */
    private fun endCapture(socks: List<Adb.Stream>) {
        val a = adb ?: return
        for (s in socks) try { s.close() } catch (ignored: Exception) { }
        try { a.shell(REVIVE_REMOTE) } catch (ignored: IOException) { }   // waits there for the virtual output to go
    }

    /** What the first shell call brings back. [jarOk]: the jar on the TV is the one we hold. */
    private class Facts(val model: String, val sdk: Int, val ipAddr: String, val jarOk: Boolean, val memKb: Long? = null, val quietMark: Int? = null)

    /**
     * One shell round trip for everything the start needs to know (each costs ~40 ms over Wi-Fi): wake, the model, the Android
     * version, `ip addr` (if wanted) and the jar's state on the TV. The jar is compared by MD5 (size alone would miss a changed
     * jar of the same size); a TV without md5sum falls back to the size.
     */
    private fun startFacts(a: Adb, jar: ByteArray): Facts {
        val cmd = (if (wake) "input keyevent KEYCODE_WAKEUP; " else "") +
            "getprop ro.product.model; echo '$SEP'; getprop ro.build.version.sdk; echo '$SEP'; " +
            "if [ -f $REMOTE ]; then wc -c < $REMOTE; md5sum $REMOTE 2>/dev/null; else echo 0; fi; echo '$SEP'; grep MemTotal /proc/meminfo; echo '$SEP'; cat ${PrivateListen.MARK} 2>/dev/null; echo '$SEP'" +   // the mark: a mute a run before us left behind
            (if (listener.wantsNetwork()) "; ip addr show" else "")
        val parts = a.shell(cmd).split(SEP, limit = 6).map { it.trim() }
        val jarLines = parts.getOrElse(2) { "" }.lines()
        val md5 = jarLines.getOrNull(1)?.trim()?.substringBefore(' ')?.takeIf { it.length == 32 }
        val ok = if (md5 != null) md5.equals(md5Of(jar), true) else jarLines.firstOrNull()?.trim() == jar.size.toString()
        return Facts(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }.toIntOrNull() ?: 0, parts.getOrElse(5) { "" }, ok, Meminfo.kb(parts.getOrElse(3) { "" }, "MemTotal"), PrivateListen.left(parts.getOrElse(4) { "" }))
    }

    private fun md5Of(b: ByteArray) = java.security.MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    /** MAC and IP of the interface that is on the home network, and the Tailscale 100.x address (no MAC) if the TV has one. */
    private fun reportNetwork(all: String) {
        val ts = Regex("inet (100\\.(?:6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.[0-9.]+)/").find(all)?.groupValues?.get(1) ?: ""
        for (block in Pattern.compile("(?m)^\\d+: ").split(all)) {
            val ip = Pattern.compile("inet ([0-9.]+)/").matcher(block)
            val mac = Pattern.compile("link/ether ([0-9a-f:]{17})").matcher(block)
            if (!ip.find() || !mac.find()) continue
            val lan = ip.group(1)!!
            if (lan.startsWith("192.168.") || lan.startsWith("10.") || lan.matches(Regex("172\\.(1[6-9]|2\\d|3[01])\\..*"))) {
                listener.onNetwork(mac.group(1)!!.uppercase(Locale.ROOT), lan, ts)
                return
            }
        }
    }

    /** Copies scrcpy-server from the APK to the TV unless [same] says the same file is already there. */
    @Throws(IOException::class)
    private fun pushServer(a: Adb, same: Boolean) {
        if (same) return
        val jar = jar()
        val want = md5Of(jar)
        a.put(REMOTE, jar)
        for (i in 0 until 20) {                                       // cat finishes writing shortly after the stream closes
            val out = a.shell("md5sum $REMOTE 2>/dev/null || wc -c < $REMOTE").trim()
            if (out.startsWith(want) || out == jar.size.toString()) return
            try { Thread.sleep(150) } catch (e: InterruptedException) { break }
        }
        throw IOException("could not copy scrcpy-server to the TV")
    }

    /**
     * scrcpy v5 video socket: 4 bytes codec id, then 12-byte headers. Top bit set = session packet (width, height in the
     * last 8 bytes, no payload). Otherwise bit 62 = codec config, bit 61 = key frame, the rest pts; last 4 bytes = size.
     */
    @Throws(IOException::class)
    private fun play(video: Adb.Stream) {
        val head = ByteArray(12)
        video.readFully(head, 0, 4)
        var first = true
        var bytes = 0L
        var since = System.currentTimeMillis()
        while (!stopped) {
            video.readFully(head, 0, 12)
            val h = ByteBuffer.wrap(head)
            val flags = h.long
            val size = h.int
            if (flags < 0) {
                width = (flags and 0xffffffffL).toInt(); height = size
                screen?.start(width, height)
                listener.onVideoSize(width, height)
                continue
            }
            if (size < 0 || size > (8 shl 20)) throw IOException("bad video packet")
            val data = ByteArray(size)
            video.readFully(data, 0, size)
            bytes += size + 12
            val now = System.currentTimeMillis()
            if (now - since >= 3000) {                                // data actually used, shown next to the TV's name
                listener.onStatus("rate", (bytes * 1000 / (now - since) / 1024).toString())
                bytes = 0; since = now
            }
            val config = (flags and (1L shl 62)) != 0L
            if (Trace.on) Trace.log("v.in", "pts=${(flags and ((1L shl 61) - 1)) / 1000} cfg=${if (config) 1 else 0} key=${if ((flags and (1L shl 61)) != 0L) 1 else 0} n=$size")
            if (sink != null) {
                sink.video(config, (flags and (1L shl 61)) != 0L, data)
                if (first && !config) { first = false; framed = true; dev.tvremote.core.Census.video(host); if (Trace.on) Trace.log("v.first"); status("live", "") }
                continue
            }
            val sc = screen ?: continue
            if (!sc.feed(config, if (config) 0L else flags and ((1L shl 61) - 1), data)) break
            if (first && !config) { first = false; framed = true; dev.tvremote.core.Census.video(host); if (Trace.on) Trace.log("v.first"); status("live", "") }
        }
    }

    /**
     * scrcpy's sound socket: 4 bytes codec id (0 or 1: the TV could not capture sound), then the same 12-byte headers as the
     * picture, handed to [audioSink].
     */
    @Throws(IOException::class)
    private fun hear(s: Adb.Stream, codec: String) {
        val head = ByteArray(12)
        s.readFully(head, 0, 4)
        val id = ByteBuffer.wrap(head, 0, 4).int
        if (id == 0 || id == 1) { status("audio", "unsupported"); return }   // e.g. the TV refused the capture
        var bytes = 0L
        var heard = false
        var voiced = false                                            // something but silence in this window (raw: a non-zero sample; Opus: judged by the rate)
        var hushed = 0                                                // windows of silence in a row while the TV is quiet
        var since = System.currentTimeMillis()
        try {
            while (!stopped) {
                s.readFully(head, 0, 12)
                val h = ByteBuffer.wrap(head)
                val flags = h.long
                val size = h.int
                if (size < 0 || size > (1 shl 20)) throw IOException("bad audio packet")
                val data = ByteArray(size)
                s.readFully(data, 0, size)
                val config = (flags and (1L shl 62)) != 0L
                if (Trace.on) Trace.log("a.in", "pts=${(flags and ((1L shl 62) - 1)) / 1000} cfg=${if (config) 1 else 0} n=$size")
                audioSink?.audio(config, data)
                if (!config && !voiced && codec == "raw") voiced = data.any { it.toInt() != 0 }
                if (Trace.on && !config && !heard) { heard = true; Trace.log("a.first") }
                bytes += size + 12
                val now = System.currentTimeMillis()
                if (now - since >= 3000) {
                    if (quieted) {
                        val loud = if (codec == "raw") voiced else bytes * 1000.0 / (now - since) / 1024 >= 1.0
                        hushed = if (loud) 0 else hushed + 1
                        if (hushed >= QUIET_WINDOWS) { hushed = 0; checkBlocked() }
                    }
                    voiced = false
                    listener.onStatus("arate", (bytes * 1000.0 / (now - since) / 1024).let { if (it < 10) String.format(java.util.Locale.US, "%.1f", it) else it.toInt().toString() }); bytes = 0; since = now }   // Opus: under 1 KB/s while the TV is quiet
            }
        } finally {
            audioSink?.end()
        }
    }

    fun stop() {
        stopped = true
        synchronized(restLock) { restLock.notifyAll() }
        val c = capture
        if (c == null) { adb?.close(); return }
        Thread({                                                      // the session's thread ends the capture, then closes ADB
            for (s in c) try { s.close() } catch (ignored: Exception) { }
            Thread.sleep(20000)
            adb?.close()                                              // in case it is stuck
        }, "scrcpy-stop").apply { isDaemon = true }.start()
    }

    fun ready(): Boolean = (control != null || resting) && !stopped

    /**
     * [by] (the picture's session, once it is live) takes this session's keys, typing and clipboard from now on: one
     * scrcpy-server on the TV instead of two. After [REST_MS] in which nothing needed our own, it rests ([rest]). null: the
     * picture is going, so our own server comes back now ([back]), before the next key needs it.
     */
    fun carry(by: Tv?) {
        carrier = by
        if (by == null) { back(); return }
        if (!restable) return
        ownUse = System.currentTimeMillis()
        Thread({
            try {
                while (carrier === by && !stopped) {
                    val idle = System.currentTimeMillis() - ownUse
                    if (idle >= REST_MS) { if (by.ready()) rest(); return@Thread }
                    Thread.sleep(REST_MS - idle)
                }
            } catch (e: InterruptedException) { }
        }, "keys-rest").apply { isDaemon = true }.start()
    }

    /**
     * Stops our scrcpy-server (~47 MB of the TV's memory, measured on the Google TV emulator) while another session carries
     * the keys. The ADB link stays for everything else, and [ready] stays true: a key that needs our own server starts it
     * again (~150 ms, the jar is on the TV already) and goes once it is up.
     */
    fun rest() {
        if (!restable || stopped) return
        val c: Adb.Stream
        synchronized(restLock) {
            if (resting) return
            c = control ?: return
            resting = true; wanted = false; control = null
        }
        try { c.close() } catch (ignored: Exception) { }              // scrcpy-server ends with its control socket
        try { server?.close() } catch (ignored: Exception) { }        // and the loop on the TV with its shell
    }

    /** Our own scrcpy-server again after [rest]. */
    fun back() = synchronized(restLock) { if (resting) { wanted = true; restLock.notifyAll() } }

    fun resting(): Boolean = resting

    /** The session's thread after [deviceMessages] returned: true once [back] asks for our server again, false when it ends. */
    private fun backWanted(a: Adb): Boolean = synchronized(restLock) {
        if (!resting) return false                                    // the TV closed it, not rest()
        while (!wanted && !stopped) {
            if (a.isDead()) return false                              // nobody would notice otherwise: "closed" makes the app reconnect
            restLock.wait(5000)
        }
        !stopped
    }

    /** A sender's wait for the server [back] asked for: up to 3 s, then the message is dropped as when the TV is gone. */
    private fun ownControl(): Adb.Stream? = synchronized(restLock) {
        val end = System.currentTimeMillis() + 3000
        while (resting && !stopped) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            restLock.wait(left)
        }
        control
    }
    fun stopped(): Boolean = stopped

    // ---- input: scrcpy control messages (big-endian), see ControlMessageReader.java in scrcpy ----
    private fun send(msg: ByteArray): java.util.concurrent.Future<*>? {
        val by = carrier
        if (by != null && by.control != null && !by.stopped) return by.send(msg)   // its scrcpy-server takes it: ours may rest
        if (resting) back() else if (control == null) return null
        if (io.isShutdown) return null
        ownUse = System.currentTimeMillis()
        try {
            return io.submit { val c = control ?: ownControl() ?: return@submit; try { c.write(msg) } catch (ignored: IOException) { } }
        } catch (shuttingDown: RuntimeException) {
            return null                                               // session ended between the check and the call
        }
    }

    /** action: 0 down, 1 up. */
    fun key(action: Int, code: Int, repeat: Int, meta: Int) {
        send(ByteBuffer.allocate(14).put(0.toByte()).put(action.toByte()).putInt(code).putInt(repeat).putInt(meta).array())
    }

    fun press(code: Int) { key(0, code, 0, 0); key(1, code, 0, 0) }

    /** scrcpy starts its capture again: a new codec config and a key frame now, instead of at the next i-frame interval. */
    fun resetVideo() = send(byteArrayOf(17))

    /** The phone app away for a moment: the picture is kept coming but not drawn, so it is back at once ([Screen.pause]). */
    fun pauseScreen() = screen?.pause()
    fun resumeScreen() = screen?.resume()

    /** A finger on the TV screen. action: 0 down, 1 up, 2 move; x, y in 0..1 of the picture. */
    fun touch(action: Int, x: Float, y: Float) {
        val px = Math.round(x.coerceIn(0f, 1f) * (width - 1))
        val py = Math.round(y.coerceIn(0f, 1f) * (height - 1))
        send(ByteBuffer.allocate(32).put(2.toByte()).put(action.toByte()).putLong(-2L).putInt(px).putInt(py)
                .putShort(width.toShort()).putShort(height.toShort()).putShort((if (action == 1) 0 else 0xffff).toShort()).putInt(0).putInt(0).array())
    }

    /** The mouse wheel at x, y (0..1 of the picture); [dy] as a browser's wheel: 16 is one notch, positive scrolls down. */
    fun scroll(x: Float, y: Float, dy: Float) {
        val px = Math.round(x.coerceIn(0f, 1f) * (width - 1))
        val py = Math.round(y.coerceIn(0f, 1f) * (height - 1))
        val v = Math.round(dy / 16 * 0x7FFF).coerceIn(-0x7FFF, 0x7FFF)
        send(ByteBuffer.allocate(21).put(3.toByte()).putInt(px).putInt(py).putShort(width.toShort()).putShort(height.toShort())
            .putShort(0).putShort(v.toShort()).putInt(0).array())
    }

    // ---- a virtual HID mouse (Air Mouse): Android draws its own pointer for it, which touch events never do ----
    // Needs only the control socket (a keys-only session works, no picture). scrcpy v5 UHID_CREATE: type 12, id u16, vendor u16,
    // product u16, name (u8 length), report descriptor (u16 length); UHID_INPUT: type 13, id u16, report (u16 length); UHID_DESTROY: 14, id.
    @Volatile private var mouseOn = false
    // The mouse lives in the scrcpy-server that made it and dies with it. Which server takes our messages changes when the
    // picture comes or goes (carry) or this session rests: the mouse was gone until the next Air Mouse, its reports sent to a
    // server that had none (the "it works, then not" of the Manage screen). Made again in whichever server takes them now.
    @Volatile private var mouseAt: Adb.Stream? = null

    /** Makes the TV's mouse; a second call while it exists does nothing. */
    fun mouseOpen() {
        if (mouseOn) return
        mouseOn = true
        mouseAt = null
        mouseHere()
    }

    private fun mouseHere() {
        val by = carrier
        val at = (if (by != null && by.control != null && !by.stopped) by.control else control) ?: return   // resting: send() wakes it, the next report makes it
        if (at === mouseAt) return
        mouseAt = at
        val name = "TV Remote Air Mouse".toByteArray()
        send(ByteBuffer.allocate(10 + name.size + MOUSE_DESC.size).put(12.toByte()).putShort(MOUSE_ID).putShort(0x18d1.toShort()).putShort(0x4e42.toShort())
            .put(name.size.toByte()).put(name).putShort(MOUSE_DESC.size.toShort()).put(MOUSE_DESC).array())
    }

    // The mouse is fed at the sensor's pace (200 Hz) but ADB takes one message per round trip (each WRTE waits for its OKAY), so
    // one write per report backed up behind a slow Wi-Fi moment and the pointer lagged, then rushed. The reports wait here
    // instead: moves between two ticks are summed, state changes (a button) kept in order, and a pump sends whatever is waiting
    // as ONE write, at most every MOUSE_TICK ms and never before the last write is done. Nothing queues up, nothing is lost.
    private val mouseLock = Object()
    private val mouseOut = java.io.ByteArrayOutputStream()            // finished reports, oldest first
    private var accDx = 0; private var accDy = 0; private var accWheel = 0; private var accBtn = 0
    private var mousePump: Thread? = null
    /** For a test or a measurement: told, once a second, the pump's numbers (reports in, writes out, write ms p50 / max, gap ms p95 / max). */
    @Volatile var mouseStats: ((String) -> Unit)? = null

    private fun reportsFor(dx: Int, dy: Int, wheel: Int, buttons: Int, out: java.io.ByteArrayOutputStream) {
        var x = dx; var y = dy; var w = wheel
        do {                                                          // a report holds -127..127: a longer move is several
            val sx = x.coerceIn(-127, 127); val sy = y.coerceIn(-127, 127); val sw = w.coerceIn(-127, 127)
            out.write(byteArrayOf(13, 0, MOUSE_ID.toByte(), 0, 4, buttons.toByte(), sx.toByte(), sy.toByte(), sw.toByte()))
            x -= sx; y -= sy; w -= sw
        } while (x != 0 || y != 0 || w != 0)
    }

    /** One report: [dx], [dy] in HID counts (the pointer's speed is the TV's), [buttons] bit 0 left, 1 right, 2 middle, [wheel] (positive up). */
    fun mouseMove(dx: Int, dy: Int, buttons: Int = 0, wheel: Int = 0) {
        if (!mouseOn) return
        synchronized(mouseLock) {
            if (buttons != accBtn) {                                  // a press / release lands between the moves before it and after it
                if (accDx != 0 || accDy != 0 || accWheel != 0) { reportsFor(accDx, accDy, accWheel, accBtn, mouseOut); accDx = 0; accDy = 0; accWheel = 0 }
                reportsFor(0, 0, 0, buttons, mouseOut)
                accBtn = buttons
            }
            accDx += dx; accDy += dy; accWheel += wheel
            if (mouseOut.size() > 9 * 400) { mouseOut.reset() }       // a stuck TV: drop what is old rather than send a minute of it later
            if (mousePump == null) mousePump = Thread(::mousePumpLoop, "tv-mouse").apply { isDaemon = true; start() }
            mouseLock.notifyAll()
        }
    }

    private fun mousePumpLoop() {
        val writes = ArrayList<Long>(); val gaps = ArrayList<Long>()
        var reports = 0; var lastStart = 0L; var statAt = System.nanoTime()
        while (mouseOn) {
            val msg: ByteArray
            synchronized(mouseLock) {
                while (mouseOn && mouseOut.size() == 0 && accDx == 0 && accDy == 0 && accWheel == 0) mouseLock.wait(500)
                if (!mouseOn) return
                while (mouseOn) {                                     // let the next moves join this one (the ADB link is the slow part)
                    val wait = lastStart + MOUSE_TICK_MS - System.currentTimeMillis()
                    if (wait <= 0) break
                    mouseLock.wait(wait)
                }
                if (!mouseOn) return
                if (accDx != 0 || accDy != 0 || accWheel != 0) { reportsFor(accDx, accDy, accWheel, accBtn, mouseOut); accDx = 0; accDy = 0; accWheel = 0 }
                msg = mouseOut.toByteArray(); mouseOut.reset()
            }
            if (msg.isEmpty()) continue
            mouseHere()
            val t0 = System.nanoTime()
            if (lastStart != 0L) gaps.add(System.currentTimeMillis() - lastStart)
            lastStart = System.currentTimeMillis(); reports += msg.size / 9
            try { send(msg)?.get(3, java.util.concurrent.TimeUnit.SECONDS) } catch (ignored: Exception) { }   // done = the TV acknowledged
            writes.add((System.nanoTime() - t0) / 1_000_000)
            val st = mouseStats
            if (st != null && System.nanoTime() - statAt > 1_000_000_000L) {
                writes.sort(); gaps.sort()
                st("reports=$reports writes=${writes.size} write_ms p50=${writes[writes.size / 2]} p95=${writes[writes.size * 95 / 100]} max=${writes.last()} gap_ms p50=${gaps.getOrElse(gaps.size / 2) { 0 }} p95=${gaps.getOrElse(gaps.size * 95 / 100) { 0 }} max=${gaps.lastOrNull() ?: 0}")
                writes.clear(); gaps.clear(); reports = 0; statAt = System.nanoTime()
            } else if (st == null && writes.size > 2000) { writes.clear(); gaps.clear() }
        }
    }

    /** Takes the mouse away again (and with it the pointer). */
    fun mouseClose() {
        if (!mouseOn) return
        mouseOn = false
        mouseAt = null
        synchronized(mouseLock) { mouseOut.reset(); accDx = 0; accDy = 0; accWheel = 0; accBtn = 0; mousePump = null; mouseLock.notifyAll() }
        send(byteArrayOf(14, 0, MOUSE_ID.toByte()))
    }

    // ---- the TV's clipboard ----
    private val clipLock = Object()
    private var clipGot = 0                                           // device CLIPBOARD messages so far (guarded by clipLock)
    private var clip = ""

    /** What scrcpy-server tells (DeviceMessage.java): the TV's clipboard, on change and when asked; the rest is skipped. */
    private fun deviceMessages(ctl: Adb.Stream) {
        val head = ByteArray(4)
        while (true) {
            if (ctl.read(head, 0, 1, 0) < 0) return
            when (head[0].toInt()) {
                0 -> {                                                // CLIPBOARD: int length, UTF-8
                    ctl.readFully(head, 0, 4)
                    val n = ByteBuffer.wrap(head).int
                    if (n < 0 || n > (1 shl 20)) { ctl.readAll(); return }
                    val text = ByteArray(n).also { ctl.readFully(it, 0, n) }
                    synchronized(clipLock) { clip = String(text, Charsets.UTF_8); clipGot++; clipLock.notifyAll() }
                }
                1 -> ctl.readFully(ByteArray(8), 0, 8)                // ACK_CLIPBOARD: the sequence of a set
                2 -> { ctl.readFully(head, 0, 4); val n = ByteBuffer.wrap(head).getShort(2).toInt() and 0xffff; ctl.readFully(ByteArray(n), 0, n) }   // UHID_OUTPUT
                else -> { ctl.readAll(); return }                     // a newer server's message: nothing after it can be read
            }
        }
    }

    /**
     * The text on the TV's clipboard ("" when empty), or null when the link is gone. Blocks up to [timeoutMs]. scrcpy's server
     * answers nothing when the clipboard is empty (no clip, or Android 10+ refusing it), so silence on a live link is "".
     */
    fun clipboard(timeoutMs: Long = 2500): String? {
        carrier?.takeIf { it.control != null && !it.stopped }?.let { return it.clipboard(timeoutMs) }   // the answer comes on its socket
        val before = synchronized(clipLock) { clipGot }
        send(byteArrayOf(8, 0))                                       // GET_CLIPBOARD, no copy key pressed first
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(clipLock) {
            while (clipGot == before) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) return if (!stopped && control != null) "" else null
                clipLock.wait(left)
            }
            return clip
        }
    }

    /** Puts [t] on the TV's clipboard; [paste]: and pastes it where the TV's cursor is. */
    fun setClipboard(t: String, paste: Boolean) {
        val raw = t.toByteArray(Charsets.UTF_8)
        send(ByteBuffer.allocate(14 + raw.size).put(9.toByte()).putLong(0L).put((if (paste) 1 else 0).toByte()).putInt(raw.size).put(raw).array())
    }

    fun text(t: String) {
        val raw = t.toByteArray(Charsets.UTF_8)
        val ascii = raw.size == t.length
        if (ascii) {                                                  // typed as key events
            send(ByteBuffer.allocate(5 + raw.size).put(1.toByte()).putInt(raw.size).put(raw).array())
            return
        }
        // accents, CJK...: key events cannot type them, so put the text on the TV clipboard and press Ctrl+V
        setClipboard(t, false)
        val ctrl = 0x3000                                             // META_CTRL_ON | META_CTRL_LEFT_ON
        key(0, 113, 0, ctrl); key(0, 50, 0, ctrl); key(1, 50, 0, ctrl); key(1, 113, 0, 0)
    }

    // ---- apps ----
    /** "package/activity" of every app that shows on the TV's home screen. */
    @Throws(IOException::class)
    fun apps(): List<String> {
        val out = ArrayList<String>()
        val a = adb ?: return out
        val m = Pattern.compile("(?m)^\\s+([\\w.]+/[\\w.\$]+)\\s*\$").matcher(
                a.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"))
        while (m.find()) if (!out.contains(m.group(1)!!)) out.add(m.group(1)!!)
        out.sort()
        return out
    }

    /** Hands a sentence to the TV's own search / Assistant, as if it had been spoken to the TV's remote. */
    /** Looks [query] up on the TV ([TvShell.search]); blocks. "yt" (YouTube), "gs" (the TV's search), or null: nothing took it. */
    fun search(query: String): String? {
        val a = adb ?: return null
        return try { a.shell(TvShell.search(query)).trim().takeIf { it == "yt" || it == "gs" } } catch (e: Exception) { null }
    }

    /** Opens the TV's Settings. The settings key of a remote does nothing on many TVs, the app itself always opens. */
    /**
     * For keys that should open something ([OPENERS]; 176 opens the TV's settings): presses the key and says whether the TV's front
     * window changed. False means the TV got the key and the current screen ignored it, so the person is not left guessing
     * whether the remote works. Blocks for about a second.
     */
    fun pressSeen(code: Int): Boolean {
        val a = adb ?: return true
        return try {
            val front = { a.shell("dumpsys window | grep -m1 mCurrentFocus").trim() }
            val before = front()
            if (code == 176) a.shell("am start -a android.settings.SETTINGS") else press(code)
            Thread.sleep(900)
            before.isEmpty() || front() != before
        } catch (e: Exception) { true }
    }

    /** What is on the TV's screen right now, as a PNG; null when it could not be taken. */
    fun screenshot(): ByteArray? {
        val a = adb ?: return null
        return try { screencap(a) } catch (e: IOException) { null }
    }

    fun settings() {
        val a = adb ?: return
        Thread { try { a.shell("am start -a android.settings.SETTINGS") } catch (ignored: IOException) { } }.start()
    }

    /** Runs a shell command on the TV through this session's ADB connection; "" when not connected. */
    @Throws(IOException::class)
    fun shell(cmd: String): String = adb?.shell(cmd) ?: ""

    /** The ADB connection this session runs on, for helpers beside it (NowPlaying on the phone's remote). */
    fun adb(): Adb = adb ?: throw IOException("not connected")

    /** The ADB connection under this session, for what goes its own way (drawing on the TV's screen: [Draw]). */
    fun link(): Adb? = adb

    fun launch(component: String) {
        val a = adb
        if (a == null || !component.matches(Regex("[\\w.]+/[\\w.\$]+"))) return
        Thread { try { a.shell("am start -n '$component'") } catch (ignored: IOException) { } }.start()
    }
}
