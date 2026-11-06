package dev.tvremote.core.ontv

import dev.tvremote.core.B64
import dev.tvremote.core.adb.Adb
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest

/**
 * Who may use ADB on the TV, and who is connected now: the "Who may use ADB" card. Both come from places the shell user may read
 * (`/data/misc/adb` is not one of them), and each answers null when the TV will not say, which the page shows as "can't check",
 * never as "nobody". The idea (and the revoke call's pitfalls) is tellyscope's (GPL-3); this is written from the TV's own output.
 */
object AdbAccess {
    /** A key the TV allows: its MD5 as the TV's own question shows it, the comment adb keeps after the key, and when the TV last saw it (epoch ms). */
    class Key(val fingerprint: String, val name: String?, val lastSeen: Long?)

    /** One open connection to adbd, by where it comes from. Nothing ties it to a key. */
    class Client(val address: String, val port: Int) {
        val loopback get() = address == "127.0.0.1" || address == "::1"
    }

    const val CLASSIC_PORT = dev.tvremote.core.adb.AdbPort.CLASSIC
    private const val SEP = "=====TVR====="
    private const val MARK = "user_keys="

    /**
     * IAdbManager.clearDebuggingKeys is the 3rd method of the aidl from Android 11 (API 30) to 16 (36), read from each AOSP branch.
     * Outside that range the number may be another method there: never called, Developer options are opened instead.
     */
    val CLEAR_KEYS = 30..36

    /** adb writes a key as base64 of a struct that begins with its word count, 64, so every key starts with `QAAAA`; then ` user@host`. */
    private val KEY = Regex("(QAAAA[A-Za-z0-9+/]+={0,2})(?:[ \\t]+([^\\r\\n\"]*))?")
    private val KEY_TAG = Regex("<adbKey\\b[^>]*>")
    private val ATTR = Regex("([\\w-]+)=\"([^\"]*)\"")
    /** Where the next field of `dumpsys adb` starts, so `user_keys` stops before it. */
    private val NEXT_FIELD = Regex("^\\s*(?:[a-z_]+=|[{}])", RegexOption.MULTILINE)

    /** The MD5 the TV's "Allow USB debugging?" question shows for a base64 adb key; null if it is not one. */
    fun fingerprint(base64: String): String? = try {
        MessageDigest.getInstance("MD5").digest(B64.decode(base64)).joinToString(":") { String.format("%02X", it) }
    } catch (e: Exception) { null }

    /**
     * The allowed keys from `dumpsys adb`: the file's own (`user_keys`), then any only the key store (`<adbKey key= lastConnection=>`)
     * knows, each with the store's last-connection time when it has one. Null when the TV does not print the debugging manager at all
     * (Android 10 and older, or a build that hides it). A manager with no `user_keys` is an empty list: the file is gone, as after a revoke.
     */
    fun keys(dumpsys: String): List<Key>? {
        if ("debugging_manager" !in dumpsys) return null
        val stored = KEY_TAG.findAll(dumpsys).mapNotNull { tag ->
            val a = ATTR.findAll(tag.value).associate { it.groupValues[1] to xml(it.groupValues[2]) }
            a["key"]?.trim()?.let { it to a["lastConnection"]?.toLongOrNull()?.takeIf { t -> t > 0 } }
        }.toList()
        val listed = dumpsys.indexOf(MARK).takeIf { it >= 0 }?.let { start ->
            val from = start + MARK.length
            val end = NEXT_FIELD.find(dumpsys, from)?.range?.first ?: dumpsys.length
            dumpsys.substring(from, end).lines().map { it.trim() }
        }.orEmpty()
        val stamps = stored.mapNotNull { (line, at) -> KEY.matchEntire(line)?.let { it.groupValues[1] to at } }.toMap()
        return (listed + stored.map { it.first }).mapNotNull { line ->
            val m = KEY.matchEntire(line) ?: return@mapNotNull null
            val fp = fingerprint(m.groupValues[1]) ?: return@mapNotNull null
            Key(fp, m.groupValues[2].trim().takeIf { it.isNotEmpty() }, stamps[m.groupValues[1]])
        }.distinctBy { it.fingerprint }
    }

    /**
     * ESTABLISHED connections whose local port is one of [ports], from `/proc/net/tcp6` and `/proc/net/tcp` together (an IPv4 client of an
     * IPv6 socket shows as `::ffff:a.b.c.d` and comes back as plain IPv4). Null when neither table was there to read.
     */
    fun clients(proc: String, ports: Set<Int>): List<Client>? {
        if ("local_address" !in proc) return null
        return proc.lines().mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 4 || !c[0].endsWith(":") || c[3] != "01") return@mapNotNull null   // 01 = ESTABLISHED
            val local = endpoint(c[1]) ?: return@mapNotNull null
            val remote = endpoint(c[2]) ?: return@mapNotNull null
            if (local.second !in ports) null else Client(remote.first, remote.second)
        }.distinctBy { it.address + ":" + it.port }
    }

    private fun endpoint(s: String): Pair<String, Int>? {
        val p = s.split(':').takeIf { it.size == 2 } ?: return null
        val port = p[1].toIntOrNull(16) ?: return null
        return (address(p[0]) ?: return null) to port
    }

    /** A kernel socket address: 8 or 32 hex digits, in 32-bit words, each in the CPU's little-endian order. */
    fun address(hex: String): String? {
        if (hex.length != 8 && hex.length != 32) return null
        val bytes = try { hex.chunked(8).flatMap { w -> w.chunked(2).map { it.toInt(16).toByte() }.reversed() }.toByteArray() } catch (e: NumberFormatException) { return null }
        val mapped = bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        val a = InetAddress.getByAddress(if (mapped) bytes.copyOfRange(12, 16) else bytes).hostAddress ?: return null
        return if (a == "0:0:0:0:0:0:0:1") "::1" else a
    }

    /** `service call` answers `Parcel(00000000 ...)` (old) or `Parcel(\n  0x00000000: 00000000 ...)` (newer) when the call went through; empty when our link was cut. */
    internal fun revoked(out: String) = out.isBlank() || Regex("Parcel\\(\\s*(?:0x0+:\\s*)?0{8}").containsMatchIn(out)

    /** What the card shows: one shell call (about 100 ms), answered as JSON. [ours] is this client's key; it is marked in the list. */
    @Throws(IOException::class)
    fun snapshot(adb: Adb): JSONObject = snapshot(adb.shell(READ, 20000), ours = adb.fingerprint())

    internal val READ = "dumpsys adb 2>&1; echo $SEP; getprop service.adb.tls.port; getprop service.adb.tcp.port; echo $SEP; " +
        "cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null; echo $SEP; getprop ro.build.version.sdk"

    internal fun snapshot(out: String, ours: String): JSONObject {
        val part = out.split(SEP)
        val keys = part.getOrNull(0)?.let(::keys)
        val props = part.getOrNull(1).orEmpty().lines().mapNotNull { it.trim().toIntOrNull()?.takeIf { p -> p > 0 } }
        val clients = part.getOrNull(2)?.let { clients(it, setOf(CLASSIC_PORT) + props) }
        val sdk = part.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
        val o = JSONObject().put("ours", ours).put("sdk", sdk).put("canRevoke", sdk in CLEAR_KEYS)
        o.put("keys", keys?.let { l -> JSONArray().apply { l.forEach { k ->
            put(JSONObject().put("fp", k.fingerprint).put("name", k.name ?: JSONObject.NULL).put("seen", k.lastSeen ?: JSONObject.NULL).put("ours", k.fingerprint == ours)) } } } ?: JSONObject.NULL)
        o.put("clients", clients?.let { l -> JSONArray().apply { l.forEach { put(JSONObject().put("address", it.address).put("port", it.port).put("loopback", it.loopback)) } } } ?: JSONObject.NULL)
        return o
    }

    /**
     * The TV's own "Revoke USB debugging authorisations" (binder call). Every allowed key goes, this client's and TV Setup's own link too:
     * the next connection of each asks "Allow USB debugging?" again. "revoked", "settings" (Android 10 and older, or a TV that refused: Developer
     * options were opened for the person to do it) or "failed".
     */
    @Throws(IOException::class)
    fun revoke(adb: Adb): String {
        val sdk = adb.shell("getprop ro.build.version.sdk", 15000).trim().toIntOrNull()
        if (sdk != null && sdk in CLEAR_KEYS) {
            // the TV cuts our own link while it removes our key, so the reply is often empty or never comes: that is success too
            val r = try { adb.shell("service call adb 3 2>&1", 15000) } catch (e: IOException) { return "revoked" }
            if (revoked(r)) return "revoked"
        }
        val o = adb.shell("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS 2>&1", 15000)
        return if ("Error" in o) "failed" else "settings"
    }

    private val FP = Regex("(?:[0-9A-F]{2}:){15}[0-9A-F]{2}")

    /**
     * Takes one allowed key away (IAdbManager.unpairDevice, the same call as Wireless debugging's "Forget"): it finds the key by this MD5
     * in the key store, cuts that device's link and drops the key, so its next connection asks "Allow USB debugging?" again. Checked by
     * reading the list back: "revoked" when the key is gone, "failed" when it stays (a TV that numbers the call differently does nothing).
     * Our own key cuts our link mid-call: that is success too.
     */
    @Throws(IOException::class)
    fun revokeOne(adb: Adb, fp: String): String {
        if (!FP.matches(fp)) return "failed"
        val sdk = adb.shell("getprop ro.build.version.sdk", 15000).trim().toIntOrNull()
        if (sdk == null || sdk !in CLEAR_KEYS) return "failed"
        try { adb.shell("service call adb 7 s16 '$fp' 2>&1", 15000) } catch (e: IOException) { return "revoked" }
        val left = try { keys(adb.shell("dumpsys adb 2>&1", 15000)) } catch (e: IOException) { return "revoked" }
        return if (left != null && left.none { it.fingerprint == fp }) "revoked" else "failed"
    }

    private fun xml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
