package dev.tvremote.phone.ir

import dev.tvremote.phone.picker.open

import dev.tvremote.core.adb.Adb
import java.io.Closeable

// Find code by itself: the phone sends each remote's code while the TV, over ADB, says whether its own IR eye got a key (getevent),
// so nobody has to watch the TV and tap "it reacted". Only for the device ADB reaches: a box's ADB cannot see the TV it is plugged
// into, so that one is still found by hand (IrRemote.kt irFind).

/** What `getevent -l` prints, read line by line: which device is which, and whether a line is a key from a real one. */
internal object IrEvents {
    private val ADD = Regex("""^add device \d+: (/dev/input/\S+)""")
    private val NAME = Regex("""^\s+name:\s+"(.*)"""")
    private val EVENT = Regex("""^(/dev/input/\S+): (EV_KEY\s+\S+\s+DOWN|EV_MSC\s+MSC_SCAN\s+\S+)""")
    private val SCAN = Regex("""MSC_SCAN\s+([0-9a-fA-F]+)""")

    /**
     * The scancodes the TV's IR driver may report for [code]. The kernel decodes ANY frame of a protocol it knows (an LG NEC key too, on a box
     * whose own remote is NEC) and reports its scancode even when no key is mapped to it: so "a scancode came" proves only that the protocol
     * fits, not that this remote is the box's. The address and command must be in it. Null: no way to tell (RAW), any scancode counts.
     */
    fun scans(code: String): Set<Long>? {
        val p = code.trim().split(Regex("\\s+"))
        if (p.size < 3 || p[0] == "RAW") return null
        val a = p[1].toLongOrNull(16) ?: return null
        val c = p[2].toLongOrNull(16) ?: return null
        val out = hashSetOf((a shl 8) or c, (a shl 16) or c)                       // rc-core: NEC, Samsung, Sony, RC5, RC6 (addr<<8|cmd)
        val word = when (p[0]) {                                                    // the 32 bits as they are sent, for drivers that report the frame
            "NEC" -> (a and 0xFF) or ((a.inv() and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)
            "NECext" -> (a and 0xFFFF) or ((c and 0xFFFF) shl 16)
            "Samsung32" -> (a and 0xFF) or ((a and 0xFF) shl 8) or ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)
            else -> null
        }
        if (word != null) {
            val w = word and 0xFFFFFFFFL
            out.add(w)
            out.add(java.lang.Long.reverseBytes(w) ushr 32)                         // and the other way round
            out.add(((a and 0xFF) shl 24) or (((a.inv()) and 0xFF) shl 16) or ((c and 0xFF) shl 8) or (c.inv() and 0xFF))   // rc-core's NEC32
            if (p[0] == "NECext") out.add(((a and 0xFFFF) shl 16) or (c and 0xFFFF))
        }
        return out
    }

    /** The value of a `MSC_SCAN` line, null for any other line. */
    fun scanOf(line: String): Long? = SCAN.find(line)?.groupValues?.get(1)?.toLongOrNull(16)

    /** Fed every line in order: true for a key pressed on a device of the TV's own. Not virtual-*: keys sent over the network
     *  (Android TV Remote service), never the IR eye. MSC_SCAN too: the IR decoder reports a code even for a key the TV maps to nothing. */
    class Reader {
        private val names = HashMap<String, String>()
        private var adding: String? = null

        /** Whether the last line [key] said true for was a mapped key (EV_KEY), as against a bare scancode. */
        var mapped = false
            private set

        fun key(line: String): Boolean {
            ADD.find(line)?.let { adding = it.groupValues[1]; return false }
            NAME.find(line)?.let { m -> adding?.let { names[it] = m.groupValues[1] }; adding = null; return false }
            val e = EVENT.find(line) ?: return false
            mapped = e.groupValues[2].startsWith("EV_KEY")
            return !(names[e.groupValues[1]] ?: "").startsWith("virtual")
        }
    }
}

/** The TV's keys as they come, while open. [last]: when the latest arrived (irNow), 0 for none yet. */
internal class IrHear(adb: Adb) : Closeable {
    /** The latest mapped key (the TV acted on it) and the latest bare scancode with its value. */
    @Volatile var lastKey = 0L
        private set
    @Volatile var lastScan = 0L
        private set
    @Volatile var scanValue = -1L
        private set

    /** When the TV last heard [code] (irNow), 0 for not yet: a mapped key of any kind, or a scancode that carries [code]'s address and command. */
    fun heard(code: String): Long {
        val want = IrEvents.scans(code)
        val scan = if (want == null || scanValue in want) lastScan else 0L
        return maxOf(lastKey, scan)
    }
    @Volatile private var closed = false
    private val s = adb.open("shell:getevent -l")

    init {
        Thread({
            val r = IrEvents.Reader()
            val buf = ByteArray(4096)
            val line = StringBuilder()
            try {
                while (!closed) {
                    val n = s.read(buf, 0, buf.size, 500)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val c = buf[i].toInt().toChar()
                        if (c != '\n') { line.append(c); continue }
                        val text = line.toString()
                        if (r.key(text)) {
                            if (r.mapped) lastKey = irNow()
                            else { scanValue = IrEvents.scanOf(text) ?: -1L; lastScan = irNow() }
                        }
                        line.setLength(0)
                    }
                }
            } catch (ignored: Exception) { }
        }, "ir-hear").apply { isDaemon = true }.start()
    }

    override fun close() { closed = true; try { s.close() } catch (ignored: Exception) { } }
}

/**
 * Steps through [steps] (one code per distinct remote, IrCodes.distinct): sends one, waits for the TV to hear it, and on a key
 * sends it once more to be sure (a person pressing the real remote meanwhile is not a match). [send] is the IR LED, [heard] the
 * time of the TV's last key; [at] is told each step. The match, or null when none or [stop].
 */
internal fun <P> irScan(steps: List<P>, code: (P) -> String, send: (String) -> Unit, heard: () -> Long, stop: () -> Boolean,
                        at: (Int) -> Unit, waitMs: Long = 700): P? {
    fun answered(c: String): Boolean {
        val t = irNow()
        send(c)
        val end = t + waitMs
        while (irNow() < end) { if (heard() > t) return true; if (stop()) return false; Thread.sleep(40) }
        return heard() > t
    }
    for ((i, p) in steps.withIndex()) {
        if (stop()) return null
        at(i)
        if (answered(code(p))) { Thread.sleep(300); if (answered(code(p))) return p }
    }
    return null
}

internal fun irNow() = System.nanoTime() / 1_000_000                   // not SystemClock: the unit tests run without Android
