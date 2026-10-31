package dev.tvremote.desktop

import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.remote.Atv
import dev.tvremote.core.remote.CodePairing
import dev.tvremote.core.remote.Vidaa
import dev.tvremote.core.picture.Feeds
import dev.tvremote.core.manage.Manage
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.ontv.RemoteLabels
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.picture.Tv
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Everything bound to the TV being controlled: the remote-protocol link (keys, volume, power state
 * without ADB), its ADB link and what is managed over it, the picture and sound for the page, and a scrcpy session for touch
 * and typing while nobody watches. Picking another TV swaps the whole thing. [kind]: an LG or Samsung TV has only its remote
 * link ([Remote]): no ADB, nothing managed, no picture.
 */
class Session(val host: String, private val keys: File, cache: File, helper: () -> ByteArray, private val jar: () -> ByteArray, val kind: String = Remote.Kind.ANDROID) {
    private val native = kind != Remote.Kind.ANDROID
    @Volatile var atv: Remote? = null
        private set
    @Volatile var paired: Boolean? = null                             // null: not known yet
        private set
    @Volatile var error = if (host.isEmpty()) "no TV selected" else ""
        private set
    @Volatile private var closed = false
    @Volatile private var dialGen = 0
    @Volatile private var pairer: CodePairing? = null
    @Volatile private var dialing: Remote? = null                     // LG / Samsung: the TV may be asking the person about us

    val link: AdbLink? = if (host.isEmpty() || native) null else AdbLink(keys, host)
    val front = dev.tvremote.core.ontv.Foreground()                  // the app on screen over ADB, when the remote link does not say
    val labels: RemoteLabels? = link?.let { RemoteLabels(it, helper, File(cache, "meta/" + host.replace(Regex("[^\\w.-]"), "_"))) }
    val manage: Manage? = link?.let { Manage(it, labels, cache, helper).apply { canPair = true } }
    val feeds = Feeds()

    private val timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "session-timers").apply { isDaemon = true } }
    val held = HashMap<Int, ScheduledFuture<*>>()                     // keys held down: let go after 8 s if the page vanishes

    init { if (host.isNotEmpty()) dial() }

    val available: Boolean get() = atv?.ready() == true

    /** The remote protocol's link, with retries: a TV that is off or drops us is dialled again every 5 s. */
    private fun dial() {
        val gen = ++dialGen
        Thread({
            while (!closed && gen == dialGen) {
                val a = Remote.of(kind, keys, host)
                dialing = a
                try {
                    a.connect { if (!closed && gen == dialGen) later(1000) { if (gen == dialGen) dial() } }
                    if (closed || gen != dialGen) { a.close(); return@Thread }
                    atv?.close()
                    atv = a
                    paired = true
                    error = ""
                    return@Thread
                } catch (e: Atv.NotPaired) {
                    a.close()
                    paired = false
                    error = "not paired"
                    return@Thread
                } catch (e: Remote.Refused) {                         // LG / Samsung: the person said no; asked again only on Pair
                    a.close()
                    paired = false
                    error = "refused"
                    return@Thread
                } catch (e: IOException) {
                    a.close()
                    if (e.message == Vidaa.NO_CERT) { paired = false; error = Vidaa.NO_CERT; return@Thread }   // waits for the certificate (/api/vidaa/cert)
                    if (native && e.message == "nobody accepted on the TV") { paired = false; error = "not accepted"; return@Thread }   // not again by itself: the question would keep popping up
                    error = "cannot connect: " + (e.message ?: e.javaClass.simpleName)
                    try { Thread.sleep(5000) } catch (stop: InterruptedException) { return@Thread }
                } catch (e: Exception) {
                    a.close()
                    error = "cannot connect: " + (e.message ?: e.javaClass.simpleName)
                    try { Thread.sleep(5000) } catch (stop: InterruptedException) { return@Thread }
                } finally {
                    if (dialing === a) dialing = null
                }
            }
        }, "atv-dial").apply { isDaemon = true }.start()
    }

    /** What the page's status line shows. */
    fun state(): JSONObject {
        val a = atv
        val o = JSONObject().put("host", host).put("connected", available).put("paired", paired ?: JSONObject.NULL).put("error", error)
            .put("is_on", a?.isOn ?: JSONObject.NULL).put("app", a?.app?.ifEmpty { null } ?: JSONObject.NULL)
        a?.let { appLabel(it) }?.let { o.put("app_label", it) }       // an LG / Roku / Samsung says an id ("12"): its name for the page
        val v = a?.volume
        o.put("volume", if (v == null) JSONObject.NULL else JSONObject().put("level", v.first).put("max", v.second).put("muted", v.third))
        o.put("device", if (a == null || a.model.isEmpty()) JSONObject.NULL else JSONObject().put("manufacturer", a.vendor).put("model", a.model).put("sw_version", a.version))
        val k = Remote.Kind.json(kind)
        for (n in k.keys()) o.put(n, k.get(n))
        a?.keys?.let { have -> o.put("keys", org.json.JSONArray(Scenes.KEYS.filter { it.value in have }.keys.sorted())) }   // this one TV's (a Roku player has no volume)
        o.put("asking", dialing?.asking == true)
        return o
    }

    @Volatile private var appNames: Map<String, String> = emptyMap()
    @Volatile private var namesAt = 0L
    private val namesBusy = java.util.concurrent.atomic.AtomicBoolean()

    /** The name of the app [a] says is open, from its list of apps, read in the background (again at most once a minute for one it does not know). */
    private fun appLabel(a: Remote): String? {
        val id = a.app
        if (id.isEmpty() || kind == Remote.Kind.ANDROID) return null       // Android TV says a package: the page names it
        appNames[id]?.let { return it }
        if (System.currentTimeMillis() - namesAt > 60000 && namesBusy.compareAndSet(false, true)) Thread({
            try { appNames = a.apps().associate { it.id to it.label } } catch (ignored: Exception) { }
            namesAt = System.currentTimeMillis(); namesBusy.set(false)
        }, "app-names").apply { isDaemon = true }.start()
        return null
    }

    /** The link, or why there is none, in the words the page shows. */
    fun need(): Remote = atv?.takeIf { it.ready() } ?: throw Unavailable(error.ifEmpty { "TV not connected" })

    class Unavailable(message: String) : Exception(message)

    // ---------------------------------------------------------------- pairing (port 6467)
    /** Makes the TV show a 6-character code. Nobody typing it within 3 minutes: back to normal dialling. */
    fun pairStart() {
        if (native && kind != Remote.Kind.VIDAA) { dialGen++; atv?.close(); atv = null; paired = null; error = ""; dial(); return }   // LG / Samsung: the TV asks again
        dialGen++
        atv?.close(); atv = null
        pairer?.close()
        val p = Remote.of(kind, keys, host) as CodePairing
        try {
            p.startPairing()
        } catch (e: Exception) {
            p.close(); dial()                                         // the TV is unreachable now: keep trying the normal way
            throw e
        }
        pairer = p
        later(180_000) { if (pairer === p) { pairer = null; p.close(); dial() } }
    }

    /** Dials again, after something changed (Hisense's certificate imported). */
    fun redial() { dialGen++; atv?.close(); atv = null; paired = null; error = ""; dial() }

    /** IllegalArgumentException: wrong code (the TV drops the attempt). */
    fun pairFinish(code: String) {
        val p = pairer ?: throw IllegalStateException("pairing was not started")
        try {
            p.finishPairing(code)
            error = ""
        } finally {
            pairer = null; p.close()
            dial()                                                    // a wrong code too: the TV dropped that attempt, Pair starts a new one
        }
    }

    // ---------------------------------------------------------------- touch and typing (scrcpy's control socket)
    @Volatile private var ctl: Tv? = null
    @Volatile private var ctlUsed = 0L

    /** The session that carries the picture when someone watches; else one of its own, kept for a minute after the last use. */
    @Throws(IOException::class)
    fun control(): Tv {
        feeds.viewer?.let { if (it.ready()) return it }
        ctlUsed = System.currentTimeMillis()
        val t = synchronized(this) {
            ctl ?: Tv(keys, jar, host, null, 0, 0, false, object : Tv.Listener {
                override fun onStatus(key: String, detail: String) { if (key == "closed" || key == "error") synchronized(this@Session) { ctl = null } }
                override fun onVideoSize(w: Int, h: Int) { }
                override fun onNetwork(mac: String, lan: String, tailscale: String) { }
            }).also { fresh ->
                if (closed) throw IOException("TV changed")
                ctl = fresh
                timers.schedule(object : Runnable {
                    override fun run() {
                        if (ctl !== fresh) return
                        if (System.currentTimeMillis() - ctlUsed > 60000 && feeds.viewer == null) { synchronized(this@Session) { if (ctl === fresh) ctl = null }; fresh.stop() }
                        else timers.schedule(this, 15, TimeUnit.SECONDS)
                    }
                }, 15, TimeUnit.SECONDS)
            }
        }
        for (i in 0 until 100) {                                      // the first one starts scrcpy-server on the TV: a few seconds
            if (t.ready()) return t
            if (ctl !== t) break
            Thread.sleep(100)
        }
        throw IOException("could not reach the TV's touch input")
    }

    /** Null once the session is closed (another TV was picked while a request for this one was under way). */
    fun later(ms: Long, what: () -> Unit): ScheduledFuture<*>? = try { timers.schedule(what, ms, TimeUnit.MILLISECONDS) } catch (e: java.util.concurrent.RejectedExecutionException) { null }

    fun close() {
        closed = true
        dialGen++
        feeds.stop()
        ctl?.stop()
        atv?.close()
        dialing?.close()
        pairer?.close()
        link?.close()
        timers.shutdownNow()
    }
}
