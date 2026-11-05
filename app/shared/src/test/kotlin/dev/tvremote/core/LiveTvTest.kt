package dev.tvremote.core

import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.AdbPrompt
import dev.tvremote.core.picture.Tv
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Against a real TV, only when asked: TVR_LIVE_HOST (its address), TVR_LIVE_KEYS (a folder with adbkey.pk8 / adbkey.x509 the TV
 * already allows). Prints what a phone would get: the sound's codec and packets, the picture's first frame and its gaps, over
 * ADB and through TV Setup (the pass is asked for over ADB, as the phone app does). Skipped in every normal run.
 */
class LiveTvTest {
    private val host = System.getenv("TVR_LIVE_HOST") ?: ""
    private val keys = File(System.getenv("TVR_LIVE_KEYS") ?: "/nonexistent")
    private val jar = { generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "app/shared/assets/scrcpy-server.jar") }.first { it.exists() }.readBytes() }
    private val secs = (System.getenv("TVR_LIVE_SECS") ?: "15").toLong()

    private class Said : Tv.Listener {
        val log = CopyOnWriteArrayList<String>()
        override fun onStatus(key: String, detail: String) { if (key != "rate" && key != "arate") log.add("$key $detail".trim()) }
        override fun onVideoSize(w: Int, h: Int) { log.add("size ${w}x$h") }
        override fun onNetwork(mac: String, lan: String, tailscale: String) {}
        override fun wantsNetwork() = false
    }

    private class Frames : Tv.Screen {
        val at = CopyOnWriteArrayList<Long>()
        @Volatile var start = 0L
        override fun start(w: Int, h: Int) { start = System.nanoTime() }
        override fun feed(config: Boolean, pts: Long, data: ByteArray): Boolean { if (!config) at.add(System.nanoTime()); return true }
        override fun release() {}
        fun report(t0: Long): String {
            if (at.isEmpty()) return "no frames"
            val gaps = at.zipWithNext { a, b -> (b - a) / 1_000_000 }.sorted()
            fun p(q: Double) = if (gaps.isEmpty()) 0 else gaps[minOf(gaps.size - 1, (gaps.size * q).toInt())]
            val long = gaps.count { it > 250 }
            return "frames ${at.size} first ${(at[0] - t0) / 1_000_000} ms, gap p50 ${p(0.5)} p90 ${p(0.9)} p99 ${p(0.99)} max ${gaps.lastOrNull() ?: 0} ms, gaps over 250 ms: $long"
        }
    }

    /** D-pad left / right every 700 ms over a side link, so the picture keeps changing (a still screen sends nothing). */
    private fun moving(stop: () -> Boolean) = Thread {
        val a = Adb(keys); a.connectTo(host) {}
        var k = 22
        while (!stop()) { try { a.shell("input keyevent $k", 5000) } catch (e: Exception) { }; k = if (k == 22) 21 else 22; Thread.sleep(700) }
        a.close()
    }.apply { isDaemon = true; start() }

    private fun picture(relay: String?): String {
        val f = Frames(); val l = Said()
        var done = false
        val t0 = System.nanoTime()
        val tv = Tv(keys, jar, host, f, 2, 960, false, l, relay = relay)     // level 2: 960 px, 2 Mbit/s, 20 fps
        val mover = moving { done }
        Thread.sleep(secs * 1000)
        done = true; tv.stop(); mover.join(3000)
        return (if (relay == null) "ADB      " else "TV Setup ") + f.report(t0) + " | " + l.log.joinToString(", ")
    }

    @Test fun live() {
        assumeTrue("set TVR_LIVE_HOST and TVR_LIVE_KEYS", host.isNotEmpty() && keys.isDirectory)
        // the sound, as the phone's 🎧 asks for it (Opus, AAC where the TV has no Opus encoder)
        run {
            val l = Said(); val codec = CopyOnWriteArrayList<String>(); var cfg = 0; var n = 0; var bytes = 0L
            val sink = object : Tv.AudioSink {
                override fun audio(config: Boolean, data: ByteArray) { if (config) cfg++ else { n++; bytes += data.size } }
                override val aac: Boolean get() = true
                override fun codec(name: String) { codec.add(name) }
            }
            val tv = Tv(keys, jar, host, null, 0, 0, false, l, audio = "opus", audioSink = sink, wantControl = false)
            Thread.sleep(8000); tv.stop()
            println("SOUND    codec $codec config $cfg packets $n (${bytes / 1024} KB in 8 s) | ${l.log.joinToString(", ")}")
        }
        println(picture(null))
        val pass = Adb(keys).let { a -> a.connectTo(host) {}; try { AdbPrompt.token(a, "phone") } finally { a.close() } }
        println("pass ${if (pass == null) "none" else "got (" + (if (pass.contains(':')) "TLS" else "plain") + ")"}")
        if (pass != null) println(picture(pass))
    }
}
