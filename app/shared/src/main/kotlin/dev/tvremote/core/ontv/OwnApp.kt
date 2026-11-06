package dev.tvremote.core.ontv
import dev.tvremote.core.adb.Adb
import dev.tvremote.update.Release
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One of this project's own TV apps that is not TV Setup (TV Channels, TV Games), from the phone or the computer: whether it is on
 * the TV, and putting it there (or a newer one) from the latest release over ADB. The file is checked against the release's signed
 * SHA256SUMS ([Release.newer]). [versionKey] is its key in versions.json, [apk] its file in the release, [none] what [install]
 * throws when nothing can be installed (the page words it).
 *   state: {installed: false, latest} or {installed, version, latest (a newer one in the latest release, when there is one)};
 *   `soon: true` when it is not on the TV and the latest release does not carry it yet (said plainly, not an error).
 */
class OwnApp(val pkg: String, val apk: String, private val versionKey: String, private val none: String,
             /** Run on the TV after an install and, by the caller, before opening it (e.g. "display over other apps"). */
             val afterInstall: String = "") {
    private class Seen(val at: Long, val version: String, val release: Release?, val ok: Boolean, val err: Exception? = null)
    @Volatile private var seen: Seen? = null

    @Throws(IOException::class)
    fun state(sh: (String) -> String): JSONObject {
        val v = Regex("versionName=(\\S+)").find(sh("dumpsys package $pkg | grep -m1 versionName"))?.groupValues?.get(1)
        val o = JSONObject().put("installed", v != null)
        v?.let { o.put("version", it) }
        val s = look(v ?: "0")
        s.release?.let { o.put("latest", it.version) }
        if (v == null && s.ok && s.release == null) o.put("soon", true)
        if (!s.ok) o.put("offline", true)                              // GitHub could not be asked: the page says so and offers a retry, not a button that is just missing
        return o
    }

    /** The latest release's app when newer than [version] ("0": none on the TV), asked of GitHub at most every 6 hours. */
    private fun look(version: String): Seen {
        seen?.let { if (it.version == version && System.currentTimeMillis() - it.at < (if (it.ok) 6 * 3600_000L else 60_000L)) return it }   // offline: asked again in a minute, not hidden for 6 hours
        val s = try { Seen(System.currentTimeMillis(), version, Release.newer(version, versionKey, apk), true) }
                catch (e: Exception) { Seen(System.currentTimeMillis(), version, null, false, e) }   // offline: not "soon", just unknown
        seen = s
        return s
    }

    fun latest(version: String): Release? = look(version).release

    /**
     * Runs [start] (an `am start`); when the TV says the app is not there, brings it back for the user in front and tries once more.
     * The package can be on the TV yet closed to that user: turned off (Settings → Apps → Disable, a cleaner app) or installed for
     * another Google TV profile. [state] still finds its version, so the page said installed while the TV showed no icon and
     * Open failed (support 11223000). `install-existing` and `pm enable` change nothing on a TV where it was fine.
     */
    @Throws(IOException::class)
    fun open(sh: (String) -> String, start: String): Boolean {
        fun ok(out: String) = !out.contains("Error") && !out.contains("does not exist", true) && !out.contains("unable to resolve", true)
        if (ok(sh((if (afterInstall.isEmpty()) "" else "$afterInstall; ") + "$start 2>&1"))) return true
        sh(AppRestore.command(pkg))
        return ok(sh("$start 2>&1"))
    }

    /** Forget what GitHub said, so the next [state] asks again (TV Setup's own timer, which wants the news within hours, not 6). */
    fun refresh() { seen = null }

    /** Installs it, or puts the newer one over it (its own data stays). [go] false once downloaded: not now (it was opened meanwhile).
     *  [progress]: the download's percent, for a bar (TV Setup Voice is 25 MB: minutes on a slow line). */
    @Throws(IOException::class)
    fun install(adb: Adb, dir: File?, lang: String, go: () -> Boolean = { true }, progress: ((Int) -> Unit)? = null): JSONObject {
        val sh: (String) -> String = { adb.shell(it) }
        seen = null
        val now = state(sh)
        // GitHub out of reach is not "in the next release" (support 33220301: a TCL with broken DNS was told TV Apps comes later)
        if (now.optBoolean("offline") && !now.optBoolean("installed")) throw seen?.err?.let { it as? IOException ?: IOException(it) } ?: IOException("net: offline")
        val r = (if (now.has("latest")) seen?.release else null) ?: return now.also { if (!it.optBoolean("installed")) throw IOException(none) }
        val file = File(dir ?: File(System.getProperty("java.io.tmpdir")), apk.removeSuffix(".apk") + "-${r.version}.apk")
        PlayProtect.check(dir, r.sha256)                              // refused before: no new "Harmful app blocked" on the TV
        try {
            r.download(file, progress?.let { f -> dev.tvremote.update.Release.Progress { f(it) } })
            if (!go()) return now
            val out = Setup(adb, dir, lang) { }.install(file)
            PlayProtect.check(dir, r.sha256, out)
            if (!out.contains("Success")) throw IOException("launcher:install:" + out.trim().lines().lastOrNull().orEmpty().take(200))
            if (afterInstall.isNotEmpty()) sh(afterInstall)
        } finally { file.delete() }
        try { adb.shell("cmd package compile -m speed -f $pkg >/dev/null 2>&1", 120000) } catch (x: IOException) { }   // a cold start on a slow chip, as LauncherInstall
        seen = null
        return state(sh)
    }
}
