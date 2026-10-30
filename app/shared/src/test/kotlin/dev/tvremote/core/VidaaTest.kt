package dev.tvremote.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import dev.tvremote.core.remote.Atv
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.remote.Ssdp
import dev.tvremote.core.remote.Vidaa

/** A made-up Hisense TV: its MQTT broker, enough of it to pair, log in with the token and take keys, apps and inputs. */
class VidaaTest {
    private val RAW = Vidaa.RAW
    private val got: MutableList<String> = Collections.synchronizedList(ArrayList())   // "action payload" the TV received
    private val logins: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var legacy = false                                        // an old TV: the fixed login, no code
    private var hangUps = 0                                           // logins hung up on (no CONNACK), as some firmwares refuse
    @Volatile private var deviceInfo = """{"model_name":"65U8K","brand":"Hisense","device_name":" Living room ","friendly_name":"x","wifi_mac":"aa-bb-cc-00-11-22"}"""
    private var silent = 0                                           // logins taken that never show a code
    private var tokenEarly = false                                    // the token comes as soon as the code is taken, unasked
    private var noWildcard = false                                    // a firmware that refuses "#" subscriptions: only exact topics get answers
    private var server: ServerSocket? = null
    private val dir: File = Files.createTempDirectory("vidaa").toFile()

    private fun start(): Int {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { return@Thread }
                Thread { try { serve(c) } catch (ignored: Exception) { } finally { c.close() } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        server = s
        return s.localPort
    }

    @After fun stop() { server?.close(); dir.deleteRecursively() }

    private fun serve(c: Socket) {
        val inp = DataInputStream(c.getInputStream()); val out = c.getOutputStream()
        val (head, body) = read(inp) ?: return
        if (head != 0x10) return                                      // a TLS hello: this broker is plain TCP
        var at = 10
        fun s(): String { val n = ((body[at].toInt() and 0xff) shl 8) or (body[at + 1].toInt() and 0xff); val r = String(body, at + 2, n); at += 2 + n; return r }
        val cid = s(); val user = s(); val pass = s()
        logins.add("$user $pass")
        if (user != Vidaa.STATIC_USER && synchronized(this) { hangUps-- > 0 }) return
        val mute = user != Vidaa.STATIC_USER && synchronized(this) { silent-- > 0 }
        val ok = when {
            user == Vidaa.STATIC_USER -> legacy
            pass == "ACC1" || pass == "REF1" -> true
            user.startsWith("his$") -> !legacy
            else -> false
        }
        send(out, 0x20, byteArrayOf(0, if (ok) 0 else 5))
        if (!ok) return
        val mobile = "/remoteapp/mobile/$cid"
        val subs = HashSet<String>()
        while (true) {
            val (h, b) = read(inp) ?: return
            if (h shr 4 == 8) {                                       // SUBSCRIBE: id, then (topic, QoS) each
                var i = 2
                while (i < b.size) { val n = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff); subs.add(String(b, i + 2, n)); i += 3 + n }
                continue
            }
            if (h shr 4 != 3) continue
            val n = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
            val topic = String(b, 2, n); val payload = String(b, 2 + n, b.size - 2 - n)
            val action = topic.substringAfterLast('/')
            got.add("$action $payload".trim())
            fun say(t: String, p: String) { if (mobile + t in subs || (!noWildcard && "$mobile/#" in subs)) publish(out, mobile + t, p) }
            when (action) {
                "vidaa_app_connect" -> if (!mute) say("/ui_service/data/authentication", "")
                "gettvstate" -> if (pass == Vidaa.STATIC_PASS && !legacy) say("/ui_service/data/authentication", "") else say("/ui_service/data/gettvstate", """{"statetype":"app","name":"Netflix"}""")
                "authenticationcode" -> if (tokenEarly && payload.contains("1234")) {
                    say("/platform_service/data/tokenissuance", """{"accesstoken":"ACC1","refreshtoken":"REF1","accesstoken_time":${System.currentTimeMillis() / 1000},"accesstoken_duration_day":2}""")
                    say("/ui_service/data/authenticationcode", """{"result":1}""")
                } else say("/ui_service/data/authenticationcode", if (payload.contains("1234")) """{"result":1}""" else """{"result":0}""")
                "gettoken" -> if (!tokenEarly) say("/platform_service/data/tokenissuance", """{"accesstoken":"ACC1","refreshtoken":"REF1","accesstoken_time":${System.currentTimeMillis() / 1000},"accesstoken_duration_day":2}""")
                "applist" -> say("/ui_service/data/applist", """[{"appId":"3","name":"Netflix","url":"netflix","storeType":0},{"appId":"7","name":"YouTube","url":"youtube"}]""")
                "sourcelist" -> say("/ui_service/data/sourcelist", """[{"sourceid":"4","sourcename":"HDMI 2","displayname2":"PlayStation","is_signal":"1"},{"sourceid":"0","sourcename":"TV"}]""")
                "getvolume" -> say("/platform_service/data/getvolume", """{"volume_type":0,"volume_value":17}""")
                "getdeviceinfo" -> say("/platform_service/data/getdeviceinfo", deviceInfo)
            }
        }
    }

    private fun read(i: DataInputStream): Pair<Int, ByteArray>? {
        val h = i.read(); if (h < 0) return null
        var len = 0; var mul = 1
        while (true) { val b = i.readUnsignedByte(); len += (b and 0x7f) * mul; if (b and 0x80 == 0) break; mul *= 128 }
        return h to ByteArray(len).also { i.readFully(it) }
    }

    private fun send(o: OutputStream, head: Int, body: ByteArray) = synchronized(o) {
        val b = ByteArrayOutputStream(); b.write(head); var n = body.size
        do { var x = n % 128; n /= 128; if (n > 0) x = x or 0x80; b.write(x) } while (n > 0)
        b.write(body); o.write(b.toByteArray()); o.flush()
    }

    private fun publish(o: OutputStream, topic: String, payload: String) {
        val t = topic.toByteArray()
        send(o, 0x30, byteArrayOf((t.size shr 8).toByte(), t.size.toByte()) + t + payload.toByteArray())
    }

    private fun sent(n: Int, filter: (String) -> Boolean): List<String> {
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until && seen().count(filter) < n) Thread.sleep(20)
        return seen().filter(filter)
    }

    private fun seen() = synchronized(got) { got.toList() }           // the TV's thread may add while this reads

    private fun waitFor(what: () -> Boolean) { val until = System.currentTimeMillis() + 3000; while (!what() && System.currentTimeMillis() < until) Thread.sleep(20) }

    @Test fun theLoginIsMadeTheWayHisensesAppMakesIt() {                // the same numbers as stevene1919/hisense_vidaa's crypto.py
        val mac = "AA:BB:CC:DD:EE:FF"
        assertEquals("AA:BB:CC:DD:EE:FF\$his\$637064_vidaacommon_001", Vidaa.clientId(mac))
        assertEquals("his$6239759786369374312" to "E4B16BE39DA56468522CACF7411FB50F", Vidaa.credentials(mac, 1700000000, "modern"))
        assertEquals("his$6239759786369374312" to "30CC23251954D2B965504DC81E944805", Vidaa.credentials(mac, 1700000000, "middle"))
        assertEquals("his$1700000000" to "30CC23251954D2B965504DC81E944805", Vidaa.credentials(mac, 1700000000, "remotenow"))
    }

    @Test fun pairsWithTheCodeThenLogsInWithTheTokenAndControlsTheTv() {
        val port = start()
        val v = Vidaa(dir, "127.0.0.1", port)
        try { v.connect {}; fail("connected unpaired") } catch (e: Atv.NotPaired) { }
        v.startPairing()
        try { v.finishPairing("9999"); fail("took a wrong code") } catch (e: IllegalArgumentException) { }
        v.startPairing()
        v.finishPairing("1234")
        v.connect {}
        assertTrue(v.ready())
        assertTrue(logins.last().endsWith(" ACC1"))
        waitFor { v.model.isNotEmpty() && v.volume != null && v.app.isNotEmpty() && v.mac.isNotEmpty() }   // the MAC comes on its own reply: under load it was still empty
        assertEquals("Living room", v.model); assertEquals(17, v.volume!!.first); assertEquals("Netflix", v.app); assertEquals(true, v.isOn)
        assertEquals("AA:BB:CC:00:11:22", v.mac)
        waitFor { File(dir, "vidaa-127.0.0.1.json").readText().contains("AA:BB:CC:00:11:22") }   // kept: standby needs it to wake the TV
        v.key(19, Atv.DOWN); v.key(19, Atv.UP); v.key(7, Atv.SHORT); v.key(999, Atv.SHORT)
        v.text("hi")
        assertEquals(listOf("3" to "Netflix", "7" to "YouTube"), v.apps().map { it.id to it.label })
        v.launch("YouTube")
        assertEquals(listOf("4" to "hdmi", "0" to "tuner"), v.inputs().map { it.id to it.kind })
        assertEquals("HDMI 2 · PlayStation", v.inputs()[0].label)
        assertTrue(v.switchInput("4")); assertFalse(v.switchInput("nope"))
        assertTrue(v.setVolume(30))
        assertEquals(listOf("sendkey KEY_UP", "sendkey KEY_0"), sent(2) { it.startsWith("sendkey") })
        assertEquals(2, sent(2) { it.startsWith("txtinputdata") || it.startsWith("bwsinputdata") }.size)
        assertTrue(sent(1) { it.startsWith("launchapp") }.single().contains("\"appId\":\"7\""))
        assertTrue(sent(1) { it.startsWith("changesource") }.single().contains("\"sourceid\":\"4\""))
        assertEquals(listOf("changevolume 30"), sent(1) { it.startsWith("changevolume") })
        v.close()
        val again = Vidaa(dir, "127.0.0.1", port)                     // the token is kept: no code the next time
        again.connect {}
        assertTrue(again.ready())
        again.close()
    }

    @Test fun aTvInStandbyIsWokenByItsSavedMacThenGivenUpOnCleanly() {
        val port = ServerSocket(0).use { it.localPort }               // nothing listens: the TV in standby
        File(dir, "vidaa-127.0.0.1.json").writeText("""{"mac":"AA:BB:CC:00:11:22"}""")
        val woken = Collections.synchronizedList(ArrayList<String>())
        val v = Vidaa(dir, "127.0.0.1", port, { mac, lan -> woken.add("$mac $lan") }, 400, 50)
        val t0 = System.currentTimeMillis()
        try { v.connect {}; fail("connected to a closed port") } catch (e: IOException) { }
        assertEquals(listOf("AA:BB:CC:00:11:22 127.0.0.1"), woken.toList())   // one packet round, not one per poll
        assertTrue(System.currentTimeMillis() - t0 >= 400)            // it did wait
        assertFalse(v.ready())
        val none = Vidaa(Files.createTempDirectory("vidaa").toFile().also { it.deleteOnExit() }, "127.0.0.1", port, { _, _ -> fail("woke a TV with no saved MAC") }, 400, 50)
        try { none.connect {}; fail("connected") } catch (e: IOException) { }
    }

    @Test fun macsAreReadFromRawHexInterfacesAndKeptAllForWakeOnLan() {
        assertEquals("E4:3B:C9:57:F1:4F", Vidaa.normMac("e43bc957f14f"))
        assertEquals("E4:3B:C9:57:F1:4F", Vidaa.normMac("e4-3b-c9-57-f1-4f"))
        assertEquals(null, Vidaa.normMac("000000000000")); assertEquals(null, Vidaa.normMac("FF:FF:FF:FF:FF:FF")); assertEquals(null, Vidaa.normMac("nope"))
        deviceInfo = """{"tv_name":"Hisense TV","model_name":"58A53FXVT","tv_version":"V0000.06.30T.N0921","eth0":"e43bc957f14f","wlan0":"e85177ec981c","country":"AUS","language":"eng"}"""
        val v = Vidaa(dir, "127.0.0.1", start())
        v.startPairing(); v.finishPairing("1234"); v.connect {}
        waitFor { v.mac.isNotEmpty() && v.model.isNotEmpty() }
        assertEquals("Hisense TV", v.model); assertEquals("E4:3B:C9:57:F1:4F", v.mac)
        val file = File(dir, "vidaa-127.0.0.1.json")
        waitFor { file.readText().contains("E8:51:77:EC:98:1C") }
        assertTrue(file.readText().contains("\"mac\":\"E4:3B:C9:57:F1:4F\""))   // the single value old versions read stays
        v.close()
        val port = ServerSocket(0).use { it.localPort }               // standby: a packet to each interface
        val woken = Collections.synchronizedList(ArrayList<String>())
        Vidaa(dir, "127.0.0.1", port, { mac, _ -> woken.add(mac) }, 200, 50).let { try { it.connect {}; fail("connected") } catch (e: IOException) { } }
        assertEquals(listOf("E4:3B:C9:57:F1:4F", "E8:51:77:EC:98:1C"), woken.toList())
    }

    @Test fun modelFallsBackToModelNameWhenTheTvHasNoName() {
        deviceInfo = """{"model_name":"58A53FXVT","eth0":"e43bc957f14f"}"""
        val v = Vidaa(dir, "127.0.0.1", start())
        v.startPairing(); v.finishPairing("1234"); v.connect {}
        waitFor { v.model.isNotEmpty() }
        assertEquals("58A53FXVT", v.model)
        v.close()
    }

    @Test fun anAppIsFoundByItsNameWhateverThePunctuation() {
        val list = listOf("Netflix", "Spotify : Music & Podcasts", "Stan.", "VIDAA   Kids", "Stan Sport").mapIndexed { i, n -> org.json.JSONObject().put("appId", "$i").put("name", n) }
        fun pick(l: String) = Vidaa.pickApp(list, l)?.optString("name")
        assertEquals("Spotify : Music & Podcasts", pick("spotify"))
        assertEquals("Stan.", pick("stan"))                           // exact before prefix ("Stan Sport")
        assertEquals("VIDAA   Kids", pick("vidaa kids"))
        assertEquals("Netflix", pick("0"))               // the id still wins
        assertEquals(null, pick("hulu"))
    }

    // issue #8: a firmware that takes one kind of login but shows no code, hangs up instead of refusing, or sends the token unasked
    @Test fun aLoginThatShowsNoCodeTriesTheNextKind() {
        silent = 1
        val v = Vidaa(dir, "127.0.0.1", start())
        v.startPairing(); v.finishPairing("1234")
        assertEquals(2, logins.size)
        v.close()
    }

    @Test fun aTvThatHangsUpOnALoginIsTriedWithTheNextKind() {
        hangUps = 2                                                   // both ways of the first kind (TLS, then plain)
        val v = Vidaa(dir, "127.0.0.1", start())
        v.startPairing(); v.finishPairing("1234")
        v.connect {}
        assertTrue(v.ready())
        v.close()
    }

    @Test fun aTokenSentBeforeItIsAskedForIsKept() {
        tokenEarly = true
        val v = Vidaa(dir, "127.0.0.1", start())
        v.startPairing(); v.finishPairing("1234")
        assertTrue(seen().none { it.startsWith("gettoken") })
        v.connect {}
        assertTrue(logins.last().endsWith(" ACC1"))
        v.close()
    }

    @Test fun aTvThatRefusesWildcardSubscriptionsStillAnswers() {   // issue #8: the code was on screen, the app said none was
        noWildcard = true
        val v = Vidaa(dir, "127.0.0.1", start())
        v.startPairing(); v.finishPairing("1234")
        v.connect {}
        waitFor { v.model.isNotEmpty() && v.volume != null }
        assertEquals("Living room", v.model)
        v.close()
    }

    @Test fun anOldTvTakesTheFixedLoginWithoutACode() {
        legacy = true
        val v = Vidaa(dir, "127.0.0.1", start())
        v.connect {}
        assertTrue(v.ready())
        assertEquals("${Vidaa.STATIC_USER} ${Vidaa.STATIC_PASS}", logins.last())
        v.close()
    }

    @Test fun aTvThatWantsHisensesCertificateSaysSo() {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))   // takes the connection, then hangs up on every login
        Thread { while (!s.isClosed) try { s.accept().close() } catch (e: IOException) { } }.apply { isDaemon = true }.start()
        server = s
        Vidaa.RAW = "http://127.0.0.1:1/"                             // the downloads fail too
        try { Vidaa(dir, "127.0.0.1", s.localPort).connect {}; fail("connected") } catch (e: IOException) { assertEquals(Vidaa.NO_CERT, e.message) }
        finally { Vidaa.RAW = RAW }
        assertTrue(Errors.say(IOException(Vidaa.NO_CERT), "en").contains("VIDAA Smart TV"))
    }

    @Test fun aDownloadedCertificateThatIsNotTheOnePinnedIsNotKept() {
        val key = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder().encodeToString(key.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))   // serves the same key for every file: no certificate matches
        Thread {
            while (!s.isClosed) try { s.accept().use { c ->
                c.getInputStream().bufferedReader().readLine()
                c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${pem.length}\r\nConnection: close\r\n\r\n$pem").toByteArray())
            } } catch (e: IOException) { }
        }.apply { isDaemon = true }.start()
        server = s
        Vidaa.RAW = "http://127.0.0.1:${s.localPort}/"
        try { assertFalse(Vidaa.fetchCerts(dir)) } finally { Vidaa.RAW = RAW }
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".crt") || it.name.endsWith(".key") })
    }

    @Test fun takesTheCertificateOnlyFromAFileThatHasOne() {
        assertFalse(Vidaa.importCert(dir, "not a certificate".toByteArray()))
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z -> z.putNextEntry(ZipEntry("res/raw/readme.txt")); z.write("x".toByteArray()); z.closeEntry() }
        assertFalse(Vidaa.importCert(dir, zip.toByteArray()))          // an APK without one
        assertFalse(Vidaa.hasCert(dir))
    }

    @Test fun isFoundByItsSsdpAnswerAndPort() {
        assertEquals(Remote.Kind.VIDAA, Ssdp.kind("HTTP/1.1 200 OK\r\nLOCATION: http://10.0.0.7:38400/MediaServer/rendererdevicedesc.xml\r\nSERVER: Linux UPnP/1.0\r\n"))
        assertEquals(listOf(36669), Remote.Kind.ports(Remote.Kind.VIDAA))
        assertEquals("Hisense VIDAA", Remote.Kind.name(Remote.Kind.VIDAA))
        assertTrue(Remote.of(Remote.Kind.VIDAA, dir, "127.0.0.1") is Vidaa)
    }
}
