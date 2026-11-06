package dev.tvremote.core.ontv

import dev.tvremote.core.B64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelsManageTest {
    @Test fun sendsTheStateAndReadsTheAnswer() {
        var cmd = ""
        val answer = B64.encode("""{"version":"0.2.0","favs":[],"groups":[]}""".toByteArray())
        val r = Channels.manage({ cmd = it; "Broadcast completed: result=-1, data=\"$answer\"" }, JSONObject("""{"favs":["a"]}"""), "vn", true, true)
        assertTrue(cmd.contains("-n dev.tvremote.channels/.ChannelsReceiver"))
        assertTrue(cmd.contains("--es list vn") && cmd.contains("--ez fresh true") && cmd.contains("--ez countries true") && cmd.contains("--es set "))
        assertTrue(r.getBoolean("receiver") && r.getBoolean("installed"))
    }

    @Test fun anOldTvChannelsHasNoReceiver() {
        val r = Channels.manage({ if (it.startsWith("am ")) "Broadcast completed: result=0" else "versionName=0.1.0" }, null)
        assertFalse(r.getBoolean("receiver"))
        assertTrue(r.getBoolean("installed"))
    }

    @Test(expected = IllegalArgumentException::class) fun aCountryIsTwoLetters() { Channels.manage({ "" }, null, "v n; rm") }

    @Test fun countsEachCountrysChannelsOnce() {
        val m3u = """#EXTM3U
#EXTINF:-1 tvg-id="VTV1.vn@SD" group-title="Vietnam",VTV1
http://a
#EXTINF:-1 tvg-id="VTV1.vn@HD" group-title="Vietnam",VTV1 HD
http://b
#EXTINF:-1 tvg-id="" group-title="Vietnam",Local
http://c
#EXTINF:-1 tvg-id="CNN.us" group-title="United States",CNN
http://d
#EXTINF:-1 tvg-id="X.int" group-title="International",X
http://e""".lineSequence()
        assertEquals(mapOf("vn" to 2, "us" to 1), Channels.count(m3u, mapOf("Vietnam" to "vn", "United States" to "us")))
    }

    @Test fun oldIsToldApartFromMissing() {
        val no = "Error: Activity not started, unable to resolve Intent"
        assertEquals(Channels.Play.OK, Channels.verdict("Starting: Intent { }") { throw AssertionError("asked") })
        assertEquals(Channels.Play.OLD, Channels.verdict(no) { true })
        assertEquals(Channels.Play.MISSING, Channels.verdict(no) { false })
        assertEquals(Channels.Play.OLD, Channels.playResult({ if (it.startsWith("pm list")) "package:" + Channels.PKG else if (it.startsWith("pm ")) "package:/data/app/x/base.apk" else no }, "http://a.b/c.m3u8"))
        assertEquals(Channels.Play.MISSING, Channels.playResult({ if (it.startsWith("pm ")) "" else no }, "http://a.b/c.m3u8"))
    }

    @Test fun playsAStreamAddressThroughAnEncodedLink() {
        var cmd = ""
        assertTrue(Channels.play({ cmd = it; "Starting: Intent { dat=tvchannels://url... }" }, "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8?a=b&c='d'"))
        assertTrue(cmd.contains("-d 'tvchannels://url?u=https%3A%2F%2Ftest-streams.mux.dev%2Fx36xhzz%2Fx36xhzz.m3u8%3Fa%3Db%26c%3D%27d%27' dev.tvremote.channels"))
        assertFalse(Channels.play({ if (it.startsWith("pm ")) "" else "Error: Activity not started, unable to resolve Intent" }, "http://a.b/c.m3u8"))   // not on the TV: pm answers nothing
    }

    @Test fun refusesWhatIsNotAPlainWebAddressBeforeAnyShell() {
        for (bad in listOf("file:///sdcard/a.mp4", "javascript:alert(1)", "http://", "https://a b/c", "ftp://x/y", "https://h/\nreboot", ""))
            try { Channels.play({ throw AssertionError("shell reached for $bad") }, bad); throw AssertionError("accepted $bad") }
            catch (e: IllegalArgumentException) { assertEquals("channels:bad_link", e.message) }
    }

    @Test fun onlyMediaLinksGoToThePlayer() {
        for (u in listOf("https://a.b/live/index.m3u8", "http://x.vn/v.MP4?token=1", "https://cdn.x/a/b.mpd"))
            assertTrue(u, Channels.isStream(u))
        for (u in listOf("https://youtu.be/abc", "https://x.vn/page.html", "market://details?id=a", "https://x.vn/m3u8", "ftp://x/a.mp4", "https://x.vn/a b.mp4"))
            assertFalse(u, Channels.isStream(u))
    }
}
