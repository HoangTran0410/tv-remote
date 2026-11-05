package dev.tvremote.core

import dev.tvremote.core.adb.Adb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * adb's `delayed_ack` (adb/docs/dev/delayed_ack.md): with the feature on both sides a stream may have a window of bytes in
 * flight instead of one packet; OPEN's arg1 asks for it, OKAY carries an int of bytes. A fake adbd with and without it.
 */
class AdbDelayedAckTest {
    private class Pkt(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    /** A device that records every packet and acks nothing by itself: the test decides when and with what. */
    private class Dev(val delayed: Boolean, val window: Int = 65536) : AutoCloseable {
        val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val got = LinkedBlockingQueue<Pkt>()
        @Volatile var openArg1 = -1
        @Volatile var sock: Socket? = null
        val wrte = AtomicInteger()

        init {
            Thread({
                val s = server.accept(); sock = s
                val input = DataInputStream(s.getInputStream())
                try {
                    while (true) {
                        val head = ByteArray(24); input.readFully(head)
                        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
                        val data = ByteArray(len); input.readFully(data)
                        when (cmd) {
                            CNXN -> send(CNXN, 0x01000000, 4096, ("device::ro.product.name=x;features=shell_v2" + (if (delayed) ",delayed_ack" else "") + "\u0000").toByteArray())
                            OPEN -> { openArg1 = a1; send(OKAY, 77, a0, if (delayed) int(window) else ByteArray(0)) }
                            WRTE -> wrte.incrementAndGet()
                        }
                        got.add(Pkt(cmd, a0, a1, data))
                    }
                } catch (ignored: Exception) { }
            }, "fake-adbd").apply { isDaemon = true }.start()
        }

        fun int(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

        @Synchronized fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray) {
            val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xff }).putInt(cmd.inv()).put(data)
            sock!!.getOutputStream().write(b.array()); sock!!.getOutputStream().flush()
        }

        fun next(cmd: Int): Pkt {
            while (true) { val p = got.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no packet"); if (p.cmd == cmd) return p }
        }

        override fun close() = server.close()
    }

    private fun connect(dev: Dev): Adb {
        val adb = Adb(Files.createTempDirectory("adbdelayed").toFile())
        adb.connect("127.0.0.1", dev.server.localPort) {}
        return adb
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!ok()) { if (System.currentTimeMillis() > end) throw AssertionError(what); Thread.sleep(10) }
    }

    @Test fun withTheFeatureSeveralPacketsFlyBeforeTheFirstAck() = Dev(delayed = true).use { dev ->
        val adb = connect(dev)
        val s = adb.open("exec:sink")
        assertEquals(1 shl 20, dev.openArg1)                          // we ask for it and give our window
        s.write(ByteArray(40000))                                      // 10 packets of 4096 into a window of 65536: returns with no ack at all
        waitFor("10 writes") { dev.wrte.get() == 10 }
        adb.close()
    }

    @Test fun theWindowStopsTheSenderAndAnOkayOpensIt() = Dev(delayed = true, window = 8192).use { dev ->
        val adb = connect(dev)
        val s = adb.open("exec:sink")
        val writer = Thread { s.write(ByteArray(20000)) }.apply { isDaemon = true; start() }
        waitFor("2 writes") { dev.wrte.get() == 2 }
        Thread.sleep(300)
        assertEquals(2, dev.wrte.get())                                // 8192 bytes of room used: nothing more goes
        dev.send(OKAY, 77, 1, dev.int(8192))                           // the TV took them
        waitFor("4 writes") { dev.wrte.get() == 4 }
        dev.send(OKAY, 77, 1, dev.int(8192))
        dev.send(OKAY, 77, 1, dev.int(8192))
        writer.join(5000)
        assertFalse(writer.isAlive)
        waitFor("5 writes") { dev.wrte.get() == 5 }                    // 20000 = 4 x 4096 + 3616
        adb.close()
    }

    @Test fun readingAcksWithTheByteCount() = Dev(delayed = true).use { dev ->
        val adb = connect(dev)
        val s = adb.open("exec:src")
        dev.send(WRTE, 77, 1, ByteArray(300))
        dev.send(WRTE, 77, 1, ByteArray(100))
        val buf = ByteArray(400)
        s.readFully(buf, 0, 400)
        val a = dev.next(OKAY)
        val b = dev.next(OKAY)
        assertArrayEquals(dev.int(300), a.data)
        assertArrayEquals(dev.int(100), b.data)
        adb.close()
    }

    @Test fun withoutTheFeatureItIsOnePacketAtATime() = Dev(delayed = false).use { dev ->
        val adb = connect(dev)
        val s = adb.open("exec:sink")
        assertEquals(0, dev.openArg1)
        val writer = Thread { s.write(ByteArray(12000)) }.apply { isDaemon = true; start() }
        waitFor("1 write") { dev.wrte.get() == 1 }
        Thread.sleep(300)
        assertEquals(1, dev.wrte.get())                                // waits for the OKAY
        dev.send(OKAY, 77, 1, ByteArray(0))
        waitFor("2 writes") { dev.wrte.get() == 2 }
        dev.send(OKAY, 77, 1, ByteArray(0))
        waitFor("3 writes") { dev.wrte.get() == 3 }
        dev.send(OKAY, 77, 1, ByteArray(0))
        writer.join(5000)
        assertFalse(writer.isAlive)
        dev.send(WRTE, 77, 1, ByteArray(10))
        s.readFully(ByteArray(10), 0, 10)
        assertEquals(0, dev.next(OKAY).data.size)                      // a plain OKAY, no count
        adb.close()
    }

    @Test fun theBannerAdvertisesTheFeature() = Dev(delayed = false).use { dev ->
        val adb = connect(dev)
        val banner = String(dev.next(CNXN).data)
        assertTrue(banner, banner.startsWith("host::features=delayed_ack"))
        adb.close()
    }

    companion object {
        private const val CNXN = 0x4e584e43
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val WRTE = 0x45545257
    }
}
