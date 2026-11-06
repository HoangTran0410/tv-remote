package dev.tvremote.core

import dev.tvremote.core.adb.Mdns
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.remote.Ssdp
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * "Why can't I reach the TV?", answered in about three seconds with ONE conclusion and what to do about it, in place of the
 * raw error. What it looks at, all at once: whether this device is on a network that can hold the TV, which of the TV's ports
 * answer (and whether a closed one was *refused*, which proves the TV is up, or stayed silent), whether a VPN is up, what the
 * app's own ADB link says; only when nothing answers it looks around for the same TV under a new address (SSDP / mDNS).
 * [decide] is the table of conclusions, free of any network so a test can walk it; [Real] is the network.
 */
object Diagnose {
    /** OPEN: connected. REFUSED: the TV's network card answered "no one listens here" (it is up). SILENT: nothing came back. */
    enum class Port { OPEN, REFUSED, SILENT }

    /** This device against the TV's address. NA: nothing to check (a name, a loopback, a public address). */
    enum class Net { OK, NONE, OTHER, TAILSCALE_OFF, NA }

    class Found(val ip: String, val name: String)

    interface Probe {
        fun port(host: String, port: Int, ms: Int): Port
        fun net(host: String): Net
        /** A VPN is carrying this device's traffic. */
        fun vpn(): Boolean
        /** What this app's own ADB link to [host] says (AdbLink.state(), "waiting" while the TV asks), null with no link to it. */
        fun adb(host: String): String?
        /** The TV called [name] of this [kind] under an address other than [not], null when none or more than one. */
        fun find(kind: String, name: String, not: String): Found?
        /** The TV announces Wireless debugging (`_adb-tls-connect._tcp` / `_adb-tls-pairing._tcp`) while 5555 is closed; false where this app cannot pair. */
        fun wireless(host: String): Boolean = false
        /** What the TV calls itself on the network (its Chromecast name), to tell a TCL; "" when it does not say. */
        fun maker(host: String): String = ""
    }

    /** What was seen; [moved] only once the search for a new address was made. */
    class Facts(val kind: String, val ip: String, val ports: Map<Int, Port>, val net: Net, val vpn: Boolean, val adb: String?,
                val tls: Boolean = false, val moved: Found? = null, val wireless: Boolean = false, val brand: String = "") {
        val open get() = ports.filterValues { it == Port.OPEN }.keys
        val alive get() = ports.values.any { it != Port.SILENT }
        val home get() = Lan.home(ip) != null
    }

    class Check(val id: String, val ok: Boolean?, val note: String)
    class Result(val code: String, val ip: String, val kind: String, val found: Found?, val checks: List<Check>, val ms: Long, val brand: String = "")

    const val CLOCK = "clock"                  // the TV answers but its date is wrong: TLS refuses
    const val NO_WIFI = "no_wifi"              // this device is on no local network
    const val OTHER_NET = "other_net"          // ... or on another one than the TV's
    const val TAILSCALE = "tailscale"          // a 100.x address and this device's Tailscale is off
    const val ALLOW = "allow"                  // ADB answers and the TV is asking "Allow USB debugging?"
    const val WIRELESS = "wireless"            // no ADB on 5555, but the TV announces Wireless debugging: pair with its code
    const val ADB_OFF = "adb_off"              // the remote service answers, ADB's port is closed: keys still work
    const val REMOTE_OFF = "remote_off"        // ADB answers, the remote service does not
    const val UP = "up"                        // answers: the failure was momentary
    const val CLOSED = "closed"                // the TV is on the network but none of its services listen
    const val MOVED = "moved"                  // nothing at this address; the same TV, by name, answers at another
    const val VPN = "vpn"                      // nothing answers and a VPN is carrying the traffic
    const val OFF = "off"                      // nothing answers: off, asleep, or another address
    const val SETUP_OFF = "setup_off"          // a Fire TV whose setup never finished: Home and Alexa keys do nothing (checked while ADB is up)
    val CODES = listOf(SETUP_OFF, CLOCK, NO_WIFI, OTHER_NET, TAILSCALE, ALLOW, WIRELESS, ADB_OFF, REMOTE_OFF, UP, CLOSED, MOVED, VPN, OFF)

    const val ADB = dev.tvremote.core.adb.AdbPort.CLASSIC
    private const val SETUP = 8765

    /** The ports worth asking, by kind: the remote service(s), then what only an Android TV has (ADB and TV Setup). */
    fun ports(kind: String): List<Int> = when (kind) {
        Remote.Kind.ANDROID -> listOf(ADB) + dev.tvremote.core.adb.AdbPort.OTHERS + listOf(6466, 6467, SETUP)
        Remote.Kind.LG -> listOf(3000, 3001)
        Remote.Kind.SAMSUNG -> listOf(8001, 8002)
        Remote.Kind.ROKU -> listOf(8060)
        Remote.Kind.VIDAA -> listOf(36669)
        else -> listOf(ADB) + dev.tvremote.core.adb.AdbPort.OTHERS + listOf(6466, 6467, SETUP)
    }

    /** An error that comes from a TV whose date is wrong (Errors.say words it the same way). */
    fun clockError(err: String): Boolean = Regex("SSLHandshake|CertPathValidator|Chain validation|CERTIFICATE_VERIFY|not yet valid|expired", RegexOption.IGNORE_CASE).containsMatchIn(err)

    /** The one conclusion. Order matters: what rules out the others first. */
    fun decide(f: Facts): String {
        val open = f.open
        val android = f.kind == Remote.Kind.ANDROID
        if (f.tls && open.isNotEmpty()) return CLOCK
        if (open.isEmpty()) {
            when (f.net) { Net.NONE -> return NO_WIFI; Net.OTHER -> return OTHER_NET; Net.TAILSCALE_OFF -> return TAILSCALE; else -> {} }
            if (f.wireless && android) return WIRELESS            // 5555 is closed, so "alive" would call it a dozing TV
            if (f.alive) return CLOSED
            if (f.moved != null) return MOVED
            if (f.vpn && f.home) return VPN
            return OFF
        }
        if (!android) return UP
        val adb = ADB in open || dev.tvremote.core.adb.AdbPort.OTHERS.any { it in open }   // a box whose adbd skips 5555 (BeeCube: 5877)
        val remote = 6466 in open || 6467 in open
        if (adb && (f.adb == "unauthorized" || f.adb == "waiting")) return ALLOW
        if (!adb && f.wireless) return WIRELESS
        if (!adb && (remote || SETUP in open)) return ADB_OFF
        if (adb && !remote) return REMOTE_OFF
        return UP
    }

    /** One read over ADB (measured: one short line each): who made the device, and whether Android thinks its first setup was finished. */
    const val SETUP_READ = "getprop ro.product.manufacturer; settings get secure user_setup_complete"
    /** The fix, run only after the person pressed Fix. */
    const val SETUP_FIX = "settings put secure user_setup_complete 1"

    /** [SETUP_READ]'s output is a Fire TV (Amazon) with `user_setup_complete` = 0. Any other maker, or a missing / "null" value, is fine. */
    fun setupIncomplete(out: String): Boolean {
        val l = out.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        return l.size >= 2 && l[0].contains("amazon", true) && l.last() == "0"
    }

    /** The finding for a TV whose [SETUP_READ] said [out], or null when nothing is wrong. Not part of [run]: ADB is up then, no one asks "why can't I reach the TV". */
    fun setup(out: String, ip: String, lang: String): Result? =
        if (!setupIncomplete(out)) null else Result(SETUP_OFF, ip, Remote.Kind.ANDROID, null,
            listOf(Check("setup", false, Words.get(lang, "diagnose_kt.setup_off_note"))), 0)

    /** What each conclusion needs the person (or the page) to do next. */
    fun actions(code: String): List<String> = when (code) {
        SETUP_OFF -> listOf("fixsetup")
        WIRELESS -> listOf("wireless", "retry")
        ADB_OFF -> listOf("adbhelp", "retry")
        MOVED -> listOf("use", "retry")
        OFF, CLOSED, VPN -> listOf("retry", "scan")
        CLOCK -> listOf("retry")
        else -> listOf("retry")
    }

    /** [title] and [fix]: a short line saying what is wrong, and the one thing to do. */

    fun say(code: String, f: Facts, lang: String): Pair<String, String> {
        fun t(id: String, vararg a: Pair<String, Any?>) = Words.get(lang, id, *a)
        return when (code) {
            SETUP_OFF -> t("diagnose_kt.setup_off_title") to
                t("diagnose_kt.setup_off_fix")
            CLOCK -> t("diagnose_kt.tv_s_date_time") to
                t("diagnose_kt.tv_answers_but_secure")
            NO_WIFI -> t("diagnose_kt.device_not_wi_fi") to
                t("diagnose_kt.turn_wi_fi_join")
            OTHER_NET -> t("diagnose_kt.device_another_network_than") to
                t("diagnose_kt.tv_at_join_tv", "ip" to f.ip)
            TAILSCALE -> t("diagnose_kt.tailscale_off_device") to
                t("diagnose_kt.tv_at_tailscale_address", "ip" to f.ip)
            ALLOW -> t("setup.tv_waiting_allow") to
                t("diagnose_kt.look_at_tv_tick")
            WIRELESS -> t("diagnose_kt.wireless_only") to
                (t("diagnose_kt.wireless_pair") + tclHint(f, lang))
            ADB_OFF -> t("diagnose_kt.adb_debugging_off_tv") to
                (t("diagnose_kt.keys_still_work_picture") + tclHint(f, lang))
            REMOTE_OFF -> t("diagnose_kt.tv_s_remote_service") to
                t("diagnose_kt.adb_answers_so_keys")
            UP -> t("diagnose_kt.tv_answering") to
                t("diagnose_kt.error_was_momentary_try")
            CLOSED -> t("diagnose_kt.tv_network_but_not") to
                t("diagnose_kt.usually_tv_dozing_press")
            MOVED -> t("diagnosing.the_tv_has_a") to
                t("diagnose_kt.found_at_was_use", "name" to f.moved?.name, "ip" to f.moved?.ip, "was" to f.ip)
            VPN -> t("diagnose_kt.vpn_way") to
                t("diagnose_kt.tv_at_home_out")
            else -> t("diagnose_kt.tv_not_answering") to
                t("diagnose_kt.tv_may_be_off")
        }
    }

    private val OTHER_BRANDS = Regex("sony|bravia|xiaomi|\\bmi\\b|hisense|philips|nvidia|shield|chromecast|fire ?tv|xgimi|epson|oneplus|benq|chiq|\\brca\\b|sanyo|toshiba|insignia|thomson|skyworth", RegexOption.IGNORE_CASE)

    private val ISP = Regex("fpt|viettel", RegexOption.IGNORE_CASE)  // ISP boxes (MainActivity.ispBox): Developer options behind their password

    /** TCL (China) hides its ADB switch behind a key sequence; shown for a TCL and for a TV whose maker is not known, not for a known other one. */
    private fun tclHint(f: Facts, lang: String): String {
        val b = f.brand
        if (ISP.containsMatchIn(b)) return "\n" + Words.get(lang, "diagnose_kt.isp_locked")   // FPT Play asks a password at the 7 presses
        val other = b.isNotBlank() && !b.contains("tcl", true) && OTHER_BRANDS.containsMatchIn(b)
        return if (other) "" else "\n" + Words.get(lang, "setup.tcl_hidden_adb")
    }

    private fun checks(f: Facts, code: String, lang: String): List<Check> {
        fun t(id: String, vararg a: Pair<String, Any?>) = Words.get(lang, id, *a)
        val out = ArrayList<Check>()
        out.add(Check("net", when (f.net) { Net.NA -> null; Net.OK -> true; else -> false },
            when (f.net) {
                Net.NA -> t("diagnose_kt.device_s_network_nothing")
                Net.OK -> t("diagnose_kt.device_tv_s_network")
                Net.NONE -> t("diagnose_kt.device_has_no_wi")
                Net.OTHER -> t("diagnose_kt.device_another_network_than_2")
                Net.TAILSCALE_OFF -> t("diagnose_kt.tailscale_off")
            }))
        val open = f.open.sorted()
        out.add(Check("tv", f.alive, when {
            open.isNotEmpty() -> t("diagnose_kt.tv_answers_port", "open" to open.joinToString(", "))
            f.alive -> t("diagnose_kt.tv_network_but_its")
            else -> t("diagnose_kt.nothing_answers_at", "ip" to f.ip)
        }))
        if (f.wireless) out.add(Check("wireless", true, t("diagnose_kt.wireless_found")))
        if (f.vpn) out.add(Check("vpn", !(f.home && open.isEmpty()), t("diagnose_kt.vpn")))
        if (f.adb in listOf("ready", "unauthorized", "waiting", "offline")) out.add(Check("adb", f.adb == "ready", t("diagnose_kt.app_s_adb", "adb" to f.adb)))
        if (f.tls) out.add(Check("clock", if (code == CLOCK) false else null, t("diagnose_kt.secure_connection_error_date")))
        return out
    }

    /**
     * Looks, then decides. [err] is the error the person met (its words tell a wrong clock); [name] the TV's saved name, used
     * to look for it under another address when nothing answers. Takes about [Real.PORT_MS] plus the search, never more than ~3.5 s.
     */

    fun run(probe: Probe, ip: String, kind: String, name: String, err: String, lang: String): Result {
        val start = System.currentTimeMillis()
        val ports = ports(kind)
        val pool = Executors.newFixedThreadPool(ports.size + 4) { Thread(it, "diagnose").apply { isDaemon = true } }
        try {
            val open = ports.associateWith { p -> pool.submit(Callable { probe.port(ip, p, Real.PORT_MS) }) }
            val net = pool.submit(Callable { probe.net(ip) })
            val vpn = pool.submit(Callable { probe.vpn() })
            val adb = pool.submit(Callable { probe.adb(ip) })
            val android = kind == Remote.Kind.ANDROID
            val wl = pool.submit(Callable { android && probe.wireless(ip) })
            val mk = pool.submit(Callable { if (android) probe.maker(ip) else "" })
            fun <T> get(f: java.util.concurrent.Future<T>, or: T): T = try { f.get(Real.PORT_MS + 700L, TimeUnit.MILLISECONDS) } catch (e: Exception) { or }
            var f = Facts(kind, ip, open.mapValues { get(it.value, Port.SILENT) }, get(net, Net.NA), get(vpn, false), get(adb, null), clockError(err),
                wireless = get(wl, false), brand = (name + " " + get(mk, "")).trim())
            var code = decide(f)
            var moved: Found? = null
            if ((code == OFF || code == VPN) && name.isNotBlank()) {
                moved = try { probe.find(kind, name, ip) } catch (e: Exception) { null }
                if (moved != null) { f = Facts(kind, ip, f.ports, f.net, f.vpn, f.adb, f.tls, moved, f.wireless, f.brand); code = decide(f) }
            }
            val cs = checks(f, code, lang)
            return Result(code, ip, kind, moved, cs, System.currentTimeMillis() - start, f.brand).also { WireLog.all.add("·", "diagnose $ip $kind -> $code (${it.ms} ms)") }
        } finally {
            pool.shutdownNow()
        }
    }


    fun json(r: Result, lang: String): JSONObject {
        val f = Facts(r.kind, r.ip, emptyMap(), Net.NA, false, null, false, r.found, brand = r.brand)
        val (title, fix) = say(r.code, f, lang)
        return JSONObject().put("code", r.code).put("title", title).put("fix", fix).put("ip", r.ip).put("kind", r.kind).put("ms", r.ms)
            .put("actions", JSONArray(actions(r.code)))
            .put("found", r.found?.let { JSONObject().put("ip", it.ip).put("name", it.name) } ?: JSONObject.NULL)
            .put("checks", JSONArray(r.checks.map { JSONObject().put("id", it.id).put("ok", it.ok ?: JSONObject.NULL).put("note", it.note) }))
    }

    /** The conclusion as one block of words (the phone's native screens, which have a text box and no cards). */

    fun text(r: Result, lang: String): String {
        val (title, fix) = say(r.code, Facts(r.kind, r.ip, emptyMap(), Net.NA, false, null, false, r.found, brand = r.brand), lang)
        return "$title\n$fix"
    }

    /** The network itself. [link]: this app's ADB link to the TV in use, when there is one, for [adb]. */
    class Real(private val link: dev.tvremote.core.adb.AdbLink? = null, private val bindUdp: (java.net.DatagramSocket) -> Unit = Diagnose.bindUdp) : Probe {
        companion object { const val PORT_MS = 1400 }

        override fun port(host: String, port: Int, ms: Int): Port = try {
            Lan.connect(host, port, ms).close(); Port.OPEN
        } catch (e: ConnectException) {
            if (e.message?.contains("refused", true) == true || e.cause?.message?.contains("refused", true) == true) Port.REFUSED else Port.SILENT
        } catch (e: Exception) { Port.SILENT }

        override fun net(host: String): Net = netOf(host, locals())
        override fun vpn(): Boolean = Lan.wifi() != null || tunnelUp()
        override fun adb(host: String): String? {
            val l = link?.takeIf { it.host == host } ?: return null
            return if (l.waitingForUser()) "waiting" else l.state()
        }

        override fun wireless(host: String): Boolean = dev.tvremote.core.adb.AdbTls.available() && dev.tvremote.core.adb.AdbTls.wireless(host, 1500, bindUdp).any
        override fun maker(host: String): String = Ssdp.castName(host)

        override fun find(kind: String, name: String, not: String): Found? {
            val want = name.trim().lowercase()
            if (want.isEmpty()) return null
            val cands: List<Found> = if (kind == Remote.Kind.ANDROID) {
                val svcs = Mdns.find("_androidtvremote2._tcp", 1800, bindUdp).filter { it.ip != not }
                val pool = Executors.newFixedThreadPool(maxOf(1, svcs.size)) { Thread(it, "diagnose").apply { isDaemon = true } }
                try {
                    svcs.map { s -> pool.submit(Callable { Found(s.ip, Ssdp.castName(s.ip).ifEmpty { s.name }) }) }.mapNotNull { try { it.get(1500, TimeUnit.MILLISECONDS) } catch (e: Exception) { null } }
                } finally { pool.shutdownNow() }
            } else Ssdp.find(1800, bindUdp).filter { it.kind == kind && it.ip != not }.map { Found(it.ip, it.name) }
            return cands.filter { it.name.trim().lowercase() == want }.singleOrNull()
        }
    }

    /** Set by the phone: puts a UDP socket on the Wi-Fi under a VPN, as its scans do. */
    @Volatile var bindUdp: (java.net.DatagramSocket) -> Unit = {}

    /** A local IPv4 address with the length of its network, from an interface a TV could be reached through. */
    class Local(val ip: InetAddress, val prefix: Int)

    private val NOT_LAN = Regex("^(lo|tun|tap|utun|ppp|wg|tailscale|rmnet|ccmni|pdp|dummy|docker|veth|br-|virbr|vmnet|vboxnet|awdl|llw|ap\\d|p2p)", RegexOption.IGNORE_CASE)

    internal fun locals(): List<Local> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { ni -> try { ni.isUp && !ni.isLoopback && !NOT_LAN.containsMatchIn(ni.name) } catch (e: Exception) { false } }
            .flatMap { ni -> ni.interfaceAddresses.filter { it.address is Inet4Address }.map { Local(it.address, it.networkPrefixLength.toInt()) } }
    } catch (e: Exception) { emptyList() }

    /** A tunnel interface (not Tailscale's 100.64/10) holding an IPv4 address: a VPN carries this device's traffic. */
    private fun tunnelUp(): Boolean = try {
        NetworkInterface.getNetworkInterfaces().toList().any { ni ->
            ni.isUp && Regex("^(tun|tap|utun|ppp|wg|ipsec)", RegexOption.IGNORE_CASE).containsMatchIn(ni.name) &&
                ni.inetAddresses.toList().any { a -> a is Inet4Address && !tailnet(a) && !a.isLinkLocalAddress }
        }
    } catch (e: Exception) { false }

    private fun tailnet(a: InetAddress) = Lan.inSubnet(a, InetAddress.getByName("100.64.0.0"), 10)

    /** This device against [host]'s address, from its own addresses [mine]. */
    fun netOf(host: String, mine: List<Local>): Net {
        if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)) return Net.NA          // a name is never looked up here (Lan.home)
        val ip = try { InetAddress.getByName(host) } catch (e: Exception) { return Net.NA }
        if (ip !is Inet4Address || ip.isLoopbackAddress) return Net.NA
        if (tailnet(ip)) return if (NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni -> try { ni.isUp && ni.inetAddresses.toList().any { it is Inet4Address && tailnet(it) } } catch (e: Exception) { false } }) Net.OK else Net.TAILSCALE_OFF
        if (Lan.home(host) == null) return Net.NA
        val lan = mine.filter { Lan.home(it.ip.hostAddress ?: "") != null && !tailnet(it.ip) }
        if (lan.isEmpty()) return Net.NONE
        return if (lan.any { Lan.inSubnet(ip, it.ip, it.prefix) }) Net.OK else Net.OTHER
    }
}
