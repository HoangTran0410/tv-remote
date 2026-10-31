package dev.tvremote.phone.ir

// The infrared remote's encoders: a code ("NEC 4 8", the address and command in hex, as Flipper-IRDB lists them) -> the carrier
// and the on/off times in microseconds that ConsumerIrManager.transmit wants (starting with "on"). Plain Kotlin, no Android, so the
// timings are unit-tested (IrTest.kt). Bits go out low bit first except RC5 / RC6 / RCA, which are sent high bit first.

internal object Ir {
    class Signal(val hz: Int, val pattern: IntArray) {
        val micros: Long get() = pattern.fold(0L) { a, b -> a + b }
    }

    /** What the phone's IR LED can send in one go: a longer pattern is refused by some drivers. */
    const val MAX_MICROS = 2_000_000L

    /** [code] is "<protocol> <address hex> <command hex>" or "RAW <Hz> <us>...". [toggle]: RC5 / RC6's bit that flips per press. Null: not a code we can send. */
    fun encode(code: String, toggle: Boolean = false): Signal? = try {
        val p = code.trim().split(Regex("\\s+"))
        val s = when (p[0]) {
            "RAW" -> raw(p[1].toInt(), p.drop(2).map { it.toInt() })
            else -> {
                val a = p[1].toLong(16)
                val c = p[2].toLong(16)
                when (p[0]) {
                    "NEC" -> nec(a.toInt(), c.toInt())
                    "NECext" -> necExt(a.toInt(), c.toInt())
                    "Samsung32" -> samsung32(a.toInt(), c.toInt())
                    "SIRC" -> sirc(5, a.toInt(), c.toInt())
                    "SIRC15" -> sirc(8, a.toInt(), c.toInt())
                    "SIRC20" -> sirc(13, a.toInt(), c.toInt())
                    "RC5", "RC5X" -> rc5(a.toInt(), c.toInt(), toggle)
                    "RC6" -> rc6(a.toInt(), c.toInt(), toggle)
                    "RCA" -> rca(a.toInt(), c.toInt())
                    else -> null
                }
            }
        }
        s?.takeIf { it.pattern.isNotEmpty() && it.micros <= MAX_MICROS }
    } catch (e: Exception) { null }

    /**
     * What a real remote sends while the key stays down, every [period] ms after the first frame: NEC its short repeat code
     * (a TV's decoder takes another full frame for a new press, so a held OK opened the app instead of its menu), the others
     * the same frame again with the same RC5 / RC6 toggle (one press, held). The TV tells a long press by the signal not stopping.
     */
    fun held(code: String, toggle: Boolean): Signal? = when (protocol(code)) {
        "NEC", "NECext" -> if (encode(code) == null) null else Signal(38000, intArrayOf(9000, 2250, 560))
        else -> encode(code, toggle)
    }

    /** Ms from the start of one frame to the next while held, as the protocol's remotes repeat (a longer gap reads as let go). */
    fun period(code: String): Int = when (protocol(code)) {
        "NEC", "NECext", "Samsung32" -> 108
        "SIRC", "SIRC15", "SIRC20" -> 135                               // the signal is already 3 frames 45 ms apart
        "RC5", "RC5X" -> 114
        "RC6" -> 107
        "RCA" -> 80
        else -> encode(code)?.let { maxOf(108, (it.micros / 1000).toInt() + 40) } ?: 108
    }

    /** The protocol of [code] ("NEC"), for the tests and the list of what a profile uses. */
    fun protocol(code: String): String = code.trim().substringBefore(' ')

    /** Pulse-distance bits, [bits] of [data] low bit first: a mark, then a short or a long space. */
    private fun distance(out: ArrayList<Int>, data: Long, bits: Int, mark: Int, zero: Int, one: Int) {
        for (i in 0 until bits) { out.add(mark); out.add(if ((data shr i) and 1L == 1L) one else zero) }
    }

    fun nec(addr: Int, cmd: Int): Signal = necFrame((addr and 0xFF) or ((addr.inv() and 0xFF) shl 8) or ((cmd and 0xFF) shl 16) or ((cmd.inv() and 0xFF) shl 24))

    /** The 16-bit address and command as they are, no inverted copies (Flipper's NECext). */
    fun necExt(addr: Int, cmd: Int): Signal = necFrame((addr and 0xFFFF) or ((cmd and 0xFFFF) shl 16))

    private fun necFrame(word: Int): Signal {
        val o = arrayListOf(9000, 4500)
        distance(o, word.toLong() and 0xFFFFFFFFL, 32, 560, 560, 1690)
        o.add(560)
        return Signal(38000, o.toIntArray())
    }

    fun samsung32(addr: Int, cmd: Int): Signal {
        val a = addr and 0xFF
        val word = a or (a shl 8) or ((cmd and 0xFF) shl 16) or ((cmd.inv() and 0xFF) shl 24)
        val o = arrayListOf(4500, 4500)
        distance(o, word.toLong() and 0xFFFFFFFFL, 32, 550, 550, 1650)
        o.add(550)
        return Signal(38000, o.toIntArray())
    }

    /** Sony: 7 command bits, then [addrBits] (5 = SIRC12, 8 = SIRC15, 13 = SIRC20), the frame sent 3 times 45 ms apart as the remote does. */
    fun sirc(addrBits: Int, addr: Int, cmd: Int): Signal {
        val data = (cmd and 0x7F).toLong() or ((addr.toLong() and ((1L shl addrBits) - 1)) shl 7)
        val one = ArrayList<Int>()
        one.add(2400); one.add(600)
        for (i in 0 until 7 + addrBits) { one.add(if ((data shr i) and 1L == 1L) 1200 else 600); one.add(600) }
        val used = one.sum()
        val o = ArrayList<Int>()
        for (n in 0 until 3) {
            o.addAll(one)
            if (n < 2) o[o.size - 1] = o[o.size - 1] + (45000 - used)   // the next frame starts 45 ms after this one
        }
        return Signal(40000, o.toIntArray())
    }

    /** Manchester: each element is [level, units]; adjacent equal levels join, a leading "off" is dropped (a pattern starts with "on"). */
    private fun halves(levels: List<Pair<Boolean, Int>>, unit: Int, hz: Int): Signal {
        val o = ArrayList<Int>()
        var last: Boolean? = null
        for ((on, n) in levels) {
            if (last == null && !on) continue
            if (on == last) o[o.size - 1] = o[o.size - 1] + n * unit else o.add(n * unit)
            last = on
        }
        return Signal(hz, o.toIntArray())
    }

    /** RC5 / RC5X: 5 address bits, 6 command bits (a 7th, in the "field" bit, for RC5X), high bit first, 889 us halves. */
    fun rc5(addr: Int, cmd: Int, toggle: Boolean): Signal {
        val bits = ArrayList<Boolean>()
        bits.add(true)
        bits.add(cmd and 0x40 == 0)                                   // the second start bit is the inverse of command bit 6
        bits.add(toggle)
        for (i in 4 downTo 0) bits.add((addr shr i) and 1 == 1)
        for (i in 5 downTo 0) bits.add((cmd shr i) and 1 == 1)
        val l = ArrayList<Pair<Boolean, Int>>()
        for (b in bits) { l.add(Pair(!b, 1)); l.add(Pair(b, 1)) }       // 1: off then on, 0: on then off
        return halves(l, 889, 36000)
    }

    /** RC6 mode 0: a leader, the start bit, mode 000, the toggle bit (double width), 8 address and 8 command bits, 444 us halves. */
    fun rc6(addr: Int, cmd: Int, toggle: Boolean): Signal {
        val l = ArrayList<Pair<Boolean, Int>>()
        l.add(Pair(true, 6)); l.add(Pair(false, 2))
        fun at(b: Boolean, w: Int = 1) { l.add(Pair(b, w)); l.add(Pair(!b, w)) }       // 1: on then off, 0: off then on (the reverse of RC5)
        at(true)
        for (i in 0 until 3) at(false)
        at(toggle, 2)
        for (i in 7 downTo 0) at((addr shr i) and 1 == 1)
        for (i in 7 downTo 0) at((cmd shr i) and 1 == 1)
        return halves(l, 444, 36000)
    }

    /** RCA (some TCL TVs): address 4 bits, command 8, then both inverted, high bit first. */
    fun rca(addr: Int, cmd: Int): Signal {
        val a = addr and 0xF
        val c = cmd and 0xFF
        val word = (a shl 20) or (c shl 12) or ((a.inv() and 0xF) shl 8) or (c.inv() and 0xFF)
        val o = arrayListOf(4000, 4000)
        for (i in 23 downTo 0) { o.add(500); o.add(if ((word shr i) and 1 == 1) 2000 else 1000) }
        o.add(500)
        return Signal(38000, o.toIntArray())
    }

    /** A recorded signal as it is ("RAW <Hz> <on> <off> ..."): the maker's own code, no protocol we know. */
    fun raw(hz: Int, times: List<Int>): Signal? =
        if (hz !in 30000..60000 || times.size < 2 || times.any { it !in 50..60000 }) null else Signal(hz, times.toIntArray())
}
