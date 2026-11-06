package dev.tvremote.setup.rules

/**
 * "Turn off when nobody uses it": after N hours (1-4) without a key, a minute's "Still watching?" ([WarnActivity] "idle"), then
 * standby; any key cancels. Pure decisions here, the reading and the standby in [Guard].
 *
 * Why not Android's own attentive timeout (Settings.Secure attentive_timeout, Android 10+): measured on the API 34 Google TV
 * emulator, the setting is taken (`dumpsys power`: mAttentiveTimeoutSetting) but the TV went to sleep with no warning on screen
 * and the stay-awake-while-plugged-in flag decides whether it acts at all. One path that behaves the same on every TV, with
 * our own warning, is easier to trust than a framework one that OEMs may strip. So nothing of the system's is touched.
 *
 * The idle time is the framework's own "last user activity" (`dumpsys power`, one 47-byte line, ~1 s over ADB). Reading is
 * scheduled for the moment the deadline would pass if nothing had been pressed: activity only moves the deadline later, so a
 * read before that moment could never change the answer. That is one read per N hours, none with the option off.
 * Playing video is NOT skipped: a movie nobody touches for the whole span is exactly "fell asleep in front of the TV";
 * the page says it counts keys, and a key during the warning keeps the TV on.
 */
internal object IdleOff {
    const val WARN_MS = 60_000L
    /** A key pressed this long after the warning began counts as an answer (starting the warning screen may itself touch the activity clock). */
    const val SLACK_MS = 3_000L
    val HOURS = 0..4

    /** What the look decided: [next] is ms until the next worthwhile look, [warnedAt] the mark to keep (elapsed ms, 0 = none). */
    data class Step(val act: Act, val warnedAt: Long, val next: Long)
    enum class Act { NONE, WARN, STANDBY, CANCELLED }

    /**
     * [hours] 0 = off. [agoMs] = time since the last key (null: could not be read). [warnedAt] = when the warning began, in the same
     * clock as [now] (0 = no warning yet).
     */
    fun step(hours: Int, agoMs: Long?, warnedAt: Long, now: Long): Step {
        if (hours <= 0) return Step(Act.NONE, 0L, 0L)
        if (agoMs == null) return Step(Act.NONE, warnedAt, 10 * 60_000L)           // unreadable: try again later, never act blind
        val limit = hours * 3_600_000L
        if (warnedAt != 0L && now - warnedAt > 5 * WARN_MS) return step(hours, agoMs, 0L, now)   // a warning that could not be followed up (no ADB): a fresh one, never an instant standby
        if (warnedAt == 0L) {
            if (agoMs < limit) return Step(Act.NONE, 0L, limit - agoMs)
            return Step(Act.WARN, now, 5_000L)
        }
        val since = now - warnedAt
        if (agoMs + SLACK_MS < since) return Step(Act.CANCELLED, 0L, limit - agoMs)   // a key came after the warning began
        if (since >= WARN_MS) return Step(Act.STANDBY, 0L, 0L)
        return Step(Act.NONE, warnedAt, minOf(5_000L, WARN_MS - since))
    }

    private val AGO = Regex("""\blastUserActivityTime=\d+ \((\d+) ms ago\)""")

    /** The idle ms from `dumpsys power` text (null when this Android words it differently). */
    fun parseAgo(dump: String?): Long? = dump?.let { AGO.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** The stored hours, cleaned: only 0-4 are real. */
    fun hours(o: org.json.JSONObject): Int = o.optInt("idle_h").takeIf { it in 1..4 } ?: 0
}
