package dev.tvremote.core

/**
 * What an error report must not carry, taken out BEFORE it leaves the device (Census.crash). The page has the same function, in the
 * same order, as js/scrub.js; tests/scrub_cases.json is the table both are held to (ScrubTest.kt, tests/scrub.test.js).
 * Out: keys, tokens, passwords and MACs (WireLog.blank), `?k=` / `pass=` in a link, emails, the person's folder name in a path
 * (/Users/<name>, /home/<name>, C:\Users\<name> -> ~), IPv4 and IPv6 addresses. Not done: the names a person gave their TVs (not
 * known here; they are not in a stack, only possibly in a message). Running it twice changes nothing more.
 */
object Scrub {
    private const val H = "[0-9a-fA-F]{1,4}"
    private const val OCT = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
    private val SECRET = Regex("(\"(?:client-key|token)\"\\s*:\\s*\")[^\"]*(\")|([?&]token=)[^&\\s\"]*|(?<=/bot)\\d+:[A-Za-z0-9_-]+")
    private val TYPED = Regex("(\"(?:text|Cmd)\"\\s*:\\s*\")([^\"]*)(\")")
    private val KEEP = setOf("Click", "Press", "Release")
    private val MAC = Regex("\\b[0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5}\\b")
    // A Wi-Fi code's text and the card that makes it carry the network's password: never into a log
    private val WIFI = Regex("WIFI:.*?(?<!\\\\);;|(\"pass\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*(\")")
    private val TVPASS = Regex("(\"(?:setup_pass|relay_\\w+)\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*(\")")   // a saved TV's web-page pass and SSH relay (tvs.json)
    private val KEYQ =Regex("([?&](?:k|key|pass|password|pwd|token)=)[^&\\s\"')]*")
    private val BEARER = Regex("(Bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val WINHOME = Regex("\\b[A-Za-z]:[\\\\/]+Users[\\\\/]+[^\\\\/\\s:*?\"<>|)']+")
    private val UNIXHOME = Regex("(^|[^\\w.-])/(?:Users|home)/[^/\\s:)'\"]+")
    private val IPV4 = Regex("\\b(?:$OCT\\.){3}$OCT\\b")
    private val IPV6 = Regex("(^|[^\\w:])((?:$H:){7}$H|(?:$H:){1,6}:(?:$H(?::$H){0,5})?|::(?:$H(?::$H){0,6})?)(?![\\w:])")

    /** Keys, tokens, typed text (its length only), MAC addresses and Wi-Fi passwords blanked; line breaks and length kept (WireLog.blank is this). */
    fun blank(text: String): String =
        SECRET.replace(text) { m -> if (m.groupValues[3].isNotEmpty()) m.groupValues[3] + "…" else m.groupValues[1] + "…" + m.groupValues[2] }
            .let { TYPED.replace(it) { m -> if (m.groupValues[2] in KEEP) m.value else m.groupValues[1] + "…(" + m.groupValues[2].length + ")" + m.groupValues[3] } }
            .let { MAC.replace(it, "xx:xx:xx:xx:xx:xx") }
            .let { WIFI.replace(it) { m -> if (m.groupValues[1].isNotEmpty()) m.groupValues[1] + "…" + m.groupValues[2] else "WIFI:…" } }
            .let { TVPASS.replace(it) { m -> m.groupValues[1] + "…" + m.groupValues[2] } }

    /**
     * [blank], plus links' keys, emails, home folders and addresses. [keepIps]: the support report (only the project's owner reads it,
     * in a session the person opened) keeps the IP addresses, which are what a network fault is found by; the rest goes as here.
     * The cases table and js/scrub.js know only the default.
     */
    fun text(s: String, keepIps: Boolean = false): String = blank(s)
        .let { KEYQ.replace(it) { m -> m.groupValues[1] + "…" } }
        .let { BEARER.replace(it) { m -> m.groupValues[1] + "…" } }
        .let { EMAIL.replace(it, "email@x") }
        .let { WINHOME.replace(it, "~") }
        .let { UNIXHOME.replace(it) { m -> m.groupValues[1] + "~" } }
        .let { if (keepIps) it else IPV4.replace(it, "x.x.x.x") }
        .let { if (keepIps) it else IPV6.replace(it) { m -> m.groupValues[1] + "x.x.x.x" } }
}
