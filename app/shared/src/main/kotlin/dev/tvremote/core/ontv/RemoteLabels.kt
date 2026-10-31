package dev.tvremote.core.ontv
import java.io.File
import java.io.IOException
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.manage.Manage

/**
 * The remote's side (phone, computer): runs [Meta] on the TV and answers from what it printed and drew. With a [cache] (one
 * folder per TV) the names and pictures are kept on the remote: the next time the list shows at once from them, and Meta runs
 * in the background only to catch apps installed or updated since. Without one, every opening waited for Meta and fetched
 * each picture again. [helper] gives assets/tvr-helper.jar: Meta and Caps, dexed for the TV (a few KB, made by :shared:tvHelper).
 */
class RemoteLabels(private val link: AdbLink, helper: () -> ByteArray, private val cache: File? = null) : Manage.Labels {
    private val jar by lazy(helper)
    @Volatile private var names: Map<String, String>? = null
    @Volatile private var own: Map<String, String> = emptyMap()      // the names given on TV Home win over the apps' own (a YouTube for Tizen renamed there)
    @Volatile private var ok = false
    @Volatile private var stamps: Map<String, String>? = null         // package -> "time size" of its PNG on the TV, once Meta has run
    private val running = Any()
    @Volatile private var failedAt = 0L                              // when Meta last gave nothing: asked again after RETRY, not on every app of the list

    /** Every app's display name, by package. */
    fun names(): Map<String, String> = system().let { if (own.isEmpty()) it else it + own }
    /** TV Home's names as they are now: after the page changed them, so nothing waits for the next run of Meta. */
    override fun own(m: Map<String, String>) { own = m }

    @Synchronized
    private fun system(): Map<String, String> {
        names?.let { return it }
        if (System.currentTimeMillis() - failedAt < RETRY) return emptyMap()
        val saved = load()
        if (saved != null) {
            names = saved
            ok = true
            Thread({ ask() }, "meta-refresh").apply { isDaemon = true }.start()
            return saved
        }
        return ask()
    }

    /** One run of Meta on the TV. */
    private fun ask(): Map<String, String> = synchronized(running) {
        val out = HashMap<String, String>()
        var why = ""
        try {
            val a = link.get()
            val remote = helper(a)
            val raw = a.shell("CLASSPATH=$remote app_process / dev.tvremote.core.Meta $DIR 2>&1")
            for (line in raw.split("\n")) {
                val tab = line.indexOf('\t')
                if (tab > 0 && line.substring(0, tab).matches(PKG)) out[line.substring(0, tab)] = line.substring(tab + 1).trim()
            }
            if (out.isEmpty()) why = "Meta printed no app names: " + raw.trim().take(300)
            if (out.isNotEmpty()) {
                val st = HashMap<String, String>()
                for (line in a.shell("cd $DIR && stat -c '%n %Y %s' *.png 2>/dev/null").split("\n")) {
                    val p = line.trim().split(" ")
                    if (p.size == 3 && p[0].endsWith(".png")) st[p[0].removeSuffix(".png")] = p[1] + " " + p[2]
                }
                stamps = st
                own = try { Home.ownNames { a.shell(it) } } catch (e: Exception) { own }
                ok = true
                names = out
                save(out)
            }
        } catch (e: Exception) {
            why = e.toString()
        }
        if (out.isEmpty()) {                                          // failed: package names are shown meanwhile, and it is tried again later
            failedAt = System.currentTimeMillis()                     // (caching the empty answer once left a computer app without names until restarted)
            System.err.println("RemoteLabels: no app names from the TV, trying again in ${RETRY / 1000} s: $why")
        } else failedAt = 0
        names ?: out
    }

    private fun helper(a: Adb): String = install(a, jar)

    /** What the TV's hardware can do ([Caps]), asked once per TV: it does not change. */
    @Volatile private var caps: String? = null
    override fun caps(): String? = caps ?: try {
        val a = link.get()
        val remote = synchronized(running) { helper(a) }
        a.shell("CLASSPATH=$remote app_process / dev.tvremote.core.Caps 2>/dev/null", 20000).takeIf { it.isNotBlank() }?.also { caps = it }
    } catch (e: IOException) { null }

    override fun label(pkg: String): String? = own[pkg] ?: system()[pkg]

    override fun icons(): Boolean { names(); return ok }

    /** The PNG [Meta] drew for this app, or null: the phone's copy while it is still the TV's, else fetched (and kept). */
    fun icon(pkg: String): ByteArray? {
        if (!ok || !pkg.matches(Regex("[\\w.]+"))) return null
        val file = cache?.let { File(it, "$pkg.png") }
        val stamp = cache?.let { File(it, "$pkg.stamp") }
        val st = stamps
        try {
            if (file != null && file.length() > 0 && (st == null || st[pkg] != null && stamp!!.readText() == st[pkg])) return file.readBytes()
        } catch (ignored: IOException) {
        }
        return try {
            val s = link.get().open("exec:cat $DIR/$pkg.png")
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(65536)
            s.use {
                while (true) {
                    val n = it.read(buf, 0, buf.size, 10000)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
            if (out.size() == 0) return null
            val png = out.toByteArray()
            if (file != null) try {
                file.parentFile?.mkdirs()
                file.writeBytes(png)
                stamp!!.writeText(st?.get(pkg) ?: "")
            } catch (ignored: IOException) {
            }
            png
        } catch (e: IOException) { null }
    }

    private fun load(): Map<String, String>? = try {
        val o = org.json.JSONObject(File(cache ?: return null, "names.json").readText())
        o.keys().asSequence().associateWith { o.getString(it) }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    private fun save(m: Map<String, String>) {
        val dir = cache ?: return
        try {
            dir.mkdirs()
            val tmp = File(dir, "names.json.tmp")
            tmp.writeText(org.json.JSONObject(m).toString())
            tmp.renameTo(File(dir, "names.json"))
        } catch (ignored: Exception) {
        }
    }

    companion object {
        /** tvr-helper.jar on the TV, copied there if it is not yet; one file per build, written whole and then renamed: see CLEAN. */
        @Synchronized fun install(a: Adb, bytes: ByteArray): String {
            val remote = "/data/local/tmp/tvr-helper-" + java.util.zip.CRC32().apply { update(bytes) }.value.toString(16) + ".jar"
            if (a.shell("[ -f $remote ] && echo y").trim() != "y") {
                a.shell(CLEAN)
                a.put("$remote.part", bytes)
                val size = bytes.size.toString()
                for (i in 0 until 40) {
                    if (a.shell("wc -c < $remote.part").trim() == size) break
                    Thread.sleep(150)
                }
                a.shell("mv $remote.part $remote")
            }
            return remote
        }


        // The computer and the phone app each bring their own build. With one shared name one of them overwrote it while another
        // started it: a crash on the TV each time. Builds older than a few days are cleared (tvr-meta-*.apk: the whole phone
        // APK, copied by versions before 2.20); one still in use stays readable to the process that has it open.
        private const val CLEAN = "find /data/local/tmp -maxdepth 1 \\( -name 'tvr-meta*.apk' -o -name 'tvr-helper-*.jar' \\) -mtime +3 -delete 2>/dev/null; rm -f /data/local/tmp/tvr-meta.apk"
        private val PKG = Regex("[\\w.]+")                                  // 2>&1: a crash on the TV is told apart from a name
        private const val RETRY = 15000L
        private const val DIR = "/data/local/tmp/tvr-meta"
    }
}
