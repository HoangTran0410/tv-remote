package dev.tvremote.core
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** The Wake-on-LAN magic packet: the one place it is built and sent (the Computer app's Wake, Hisense VIDAA's connect). */
object Wol {
    private val HEX12 = Regex("[0-9a-fA-F]{12}")
    /** [mac] (any separators) woken by broadcasts to 255.255.255.255 and to [lan]'s ".255" (when [lan] is an IPv4), on UDP 9 and 7; packets sent. */
    fun send(mac: String, lan: String = ""): Int {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (!HEX12.matches(hex)) return 0                            // "GG" would throw in toInt(16): a 500 instead of "bad MAC"
        val raw = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pkt = ByteArray(6) { 0xff.toByte() } + (1..16).flatMap { raw.toList() }
        val targets = listOf("255.255.255.255") + if (lan.count { it == '.' } == 3) listOf(lan.substringBeforeLast('.') + ".255") else emptyList()
        var sent = 0
        DatagramSocket().use { s ->
            s.broadcast = true
            for (ip in targets) for (port in listOf(9, 7)) try {
                repeat(3) { s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(ip), port)); sent++; Thread.sleep(100) }   // 3 tries: UDP gets lost
            } catch (ignored: Exception) {
            }
        }
        return sent
    }
}
