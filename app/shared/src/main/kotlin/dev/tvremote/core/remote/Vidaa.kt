package dev.tvremote.core.remote
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import dev.tvremote.core.B64
import dev.tvremote.core.WireLog
import dev.tvremote.core.Wol

/**
 * Hisense (and Toshiba) TVs on VIDAA OS: MQTT on port 36669, the way Hisense's own "VIDAA Smart TV" app talks to them (worked
 * out by github.com/stevene1919/hisense_vidaa, MIT). Newer TVs only take a TLS client certificate from Hisense's apps, which we
 * never ship: it is downloaded the first time a TV wants it, from the copies already public on GitHub ([PUBLIC], pinned), or
 * read from Hisense's app (the phone, when it is installed; the computer, from the app's .apk or a .p12 the person picks:
 * [importCert]); [NO_CERT] when none of that worked. Pairing is a 4-digit code on the TV ([startPairing] / [finishPairing]), which gives a
 * token kept per TV in [dir]; TVs from before 2022 take a fixed login and no code.
 */
class Vidaa(private val dir: File, private val host: String, private val port: Int = 36669,
            private val wol: (mac: String, lan: String) -> Unit = { mac, lan -> Wol.send(mac, lan) },   // tests count the packets
            private val wakeWaitMs: Long = 30_000, private val wakePollMs: Long = 2_000) : Remote, CodePairing {
    override val kind = Remote.Kind.VIDAA
    @Volatile override var model = ""
        private set
    @Volatile override var vendor = "Hisense"
        private set
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
    @Volatile private var macs: List<String> = emptyList()           // every interface the TV named; [mac] is the first
    override val keys: Set<Int> get() = KEYS.keys

    @Volatile private var mqtt: MqttLite? = null
    @Volatile private var live = false
    @Volatile private var closed = false
    @Volatile private var apps: List<JSONObject> = emptyList()
    @Volatile private var sources: List<JSONObject> = emptyList()
    private val waits = CopyOnWriteArrayList<Pair<(String) -> Boolean, ArrayBlockingQueue<Pair<String, String>>>>()   // no CompletableFuture: Android 5 and 6

    private val me: String = id(dir)                                  // our made-up MAC: the TV knows this phone / computer by it
    private val cid = clientId(me)
    private val tokenFile = File(dir, "vidaa-" + host.replace(Regex("[^\\w.-]"), "_") + ".json")

    override fun ready(): Boolean = live && !closed

    // ---------------------------------------------------------------- connecting

    /** Blocks until keys go through. [Atv.NotPaired]: the TV wants its code first; [NO_CERT]: Hisense's certificate is missing. */
    override fun connect(onClosed: () -> Unit) {
        wakeUp()
        val saved = load()
        if (saved != null) {
            val user = saved.optString("user")
            var token = saved.optString("access")
            if (saved.optString("profile") != "legacy" && expired(saved, "access")) token = refresh(saved) ?: throw forget()
            try {
                open(user, token, onClosed)
            } catch (e: MqttLite.Rejected) {
                if (saved.optString("profile") == "legacy") throw forget()
                open(user, refresh(saved) ?: throw forget(), onClosed)   // the TV forgets tokens when it sleeps deeply
            }
            return
        }
        // Never paired here: a TV from before 2022 takes the fixed login, and only asks for a code if it wants one.
        val m = try { session(STATIC_USER, STATIC_PASS) } catch (e: MqttLite.Rejected) { throw Atv.NotPaired() }
        val asked = expect(3000, { it.endsWith("/ui_service/data/authentication") }) { m.publish(ui("gettvstate")) }
        m.close()
        if (asked != null) { remember("static", true); throw Atv.NotPaired() }
        save(JSONObject().put("profile", "legacy").put("user", STATIC_USER).put("access", STATIC_PASS))
        open(STATIC_USER, STATIC_PASS, onClosed)
    }

    /** A TV in standby refuses the port: with its MAC kept from an earlier visit, wake it and wait (up to [wakeWaitMs]) for the port; else go on, and the caller gets the usual error. */
    private fun wakeUp() {
        val kept = LinkedHashSet<String>()                            // "mac" is what old versions wrote: still read
        (remembered("mac") as? String)?.let { normMac(it) }?.let { kept.add(it) }
        (remembered("macs") as? JSONArray)?.let { for (i in 0 until it.length()) normMac(it.optString(i))?.let { m -> kept.add(m) } }
        if (kept.isEmpty()) return
        macs = kept.toList()
        if (mac.isEmpty()) mac = macs.first()
        if (portOpen()) return
        WireLog.all.add("→", "VIDAA $host refuses port $port: Wake-on-LAN to " + macs.joinToString(" "))
        try { for (m in macs) wol(m, host) } catch (e: Exception) { return }
        val until = System.currentTimeMillis() + wakeWaitMs
        while (System.currentTimeMillis() < until) {
            try { Thread.sleep(wakePollMs) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); return }
            if (portOpen()) return
        }
    }

    private fun forget(): Atv.NotPaired {
        tokenFile.delete()
        WireLog.all.add("·", "VIDAA $host: the token is no longer taken, pair again")
        return Atv.NotPaired()
    }

    private fun open(user: String, pass: String, onClosed: () -> Unit) {
        val m = session(user, pass, onClosed)
        mqtt = m
        live = true
        m.subscribe(BROADCAST + "ui_service/state", BROADCAST + "platform_service/actions/volumechange", BROADCAST + "ui_service/volume",
            BROADCAST + "platform_service/actions/tvsleep")
        for (a in listOf("gettvstate", "sourcelist", "applist")) m.publish(ui(a))
        m.publish(platform("getvolume"))
        // the name before connect() returns: the phone saves it with the TV right after (#8: a TV added by address kept no name)
        expect(1500, { it.endsWith("/platform_service/data/getdeviceinfo") || it.endsWith("/platform_service/data/gettvinfo") }) {
            for (a in listOf("getdeviceinfo", "gettvinfo")) m.publish(platform(a))
        }
    }

    /** A logged-in MQTT link (our own topics subscribed), over the TLS the TV takes: with the certificate, without, or plain. */
    private fun session(user: String, pass: String, onClosed: () -> Unit = {}): MqttLite {
        val ways = ways()
        var failed: Exception? = null
        login(ways, user, pass, onClosed) { failed = it }?.let { return it }
        if (!portOpen()) throw IOException("the TV did not answer on port $port")
        if (fetchCerts(dir)) login(ways() - ways.toSet(), user, pass, onClosed) { failed = it }?.let { return it }   // a TV that wants a certificate: only now downloaded
        if (ways().none { it != "tls" && it != "plain" }) throw IOException(NO_CERT)
        throw IOException("cannot reach the TV's remote service: " + (failed?.message ?: "?"))
    }

    /** "cert" (Hisense's app's own), the downloaded ones by name, then TLS without a certificate and plain TCP. */
    private fun ways(): List<String> = buildList {
        if (cert(dir) != null) add("cert")
        for (c in PUBLIC) if (pem(dir, c.name) != null) add(c.name)
        add("tls"); add("plain")
    }

    private fun login(ways: List<String>, user: String, pass: String, onClosed: () -> Unit, failed: (Exception) -> Unit): MqttLite? {
        val last = remembered("tls")
        for (way in ways.sortedBy { if (it == last) 0 else 1 }) {
            val m = try {
                MqttLite(socket(way), ::message) { if (live) { live = false; WireLog.all.add("·", "VIDAA $host closed the link"); if (!closed) onClosed() } }
            } catch (e: Exception) { failed(e); continue }
            try {
                m.connect(cid, user, pass)
            } catch (e: MqttLite.Rejected) {
                m.close(); WireLog.all.add("←", "VIDAA $host ($way) refused the login: code ${e.code}"); remember("tls", way); throw e
            } catch (e: Exception) { m.close(); failed(e); continue }
            WireLog.all.add("←", "VIDAA $host connected ($way)")
            remember("tls", way)
            // the wildcard, and each answer by name: some firmwares refuse a wildcard (SUBACK 128) and send nothing through it
            // (issue #8: the TV showed its code, we never heard); one packet, the TV answers each topic on its own
            m.subscribe(*(listOf("/remoteapp/mobile/$cid/#") + MINE.map { "/remoteapp/mobile/$cid/$it" }).toTypedArray())
            return m
        }
        return null
    }

    private fun socket(way: String): Socket {
        val raw = MqttLite.dial(host, port, 4000)
        if (way == "plain") return raw
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(if (way == "cert") cert(dir) else if (way == "tls") null else pem(dir, way), arrayOf(TRUST_ALL), SecureRandom())   // the TV's own certificate is self-made
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = 6000
        try { s.startHandshake() } catch (e: Exception) { try { s.close() } catch (ignored: Exception) { }; throw e }
        s.soTimeout = 0
        return s
    }

    private fun portOpen(): Boolean = try { MqttLite.dial(host, port, 2500).close(); true } catch (e: Exception) { false }

    // ---------------------------------------------------------------- pairing: a 4-digit code on the TV

    @Volatile private var pairing: MqttLite? = null
    @Volatile private var pairUser = ""
    @Volatile private var pairStatic = false

    /** Logs in the way this TV takes and makes it show its code. */
    override fun startPairing() {
        pairing?.close(); pairing = null
        val (ts, protocol) = tvClock()
        val profiles = when {
            remembered("static") == true -> listOf("static")
            protocol in 3000 until 3290 -> listOf("middle", "modern", "remotenow", "static")
            protocol in 1 until 3000 -> listOf("remotenow", "middle", "modern", "static")
            else -> listOf("modern", "middle", "remotenow", "static")
        }
        var why: IOException? = null
        for (p in profiles) {
            val (user, pass) = if (p == "static") STATIC_USER to STATIC_PASS else credentials(me, ts, p)
            val m = try { session(user, pass) } catch (e: MqttLite.Rejected) { continue }
            catch (e: IOException) { if (!portOpen()) throw e; why = e; continue }   // some firmwares hang up on a login they do not take instead of refusing it (issue #8)
            pairing = m; pairUser = user; pairStatic = p == "static"
            val shown = if (p == "static") expect(8000, { it.endsWith("/ui_service/data/authentication") }) { m.publish(ui("gettvstate")) }
            else (0 until 3).firstNotNullOfOrNull {
                expect(4000, { t -> t.endsWith("/ui_service/data/authentication") || t.endsWith("/ui_service/data/vidaa_app_connect") }) {
                    m.publish(ui("vidaa_app_connect"), """{"app_version":2,"connect_result":0,"device_type":"Mobile App"}""")
                }
            }
            if (shown == null) { m.close(); pairing = null; why = IOException("the TV did not show a code"); continue }   // logged in, no code: the next kind may show it (issue #8)
            WireLog.all.add("←", "VIDAA $host shows its code ($p)")
            return
        }
        throw why ?: IOException("the TV did not take any kind of login")
    }

    /** IllegalArgumentException: wrong code. Saves the token the TV gives; [connect] then logs in with it. */
    override fun finishPairing(code: String) {
        val m = pairing ?: throw IllegalStateException("startPairing first")
        // listened for before the code goes: some firmwares send the token as soon as they take the code, before asked (issue #8)
        val token = ArrayBlockingQueue<Pair<String, String>>(1)
        val tokenWait = { t: String -> t.endsWith("/platform_service/data/tokenissuance") || t.endsWith("/platform_service/data/gettoken") } to token
        waits.add(tokenWait)
        try {
            val n = code.filter { it.isDigit() }.toIntOrNull() ?: throw IllegalArgumentException("wrong code")
            val (topic, said) = expect(15_000, { it.endsWith("/ui_service/data/authenticationcode") || it.endsWith("/ui_service/data/authenticationcodeclose") }) {
                m.publish(ui("authenticationcode"), JSONObject().put("authNum", n).toString())
            } ?: throw IOException("the TV did not answer the code")
            if (topic.endsWith("close")) throw IOException("the TV closed its code")
            if (json(said)?.optInt("result") != 1) throw IllegalArgumentException("wrong code")
            if (pairStatic) {
                save(JSONObject().put("profile", "legacy").put("user", STATIC_USER).put("access", STATIC_PASS))
                return
            }
            val (_, tokens) = token.poll() ?: run {
                m.publish(platform("gettoken", "data"), """{"refreshtoken":""}""")
                m.publish(ui("authenticationcodeclose"))
                token.poll(15_000, TimeUnit.MILLISECONDS)
            } ?: throw IOException("the TV gave no token")
            val t = json(tokens)?.takeIf { it.optString("accesstoken").isNotEmpty() } ?: throw IOException("the TV gave no token")
            save(fromTokens(t).put("profile", "dynamic").put("user", pairUser))
            WireLog.all.add("←", "VIDAA $host paired")
        } finally {
            waits.remove(tokenWait); m.close(); pairing = null
        }
    }

    /** A new token for the refresh token (good 30 days); null when the TV no longer takes it, IOException when it did not answer (the pairing is kept). */
    private fun refresh(saved: JSONObject): String? {
        val r = saved.optString("refresh").ifEmpty { return null }
        if (expired(saved, "refresh")) return null
        val m = try { session(saved.optString("user"), r) } catch (e: MqttLite.Rejected) { return null }
        try {
            val (_, said) = expect(10_000, { it.endsWith("/platform_service/data/tokenissuance") || it.endsWith("/platform_service/data/gettoken") }) {
                m.publish(platform("gettoken", "data"), JSONObject().put("refreshtoken", r).toString())
            } ?: throw IOException("the TV did not renew its token")
            val t = json(said)?.takeIf { it.optString("accesstoken").isNotEmpty() } ?: return null
            if (t.optString("refreshtoken").isEmpty()) t.put("refreshtoken", r).put("refreshtoken_time", saved.optLong("refresh_time")).put("refreshtoken_duration_day", saved.optInt("refresh_days", 30))
            save(fromTokens(t).put("profile", "dynamic").put("user", saved.optString("user")))
            WireLog.all.add("←", "VIDAA $host renewed its token")
            return t.optString("accesstoken")
        } finally { m.close() }
    }

    private fun fromTokens(t: JSONObject): JSONObject {
        val now = System.currentTimeMillis() / 1000
        return JSONObject().put("access", t.optString("accesstoken")).put("refresh", t.optString("refreshtoken"))
            .put("access_time", t.optLong("accesstoken_time", now)).put("access_days", t.optInt("accesstoken_duration_day", 2))
            .put("refresh_time", t.optLong("refreshtoken_time", now)).put("refresh_days", t.optInt("refreshtoken_duration_day", 30))
    }

    private fun expired(o: JSONObject, what: String): Boolean {
        val at = o.optLong(what + "_time"); val days = o.optInt(what + "_days")
        return at > 0 && days > 0 && at + days * 86400L < System.currentTimeMillis() / 1000
    }

    /** The TV's clock (its web server's Date header: the login is made from it) and its protocol number; ours when it does not say. */
    private fun tvClock(): Pair<Long, Int> {
        for (p in listOf(38400, 18400)) try {
            val c = dev.tvremote.core.Lan.open(URL("http://$host:$p/MediaServer/rendererdevicedesc.xml")) as HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 1500
            try {
                val body = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                val ts = c.getHeaderFieldDate("Date", 0L) / 1000
                val proto = Regex("transport_protocol>\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (ts > 0) return ts to proto
            } finally { c.disconnect() }
        } catch (ignored: Exception) { }
        return System.currentTimeMillis() / 1000 to 0
    }

    // ---------------------------------------------------------------- what the TV says

    private fun message(topic: String, payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8)
        val o = json(text)
        when {
            topic.endsWith("/ui_service/state") || topic.endsWith("/ui_service/data/gettvstate") || topic.endsWith("/ui_service/data/state") -> o?.let { state(it) }
            topic.endsWith("/volumechange") || topic.endsWith("/ui_service/volume") || topic.endsWith("/platform_service/data/getvolume") -> o?.let { vol(it) }
            topic.endsWith("/actions/tvsleep") -> isOn = false
            topic.endsWith("/ui_service/data/sourcelist") -> sources = list(text, "sourcelist")
            topic.endsWith("/ui_service/data/applist") -> apps = list(text, "applist")
            topic.endsWith("/platform_service/data/getdeviceinfo") || topic.endsWith("/platform_service/data/gettvinfo") -> o?.let { device(it) }
        }
        for (w in waits) if (w.first(topic)) w.second.offer(topic to text)   // after the facts above are set: a wait that returns sees them (the name)
    }

    private fun state(o: JSONObject) {
        val st = o.optString("statetype").lowercase()
        isOn = when {
            "off" in st || st == "fake_sleep_0" -> false
            st.isNotEmpty() -> true
            else -> isOn
        }
        app = if (st == "app") o.optString("name").ifEmpty { o.optString("appId") } else ""
    }

    private fun vol(o: JSONObject) {
        val v = volume ?: Triple(0, 100, false)
        val type = o.opt("volume_type")?.toString()
        val value = o.optString("volume_value").ifEmpty { o.optString("volume") }.toIntOrNull()
        volume = when {
            type == "2" -> v.copy(third = value == 1)
            value != null && (type == null || type == "0" || type == "1") -> v.copy(first = value)
            else -> v
        }
    }

    private fun device(o: JSONObject) {
        (NAME_FIELDS.map { o.optString(it) } + o.optString("model_name") + o.optString("model"))   // the name the TV gives itself, set by its owner, before the model
            .firstOrNull { it.isNotBlank() }?.let { model = it.trim() }
        o.optString("brand").ifEmpty { o.optString("manufacturer") }.takeIf { it.isNotBlank() }?.let { vendor = it.trim() }
        o.optString("firmware_version").ifEmpty { o.optString("sw_version") }.takeIf { it.isNotBlank() }?.let { version = it.trim() }
        val found = LinkedHashSet<String>()                           // the field's name changes between firmwares: *mac*, or eth0 / wlan0 (VIDAA U6)
        val keys = o.keys().asSequence().sortedBy { it.lowercase().let { l -> if (l == "eth0") 0 else if (l == "wlan0") 1 else 2 } }.toList()   // the cable first: JSON keys come in no order
        for (k in keys) if ("mac" in k.lowercase() || k.lowercase() in IFACES) normMac(o.optString(k))?.let { found.add(it) }
        if (found.isEmpty()) return
        val all = LinkedHashSet<String>(); all.addAll(macs); all.addAll(found)
        macs = all.toList()
        if (mac.isEmpty()) mac = macs.first()
        remember("mac", mac)                                          // standby refuses the port: connect needs them to wake the TV
        remember("macs", JSONArray(macs))                             // both interfaces: the TV may sleep on Wi-Fi or on the cable
    }

    /** Waits up to [ms] for a message whose topic [match]es, after [trigger]; (topic, payload) or null. */
    private fun expect(ms: Long, match: (String) -> Boolean, trigger: () -> Unit): Pair<String, String>? {
        val q = ArrayBlockingQueue<Pair<String, String>>(1)
        val w = match to q
        waits.add(w)
        try {
            trigger()
            return q.poll(ms, TimeUnit.MILLISECONDS)
        } finally { waits.remove(w) }
    }

    // ---------------------------------------------------------------- commands

    private fun ui(action: String) = "/remoteapp/tv/ui_service/$cid/actions/$action"
    private fun platform(action: String, under: String = "actions") = "/remoteapp/tv/platform_service/$cid/$under/$action"

    private val out = Executors.newSingleThreadExecutor { Thread(it, "vidaa-out").apply { isDaemon = true } }   // keys come from the UI thread

    private fun send(topic: String, payload: String = "") {
        val m = mqtt ?: return
        if (!ready()) return
        try {
            out.execute { try { m.publish(topic, payload); WireLog.all.add("→", "VIDAA $host " + topic.substringAfterLast("/") + " " + payload.take(80)) } catch (e: IOException) { m.drop() } }
        } catch (closing: java.util.concurrent.RejectedExecutionException) { }   // close() on another thread meanwhile
    }

    override fun key(code: Int, direction: Int) {
        if (direction == Atv.UP) return                               // the TV takes taps only
        val name = KEYS[code] ?: return
        send("/remoteapp/tv/remote_service/$cid/actions/sendkey", name)
        if (code == 26 && isOn != null) isOn = !isOn!!
    }

    /** The backlight off, the sound on: KEY_AUDIO; the same key or any other brings the picture back. False when not connected. */
    override fun screenOff(): Boolean {
        if (!ready()) return false
        send("/remoteapp/tv/remote_service/$cid/actions/sendkey", "KEY_AUDIO")
        return true
    }

    /** Into the text field on screen (and the browser's address bar, which listens elsewhere). */
    override fun text(t: String) {
        val p = JSONObject().put("text", t).put("action", "insert").toString()
        send(platform("txtinputdata"), p)
        send(platform("bwsinputdata"), p)
    }

    /** An app by its id or name from [apps]. */
    override fun launch(link: String) {
        val a = pickApp(apps.ifEmpty { fetch("applist", "applist") }, link) ?: return
        send(ui("launchapp"), JSONObject().put("appId", a.optString("appId")).put("name", a.optString("name")).put("url", a.optString("url"))
            .put("urlType", a.opt("urlType") ?: 37).put("storeType", a.opt("storeType") ?: 0).put("appName", a.optString("name")).put("appUrl", a.optString("url")).toString())
    }

    override fun apps(): List<Remote.App> = fetch("applist", "applist").mapNotNull { a ->
        val id = a.optString("appId").ifEmpty { return@mapNotNull null }
        Remote.App(id, a.optString("name").ifEmpty { id })
    }

    override fun setVolume(level: Int): Boolean {
        if (!ready()) return false
        send(platform("changevolume"), level.coerceIn(0, 100).toString())
        volume = (volume ?: Triple(0, 100, false)).copy(first = level.coerceIn(0, 100))
        return true
    }

    override fun inputs(): List<Inputs.Input> = fetch("sourcelist", "sourcelist").mapNotNull { s ->
        val id = s.optString("sourceid").ifEmpty { s.optString("sourcename") }.ifEmpty { return@mapNotNull null }
        val name = s.optString("sourcename").ifEmpty { s.optString("displayname") }.ifEmpty { id }
        val lower = name.lowercase()
        val kind = when { "hdmi" in lower -> "hdmi"; lower == "tv" || "dtv" in lower || "atv" in lower -> "tuner"; "av" in lower || "component" in lower -> "av"; else -> "other" }
        val label = s.optString("displayname2").takeIf { it.isNotBlank() && it != name }?.let { "$name · $it" } ?: name
        Inputs.Input(id, kind, if (kind == "hdmi") Regex("(\\d+)").find(name)?.value?.toIntOrNull() ?: 0 else 0, s.optString("displayname2"), label,
            s.opt("is_signal")?.toString()?.let { it == "1" || it == "true" })
    }

    override fun switchInput(id: String): Boolean {
        if (!ready()) return false
        val s = sources.firstOrNull { it.optString("sourceid") == id } ?: return false
        send(ui("changesource"), JSONObject().put("sourceid", id).put("sourcename", s.optString("sourcename").ifEmpty { id }).toString())
        return true
    }

    /** The list as the TV last sent it, asked for again (and waited on briefly) when there is none yet. */
    private fun fetch(action: String, field: String): List<JSONObject> {
        val have = if (field == "applist") apps else sources
        if (have.isNotEmpty() || !ready()) return have
        val got = expect(3000, { it.endsWith("/ui_service/data/$action") }) { send(ui(action)) } ?: return emptyList()   // not on the UI thread: it waits
        return list(got.second, field)
    }

    override fun close() {
        closed = true
        live = false
        val a = mqtt; val b = pairing
        mqtt = null; pairing = null
        out.shutdown()
        Thread { a?.close(); b?.close() }.start()                      // may be called on the UI thread
    }

    // ---------------------------------------------------------------- saved state

    private fun load(): JSONObject? = try { if (tokenFile.isFile) JSONObject(tokenFile.readText()).takeIf { it.optString("access").isNotEmpty() } else null } catch (e: Exception) { null }

    private fun save(o: JSONObject) {
        val old = try { if (tokenFile.isFile) JSONObject(tokenFile.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        for (k in listOf("tls", "static", "mac", "macs")) if (!o.has(k) && old.has(k)) o.put(k, old.get(k))
        dir.mkdirs(); tokenFile.writeText(o.toString())
    }

    private fun remembered(k: String): Any? = try { if (tokenFile.isFile) JSONObject(tokenFile.readText()).opt(k) else null } catch (e: Exception) { null }

    private fun remember(k: String, v: Any) {
        val o = try { if (tokenFile.isFile) JSONObject(tokenFile.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
        if (o.opt(k) == v) return
        dir.mkdirs(); tokenFile.writeText(o.put(k, v).toString())
    }

    companion object {
        /** The TV wants Hisense's client certificate and we have none yet. */
        const val NO_CERT = "vidaa: the certificate from Hisense's app is needed"
        /** The official app whose package holds the certificate (the phone reads it from there). */
        val APPS = listOf("com.universal.remote.multi", "com.vidaa.smarttv", "com.hisense.tv.remotenow")

        const val STATIC_USER = "hisenseservice"
        const val STATIC_PASS = "multimqttservice"
        private const val BROADCAST = "/remoteapp/mobile/broadcast/"
        /** Every answer [message] and the waits read, under our own topic: subscribed by name too (see [login]). */
        internal val MINE = listOf("authentication", "authenticationcode", "authenticationcodeclose", "vidaa_app_connect",
            "gettvstate", "state", "sourcelist", "applist").map { "ui_service/data/$it" } +
            listOf("tokenissuance", "gettoken", "getvolume", "getdeviceinfo", "gettvinfo").map { "platform_service/data/$it" }
        private const val PATTERN = "38D65DC30F45109A369A86FCE866A85B"
        private const val MODERN_SALT = "h!i@s#\$v%i^d&a*a"
        private const val STANDARD_SALT = "h*i&s%e!r^v0i1c9"
        private const val TIME_XOR = 6239759785777146216L
        /** Passwords the certificate files in Hisense's apps have been locked with. */
        private val P12_PASSWORDS = listOf("186e990688070325a1c4b0ce275d2388", "", "remote", "hisense", "123456")
        /** Where firmwares put the TV's own name (upstream hisense_vidaa reads the same four). */
        private val NAME_FIELDS = listOf("devicename", "device_name", "friendly_name", "tv_name")
        private val IFACES = setOf("eth0", "wlan0")
        private val RAW_MAC = Regex("(?i)^[0-9a-f]{12}$")
        /** AA:BB:CC:DD:EE:FF (upper case, as already stored) from "e43bc957f14f", "E4-3B-..." or "e4:3b:..."; null for anything else and for all-zero / all-ff. */
        internal fun normMac(v: String): String? {
            val t = v.trim()
            val hex = when { RAW_MAC.matches(t) -> t; MAC.matches(t) -> t.replace(Regex("[:-]"), ""); else -> return null }.uppercase()
            if (hex == "000000000000" || hex == "FFFFFFFFFFFF") return null
            return hex.chunked(2).joinToString(":")
        }
        /** Letters and digits only, lowercase: "Spotify : Music & Podcasts" and "Stan." compare as the words a person types. */
        internal fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        /** By id, then by exact name, then by normalised name, then by a name that starts with it. */
        internal fun pickApp(list: List<JSONObject>, link: String): JSONObject? {
            list.firstOrNull { it.optString("appId") == link || it.optString("name").equals(link, true) }?.let { return it }
            val n = norm(link).ifEmpty { return null }
            return list.firstOrNull { norm(it.optString("name")) == n } ?: list.firstOrNull { norm(it.optString("name")).startsWith(n) }
        }
        private val MAC = Regex("(?i)^[0-9a-f]{2}([:-][0-9a-f]{2}){5}$")

        /** Android key codes to VIDAA's key names; what is missing is greyed. */
        val KEYS: Map<Int, String> = mapOf(19 to "KEY_UP", 20 to "KEY_DOWN", 21 to "KEY_LEFT", 22 to "KEY_RIGHT", 23 to "KEY_OK", 66 to "KEY_OK",
            4 to "KEY_RETURNS", 3 to "KEY_HOME", 82 to "KEY_MENU", 176 to "KEY_MENU", 111 to "KEY_EXIT", 26 to "KEY_POWER",
            24 to "KEY_VOLUMEUP", 25 to "KEY_VOLUMEDOWN", 164 to "KEY_MUTE", 85 to "KEY_PLAY", 126 to "KEY_PLAY", 127 to "KEY_PAUSE",
            86 to "KEY_STOP", 90 to "KEY_FORWARDS", 89 to "KEY_BACK", 166 to "KEY_CHANNELUP", 167 to "KEY_CHANNELDOWN",
            183 to "KEY_RED", 184 to "KEY_GREEN", 185 to "KEY_YELLOW", 186 to "KEY_BLUE", 175 to "KEY_SUBTITLE", 165 to "KEY_INFO",
            170 to "KEY_LIVETV", 172 to "KEY_EPG", 284 to "KEY_APPS", 190 to "KEY_YOUTUBE", 191 to "KEY_NETFLIX", 195 to "KEY_PRIME") + (0..9).associate { (7 + it) to "KEY_$it" }

        private fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }

        internal fun clientId(mac: String) = "$mac\$his\$" + md5("$PATTERN\$$mac").take(6) + "_vidaacommon_001"

        /** The first login's user and password for a TV generation, from the TV's clock [ts] (seconds). */
        internal fun credentials(mac: String, ts: Long, profile: String): Pair<String, String> {
            val salt = if (profile == "modern") MODERN_SALT else STANDARD_SALT
            val user = if (profile == "remotenow") "his\$$ts" else "his\$" + (ts xor TIME_XOR)
            val digit = ts.toString().sumOf { it - '0' } % 10
            return user to md5("$ts\$" + md5("his$digit$salt").take(6))
        }

        /** Ours for good: a random MAC (locally administered), the name this device pairs under. */
        private fun id(dir: File): String {
            val f = File(dir, "vidaa-id")
            f.takeIf { it.isFile }?.readText()?.trim()?.takeIf { MAC.matches(it) }?.let { return it }
            val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
            b[0] = ((b[0].toInt() and 0xfc) or 0x02).toByte()
            val m = b.joinToString(":") { "%02X".format(it) }
            dir.mkdirs(); f.writeText(m)
            return m
        }

        private fun json(s: String): JSONObject? = try { JSONObject(s) } catch (e: Exception) { null }

        private fun list(s: String, field: String): List<JSONObject> {
            val arr = try { if (s.trim().startsWith("[")) JSONArray(s) else JSONObject(s).optJSONArray(field) } catch (e: Exception) { null } ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }

        private val TRUST_ALL = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        // ---------------------------------------------------------------- Hisense's certificate

        /** One of Hisense's client certificates, public on GitHub: the SHA-256 of its DER form and where to find it (cert, key). */
        private class Public(val name: String, val sha256: String, val at: List<Pair<String, String>>)

        // The two certificates Hisense's apps use, each from two projects that publish them; the copy in HoangTran0410/vidaa-certs
        // only once those are gone. Pinned: a changed file is not taken.
        private val PUBLIC = listOf(
            Public("vidaa_client", "31f914630f8636d6c4313e02e9eb3724b4706c6b5049c838b04213ab56905aa3", listOf(   // CN=VidaaAppAndroidV01
                "adamazad/vidaa-remote/HEAD/certs/vidaa_client.pem" to "adamazad/vidaa-remote/HEAD/certs/vidaa_client.key",
                "tombabolewski/vidaa-control/HEAD/vidaa/certs/vidaa_client.pem" to "tombabolewski/vidaa-control/HEAD/vidaa/certs/vidaa_client.key",
                "HoangTran0410/vidaa-certs/main/vidaa_client.pem" to "HoangTran0410/vidaa-certs/main/vidaa_client.key")),
            Public("remoteclientmobile", "857e4d1ad94f892404371ff1cb282c1b890f73ced3fd47051e6e20d590557c59", listOf(   // RemoteNOW's
                "Alexa-RR/ha-vidaa/HEAD/custom_components/vidaa/certs/vidaa_client.crt" to "Alexa-RR/ha-vidaa/HEAD/custom_components/vidaa/certs/vidaa_client.key",
                "amirburbea/neeo/HEAD/Neeo.Drivers.Hisense/Certificates/rcm_certchain_pem.cer" to "amirburbea/neeo/HEAD/Neeo.Drivers.Hisense/Certificates/rcm_pem_privkey.pkcs8",
                "HoangTran0410/vidaa-certs/main/remoteclientmobile.pem" to "HoangTran0410/vidaa-certs/main/remoteclientmobile.key")))

        /** The source of the files, "https://raw.githubusercontent.com/" in use; tests point it elsewhere. */
        @Volatile internal var RAW = "https://raw.githubusercontent.com/"

        /** Downloads the certificates not kept yet; true when one was added. */
        @Synchronized internal fun fetchCerts(dir: File): Boolean {
            var added = false
            for (c in PUBLIC) {
                if (pem(dir, c.name) != null) continue
                for ((crt, key) in c.at) {
                    val a = download(RAW + crt) ?: continue
                    val b = download(RAW + key) ?: continue
                    if (keyManagers(a, b, c.sha256) == null) { WireLog.all.add("·", "VIDAA: $crt is not the certificate expected"); continue }
                    dir.mkdirs()
                    File(dir, "vidaa-${c.name}.crt").writeBytes(a); File(dir, "vidaa-${c.name}.key").writeBytes(b)
                    WireLog.all.add("·", "VIDAA: certificate ${c.name} downloaded")
                    added = true
                    break
                }
            }
            return added
        }

        private fun download(url: String): ByteArray? = try {
            val c = dev.tvremote.core.Lan.open(URL(url)) as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() }.takeIf { it.size < 64 * 1024 } else null } finally { c.disconnect() }
        } catch (e: Exception) { null }

        private fun pem(dir: File, name: String): Array<javax.net.ssl.KeyManager>? {
            val crt = File(dir, "vidaa-$name.crt"); val key = File(dir, "vidaa-$name.key")
            if (!crt.isFile || !key.isFile) return null
            return keyManagers(crt.readBytes(), key.readBytes(), PUBLIC.first { it.name == name }.sha256)
        }

        /** A key store from a PEM certificate and its PKCS#8 key, when the certificate is the one pinned and the key is its own. */
        private fun keyManagers(crt: ByteArray, key: ByteArray, sha256: String): Array<javax.net.ssl.KeyManager>? = try {
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(crt)) as X509Certificate
            if (MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) } != sha256) null
            else {
                val body = String(key, Charsets.US_ASCII).substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END").filter { !it.isWhitespace() }
                val k = KeyFactory.getInstance(cert.publicKey.algorithm).generatePrivate(PKCS8EncodedKeySpec(B64.decode(body)))
                if (k is RSAKey && (cert.publicKey as? RSAKey)?.modulus != k.modulus) null
                else {
                    val pw = "vidaa".toCharArray()
                    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setKeyEntry("vidaa", k, pw, arrayOf(cert)) }
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pw) }.keyManagers
                }
            }
        } catch (e: Exception) { null }

        private fun certFile(dir: File) = File(dir, "vidaa.p12")
        fun hasCert(dir: File) = certFile(dir).isFile

        private fun cert(dir: File): Array<javax.net.ssl.KeyManager>? {
            val f = certFile(dir).takeIf { it.isFile } ?: return null
            val pass = File(dir, "vidaa.pass").takeIf { it.isFile }?.readText() ?: ""
            return try {
                val ks = KeyStore.getInstance("PKCS12").apply { load(f.inputStream(), pass.toCharArray()) }
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass.toCharArray()) }.keyManagers
            } catch (e: Exception) { null }
        }

        /** The certificate's password when [p12] opens and holds a key, else null. */
        private fun open(p12: ByteArray): String? = P12_PASSWORDS.firstOrNull { pw ->
            try {
                val ks = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(p12), pw.toCharArray()) }
                ks.aliases().toList().any { ks.isKeyEntry(it) && ks.getKey(it, pw.toCharArray()) != null }
            } catch (e: Exception) { false }
        }

        /**
         * Keeps Hisense's client certificate from [bytes]: a .p12 itself, or the official app's .apk (its res/El.p12 and the
         * like). Only on this device, never sent anywhere. False when there is none we can open.
         */
        fun importCert(dir: File, bytes: ByteArray): Boolean = importCert(dir, ByteArrayInputStream(bytes), bytes.size.toLong())

        /** The same from a stream of [length] bytes (an APK is 100 MB and more: only its .p12 files are held in memory). */
        fun importCert(dir: File, input: InputStream, length: Long): Boolean {
            val c = Capped(input, length)
            val b = BufferedInputStream(c)
            b.mark(4)
            val zip = b.read() == 'P'.code && b.read() == 'K'.code
            b.reset()
            val found = if (zip) {
                val p12s = ArrayList<Pair<String, ByteArray>>()
                ZipInputStream(b).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val n = e.name.lowercase()
                        if (!e.isDirectory && (n.endsWith(".p12") || n.endsWith(".pfx")) && e.size < 1 shl 20) atMost(z, 1 shl 20)?.let { p12s.add(e.name to it) }
                    }
                }
                p12s.sortedBy { (n, _) -> if (n.endsWith("El.p12") || "vidaa" in n.lowercase()) 0 else 1 }.firstNotNullOfOrNull { (_, b) -> open(b)?.let { b to it } }
            } else atMost(b, 1 shl 20)?.let { bytes -> open(bytes)?.let { bytes to it } }
            val rest = ByteArray(65536); while (c.read(rest) >= 0) { }         // to the end of the upload: the connection carries the next request
            val (p12, pass) = found ?: return false
            dir.mkdirs()
            certFile(dir).writeBytes(p12)
            File(dir, "vidaa.pass").writeText(pass)
            WireLog.all.add("·", "VIDAA: Hisense's certificate kept")
            return true
        }

        /** All of [input] if it is no longer than [max] bytes, else null. */
        private fun atMost(input: InputStream, max: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) return null }
            return out.toByteArray()
        }

        /** No further than [left] bytes: an upload's body is not closed after it. */
        private class Capped(private val input: InputStream, private var left: Long) : InputStream() {
            override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0 || len == 0) return if (len == 0) 0 else -1
                val n = input.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            override fun close() {}                                   // the stream is the caller's (an upload's socket)
        }
    }
}

/** A TV that pairs by a code it shows on its screen, typed here: Android TV (6 characters), Hisense VIDAA (4 digits). */
interface CodePairing : java.io.Closeable {
    fun startPairing()
    /** IllegalArgumentException when the code is wrong. */
    fun finishPairing(code: String)
}
