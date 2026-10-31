package dev.tvremote.core.web
import dev.tvremote.core.Words
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.adb.AdbPrompt
import dev.tvremote.core.adb.AdbTls
import dev.tvremote.core.adb.Mdns
import dev.tvremote.core.ontv.Draw
import dev.tvremote.core.remote.Inputs
import dev.tvremote.core.ontv.Notify
import dev.tvremote.core.ontv.NowPlaying
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.ontv.ScreenOff
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.ontv.SetupNeed
import dev.tvremote.core.ontv.Tailscale
import dev.tvremote.core.adb.TvShell
import dev.tvremote.core.WireLog

/**
 * Managing a TV over ADB for the page all three apps serve. The paths and the answers are the ones the computer's server gives
 * (app/desktop Server.kt), because all three serve the same page (app/shared/web). Whoever owns the HttpServer checks first that
 * the caller may use it. The work is in parts, each with its own routes ([ManagePart]): apps ([AppsApi]), files ([FilesApi]),
 * installs ([InstallApi]), health ([HealthApi]), TV Setup ([SetupApi]), the project's TV apps ([HomeApi]); what is left here is
 * the TV itself (keys, settings, the screen, ADB, the jobs).
 */
class Manage(private val link: AdbLink, private val labels: Labels?, private val dir: java.io.File?, private val helper: (() -> ByteArray)? = null) {
    /** Names and icons for packages, where the app can ask the system for them (the TV app); otherwise package names are shown. */
    interface Labels {
        fun label(pkg: String): String?
        fun icons(): Boolean
        /** What the TV's hardware can do, as [Caps] prints it; null where it cannot be asked. */
        fun caps(): String? = null
        /** The names given on TV Home (its settings were just read or changed): they win over the apps' own. */
        fun own(m: Map<String, String>) {}
    }

    /** Told when a device was let in through the TV's question (TV Setup shows it on the TV). */
    var allowed: ((String) -> Unit)? = null
    /** Pairing through Wireless debugging needs TLS 1.3 from a library the TV app does not carry: only the Phone and Computer apps turn this on. */
    var canPair = false
    private val promptLock = Any()

    private val core = ManageCore(link, labels, dir)
    private val jobs = core.jobs
    // the parts, made on first use: a Manage is also made for one install or one welcome on several TVs at once
    private val copy by lazy { TvCopy(core) }
    private val setupApi by lazy { SetupApi(core, copy) }
    private val installs by lazy { InstallApi(core, copy) }
    private val files by lazy { FilesApi(core, copy, installs, setupApi) }
    private val apps by lazy { AppsApi(core) }
    private val healthApi by lazy { HealthApi(core) }
    private val parts by lazy { listOf(apps, files, installs, healthApi, setupApi, HomeApi(core), copy) }

    private val playing = helper?.let { NowPlaying({ link.get() }, it) }      // the now playing bar: null where tvr-helper.jar is not at hand
    private val kbdReader = dev.tvremote.core.adb.TvKeyboard.Reader()   // /api/tvkbd: the proto, or the text dump on a TV without it
    private val draw = Draw { link.get() }                           // strokes for TV Setup to draw over the screen
    private val launcherApi by lazy { LauncherApi(link, dir, { t, w -> jobs.job(t, null, null, w) }, { jobs.current.get() }) }
    private val tvAppsApi by lazy { TvAppsApi(link, dir, { t, w -> jobs.job(t, null, null, w) }, { jobs.current.get() }, { i, n, c -> install(i, n, null, c) }) }

    /**
     * The TV Apps app asking TV Setup (HomeInfo "tvapps") to install one app of the list over this ADB link, no question on the
     * TV: [op] "install" (by the list's [id]) starts the page's own job, "job" reads it, "cancel" stops it.
     */
    fun tvApps(op: String, id: String): JSONObject = when (op) {
        "install" -> tvAppsApi.install(id)
        "job" -> jobs[id]?.let { Jobs.snapshot(it) } ?: JSONObject().put("id", id).put("status", "error").put("msg", "no such job")
        "cancel" -> { jobs.stops[id]?.stop(); JSONObject().put("ok", true) }
        else -> throw IllegalArgumentException("op")
    }

    /** Told when work starts or ends (the phone keeps itself running meanwhile, and asks before leaving the TV). */
    var onWork: ((String) -> Unit)?
        get() = jobs.onWork
        set(v) { jobs.onWork = v }

    /** What is under way, as upload / install / update / tailscale / work; "" when nothing. */
    fun working(): String = jobs.working()

    /** How far the newest running job is, 0..100, or -1 when none tells (the phone's notification bar). */
    fun progress(): Int = jobs.progress()

    /** Makes the TV open a link (a video, a web page) with whatever app takes it. */
    fun openLink(url: String) = apps.openLink(url)

    /** Puts a file from this device into the TV's Download folder and has the TV open it: a photo, a video, a song. */
    @Throws(IOException::class)
    fun cast(input: InputStream, length: Long, name: String, job: JSONObject? = null, cancel: Cancel? = null) = files.cast(input, length, name, job, cancel)

    /** An APK from this device, checked and installed as an upload is: the phone's "several TVs at once" (pct 0..90 while it copies). */
    @Throws(IOException::class)
    fun install(input: InputStream, length: Long, job: JSONObject?, cancel: Cancel? = null): String = installs.install(input, length, job, cancel)

    /** The welcome screen on one of several TVs (Fleet): see [SetupApi.welcomeBulk]. */
    @Throws(IOException::class)
    fun welcomeBulk(input: InputStream?, length: Long, name: String, cfg: JSONObject, job: JSONObject?, cancel: Cancel?) = setupApi.welcomeBulk(input, length, name, cfg, job, cancel)

    /** CPU load in percent over one second, or null. */
    fun cpuNow(): Int? = healthApi.cpuNow()

    /** Lets the TV Setup app on the TV read which app is on screen (it cannot ask for this itself); false when it could not be asked (no ADB yet): the caller tries again later. */
    fun allowUsage(pkg: String): Boolean = healthApi.allowUsage(pkg)

    private fun text(cmd: String, quietMs: Long): HttpServer.Response = core.text(cmd, quietMs)

    /** null when the path is not ours. */
    @Throws(Exception::class)
    fun handle(r: HttpServer.Request): HttpServer.Response? {
        if (!r.path.startsWith("/api/")) return null
        val what = r.path.substring(5)
        val post = r.method == "POST"
        try {
            when (what) {
                "census" -> return census(r)
                "crash" -> return crash(r)
                "screen/state" -> if (!post) return HttpServer.Response.json(JSONObject().put("off", ScreenOff.isOff { sh(it) }))
                "dim" -> return HttpServer.Response.json(dev.tvremote.core.ontv.Dim.call({ sh(it) }, if (post) r.json() else null))
                "speed" -> if (!post) return HttpServer.Response.json(dev.tvremote.core.ontv.SpeedTest.read { sh(it) })
                "log" -> return text(TvShell.log(r.q("pkg"), r.q("since")), 20000)
                "diagnose/setup" -> {                                 // Fire TV, first setup unfinished: Home / Alexa keys dead (Diagnose.SETUP_OFF)
                    if (post) { sh(dev.tvremote.core.Diagnose.SETUP_FIX); return HttpServer.Response.json(JSONObject().put("ok", true)) }   // only after the person pressed Fix
                    val lang = r.q("lang").takeIf { it.isNotEmpty() }?.let(Words::pick) ?: r.lang
                    val res = dev.tvremote.core.Diagnose.setup(sh(dev.tvremote.core.Diagnose.SETUP_READ), link.host, lang)
                    return HttpServer.Response.json(if (res == null) JSONObject().put("code", "ok") else dev.tvremote.core.Diagnose.json(res, lang))
                }
                "diagnose" -> return diagnose(r, link)                // why the TV does not answer: one conclusion (Diagnose.kt)
                "wirelog" -> {
                    if (post) { WireLog.all.add("page", r.json().optString("said").take(500)); return HttpServer.Response.json(JSONObject().put("ok", true)) }   // what went wrong inside the page (a decoder that gave nothing), for the same log
                    return HttpServer.Response.of("text/plain; charset=utf-8", WireLog.all.report().toByteArray())   // ours, not the TV's: "Gửi nhật ký"
                }
                "settings" -> if (!post) return text(TvShell.SETTINGS_READ, 20000)
                "inputs" -> if (!post) return HttpServer.Response.json(Inputs.json(inputs(r.q("fresh") == "1")))   // HDMI 1 · PlayStation...
                "caps" -> return HttpServer.Response.json(JSONObject().put("text", labels?.caps() ?: ""))
                "playing" -> if (!post) return HttpServer.Response.json(playing?.state() ?: JSONObject().put("state", "none"))
                "tailscale" -> { val t = Tailscale(link.get(), dir); return HttpServer.Response.json(JSONObject().put("installed", t.installed()).put("ip", t.ip())) }
                "downloader" -> return HttpServer.Response.json(dev.tvremote.core.Downloader.json())   // the Downloader codes the page tells people to type
                "tvapps" -> return HttpServer.Response.json(tvAppsApi.list(r.q("fresh") == "1"))   // TV Apps: the free apps to install
                "tvapps/icon" -> return tvAppsApi.icon(r.q("id"))
                "tvapps/install" -> if (post) return HttpServer.Response.json(tvAppsApi.install(r.json().optString("id")))
                "launchers" -> return HttpServer.Response.json(launcherApi.list())
                "launcher/thumb" -> return launcherApi.thumb(r.q("id"))
                "launcher/install" -> return HttpServer.Response.json(launcherApi.install(r.json().optString("id"), r.lang))
                "launcher/play" -> return HttpServer.Response.json(launcherApi.play(r.json().optString("id")))
                "launcher/answer" -> return HttpServer.Response.json(launcherApi.answer(r.json().optBoolean("keep")))
                "launcher/restore" -> return HttpServer.Response.json(launcherApi.restore())
                "adb/prompt" -> {                                     // the TV's "Allow USB debugging?" question, if it is up
                    val p = if (link.state() == "ready") AdbPrompt.read(link.get()) else null
                    return HttpServer.Response.json(JSONObject().put("fingerprint", p?.fingerprint ?: ""))
                }
                "tvkbd" -> {                                          // is the TV's own keyboard up (the page opens its typing box)
                    val up = if (link.state() != "ready") false else try { kbdReader.ask { link.get().shell(it) } } catch (e: Exception) {
                        return HttpServer.Response.error(503, "no answer") }   // unknown, not "down": the page keeps its typing box as it is
                    return HttpServer.Response.json(JSONObject().put("shown", up))
                }
                "adb/discover" -> if (canPair) return discoverPairing()     // where Conscrypt is packed: the phone, the computer, TV Setup (its own TV)
                "job" -> {
                    val j = jobs[r.q("id")]
                    return if (j == null) HttpServer.Response.error(404, "no such job") else HttpServer.Response.json(Jobs.snapshot(j))
                }
            }
            for (p in parts) p.route(what, post, r)?.let { return it }
            if (!post) return null
            return when (what) {
                "settings" -> { val d = r.json(); val vol = TvShell.VOLUME[d.optString("name")]
                    if (vol != null && d.optString("table") == "system") {
                        val v = d.optString("value").toIntOrNull()?.takeIf { it in 0..100 } ?: throw IllegalArgumentException("bad value")
                        Scenes.volume({ sh(it) }, v, vol); HttpServer.Response.json(JSONObject().put("text", "ok"))
                    } else text(TvShell.setting(d.optString("table"), d.optString("name"), d.optString("value")), 20000) }
                "reboot" -> {
                    sh(TvShell.REBOOT_PREP)
                    try { link.get().open("reboot:").use { it.readAll(3000) } } catch (ignored: Exception) { }   // the link drops as the TV goes down
                    link.close()                                      // so the page's next state() dials again instead of trusting it
                    HttpServer.Response.ok()
                }
                "input" -> {
                    val id = r.json().optString("id")
                    val via = Inputs.switch({ sh(it) }, id, inputCache.peek())
                    HttpServer.Response.json(JSONObject().put("ok", true).put("via", via))
                }
                "adb/pair" -> { if (canPair && post) return pairing(r.json()); HttpServer.Response.error(404, "not here") }
                "adb/connect" -> HttpServer.Response.json(JSONObject().put("state", link.reconnect()))
                "shell" -> shell(r.json().optString("cmd"))
                "notify" -> { Notify.send({ sh(it) }, r.json()); HttpServer.Response.json(JSONObject().put("ok", true)) }
                "share" -> HttpServer.Response.json(Invite.share({ sh(it) }, link.host, r.json().optString("scope"), r.json().optBoolean("again")))   // a QR another phone scans (Invite.kt)
                "search" -> {
                    val to = sh(TvShell.search(r.json().optString("query"))).trim()
                    if (to != "yt" && to != "gs") throw IllegalArgumentException("this TV has no YouTube and no Google search")
                    HttpServer.Response.json(JSONObject().put("to", if (to == "yt") "youtube" else "assistant"))
                }
                "cancel" -> { jobs.stops[r.json().optString("id")]?.stop(); HttpServer.Response.json(JSONObject().put("ok", true)) }
                "playing" -> { val d = r.json(); (playing ?: throw IllegalStateException("not here")).command(d.optString("do"), d.optLong("ms")); HttpServer.Response.json(JSONObject().put("ok", true)) }
                "screen/off" -> { ScreenOff.off(link.get(), (helper ?: throw IllegalStateException("not here"))()); HttpServer.Response.json(JSONObject().put("ok", true)) }
                "speed" -> {                                          // {stop: true} ends one; anything else starts one
                    if (r.json().optBoolean("stop")) dev.tvremote.core.ontv.SpeedTest.stop { sh(it) }
                    else dev.tvremote.core.ontv.SpeedTest.start(link.get(), (helper ?: throw IllegalStateException("not here"))())
                    HttpServer.Response.json(JSONObject().put("ok", true))
                }
                "screen/on" -> { ScreenOff.on(link.get(), (helper ?: throw IllegalStateException("not here"))()); HttpServer.Response.json(JSONObject().put("ok", true)) }
                "draw" -> { draw.send(r.json(Draw.MAX_BODY).getJSONArray("ops")); HttpServer.Response.json(JSONObject().put("ok", true).put("pic", draw.pics)) }
                "adb/allow" -> {                                      // only for the key named: see AdbPrompt
                    val d = r.json()
                    val fp = d.optString("fingerprint").uppercase()
                    if (!fp.matches(Regex("(?:[0-9A-F]{2}:){15}[0-9A-F]{2}"))) throw IllegalArgumentException("bad fingerprint")
                    val result = synchronized(promptLock) { AdbPrompt.allow(link.get(), fp, d.optLong("wait", 0).coerceIn(0, 40) * 1000, d.optBoolean("others")) }
                    if (result == "allowed") allowed?.invoke(fp)
                    HttpServer.Response.json(JSONObject().put("result", result))
                }
                "adb/deny" -> HttpServer.Response.json(JSONObject().put("ok", synchronized(promptLock) { AdbPrompt.deny(link.get(), r.json().optString("fingerprint")) }))
                "tailscale/start" -> { val lang = r.json().optString("lang").takeIf { it.isNotEmpty() }?.let(Words::pick) ?: r.lang; HttpServer.Response.json(jobs.job("tailscale", null, null) { j ->
                    val t = Tailscale(link.get(), dir)
                    val url = t.start({ on -> Notify.busy(::sh, lang, if (on) Words.get(lang, "server.turning_tailscale_on") else null) }) { what, pct ->
                        j.put("msg", what).put("pct", pct)
                    }
                    if (url.isEmpty()) { j.put("ip", t.ip()); "in" } else { j.put("url", url); "sign in" }
                }) }
                else -> null
            }
        } catch (e: Cancelled) {                                      // before IOException: stopping is not a broken link
            return HttpServer.Response.error(410, "stopped")
        } catch (e: IllegalArgumentException) {
            return HttpServer.Response.error(400, e.message.toString())
        } catch (e: SetupNeed.Missing) {                              // the page asks to update or install TV Setup (needSetup in js/core.js)
            return HttpServer.Response.error(409, e.message.toString())
        } catch (e: IOException) {
            return HttpServer.Response.error(503, if (link.waitingForUser()) "adb:allow" else "adb: " + e.message)
        }
    }

    private val inputCache = Inputs.Cache()

    /** The TV's inputs, kept a minute (reading two dumpsys takes a second or two on a slow TV). */
    private fun inputs(fresh: Boolean): List<Inputs.Input> = inputCache.get(fresh) { Inputs.parse(sh(Inputs.READ)) }
    /** The pairing and connect ports heard on the network (mDNS), for the page to prefill; empty lists when nothing answers. */
    private fun discoverPairing(): HttpServer.Response {
        fun list(l: List<Mdns.Service>) = org.json.JSONArray().also { a -> l.forEach { a.put(JSONObject().put("ip", it.ip).put("port", it.port).put("self", AdbTls.same(link.host, it.ip))) } }
        val (pairing, connect) = AdbTls.discover()
        return HttpServer.Response.json(JSONObject().put("host", link.host).put("pairing", list(pairing)).put("connect", list(connect)))
    }

    /** Wireless debugging: pairs with the 6-digit code, then connects through the TLS port (found by mDNS unless the page sent it). */
    private fun pairing(d: JSONObject): HttpServer.Response {
        val host = d.optString("host").trim().ifEmpty { link.host }
        var cp = d.optInt("connectPort", 0)
        if (d.optString("code").isNotBlank() || cp <= 0) {            // no code, only a connect port: pairing was done, the port was missing
            val port = d.optInt("port", 0)
            val code = AdbTls.cleanCode(d.optString("code")) ?: throw IllegalArgumentException("the pairing code is 6 digits")
            if (port !in 1..65535) throw IllegalArgumentException("the pairing port is a number")
            AdbTls.pair(Adb(link.keyDir), host, port, code)
        }
        var tries = 0
        while (cp <= 0 && tries++ < 3) cp = AdbTls.discoverConnect(host)
        val out = JSONObject().put("ok", true).put("paired", true)
        if (cp <= 0) return HttpServer.Response.json(out.put("needPort", true))   // paired, but the connect port is still to be typed
        AdbTls.save(link.keyDir, host, cp)
        if (link.host != host && AdbTls.same(link.host, host)) AdbTls.save(link.keyDir, link.host, cp)   // TV Setup paired with its own TV: it dials 127.0.0.1
        return HttpServer.Response.json(out.put("port", cp).put("state", link.reconnect()))
    }

    /** ready, unauthorized (the TV is asking) or closed: the words the page knows. */
    fun adbState(): String = link.state()

    @Throws(IOException::class)
    private fun sh(cmd: String): String = link.get().shell(cmd)

    // ---------------------------------------------------------------- terminal
    /** Runs one command as the TV's shell user and returns what it printed (at most 30 s and 200 KB). */
    private fun shell(cmd: String): HttpServer.Response {
        if (cmd.trim().isEmpty()) throw IllegalArgumentException("empty command")
        val s = link.get().open("shell:$cmd 2>&1")
        val out = ByteArrayOutputStream()
        val b = ByteArray(16384)
        val until = System.nanoTime() / 1_000_000 + 30000
        var cut = false
        try {
            while (true) {
                val left = until - System.nanoTime() / 1_000_000
                if (left <= 0 || out.size() > 200000) { cut = true; break }
                val n = s.read(b, 0, b.size, left)
                if (n < 0) break
                out.write(b, 0, n)
            }
        } finally {
            s.close()
        }
        return HttpServer.Response.json(JSONObject().put("out", out.toString("UTF-8").replace("\r\n", "\n")).put("cut", cut))
    }

    companion object {
        /**
         * `/api/crash`: the page's error box reports an error ({kind, msg, stack}) through its own core, which scrubs it again, adds the
         * device's facts and sends it with the stats switch (Census.crashNow): the answer says whether the server has it.
         */
        fun crash(r: HttpServer.Request): HttpServer.Response {
            if (r.method != "POST") return HttpServer.Response.error(405, "post")
            val b = r.json()
            val sent = dev.tvremote.core.Census.crashNow("page", b.optString("kind", "Error").take(120), b.optString("msg").take(600), b.optString("stack").take(16384))
            return HttpServer.Response.json(JSONObject().put("sent", sent))
        }

        /** `/api/census`: the Settings switch of the anonymous stats (Census.kt), the same on every core; POST {on: bool} turns it on or off. */
        fun census(r: HttpServer.Request): HttpServer.Response {
            if (r.method == "POST") dev.tvremote.core.Census.enabled = r.json().optBoolean("on", true)
            return HttpServer.Response.json(JSONObject().put("on", dev.tvremote.core.Census.enabled))
        }

        /**
         * `/api/diagnose?ip=&kind=&name=&err=&lang=`: the quick self-check after a failed connect, answered as one conclusion with
         * what to do. It needs no ADB and no session, so the computer app answers it itself too (Server.kt). [link] is this app's
         * own ADB link when it has one, for "the TV is waiting for Allow".
         */
        fun diagnose(r: HttpServer.Request, link: AdbLink?): HttpServer.Response {
            val ip = r.q("ip").trim().ifEmpty { link?.host ?: "" }
            if (ip.isEmpty() || ip.length > 80 || !Regex("[0-9A-Za-z.:\\[\\]-]+").matches(ip)) return HttpServer.Response.error(400, "ip")
            val lang = r.q("lang").takeIf { it.isNotEmpty() }?.let(Words::pick) ?: r.lang
            val res = dev.tvremote.core.Diagnose.run(dev.tvremote.core.Diagnose.Real(link), ip, dev.tvremote.core.remote.Remote.Kind.of(r.q("kind")), r.q("name").take(80), r.q("err").take(2000), lang)
            return HttpServer.Response.json(dev.tvremote.core.Diagnose.json(res, lang))
        }
    }
}
