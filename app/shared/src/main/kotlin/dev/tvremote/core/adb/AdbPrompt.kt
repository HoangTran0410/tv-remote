package dev.tvremote.core.adb
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import dev.tvremote.core.SelfSigned
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.WireLog

/**
 * The TV's "Allow USB debugging?" question, answered from an ADB session that the TV already trusts (TV Setup's own, or
 * the phone's or computer's). Only ever for a device the person already trusts: one paired with TV Setup (its code is shown
 * on the TV, so someone was there) or one with ADB already. And only for the key the question shows: the answer is given
 * after its fingerprint matches the one asked for, so a stranger asking at the same moment is never let in.
 */
object AdbPrompt {
    /** What the question on screen shows, and where its parts are. */
    class Seen(val fingerprint: String, val nodes: List<Node>) {
        val always get() = nodes.firstOrNull { it.id.endsWith("alwaysUse") || it.cls.endsWith("CheckBox") }
        val allow get() = nodes.firstOrNull { it.id == "android:id/button1" }
        val cancel get() = nodes.firstOrNull { it.id == "android:id/button2" }
        val focused get() = nodes.firstOrNull { it.focused }
    }

    /** The TV's shell, as Adb.shell: (command, quiet ms) -> output. A pretend TV in the tests. */
    fun interface Sh { fun shell(cmd: String, quietMs: Long): String }

    class Node(val id: String, val cls: String, val checked: Boolean, val focused: Boolean, val x: Int, val y: Int)

    private val FP = Regex("(?:[0-9A-F]{2}:){15}[0-9A-F]{2}")
    private const val DUMP = "/data/local/tmp/tvr-ui.xml"

    /** The question if it is on screen now, null otherwise. */
    @Throws(IOException::class)
    fun read(adb: Adb): Seen? = read(Sh(adb::shell))

    internal fun read(adb: Sh): Seen? {
        if (!adb.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedWindow'", 15000).contains("UsbDebuggingActivity")) return null
        for (attempt in 0..2) {                                       // uiautomator sometimes finds the screen busy: try again
            val xml = adb.shell("uiautomator dump $DUMP >/dev/null 2>&1; cat $DUMP; rm -f $DUMP", 30000)
            val nodes = ArrayList<Node>()
            var fp = ""
            for (m in Regex("<node ([^>]*)").findAll(xml)) {
                val a = Regex("([\\w-]+)=\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
                FP.find(a["text"] ?: "")?.let { fp = it.value }
                val b = Regex("\\d+").findAll(a["bounds"] ?: "").map { it.value.toInt() }.toList()
                if (b.size == 4) nodes.add(Node(a["resource-id"] ?: "", a["class"] ?: "", a["checked"] == "true", a["focused"] == "true", (b[0] + b[2]) / 2, (b[1] + b[3]) / 2))
            }
            if (fp.isNotEmpty()) return Seen(fp, nodes)
            Thread.sleep(600)
        }
        return null
    }

    /**
     * Waits up to [waitMs] for the question about [fingerprint] and answers it with "Always allow". With [skipOthers], questions
     * about other keys that come first (often left over from connections long gone; the TV shows one at a time) are declined,
     * which never lets anyone in; their device just asks again. Returns allowed, none (no question about it came) or
     * other:<fingerprint> (another key's question is on screen and was left alone).
     */
    @Throws(IOException::class)
    fun allow(adb: Adb, fingerprint: String, waitMs: Long, skipOthers: Boolean, always: Boolean = true): String = allow(Sh(adb::shell), fingerprint, waitMs, skipOthers, always)

    /** [always]: the tick-box is left as the person chose (ticked: the key is remembered; unticked: this time only). */
    internal fun allow(adb: Sh, fingerprint: String, waitMs: Long, skipOthers: Boolean, always: Boolean = true, nap: (Long) -> Unit = Thread::sleep, skippedOne: () -> Unit = {}): String {
        val want = fingerprint.uppercase()
        val end = System.currentTimeMillis() + waitMs
        var skipped = 0
        while (true) {
            val p = read(adb)
            if (p == null) {
                if (System.currentTimeMillis() > end) return "none"
                nap(800)
                continue
            }
            if (p.fingerprint != want) {
                if (!skipOthers || skipped >= 6) return "other:" + p.fingerprint
                press(adb, p, { it.cancel }, nap)
                skipped++
                skippedOne()
                nap(1200)
                continue
            }
            if (p.always?.checked.let { it != null && it != always }) press(adb, p, { it.always }, nap)
            // Allow is pressed only on the question read just now, and only if it is still about this key: when it went away
            // meanwhile and the TV put another key's question up (it queues them), the top of the loop treats that one as such
            val now = read(adb)
            if (now == null || now.fingerprint != want) continue
            if (now.always?.checked.let { it != null && it != always }) return "failed"   // never answer with the tick-box other than chosen
            press(adb, now, { it.allow }, nap)
            nap(900)
            val after = read(adb)
            return if (after == null || after.fingerprint != want) "allowed" else "failed"
        }
    }

    /** Declines the question about [fingerprint] if it is the one on screen. */
    @Throws(IOException::class)
    fun deny(adb: Adb, fingerprint: String): Boolean = deny(Sh(adb::shell), fingerprint)

    internal fun deny(adb: Sh, fingerprint: String): Boolean {
        val p = read(adb) ?: return false
        if (p.fingerprint != fingerprint.uppercase()) return false
        press(adb, p, { it.cancel })
        return true
    }

    /**
     * Once logged in: closes the copies of our own question still queued on the TV. Android 12 and older keep every question
     * asked (each time we asked again, each part of the app) and show them one after another, even after "Always allow";
     * Android 13+ drops a question while one is up. Ours are known by the fingerprint, and Cancel on a copy changes nothing:
     * the key is trusted already. Stops after a few seconds with none.
     */
    internal fun leftovers(adb: Sh, fingerprint: String, nap: (Long) -> Unit = Thread::sleep): Int {
        var closed = 0; var none = 0
        while (none < 2 && closed < 15) {
            val p = read(adb)
            val c = p?.cancel
            if (p != null && c != null && p.fingerprint == fingerprint.uppercase()) {
                adb.shell("input tap ${c.x} ${c.y}", 120000)              // a tap only: the next copy looks the same, so [press]'s check would misread it
                closed++; none = 0; nap(1500)
            }
            else { none++; nap(2500) }
        }
        if (closed > 0) WireLog.all.add("·", "closed $closed leftover copies of our own question")
        return closed
    }

    fun leftovers(adb: Adb, fingerprint: String): Int = leftovers(Sh(adb::shell), fingerprint)

    /** A tap where the part is; if the TV ignores taps, the arrow keys to it and OK. Then checks that something changed. */
    private fun press(adb: Sh, p: Seen, part: (Seen) -> Node?, nap: (Long) -> Unit = Thread::sleep) {
        val n = part(p) ?: throw IOException("the question on the TV looks different than expected")
        adb.shell("input tap ${n.x} ${n.y}", 120000)
        nap(500)
        val after = read(adb)
        if (after == null || after.fingerprint != p.fingerprint || changed(part(after), n)) return
        var s: Seen = after
        for (i in 0 until 8) {                                        // keys: move the focus to the part, then OK
            val t = part(s) ?: return
            val f = s.focused
            if (f != null && f.x == t.x && f.y == t.y) { adb.shell("input keyevent 23", 120000); return }
            val key = when {
                f == null -> 20
                t.y > f.y + 10 -> 20
                t.y < f.y - 10 -> 19
                t.x > f.x -> 22
                else -> 21
            }
            adb.shell("input keyevent $key", 120000)
            s = read(adb) ?: return
            if (s.fingerprint != p.fingerprint) return                // another key's question now: never a key press on it
        }
    }

    private fun changed(now: Node?, before: Node): Boolean = now == null || now.checked != before.checked

    // ---------------------------------------------------------------- asking TV Setup, from a device it has paired with

    /**
     * A TV Setup pass for this device, handed out over ADB only (a broadcast only the shell may send). Null without TV Setup.
     * [app] ("phone" / "computer") is what the TV lists the device as. A TV Setup with HTTPS also sends the fingerprint of
     * its certificate, and the answer is "pass:fingerprint": [ask] then pins it, over the one channel that is already trusted;
     * the pass is then asked for as a TLS one (`tls`), which the TV never takes over plain HTTP.
     */
    @Throws(IOException::class)
    fun token(adb: Adb, app: String = ""): String? {
        val to = "am broadcast -n dev.tvremote.setup/.TrustReceiver" + (if (app.matches(Regex("[a-z]+"))) " --es app $app" else "")
        val first = adb.shell("$to --es want fp 2>&1", 15000)
        val fp = Regex("data=\"([0-9a-f]{64})\"").find(first)?.groupValues?.get(1)
        if (fp == null) return Regex("data=\"([0-9a-f]{48})\"").find(first)?.groupValues?.get(1)   // an older TV Setup: it made the pass already
        val pass = Regex("data=\"([0-9a-f]{48})\"").find(adb.shell("$to --es tls 1 2>&1", 15000))?.groupValues?.get(1) ?: return null
        return "$pass:$fp"
    }

    /**
     * Just allowed on this TV: answers TV Setup's own "Allow USB debugging?" too. TV Setup asks for its own key (it talks to
     * the TV over ADB as well) and cannot press Allow for itself before it has ADB. It says which key is its own over the
     * shell-only broadcast, and whether it is asking (or waiting to); only a question about exactly that key is answered.
     * An older TV Setup hands out a pass instead, which matches nothing here.
     */
    @Throws(IOException::class)
    fun helpSetup(adb: Adb) {
        val out = adb.shell("am broadcast -n dev.tvremote.setup/.TrustReceiver --es want adbkey 2>&1", 15000)
        val m = Regex("data=\"([0-9A-F]{2}(?::[0-9A-F]{2}){15}) (hold|ask)\"").find(out) ?: return
        val fp = m.groupValues[1]
        if (fp == adb.fingerprint()) return                           // TV Setup itself, on its own TV
        // a question about another key in front of it (a dead connection's, left on the screen) is declined, never allowed;
        // the TV dropped TV Setup's question meanwhile, so it is told to ask again
        val poke = { try { adb.shell("am broadcast -n dev.tvremote.setup/.TrustReceiver --es want adbkey", 15000) } catch (ignored: Exception) { } ; Unit }
        WireLog.all.add("·", "adb answering TV Setup's question: " + allow(Sh(adb::shell), fp, 40000, true, skippedOne = poke))
    }

    /** Trusts one certificate only: the one whose SHA-256 is [fingerprint]. Names do not matter, the TV has none to check. */
    private class Pin(private val fingerprint: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no clients")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val got = SelfSigned.fingerprint(chain[0].encoded)
            if (!MessageDigest.isEqual(got.toByteArray(), fingerprint.toByteArray())) throw CertificateException("not the TV's certificate")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * A request to TV Setup's API on [host] ([path] with its query) with this device's [pass]. A pass with a fingerprint is only
     * ever sent over TLS to that certificate; plain HTTP is for a TV Setup too old to have it. Also the picture's relay (Tv.kt).
     */
    /** One context per pinned certificate, so a connection after the first resumes its TLS session (the relay's touches, Tv.kt) */
    private val pinned = java.util.concurrent.ConcurrentHashMap<String, javax.net.ssl.SSLSocketFactory>()

    /** TV Setup's plain-HTTP port; a test moves it to a free one (8765 is the Computer app's on a developer's machine) */
    @Volatile internal var plainPort = 8765

    fun openSetup(host: String, pass: String, path: String): HttpURLConnection {
        val token = pass.substringBefore(':')
        val pin = pass.substringAfter(':', "")
        val c = dev.tvremote.core.Lan.open(if (pin.isEmpty()) URL("http://$host:$plainPort$path") else URL("https://$host:8766$path")) as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = pinned.getOrPut(pin) { SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(Pin(pin)), null) }.socketFactory }
            c.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        c.connectTimeout = 4000
        c.setRequestProperty("Cookie", (if (pin.isEmpty()) "tvr=" else "tvrs=") + token)
        return c
    }

    /** Asks TV Setup on [host] to answer the TV's question about [fingerprint]. allowed, none, other:…, forgotten (pass no longer valid) or an error. */
    fun ask(host: String, pass: String, fingerprint: String, waitS: Int): String {
        return try {
            val c = openSetup(host, pass, "/api/adb/allow")
            c.readTimeout = (waitS + 30) * 1000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("fingerprint", fingerprint).put("wait", waitS).put("others", true).toString().toByteArray()) }
            when (c.responseCode) {
                200 -> JSONObject(c.inputStream.bufferedReader().readText()).optString("result")
                401, 403 -> "forgotten"
                else -> "error"
            }
        } catch (e: javax.net.ssl.SSLException) {
            "error"                                                   // pinned and it did not match: never fall back to plain HTTP
        } catch (e: Exception) {
            "unreachable"
        }
    }
}
