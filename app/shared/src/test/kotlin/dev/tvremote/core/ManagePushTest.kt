package dev.tvremote.core

import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.web.ManageCore
import dev.tvremote.core.web.TvCopy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/** A failed or cancelled upload removes its own leftovers and never a good file that was already at the destination. */
class ManagePushTest {
    private val OPEN = 0x4e45504f
    private val OKAY = 0x59414b4f
    private val CLSE = 0x45534c43
    private val WRTE = 0x45545257

    /** A TV that answers the ADB packets in memory; [shells] collects the shell commands it was asked. */
    private class FakeTv(val exists: Boolean) {
        val shells = CopyOnWriteArrayList<String>()
        val services = CopyOnWriteArrayList<String>()
        val toClient = LinkedBlockingQueue<ByteArray>()
        private var remote = 100
        private val ids = HashMap<Int, Int>()

        private fun pack(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)): ByteArray =
            ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(0).putInt(cmd.inv()).put(data).array()

        val fromClient = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {
                val bb = ByteBuffer.wrap(b, off, len).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val n = bb.int
                bb.int; bb.int
                val data = ByteArray(n).also { bb.get(it) }
                when (cmd) {
                    0x4e45504f -> {                                   // OPEN: a shell answers and ends, an exec stays open
                        val svc = String(data).trimEnd('\u0000')
                        services.add(svc)
                        val r = remote++
                        ids[a0] = r
                        toClient.add(pack(0x59414b4f, r, a0))
                        if (svc.startsWith("shell:")) {
                            val cmdline = svc.removePrefix("shell:")
                            shells.add(cmdline)
                            val out = if (cmdline.startsWith("test -e") && exists) "y\n" else ""
                            if (out.isNotEmpty()) toClient.add(pack(0x45545257, r, a0, out.toByteArray()))
                            toClient.add(pack(0x45534c43, r, a0))
                        }
                    }
                    0x45545257 -> toClient.add(pack(0x59414b4f, ids[a0] ?: 0, a0))   // WRTE: taken
                }
            }
        }
        val toClientStream = object : InputStream() {
            private var cur = ByteArray(0)
            private var pos = 0
            override fun read(): Int {
                while (pos >= cur.size) { cur = toClient.take(); pos = 0 }
                return cur[pos++].toInt() and 0xff
            }
        }
    }

    private fun link(tv: FakeTv): AdbLink {
        val adb = Adb(Files.createTempDirectory("keys").toFile())
        fun f(c: Class<*>, n: String) = c.getDeclaredField(n).apply { isAccessible = true }
        f(Adb::class.java, "input").set(adb, DataInputStream(tv.toClientStream))
        f(Adb::class.java, "output").set(adb, tv.fromClient)
        Adb::class.java.getDeclaredMethod("pump").apply { isAccessible = true }.let { m -> Thread { m.invoke(adb) }.apply { isDaemon = true }.start() }
        val l = AdbLink(Files.createTempDirectory("keys").toFile(), "127.0.0.1:1")
        f(AdbLink::class.java, "adb").set(l, adb)
        return l
    }

    /** Calls push() the way the routes do; an input that breaks halfway stands for a cable pulled mid-transfer. */
    private fun upload(tv: FakeTv, length: Long, dest: String): Boolean {
        val broken = object : InputStream() {
            private var left = 10
            override fun read(): Int = throw IOException("cut")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) throw IOException("cut")
                val n = minOf(left, len); left -= n; return n
            }
        }
        try { TvCopy(ManageCore(link(tv), null, null)).push(broken, length, dest, null, 1); fail("should throw") } catch (e: IOException) { }
        return tv.shells.any { it == "rm -f " + Adb.q(dest) }
    }

    @Test fun knownLengthFailureNeverRemovesTheGoodFileAtDest() {
        val tv = FakeTv(exists = true)
        assertFalse(upload(tv, 100, "/sdcard/Download/a.mp4"))
        assertTrue(tv.shells.contains("rm -f " + Adb.q("/sdcard/Download/a.mp4.part")))
    }

    @Test fun chunkedFailureKeepsAFileThatWasThereBefore() {
        val tv = FakeTv(exists = true)
        assertFalse(upload(tv, -1, "/sdcard/Download/b.bin"))
        assertTrue(tv.shells.contains("rm -f " + Adb.q("/sdcard/Download/b.bin.part")))      // no half file stays
        assertFalse("no probe round trip", tv.shells.any { it.startsWith("test -e") })
        assertTrue("written aside, never straight to dest", tv.services.contains("exec:cat > " + Adb.q("/sdcard/Download/b.bin.part")))
        assertFalse(tv.services.contains("exec:cat > " + Adb.q("/sdcard/Download/b.bin")))
    }

    @Test fun chunkedFailureLeavesNoPartFile() {
        val tv = FakeTv(exists = false)
        assertFalse(upload(tv, -1, "/sdcard/Download/c.bin"))
        assertTrue(tv.shells.contains("rm -f " + Adb.q("/sdcard/Download/c.bin.part")))
    }
}
