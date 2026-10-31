package dev.tvremote.core.web
import dev.tvremote.core.B64
import dev.tvremote.core.Words
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.TvShell
import dev.tvremote.core.ontv.LauncherSwap
import dev.tvremote.core.ontv.RulesClient
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.ontv.SetupNeed
import dev.tvremote.core.ontv.SetupUpdate
import dev.tvremote.core.ontv.Welcome
import dev.tvremote.update.Release
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import org.json.JSONObject

/** TV Setup's side of the page: Optimize TV and Undo, its updates, the rules it keeps (timers, limits), Telegram, support, the welcome screen, the TV's settings and Home key. */
internal class SetupApi(core: ManageCore, private val copy: TvCopy) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? {
        when (what) {
            "rules" -> if (!post) return HttpServer.Response.json(rules(null))
            "telegram" -> return HttpServer.Response.json(telegram(r))
            "support/tv" -> if (!post) return HttpServer.Response.json(supportTv("state"))
            "welcome" -> if (!post) return HttpServer.Response.json(Welcome.call({ sh(it) }, null))
            "welcome/thumb" -> {                                  // a small picture of the welcome, for the card's preview
                val b64 = Welcome.call({ sh(it) }, JSONObject().put("do", "thumb")).optString("thumb")
                return if (b64.isEmpty()) HttpServer.Response.error(404, "no picture") else HttpServer.Response.of("image/jpeg", B64.decode(b64))
            }
            "tvset" -> if (!post) return tvsetRead()
            "setup/version" -> return HttpServer.Response.json(setupVersion())
            "setup/need" -> { SetupNeed.check(link.get(), r.q("f")); return HttpServer.Response.json(JSONObject().put("ok", true)) }
            "setup/restore" -> if (!post) return HttpServer.Response.json(JSONObject().put("has", Setup(link.get(), dir, r.lang) { }.optimized()))   // whether the card offers the script
            "setup/restore.sh" -> if (!post) {                    // a plain shell script that does Undo with only adb: a download, like the scenes backup
                val s = Setup(link.get(), dir, r.lang) { }
                if (!s.optimized()) throw IOException("setup:nothing_to_restore")
                val model = sh("getprop ro.product.model").trim()
                val script = s.restore(model, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()), link.host)
                val out = HttpServer.Response.of("text/x-shellscript; charset=utf-8", script.toByteArray())
                val name = Setup.restoreName(model)
                out.headers["Content-Disposition"] = "attachment; filename=\"$name\"; filename*=UTF-8''$name"
                return out
            }
            "setup/plan" -> return HttpServer.Response.json(Setup(link.get(), dir, r.lang) { }.plan { labels?.label(it) })
        }
        if (!post) return null
        return when (what) {
            "keytest/open" -> {                                   // the key tester on the TV (TV Setup's KeyTestActivity)
                SetupNeed.check(link.get(), "keytest")
                if (sh("am start -n dev.tvremote.setup/.KeyTestActivity 2>&1").contains("Error")) throw IllegalArgumentException("the TV did not open the key tester")
                HttpServer.Response.ok()
            }
            "tvset" -> { val d = r.json(); val k = d.optString("key"); val v = d.optString("value")
                if (k == "home") homeSet(v, r.lang) else text(TvShell.tvset(k, v), 60000) }
            "rules" -> HttpServer.Response.json(rules(r.json()))
            "telegram" -> HttpServer.Response.json(telegram(r))
            "support/tv" -> HttpServer.Response.json(supportTv(r.json().optString("act")))
            "welcome" -> HttpServer.Response.json(Welcome.call({ sh(it) }, r.json()))
            "setup" -> { val d = r.json(); HttpServer.Response.json(setup(d.optString("mode") == "undo", d.optString("lang").takeIf { it.isNotEmpty() }?.let(Words::pick) ?: r.lang, choice(d))) }
            "setup/update" -> HttpServer.Response.json(setupUpdate())
            else -> null
        }
    }

    /** The TV's settings for the page; a stock launcher a swap turned off is listed still: picking it is the way back ([homeSet]). */
    private fun tvsetRead(): HttpServer.Response {
        var out = link.get().shell(TvShell.TVSET_READ, 60000).replace("\r\n", "\n")
        val rec = try { LauncherSwap({ sh(it) }).state() } catch (e: IOException) { null }
        if (rec?.active == true) for (p in rec.off) if (p in Setup.LAUNCHER && !out.contains("$p/")) out += "\nlauncher $p/"
        // the key way: Android still names its own launcher, Home opens this one (dev/flows/home-screen.md)
        if (rec?.why == LauncherSwap.KEY && rec.state == "kept" && out.lines().any { it.trim() == "launcher ${rec.comp}" }) out = out.replace(Regex("(?m)^home .*$"), "home ${rec.comp}")
        return HttpServer.Response.json(JSONObject().put("text", out))
    }

    /**
     * The Home key's screen. While the TV's own launcher is on, Home opens it whatever the HOME role names: Google TV's launcherx (a
     * Coocaa, the Google TV emulator: role TV Home, Home -> launcherx), and a BRAVIA's tvlauncher on Android 9, where set-home-activity
     * says Success and changes nothing. So another launcher takes the whole swap (the TV's own launchers and the wizard's Home off, Home
     * checked, the record and TV Setup's watch); the TV's own launcher picked again undoes it. With none of them on the role is enough.
     */
    private fun homeSet(value: String, lang: String): HttpServer.Response {
        val pkg = value.substringBefore('/')
        val swap = LauncherSwap({ sh(it) })
        val swapped = swap.state()?.active == true
        if (pkg in Setup.LAUNCHER) swap.dropKey()                     // a Chromecast's Home back to Google TV (HomeKey), if it was sent elsewhere
        if (value != "$pkg/" || pkg !in Setup.LAUNCHER) TvShell.tvset("home", value)   // checks the name before it reaches a shell; "pkg/": a stock one turned off (tvsetRead)
        if (pkg in Setup.LAUNCHER && swapped) {
            swap.rollback("user")
            if (swap.homeNow().substringBefore('/') != pkg)            // rollback chose the one before, not the one picked
                sh("c=\$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -m1 '$pkg/'); " +
                    "[ -n \"\$c\" ] && cmd package set-home-activity \$c >/dev/null 2>&1")
        } else if (pkg !in Setup.LAUNCHER && (swapped || stockOn())) {
            if (!swap.take(pkg)) {
                val why = swap.refused.ifEmpty { swap.state()?.why ?: "" }   // the Wi-Fi or casting the wizard holds: what to do about it, not only "refused"
                val say = if (why in LauncherSwap.REFUSED) Words.get(lang, LauncherSwap.whyId(why)) else Words.get(lang, "tvset.home_refused")
                return HttpServer.Response.json(JSONObject().put("text", "home $say"))
            }
        } else return text(TvShell.tvset("home", value), 60000)
        val now = swap.homeOpens()
        return HttpServer.Response.json(JSONObject().put("text", "home " + if (now.substringBefore('/') == pkg) value else now))
    }

    /** Whether one of the TV's own launchers ([Setup.LAUNCHER]) is on: then it, not the HOME role, decides where Home lands. */
    private fun stockOn(): Boolean {
        val on = sh("pm list packages -e").split("\n").map { it.removePrefix("package:").trim() }
        return Setup.LAUNCHER.any { it in on }
    }

    /**
     * The welcome screen's video or picture (Welcome.kt) from the browser into TV Setup's own folder, which TV Setup then adopts
     * (checks, makes the preview, switches the welcome on). Refused before a byte goes when it is too big or TV Setup cannot take it.
     */
    fun welcomeUpload(r: HttpServer.Request, name: String) {
        if (r.length <= 0) throw IllegalArgumentException("empty upload")
        welcomeStore(r.body, r.length, name, null)
    }

    private fun welcomeStore(input: InputStream, length: Long, name: String, job: JSONObject?) {
        val ext = Welcome.ext(name)
        if (Welcome.kind(name) == null) throw IllegalArgumentException("welcome:kind")
        if (length > Welcome.MAX_BYTES) throw IllegalArgumentException("welcome:toobig")
        SetupNeed.check(link.get(), "welcome")
        sh("mkdir -p " + Adb.q(Welcome.DIR))
        val dest = Welcome.DIR + "/incoming." + ext
        copy.push(input, length, dest, job)
        try {
            Welcome.call({ sh(it) }, JSONObject().put("do", "adopt").put("ext", ext).put("original", name))
        } catch (e: Exception) {
            try { sh("rm -f " + Adb.q(dest)) } catch (ignored: Exception) { }
            throw e
        }
    }

    /**
     * The welcome screen on one of several TVs (Fleet): [input] is the file from this device (null: only the choices), [cfg] the
     * choices ({"on": true, "when": "boot"}). A TV Setup without the welcome screen throws [SetupNeed.Missing], which the caller
     * shows on that TV's line; the other TVs go on.
     */
    @Throws(IOException::class)
    fun welcomeBulk(input: InputStream?, length: Long, name: String, cfg: JSONObject, job: JSONObject?, cancel: Cancel?) {
        current.set(cancel)
        try {
            if (input != null) welcomeStore(input, length, name, job) else SetupNeed.check(link.get(), "welcome")
            if (cfg.length() > 0) Welcome.call({ sh(it) }, JSONObject(cfg.toString()).put("do", "config"))
        } finally { current.remove() }
    }

    /** The person's choices from the Optimize dialog; a page that sends none gets everything, as before. */
    private fun choice(d: JSONObject): Setup.Choice {
        val list = d.optJSONArray("disable") ?: return Setup.Choice(d.optBoolean("anim", true), d.optBoolean("home", true), null)
        return Setup.Choice(d.optBoolean("anim", true), d.optBoolean("home", true), (0 until list.length()).map { list.optString(it) })
    }

    /** "Optimize TV" or its undo; the job's message grows a line per step. */
    private fun setup(undo: Boolean, lang: String, c: Setup.Choice): JSONObject =
        job(if (undo) "undo" else "optimize") { j ->
            val lines = StringBuilder()
            // the link, not one connection: Optimize waits out a TV that restarts midway
            val s = Setup(link, dir, lang) { line -> lines.append(line).append('\n'); try { j.put("log", lines.toString().trim()) } catch (ignored: Exception) { } }
            if (undo) s.undo() else { s.apply(c); dev.tvremote.core.Census.optimize(link.host) }
            "ok"
        }
    // ---------------------------------------------------------------- updates of TV Setup on the TV (SetupUpdate)
    @Volatile private var latest: Release? = null
    @Volatile private var latestAt = 0L
    @Volatile private var latestFor = ""

    /** The newest TV Setup for [installed], asked of GitHub at most every half hour (it allows 60 questions an hour). */
    private fun latestSetup(installed: String): Release? {
        if (installed != latestFor || System.currentTimeMillis() - latestAt > 30 * 60_000L) {
            latest = Release.newer(installed, "tv", "tv-setup.apk")
            latestAt = System.currentTimeMillis(); latestFor = installed
        }
        return latest
    }

    /** {installed, latest (when newer and not known to break here), notes, state...: how the last update went}. */
    private fun setupVersion(): JSONObject {
        val adb = link.get()
        val installed = SetupUpdate.installed(adb)
        val o = SetupUpdate.state(adb).put("installed", installed).put("latest", "")
        if (dev.tvremote.core.Census.enabled && (o.optString("detail") == "noadb" || o.optString("state") in setOf("rolledback", "broken"))) try {
            val rep = SetupUpdate.report(adb)                         // a TV nobody here can reach: its model and logs are the only way to see why
            if (rep.isNotBlank()) dev.tvremote.core.Census.crash("kotlin", "TV Setup update " + o.optString("state") + " " + Regex("ro.product.model=(.*)").find(rep)?.groupValues?.get(1)?.trim().orEmpty(),
                "TV Setup " + o.optString("from") + " -> " + o.optString("version") + ": " + o.optString("state") + " " + o.optString("detail"), rep)
        } catch (e: Exception) { }
        if (installed.isEmpty()) return o
        // TV Setup installs its own and TV Home's updates by itself (AutoUpdate): the page then keeps quiet (UpdatePlan.GRACE).
        // An older TV Setup answers "Result: null": not silent, offered as before
        o.put("silent", try { adb.shell("content call --uri content://${RulesClient.SETUP}.home --method updates 2>/dev/null").contains("\"silent\":true") } catch (e: IOException) { false })
        val r = try { latestSetup(installed) } catch (e: Exception) { o.put("error", e.message ?: e.javaClass.simpleName); null }
        if (r != null && !SetupUpdate.bad(o, r.version)) o.put("latest", r.version).put("notes", r.notes)
        return o
    }

    /**
     * Brings the newest TV Setup to the TV and installs it with SetupUpdate's script, then follows it: done when the new version
     * runs, an error when it was put back (the job's "msg" says which). Where TV Setup itself serves this, the job goes with it.
     */
    private fun setupUpdate(): JSONObject {
        val adb = link.get()
        val installed = SetupUpdate.installed(adb)
        if (installed.isEmpty()) throw IllegalArgumentException("TV Setup is not installed")
        latestAt = 0                                                  // ask GitHub again: a release may have come out meanwhile
        val r = latestSetup(installed) ?: throw IllegalArgumentException("TV Setup is up to date")
        if (SetupUpdate.busy(SetupUpdate.state(adb)) || !SetupUpdate.lock(adb)) throw IllegalArgumentException("an update of TV Setup is already running")
        return job("update TV Setup " + r.version) { j ->
            try {                                                     // the APK in place under the lock: nobody else's install reads it half written
                j.put("msg", "download").put("version", r.version)
                val c = URL(r.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "tv-remote")
                try {
                    if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
                    val md = MessageDigest.getInstance("SHA-256")
                    DigestInputStream(c.inputStream, md).use { copy.push(it, if (c.contentLength >= 0) c.contentLength.toLong() else -1L, SetupUpdate.APK, j) }
                    try { r.check(md) } catch (e: IOException) { sh("rm -f " + SetupUpdate.APK); throw e }
                } finally {
                    c.disconnect()
                }
                SetupUpdate.start(link.get(), SetupUpdate.APK, r.version, locked = true)
            } catch (e: Exception) {
                try { SetupUpdate.unlock(link.get()) } catch (ignored: Exception) { }
                throw e
            }
            j.put("msg", "installing").put("pct", 92)
            val t0 = System.nanoTime() / 1_000_000
            while (System.nanoTime() / 1_000_000 - t0 < 5 * 60_000L) {
                Thread.sleep(2000)
                val s = try { SetupUpdate.state(link.get()) } catch (e: IOException) { continue }   // the TV's ADB may blink meanwhile
                if (s.optString("version") != r.version) {
                    if (System.nanoTime() / 1_000_000 - t0 > 30_000L) throw IOException("the update did not start on the TV")
                    continue
                }
                when (s.optString("state")) {
                    "checking" -> j.put("msg", "checking").put("pct", 96)
                    "ok" -> return@job "updated " + r.version
                    "rolledback" -> throw IOException("setup:rolledback " + r.version + " " + s.optString("from"))
                    "broken" -> throw IOException("setup:broken " + r.version)
                    "failed" -> throw IOException(s.optString("detail").ifEmpty { "install failed" })
                }
            }
            throw IOException("setup:slow")
        }
    }

    // ---------------------------------------------------------------- timers and limits (kept by TV Setup on the TV)
    /** The labels of the limited apps are this device's to give; TV Setup knows only package names when asked over ADB. */
    /**
     * The Telegram card: the bot lives in TV Setup on the TV, so this asks it over the same shell-only broadcast as the rules (the owner's; a paired
     * browser of the TV has its own /api/telegram there). The token goes to the TV once and is never read back: the answer has it masked.
     */
    private fun telegram(r: HttpServer.Request): JSONObject {
        val req = JSONObject()
        if (r.method == "POST") req.put("body", r.json()) else req.put("query", JSONObject().apply { for (k in listOf("history", "chat", "offset")) if (r.q(k).isNotEmpty()) put(k, r.q(k)) })
        var out = RulesClient.call({ sh(it) }, JSONObject().put("do", "telegram").put("req", req))
        if (out.optString("failed") == "not running") { sh(RulesClient.START); Thread.sleep(2500); out = RulesClient.call({ sh(it) }, JSONObject().put("do", "telegram").put("req", req)) }
        val setup = out.optString("setup")
        if (setup == "missing" || setup == "old" || out.optString("error") == "unknown action") throw SetupNeed.Missing("telegram", SetupNeed.have { sh(it) })
        if (setup == "busy") throw IOException("adb: TV Setup is starting")
        if (out.has("failed")) throw IllegalArgumentException(out.optString("failed"))
        return out
    }

    /**
     * Let support look at the TV (TV Setup's Support, RulesReceiver "support"): [act] "start", "stop" or "state". The answer is
     * `{open, code, min, left}`, or `{failed: "no_net" | "none_left" | why}`; the browser of the TV has its own route (RemoteService).
     */
    private fun supportTv(act: String): JSONObject {
        val ask = JSONObject().put("do", "support").put("act", act)
        var out = RulesClient.call({ sh(it) }, ask)
        if (out.optString("setup") == "busy") { sh(RulesClient.START); Thread.sleep(2500); out = RulesClient.call({ sh(it) }, ask) }
        val setup = out.optString("setup")
        if (setup == "missing" || setup == "old" || out.optString("error") == "unknown action")
            throw SetupNeed.Missing("support", SetupNeed.have { sh(it) })
        if (setup == "busy") throw IOException("adb: TV Setup is starting")
        return out
    }

    private fun rules(change: JSONObject?): JSONObject {
        val state = RulesClient.call({ sh(it) }, change)
        val limits = state.optJSONArray("limits")
        if (limits != null && labels != null) for (i in 0 until limits.length()) limits.optJSONObject(i)?.let { l -> if (!l.has("label")) labels.label(l.optString("pkg"))?.let { l.put("label", it) } }
        return state
    }
}
