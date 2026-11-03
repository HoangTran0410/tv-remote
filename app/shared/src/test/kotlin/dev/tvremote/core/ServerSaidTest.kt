package dev.tvremote.core

import dev.tvremote.core.picture.Tv
import org.junit.Assert.assertEquals
import org.junit.Test

/** "scrcpy-server did not start on the TV" carries the server's own reason (census 3d3fb107, ae16036c). */
class ServerSaidTest {
    @Test fun theErrorLineWins() = assertEquals(": [server] ERROR: Could not create display",
        Tv.serverSaid("[server] INFO: Device: TCL\n[server] WARN: x\n[server] ERROR: Could not create display\n\tat a.b(C.java:1)\n"))
    @Test fun anExceptionLine() = assertEquals(": java.lang.SecurityException: no permission",
        Tv.serverSaid("INFO: start\njava.lang.SecurityException: no permission\n"))
    @Test fun elseTheLastLine() = assertEquals(": Aborted", Tv.serverSaid("INFO: start\r\nAborted\r\n"))
    @Test fun nothingSaid() = assertEquals("", Tv.serverSaid("  \n"))
    @Test fun keptShort() = assertEquals(202, Tv.serverSaid("ERROR: " + "x".repeat(500)).length)
}
