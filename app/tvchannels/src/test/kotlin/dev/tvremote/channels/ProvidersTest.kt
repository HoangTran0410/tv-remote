package dev.tvremote.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProvidersTest {
    private val docs = File("../../docs/channels").takeIf { it.isDirectory } ?: File("../docs/channels")

    @Test fun theSitesListIsSignedAndRead() {
        val list = File(docs, "providers.json").readBytes()
        assertTrue(dev.tvremote.update.SignedList.verify(list, File(docs, "providers.json.sig").readText()))
        assertFalse(dev.tvremote.update.SignedList.verify(list + ' '.code.toByte(), File(docs, "providers.json.sig").readText()))   // one byte more: refused
        assertEquals(listOf("tv360", "vieon", "vtvgo", "stations"), Providers.parse(String(list)).map { it.id })
    }

    @Test fun offAndUnknownKindsAreLeftOut() {
        val l = """{"v":1,"providers":[{"id":"a","kind":"vieon","country":"VN","on":false},{"id":"b","kind":"later","country":"vn"},
            {"id":"c","kind":"vieon","country":"VN"}]}"""
        assertEquals(listOf("c"), Providers.parse(l).map { it.id })
        assertEquals("vn", Providers.parse(l)[0].country)
        assertTrue(Providers.parse("""{"v":2,"providers":[]}""").isEmpty())
        assertTrue(Providers.parse("not json").isEmpty())
    }

    /** Streams written in the list: a name TV Channels already has becomes that channel's backup (same key), anything not http(s) is dropped. */
    @Test fun streamsFromTheListItself() {
        val p = Providers.parse("""{"v":1,"providers":[{"id":"st","kind":"streams","country":"vn","streams":[
            {"name":"Can Tho TV","url":"https://live.example.vn/tv/playlist.m3u8"},{"name":"Bad","url":"javascript:alert(1)"},
            {"name":"","url":"https://x/a.m3u8"},{"name":"VOV5","url":"https://x/vov5.m3u8","group":"Music"}]}]}""")[0]
        val got = Streams.parse(p)
        assertEquals(listOf("Can Tho TV", "VOV5"), got.map { it.name })
        assertEquals(Providers.key("Cần Thơ TV"), Providers.key(got[0].name))
        assertEquals("cantho.vn@st", got[0].id)
        assertEquals("Music", got[1].group)
        assertFalse(Providers.isLink(got[0].url))                        // played as is, no provider call
    }

    @Test fun namesBothListsAgreeOn() {
        assertEquals(Providers.key("VTV1"), Providers.key("VTV1 HD"))
        assertEquals(Providers.key("Vinh Long TV 1"), Providers.key("Vĩnh Long 1 HD"))
        assertEquals(Providers.key("Đồng Tháp 1"), Providers.key("Dong Thap TV 1"))
        assertFalse(Providers.key("VTV1") == Providers.key("VTV10"))
    }

    @Test fun links() {
        assertTrue(Providers.isLink("vieon:vtv1-hd"))
        assertFalse(Providers.isLink("https://x/a.m3u8"))
        assertFalse(Providers.isLink("http://x/a.m3u8"))
    }

    private val p = Providers.parse(String(File(docs, "providers.json").readBytes())).first { it.kind == "vieon" }

    @Test fun vieonList() {
        val l = Vieon.parseList("""{"items":[{"slug":"vtv1-hd","title":"VTV1 HD","is_premium":0,"images":{"livetv_logo_light":"https://l/1.png"}},
            {"slug":"vtv3-hd","title":"VTV3 HD","is_premium":1},{"slug":"voh-fm-999","title":"VOH FM 99.9 MHz","is_premium":0,"image_link":"https://l/2.png"},
            {"slug":"","title":"x"}]}""", p)!!
        assertEquals(listOf("vtv1-hd.vn@vieon", "voh-fm-999.vn@vieon"), l.map { it.id })
        assertEquals("vn", l[0].country)                                // its country's list finds it again (links, favourites)
        assertEquals("vieon:vtv1-hd", l[0].url)
        assertEquals("https://l/1.png", l[0].logo)
        assertEquals("Music", l[1].group)
        assertNull(Vieon.parseList("oops", p))
    }

    @Test fun vtvgoList() {
        val v = Providers.parse("""{"v":1,"providers":[{"id":"vtvgo","kind":"vtvgo","country":"vn","ids":"2,13,9"}]}""")[0]
        val l = Vtvgo.parseList("""{"data":{"13":{"id":"13","name":"VTV6","logo":"https://l/6.webp","mustLogin":false},
            "2":{"id":"2","name":"VTV2","logo":"https://l/2.webp"},"9":{"id":"9","name":"K+","mustLogin":true}}}""", v)!!
        assertEquals(listOf("VTV2", "VTV6"), l.map { it.name })       // the list's order; a channel that needs an account left out
        assertEquals("vtvgo:13", l[1].url)
        assertEquals("vn", l[1].country)
        assertNull(Vtvgo.parseList("oops", v))
        val w = Providers.parse("""{"v":1,"providers":[{"id":"vtvgo","kind":"vtvgo","country":"vn","ids":"121,1","strip":"^(Truyền hình|Đài PTTH tỉnh) "}]}""")[0]
        assertEquals(listOf("Nghệ An", "Truyền hình"), Vtvgo.parseList("""{"data":{"121":{"id":"121","name":"Truyền hình Nghệ An"},
            "1":{"id":"1","name":"Truyền hình"}}}""", w)!!.map { it.name })   // the other lists' name; a name that is all prefix stays
    }

    @Test fun vtvgoListInParts() {
        val ids = (1..45).joinToString(",")
        val v = Providers.parse("""{"v":1,"providers":[{"id":"vtvgo","kind":"vtvgo","country":"vn","list":"https://x/?c={ids}","ids":"$ids"}]}""")[0]
        val asked = ArrayList<String>()
        val l = Vtvgo.list(v) { u -> val c = u.substringAfter("c=").also { asked.add(it) }.split(',')
            """{"data":{${c.joinToString(",") { """"$it":{"id":"$it","name":"C$it"}""" }}}}""" }
        assertEquals(listOf(20, 20, 5), asked.map { it.split(',').size })   // the API takes 20 at most
        assertEquals((1..45).map { "C$it" }, l.map { it.name })
        assertEquals(emptyList<Channel>(), Vtvgo.list(v) { u -> if ("c=21," in u) null else """{"data":{}}""" })   // a part lost: no half list
    }

    @Test fun vtvgoPlay() {
        val now = 1_792_600_000_000L
        fun src(drm: String, u: String) = """{"drmTypeID":"$drm","drmInfo":{"expire":14410},"manifestType":"hls","sources":[{"url":"$u"}]}"""
        val (urls, until) = Vtvgo.parsePlay("""{"data":{"sourceModes":[{"id":"default","isVip":0,"multiSource":[${src("none", "https://a/s/1/master.m3u8")},
            ${src("widevine", "https://b/x.mpd")},${src("none", "https://c/s/1/playlist.m3u8")}]},{"id":"1080p","isVip":1}]}}""", now)!!
        assertEquals(listOf("https://a/s/1/master.m3u8", "https://c/s/1/playlist.m3u8"), urls)   // DRM sources are not ours to play
        assertEquals(now + 14410_000L - 30 * 60_000L, until)
        assertNull(Vtvgo.parsePlay("""{"data":{"sourceModes":[{"isVip":1,"multiSource":[${src("none", "https://a/v.m3u8")}]}]}}""", now))   // VIP only
        assertNull(Vtvgo.parsePlay("""{"error":{"code":"BAD_REQUEST"}}""", now))
    }

    @Test fun vieonPlay() {
        val now = 1_792_600_000_000L
        val url = "https://cdn.vieon.vn/0123456789abcdef0123456789abcdef/${now + 20 * 3600_000L}/livetv/x/playlist.m3u8?c=a"
        val got = Vieon.parsePlay("""{"id":"x","permission":206,"play_links":{"h264":{"hls":"$url"},"h265":{"hls":"https://h/265.m3u8"}}}""", 206, now)
        assertNotNull(got)
        assertEquals(url, got!!.url)
        assertEquals(now + 6 * 3600_000L, got.until)                    // a day's link: kept at most 6 hours
        val soon = "https://cdn.vieon.vn/0123456789abcdef0123456789abcdef/${now + 2 * 3600_000L}/p.m3u8"
        assertEquals(now + 3600_000L, Vieon.parsePlay("""{"permission":206,"hls_link_play":"$soon"}""", 206, now)!!.until)   // an hour before it runs out
        assertNull(Vieon.parsePlay("""{"id":"x","permission":0,"link_play":""}""", 206, now))   // needs an account
        assertNull(Vieon.parsePlay("<html>", 206, now))
    }

    @Test fun guides() {
        val shows = Tv360.parseGuide("""{"data":{"schedules":[{"name":"Thời sự","epochSt":1000,"epochEt":2000},{"name":"Phim","epochSt":2000,"epochEt":3000},{"name":"","epochSt":3000,"epochEt":4000}]}}""")!!
        assertEquals(listOf("Thời sự", "Phim"), shows.map { it.title })
        assertEquals("Thời sự", Guide.now(shows, 1500)!!.title)
        assertEquals("Phim", Guide.after(shows, 1500)!!.title)
        assertNull(Guide.now(shows, 5000))
        assertEquals(emptyList<Show>(), Tv360.parseGuide("""{"data":{"info":[]}}"""))
        val v = Vieon.parseGuide("""[{"title":"Phim truyện","time_start":1792515600,"time_end":1792517400}]""")!!
        assertEquals(1792515600_000L, v[0].start)                       // seconds there, ms here
        assertNull(Vieon.parseGuide("{}"))
    }

    @Test fun guideIdsFromTheSitesList() {
        val ps = Providers.parse(File(docs, "providers.json").readText())
        val tv360 = ps.first { it.kind == "tv360" }
        assertEquals("4", tv360.guides[Providers.key("VTV3 HD")])        // iptv-org's name finds TV360's id
        assertTrue(tv360.guides.size > 100)
        assertTrue(ps.first { it.kind == "vieon" }.o.optString("guide").contains("{id}"))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(Guide.day(tv360, 1)))
    }

    // census 13b5562e: Back while the list waits on a FutureTask: shutdownNow interrupts it, which ends that job, not the app
    @Test fun aScreenClosedWhileItsJobWaitsDoesNotCrash() {
        val crashed = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val threads = ArrayList<Thread>()                                  // joined: their crash handler runs after the pool counts them done
        val factory = java.util.concurrent.ThreadFactory { r -> Providers.screenThread.newThread(r).also { threads.add(it) } }
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashed.set(e) }
        try {
            for (wrapped in listOf(true, false)) {                         // ThreadPoolExecutor wraps it in an Error; a bare one too
                val io = java.util.concurrent.Executors.newSingleThreadExecutor(factory)
                val waiting = java.util.concurrent.CountDownLatch(1)
                val never = java.util.concurrent.FutureTask { Thread.sleep(60_000); 1 }
                io.execute { waiting.countDown(); if (wrapped) Providers.join(never) else throw InterruptedException() }
                waiting.await(); Thread.sleep(100)
                io.shutdownNow(); threads.forEach { it.join(5000) }
            }
            assertNull(crashed.get())
            val io = java.util.concurrent.Executors.newSingleThreadExecutor(factory)
            io.execute { throw IllegalStateException("a real fault") }       // anything else still crashes as before
            io.shutdown(); threads.forEach { it.join(5000) }
            assertEquals("a real fault", crashed.get()?.message)
        } finally { Thread.setDefaultUncaughtExceptionHandler(before) }
    }
}
