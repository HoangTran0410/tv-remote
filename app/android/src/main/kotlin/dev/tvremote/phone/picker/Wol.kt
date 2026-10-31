package dev.tvremote.phone.picker

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Wake-on-LAN. The magic packet is a broadcast, so it only reaches the TV when the phone is on the TV's own network
 * (not through Tailscale or mobile data).
 */
internal object Wol {
    /** Returns how many packets went out. lan: the TV's home IP, or "" if unknown. */
    fun send(mac: String, lan: String): Int {
        val hex = mac.replace(":", "").replace("-", "")
        if (hex.length != 12) return 0
        val pkt = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) pkt[i] = 0xff.toByte()
        for (i in 0 until 16 * 6) pkt[6 + i] = hex.substring(i % 6 * 2, i % 6 * 2 + 2).toInt(16).toByte()
        val to = LinkedHashSet<InetAddress>()
        try {
            to.add(InetAddress.getByName("255.255.255.255"))
            if (lan.lastIndexOf('.') > 0) to.add(InetAddress.getByName(lan.substring(0, lan.lastIndexOf('.')) + ".255"))
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (ni.isUp && !ni.isLoopback)
                    for (a in ni.interfaceAddresses) a.broadcast?.let { to.add(it) }
        } catch (ignored: Exception) {
        }
        var sent = 0
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                repeat(3) {
                    for (ip in to) for (port in intArrayOf(9, 7)) {
                        try {
                            s.send(DatagramPacket(pkt, pkt.size, ip, port))
                            sent++
                        } catch (ignored: Exception) {                // e.g. no route on mobile data
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return sent
    }

    /** True when one of the phone's own networks is the TV's home network (same first three numbers). */
    fun sameNetwork(lan: String): Boolean {
        if (lan.lastIndexOf('.') <= 0) return false
        val net = lan.substring(0, lan.lastIndexOf('.') + 1)
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (ni.isUp && !ni.isLoopback)
                    for (a in Collections.list(ni.inetAddresses)) if (a.hostAddress?.startsWith(net) == true) return true
        } catch (ignored: Exception) {
        }
        return false
    }
}
