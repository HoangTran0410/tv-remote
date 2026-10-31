package dev.tvremote.phone.scenes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Calendar

/** sleepMinutes reads each text as tests/sleep_cases.json says, the table sleep.test.js reads for auto.js too. */
class SleepTest {
    private val table = JSONObject(File("../shared/tests/sleep_cases.json").readText())

    @Test fun table() {
        val (h, m) = table.getString("now").split(":").map { it.toInt() }
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 24, h, m, 0); set(Calendar.MILLISECOND, 0) }
        val all = table.getJSONArray("cases")
        for (i in 0 until all.length()) {
            val c = all.getJSONObject(i)
            assertEquals(c.getString("in"), c.getInt("min"), sleepMinutes(c.getString("in"), now))
        }
    }
}
