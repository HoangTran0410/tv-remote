package dev.tvremote.setup

import dev.tvremote.core.Words
import dev.tvremote.core.T
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.tvremote.core.Caps
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.picture.Feeds
import dev.tvremote.core.web.HttpServer
import dev.tvremote.core.web.Invite
import dev.tvremote.core.manage.Manage
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.ontv.Notify
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.ontv.SetupUpdate
import dev.tvremote.core.picture.Tv
import dev.tvremote.core.picture.TvControl
import dev.tvremote.core.picture.Watchers
import dev.tvremote.core.web.WebPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The web remote that lives on the TV: an HTTP server on port 8765 serving one page, and an input session to the TV's
 * own ADB (scrcpy-server without picture) that turns the page's button presses into key events.
 * A browser must be paired first with a code this service shows on the TV; after that it holds a cookie.
 */
class RemoteService : Service(), Tv.Listener, HttpServer.Handler {
    companion object {
        const val PORT = 8765
        const val HTTPS_PORT = 8766                                   // the same page over TLS, with a certificate of its own (Tls.kt)
        private const val PREFS = "remote"
        // two names, as a cookie does not tell ports apart: a Secure one set over HTTPS must not shadow the one set over HTTP
        private const val COOKIE = "tvr"
        private const val COOKIE_TLS = "tvrs"
        private const val COOKIE_AGE = Paired.WEB_DAYS * 24 * 3600
        private val KEYS: Map<String, Int> = Scenes.KEYS

        // pairing: one code at a time, shown on the TV, good for two minutes and five guesses
        @Volatile private var code: String? = null
        @Volatile private var codeUntil = 0L
        @Volatile private var guesses = 0
        @Volatile private var running: RemoteService? = null
        @Volatile private var adbState = "connecting"
        @Volatile private var adbDetail = ""                       // why the last session failed, as the TV said it: the page shows it under "ADB error"

        // sharing (Invite.kt): one invite at a time, ten minutes, one browser; 128 random bits, so no count of guesses
        @Volatile private var invite: String? = null
        @Volatile private var inviteUntil = 0L
        @Volatile private var inviteScope = Scope.REMOTE

        /** [scope]: what the browser that opens it may do (Scope.kt), picked by whoever shares. */
        @Synchronized fun newInvite(scope: String): String {
            val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
            inviteScope = if (scope in Scope.ALL) scope else Scope.REMOTE
            return b.joinToString("") { String.format("%02x", it) }.also { invite = it; inviteUntil = System.currentTimeMillis() + 600000 }
        }

        /** The scope of the invite handed out last, once, in its ten minutes; it is used up then. */
        @Synchronized fun takeInvite(got: String): String? {
            val want = invite ?: return null
            if (System.currentTimeMillis() > inviteUntil || !MessageDigest.isEqual(want.toByteArray(), got.toByteArray())) return null
            invite = null
            return inviteScope
        }

        /** This TV's address on the home network, from its interfaces (no ADB needed): what a phone beside it can reach. */
        fun lanIp(): String = try {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
                .flatMap { java.util.Collections.list(it.inetAddresses) }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: ""
        } catch (e: Exception) { "" }

        /**
         * The pass of the Manage screen on the TV itself (ManageActivity): made anew each time TV Setup starts, kept in its
         * memory only, and taken only from 127.0.0.1, so neither another app on the TV nor a device on the network has it.
         */
        val selfPass: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { String.format("%02x", it) }
        const val SELF_COOKIE = COOKIE

        fun pairingCode(): String? { val c = code; return if (c != null && System.currentTimeMillis() < codeUntil) c else null }
        fun cancelPairing() { code = null }
        fun isRunning(): Boolean = running != null
        /** Tells the Telegram bot something happened (Telegram.alert); nothing when the service or the bot is not running. */
        fun alert(kind: String, vararg args: Pair<String, Any?>) { try { running?.telegram?.alert(kind, *args) } catch (e: Exception) { } }

        /** The code screen opened for [pkg] by [how] (pin, totp, recovery): in Telegram's history, nothing else. */
        fun unlocked(how: String, pkg: String) { try { running?.telegram?.unlocked(how, pkg) } catch (e: Exception) { } }

        /** Three wrong codes in a row on the code screen over [app]: the Full chats are told (Telegram.wrongCodes). */
        fun wrongCodes(app: String) { try { running?.telegram?.wrongCodes(app, Build.MODEL) } catch (e: Exception) { } }

        /** Kids mode's "ask a parent" can be answered: the bot is on and a chat with the Full scope is paired. */
        fun canAsk(): Boolean = try { running?.telegram?.hasParent() == true } catch (e: Exception) { false }

        /** The code screen's question to the parents over Telegram (blocks on the network: not the main thread): "sent", "wait", "nochat". [pkg] empty: more time on the TV. */
        fun askParent(pkg: String, app: String): String = running?.telegram?.askParent(pkg, app, Build.MODEL) ?: "nochat"

        /**
         * The Telegram card of the phone and computer apps (RulesReceiver "telegram", sent by Manage over ADB, so the owner's): [req] is {body, query}
         * as the TV's own page would send /api/telegram. Slow (it asks Telegram), so the receiver calls it from a thread. {failed: why} when the service is not up or refuses (its own state has an "error" field, so that name is taken).
         */
        fun telegramApi(req: JSONObject): JSONObject {
            val t = running?.telegram ?: return JSONObject().put("failed", "not running")
            val body = req.optJSONObject("body")
            val bytes = (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
            val q = HttpServer.Request()
            q.method = if (body != null && body.length() > 0) "POST" else "GET"; q.path = "/api/telegram"; q.peer = "127.0.0.1"
            req.optJSONObject("query")?.let { o -> for (k in o.keys()) q.query[k] = o.optString(k) }
            q.headers["content-type"] = "application/json"; q.body = java.io.ByteArrayInputStream(bytes); q.length = bytes.size.toLong()
            val r = t.config(q)
            val out = try { JSONObject(String(r.body)) } catch (e: Exception) { JSONObject() }
            return if (r.status in 200..299) out else JSONObject().put("failed", out.optString("error").ifEmpty { "error ${r.status}" })
        }

        /**
         * TV Home's Telegram widget (HomeInfo "telegram"): {state: token_missing (no bot yet) | none (a token, switched off) | ready (listening),
         * bot (the @name without @, "" until Telegram has answered once)}; with [newCode] (and ready) a fresh pairing code: {code, code_left (s), link
         * https://t.me/<bot>?start=<code>}, which a phone's camera can open and /start <code> pairs.
         */
        fun telegramForHome(c: Context, newCode: Boolean): JSONObject {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val has = !p.getString("tg_token", "").isNullOrEmpty()
            val state = if (!has) "token_missing" else if (p.getBoolean("tg_on", false)) "ready" else "none"
            val bot = (p.getString("tg_bot", "") ?: "").removePrefix("@")
            val o = JSONObject().put("state", state).put("bot", bot)
            val t = if (state == "ready") running?.telegram else null
            if (t != null) {
                if (newCode) t.newCode()
                val left = t.codeLeftS()
                if (left > 0) { val code = t.json().optString("code"); o.put("code", code).put("code_left", left); if (bot.isNotEmpty()) o.put("link", "https://t.me/$bot?start=$code") }
            }
            return o
        }
        /** The HTTPS port is up: the TV's screen shows that address then. */
        fun secure(): Boolean = running?.https != null
        /** connecting | allow | ready | error: the state of the input session, for the app's own screen. */
        fun adbState(): String = adbState
        /** Why the last session failed, as the TV said it (Support's report). */
        fun adbDetail(): String = adbDetail
        /** A command in the TV's shell over our own ADB link (null without one): what apps may not read, like /proc/stat. */
        fun shell(cmd: String): String? = try { running?.link?.get()?.shell(cmd) } catch (e: Exception) { null }
        /** The ADB session itself (null without the service or a link): HomePower runs its fixed actions on it. */
        fun adb(): dev.tvremote.core.adb.Adb? = try { running?.link?.get() } catch (e: Exception) { null }

        /** The anonymous-stats switch and install id as the census keeps them (Census.enabled / Census.id): what TV Home, Channels and Games follow (HomeInfo "census"). */
        fun censusState(c: Context): JSONObject {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return JSONObject().put("on", p.getString("census.on", null) != "0").put("id", p.getString("census.id", null)?.takeIf { it.length == 36 } ?: "")
        }

        /**
         * What the apps ask before showing their own Update popup (HomeInfo "updates"): {silent: true} when [AutoUpdate] is on and the
         * service holds an ADB link (or is dialling it), so it installs their updates itself within hours. Not asking the link to
         * dial: "closed" / "unauthorized" is a TV where it cannot.
         */
        fun updatesState(c: Context): JSONObject {
            val l = running?.link
            val st = try { l?.state() } catch (e: Exception) { null }
            // "unknown" is also the redial every 10 s of a TV with no ADB at all (seen on an FPT box): quiet only if the last dial got through
            return JSONObject().put("silent", AutoUpdate.on(c) && (st == "ready" || (st == "unknown" && l?.failing() == false)))
        }

        /** TV Apps' install through our ADB link (HomeInfo "tvapps", Manage.tvApps); null: no service or no link ready, the app installs it with Android's installer. */
        fun tvApps(op: String, id: String): JSONObject? {
            val s = running ?: return null
            if (op == "install" && (try { s.link?.state() } catch (e: Exception) { null }) != "ready") return null
            return s.manage?.tvApps(op, id)
        }

        fun enabled(c: Context): Boolean =c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

        /** The service runs for the web remote, and also, with the web remote off, while timers or limits need watching. */
        fun wanted(c: Context): Boolean = enabled(c) || Rules.active(c)

        fun setEnabled(c: Context, on: Boolean, background: Boolean = false) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).commit()
            c.stopService(Intent(c, RemoteService::class.java))       // started again with or without its web server
            if (wanted(c)) start(c, background)
        }

        /**
         * [background]: from a receiver (boot, update, the shell's broadcast). Some TVs (TCL's TclAppBoot) refuse a foreground start
         * from there, and then startForegroundService() kills the whole process 10 s later ("did not then call startForeground()").
         * A plain startService() has no such deadline; the receiver is let start a service for a few seconds, and onCreate asks
         * for the foreground itself, in a try. Only where the system refuses the plain start does it fall back.
         */
        fun start(c: Context, background: Boolean = false) {
            val i = Intent(c, RemoteService::class.java)
            if (Build.VERSION.SDK_INT < 26) { c.startService(i); return }
            if (background) {
                try { c.startService(i); return } catch (e: IllegalStateException) { }   // "not allowed to start service": background limits
            }
            try { c.startForegroundService(i) } catch (e: IllegalStateException) { }   // Android 12+ refuses it from the background: the next boot or opening TV Setup starts it
        }

        fun pairedCount(c: Context): Int = Paired.count(c)

        /** A new pass for a device, kept with the others (pairing hands them out, and TrustReceiver to ADB). */
        fun newToken(c: Context, kind: String, name: String, ip: String, tls: Boolean): String = Paired.add(c, kind, name, ip, tls)

        fun forgetAll(c: Context) { Paired.forgetAll(c) }

        /**
         * Check-out (RulesReceiver "forget_web", sent over ADB by the several-TVs tool): every paired browser forgotten, the code on
         * the TV and any invite (a shared QR) dead, so the next browser needs a fresh code shown on the TV's own screen. Returns
         * how many browsers went. A picture already streaming to a forgotten browser is not cut (its request was let in once).
         */
        @Synchronized fun rotateWeb(c: Context): Int {
            val n = Paired.forgetBrowsers(c)
            code = null; guesses = 0; invite = null
            return n
        }

        private fun cookie(token: String, tls: Boolean, age: Int = COOKIE_AGE): String =
            (if (tls) COOKIE_TLS else COOKIE) + "=$token; Max-Age=$age; Path=/; HttpOnly; SameSite=Strict" + (if (tls) "; Secure" else "")

        private const val REST_MS = 3 * 60 * 1000L
        /** Needs the input session (`tv`) or is a hand on the remote: wakes it when resting and keeps it from resting. */
        private val SESSION = setOf("/api/key", "/api/type", "/api/touch", "/api/clip", "/api/screen", "/api/adb/connect", "/api/warm")
        /** What a page asks by itself every few seconds: says nothing about anyone using the remote. */
        private val PASSIVE = setOf("/api/state", "/api/watchers", "/api/about", "/api/playing", "/api/health", "/api/stats", "/api/paired", "/api/app/icon", "/api/app/meta", "/api/setup/need", "/api/wirelog")
        private fun ok(): HttpServer.Response = HttpServer.Response.ok()
    }

    private val ME = Paired.Entry("tv", "", "TV", "127.0.0.1", 0L, 0L, "tv", false, Scope.FULL)   // the Manage screen on the TV: never in the list

    private val ui = Handler(Looper.getMainLooper())

    // Home Assistant and Shortcuts (Hook.kt): on this port already, so no listener of its own. /api/hook has its token and no
    // pairing; the switch and the token are changed only by the owner's page (/api/hook/config, Scope.FULL).
    private val hook: dev.tvremote.core.web.Hook by lazy {
        dev.tvremote.core.web.Hook(object : dev.tvremote.core.web.Hook.Store {
            override var enabled: Boolean get() = prefs.getBoolean("hook_on", false); set(v) { prefs.edit().putBoolean("hook_on", v).apply() }
            override var token: String get() = prefs.getString("hook_token", "") ?: ""; set(v) { prefs.edit().putString("hook_token", v).apply() }
        }, dev.tvremote.core.web.Hook.Routes(), { used(it.path); route(it, "127.0.0.1", ME) }, { state("127.0.0.1") }, { scenes() },
            { lanIp().takeIf { it.isNotEmpty() }?.let { "http://$it:$PORT" } ?: "" })
    }

    // The Telegram bot (Telegram.kt): long polling from here, so no port; it lives as long as this service. Its commands go through
    // route() like the hook's, with the scope of the chat that sent them (a REMOTE chat is refused where a REMOTE browser is).
    private val telegram: dev.tvremote.core.web.Telegram by lazy {
        dev.tvremote.core.web.Telegram(object : dev.tvremote.core.web.Telegram.Store {
            override var enabled: Boolean get() = prefs.getBoolean("tg_on", false); set(v) { prefs.edit().putBoolean("tg_on", v).apply() }
            override var token: String get() = prefs.getString("tg_token", "") ?: ""; set(v) { prefs.edit().putString("tg_token", v).apply() }
            override var offset: Long get() = prefs.getLong("tg_offset", 0); set(v) { prefs.edit().putLong("tg_offset", v).apply() }
            override var lang: String get() = prefs.getString("tg_lang", "") ?: ""; set(v) { prefs.edit().putString("tg_lang", v).apply() }
            override var bot: String get() = prefs.getString("tg_bot", "") ?: ""; set(v) { prefs.edit().putString("tg_bot", v).apply() }
            override var chats: String get() = prefs.getString("tg_chats", "[]") ?: "[]"; set(v) { prefs.edit().putString("tg_chats", v).apply() }
            override var history: String get() = prefs.getString("tg_history", "[]") ?: "[]"; set(v) { prefs.edit().putString("tg_history", v).apply() }
        }, dev.tvremote.core.web.TelegramHttp(telegramBase()), hook,
            { r, scope -> used(r.path); route(r, "127.0.0.1", Paired.Entry("tv", "", "Telegram", "127.0.0.1", 0L, 0L, "tv", false, scope)) },
            { state("127.0.0.1") },
            { val a = apps(); (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("off") }.map { it.optString("pkg") to it.optJSONObject("meta")!!.optString("label") } },
            { code -> ui.post { NotifyLayer.show(this, Notify.clean(JSONObject().put("title", T("telegram.card_title")).put("text", T("telegram.card_text", "code" to code)).put("seconds", 60))) } },
            onDate = { ms -> Clock.note(this, ms, Clock.TELEGRAM) }, skew = { Clock.driftMs(this) }).also { telegramStarted = true }
    }
    @Volatile private var telegramStarted = false

    /** Telegram's address; another one (a fake server for tests) is read from a file only in a debuggable build. */
    private fun telegramBase(): String {
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val u = if (!debug) null else try { java.io.File(getExternalFilesDir(null), "telegram-url.txt").takeIf { it.isFile }?.readText()?.trim() } catch (e: Exception) { null }
        return u?.takeIf { it.startsWith("http") }?.trimEnd('/') ?: "https://api.telegram.org"
    }
    private val random = SecureRandom()
    private val icons: MutableMap<String, ByteArray> = ConcurrentHashMap()
    private val held = HashMap<Int, Runnable>()
    private var http: HttpServer? = null
    private var https: HttpServer? = null
    private val front = dev.tvremote.core.ontv.Foreground()
    private var link: AdbLink? = null                                 // for apps, files, installs and health; the keys go through `tv`
    private var manage: Manage? = null
    private lateinit var prefs: SharedPreferences
    @Volatile private var tv: Tv? = null
    private val feeds = Feeds()                                       // the picture and the sound, one session each for every browser
    @Volatile private var stopping = false
    @Volatile private var lastUse = 0L                                // when a paired browser last used the input session (keys, touch, picture...)
    @Volatile private var lastSeen = 0L                               // when a paired browser last asked for anything but a poll: updates wait for it
    @Volatile private var resting = false                             // the input session is closed on purpose: nobody is using the remote
    private var lastPairStart = 0L
    private val backoff = PairBackoff()                               // per peer: code screens shown that nobody finished
    @Volatile private var wrong = 0                                  // wrong codes since the last pairing or lockout
    @Volatile private var lockedUntil = 0L
    private var guard: Guard? = null
    private var launcherGuard: LauncherGuard? = null
    private var tsWatch: TailscaleWatch? = null
    private var dnsWatch: DnsWatch? = null
    private var keepAdb: KeepAdb? = null
    private var reoptimize: Reoptimize? = null
    private var nightly: Nightly? = null
    private var keyFix: KeyFixWatch? = null
    private var homeCompile: HomeCompile? = null
    internal var keepAwake: KeepAwake? = null
    private var welcome: WelcomeWatch? = null
    private var autoOpen: AutoOpenWatch? = null
    private var updates: AutoUpdate? = null
    private var serving = true                                        // the web server started, when it is wanted
    @Volatile private var saidAlive = false
    private val version by lazy { try { packageManager.getPackageInfo(packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" } }
    private val page by lazy { WebPage({ assets.open(it) }, "tv", try { packageManager.getPackageInfo(packageName, 0).let { it.versionName + "-" + it.lastUpdateTime / 1000 } } catch (e: Exception) { "0" }) }

    override fun onBind(i: Intent?): IBinder? = null

    /** The anonymous census (core/Census.kt): this TV's own build properties; ADB shows up as it connects, or as "failed" if it has not by then. */
    private fun startCensus(l: AdbLink) {
        val c = dev.tvremote.core.Census
        c.init("setup", try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }, object : dev.tvremote.core.Census.Store {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
        })
        c.useTestFile(getExternalFilesDir(null))
        dev.tvremote.core.CensusAndroid.use()                       // errors the guard catches are reported with the same switch
        c.self = {
            val m = HashMap<String, String>()
            m["ro.product.brand"] = Build.BRAND; m["ro.product.model"] = Build.MODEL; m["ro.product.device"] = Build.DEVICE
            m["ro.product.board"] = Build.BOARD; m["ro.hardware"] = Build.HARDWARE
            m["ro.soc.model"] = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
            m["ro.board.platform"] = try { Runtime.getRuntime().exec(arrayOf("getprop", "ro.board.platform")).inputStream.bufferedReader().readText().trim() } catch (e: Exception) { "" }
            m["ro.build.version.release"] = Build.VERSION.RELEASE; m["ro.build.version.sdk"] = Build.VERSION.SDK_INT.toString()
            dev.tvremote.core.Census.Props(m, packageManager.hasSystemFeature("android.software.leanback"))
        }
        if (!c.enabled) return
        Thread({
            try { Thread.sleep(40000); if (l.state() != "ready") c.failed("127.0.0.1") } catch (e: Exception) { }
        }, "census-adb").apply { isDaemon = true }.start()
    }

    /** Asked at once in onCreate and again in each onStartCommand: a service already running (or once refused) got no new
     *  startForeground() for a later startForegroundService(), and the system killed TV Setup 10 s on (TCL, Android 11: c2ce1b14). */
    private fun foreground() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("remote", "TV Remote", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        @Suppress("DEPRECATION")
        val n = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "remote") else Notification.Builder(this)
        try { startForeground(1, n.setSmallIcon(R.drawable.ic_power).setContentTitle("TV Remote").setContentText("Web remote: port $PORT").setOngoing(true).build()) }
        catch (e: Exception) { }                                   // refused (vendor block, Android 12+ background): it still serves as a plain service
    }

    override fun onCreate() {
        super.onCreate()
        foreground()                                                  // first: the 10 s count runs from startForegroundService(), slow TVs used most of it below
        dev.tvremote.core.scenes.VoicePhrases.start({ assets.open(it) }, filesDir)   // the page asks for the same words (voice/phrases.json)
        dev.tvremote.core.adb.Adb.holdFirstAsk(10000)                       // a phone turning ADB on asks first, then allows us too (AdbPrompt.helpSetup)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        ScenePin.publishFirst(this, scenes())
        running = this
        DimLayer.restore(this)
        Support.resume(this)                                          // support open when TV Setup stopped (an update, a kill) goes on
        dev.tvremote.core.WireLog.app = "TV Setup " + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }) +
            " (TV web page, Android " + Build.VERSION.RELEASE + ")"
        val server = HttpServer(this)
        http = server
        dev.tvremote.core.Downloader.dir = filesDir                   // the signed Downloader codes are kept here
        val l = AdbLink(filesDir, "127.0.0.1")
        link = l
        startCensus(l)
        manage = Manage(l, object : Manage.Labels {
            @Volatile var own: Map<String, String>? = null              // TV Home's names, read once over ADB, then kept by the page's changes
            override fun own(m: Map<String, String>) { own = m }
            override fun label(pkg: String): String? = (own ?: try { dev.tvremote.core.ontv.Home.ownNames { l.get().shell(it) }.also { own = it } } catch (e: Exception) { emptyMap() })[pkg] ?:   // no ADB yet: asked again
                try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { null }
            override fun icons(): Boolean = true
            private val caps by lazy { Caps.report(this@RemoteService) }   // the hardware does not change while TV Setup runs
            override fun caps(): String = caps
        }, getExternalFilesDir(null)) { assets.open("tvr-helper.jar").use { it.readBytes() } }
        manage?.canPair = dev.tvremote.core.adb.AdbTls.available()    // a TV with Wireless debugging only: the code is typed on the web page, from a phone
        manage?.allowed = { fp ->                                      // whoever is at the TV sees that a device was let in
            ui.post { android.widget.Toast.makeText(this, T("tv_server.usb_allowed") + "\n" + fp, android.widget.Toast.LENGTH_LONG).show() }
        }
        if (!enabled(this)) try { server.start(PORT, true) } catch (e: Exception) { }   // the web remote is off: only the Manage screen on the TV
        if (enabled(this)) {
            try { server.start(PORT, false) } catch (e: Exception) { adbState = "error"; serving = false }
            Thread({                                                  // the first time this makes a key: a few seconds on a slow TV
                try {
                    val s = HttpServer(this)
                    s.start(HTTPS_PORT, false, Tls.context(this))
                    if (stopping) s.stop() else https = s
                } catch (e: Exception) { dev.tvremote.core.WireLog.all.add("·", "https: " + e.message) }   // plain HTTP still works
            }, "https").apply { isDaemon = true }.start()
        }
        lastUse = System.currentTimeMillis()
        lastSeen = lastUse
        connect()
        guard = Guard(this) { try { link?.get() } catch (e: Exception) { null } }.also { it.start() }
        dev.tvremote.core.ontv.Launchers.useTestFile(getExternalFilesDir(null))
        dev.tvremote.core.ontv.Launchers.dir = java.io.File(cacheDir, "launchers")   // the gallery's list and pictures kept: it opens without waiting on the network
        launcherGuard = LauncherGuard(this) { try { link?.get() } catch (e: Exception) { null } }.also { it.start() }
        tsWatch = TailscaleWatch(this) { try { link?.get() } catch (e: Exception) { null } }.also { it.start() }
        dnsWatch = DnsWatch(this) { try { link?.get() } catch (e: Exception) { null } }.also { it.start() }
        keepAdb = KeepAdb(this).also { it.start() }
        reoptimize = Reoptimize(this) { try { link?.get() } catch (e: Exception) { null } }.also { it.start() }
        nightly = Nightly(this) { try { link?.get() } catch (e: Exception) { null } }.also { it.start() }
        keyFix = KeyFixWatch(this).also { it.start() }
        homeCompile = HomeCompile(this).also { it.start() }
        keepAwake = KeepAwake(this).also { it.start(); it.check() }
        welcome = WelcomeWatch(this).also { it.start() }
        autoOpen = AutoOpenWatch(this).also { it.start() }
        updates = AutoUpdate(this, version, { try { link?.get() } catch (e: Exception) { null } }) { busyWhy() }.also { it.start() }
        telegram.sync()                                               // on only when the owner switched it on and gave a token
        ui.postDelayed(rest, 30000)
        ui.postDelayed(diary, 20000)
        stats.watchNet()
        stats.watchPower()
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        foreground()                                                  // every startForegroundService() wants its own answer, not only the first
        if (saidAlive) afterUpdate()                                  // started again by an update's script that missed the first word
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        running = null
        code = null
        http?.stop()
        https?.stop()
        guard?.stop()
        launcherGuard?.stop()
        tsWatch?.stop()
        dnsWatch?.stop()
        keepAdb?.stop()
        reoptimize?.stop()
        nightly?.stop()
        keyFix?.stop()
        homeCompile?.stop()
        keepAwake?.stop()
        welcome?.stop()
        autoOpen?.stop()
        updates?.stop()
        if (telegramStarted) telegram.stop()
        stats.stopNet()
        stats.stopPower()
        link?.close()
        tv?.stop()
        feeds.stop()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- the input session to the TV's own ADB
    @Synchronized
    private fun connect() {
        if (stopping || tv != null) return
        resting = false
        adbState = "connecting"
        var mine: Tv? = null
        mine = Tv(this, "127.0.0.1", null, 0, 0, false, object : Tv.Listener {   // a session we have let go of no longer has a say
            override fun onStatus(key: String, detail: String) { if (mine == null || tv === mine) this@RemoteService.onStatus(key, detail) }
            override fun onVideoSize(w: Int, h: Int) { }
            override fun onNetwork(mac: String, lan: String, tailscale: String) { }
            override fun wantsNetwork() = false                        // the address is not used here: one shell less on every wake
        }, quiet = true)                                               // never turns a TV in standby on (issue #6)
        tv = mine
    }

    /**
     * The input session keeps a scrcpy-server process alive on the TV, about 50 MB of its 2 GB. So it only runs while
     * somebody really uses it: a key, typing, touch, the clipboard, a screenshot (SESSION), or the page saying a hand is on
     * the remote (/api/warm); not while a browser watches, whose picture's session takes them ([input]). Polls (/api/state,
     * /api/playing, health: PASSIVE) do not count, so a tab left open lets it rest. After [REST_MS] without use only the scrcpy process is stopped: the saved key, the pushed scrcpy-server.jar
     * and the ADB link used by everything else stay, so waking costs one ADB login and the start of app_process on the TV.
     * The next use opens it again; [wait] holds the request for it (up to 4 s) so the first key still works.
     */
    /**
     * Where keys, typing and the clipboard go: the picture's session while a browser watches (it is a scrcpy-server with
     * control too), so the input session's own (~50 MB of the TV's memory) can rest meanwhile ([rest]); else the input session.
     */
    private fun input(): Tv? = feeds.viewer?.takeIf { it.ready() } ?: tv

    private fun used(path: String) {
        if (path == "/api/audio" || path == "/api/video") lastUse = System.currentTimeMillis()   // not idle, but they need no input session
        if (path in SESSION && feeds.viewer?.ready() == true && path != "/api/adb/connect") lastUse = System.currentTimeMillis()   // the picture's session takes it: see input()
        else if (path in SESSION) inUse(wait = path != "/api/warm")
        else if (path !in PASSIVE) lastSeen = System.currentTimeMillis()
    }

    private fun inUse(wait: Boolean = true) {
        lastUse = System.currentTimeMillis()
        lastSeen = lastUse
        if (tv == null) { ui.removeCallbacks(retry); connect() }         // resting, or waiting out a failure: someone wants it now
        if (wait && adbState == "connecting") for (i in 0 until 40) { // up to 4 s, usually about a second
            if (adbState != "connecting") break
            try { Thread.sleep(100) } catch (e: InterruptedException) { break }
        }
    }

    /** The diary for the History view: one entry every five minutes, the first shortly after the service starts. */
    private val diary = object : Runnable {
        override fun run() {
            if (stopping) return
            Thread({
                val m = manage
                val on = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
                if (!usageAllowed) usageAllowed = m?.allowUsage(packageName) == true
                stats.tick(if (on) m?.cpuNow() else null)             // asleep: do not wake ADB for a number nobody needs
            }, "diary").apply { isDaemon = true }.start()
            ui.postDelayed(this, 5 * 60 * 1000L)
        }
    }
    private val stats by lazy { Stats(this) }
    private var usageAllowed = false

    private var retryMs = 5000L
    private var failSince = 0L                                        // the failed tries since ADB last worked (AdbReport)
    private var fails = 0
    private val retry = Runnable { connect() }

    private val rest = object : Runnable {
        override fun run() {
            if (stopping) return
            val t = tv
            if (t != null && adbState == "ready" && (feeds.viewer?.ready() == true || System.currentTimeMillis() - lastUse > REST_MS)) {
                resting = true
                tv = null
                t.stop()
                adbState = "ready"                                    // nothing is wrong; it comes back with the next request
            }
            ui.postDelayed(this, 30000)
        }
    }

    override fun onStatus(key: String, detail: String) {
        if (stopping) return
        when (key) {
            "allow" -> {                                              // the TV is asking "Allow USB debugging?"
                adbState = "allow"
                if (failSince == 0L) failSince = android.os.SystemClock.elapsedRealtime()
                AdbReport.failing(this, "allow", "", failSince, ++fails, version)   // asked for minutes and never allowed
            }
            "live" -> {
                adbState = "ready"
                adbDetail = ""
                retryMs = 5000
                failSince = 0L; fails = 0
                if (!saidAlive && serving) { saidAlive = true; afterUpdate() }
                if (!KeepAdb.can(this) || !KeyFix.prepared) Thread({ try { link?.get()?.let { KeepAdb.grant(this, it); KeyFix.prepare(this, it); WebViewFix.repair(it); if (MicVoice.on(this)) MicVoice.grant(this, it); KeyFix.sync(this) } } catch (e: Exception) { } }, "grant").apply { isDaemon = true }.start()
            }
            "closed", "error" -> {                                    // ADB off, TV just booted, session dropped: keep trying
                if (resting) return                                   // we closed it ourselves
                val o = LinkFailure.of(key, detail, retryMs) { dev.tvremote.core.Errors.say(java.io.IOException(it), dev.tvremote.core.Words.lang) }
                adbDetail = o.detail
                adbState = o.state
                tv = null
                ui.removeCallbacks(retry); ui.postDelayed(retry, o.retryMs)
                if (o.backoff) retryMs = minOf(retryMs * 2, 5 * 60_000L)   // ADB off for good: every 5 min at most, not a session every 5 s forever; a use tries at once
                if (failSince == 0L) failSince = android.os.SystemClock.elapsedRealtime()
                fails++
                AdbReport.failing(this, adbState, detail, failSince, fails, version)   // never through for minutes: the census hears it once
            }
        }
    }

    override fun onVideoSize(w: Int, h: Int) { }
    override fun onNetwork(mac: String, lan: String, tailscale: String) { }

    // ---------------------------------------------------------------- updates of this app (AutoUpdate, SetupUpdate)
    /** Up, serving and through to the TV's ADB: an update that is waiting to know whether this version works keeps it. */
    private fun afterUpdate() = Thread({
        try { link?.get()?.let { SetupUpdate.alive(it, version) } } catch (e: Exception) { }
        if (!wanted(this)) ui.post { stopSelf() }                     // started by the update only: nothing for it to do
    }, "alive").apply { isDaemon = true }.start()

    /** Why an update would be noticed now, or null when nobody would. */
    private fun busyWhy(): String? {
        val now = System.currentTimeMillis()
        if (now - lastSeen < 30 * 60_000L) return "the remote was used lately"
        if (tv != null || feeds.viewer != null) return "a remote session is open"
        if (pairingCode() != null) return "pairing"
        if (MainActivity.shown) return "TV Setup is on screen"
        val o = Rules.load(this)
        val at = o.optLong("sleep_at")
        if (at > 0 && at - now < 15 * 60_000L) return "the sleep timer ends soon"
        if (Rules.inBedtime(o, now) && (getSystemService(POWER_SERVICE) as PowerManager).isInteractive) return "bedtime"
        return null
    }

    // ---------------------------------------------------------------- the picture, one session for every browser
    /** The picture for the page, from a second session made only while a browser watches: see Feeds. */
    private fun video(quality: Int, fps: Int, rate: Int, enc: String, hand: Boolean): HttpServer.Response =
        feeds.video("$quality/$fps/$rate/$enc/$hand") { l, sink -> Tv(this, "127.0.0.1", null, quality, 1280, false, l, sink, fps = fps, bitRate = rate, encoder = enc, hand = hand) }

    /** The one scenes handler of every core (Scenes.Routes); here the list is kept in the preferences. */
    private val sceneRoutes by lazy {
        Scenes.Routes({ scenes() }, { prefs.edit().putString("scenes", it.toString()).apply(); ScenePin.publish(this, it) }, { "adb:$adbState" }) {
            link?.let { l -> Scenes.Shell({ l.get().shell(it) }) { m -> Rules.change(this, JSONObject().put("do", "sleep").put("minutes", m)) } }
        }.also { it.pin = { sc -> ScenePin.request(this, sc) } }
    }

    private fun scenes(): JSONArray = try { JSONArray(prefs.getString("scenes", "[]")) } catch (e: Exception) { JSONArray() }

    // ---------------------------------------------------------------- the sound, one session for every browser
    /** While someone listens the TV's own speakers are silent (unless [share] works there); they come back when the browser stops. */
    private fun sound(share: Boolean, hq: Boolean): HttpServer.Response =
        feeds.sound("raw/$share", hq, false) { l, sink -> Tv(this, "127.0.0.1", null, 0, 0, false, l, null, "raw", sink, false, share) }

    // ---------------------------------------------------------------- HTTP
    @Throws(Exception::class)
    override fun handle(r: HttpServer.Request): HttpServer.Response {
        // a web page elsewhere must not be able to talk to us through the visitor's browser (DNS rebinding): only IPs are served
        val host = r.headers["host"].toString().replaceFirst(Regex(":\\d+$"), "")
        if (!host.matches(Regex("[0-9.]+|localhost|\\[[0-9a-fA-F:]+\\]"))) return HttpServer.Response.error(403, "open this page by the TV's IP address")

        page.serve(r.path, r.headers["accept-encoding"].orEmpty())?.let { return it }                        // the page and its scripts need no pairing: they hold no secrets
        hook.handle(r)?.let { return it }                            // its own token instead of a pairing (Hook.kt); off until switched on
        if (r.path == "/api/pair/start" && r.method == "POST") return pairStart(r.peer, r.lang)
        if (r.path == "/api/pair/finish" && r.method == "POST") return pairFinish(r)
        if (r.path == "/api/pair/invite" && r.method == "POST") return pairInvite(r)
        // a pass made over TLS is only taken over TLS; the Secure cookie never comes over HTTP anyway
        var token = if (r.tls) r.cookie(COOKIE_TLS) else ""
        if (!r.tls && r.peer == "127.0.0.1" && MessageDigest.isEqual(r.cookie(COOKIE).toByteArray(), selfPass.toByteArray())) {
            used(r.path)                                              // the Manage screen polls like any page: no wake for that
            return route(r, host, ME)
        }
        var hit = Paired.check(this, token, r.tls)
        if (hit == null) { token = r.cookie(COOKIE); hit = Paired.check(this, token, r.tls) }
        if (hit == null) return HttpServer.Response.json(401, JSONObject().put("error", "not paired").put("name", Build.MODEL))

        used(r.path)
        val out = route(r, host, hit.entry)
        if (hit.renew) out.headers["Set-Cookie"] = cookie(token, r.tls)   // a browser in use keeps its 30 days
        return out
    }

    @Throws(Exception::class)
    private fun route(r: HttpServer.Request, host: String, me: Paired.Entry): HttpServer.Response {
        val post = r.method == "POST"
        if (!Scope.allows(me.scope, r.path, post, r.query) { r.json() }) return HttpServer.Response.json(403, JSONObject().put("error", "scope").put("scope", me.scope))
        val owner = me.scope == Scope.FULL
        when (r.path) {
            // a guest sees only itself, to forget it; the owner sees everyone and what each may do
            "/api/paired" -> if (!post) return HttpServer.Response.json(JSONObject().put("devices", JSONArray(Paired.list(this).filter { owner || it.id == me.id }.map { it.json(it.id == me.id) })))
            "/api/paired/scope" -> if (post) {
                val d = r.json()
                return if (Paired.setScope(this, d.optString("id"), d.optString("scope"))) ok() else HttpServer.Response.error(400, "no such device")
            }
            "/api/paired/forget" -> if (post) {
                val d = r.json()
                if (!owner && (d.optBoolean("all") || d.optString("id") != me.id)) return HttpServer.Response.json(403, JSONObject().put("error", "scope").put("scope", me.scope))
                if (d.optBoolean("all")) Paired.forgetAll(this) else Paired.forget(this, d.optString("id"))
                val gone = d.optBoolean("all") || d.optString("id") == me.id
                val out = ok()
                if (gone) { out.headers["Set-Cookie"] = cookie("", r.tls, 0) }   // forgetting oneself logs out: the page asks for a code
                return out
            }
            "/api/hook/config" -> return hook.config(r)               // Scope.allows keeps everyone but the owner out
            "/api/telegram" -> return telegram.config(r)              // the same: the token is never sent back, only masked
            "/api/support/tv" -> return HttpServer.Response.json(Support.act(this, if (post) r.json().optString("act") else "state"))   // the page's "let support look" (Manage does it over ADB for the other apps)
            "/api/state" -> return HttpServer.Response.json(state(host).put("scope", me.scope))
            "/api/share" -> if (post) return HttpServer.Response.json(if (!enabled(this)) JSONObject().put("off", true)   // the web remote off: only 127.0.0.1 is served, no phone could open it (Manage on the TV asks)
                else Invite.link(lanIp(), newInvite(r.json().optString("scope")), host))   // a paired page shares the TV it is on
            "/api/about" -> return HttpServer.Response.json(state(host))
            "/api/setup/need" -> return HttpServer.Response.json(JSONObject().put("ok", true))   // this is TV Setup: it has what this page knows
            "/api/screen" -> {                                        // one picture of the screen: the Screenshots mode, and the camera key
                val png = input()?.screenshot() ?: return HttpServer.Response.error(503, "no screenshot")
                val shot = HttpServer.Response.of("image/png", png)
                shot.headers["Cache-Control"] = "no-store"
                return shot
            }
            "/api/video" -> return video(r.q("q").toIntOrNull() ?: 1, r.q("fps").toIntOrNull() ?: 0, r.q("rate").toIntOrNull() ?: 0, r.q("enc").takeIf { it == "hw" || it == "sw" } ?: "auto", r.q("hand") == "1")
            "/api/audio" -> return sound(r.query["share"] == "1", r.query["hq"] == "1")
            "/api/watchers" -> return HttpServer.Response.json(feeds.count(try { link?.takeIf { adbState == "ready" }?.get()?.shell(Watchers.CMD) ?: "" } catch (e: Exception) { "" }))
            "/api/rules" -> return try {
                val st = Rules.change(this, if (r.method == "POST") r.json() else JSONObject())
                HttpServer.Response.json(st)
            } catch (e: CodeWrong) { HttpServer.Response.json(400, JSONObject().put("error", "code_wrong").put("clock", e.clock))
            } catch (e: IllegalArgumentException) { HttpServer.Response.error(400, e.message.toString()) }
            "/api/scenes", "/api/scene/run", "/api/scene/pin" -> return sceneRoutes.handle(r) ?: HttpServer.Response.error(405, "post")   // run and pin only take a POST
            "/api/touch" -> {
                val v = feeds.viewer?.takeIf { it.ready() } ?: return ok()   // no picture session: nothing to touch
                return TvControl.touch(v, r.json())                          // the wheel (scroll) too, as on the computer
            }
            "/api/apps" -> if (r.q("system") != "1") return HttpServer.Response.json(JSONObject().put("apps", apps()))   // every package: Manage
            "/api/app/icon" -> {
                val png = icon(r.query["pkg"].toString()) ?: return HttpServer.Response.error(404, "no icon")
                val icon = HttpServer.Response.of("image/png", png)
                icon.headers["Cache-Control"] = "max-age=86400"
                return icon
            }
            "/api/clip" -> {                                          // the TV's clipboard: through scrcpy, whose shell may read it
                val t = input()
                if (t == null || !t.ready()) return HttpServer.Response.error(503, "adb:$adbState")
                if (post) { val d = r.json(1_000_000); t.setClipboard(d.optString("text").take(100000), d.optBoolean("paste")); return ok() }
                val text = t.clipboard() ?: return HttpServer.Response.error(504, "the TV did not answer")
                return HttpServer.Response.json(JSONObject().put("text", text))
            }
            "/api/warm" -> return ok()                                // inUse() has done it: the page's hand is on the remote
            "/api/adb/connect" -> { if (tv == null) connect(); inUse(); return HttpServer.Response.json(JSONObject().put("state", adbWord())) }
        }
        val managed = manage?.handle(r)
        if (managed != null) return managed
        if (!post) return HttpServer.Response.error(404, "not found")
        val t = input()
        val d = r.json()
        when (r.path) {
            "/api/key" -> {
                val keyCode = KEYS[d.optString("key").uppercase(Locale.ROOT)] ?: return HttpServer.Response.error(400, "unknown key")
                if (t == null || !t.ready()) return HttpServer.Response.error(503, "adb:$adbState")
                val action = when (d.optString("dir")) { "START_LONG" -> "down"; "END_LONG" -> "up"; else -> "press" }   // the words of the computer's server
                if (action == "press" && keyCode in dev.tvremote.core.ontv.RulesClient.SETUP_KEYS && KeysBridge.press(this, keyCode)) return ok()   // as a real press would
                if (action == "press" && keyCode == HomeKey.CODE && HomeKey.pkg(this) != null && KeysBridge.press(this, keyCode)) return ok()   // Home to the launcher picked (a Chromecast)
                if (action == "press" && keyCode in Tv.OPENERS)   // keys that should open something
                    return HttpServer.Response.json(JSONObject().put("ok", true).put("noop", !t.pressSeen(keyCode)))
                if (keyCode == 26) {                                  // sleep or wake by what the TV is doing now, so a tap never does the opposite
                    if (action != "up") t.press(if ((getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive) 223 else 224)
                } else if (keyCode == 176) { if (action != "up") t.settings() }   // the key itself does nothing on many TVs
                else key(t, keyCode, action)
                return ok()
            }
            "/api/type" -> {
                if (t == null || !t.ready()) return HttpServer.Response.error(503, "adb:$adbState")
                return TvControl.type(t, d)
            }
            else -> return HttpServer.Response.error(404, "not found")
        }
    }

    /**
     * down / up are separate so holding a button in the browser is a long press on the TV. While it is held the key repeats
     * (fast scrolling, volume), and a lost "up" is sent for it after 8 s.
     */
    private fun key(t: Tv, keyCode: Int, action: String) {
        synchronized(held) {
            val old = held.remove(keyCode)
            if (old != null) ui.removeCallbacks(old)
            if (action == "down") {
                t.key(0, keyCode, 0, 0)
                val until = System.currentTimeMillis() + 8000
                val repeat = object : Runnable {
                    var count = 0
                    override fun run() {
                        synchronized(held) {
                            if (held[keyCode] !== this) return
                            if (System.currentTimeMillis() > until) { held.remove(keyCode); t.key(1, keyCode, 0, 0); return }
                            t.key(0, keyCode, ++count, 0)
                            ui.postDelayed(this, 80)
                        }
                    }
                }
                held[keyCode] = repeat
                ui.postDelayed(repeat, 450)
            } else if (action == "up") {
                t.key(1, keyCode, 0, 0)
            } else {
                if (old != null) t.key(1, keyCode, 0, 0)
                t.press(keyCode)
            }
        }
    }

    /** ready, unauthorized, closed, error or unknown: the words the page knows (the computer's server uses them too). */
    private fun adbWord(): String = when (adbState) { "ready" -> "ready"; "allow" -> "unauthorized"; "connecting" -> "unknown"; "error" -> "error"; else -> "closed" }

    /** What the page shows at the top, in the shape the computer's server gives it. */
    @Throws(Exception::class)
    private fun state(host: String): JSONObject {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        var version: String? = ""
        try { version = packageManager.getPackageInfo(packageName, 0).versionName } catch (ignored: Exception) { }
        return JSONObject().put("core", "tv").put("host", host).put("name", Build.MODEL).put("connected", true).put("is_on", pm.isInteractive).put("paired", true)
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("android", Build.VERSION.RELEASE))
            .put("version", version).put("app_name", "TV Setup")
            .put("released", try { getString(resources.getIdentifier("released", "string", packageName)).split("-").let { it[2] + "/" + it[1] + "/" + it[0] } } catch (e: Exception) { "" })
            .put("adb", JSONObject().put("state", adbWord()).put("detail", adbDetail)
                .put("pair", Build.VERSION.SDK_INT >= 30 && manage?.canPair == true))   // Wireless debugging (Android 11+): the page offers to type its code
            .put("app", front.get(link?.takeIf { adbState == "ready" }?.let { l -> { c: String -> l.get().shell(c) } }).ifEmpty { JSONObject.NULL })   // the remote screen's "now open" line
            .put("volume", JSONObject().put("level", am.getStreamVolume(AudioManager.STREAM_MUSIC)).put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("muted", Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC)))
    }

    // ---------------------------------------------------------------- pairing
    @Throws(Exception::class)
    private fun pairStart(peer: String, lang: String): HttpServer.Response {
        pairOpen(peer, lang)?.let { return it }
        // with no ADB and no overlay right Android drops a service's screen without a word (an FPT Play, 2026-10-25): the page
        // then says to open TV Setup, whose own screen shows the code too (MainActivity.pairPoll). Out of the lock: a wait here
        // must not hold up connect() and pairCheck()
        var up = false
        for (i in 0 until 15) { if (PairActivity.showing || MainActivity.shown) { up = true; break }; Thread.sleep(100) }
        return HttpServer.Response.json(JSONObject().put("ok", true).put("seconds", 120).put("shown", up))
    }

    /** The code and its screen; an answer when there is nothing to wait for (too soon, locked, already up), else null. */
    @Synchronized @Throws(Exception::class)
    private fun pairOpen(peer: String, lang: String): HttpServer.Response? {
        val now = System.currentTimeMillis()
        if (now - lastPairStart in 0 until 3000) return HttpServer.Response.error(429, "wait a moment")
        // asking for code after code would give five guesses each: wrong guesses count across codes, and too many lock it
        if (now < lockedUntil && lockedUntil - now <= 15 * 60000L) return HttpServer.Response.error(429, Words.get(lang, "tv_server.locked_15"))
        // /api/pair/start needs no pass (it is how a pass is got), so anyone on the network can call it: never pile a second
        // code screen on the first, and after a few that nobody finished, wait longer and longer (30 s, 1, 2, 4, 5 min)
        if (pairingCode() != null && PairActivity.showing) return HttpServer.Response.json(JSONObject().put("ok", true).put("seconds", ((codeUntil - now) / 1000).toInt()))
        val wait = backoff.wait(peer, now)
        if (wait > 0) return HttpServer.Response.error(429, Words.get(lang, "tv_server.wait_s", "n" to wait / 1000 + 1))
        lastPairStart = now
        if (pairingCode() == null || codeUntil - now < 30000) {          // the code on the TV stays the same when asked again
            code = String.format("%06d", random.nextInt(1000000))
            codeUntil = now + 120000
            guesses = 0
        }
        // Since Android 10 a service may not open a screen by itself; the shell user (our ADB session) still may.
        val t = tv
        var shown = false
        if (t != null && t.ready()) {
            try { t.shell("am start -n $packageName/.PairActivity"); shown = true } catch (ignored: Exception) { }
        }
        if (!shown) {
            try { startActivity(Intent(this, PairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (ignored: Exception) { }
        }
        backoff.shown(peer, now)
        return null
    }

    // the body is read before taking the lock: a browser that sends it slowly must not hold up everyone else's pairing
    @Throws(Exception::class)
    private fun pairFinish(r: HttpServer.Request): HttpServer.Response = pairCheck(r, r.json().optString("code").replace(Regex("\\D"), ""))

    @Synchronized @Throws(Exception::class)
    private fun pairCheck(r: HttpServer.Request, got: String): HttpServer.Response {
        val want = pairingCode() ?: return HttpServer.Response.error(400, "expired")
        if (want != got) {
            if (++guesses >= 5) code = null
            if (++wrong >= 15) { wrong = 0; code = null; lockedUntil = System.currentTimeMillis() + 15 * 60000 }
            return HttpServer.Response.error(400, if (code == null) "expired" else "wrong")
        }
        code = null
        wrong = 0
        backoff.paired(r.peer)                                        // somebody really paired: the next one starts fresh
        val token = Paired.add(this, "web", Paired.deviceName(r.headers["user-agent"] ?: ""), r.peer, r.tls)
        val ok = ok()
        ok.headers["Set-Cookie"] = cookie(token, r.tls)
        return ok
    }

    /** A shared QR's link: its invite pairs this browser as a code would, once. */
    private fun pairInvite(r: HttpServer.Request): HttpServer.Response {
        val scope = takeInvite(r.json().optString("invite")) ?: return HttpServer.Response.error(400, "expired")
        val token = Paired.add(this, "web", Paired.deviceName(r.headers["user-agent"] ?: ""), r.peer, r.tls, scope)
        val ok = ok()
        ok.headers["Set-Cookie"] = cookie(token, r.tls)
        return ok
    }

    // ---------------------------------------------------------------- apps on the TV's home screen
    @Throws(Exception::class)
    private fun apps(): JSONArray {
        val pm = packageManager
        val out = JSONArray()
        val seen = HashSet<String>()
        for (category in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val pkg = ri.activityInfo.packageName
                if (!seen.add(pkg)) continue                          // TV Setup itself too: it has a screen of its own now
                out.put(JSONObject().put("pkg", pkg).put("keep", Setup.PROTECTED.matches(pkg)).put("meta", JSONObject().put("label", ri.loadLabel(pm).toString()).put("icon", true).put("banner", true)))
            }
        }
        // the apps turned off (they have no launcher entry then), so "Turn on" is always at hand
        @Suppress("DEPRECATION")
        for (ai in pm.getInstalledApplications(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (ai.enabled || !seen.add(ai.packageName)) continue
            out.put(JSONObject().put("pkg", ai.packageName).put("off", true).put("meta", JSONObject().put("label", ai.loadLabel(pm).toString()).put("icon", true).put("banner", true)))
        }
        return out
    }

    private fun icon(pkg: String): ByteArray? {
        icons[pkg]?.let { return it }
        try {
            val pm = packageManager
            var d: Drawable? = null
            try { d = pm.getApplicationBanner(pkg) } catch (ignored: Exception) { }   // TV apps carry a wide banner; fall back to the icon
            val banner = d != null
            val drawable = d ?: pm.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(if (banner) 320 else 144, if (banner) 180 else 144, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bmp.width, bmp.height)
            drawable.draw(Canvas(bmp))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            val png = out.toByteArray()
            if (icons.size < 200) icons[pkg] = png
            return png
        } catch (e: Exception) {
            return null
        }
    }
}
