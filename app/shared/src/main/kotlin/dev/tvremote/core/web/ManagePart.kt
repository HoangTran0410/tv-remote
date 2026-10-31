package dev.tvremote.core.web
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.adb.Pkg
import org.json.JSONObject
import java.io.IOException
import java.util.regex.Pattern

/** What every part of [Manage] shares: the TV's link, the names of its apps, this app's own folder and the jobs the page polls. */
internal class ManageCore(val link: AdbLink, val labels: Manage.Labels?, val dir: java.io.File?) {
    val jobs = Jobs()

    @Throws(IOException::class)
    fun sh(cmd: String): String = link.get().shell(cmd)

    /** What a [dev.tvremote.core.adb.TvShell] recipe printed, for the page to read. */
    fun text(cmd: String, quietMs: Long): HttpServer.Response =
        HttpServer.Response.json(JSONObject().put("text", link.get().shell(cmd, quietMs).replace("\r\n", "\n")))

    /** A command's output to the browser. Opened only once the reply has started, so a browser that left never leaves it open. */
    fun pipe(s: Adb.Stream, o: java.io.OutputStream) {
        try {
            val b = ByteArray(65536)
            while (true) {
                val n = s.read(b, 0, b.size, 0)
                if (n < 0) break
                o.write(b, 0, n)
            }
        } finally {
            s.close()
        }
    }
}

/**
 * One kind of work of [Manage] (apps, files, installs, health, TV Setup, the project's TV apps), with its own `/api` routes.
 * [route] takes the path after `/api/`; null when it is not one of this part's. Errors are turned into answers by [Manage.handle].
 */
internal abstract class ManagePart(val core: ManageCore) {
    val link = core.link
    val labels = core.labels
    val dir = core.dir
    val current: ThreadLocal<Cancel?> get() = core.jobs.current
    val stops get() = core.jobs.stops

    @Throws(IOException::class)
    fun sh(cmd: String): String = core.sh(cmd)
    fun text(cmd: String, quietMs: Long): HttpServer.Response = core.text(cmd, quietMs)
    fun pipe(s: Adb.Stream, o: java.io.OutputStream) = core.pipe(s, o)
    inline fun <T> at(what: String, f: () -> T): T = core.jobs.at(what, f)
    fun job(title: String, kind: String? = null, name: String? = null, work: (JSONObject) -> String): JSONObject = core.jobs.job(title, kind, name, work)

    abstract fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response?

    companion object {
        val PKG: Pattern = Pattern.compile(Pkg.PATTERN)
        val DIGITS = Regex("\\d+")
    }
}

/** Java's String.split: trailing empty strings are dropped unless a limit is given. The parsing of the TV's answers counts on it. */
internal fun String.jsplit(regex: String, limit: Int = 0): Array<String> = Pattern.compile(regex).split(this, limit)
