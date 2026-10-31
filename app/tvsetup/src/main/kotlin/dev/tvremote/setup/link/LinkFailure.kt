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

    private val REFUSED = Regex("ECONNREFUSED|Connection refused", RegexOption.IGNORE_CASE)

    /** [key] "closed" or "error" from the session, its raw [detail], the [wait] now, and [say] turning raw text into words. */
    fun of(key: String, detail: String, wait: Long, say: (String) -> String): Outcome {
        if (key == "error" && detail.contains(Adb.UNANSWERED)) return Outcome("allow", "", wait, true)
        val refused = key == "error" && REFUSED.containsMatchIn(detail)
        // refused already has its plain words; the raw socket text only scared. Anything else: words a person can act on
        val words = if (key == "error" && !refused) say(detail.take(200)).take(200) else ""
        val state = if (key == "error" && !refused) "error" else if (refused) "refused" else "connecting"
        return Outcome(state, words, wait, true)
    }
}
