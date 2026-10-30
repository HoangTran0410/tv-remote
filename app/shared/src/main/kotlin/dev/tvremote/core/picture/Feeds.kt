package dev.tvremote.core.picture
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import dev.tvremote.core.web.HttpServer

/**
 * The TV's picture and sound for the page (js/screen.js h264Loop, js/listen.js): one session each on the TV, however many
 * browsers watch or listen (each a scrcpy-server of ~50 MB of its own on the TV, and a hardware encoder it may have only one of). A
 * second browser used to take the picture from the first, which then said the TV sent nothing. Both are packets of 1 byte
 * kind, 4 bytes length, then the data, sent as long as the browser reads them. The session itself is made by the caller
 * ([Tv] on the TV for TV Setup, on the computer for the desktop server).
 */
class Feeds {
    /** The session that carries the picture, only while a browser watches: touches go to it. */
    @Volatile var viewer: Tv? = null
        private set
    @Volatile private var hearer: Tv? = null
    private val watchers = CopyOnWriteArrayList<CatchUp>()
    private val listeners = CopyOnWriteArrayList<Ear>()
    private var videoKey = ""                                         // what the picture's session was asked for (size, fps, ...)
    private var soundKey = ""
    @Volatile private var size: CatchUp.Packet? = null                // what a browser joining needs before the next key frame
    @Volatile private var config: ByteArray? = null
    @Volatile private var opusHead: ByteArray? = null
    @Volatile private var notice: CatchUp.Packet? = null
    @Volatile private var hush: Hush? = null                          // raw PCM's silences left out (Opus makes almost nothing of them)

    /** How many browsers of this server watch and listen now. */
    val watching: Int get() = watchers.size
    val listening: Int get() = listeners.size

    /** The TV's own count ([Watchers]), with the browsers this server serves beyond the first: {watch, listen}, {} unknown. */
    fun count(ps: String): org.json.JSONObject {
        val c = Watchers.parse(ps) ?: return org.json.JSONObject()
        return org.json.JSONObject().put("watch", c.watch + maxOf(0, watching - 1)).put("listen", c.listen + maxOf(0, listening - 1))
    }

    private val end = ByteArray(0)

    private fun packet(kind: Int, data: ByteArray) = CatchUp.Packet(kind, CatchUp.packet(kind, data), 0)
    private val endPacket = CatchUp.Packet(-1, end, 0)

    /**
     * Kinds: 0 size (width, height), 1 codec config, 2 key frame, 3 frame, 4 nothing new, 5 it fell behind and caught up
     * ([CatchUp]: the page steps the picture down after a few). A still picture sends nothing, so kind 4 goes out now and
     * then: that is how we notice the browser has left, and stop the encoder on the TV once the last one has.
     * [key]: what [make] asks for. A browser asking the same joins the running session (a key frame is asked for it); one
     * asking otherwise (a quality picked, or stepped down) restarts it, and every browser goes on with the new picture.
     */
    @Synchronized fun video(key: String, make: (Tv.Listener, Tv.Sink) -> Tv): HttpServer.Response {
        val c = CatchUp(LAG_MS) { viewer?.resetVideo() }
        val running = viewer?.takeIf { key == videoKey && !it.stopped() }
        if (running != null) {
            size?.let { c.put(it) }
            config?.let { c.add(1, it, System.currentTimeMillis()) }
            c.fromKey()
            watchers += c
            try { running.resetVideo() } catch (e: Exception) { }
        } else {
            watchers += c
            startVideo(key, make)
        }
        // the system grows a socket's send buffer to megabytes: seconds of picture waiting where CatchUp cannot see them
        return pump("v", c.q) { synchronized(this) { watchers -= c; if (watchers.isEmpty()) { viewer?.stop(); viewer = null } } }
            .apply { sendBuffer = 64 * 1024 }
    }

    private fun startVideo(key: String, make: (Tv.Listener, Tv.Sink) -> Tv) {
        viewer?.stop()                                                // its "closed" is not ours to pass on: see mine()
        videoKey = key; size = null; config = null
        var self: Tv? = null
        val mine = { self != null && viewer === self }
        val v = make(object : Tv.Listener {
            override fun onStatus(key: String, detail: String) { if ((key == "closed" || key == "error") && mine()) watchers.forEach { it.put(endPacket) } }
            override fun onVideoSize(w: Int, h: Int) {
                val p = packet(0, ByteBuffer.allocate(8).putInt(w).putInt(h).array())
                size = p; watchers.forEach { it.put(p) }
            }
            override fun onNetwork(mac: String, lan: String, tailscale: String) { }
        }, Tv.Sink { cfg, kf, data ->
            if (cfg) config = data
            val now = System.currentTimeMillis()
            for (c in watchers) {
                if (c.q.size > 150) { if (c.q.size < 160) c.put(endPacket) }   // this browser does not read (no key frame came): it goes, not the others
                else c.add(if (cfg) 1 else if (kf) 2 else 3, data, now)
            }
        })
        self = v
        viewer = v
    }

    /** One browser's sound: its queue, and whether it takes the sound as captured or made smaller ([Downmix]). */
    private class Ear(val q: LinkedBlockingQueue<CatchUp.Packet>, val mix: Downmix?)

    /**
     * Kinds: 0 the format (codec 1 Opus or 2 PCM, then the rate and the channels), 1 Opus's header, 2 a piece of sound, 3 a
     * notice ("noshare": the TV's speakers go silent meanwhile; "tvsilent" / "tvloud": the private way did / did not turn the TV's volume to 0, Android 13+), 4 nothing new, 5 an error word, 6 the TV went silent
     * (raw only: nothing is sent until it is not, [Hush]). With [opus] the session is
     * asked for "opus" (a browser on a secure page decodes it: the computer's 127.0.0.1); otherwise for "raw" and the TV
     * encodes nothing: scrcpy hands over PCM, made 24 kHz mono here for each browser ([Downmix], 48 KB/s) unless [hq] (as
     * captured, 48 kHz stereo, fine on the home network). [key]: what [make] asks for; one session for all asking the same.
     */
    @Synchronized fun sound(key: String, hq: Boolean, opus: Boolean, make: (Tv.Listener, Tv.AudioSink) -> Tv): HttpServer.Response {
        val q = LinkedBlockingQueue<CatchUp.Packet>()
        val whole = opus || hq
        q.add(packet(0, ByteBuffer.allocate(6).put((if (opus) 1 else 2).toByte()).putInt(if (whole) 48000 else 24000).put((if (whole) 2 else 1).toByte()).array()))
        val ear = Ear(q, if (whole) null else Downmix())
        val running = hearer?.takeIf { key == soundKey && !it.stopped() }
        if (running != null) {
            opusHead?.let { q.add(packet(1, it)) }
            notice?.let { q.add(it) }
            if (hush?.held == true) q.add(packet(6, end))
            listeners += ear
        } else {
            listeners += ear
            startSound(key, opus, make)
        }
        return pump("a", q) { synchronized(this) { listeners -= ear; if (listeners.isEmpty()) { hearer?.stop(); hearer = null } } }
    }

    private fun startSound(key: String, opus: Boolean, make: (Tv.Listener, Tv.AudioSink) -> Tv) {
        hearer?.stop()                                                // a browser asking another codec: every listener goes on with the new one
        soundKey = key; opusHead = null; notice = null
        val quiet = if (opus) null else Hush()
        hush = quiet
        var self: Tv? = null
        val mine = { self != null && hearer === self }
        val h = make(object : Tv.Listener {
            override fun onStatus(key: String, detail: String) {
                if (!mine()) return
                if (key == "audio") { val p = if (detail == "noshare" || detail == "tvsilent" || detail == "tvloud") packet(3, detail.toByteArray()) else packet(5, "unsupported".toByteArray()); notice = p; listeners.forEach { it.q.add(p) } }
                if (key == "closed" || key == "error") listeners.forEach { if (key == "error") it.q.add(packet(5, detail.toByteArray())); it.q.add(endPacket) }
            }
            override fun onVideoSize(w: Int, h: Int) { }
            override fun onNetwork(mac: String, lan: String, tailscale: String) { }
        }, Tv.AudioSink { config, data ->
            if (config) { if (opus) { opusHead = data; listeners.forEach { it.q.add(packet(1, data)) } }; return@AudioSink }
            when (quiet?.feed(data)) {
                Hush.HELD -> return@AudioSink
                Hush.START -> { val p = packet(6, end); listeners.forEach { it.q.add(p) }; return@AudioSink }
            }
            val now = System.currentTimeMillis()
            for (e in listeners) {
                if (e.q.size > 200) { if (e.q.size < 210) e.q.add(endPacket) }   // this browser cannot keep up: it goes, not the others
                else e.q.add(CatchUp.Packet(2, CatchUp.packet(2, e.mix?.feed(data) ?: data), now))
            }
        })
        self = h
        hearer = h
    }

    private fun pump(tag: String, q: LinkedBlockingQueue<CatchUp.Packet>, done: () -> Unit) =
        HttpServer.Response.stream("application/octet-stream", -1) { out ->
            try {
                while (true) {
                    val p = q.poll(5, TimeUnit.SECONDS) ?: packet(4, end)
                    if (p === endPacket) break
                    out.write(p.bytes)
                    out.flush()
                    if (Trace.on && p.at > 0) Trace.log("w", "$tag kind=${p.kind} age=${System.currentTimeMillis() - p.at} n=${p.bytes.size - 5}")
                }
            } finally {
                done()
            }
        }

    @Synchronized fun stop() {
        viewer?.stop(); viewer = null
        hearer?.stop(); hearer = null
        watchers.forEach { it.put(endPacket) }
        listeners.forEach { it.q.add(endPacket) }
    }

    companion object {
        /** How far behind the TV the picture may fall before it jumps to now: 6 frames at the usual 10 a second */
        const val LAG_MS = 600L
    }
}
