package dev.tvremote.phone.ir

// "Học phím": a box with no set in Flipper-IRDB (an FPT Play: Realtek, its keymap is inside the kernel and its .kl is unreadable to ADB's shell)
// still tells, through `getevent`, the scancode (MSC_SCAN) of every key of its own remote. The user presses each key once; this turns the
// scancodes into a code set. Plain Kotlin, so IrLearnTest checks that every code we build is read back by IrEvents.scans as the same scancode.

internal object IrLearn {
    /** The keys asked for, in this order: what a box is driven with first. */
    val KEYS = listOf("HOME", "BACK", "UP", "DOWN", "LEFT", "RIGHT", "OK", "MENU", "VOL_UP", "VOL_DOWN", "MUTE", "POWER")   // Power last: it puts the box in standby (its IR eye still hears the rest, but people think it broke)

    /** Fewest keys worth keeping a set for. */
    const val MIN = 4

    private fun hex(v: Int) = Integer.toHexString(v and 0xFF).uppercase()

    /** One way of reading a scancode: its (address, command) as a code, or null when the scancode does not fit this reading. */
    private class Reading(val name: String, val of: (Long) -> Pair<String, String>?)

    private fun ok(a: Int, na: Int) = (a xor na) and 0xFF == 0xFF

    private val READINGS = listOf(
        // the 32 bits as the NEC frame sends them (address, ~address, command, ~command from the low byte)
        Reading("wire") { s -> if (s > 0xFFFFFFFFL) null else {
            val b = (0..3).map { ((s shr (8 * it)) and 0xFF).toInt() }
            if (ok(b[0], b[1]) && ok(b[2], b[3])) Pair(hex(b[0]), hex(b[2])) else null } },
        // rc-core's NEC32: the same bytes, the other way round
        Reading("rc32") { s -> if (s > 0xFFFFFFFFL) null else {
            val b = (0..3).map { ((s shr (8 * it)) and 0xFF).toInt() }
            if (ok(b[2], b[3]) && ok(b[0], b[1])) Pair(hex(b[3]), hex(b[1])) else null } },
        // rc-core's NEC: address << 8 | command
        Reading("nec16") { s -> if (s > 0xFFFFL) null else Pair(hex((s shr 8).toInt()), hex(s.toInt())) },
    )

    /**
     * The profile's keys from the scancodes the box reported ([scans]: our key name -> scancode). The reading must fit every key, and the
     * address must be the same for all of them (one remote, one address); of the readings that do, the first. Null: nothing fits,
     * fewer than [MIN] keys or two keys with one scancode.
     */
    fun build(scans: Map<String, Long>): Map<String, String>? {
        if (scans.size < MIN || scans.values.toSet().size != scans.size) return null
        for (r in READINGS) {
            val parts = scans.mapValues { r.of(it.value) }
            if (parts.values.any { it == null }) continue
            if (parts.values.map { it!!.first }.toSet().size != 1) continue
            if (parts.values.map { it!!.second }.toSet().size != parts.size) continue
            val keys = parts.mapValues { "NEC " + it.value!!.first + " " + it.value!!.second }
            // the check that matters: the box would report this very scancode for the code we send
            if (keys.all { (k, c) -> IrEvents.scans(c)?.contains(scans.getValue(k)) == true }) return keys
        }
        return null
    }
}
