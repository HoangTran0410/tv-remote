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
}
