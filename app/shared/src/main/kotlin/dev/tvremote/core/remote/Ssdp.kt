package dev.tvremote.core.remote
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Finding LG, Samsung and Roku TVs on the home network: they answer an SSDP search (UPnP's "who is there?") for their remote
 * services, which a port scan would only find slowly. The answers come back to this socket alone, so Android needs no
 * multicast lock. Android TVs are found by their ports as before (Discover.kt, the phone's scan).
 */
object Ssdp {
    /** The name an Android TV gives itself, from its Chromecast service (every Android TV has one). "" when it does not answer. */
    fun castName(ip: String, port: Int = 8008): String = try {
        dev.tvremote.core.Lan.connect(ip, port, 800).use { so ->
            so.soTimeout = 1500
            so.getOutputStream().write("GET /setup/eureka_info HTTP/1.1\r\nHost: $ip:$port\r\nConnection: close\r\n\r\n".toByteArray())
            val got = java.io.ByteArrayOutputStream()
            val buf = ByteArray(2048)
            var found = ""
            try {
                while (found.isEmpty() && got.size() < 20000) {       // the TV may keep the connection open: stop at the name
                    val n = so.getInputStream().read(buf)
                    if (n < 0) break
                    got.write(buf, 0, n)
                    found = Regex("\"name\"\\s*:\\s*\"([^\"]{1,60})\"").find(got.toString("UTF-8"))?.groupValues?.get(1) ?: ""
                }
            } catch (timeout: Exception) {                            // a read timeout keeps what was read so far
            }
            found
        }
    } catch (e: Exception) { "" }

    class Found(val ip: String, val kind: String, val name: String)

    private val SEARCH = listOf("urn:lge-com:service:webos-second-screen:1", "urn:samsung.com:device:RemoteControlReceiver:1", "roku:ecp", "urn:schemas-upnp-org:device:MediaRenderer:1")   // the last: Hisense VIDAA has no search of its own

    /** Every TV that answers within [ms], each once, with the name it gives itself; [each] as soon as one is known. */
    /** [bind]: puts the socket on the Wi-Fi itself (Android, with a VPN such as Tailscale taking the traffic). */
    fun find(ms: Int = 2500, bind: (DatagramSocket) -> Unit = {}, each: (Found) -> Unit = {}): List<Found> {
        val seen = LinkedHashMap<String, Found>()
        try {
            DatagramSocket().use { s ->
                bind(s)
                s.soTimeout = 300
                val to = InetAddress.getByName("239.255.255.250")
                repeat(2) {                                           // UDP: a second ask for the one that got lost
                    for (st in SEARCH) {
                        val m = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                        s.send(DatagramPacket(m, m.size, to, 1900))
                    }
                }
                val until = System.currentTimeMillis() + ms
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < until) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                    val ip = p.address.hostAddress ?: continue
                    if (seen.containsKey(ip)) continue
                    val head = String(p.data, 0, p.length, Charsets.UTF_8)
                    val kind = kind(head) ?: continue
                    if (kind == Remote.Kind.VIDAA && !named(head) && !listens(ip, 36669)) continue   // a media port alone: an Android box (QuickShare) was called a Hisense, its ADB never tried
                    val f = Found(ip, kind, name(ip, kind, location(head, ip)))
                    seen[ip] = f
                    each(f)
                }
            }
        } catch (ignored: Exception) {                                // no network, or multicast blocked: nothing found this way
        }
        return seen.values.toList()
    }

    /** LG, Samsung or Roku, from the answer's headers. */
    fun kind(head: String): String? = when {
        Regex("(?i)roku:ecp").containsMatchIn(head) -> Remote.Kind.ROKU   // first: a Roku TV's answer names its maker too
        Regex("(?i)lge-com|webos").containsMatchIn(head) -> Remote.Kind.LG
        Regex("(?i)samsung|tizen").containsMatchIn(head) -> Remote.Kind.SAMSUNG
        Regex("(?i)vidaa|:38400/|:18400/").containsMatchIn(head) -> Remote.Kind.VIDAA   // VIDAA's media renderer answers from these ports
        else -> null
    }

    /** The answer names VIDAA itself; the media ports alone (38400 / 18400) are also used by Android boxes' renderers. */
    fun named(head: String): Boolean = Regex("(?i)vidaa").containsMatchIn(head)

    private fun listens(ip: String, port: Int): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(ip, port), 700); true }
    } catch (e: Exception) { false }

    /** The name the person gave the TV: Samsung's REST description, Roku's device-info, else the UPnP description's friendlyName. */
    private fun name(ip: String, kind: String, location: String?): String {
        if (kind == Remote.Kind.ROKU) get("http://$ip:8060/query/device-info")?.let { t ->
            Roku.field(t, "user-device-name").ifEmpty { Roku.field(t, "friendly-device-name") }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (kind == Remote.Kind.SAMSUNG) get("http://$ip:8001/api/v2/")?.let { t ->
            try { JSONObject(t).optJSONObject("device")?.optString("name")?.takeIf { it.isNotEmpty() }?.let { return it.removePrefix("[TV] ") } } catch (ignored: Exception) { }
        }
        val xml = location?.let { get(it) } ?: return ""
        return Regex("<friendlyName>([^<]{1,80})</friendlyName>").find(xml)?.groupValues?.get(1)?.trim()?.removePrefix("[TV] ") ?: ""
    }

    /** The answer's description URL, only when it is http(s) on the device that answered: anyone on the Wi-Fi can answer. */
    internal fun location(head: String, ip: String): String? {
        val at = Regex("(?im)^location:\\s*(\\S+)").find(head)?.groupValues?.get(1) ?: return null
        val u = try { URL(at) } catch (e: Exception) { return null }
        return at.takeIf { (u.protocol == "http" || u.protocol == "https") && u.host.removePrefix("[").removeSuffix("]") == ip }
    }

    private fun get(at: String): String? = try {
        val c = dev.tvremote.core.Lan.open(URL(at)) as HttpURLConnection
        c.connectTimeout = 1200; c.readTimeout = 1500
        try { if (c.responseCode != 200) null else c.inputStream.use { atMost(it, 256 * 1024, 4000) } } finally { c.disconnect() }
    } catch (e: Exception) { null }

    /** At most [max] bytes within [ms]: a description is a few KB, and an endless one must not fill the memory or hold the scan. */
    internal fun atMost(input: java.io.InputStream, max: Int, ms: Long): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        val until = System.currentTimeMillis() + ms
        while (out.size() < max && System.currentTimeMillis() < until) {
            val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
