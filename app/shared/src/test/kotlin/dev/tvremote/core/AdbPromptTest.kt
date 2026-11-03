package dev.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import dev.tvremote.core.adb.AdbPrompt

/**
 * AdbPrompt.allow against a pretend TV that queues the "Allow USB debugging?" questions of several keys, as real TVs do: Allow
 * is only ever pressed on the question about the key asked for, never on a stranger's that takes its place, whether the TV
 * takes taps or only the arrow keys.
 */
class AdbPromptTest {
    private val ours = "AA:".repeat(15) + "AA"
    private val stranger = "BB:".repeat(15) + "BB"
    private val parts = mapOf("always" to (110 to 70), "cancel" to (350 to 120), "allow" to (550 to 120))
    private val order = listOf("always", "cancel", "allow")          // the arrow keys move through them in this order

    private class Q(val fp: String) { var always = false }

    /** [after]: called after each press or key, to make the screen change under the code (a question timing out...). */
    private inner class FakeTv(keys: List<String>, val taps: Boolean = true, val after: (String, FakeTv) -> Unit = { _, _ -> }) : AdbPrompt.Sh {
        val queue = keys.map { Q(it) }.toMutableList()
        var focus = "always"
        val answers = ArrayList<Pair<String, String>>()

        override fun shell(cmd: String, quietMs: Long): String {
            if (cmd.startsWith("dumpsys window")) return if (queue.isNotEmpty()) "mCurrentFocus=Window{1 u0 com.android.systemui/com.android.systemui.usb.UsbDebuggingActivity}" else "mCurrentFocus=Window{2 u0 launcher}"
            if (cmd.startsWith("uiautomator dump")) {
                val q = queue.firstOrNull() ?: return "<hierarchy></hierarchy>"
                fun node(part: String, id: String, cls: String, extra: String = ""): String {
                    val (x, y) = parts.getValue(part)
                    return "<node resource-id=\"$id\" class=\"$cls\" focused=\"${focus == part}\" $extra bounds=\"[${x - 10},${y - 10}][${x + 10},${y + 10}]\" />"
                }
                return "<hierarchy><node text=\"The computer's RSA key fingerprint is:&#10;${q.fp}\" bounds=\"[0,0][100,40]\" />" +
                    node("always", "com.android.systemui:id/alwaysUse", "android.widget.CheckBox", "checked=\"${q.always}\"") +
                    node("cancel", "android:id/button2", "android.widget.Button") + node("allow", "android:id/button1", "android.widget.Button") + "</hierarchy>"
            }
            Regex("input tap (\\d+) (\\d+)").matchEntire(cmd)?.let { m ->
                val (x, y) = m.groupValues.drop(1).map { it.toInt() }
                if (taps) parts.entries.firstOrNull { Math.abs(it.value.first - x) <= 10 && Math.abs(it.value.second - y) <= 10 }?.let { press(it.key) }
                return ""
            }
            Regex("input keyevent (\\d+)").matchEntire(cmd)?.let { m ->
                if (queue.isEmpty()) return ""
                val i = order.indexOf(focus)
                when (m.groupValues[1].toInt()) {
                    23 -> press(focus)
                    20, 22 -> { focus = order[minOf(i + 1, 2)]; after("move", this) }
                    19, 21 -> focus = order[maxOf(i - 1, 0)]
                }
            }
            return ""
        }

        fun press(part: String) {
            val q = queue.firstOrNull() ?: return
            if (part == "always") q.always = !q.always
            else { answers.add(q.fp to if (part == "allow") "allowed" else "declined"); queue.removeAt(0); focus = "always" }
            after(part, this)
        }
    }

    private fun run(tv: FakeTv, others: Boolean = false) = AdbPrompt.allow(tv, ours, 0, others) { }

    /** Our question goes away right after "Always" is ticked; the TV shows the next key's question in its place. */
    private val vanish: (String, FakeTv) -> Unit = { part, tv -> if (part == "always" && tv.queue.firstOrNull()?.fp == ours) tv.queue.removeAt(0) }

    @Test fun ours() {
        val tv = FakeTv(listOf(ours))
        assertEquals("allowed", run(tv))
        assertEquals(listOf(ours to "allowed"), tv.answers)
    }

    @Test fun oursWithTheArrowKeys() {
        val tv = FakeTv(listOf(ours), taps = false)
        assertEquals("allowed", run(tv))
        assertEquals(listOf(ours to "allowed"), tv.answers)
    }

    /** Android 12: copies of our question queued, then a stranger's; ours are closed, the stranger's is never touched. */
    @Test fun leftoverCopiesOfOursAreClosedAndAStrangerIsNot() {
        val tv = FakeTv(listOf(ours, ours, ours, stranger))
        assertEquals(3, AdbPrompt.leftovers(tv, ours) { })
        assertEquals(List(3) { ours to "declined" }, tv.answers)
        assertEquals(listOf(stranger), tv.queue.map { it.fp })
        assertEquals(0, AdbPrompt.leftovers(FakeTv(listOf()), ours) { })
    }

    @Test fun aStrangerFirstIsLeftAlone() {
        val tv = FakeTv(listOf(stranger, ours))
        assertEquals("other:$stranger", run(tv))
        assertEquals(emptyList<Pair<String, String>>(), tv.answers)
    }

    @Test fun aStrangerFirstIsDeclinedWhenAsked() {
        val tv = FakeTv(listOf(stranger, ours))
        assertEquals("allowed", run(tv, others = true))
        assertEquals(listOf(stranger to "declined", ours to "allowed"), tv.answers)
    }

    @Test fun aStrangerTakingOurPlaceIsNeverAllowed() {
        for (taps in listOf(true, false)) for (others in listOf(false, true)) {
            val tv = FakeTv(listOf(ours, stranger), taps, vanish)
            run(tv, others)
            assertFalse("taps=$taps others=$others", tv.answers.contains(stranger to "allowed"))
        }
    }

    @Test fun aStrangerAppearingWhileTheKeysMoveIsNeverAllowed() {
        // ours goes away after the first arrow key toward Allow, and the stranger's box opens with Allow in focus
        val tv = FakeTv(listOf(ours, stranger), taps = false) { part, tv ->
            if (part == "move" && tv.queue.firstOrNull()?.let { it.fp == ours && it.always } == true) { tv.queue.removeAt(0); tv.focus = "allow" }
        }
        run(tv)
        assertFalse(tv.answers.contains(stranger to "allowed"))
    }

    /** The person unticked "Always allow" for another device's question: the tick-box is never touched, Allow is pressed once. */
    @Test fun aKeyAllowedThisTimeOnlyLeavesTheTickBoxAlone() {
        var ticks = 0
        val tv = FakeTv(listOf(stranger)) { part, _ -> if (part == "always") ticks++ }
        assertEquals("allowed", AdbPrompt.allow(tv, stranger, 0, false, false, { }))
        assertEquals(0, ticks)
        assertEquals(listOf(stranger to "allowed"), tv.answers)
    }
}
