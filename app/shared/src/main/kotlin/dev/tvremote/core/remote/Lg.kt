package dev.tvremote.core.remote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import dev.tvremote.core.WireLog

/**
 * LG webOS TVs (2014 on): the "second screen" API (SSAP) LG's own phone app speaks, JSON over a WebSocket on port 3000, or
 * 3001 with TLS (webOS 6+ closes 3000 on many sets). The first time, the TV asks "allow this device?"; once accepted it hands
 * over a client key, kept beside the other keys, which lets us straight in from then on. The keys go over a second socket the
 * TV opens for them (the Magic Remote's), the rest as requests. No picture, no ADB: see [Remote.Kind.caps].
 */
class Lg(private val dir: File, private val host: String, private val ports: List<Pair<Int, Boolean>> = PORTS) : Remote {
    override val kind = Remote.Kind.LG
    @Volatile override var model = ""
        private set
    override val vendor = "LG"
    @Volatile override var version = ""
        private set
    @Volatile override var isOn: Boolean? = null
        private set
    @Volatile override var volume: Triple<Int, Int, Boolean>? = null
        private set
    @Volatile override var app = ""
        private set
    @Volatile override var mac = ""
        private set
    @Volatile override var asking = false
        private set
    override val keys: Set<Int> get() = KEYS.keys

    private val saved = TvKeys(dir, "lg")
    @Volatile private var ws: WebSocket? = null
    @Volatile private var pointer: WebSocket? = null
    @Volatile private var live = false
    @Volatile private var closed = false
    @Volatile private var port = 0
    @Volatile private var secure = false
    @Volatile private var heard = false                               // the TV said anything on this socket
    @Volatile private var bare = false                                // it hangs up on a hello: the register alone from then on
    @Volatile private var external = false                            // the sound goes to a soundbar / receiver: its volume takes steps only
    @Volatile private var playing = false                             // what PLAY_PAUSE sends next (the TV has PLAY and PAUSE, no toggle)
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val subs = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val out = Executors.newSingleThreadScheduledExecutor { Thread(it, "lg-out").apply { isDaemon = true } }
    private val held = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private val icons = ConcurrentHashMap<String, String>()          // app id -> its picture on the TV, from the app list

    private class HungUp : IOException("the TV closed the connection")
    private class Pending { val done = CountDownLatch(1); @Volatile var got: JSONObject? = null }

    override fun ready(): Boolean = live && !closed

    /**
     * Blocks until the TV takes keys: at once with a client key it knows, else after the person accepts on the TV (up to
     * [PROMPT_S] seconds, [asking] meanwhile). [Remote.Refused]: they chose no; IOException: the TV is not there, or nobody answered.
     */
    override fun connect(onClosed: () -> Unit) {
        try { connect(onClosed, bare) }
        catch (e: IOException) {
            // A set that hung up before saying a word (issue #36: right after the hello) gets one more go the way it was
            // done before webOS 23: the register alone, nothing ahead of it
            if (e !is HungUp || closed || heard || bare) throw e
            WireLog.all.add("·", "LG $host: again with the register alone")
            connect(onClosed, bare = true)
            bare = true
        }
    }

    private fun connect(onClosed: () -> Unit, bare: Boolean) {
        val known = saved.get(host)                                   // "port key": a key is good only on the port it was given on
        val knownPort = known.substringBefore(' ').toIntOrNull() ?: 0
        var last: IOException? = null
        var socket: WebSocket? = null
        for ((p, tls) in ports.sortedBy { if (it.first == knownPort) 0 else 1 }) {
            try { socket = WebSocket.open(host, p, "/", tls); port = p; secure = tls; break } catch (e: IOException) { last = e; WireLog.all.add("·", "LG $host:$p $e") }
        }
        val s = socket ?: throw last ?: IOException("no port")
        if (closed) { s.close(); throw IOException("closed") }
        ws = s
        WireLog.all.add("·", "LG $host:$port open" + (if (secure) " (wss)" else ""))
        s.timeout(0)
        heard = false
        Thread({ read(s, onClosed) }, "lg-in").apply { isDaemon = true }.start()
        try {
            if (!bare) {
                // webOS 23+ wants a hello first and drops a register that comes without one; older sets answer it with an
                // error, harmless. Its answer is waited for, as aiowebostv does: nothing else goes while the TV is on it
                val h = Pending()
                pending[HELLO] = h
                send(JSONObject().put("id", HELLO).put("type", "hello").put("payload", JSONObject()))
                h.done.await(2000, TimeUnit.MILLISECONDS)
                pending.remove(HELLO)
                // and answers getSystemInfo only before the register (aiowebostv does the same); later it says no
                if (!s.closed) request("ssap://system/getSystemInfo", ms = 2000)?.let { model = it.optString("modelName") }
            }
            val key = if (knownPort == port) known.substringAfter(' ', "") else ""
            val manifest = JSONObject(MANIFEST)
            if (key.isNotEmpty()) manifest.put("client-key", key)
            val done = CountDownLatch(1)
            var got: JSONObject? = null
            subs[REG] = { m ->                                       // a "response" first means the prompt is on the TV: wait for the person
                if (m.optString("type") == "response") asking = true
                else { got = m; done.countDown() }
            }
            send(JSONObject().put("id", REG).put("type", "register").put("payload", manifest))
            val until = System.nanoTime() / 1_000_000 + PROMPT_S * 1000L
            while (got == null && System.nanoTime() / 1_000_000 < until && !closed && !s.closed) done.await(300, TimeUnit.MILLISECONDS)
            subs.remove(REG)
            asking = false
            val m = got ?: throw if (s.closed) HungUp() else IOException("nobody accepted on the TV")
            if (m.optString("type") == "error") throw Remote.Refused(m.optString("error").ifEmpty { "the TV said no" })
            val k = m.optJSONObject("payload")?.optString("client-key") ?: ""
            if (k.isEmpty()) throw Remote.Refused("the TV gave no client key")
            saved.put(host, "$port $k")
            live = true
        } catch (e: Exception) {
            val closedByTv = s.closed
            WireLog.all.add("·", "LG $host pairing: $e")
            asking = false
            subs.remove(REG)
            s.close(); ws = null
            if (closedByTv && e is IOException && e !is Remote.Refused) throw HungUp()   // a write into the dead socket counts too
            throw e
        }
        Thread({ learn() }, "lg-learn").apply { isDaemon = true }.start()   // not on the keys' thread: its answers can take seconds
    }

    /** Who the TV is and what it does now, once paired; each answer is optional (older firmware lacks some). */
    private fun learn() {
        if (model.isEmpty()) request("ssap://system/getSystemInfo")?.let { model = it.optString("modelName") }
        request("ssap://com.webos.service.update/getCurrentSWInformation")?.let {
            version = listOf(it.optString("product_name"), listOf(it.optString("major_ver"), it.optString("minor_ver")).filter { v -> v.isNotEmpty() }.joinToString(".")).filter { v -> v.isNotEmpty() }.joinToString(" ")
            if (model.isEmpty()) model = it.optString("model_name")
        }
        val net = request("ssap://com.webos.service.connectionmanager/getinfo")
        val st = request("ssap://com.webos.service.connectionmanager/getstatus")
        if (net != null) {
            val wired = st?.optJSONObject("wired")?.optString("state") == "connected"
            mac = (if (wired) net.optJSONObject("wiredInfo") else net.optJSONObject("wifiInfo"))?.optString("macAddress")?.uppercase()
                ?: net.optJSONObject("wiredInfo")?.optString("macAddress")?.uppercase() ?: ""
        }
        subscribe("ssap://audio/getVolume") { p ->
            val v = p.optJSONObject("volumeStatus")
            val level = v?.optInt("volume", -1)?.takeIf { it >= 0 } ?: p.optInt("volume", -1)
            val muted = if (v != null && v.has("muteStatus")) v.optBoolean("muteStatus") else p.optBoolean("muted", p.optBoolean("mute"))
            external = (v?.optString("soundOutput") ?: p.optString("scenario")).let { it.isNotEmpty() && !it.contains("tv_speaker") && !it.contains("tv_external_speaker") }
            if (level >= 0) volume = Triple(level, v?.optInt("maxVolume", 100)?.takeIf { it > 0 } ?: 100, muted)
        }
        subscribe("ssap://com.webos.applicationManager/getForegroundAppInfo") { p ->
            app = p.optString("appId")
            if (powerUnknown) isOn = app.isNotEmpty() || isOn != false
        }
        if (!subscribe("ssap://com.webos.service.tvpower/power/getPowerState") { p ->
                val st2 = p.optString("state")
                if (st2.isNotEmpty()) isOn = st2 !in OFF                   // "Screen Off" is on: the sound plays, any key lights it
            }) { powerUnknown = true; if (isOn == null) isOn = true }      // older firmware: connected at all means on
        if (isOn == null) isOn = true
    }
    @Volatile private var powerUnknown = false

    private fun read(s: WebSocket, onClosed: () -> Unit) {
        try {
            while (true) {
                val text = s.read() ?: break
                heard = true
                WireLog.all.add("←", "LG $host $text")
                val m = try { JSONObject(text) } catch (e: Exception) { continue }
                val id = m.optString("id")
                subs[id]?.let { f -> try { f(m) } catch (ignored: Exception) { } }
                pending.remove(id)?.let { p -> p.got = m; p.done.countDown() }
            }
        } catch (e: Exception) {
            WireLog.all.add("·", "LG $host read: $e")
        }
        WireLog.all.add("·", "LG $host closed" + if (s.closeWhy.isNotEmpty()) " (${s.closeWhy})" else "")
        val was = live
        live = false
        for (p in pending.values) p.done.countDown()
        if (was && !closed) onClosed()
    }

    private fun send(m: JSONObject) {
        val s = ws ?: throw IOException("not connected")
        val t = m.toString()
        WireLog.all.add("→", "LG $host $t")                          // before: the answer can come back first
        s.send(t)
    }

    /** One request and its answer's payload; null when the TV says no or does not answer in [ms]. */
    fun request(uri: String, payload: JSONObject? = null, ms: Long = 5000): JSONObject? {
        val id = "r" + ids.incrementAndGet()
        val p = Pending()
        pending[id] = p
        try {
            send(JSONObject().put("id", id).put("type", "request").put("uri", uri).apply { if (payload != null) put("payload", payload) })
            p.done.await(ms, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            return null
        } finally {
            pending.remove(id)
        }
        val m = p.got ?: return null
        if (m.optString("type") == "error") return null
        val body = m.optJSONObject("payload") ?: JSONObject()
        return if (body.optBoolean("returnValue", true)) body else null
    }

    /** A request whose answer nobody waits for. */
    private fun fire(uri: String) { try { send(JSONObject().put("id", "f" + ids.incrementAndGet()).put("type", "request").put("uri", uri)) } catch (ignored: IOException) { } }

    /** Follows [uri]: its first answer and every change after. False when the TV does not have it. */
    private fun subscribe(uri: String, on: (JSONObject) -> Unit): Boolean {
        val id = "s" + ids.incrementAndGet()
        val first = Pending()
        pending[id] = first
        subs[id] = { m -> if (m.optString("type") != "error") m.optJSONObject("payload")?.let { if (it.optBoolean("returnValue", true)) on(it) } }
        try { send(JSONObject().put("id", id).put("type", "subscribe").put("uri", uri)) } catch (e: Exception) { subs.remove(id); return false }
        first.done.await(5000, TimeUnit.MILLISECONDS)
        val m = first.got
        if (m == null || m.optString("type") == "error" || m.optJSONObject("payload")?.optBoolean("returnValue", true) == false) { subs.remove(id); return false }
        return true
    }

    // ---------------------------------------------------------------- keys
    /** The Magic Remote's socket, opened once and again when it drops. */
    private fun pointer(): WebSocket {
        pointer?.let { if (!it.closed) return it }
        val path = request("ssap://com.webos.service.networkinput/getPointerInputSocket")?.optString("socketPath")
            ?: throw IOException("the TV gave no input socket")
        val u = URL(path.replaceFirst(Regex("^ws"), "http"))
        val tls = path.startsWith("wss")
        val p = WebSocket.open(u.host, if (u.port > 0) u.port else if (tls) 443 else 80, u.file.ifEmpty { "/" }, tls)
        p.timeout(0)
        Thread({ try { while (p.read() != null) { } } catch (ignored: Exception) { } }, "lg-pointer").apply { isDaemon = true }.start()   // answers its pings
        pointer = p
        return p
    }

    private fun button(name: String) {
        WireLog.all.add("→", "LG $host button $name")
        try { pointer().send("type:button\nname:$name\n\n") } catch (e: IOException) {
            pointer?.close(); pointer = null
            try { pointer().send("type:button\nname:$name\n\n") } catch (ignored: IOException) { }   // once more on a fresh socket
        }
    }

    // The Magic Remote's pointer on the same socket as the buttons. Sent off the caller's thread: a gyro callback must not wait on the network.
    private fun pointerSend(m: String) = out.execute {
        try { pointer().send(m) } catch (e: IOException) { pointer?.close(); pointer = null }   // the next move opens a fresh one
    }
    @Volatile private var pointerDown = 0
    override fun pointer(dx: Int, dy: Int) { if (ready()) pointerSend("type:move\ndx:$dx\ndy:$dy\ndown:$pointerDown\n\n") }   // down:1 moves drag
    override fun pointerHold(on: Boolean) { pointerDown = if (on) 1 else 0; pointer(0, 0) }
    override fun pointerClick() { if (ready()) pointerSend("type:click\n\n") }
    override fun pointerScroll(dy: Int) { if (ready()) pointerSend("type:scroll\ndx:0\ndy:${-dy}\n\n") }   // LG's dy grows downward

    /** LG has no held keys: a key held here is sent again every [REPEAT_MS] until let go. */
    override fun key(code: Int, direction: Int) {
        if (closed || !KEYS.containsKey(code)) return
        when (direction) {
            Atv.UP -> { held.remove(code)?.cancel(false); return }
            Atv.DOWN -> {
                held.remove(code)?.cancel(false)
                if (code in REPEATS) held[code] = out.scheduleWithFixedDelay({ tap(code) }, REPEAT_MS, REPEAT_MS, TimeUnit.MILLISECONDS)
            }
        }
        out.execute { tap(code) }
    }

    private fun tap(code: Int) {
        if (!ready()) return
        when (code) {
            26, 223 -> if (isOn != false) fire("ssap://system/turnOff")   // a TV shutting down may never answer
            224 -> { }                                                // waking is Wake-on-LAN's job: the TV is not on the network while off
            66 -> request("ssap://com.webos.service.ime/sendEnterKey")
            67 -> request("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", 1))
            164 -> request("ssap://audio/setMute", JSONObject().put("mute", volume?.third != true))
            170 -> request("ssap://system.launcher/launch", JSONObject().put("id", "com.webos.app.livetv"))
            85 -> { playing = !playing; button(if (playing) "PLAY" else "PAUSE") }
            else -> button(KEYS[code] ?: return)
        }
    }

    // ---------------------------------------------------------------- text, apps, volume
    /** Into the focused field on the TV, at the cursor. */
    override fun text(t: String) { out.execute { request("ssap://com.webos.service.ime/insertText", JSONObject().put("text", t).put("replace", 0)) } }

    override fun type(del: Int, add: String, enter: Boolean) {
        out.execute {
            if (del > 0) request("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", del))
            if (add.isNotEmpty()) request("ssap://com.webos.service.ime/insertText", JSONObject().put("text", add).put("replace", 0))
            if (enter) request("ssap://com.webos.service.ime/sendEnterKey")
        }
    }

    /** An app by its id ("netflix", "youtube.leanback.v4"), or a link in the TV's browser. */
    override fun launch(link: String) {
        out.execute {
            if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(link)) {
                val yt = Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([\\w-]{6,})").find(link)?.groupValues?.get(1)
                if (yt != null) request("ssap://system.launcher/launch", JSONObject().put("id", "youtube.leanback.v4").put("contentId", yt))
                else request("ssap://system.launcher/open", JSONObject().put("target", link))
            } else request("ssap://system.launcher/launch", JSONObject().put("id", link))
        }
    }

    override fun apps(): List<Remote.App> {
        val list = request("ssap://com.webos.applicationManager/listLaunchPoints", ms = 8000)?.optJSONArray("launchPoints") ?: return emptyList()
        val outList = ArrayList<Remote.App>()
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            val id = a.optString("id"); if (id.isEmpty()) continue
            val icon = a.optString("icon").ifEmpty { a.optString("largeIcon") }
            if (icon.isNotEmpty()) icons[id] = icon
            outList.add(Remote.App(id, a.optString("title").ifEmpty { id }, icon))
        }
        return outList
    }

    /** The picture the app list named, fetched from the TV (over TLS too, its own certificate). */
    override fun icon(id: String): ByteArray? {
        val at = icons[id] ?: run { apps(); icons[id] } ?: return null
        return try {
            val c = dev.tvremote.core.Lan.open(URL(at)) as HttpURLConnection
            if (c is HttpsURLConnection) {
                c.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(WebSocket.TRUST_ANY), null) }.socketFactory
                c.setHostnameVerifier { _, _ -> true }
            }
            c.connectTimeout = 3000; c.readTimeout = 5000
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (e: Exception) { null }
    }

    override fun setVolume(level: Int): Boolean {
        if (!ready()) return false
        if (!external && request("ssap://audio/setVolume", JSONObject().put("volume", level)) != null) return true
        // a soundbar on HDMI ARC / optical takes only steps, and drops those that come too close together
        for (i in 0 until 60) {
            val now = volume?.first ?: return false
            if (now == level) return true
            request(if (now < level) "ssap://audio/volumeUp" else "ssap://audio/volumeDown")
            Thread.sleep(300)
        }
        return true
    }

    /** YouTube's TV app opens a search from a contentTarget URL (what LG's own voice search hands it). */
    override fun search(q: String): Boolean {
        val target = "https://www.youtube.com/tv?#/search?q=" + java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        return request("ssap://system.launcher/launch", JSONObject().put("id", "youtube.leanback.v4").put("params", JSONObject().put("contentTarget", target))) != null
    }

    /** The screen off, the sound on (webOS 4.5+; "Screen Off" in the power state until a key lights it again). */
    private val inputList = Inputs.Cache()
    override fun inputs(): List<Inputs.Input> = inputList.get { Inputs.parseLg(request("ssap://tv/getExternalInputList")) }
    override fun switchInput(id: String): Boolean = Inputs.valid(id) && request("ssap://tv/switchInput", JSONObject().put("inputId", id)) != null

    override fun screenOff(): Boolean = request("ssap://com.webos.service.tvpower/power/turnOffScreen") != null

    override fun close() {
        closed = true
        live = false
        out.shutdownNow()
        val a = ws; val b = pointer
        Thread { a?.close(); b?.close() }.start()                      // may be called on the UI thread
    }

    companion object {
        val PORTS = listOf(3000 to false, 3001 to true)
        const val PROMPT_S = 60
        private val OFF = setOf("Power Off", "Suspend", "Active Standby")    // getPowerState's off states (aiowebostv)
        const val REPEAT_MS = 180L
        private const val REG = "register_0"
        private const val HELLO = "hello_0"
        private val REPEATS = setOf(19, 20, 21, 22, 24, 25, 166, 167, 89, 90, 67)

        /** Android key codes to the Magic Remote's button names; the specials (power, typing, mute, play / pause) are requests. */
        val KEYS: Map<Int, String> = HashMap<Int, String>().apply {
            putAll(mapOf(19 to "UP", 20 to "DOWN", 21 to "LEFT", 22 to "RIGHT", 23 to "ENTER", 4 to "BACK", 3 to "HOME", 82 to "MENU",
                176 to "MENU", 165 to "INFO", 172 to "GUIDE", 175 to "CC", 86 to "STOP", 89 to "REWIND", 90 to "FASTFORWARD",
                24 to "VOLUMEUP", 25 to "VOLUMEDOWN", 166 to "CHANNELUP", 167 to "CHANNELDOWN",
                26 to "", 223 to "", 224 to "", 66 to "", 67 to "", 164 to "", 170 to "", 85 to ""))
            for (d in 0..9) put(7 + d, d.toString())
        }

        /** What LG's own test client sends: the TV checks the signature, so it goes as it is (aiowebostv, lgtv2 and the others do the same). */
        private val MANIFEST: String = JSONObject().put("forcePairing", false).put("pairingType", "PROMPT").put("manifest", JSONObject()
            .put("manifestVersion", 1).put("appVersion", "1.1")
            .put("signed", JSONObject().put("created", "20140509").put("appId", "com.lge.test").put("vendorId", "com.lge")
                .put("localizedAppNames", JSONObject().put("", "LG Remote App").put("ko-KR", "리모컨 앱").put("zxx-XX", "ЛГ Rэмotэ AПП"))
                .put("localizedVendorNames", JSONObject().put("", "LG Electronics"))
                .put("permissions", JSONArray(listOf("TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS", "READ_LGE_SDX",
                    "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT", "CONTROL_POWER", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS",
                    "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME")))
                .put("serial", "2f930e2d2cfe083771f68e4fe7bb07"))
            .put("permissions", JSONArray(listOf("LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED", "CONTROL_AUDIO",
                "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
                "CONTROL_POWER", "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
                "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE", "READ_COUNTRY_INFO", "READ_SETTINGS", "CONTROL_TV_SCREEN",
                "CONTROL_TV_STANBY", "CONTROL_FAVORITE_GROUP", "CONTROL_USER_INFO", "CHECK_BLUETOOTH_DEVICE", "CONTROL_BLUETOOTH", "CONTROL_TIMER_INFO",
                "STB_INTERNAL_CONNECTION", "CONTROL_RECORDING", "READ_RECORDING_STATE", "WRITE_RECORDING_LIST", "READ_RECORDING_LIST",
                "READ_RECORDING_SCHEDULE", "WRITE_RECORDING_SCHEDULE", "READ_STORAGE_DEVICE_LIST", "READ_TV_PROGRAM_INFO", "CONTROL_BOX_CHANNEL",
                "READ_TV_ACR_AUTH_TOKEN", "READ_TV_CONTENT_STATE", "READ_TV_CURRENT_TIME", "ADD_LAUNCHER_CHANNEL", "SET_CHANNEL_SKIP",
                "RELEASE_CHANNEL_SKIP", "CONTROL_CHANNEL_BLOCK", "DELETE_SELECT_CHANNEL", "CONTROL_CHANNEL_GROUP", "SCAN_TV_CHANNELS",
                "CONTROL_TV_POWER", "CONTROL_WOL")))
            .put("signatures", JSONArray().put(JSONObject().put("signatureVersion", 1).put("signature",
                "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw=="))))
            .toString()
    }
}
