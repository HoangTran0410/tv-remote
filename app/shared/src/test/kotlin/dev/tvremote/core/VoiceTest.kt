package dev.tvremote.core

import dev.tvremote.core.scenes.Voice.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import dev.tvremote.core.scenes.Phrases
import dev.tvremote.core.scenes.Voice
import org.json.JSONObject
import java.io.File

/** The same cases as app/shared/tests/voice.test.js: the page and the phone app must understand the same sentences. */
class VoiceTest {
    private val apps = mapOf("com.google.android.youtube.tv" to "YouTube", "com.netflix.ninja" to "Netflix", "com.fptplay.tv" to "FPT Play", "org.videolan.vlc" to "VLC")
    private val scenes = mapOf("s1" to "Đi ngủ", "s2" to "Xem phim")
    private fun p(t: String) = Voice.parse(t, apps, scenes)
    @Before fun words() { Voice.phrases = Phrases(File("web/voice/phrases.json").readText()) }     // the packed list: what the apps start with

    /** tests/voice_cases.json is read by voice.test.js too: the page and the phone must understand the same sentences. */
    private fun show(c: Command?): String = when (c) {
        is Command.Pin -> "pin:${c.key}"; is Command.Setting -> "setting:${c.id}"; is Command.Choice -> "choice:" + c.options.joinToString("|") { show(it) }
        null -> "null"; is Command.Key -> "key:${c.key}:${c.times}"; is Command.App -> "app:${c.pkg}"; is Command.Close -> "close:${c.pkg}"
        is Command.Channel -> "channel:${c.name}"; is Command.Timer -> "timer:${c.minutes}"; is Command.Scene -> "scene:${c.id}"
        is Command.Search -> "search:${c.pkg}:${c.query}"
    }
    @Test fun sentenceTable() {
        val t = JSONObject(File("tests/voice_cases.json").readText())
        val a = t.getJSONObject("apps").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        val sc = t.getJSONObject("scenes").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
        val pj = t.getJSONArray("pins")
        val pins = (0 until pj.length()).map { pj.getJSONObject(it) }.map { Voice.PinRef(it.getString("key"), it.getString("title"), it.optString("app"), it.optString("link").ifEmpty { null }) }
        val cases = t.getJSONArray("cases")
        assertTrue(cases.length() >= 40)
        for (i in 0 until cases.length()) { val c = cases.getJSONArray(i); assertEquals(c.getString(0), c.getString(1), show(Voice.parse(c.getString(0), a, sc, pins))) }
    }

    @Test fun closeNeedsAdbSaidInThePreview() {
        assertEquals(Voice.Say("talk.close_app", mapOf("name" to "YouTube")), Voice.preview("tắt youtube", apps, scenes)!!.say)
        assertEquals(Voice.Say("talk.close_needs_adb", mapOf("name" to "YouTube")), Voice.preview("tắt youtube", apps, scenes, adb = false)!!.say)
        assertEquals(Command.Close("com.google.android.youtube.tv", "YouTube"), Voice.preview("tắt youtube", apps, scenes, adb = false)!!.command)
        assertNull(Voice.parse("tắt tv home", apps + ("dev.tvremote.home" to "TV Home"), scenes))      // never TV Home / TV Setup
    }

    @Test fun fold() {
        assertEquals("mo youtube", Voice.fold("  Mở YouTube! "))
        assertEquals("dat hen gio", Voice.fold("Đặt hẹn giờ"))
    }

    @Test fun wantsApp() {
        assertTrue(Voice.wantsApp("Mở YouTube!")); assertTrue(Voice.wantsApp("tắt youtube")); assertTrue(Voice.wantsApp("open the app")); assertTrue(Voice.wantsApp("chạy ứng dụng zing tv"))
        assertFalse(Voice.wantsApp("tăng âm lượng")); assertFalse(Voice.wantsApp("mở")); assertFalse(Voice.wantsApp(""))
    }

    @Test fun apps() {
        assertEquals(Command.App("com.google.android.youtube.tv", "YouTube"), p("mở YouTube"))
        assertEquals("com.netflix.ninja", (p("mo netflix") as Command.App).pkg)
        assertEquals("com.fptplay.tv", (p("Xem FPT Play") as Command.App).pkg)
        assertEquals("org.videolan.vlc", (p("open vlc") as Command.App).pkg)
        assertEquals("com.google.android.youtube.tv", (p("mở ứng dụng youtube") as Command.App).pkg)
    }

    @Test fun wholeNameOnly() {
        fun w(t: String) = Voice.whole(t, p(t))
        assertTrue(w("mở YouTube")); assertTrue(w("mở ứng dụng youtube")); assertTrue(w("FPT Play")); assertTrue(w("open vlc"))
        assertFalse(w("mở you")); assertFalse(w("mở fpt")); assertFalse(w("tắt tiếng")); assertFalse(w("thời tiết hôm nay"))
    }

    @Test fun unknownGoesToTheAssistant() {
        assertNull(p("mở Spotify"))
        assertNull(p("thời tiết hôm nay"))
        assertNull(p(""))
    }

    @Test fun channels() {
        for (t in listOf("mở kênh vtv1", "Xem kênh VTV1", "chuyển kênh vtv1", "chuyển sang kênh vtv1", "bật kênh vtv1", "kênh vtv1", "open channel vtv1", "watch channel vtv1", "channel vtv1", "tune to vtv1"))
            assertEquals(t, Command.Channel("vtv1"), p(t))
        assertEquals(Command.Channel("the thao"), p("mở kênh Thể thao"))
        assertEquals(Command.Key("CH_UP", 1), p("kênh tiếp"))
        assertEquals(Command.Key("CH_UP", 1), p("next channel"))
        assertEquals(Command.Key("CH_DOWN", 1), p("kênh trước"))
        assertEquals(Command.Key("CH_DOWN", 1), p("previous channel"))
        assertEquals("tvchannels://find/the%20thao", Voice.channelLink("the thao"))
    }

    @Test fun keys() {
        assertEquals(Command.Key("MUTE", 1), p("tắt tiếng"))
        assertEquals(Command.Key("PLAY_PAUSE", 1), p("Tạm dừng"))
        assertEquals(Command.Key("HOME", 1), p("về màn hình chính"))
        assertEquals(Command.Key("SLEEP", 1), p("tắt TV"))
        assertEquals(Command.Key("WAKE", 1), p("bật tivi"))
        assertEquals(Command.Key("WAKE", 1), p("bật tv"))
    }

    @Test fun volume() {
        assertEquals(Command.Key("VOL_UP", 3), p("tăng âm lượng"))
        assertEquals(Command.Key("VOL_DOWN", 5), p("giảm âm lượng 5"))
        assertEquals(Command.Key("VOL_UP", 15), p("volume up 99"))
    }

    @Test fun timer() {
        assertEquals(Command.Timer(30), p("hẹn giờ tắt 30 phút"))
        assertEquals(Command.Timer(120), p("tắt TV sau 2 tiếng"))
        assertEquals(Command.Timer(45), p("sleep in 45 minutes"))
        assertEquals(Command.Timer(0), p("hủy hẹn giờ"))
    }

    @Test fun scenes() {
        assertEquals(Command.Scene("s1"), p("đi ngủ"))
        assertEquals(Command.Scene("s2"), p("cảnh xem phim"))
    }

    @Test fun previewSaysWhatItWouldDo() {
        fun say(t: String) = Voice.preview(t, apps, scenes)!!.say
        assertEquals(Voice.Say("talk.k_mute"), say("tắt tiếng"))
        assertEquals(Voice.Say("talk.k_home"), say("ra màn hình chính"))
        assertEquals(Voice.Say("talk.s_settings"), say("mở cài đặt"))
        assertEquals(Voice.Say("talk.k_vol_up_n", mapOf("n" to "3")), say("tăng âm lượng"))
        assertEquals(Voice.Say("talk.k_vol_down_n", mapOf("n" to "5")), say("giảm âm lượng 5"))
        assertEquals(Voice.Say("talk.open_app", mapOf("name" to "YouTube")), say("mở youtube"))
        assertEquals(Voice.Say("talk.channel", mapOf("name" to "vtv1")), say("mở kênh vtv1"))
        assertEquals(Voice.Say("talk.timer", mapOf("n" to "90")), say("hẹn giờ tắt 90 phút"))
        assertEquals(Voice.Say("talk.timer_off"), say("hủy hẹn giờ"))
        assertEquals(Voice.Say("talk.scene", mapOf("name" to "Xem phim")), say("xem phim"))
        assertEquals(Voice.Say("talk.ask", mapOf("sentence" to "thời tiết hôm nay")), say(" thời tiết hôm nay "))
    }

    @Test fun previewAgreesWithParseAndIsNullWhenNothingHeard() {
        for (t in listOf("tắt tiếng", "mở netflix", "mở spotify", "tăng âm lượng 4", "bài tiếp", "đi ngủ")) {
            assertEquals(p(t), Voice.preview(t, apps, scenes)!!.command)
        }
        assertNull(Voice.preview("", apps, scenes)); assertNull(Voice.preview("  !! ", apps, scenes))
        assertNull(Voice.preview("mở spotify", apps, scenes)!!.command)             // not known: the TV's search, said so
    }

    @Test fun searchInAnApp() {
        val c = p("mở vtv3 trên fpt play") as Command.Search
        assertEquals("https://fptplay.vn/tim-kiem/vtv3", c.url)
        assertEquals("https://www.youtube.com/results?search_query=S%C6%A1n%20T%C3%B9ng", (p("Mở Sơn Tùng trên YouTube") as Command.Search).url)
        assertNull((p("open cats on netflix") as Command.Search).url)
        assertEquals("net.fptplay.ottbox", (Voice.parse("mở vtv3 trên fpt play", emptyMap(), scenes) as Command.Search).pkg)
        assertEquals(Voice.Say("talk.search_in", mapOf("name" to "FPT Play", "q" to "vtv3")), Voice.preview("mở vtv3 trên fpt play", apps, scenes)!!.say)
    }
}
