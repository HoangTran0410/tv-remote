package dev.tvremote.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.tvremote.core.web.HttpServer
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.ontv.SetupNeed

class ScenesTest {
    /** Records what the steps asked of the TV. */
    private class Fake(val failApp: Boolean = false) : Scenes.Target {
        val did = ArrayList<String>()
        override fun key(code: Int) { did.add("key $code") }
        override fun app(pkg: String) { if (failApp) throw IllegalArgumentException("no such app"); did.add("app $pkg") }
        override fun link(url: String) { did.add("link $url") }
        override fun volume(level: Int) { did.add("volume $level") }
        override fun timer(minutes: Int) { did.add("timer $minutes") }
        override fun input(id: String) { did.add("input $id") }
        override fun notify(msg: JSONObject) { did.add("notify " + msg.getString("text") + " " + msg.getString("corner") + " " + msg.getInt("seconds")) }
    }

    private fun steps(vararg s: String) = JSONArray(s.map { JSONObject(it) })

    @Test fun runsStepsInOrder() {
        val tv = Fake()
        assertNull(Scenes.run(steps("""{"do":"wake"}""", """{"do":"app","pkg":"com.a.b"}""", """{"do":"volume","level":12}""",
            """{"do":"key","key":"mute"}""", """{"do":"timer","min":30}""", """{"do":"sleep"}"""), tv))
        assertEquals(listOf("key 224", "app com.a.b", "volume 12", "key 164", "timer 30", "key 223"), tv.did)
    }

    @Test fun aNotifyStepSendsTheCleanedMessage() {
        val tv = Fake()
        assertNull(Scenes.run(steps("""{"do":"notify","text":"Dinner","corner":"bottom-left","seconds":500}""", """{"do":"notify","text":"x","corner":"??"}"""), tv))
        assertEquals(listOf("notify Dinner bottom-left 60", "notify x top-right 5"), tv.did)
    }

    @Test fun cleanKeepsNotifyStepsWithSaneValuesAndDropsEmptyOnes() {
        val out = Scenes.clean(JSONArray().put(JSONObject("""{"name":"Call","steps":[
            {"do":"notify","title":" Hi ","text":"Dinner","corner":"nope","seconds":0},{"do":"notify","text":"  "},{"do":"notify"}]}""")))
        val st = out.getJSONObject(0).getJSONArray("steps")
        assertEquals(1, st.length())
        val n = st.getJSONObject(0)
        assertEquals("Hi", n.getString("title"))
        assertEquals("top-right", n.getString("corner"))
        assertEquals(1, n.getInt("seconds"))
    }

    @Test fun aTargetThatCannotShowAMessageNamesTheStep() {
        val bare = object : Scenes.Target {
            override fun key(code: Int) {}
            override fun app(pkg: String) {}
            override fun link(url: String) {}
            override fun volume(level: Int) {}
            override fun timer(minutes: Int) {}
        }
        assertTrue(Scenes.run(steps("""{"do":"notify","text":"x"}"""), bare)!!.startsWith("1: "))
    }

    @Test fun aFailingStepIsNamedAndTheRestRuns() {
        val tv = Fake(failApp = true)
        val failed = Scenes.run(steps("""{"do":"app","pkg":"com.a.b"}""", """{"do":"key","key":"HOME"}"""), tv)
        assertTrue(failed!!.startsWith("1: no such app"))
        assertEquals(listOf("key 3"), tv.did)
    }

    @Test fun stopIsAskedBetweenSteps() {
        val tv = Fake()
        Scenes.run(steps("""{"do":"wake"}""", """{"do":"sleep"}"""), tv) { tv.did.size == 1 }
        assertEquals(listOf("key 224"), tv.did)
    }

    @Test fun cleanKeepsOnlyWhatCanRun() {
        val out = Scenes.clean(JSONArray().put(JSONObject("""{"id":"s1","name":" Xem phim ","icon":"🎬","steps":[
            {"do":"wake"},{"do":"app","pkg":"bad; rm -rf /"},{"do":"key","key":"nope"},{"do":"volume","level":300},
            {"do":"link","url":"javascript"},{"do":"link","url":"https://youtu.be/x"},{"do":"explode"}]}""")))
        assertEquals(1, out.length())
        val sc = out.getJSONObject(0)
        assertEquals("Xem phim", sc.getString("name"))
        val st = sc.getJSONArray("steps")
        assertEquals(listOf("wake", "volume", "link"), (0 until st.length()).map { st.getJSONObject(it).getString("do") })
        assertEquals(100, st.getJSONObject(1).getInt("level"))
        assertEquals("https://youtu.be/x", st.getJSONObject(2).getString("url"))
    }

    @Test fun cleanDropsNamelessOrEmptyScenesAndBadIds() {
        val out = Scenes.clean(JSONArray("""[{"name":"","steps":[{"do":"wake"}]},{"name":"x","steps":[]},{"id":"../x","name":"y","steps":[{"do":"sleep"}]}]"""))
        assertEquals(1, out.length())
        assertTrue(out.getJSONObject(0).getString("id").matches(Regex("s[0-9a-z]+")))
    }

    @Test fun backupIsADownloadThatReadsBack() {
        val scenes = Scenes.clean(JSONArray("""[{"id":"s1","name":"Đi ngủ","icon":"🌙","steps":[{"do":"timer","min":30}]}]"""))
        val r = Scenes.backup(scenes)
        assertTrue(r.headers["Content-Disposition"]!!.startsWith("attachment; filename=\"tv-remote-scenes-"))
        val file = JSONObject(String(r.body, Charsets.UTF_8))
        assertEquals("scenes", file.getString("kind"))
        assertEquals(scenes.toString(), Scenes.clean(file.getJSONArray("scenes")).toString())
    }

    @Test fun launchUsesTheLauncherActivity() {
        val asked = ArrayList<String>()
        Scenes.launch({ asked.add(it); if (it.contains("resolve-activity")) "priority=0\ncom.a.b/.Main\n" else "" }, "com.a.b")
        assertTrue(asked.last().startsWith("am start -n 'com.a.b/.Main'"))
    }

    // ---- runRequest: the one /api/scene/run of the phone, TV Setup and the computer
    private val saved = JSONObject("""{"id":"s1","name":"Night","steps":[{"do":"wake"},{"do":"key","key":"nope"},{"do":"volume","level":9}]}""")
    private val find = { id: String -> if (id == "s1") saved else null }
    private fun body(r: dev.tvremote.core.web.HttpServer.Response) = JSONObject(String(r.body, Charsets.UTF_8))

    @Test fun runRequestRunsASavedSceneByIdWithItsStepsCleaned() {
        val tv = Fake()
        val r = Scenes.runRequest(JSONObject().put("id", "s1"), find, "off") { tv }
        assertEquals(200, r.status)
        assertEquals(true, body(r).getBoolean("ok"))
        assertEquals(listOf("key 224", "volume 9"), tv.did)               // the unknown key was cleaned away
    }

    @Test fun runRequestRunsStepsOfItsOwnAndStepsWinOverTheId() {
        val tv = Fake()
        Scenes.runRequest(JSONObject().put("id", "s1").put("steps", steps("""{"do":"key","key":"home"}""")), find, "off") { tv }
        assertEquals(listOf("key 3"), tv.did)
    }

    @Test fun runRequestNamesTheFailedStepsInAnOkAnswer() {
        val r = body(Scenes.runRequest(JSONObject().put("steps", steps("""{"do":"app","pkg":"com.a.b"}""")), find, "off") { Fake(failApp = true) })
        assertEquals(false, r.getBoolean("ok"))
        assertTrue(r.getString("failed").startsWith("1: no such app"))
    }

    @Test fun runRequestUnknownSceneIs404BeforeTheTvIsAsked() {
        var asked = false
        val r = Scenes.runRequest(JSONObject().put("id", "zzz"), find, "off") { asked = true; Fake() }
        assertEquals(404, r.status)
        assertEquals(false, asked)
        assertEquals(404, Scenes.runRequest(JSONObject(), find, "off") { Fake() }.status)
    }

    @Test fun runRequestWithNoTvIs503WithTheCoresWords() {
        val r = Scenes.runRequest(JSONObject().put("id", "s1"), find, "adb:closed") { null }
        assertEquals(503, r.status)
        assertEquals("adb:closed", body(r).getString("error"))
    }

    @Test fun runRequestTurnsATimerOnAMissingTvSetupIntoTheInstallQuestion() {
        val tv = object : Scenes.Target by Fake() {
            override fun timer(minutes: Int) { throw SetupNeed.Missing("rules", "") }
        }
        val r = Scenes.runRequest(JSONObject().put("steps", steps("""{"do":"timer","min":30}""")), find, "off") { tv }
        assertEquals(409, r.status)
        assertTrue(body(r).getString("error").startsWith("setup:missing:rules:"))
    }

    @Test(expected = IllegalArgumentException::class) fun launchRefusesBadPackageNames() { Scenes.launch({ "" }, "x; reboot") }

    @Test fun volumeFallsBackToTheOldCommand() {
        val asked = ArrayList<String>()
        Scenes.volume({ asked.add(it); if (it.startsWith("cmd media_session")) "Unknown command" else "" }, 7)
        assertTrue(asked[1].startsWith("media volume --stream 3 --set 7"))
    }

    @Test fun volumePressesKeysWhenTheTvKeepsItsLevel() {
        val asked = ArrayList<String>()
        Scenes.volume({ asked.add(it); if (it.contains("--get")) "[V] volume is 6 in range [0..15]" else "" }, 4)
        assertEquals("input keyevent 25; input keyevent 25", asked.last())
    }

    @Test fun inputStepRunsAndIsCleaned() {
        val tv = Fake()
        assertNull(Scenes.run(steps("""{"do":"input","id":"com.a/.B/HW5"}"""), tv))
        assertEquals(listOf("input com.a/.B/HW5"), tv.did)
        val kept = Scenes.clean(JSONArray("""[{"name":"Game","steps":[{"do":"input","id":"HDMI_1","label":"HDMI 1 · PS5"},{"do":"input","id":"x; reboot"},{"do":"input"}]}]"""))
        val st = kept.getJSONObject(0).getJSONArray("steps")
        assertEquals(1, st.length()); assertEquals("HDMI_1", st.getJSONObject(0).getString("id")); assertEquals("HDMI 1 · PS5", st.getJSONObject(0).getString("label"))
    }

    @Test fun targetsWithoutInputsSayNo() {
        val t = object : Scenes.Target {
            override fun key(code: Int) {}; override fun app(pkg: String) {}; override fun link(url: String) {}; override fun volume(level: Int) {}; override fun timer(minutes: Int) {}
        }
        assertTrue(Scenes.run(steps("""{"do":"input","id":"HDMI_1"}"""), t)!!.contains("cannot switch inputs"))
    }

    // ---- Routes: the one /api/scenes + /api/scene/run of every core
    private fun req(method: String, path: String, json: JSONObject? = null, query: Map<String, String> = emptyMap()): dev.tvremote.core.web.HttpServer.Request {
        val r = dev.tvremote.core.web.HttpServer.Request(); r.method = method; r.path = path; r.query.putAll(query)
        json?.toString()?.toByteArray()?.let { r.body = java.io.ByteArrayInputStream(it); r.length = it.size.toLong() }
        return r
    }

    @Test fun routesSaveCleanListAndBackUpScenes() {
        var store = JSONArray()
        val tv = Fake()
        val routes = Scenes.Routes({ store }, { store = it }, { "off" }) { tv }
        val list = JSONArray().put(JSONObject().put("id", "s1").put("name", "Night").put("steps", steps("""{"do":"wake"}""", """{"do":"bogus"}""")))
        val saved = body(routes.handle(req("POST", "/api/scenes", JSONObject().put("scenes", list)))!!)
        assertEquals(1, saved.getJSONArray("scenes").getJSONObject(0).getJSONArray("steps").length())   // cleaned, and answered as saved
        assertEquals(1, body(routes.handle(req("GET", "/api/scenes"))!!).getJSONArray("scenes").length())
        assertEquals(400, routes.handle(req("POST", "/api/scenes", JSONObject()))!!.status)               // a broken import must not wipe them
        assertEquals(1, store.length())
        assertTrue(routes.handle(req("GET", "/api/scenes", query = mapOf("download" to "1")))!!.headers["Content-Disposition"]!!.contains("tv-remote-scenes-"))
        assertEquals(true, body(routes.handle(req("POST", "/api/scene/run", JSONObject().put("id", "s1")))!!).getBoolean("ok"))
        assertEquals(listOf("key 224"), tv.did)
        assertEquals(404, routes.handle(req("POST", "/api/scene/run", JSONObject().put("id", "nope")))!!.status)
        assertNull(routes.handle(req("GET", "/api/other")))
    }

    @Test fun savingTellsTheClientsPublisherTheCleanedList() {
        var told: JSONArray? = null
        val routes = Scenes.Routes({ JSONArray() }, { }, { "off" }) { null }
        routes.publish = { told = it }
        routes.handle(req("POST", "/api/scenes", JSONObject().put("scenes", JSONArray().put(JSONObject().put("id", "s1").put("name", "N").put("steps", steps("""{"do":"wake"}""", """{"do":"bogus"}"""))))))
        assertEquals(1, told!!.getJSONObject(0).getJSONArray("steps").length())
    }

    @Test fun pinRouteGivesTheHomeTheCleanedSceneAndExistsOnlyWhenACoreSetsIt() {
        val routes = Scenes.Routes({ JSONArray().put(saved) }, { }, { "off" }) { null }
        assertNull(routes.handle(req("POST", "/api/scene/pin", JSONObject().put("id", "s1"))))
        var got: JSONObject? = null
        routes.pin = { got = it; JSONObject().put("ok", true) }
        assertEquals(true, body(routes.handle(req("POST", "/api/scene/pin", JSONObject().put("id", "s1")))!!).getBoolean("ok"))   // a saved scene by id
        assertEquals("s1", got!!.getString("id"))
        routes.handle(req("POST", "/api/scene/pin", JSONObject().put("id", "e1").put("name", "Edit").put("steps", steps("""{"do":"wake"}""", """{"do":"bogus"}"""))))   // the editor's own, unsaved
        assertEquals(1, got!!.getJSONArray("steps").length())
        assertEquals("e1", got!!.getString("id"))
        assertEquals(400, routes.handle(req("POST", "/api/scene/pin", JSONObject().put("id", "nope")))!!.status)
        routes.pin = { throw SetupNeed.Missing("scenepin", "0.1.0") }
        assertEquals(409, routes.handle(req("POST", "/api/scene/pin", JSONObject().put("id", "s1")))!!.status)
    }

    @Test fun routesAnswerOfflineWithTheCoresOwnWords() {
        val routes = Scenes.Routes({ JSONArray().put(saved) }, { }, { "adb:none" }) { null }
        val r = routes.handle(req("POST", "/api/scene/run", JSONObject().put("id", "s1")))!!
        assertEquals(503, r.status)
        assertTrue(String(r.body).contains("adb:none"))
    }

    // ---- "start at"
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private fun ms(s: String) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = utc }.parse(s)!!.time
    private fun at(s: String) = JSONObject(s)

    @Test fun atIsCleanedAndKept() {
        val sc = JSONObject().put("name", "x").put("steps", JSONArray().put(JSONObject().put("do", "wake")))
        assertNull(Scenes.clean(JSONArray().put(JSONObject(sc.toString()))).getJSONObject(0).optJSONObject("at"))     // none = as before
        val weekly = Scenes.clean(JSONArray().put(JSONObject(sc.toString()).put("at", at("{time:'7:05',days:[5,1,1,9]}")))).getJSONObject(0).getJSONObject("at")
        assertEquals("07:05", weekly.getString("time")); assertEquals("[1,5]", weekly.getJSONArray("days").toString())
        val every = Scenes.cleanAt(at("{time:'20:30'}"))!!
        assertEquals(7, every.getJSONArray("days").length())
        assertEquals("2026-11-20", Scenes.cleanAt(at("{time:'20:30',date:'2026-11-20'}"))!!.getString("date"))
        assertNull(Scenes.cleanAt(at("{time:'25:00'}"))); assertNull(Scenes.cleanAt(at("{time:'20:30',date:'2026-13-01'}"))); assertNull(Scenes.cleanAt(null))
    }

    @Test fun nextAtWeeklyAndOnce() {
        val mon = at("{time:'08:00',days:[1]}")                                   // 2026-11-02 is a Monday
        assertEquals(ms("2026-11-02 08:00"), Scenes.nextAt(mon, ms("2026-11-02 07:59"), utc))
        assertEquals(ms("2026-11-09 08:00"), Scenes.nextAt(mon, ms("2026-11-02 08:00"), utc))   // strictly after
        assertEquals(ms("2026-11-09 08:00"), Scenes.nextAt(mon, ms("2026-11-05 12:00"), utc))
        val once = at("{time:'20:00',date:'2026-11-20'}")
        assertEquals(ms("2026-11-20 20:00"), Scenes.nextAt(once, ms("2026-11-01 00:00"), utc))
        assertNull(Scenes.nextAt(once, ms("2026-11-20 20:00"), utc))
        assertEquals(ms("2026-12-01 06:00"), Scenes.nextAt(at("{time:'06:00',days:[2]}"), ms("2026-11-25 07:00"), utc))   // over a month end (Tuesday)
    }

    @Test fun timedSceneNeedsANewerTvSetup() {
        val list = JSONArray().put(JSONObject().put("id", "a").put("name", "x").put("at", at("{time:'20:30'}")).put("steps", JSONArray().put(JSONObject().put("do", "wake"))))
        val sh = { c: String -> if (c.contains("RulesReceiver")) "Broadcast completed: result=0, data=\"" + dev.tvremote.core.B64.encode("{\"ok\":true}".toByteArray()) + "\"" else "" }
        try { Scenes.publishThroughSetup(sh, list); org.junit.Assert.fail("an old TV Setup answers without timed") } catch (e: SetupNeed.Missing) { assertEquals("scenetime", e.feature) }
        Scenes.publishThroughSetup(sh, JSONArray())                                  // nothing timed: quiet as before
    }

    @Test fun startOnIsCleanedAndNeedsAnEventAwareTvSetup() {
        assertEquals("{\"app\":\"com.google.android.youtube.tv\"}", Scenes.cleanOn(JSONObject("{app:'com.google.android.youtube.tv',x:1}"))?.toString())
        assertEquals("{\"power\":\"on\"}", Scenes.cleanOn(JSONObject("{power:'on'}"))?.toString())
        assertEquals(null, Scenes.cleanOn(JSONObject("{power:'off'}")))
        val list = JSONArray().put(JSONObject().put("id", "a").put("name", "x").put("on", JSONObject("{power:'on'}")).put("steps", JSONArray().put(JSONObject().put("do", "wake"))))
        assertEquals("on", Scenes.clean(list).getJSONObject(0).getJSONObject("on").getString("power"))
        val sh = { c: String -> if (c.contains("RulesReceiver")) "Broadcast completed: result=0, data=\"" + dev.tvremote.core.B64.encode("{\"ok\":true,\"timed\":true}".toByteArray()) + "\"" else "" }
        try { Scenes.publishThroughSetup(sh, list); org.junit.Assert.fail("a TV Setup without events") } catch (e: SetupNeed.Missing) { assertEquals("sceneon", e.feature) }
    }
}
