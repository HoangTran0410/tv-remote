package dev.tvremote.core

import dev.tvremote.core.adb.Adb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * A TV whose adbd serves one client at a time (an RCA set, Realtek RTD284x, Android 7.1.1): the second user of the app shares
 * the open connection instead of failing, the TV is remembered, and the connection closes only when its last user lets go.
 */
class AdbShareTest {
    /** adbd that takes one client: a second one, while the first is in, is dropped at once. Answers every shell with "hi". */
    private class OneClient : AutoCloseable {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val accepted = AtomicInteger()
        @Volatile var inUse = false

        init {
            Thread({
                while (!server.isClosed) {
                    val s = try { server.accept() } catch (e: Exception) { break }
                    accepted.incrementAndGet()
                    if (inUse) { s.close(); continue }
                    inUse = true
                    Thread({ try { serve(s) } catch (ignored: Exception) { } finally { inUse = false; s.close() } }, "fake-adbd").apply { isDaemon = true }.start()
                }
            }, "fake-adbd-accept").apply { isDaemon = true }.start()
        }

        private fun send(s: Socket, cmd: Int, a0: Int, a1: Int, data: ByteArray) {
            val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv()).put(data)
            s.getOutputStream().write(b.array()); s.getOutputStream().flush()
        }

        private fun serve(s: Socket) {
            val input = DataInputStream(s.getInputStream())
            while (true) {
                val head = ByteArray(24); input.readFully(head)
                val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = b.int; val a0 = b.int; b.int; val len = b.int
                input.readFully(ByteArray(len))
                when (cmd) {
                    CNXN -> send(s, CNXN, 0x01000000, 4096, "device::\u0000".toByteArray())
                    OPEN -> { val remote = 1000 + a0; send(s, OKAY, remote, a0, ByteArray(0)); send(s, WRTE, remote, a0, "hi\n".toByteArray()); send(s, CLSE, remote, a0, ByteArray(0)) }
                }
            }
        }

        override fun close() = server.close()
    }

    private fun dial(dir: File, tv: OneClient): Adb {
        val fresh = Adb(dir)
        return fresh.connectOrShare(HOST, dial = { fresh.connect(HOST, tv.server.localPort) {} }) {}
    }

    @Test fun aSecondUserSharesTheOneConnection() = OneClient().use { tv ->
        val dir = Files.createTempDirectory("adbshare").toFile()
        val first = dial(dir, tv)
        val second = dial(dir, tv)                                     // the TV drops this dial: the open connection is shared
        assertSame(first, second)
        assertEquals("hi", second.shell("echo").trim())
        assertTrue(File(dir, "adb-one-client.txt").readText().contains(HOST))
        val third = dial(dir, tv)                                      // remembered: no dial at all
        assertSame(first, third)
        assertEquals(2, tv.accepted.get())
        first.close(); third.close()                                   // the others let go: still on for the last one
        assertEquals("hi", second.shell("echo").trim())
        second.close()
        assertTrue(second.isDead())
    }

    @Test fun aTvWithoutTheLimitKeepsItsOwnConnections() {
        val dir = Files.createTempDirectory("adbshare").toFile()
        val a = Adb(dir)
        val e = runCatching { a.connectOrShare("192.0.2.1", dial = { throw java.io.IOException("refused") }) {} }.exceptionOrNull()
        assertTrue(e?.message == "refused")                           // nothing open to that TV: the failure is the TV's own
        assertTrue(!File(dir, "adb-one-client.txt").exists())
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val CNXN = 0x4e584e43
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
    }
}
