package dev.tvremote.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import dev.tvremote.core.ontv.Notify
import dev.tvremote.core.ontv.SetupNeed

class NotifyTest {
    private fun clean(s: String) = Notify.clean(JSONObject(s))

    @Test fun busyAsksToLeaveTheRemoteAloneThenSaysDoneAndNeverThrows() {
        val sent = ArrayList<JSONObject>()
        val tv = { cmd: String ->
            sent.add(JSONObject(String(B64.decode(Regex("--es n (\\S+)").find(cmd)!!.groupValues[1]), Charsets.UTF_8)))
            "Broadcast completed: result=-1, data=\"" + B64.encode("{\"ok\":true}".toByteArray()) + "\""
        }
        Notify.busy(tv, "vi", "Đang bật Tailscale")
        Notify.busy(tv, "en", null)
        assertTrue(sent[0].getString("text").startsWith("Đang bật Tailscale. Đừng bấm remote"))
        assertEquals(Notify.MAX_SECONDS, sent[0].getInt("seconds"))   // outlasts the job; the next card takes its place
        assertEquals("TV Remote is done", sent[1].getString("title"))
        Notify.busy({ "Broadcast completed: result=0" }, "vi", "x")   // no TV Setup: goes without, no error
    }

    @Test fun cornerIsOneOfFourOrTheDefault() {
        for (c in Notify.CORNERS) assertEquals(c, Notify.corner(c))
        assertEquals("bottom-left", Notify.corner("  Bottom-Left "))
        for (bad in listOf(null, "", "middle", "top", "left-top")) assertEquals("top-right", Notify.corner(bad))
    }

    @Test fun secondsAreWholeAndClamped() {
        assertEquals(5, Notify.seconds(null))
        assertEquals(5, Notify.seconds("abc"))
        assertEquals(5, Notify.seconds(Double.NaN))
        assertEquals(1, Notify.seconds(0))
        assertEquals(1, Notify.seconds(-30))
        assertEquals(60, Notify.seconds(9999))
        assertEquals(10, Notify.seconds("10"))
        assertEquals(3, Notify.seconds(2.6))
        assertEquals(30, Notify.seconds(30))
    }

    @Test fun cleanKeepsWhatTheTvDraws() {
        val o = clean("""{"title":"  Dinner ","text":" Come down ","corner":"bottom-right","seconds":"10"}""")
        assertEquals("Dinner", o.getString("title"))
        assertEquals("Come down", o.getString("text"))
        assertEquals("bottom-right", o.getString("corner"))
        assertEquals(10, o.getInt("seconds"))
    }

    @Test fun cleanFillsDefaultsAndCutsLongWords() {
        val o = clean("""{"text":"${"x".repeat(500)}","title":"${"t".repeat(100)}","corner":"nowhere","seconds":-4}""")
        assertEquals(Notify.MAX_TEXT, o.getString("text").length)
        assertEquals(Notify.MAX_TITLE, o.getString("title").length)
        assertEquals("top-right", o.getString("corner"))
        assertEquals(1, o.getInt("seconds"))
        assertEquals(5, clean("""{"text":"a"}""").getInt("seconds"))
    }

    @Test fun aTitleAloneOrATextAloneIsEnoughButNothingIsNot() {
        assertEquals("", clean("""{"title":"Hi"}""").getString("text"))
        assertEquals("", clean("""{"text":"Hi"}""").getString("title"))
        for (empty in listOf("{}", """{"title":"  ","text":""}""")) try { clean(empty); fail(empty) } catch (e: IllegalArgumentException) { }
    }

    @Test fun sendBroadcastsToTheReceiverWithTheCleanedMessageAsBase64() {
        val asked = ArrayList<String>()
        val answer = B64.encode("""{"ok":true}""".toByteArray())
        Notify.send({ asked.add(it); "Broadcast completed: result=-1, data=\"$answer\"" }, JSONObject("""{"text":"Hello","corner":"top-left","seconds":3}"""))
        val cmd = asked.single()
        assertTrue(cmd, "-n dev.tvremote.setup/.NotifyReceiver --es n " in cmd)
        val sent = JSONObject(String(B64.decode(cmd.substringAfter("--es n ").substringBefore(" ")), Charsets.UTF_8))
        assertEquals("top-left", sent.getString("corner"))
        assertEquals(3, sent.getInt("seconds"))
        assertEquals("Hello", sent.getString("text"))
    }

    @Test fun noAnswerMeansTvSetupIsMissingOrOldAndTheOverlayRefusalIsNamed() {
        val e = try { Notify.send({ if ("versionName" in it) "versionName=0.4.1" else "Broadcast completed: result=0" }, JSONObject("""{"text":"x"}""")); null } catch (e: SetupNeed.Missing) { e }
        assertEquals("setup:old:notify:0.4.2:0.4.1", e!!.message)
        val refused = B64.encode("""{"error":"overlay"}""".toByteArray())
        try { Notify.send({ "data=\"$refused\"" }, JSONObject("""{"text":"x"}""")); fail() } catch (e: java.io.IOException) { assertEquals("notify:nooverlay", e.message) }
    }

    @Test fun speakIsOffUnlessAskedAndAnyYesWordCounts() {
        for (v in listOf(true, "true", " ON ", "1", 1, "yes")) assertTrue("$v", Notify.speak(v))
        for (v in listOf(null, false, "", "false", "0", 0, 2, "maybe", JSONObject.NULL)) assertFalse("$v", Notify.speak(v))
        assertFalse(clean("""{"text":"x"}""").has("speak"))                  // today's message is unchanged, an old TV Setup sees nothing new
        assertFalse(clean("""{"text":"x","speak":false}""").has("speak"))
        assertTrue(clean("""{"text":"x","speak":"1"}""").getBoolean("speak"))
    }

    @Test fun theLanguageIsVietnameseOnlyWithVietnameseLettersElseTheTvs() {
        assertEquals("vi", Notify.speechLang("Cơm xong rồi nhé", "en"))
        assertEquals("vi", Notify.speechLang("Đi ăn", "fr"))
        assertEquals("vi", Notify.speechLang("tới giờ ngủ", "ru"))
        assertEquals("fr", Notify.speechLang("Le dîner est prêt à côté", "fr"))      // â ê ô are French too
        assertEquals("en", Notify.speechLang("Dinner is ready", " EN "))
        assertEquals("ru", Notify.speechLang("Ужин готов", "ru"))
        assertEquals("en", Notify.speechLang("Dinner", ""))
    }

    @Test fun whatIsReadIsTheTitleThenTheText() {
        assertEquals("Dinner. Come down!", Notify.spoken("Dinner", "Come down!"))
        assertEquals("Come down.", Notify.spoken("", "Come down"))
        assertEquals("Dinner.", Notify.spoken(" Dinner ", ""))
        assertEquals("", Notify.spoken("", " "))
    }

    @Test fun readingAloudNeedsTheNewerTvSetupBeforeAnythingIsSent() {
        val sent = ArrayList<String>()
        val e = try { Notify.send({ sent.add(it); if ("versionName" in it) "versionName=0.42.2" else "" }, JSONObject("""{"text":"x","speak":true}""")); null } catch (e: SetupNeed.Missing) { e }
        assertEquals("setup:old:speak:0.42.3:0.42.2", e!!.message)
        assertEquals(1, sent.size)                                      // only the version was asked: no card on a TV that cannot read it
        assertEquals("Đọc thông báo to trên TV", SetupNeed.name("speak", "vi"))
    }

    @Test fun theSendersGetTvSetupsReasonWhyItWasNotRead() {
        val tv = { json: String -> { cmd: String -> if ("versionName" in cmd) "versionName=0.42.3" else "data=\"" + B64.encode(json.toByteArray()) + "\"" } }
        val r = Notify.send(tv("""{"ok":true,"speak":"nolang","lang":"vi"}"""), JSONObject("""{"text":"x","speak":true}"""))
        assertEquals("nolang", r.getString("speak")); assertEquals("vi", r.getString("lang"))
        assertFalse(Notify.send(tv("""{"ok":true}"""), JSONObject("""{"text":"x"}""")).has("speak"))
    }

    @Test fun theFeatureNeedsTheNextTvSetup() {
        assertFalse(SetupNeed.ok("0.4.1", "notify"))
        assertTrue(SetupNeed.ok("0.4.2", "notify"))
        assertEquals("Gửi thông báo lên TV", SetupNeed.name("notify", "vi"))
    }
}
