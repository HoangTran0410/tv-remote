package dev.tvremote.core.adb

import dev.tvremote.core.Lan
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Which port a TV's adbd listens on. Almost always 5555, but some firmware moves it: the BeeCube Extreme2 projector (Android 9)
 * sets `service.adb.tcp.port` to 5877 and leaves 5555 closed (a user's post, 2026-11-06). The TV itself reads its own property;
 * a phone or computer cannot, so it tries the ports other boxes are known to use, 5555 first. The port found is kept per host.
 */
object AdbPort {
    const val CLASSIC = 5555
    /** Ports seen on boxes whose adbd skips 5555, tried after it. A new one goes here, with the box it was seen on. */
    val OTHERS = listOf(5877)                                         // BeeCube Extreme2
    private val found = ConcurrentHashMap<String, Int>()
    private val typed = ConcurrentHashMap<String, Int>()             // host -> the port the person typed with it (kept in FILE)
    @Volatile private var loaded: File? = null
    private const val FILE = "adb-ports"                              // beside the ADB key: one "host port" line per TV typed with a port

    /**
     * An address typed with a port: "192.168.1.5:5877" -> ("192.168.1.5", 5877). Also a space, comma or semicolon before the port,
     * since a phone's number pad has those but no colon. Without a port (or not an IPv4 address): the text and 0.
     */
    fun split(text: String): Pair<String, Int> {
        val m = TYPED.matchEntire(text.trim()) ?: return text.trim() to 0
        val port = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return text.trim() to 0
        return m.groupValues[1] to port
    }
    private val TYPED = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*[:,; ]\\s*(\\d{1,5})")

    /** The port typed for [host]: tried first from now on, also after a restart (kept in [dir], the ADB key's folder). 5555 forgets it. */
    fun remember(dir: File, host: String, port: Int) {
        load(dir)
        if (port == CLASSIC) typed.remove(host) else typed[host] = port
        found.remove(host)
        try { File(dir, FILE).writeText(typed.entries.joinToString("") { "${it.key} ${it.value}\n" }) } catch (ignored: Exception) { }
    }

    /** Reads the typed ports kept in [dir], once per folder. */
    fun load(dir: File) {
        if (loaded == dir) return
        loaded = dir
        try { File(dir, FILE).takeIf { it.exists() }?.readLines()?.forEach { l -> l.trim().split(' ').takeIf { it.size == 2 }?.let { (h, p) -> p.toIntOrNull()?.let { typed[h] = it } } } } catch (ignored: Exception) { }
    }

    /** The TV's own adbd ports from its properties (`service.adb.tcp.port`, `persist.adb.tcp.port`); empty off Android. */
    fun props(): List<Int> = listOf("service.adb.tcp.port", "persist.adb.tcp.port").mapNotNull { k ->
        try { (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, k) as String).toIntOrNull() } catch (e: Throwable) { null }
    }.filter { it in 1..65535 }

    /** The ports to try on [host], most likely first: the one typed, the last one that answered, the TV's own property (on the TV), 5555, the others. */
    fun candidates(host: String): List<Int> =
        (listOfNotNull(typed[host], found[host]) + (if (AdbTls.same(host, "127.0.0.1")) props() else emptyList()) + CLASSIC + OTHERS).distinct()

    /** The port [host] was typed with or last answered on, else 5555: what a script made for that TV tells the person to dial. */
    fun known(host: String): Int = typed[host] ?: found[host] ?: CLASSIC

    /** The first candidate that answers on [host], or 0 when none does; [open] dials one port (a test passes its own). */
    fun find(host: String, timeoutMs: Int = 1500, open: (String, Int, Int) -> Boolean = ::dial): Int {
        for (p in candidates(host)) if (open(host, p, timeoutMs)) { if (p != CLASSIC || found[host] != null) found[host] = p; return p }
        return 0
    }

    /** The port to dial for ADB on [host]: the one that answers, else 5555 (so the error says what 5555 said). */
    fun of(host: String, timeoutMs: Int = 1500): Int = find(host, timeoutMs).takeIf { it > 0 } ?: CLASSIC

    private fun dial(host: String, port: Int, timeoutMs: Int): Boolean = try { Lan.connect(host, port, timeoutMs).close(); true } catch (e: Exception) { false }
}
