package dev.tvremote.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import dev.tvremote.core.remote.Atv
import dev.tvremote.core.remote.Lg
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.remote.Samsung
import dev.tvremote.core.scenes.Scenes

/** The LG adapter against a fake webOS TV: SSAP over a WebSocket, the prompt, the client key, the Magic Remote socket. */
class LgTest {
    /**
     * Enough of a webOS TV: how it answers a register ([pairing]), the requests it got, the buttons on its pointer socket.
     * [webos]: "mid" (answers what it is asked), "new" (webOS 23+: drops a register that came without a hello, and tells its
     * system info only before the register, as aiowebostv found), "old" (no hello, no power state, no screen off), "slow" (new,
     * but its hello takes a moment and anything sent before that answer makes it hang up), "nohello" (hangs up on a hello).
     */
    private class FakeLg(var pairing: String = "prompt", val webos: String = "mid") : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        val payloads = CopyOnWriteArrayList<JSONObject>()
        val buttons = CopyOnWriteArrayList<String>()
        val known = HashSet<String>()
        var registers = 0
        var volume = 12
        @Volatile var power = "Active"
        private val hello = HashSet<FakeWs.Conn>()
        private val registered = HashSet<FakeWs.Conn>()
        private val powerSubs = CopyOnWriteArrayList<Pair<FakeWs.Conn, String>>()
        val pointer = FakeWs({ _, t -> buttons.add(Regex("name:(\\S+)").find(t)?.groupValues?.get(1) ?: t) })
        val ws = FakeWs({ c, t -> answer(c, JSONObject(t)) })

        private fun answer(c: FakeWs.Conn, m: JSONObject) {
            val id = m.optString("id")
            fun reply(p: JSONObject) = c.send(JSONObject().put("type", "response").put("id", id).put("payload", p.put("returnValue", true)).toString())
            val type = m.optString("type")
            if (webos == "slow" && type != "hello" && c !in hello) { c.drop(); return }
            when (type) {
                "hello" -> if (webos == "nohello") c.drop()
                    else if (webos == "slow") Thread { Thread.sleep(150); hello.add(c); c.send(JSONObject().put("type", "hello").put("id", id).put("payload", JSONObject()).toString()) }.start()
                    else if (webos == "old") c.send(JSONObject().put("type", "error").put("id", id).put("error", "404 unknown").toString())
                    else { hello.add(c); c.send(JSONObject().put("type", "hello").put("id", id).put("payload", JSONObject().put("deviceOS", "webOS")).toString()) }
                "register" -> {
                    if ((webos == "new" || webos == "slow") && c !in hello) { c.drop(); return }
                    registered.add(c)
                    registers++
                    val offered = m.getJSONObject("payload").optString("client-key")
                    fun grant() { val k = if (offered in known) offered else "key-" + registers; known.add(k)
                        c.send(JSONObject().put("type", "registered").put("id", id).put("payload", JSONObject().put("client-key", k)).toString()) }
                    when {
                        offered in known -> grant()
                        pairing == "reject" -> c.send(JSONObject().put("type", "error").put("id", id).put("error", "403 user denied").toString())
                        pairing == "never" -> { }
                        else -> { reply(JSONObject().put("pairingType", "PROMPT")); Thread { Thread.sleep(200); grant() }.start() }
                    }
                }
                "subscribe", "request" -> {
                    val uri = m.optString("uri")
                    requests.add(uri)
                    payloads.add(m.optJSONObject("payload") ?: JSONObject())
                    when (uri) {
                        "ssap://audio/getVolume" -> reply(JSONObject().put("volumeStatus", JSONObject().put("volume", volume).put("muteStatus", false).put("soundOutput", "tv_speaker").put("maxVolume", 100)))
                        "ssap://audio/setVolume" -> { volume = m.getJSONObject("payload").getInt("volume"); reply(JSONObject()) }
                        "ssap://system/getSystemInfo" -> if ((webos == "new" || webos == "slow") && c in registered) c.send(JSONObject().put("type", "error").put("id", id).put("error", "401 insufficient permissions").toString())
                            else reply(JSONObject().put("modelName", "OLED55C3"))
                        "ssap://com.webos.service.update/getCurrentSWInformation" -> reply(JSONObject().put("product_name", "webOSTV 23").put("major_ver", "33").put("minor_ver", "10"))
                        "ssap://com.webos.service.connectionmanager/getinfo" -> reply(JSONObject().put("wiredInfo", JSONObject().put("macAddress", "aa:bb:cc:00:11:22")).put("wifiInfo", JSONObject().put("macAddress", "aa:bb:cc:00:11:33")))
                        "ssap://com.webos.service.connectionmanager/getstatus" -> reply(JSONObject().put("wired", JSONObject().put("state", "connected")))
                        "ssap://com.webos.applicationManager/getForegroundAppInfo" -> reply(JSONObject().put("appId", "netflix"))
                        "ssap://com.webos.service.tvpower/power/getPowerState" -> if (webos == "old") c.send(JSONObject().put("type", "error").put("id", id).put("error", "404 no such service or method").toString())
                            else { powerSubs.add(c to id); reply(JSONObject().put("state", power)) }
                        "ssap://com.webos.service.tvpower/power/turnOffScreen" -> if (webos == "old") c.send(JSONObject().put("type", "error").put("id", id).put("error", "404 no such service or method").toString())
                            else { reply(JSONObject()); pushPower("Screen Off") }
                        "ssap://system/turnOff" -> { }                       // a TV shutting down answers nothing
                        "ssap://com.webos.applicationManager/listLaunchPoints" -> reply(JSONObject().put("launchPoints", org.json.JSONArray()
                            .put(JSONObject().put("id", "netflix").put("title", "Netflix").put("icon", "http://127.0.0.1:1/n.png"))
                            .put(JSONObject().put("id", "youtube.leanback.v4").put("title", "YouTube"))))
                        "ssap://com.webos.service.networkinput/getPointerInputSocket" -> reply(JSONObject().put("socketPath", "ws://127.0.0.1:${pointer.port}/resources/x/netinput.pointer.sock"))
                        "ssap://system.launcher/launch", "ssap://system.launcher/open",
                        "ssap://com.webos.service.ime/insertText", "ssap://com.webos.service.ime/sendEnterKey", "ssap://audio/setMute" -> reply(JSONObject())
                        else -> c.send(JSONObject().put("type", "error").put("id", id).put("error", "404 no such service or method").toString())
                    }
                }
            }
        }

        fun pushPower(state: String) {
            power = state
            for ((c, id) in powerSubs) c.send(JSONObject().put("type", "response").put("id", id).put("payload", JSONObject().put("state", state).put("returnValue", true)).toString())
        }

        override fun close() { ws.close(); pointer.close() }
    }

    private val dir: File = Files.createTempDirectory("lgtest").toFile()
    private val tvs = ArrayList<FakeLg>()
    private val remotes = ArrayList<Lg>()
    private fun tv(pairing: String = "prompt", webos: String = "mid") = FakeLg(pairing, webos).also { tvs.add(it) }
    private fun lg(vararg ports: Int) = Lg(dir, "127.0.0.1", ports.map { it to false }).also { remotes.add(it) }
    private fun until(what: () -> Boolean) { for (i in 0 until 50) { if (what()) return; Thread.sleep(50) }; fail("timed out") }

    @After fun done() { remotes.forEach { it.close() }; tvs.forEach { it.close() }; dir.deleteRecursively() }

    @Test fun pairsAfterThePromptAndKeepsTheKey() {
        val tv = tv()
        val a = lg(tv.ws.port)
        a.connect { }
        assertTrue(a.ready())
        assertEquals(1, tv.registers)
        assertFalse(a.asking)
        assertFalse(tv.ws.unmasked)
        a.close()
        val b = lg(tv.ws.port)                                       // the second time: the saved key, no prompt
        b.connect { }
        assertTrue(b.ready())
        val reg = tv.known.size
        assertEquals(1, reg)
    }

    @Test fun learnsTheTv() {
        val tv = tv()
        val a = lg(tv.ws.port)
        a.connect { }
        until { a.isOn != null && a.volume != null && a.mac.isNotEmpty() && a.app.isNotEmpty() }
        assertEquals("OLED55C3", a.model)
        assertEquals("webOSTV 23 33.10", a.version)
        assertEquals("AA:BB:CC:00:11:22", a.mac)                   // wired, as the TV is
        assertEquals(Triple(12, 100, false), a.volume)
        assertEquals("netflix", a.app)
        assertEquals(true, a.isOn)
    }

    @Test fun aRefusalIsRefused() {
        val tv = tv("reject")
        try { lg(tv.ws.port).connect { }; fail() } catch (e: Remote.Refused) { }
    }

    @Test fun fallsBackToTheNextPort() {
        val tv = tv()
        val a = lg(FakeWs.deadPort(), tv.ws.port)
        a.connect { }
        assertTrue(a.ready())
    }

    @Test fun keysGoToThePointerSocketAndTheSpecialsAreRequests() {
        val tv = tv()
        val a = lg(tv.ws.port)
        a.connect { }
        a.key(19, Atv.SHORT); a.key(23, Atv.SHORT); a.key(4, Atv.SHORT); a.key(8, Atv.SHORT)
        until { tv.buttons.size >= 4 }
        assertEquals(listOf("UP", "ENTER", "BACK", "1"), tv.buttons.toList())
        a.key(66, Atv.SHORT)
        a.key(26, Atv.SHORT)
        a.key(219, Atv.SHORT)                                         // the Assistant: no such key on LG, ignored
        until { "ssap://system/turnOff" in tv.requests }
        assertTrue("ssap://com.webos.service.ime/sendEnterKey" in tv.requests)
        assertFalse(219 in a.keys)
    }

    @Test fun aHeldKeyRepeatsUntilLetGo() {
        val tv = tv()
        val a = lg(tv.ws.port)
        a.connect { }
        a.key(24, Atv.DOWN)
        Thread.sleep(700)
        a.key(24, Atv.UP)
        Thread.sleep(300)
        val n = tv.buttons.size
        assertTrue("got $n", n >= 3)
        Thread.sleep(400)
        assertEquals(n, tv.buttons.size)
    }

    @Test fun textAppsLinksAndVolume() {
        val tv = tv()
        val a = lg(tv.ws.port)
        a.connect { }
        a.text("xin chào")
        a.launch("netflix")
        a.launch("https://example.com/a")
        a.launch("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        until { tv.requests.count { it.startsWith("ssap://system.launcher/") } >= 3 }
        val i = tv.requests.indexOf("ssap://com.webos.service.ime/insertText")
        assertEquals("xin chào", tv.payloads[i].getString("text"))
        val launches = tv.requests.indices.filter { tv.requests[it].startsWith("ssap://system.launcher/") }.map { tv.requests[it] + " " + tv.payloads[it] }
        assertTrue(launches[0], launches[0].contains("\"id\":\"netflix\""))
        assertTrue(launches[1], launches[1].startsWith("ssap://system.launcher/open") && launches[1].contains("example.com"))
        assertTrue(launches[2], launches[2].contains("youtube.leanback.v4") && launches[2].contains("dQw4w9WgXcQ"))
        val apps = a.apps()
        assertEquals(listOf("netflix", "youtube.leanback.v4"), apps.map { it.id })
        assertEquals("Netflix", apps[0].label)
        assertTrue(a.setVolume(30))
        assertEquals(30, tv.volume)
    }

    @Test fun nobodyAnsweringIsAnErrorNotAHang() {
        val tv = tv("never")
        val a = Lg(dir, "127.0.0.1", listOf(tv.ws.port to false))
        remotes.add(a)
        val t = Thread { try { a.connect { } } catch (ignored: IOException) { } }
        t.start()
        Thread.sleep(500)
        a.close()                                                     // closing gives up the wait
        t.join(2000)
        assertFalse(t.isAlive)
    }

    @Test fun everyKeyNameIsAKnownSceneKey() {
        for (code in Lg.KEYS.keys) assertTrue("$code", code in Scenes.KEYS.values)
        for (code in Samsung.KEYS.keys) assertTrue("$code", code in Scenes.KEYS.values)
        assertTrue(Remote.Kind.keyNames(Remote.Kind.LG).containsAll(listOf("UP", "OK", "BACK", "HOME", "VOL_UP", "MUTE", "POWER", "0")))
        assertFalse("ASSIST" in Remote.Kind.keyNames(Remote.Kind.LG))
        assertEquals(Scenes.KEYS.keys.sorted(), Remote.Kind.keyNames(Remote.Kind.ANDROID))
    }

    @Test fun newWebosWantsAHelloAndTellsItsModelOnlyBeforeTheRegister() {
        val tv = tv(webos = "new")
        val a = lg(tv.ws.port)
        a.connect { }
        assertTrue(a.ready())
        until { a.isOn != null }
        assertEquals("OLED55C3", a.model)
    }

    @Test fun aSlowHelloIsWaitedFor() {                               // issue #36: the TV hung up on what came before its hello answer
        val tv = tv(webos = "slow")
        val a = lg(tv.ws.port)
        a.connect { }
        assertTrue(a.ready())
        until { a.model.isNotEmpty() }
        assertEquals("OLED55C3", a.model)
    }

    @Test fun aTvThatHangsUpOnTheHelloGetsTheRegisterAlone() {
        val tv = tv(webos = "nohello")
        val a = lg(tv.ws.port)
        a.connect { }
        assertTrue(a.ready())
        assertEquals(1, tv.registers)
    }

    @Test fun oldWebosHasNoPowerStateAndNoScreenOff() {
        val tv = tv(webos = "old")
        val a = lg(tv.ws.port)
        a.connect { }
        until { a.isOn != null && a.app.isNotEmpty() }
        assertEquals(true, a.isOn)                                    // connected at all means on
        assertFalse(a.screenOff())                                    // the TV said no: not reported as done
    }

    @Test fun theScreenOffIsStillOnAndPowerStillTurnsItOff() {
        val tv = tv()
        val a = lg(tv.ws.port)
        a.connect { }
        until { a.isOn == true }
        assertTrue(a.screenOff())
        until { tv.power == "Screen Off" }
        Thread.sleep(200)
        assertEquals(true, a.isOn)                                    // the sound plays on: on, not standby
        a.key(26, Atv.SHORT)
        until { "ssap://system/turnOff" in tv.requests }
        tv.pushPower("Active Standby")
        until { a.isOn == false }
    }

    @Test fun turningOffBlocksNoKeysWhileTheTvSaysNothing() {
        val tv = tv()
        val a = lg(tv.ws.port)
        a.connect { }
        until { a.isOn == true }
        a.key(26, Atv.SHORT)                                          // the fake never answers turnOff
        a.key(19, Atv.SHORT)
        val t0 = System.currentTimeMillis()
        until { "UP" in tv.buttons }
        assertTrue(System.currentTimeMillis() - t0 < 2000)
    }

    @Test fun aDroppedConnectionIsReported() {
        val tv = tv()
        val a = lg(tv.ws.port)
        var closed = false
        a.connect { closed = true }
        tv.ws.conns.forEach { it.drop() }
        until { closed }
        assertFalse(a.ready())
    }
}
