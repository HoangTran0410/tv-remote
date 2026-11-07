package dev.tvremote.setup.link

import dev.tvremote.core.adb.Adb

/**
 * What TV Setup's own ADB link says about a failed session, in one place (RemoteService.onStatus reads it): the state the page
 * shows, the words under it, and how soon the next try comes. Census 2026-10: 80 of 213 "TV Setup ADB" reports were the
 * "Allow USB debugging?" question left unanswered, half of them filed as "error: timeout" (the question's 3 minutes ran out),
 * which showed raw "timeout" on the page and "turn on ADB" on the TV. The tries keep their backoff: the question pops over whatever
 * is playing, so asking every few seconds would nag a room nobody is in; opening TV Setup (or any use) asks again at once.
 */
internal object LinkFailure {
    class Outcome(val state: String, val detail: String, val retryMs: Long, val backoff: Boolean)

    /** Unanswered "Allow USB debugging?" questions in a row before the timed retries stop (RemoteService). */
    const val ASK_LIMIT = 3

    private val TIMEOUT = Regex("SocketTimeout|timed out", RegexOption.IGNORE_CASE)
    private val REFUSED = Regex("ECONNREFUSED|Connection refused", RegexOption.IGNORE_CASE)

    /** [key] "closed" or "error" from the session, its raw [detail], the [wait] now, and [say] turning raw text into words; on a
     *  [maker]'s TV that blocks its own ADB ([fireBlocked]) the words are [fire]'s; a timeout has [silent]'s. */
    fun of(key: String, detail: String, wait: Long, say: (String) -> String, maker: String = "", fire: () -> String = { "" },
           silent: () -> String = { "" }): Outcome {
        if (key == "error" && detail.contains(Adb.UNANSWERED)) return Outcome("allow", "", wait, true)
        if (key == "error" && fireBlocked(maker, detail)) return Outcome("error", fire(), wait, true)
        // a read timeout on 127.0.0.1 is never "the network is slow": the TV's own adbd did not answer ([silent]'s words)
        if (key == "error" && TIMEOUT.containsMatchIn(detail)) return Outcome("error", silent(), wait, true)
        val refused = key == "error" && REFUSED.containsMatchIn(detail)
        // refused already has its plain words; the raw socket text only scared. Anything else: words a person can act on
        val words = if (key == "error" && !refused) say(detail.take(200)).take(200) else ""
        val state = if (key == "error" && !refused) "error" else if (refused) "refused" else "connecting"
        return Outcome(state, words, wait, true)
    }

    /**
     * Fire TV: Amazon blocks an app on the TV from its own ADB since February 2024 (aftvnews.com/?p=7897): adbd takes the connection
     * from 127.0.0.1 and closes it, an EOF (census AFTR, AFTSSS, 2026-11). Nothing on the TV turns it back on, so "turn on ADB" sent
     * people in circles; a phone or computer over Wi-Fi still gets in.
     */
    fun fireBlocked(maker: String, detail: String): Boolean =
        maker.equals("Amazon", ignoreCase = true) && (detail.contains("EOFException") || detail.contains("stream closed"))

    /**
     * What the TV shows (word ids) when ADB debugging is on yet port 5555 refuses. Android 11+ with Wireless debugging off is the
     * Chromecast with Google TV (20 of ~70 census reports, 2026-10): it never opens 5555, so "unplug it" led nowhere; the way in is
     * the Wireless debugging switch and a code, once (not "Reconnect": nothing opens 5555 before the pairing). Older Android has no such switch: only the plain note.
     */
    fun noPortHowto(wifiOn: Boolean, sdk: Int, opening: Boolean = false, shield: Boolean = false): List<String> = when {   // opening: the page opens now, its line says where the switch sits
        shield -> listOf("tv_main.shield_adb")                       // its own "Network debugging" switch opens 5555 (census: refused on 3 SHIELDs)
        sdk >= 30 && !wifiOn && opening -> listOf("tv_main.opening_wireless_debugging", "tv_main.adb_wifi_only_pair", "tv_main.adb_wifi_only_again")
        sdk >= 30 && wifiOn -> listOf("tv_main.adb_wifi_only_howto", "tv_main.adb_wifi_only_pair", "tv_main.adb_wifi_only_again")
        sdk >= 30 -> listOf("tv_main.adb_wifi_off_howto", "tv_main.adb_wifi_only_pair", "tv_main.adb_wifi_only_again")   // three lines, as the switch-on case: a fourth pushed the first out of the log box
        else -> listOf("tv_main.adb_no_port_howto")
    }

    /** One Settings screen to try: [cls] in [pkg], or Developer options with the [fragment] extra when [cls] is empty. */
    data class Screen(val pkg: String, val cls: String, val fragment: String = "")

    /**
     * The Wireless debugging page, in the order to try (the caller keeps the first that opens and stays in front; none or all
     * failing = Developer options, then the words only). Android 11+ only: no such switch before. The names are AOSP's; a TV
     * vendor's Settings may lack them, so each is a try, never a promise.
     */
    fun wirelessScreens(sdk: Int): List<Screen> = if (sdk < 30) emptyList() else listOf(
        Screen("com.android.settings", "com.android.settings.Settings\$WirelessDebuggingActivity"),
        Screen("com.android.settings", "", "com.android.settings.development.WirelessDebuggingFragment"))

    /** Open the page by itself, once, only where the words say to go there (Wireless debugging off, Android 11+). */
    fun opensWireless(wifiOn: Boolean, sdk: Int, shield: Boolean = false): Boolean = !shield && sdk >= 30 && !wifiOn

    /**
     * ADB is on, port 5555 open, yet "Allow USB debugging?" ran out of time: on some TVs it never shows (a Changhong AI PONT,
     * Android 11: 3 installs, census 828f06d1). Android 11+ has a way with no question: a phone or computer pairs through
     * Wireless debugging, then answers TV Setup's question itself (AdbPrompt). Word ids, added under "tick Always allow".
     */
    fun unansweredHowto(sdk: Int, wifiOn: Boolean): List<String> = when {
        sdk < 30 -> emptyList()
        wifiOn -> listOf("tv_main.allow_not_shown", "tv_main.adb_wifi_only_howto")
        else -> listOf("tv_main.allow_not_shown", "tv_main.adb_wifi_off_howto")
    }

    /** An NVIDIA SHIELD: 5555 is its "Network debugging" switch in Developer options, beside USB debugging. */
    fun shield(maker: String, model: String): Boolean = maker.equals("NVIDIA", ignoreCase = true) || model.contains("SHIELD", ignoreCase = true)
}
