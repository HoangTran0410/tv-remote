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
    // and what may run: commands that print, never set. dumpsys / cmd forms that act are named below.
    private val READ = listOf(
        Regex("getprop( [\\w.-]+)?"),
        Regex("settings (get (global|secure|system) [\\w.-]+|list (global|secure|system))"),
        Regex("dumpsys( -t \\d{1,3})? (-l|[a-zA-Z][\\w./:-]*)( [\\w./:-]+)*"),   // a service is named: not the whole dump
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
    private val ACTS = Regex("\\b(set|reset|unplug|enable|disable|force-idle|force-inactive|step|clear|kill|run|put|delete|write|unforce|--reset|start|stop|add|remove)\\b")

    // services whose dump carries a person's own words or accounts: never asked from afar
    private val PRIVATE = Regex("(?<![\\w.-])(clipboard|notification|account|telecom|content|sms|contacts|private)(?![\\w.-])")   // as a word anywhere: after -t N or extra spaces too

    fun allowed(cmd: String): Boolean = cmd.length <= 200 && !SYNTAX.containsMatchIn(cmd) && READ.any { it.matches(cmd) } &&
        !(cmd.startsWith("dumpsys") && (ACTS.containsMatchIn(cmd) || PRIVATE.containsMatchIn(cmd)))

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
