package dev.tvremote.setup.health

import android.content.Context
import dev.tvremote.core.Scrub
import dev.tvremote.core.SupportAndroid
import dev.tvremote.core.SupportRelay
import dev.tvremote.setup.RemoteService

/**
 * What support reads on the TV: [prefs] and [logcat] (SupportAndroid, the same as the Phone app's; KeyFix, AutoOpen ... say what
 * they did there), and [shell]: while support looks, any command that only reads, over this app's ADB.
 */
object SupportDump {
    fun prefs(c: Context): String = SupportAndroid.prefs(c)

    fun logcat(): String = SupportAndroid.logcat()

    // one command, no shell syntax (no ; | & > < ` $ quotes or line breaks): the only way to chain or write is ruled out first
    private val SYNTAX = Regex("[;|&><`$\\\\'\"\\n\\r(){}]")
    // and what may run: commands that print, never set (dumpsys: [DUMPSYS], its own list)
    private val READ = listOf(
        Regex("getprop( [\\w.-]+)?"),
        Regex("settings (get (global|secure|system) [\\w.-]+|list (global|secure|system))"),
        Regex("dumpsys -l"),
        Regex("pm (list (packages|permissions|features|users|libraries)( -[a-zA-Z]+)*( [\\w.-]+)?|path [\\w.]+|dump [\\w.]+)"),
        Regex("appops get [\\w.]+( [\\w_:]+)?"),
        Regex("cmd package (resolve-activity|query-activities|query-services|query-receivers|list packages)( [\\w.:/=-]+)*"),
        Regex("logcat -d( -[tsv] [\\w:*.]+| -b \\w+| [\\w]+:[VDIWEFS])*"),
        Regex("(uptime|df( -h)?|ps -A( -o [\\w,]+)?|top -b -n 1( -m \\d+)?|wm (size|density)|id|date)"),
        // a remote's keys (a Chromecast with a TIMvision remote, 2026-10-26): the system's key maps, which keys each remote declares
        // (-p prints and ends), and the next presses while the person tries a button (timeout ends it if they press nothing)
        Regex("(cat|ls( -l)?) /(system|vendor|product|odm)/usr/(keylayout|keychars|idc)(/[\\w-][\\w.-]*)?"),
        Regex("getevent -(?=[lip]*p)[lip]+"),
        Regex("timeout ([1-9]|1\\d|20) getevent -lt? -c \\d{1,3}")
    )
    /**
     * dumpsys: only these services, each with the arguments it may take (a fixed list, not a list of what is refused: some services
     * act on words nobody thought of, `battery suspend_input`, `deviceidle tempwhitelist`). What the owner's support has needed: the
     * box's memory, CPU, heat and power; what is on screen and which apps / services run; packages, the home role, the key filter
     * (accessibility, input); sound, HDMI inputs and the network link. Left out on purpose, their dumps carry the person's own life:
     * wifi (saved networks), location, usagestats / batterystats (app use), media_session (what is being watched), notification,
     * clipboard, account, dropbox (other apps' crash texts).
     */
    private const val PKG = "( [\\w.-]+)?"
    private val DUMPSYS = mapOf(
        "battery" to "", "cpuinfo" to "", "thermalservice" to "", "power" to "", "display" to "", "deviceidle" to "",
        "meminfo" to "( -[a-z]+)*$PKG", "gfxinfo" to PKG, "package" to PKG, "role" to "", "accessibility" to "", "input" to "",
        "input_method" to "", "audio" to "", "tv_input" to "", "hdmi_control" to "", "connectivity" to "", "alarm" to "", "jobscheduler" to "",
        "activity" to "( (activities|top|recents|processes|broadcasts|intents|providers|lru|oom|starter|containers)|( services| p)$PKG)?",
        "window" to "( (windows|displays|policy|tokens|input|lastanr|visible-apps))?",
    )
    private val DUMP = Regex("dumpsys( -t \\d{1,3})? ([a-z_]+)(.*)")

    private fun dumpOk(cmd: String): Boolean {
        val m = DUMP.matchEntire(cmd) ?: return false
        val args = DUMPSYS[m.groupValues[2]] ?: return false
        return Regex(args).matches(m.groupValues[3])
    }

    fun allowed(cmd: String): Boolean = cmd.length <= 200 && !SYNTAX.containsMatchIn(cmd) &&
        (READ.any { it.matches(cmd) } || dumpOk(cmd))

    /** [cmd] when [allowed], over this app's own ADB link; (status, output). */
    fun shell(cmd: String): Pair<Int, String> {
        val c = cmd.trim().replace(Regex("\\s+"), " ")
        if (!allowed(c)) return 403 to "not a read-only command: $c"
        val adb = RemoteService.adb() ?: return 503 to "no ADB on this TV (state ${RemoteService.adbState()})"
        // Scrub.text: emails and addresses out too, a dump may name the person's account
        // cut to what the worker takes (a whole logcat is megabytes: refused, the owner got "answer not delivered"); a log keeps its end
        return try { 200 to SupportRelay.fit(Scrub.text(adb.shell("$c 2>&1", 25000)), SupportRelay.MAX_ANSWER, tail = c.startsWith("logcat")) } catch (e: Exception) { 0 to (e.message ?: e.javaClass.simpleName) }
    }
}
