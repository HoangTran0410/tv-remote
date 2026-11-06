package dev.tvremote.core

import dev.tvremote.core.adb.AdbPort
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbPortTest {
    @Test fun classicPortWins() {
        assertEquals(5555, AdbPort.find("10.9.0.1", open = { _, p, _ -> p == 5555 || p == 5877 }))
    }

    @Test fun otherPortIsFoundAndRemembered() {
        val host = "10.9.0.2"
        assertEquals(5877, AdbPort.find(host, open = { _, p, _ -> p == 5877 }))
        val tried = mutableListOf<Int>()
        AdbPort.find(host, open = { _, p, _ -> tried.add(p); true })
        assertEquals(5877, tried.first())                       // the one that answered last time goes first
    }

    @Test fun nothingOpenGivesZeroAndClassic() {
        assertEquals(0, AdbPort.find("10.9.0.3", open = { _, _, _ -> false }))
        assertEquals(5555, AdbPort.of("10.9.0.3", 50))          // nothing listens there: 5555, so the error says what 5555 said
    }

    @Test fun typedAddressWithPort() {
        assertEquals("192.168.1.5" to 5877, AdbPort.split(" 192.168.1.5:5877 "))
        assertEquals("192.168.1.5" to 5877, AdbPort.split("192.168.1.5 5877"))   // the phone's number pad has no colon
        assertEquals("192.168.1.5" to 5877, AdbPort.split("192.168.1.5,5877"))
        assertEquals("192.168.1.5" to 0, AdbPort.split("192.168.1.5"))
        assertEquals("192.168.1.5:99999" to 0, AdbPort.split("192.168.1.5:99999"))   // not a port: left for the address check to refuse
    }

    @Test fun typedPortGoesFirstAndIsKept() {
        val dir = java.nio.file.Files.createTempDirectory("adbport").toFile()
        AdbPort.remember(dir, "10.9.0.4", 6000)
        assertEquals(6000, AdbPort.candidates("10.9.0.4").first())
        assertEquals("10.9.0.4 6000\n", java.io.File(dir, "adb-ports").readText())
        AdbPort.remember(dir, "10.9.0.4", 5555)                    // typed with 5555: back to the usual order
        assertEquals(5555, AdbPort.candidates("10.9.0.4").first())
    }
}
