package dev.tvremote.setup.health

import android.content.Context
import android.os.Build
import dev.tvremote.core.Scrub
import dev.tvremote.core.SupportAndroid
import dev.tvremote.core.SupportRelay
import dev.tvremote.core.WireLog
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.link.Paired
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Help for a TV nobody here can reach (an FPT Play behind its ISP's lock, 2026-10-25: AnyDesk, Tailscale, then a pairing code
 * read out over TeamViewer, one evening for four answers), through [SupportRelay] like the Phone and Computer apps. Opened on the
 * TV (Connection), or from a phone, a computer or this TV's web page (RulesReceiver "support", `/api/support/tv`). While support
 * looks, the owner may GET a few of this app's own read-only routes, run on its own server with a pass of its own listed among
 * the paired devices, plus [SupportDump]'s settings, own log and read-only commands (`support.py CODE sh "dumpsys package x"`),
 * so a new feature needs no new field here. Nothing is ever pressed or changed.
 */
object Support {
    const val MINUTES = SupportRelay.MINUTES
    private const val KIND = "support"
    // read-only answers worth having from afar; never the clipboard, files, the screen or anything that acts
    private val ROUTES = setOf("/api/about", "/api/state", "/api/diagnose", "/api/setup/version", "/api/setup/need", "/api/wirelog",
        "/api/apps", "/api/playing", "/api/caps", "/api/rules", "/api/paired", "/api/health", "/api/stats", "/api/tailscale")

    @Volatile private var relay: SupportRelay? = null
    @Volatile private var token = ""

    private fun relay(c: Context): SupportRelay = relay ?: synchronized(this) {
        relay ?: run {
            val app = c.applicationContext
            val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
            SupportRelay("setup", version, Build.MODEL, SupportAndroid.store(app), { report(app, version) }, { answer(app, it) }).apply {
                onOpen = { code -> forgetPass(app); token = Paired.add(app, KIND, "Hỗ trợ / Support $code", "relay", false) }   // one pass, not two
                onEnd = { forgetPass(app) }
            }.also { relay = it }
        }
    }

    fun open() = relay?.open() == true
    val code get() = relay?.code ?: ""
    val until get() = relay?.until ?: 0L
    /** Sends today has left after the last one (20 a day); -1 not known. */
    val left get() = relay?.left ?: -1

    /** The report as sent: what AdbReport reads without ADB, this app's state, its log. */
    fun report(c: Context, version: String): String = Scrub.blank(buildString {
        append("TV Setup ").append(version).append(" · adb ").append(RemoteService.adbState()).append(' ').append(RemoteService.adbDetail()).append('\n')
        append(AdbReport.facts(c, RemoteService.adbState(), RemoteService.adbDetail())).append('\n')
        append(SupportRelay.fit(SupportDump.prefs(c), 10_000, tail = false)).append('\n')
        append(SupportRelay.fit(SupportDump.logcat(), 14_000, tail = true)).append('\n')
        append(WireLog.all.report().takeLast(24_000))
    })

    /** Sends the report; the code to read out, or null. Blocks: off the main thread. */
    fun send(c: Context): String? = relay(c).send()

    /** Lets the owner read for [MINUTES]; [said] gets the code, or null, on its own thread. */
    fun start(c: Context, said: (String?) -> Unit) {
        val r = relay(c)
        Thread({ said(r.start()) }, "support-start").apply { isDaemon = true }.start()
    }

    /** At start: support open when TV Setup stopped (an update, a kill) goes on with the same code, else no pass is kept. */
    fun resume(c: Context) { if (!relay(c).resume()) forgetPass(c.applicationContext) }

    fun stop(c: Context) = relay(c).stop()

    fun state(c: Context) = relay(c).state()

    /**
     * For the phone, the computer and the web page (RulesReceiver "support", `/api/support/tv`): [act] "start", "stop" or "state";
     * `{open, code, min, left}`, or `{failed: "no_net" | "none_left" | why}`. Blocks up to 30 s on "start": off the main thread.
     */
    fun act(c: Context, act: String): JSONObject = try {
        when (act) {
            "start" -> {
                if (!RemoteService.isRunning()) try { RemoteService.start(c, true) } catch (e: Exception) { }   // its routes answer support
                val got = LinkedBlockingQueue<String>()
                start(c) { got.put(it ?: "") }
                if (got.poll(30, TimeUnit.SECONDS).isNullOrEmpty()) JSONObject().put("failed", if (left == 0) "none_left" else "no_net") else state(c)
            }
            "stop" -> { stop(c); state(c) }
            else -> state(c)
        }
    } catch (e: Exception) { JSONObject().put("failed", e.message ?: e.javaClass.simpleName) }

    private fun forgetPass(c: Context) { Paired.list(c).filter { it.kind == KIND }.forEach { Paired.forget(c, it.id) } }

    /** One GET on this app's own server, as a paired browser would, only [ROUTES]; or a [SupportDump] read. */
    fun answer(c: Context, path: String): Pair<Int, String> {
        val route = path.substringBefore('?')
        when (route) {                                                // no route of the server: read here (SupportDump)
            "/api/support/sh" -> return SupportDump.shell(java.net.URLDecoder.decode(path.substringAfter("c=", "").substringBefore('&'), "UTF-8"))
            "/api/support/prefs" -> return 200 to SupportDump.prefs(c)
            "/api/support/logcat" -> return 200 to SupportDump.logcat()
        }
        if (route !in ROUTES || ".." in path) return 403 to "not allowed for support: $route"
        return SupportRelay.get("http://127.0.0.1:${RemoteService.PORT}$path") { it.setRequestProperty("Cookie", RemoteService.SELF_COOKIE + "=" + token) }
    }
}
