package dev.tvremote.core.web
import dev.tvremote.core.B64
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.PmInstall
import dev.tvremote.core.adb.TvShell
import dev.tvremote.core.web.TvPaths.TMP_APK
import dev.tvremote.core.web.TvPaths.safeName
import dev.tvremote.core.web.TvPaths.safePath
import dev.tvremote.update.Release
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.regex.Pattern
import org.json.JSONObject

/** APKs onto the TV: from this device, from a link, a copy beside a built-in app, a backup put back; checked before pm sees them. */
internal class InstallApi(core: ManageCore, private val copy: TvCopy) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? {
        if (!post) return null
        return when (what) {
            "file/install" -> HttpServer.Response.json(installPath(safePath(r.json().optString("path"))))
            "fetch" -> HttpServer.Response.json(fetch(r.json().optString("url"), false))
            "install" -> HttpServer.Response.json(fetch(r.json().optString("url"), true))
            else -> null
        }
    }

    @Throws(IOException::class)
    private fun pmInstall(path: String): String {
        val adb = link.get()
        val q = Adb.q(path)
        if (!adb.shell("head -c 2 $q 2>/dev/null").startsWith("PK")) throw IOException("not an APK file")
        val size = adb.shell("stat -c %s $q").trim()
        val info = try { Apk.info(B64.decode(adb.shell("unzip -p $q AndroidManifest.xml 2>/dev/null | base64"))) }
                   catch (e: IllegalArgumentException) { emptyMap() }
        val pkg = info["package"]
        // said before installing, in words a person can act on, rather than pm's INSTALL_FAILED_... after it
        val tv = adb.shell("getprop ro.build.version.sdk; getprop ro.product.cpu.abilist").split("\n") + listOf("", "")
        val abis = Apk.abis(adb.shell("unzip -l $q 2>/dev/null | grep -o 'lib/[^ ]*\\.so' || true").split(Regex("\\s+")))
        Apk.fits(info, abis, tv[0].trim().toIntOrNull() ?: 0, tv[1].trim().split(",").filter { it.isNotEmpty() })?.let { throw IOException(it) }
        val cancel = current.get()
        cancel?.check()
        val s = adb.exec("cat $q | pm install -r -S $size 2>&1")
        cancel?.onStop { s.close() }                                  // closing the stream ends pm, which drops its install session
        var out = try { s.readAll(600000) } finally { s.close() }
        cancel?.check()
        out = PmInstall.fallback(out, { adb.shell(it, 600000) }, q, size.toLongOrNull() ?: 0) { cancel?.check() }   // firmware that refuses pm install: a session instead
        cancel?.check()
        if (!out.contains("Success") && pkg != null && Regex("INSTALL_FAILED_VERSION_DOWNGRADE|INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match").containsMatchIn(out)
            && adb.shell("pm list packages -s $pkg 2>/dev/null; echo .").lines().any { it.trim() == "package:$pkg" })
            throw IOException("builtin app: $pkg")                  // "uninstall it first" cannot be done to a system app: say so
        if (!out.contains("Success")) throw IOException(if (out.trim().isEmpty()) "install failed" else out.trim())
        // some boxes (FPT Play...) uninstall the apps they do not allow the moment they land, so "Success" alone proves nothing
        if (pkg != null) {
            Thread.sleep(3000)
            if (!adb.shell("pm path $pkg 2>/dev/null; echo .").contains("package:")) throw IOException("removed by the TV right after install: $pkg")
        }
        return "installed"
    }

    /** An APK from this device, checked and installed as an upload is: the phone's "several TVs at once" (pct 0..90 while it copies). */
    @Throws(IOException::class)
    fun install(input: InputStream, length: Long, job: JSONObject?, cancel: Cancel? = null): String {
        current.set(cancel)
        try {
            copy.push(input, length, TMP_APK, job, 2)
            try { return pmInstall(TMP_APK) } finally { try { sh("rm -f $TMP_APK") } catch (ignored: IOException) { } }
        } finally { current.remove() }
    }

    private fun installPath(path: String): JSONObject =
        job("install " + path.substring(path.lastIndexOf('/') + 1), "install", path.substring(path.lastIndexOf('/') + 1)) { j ->
            j.put("pct", 50)
            sh("stat -c %s " + Adb.q(path) + " 2>/dev/null").trim().toLongOrNull()?.let { copy.ensureRoom("/data", it, 1) }   // pm installs a copy of it
            pmInstall(path)
        }

    /** The APK arrives in the request body: it has to be read before we answer, so this one is not a background job. */
    fun installUpload(r: HttpServer.Request) {
        if (r.length <= 0) throw IllegalArgumentException("empty upload")
        copy.push(r.body, r.length, TMP_APK, null, 2)
        try {
            pmInstall(TMP_APK)
        } catch (e: Cancelled) {
            throw e                                                   // a Stop, answered 410 like the copy's
        } catch (e: IOException) {
            throw IllegalArgumentException(e.message)
        } finally {
            sh("rm -f $TMP_APK")
        }
    }

    /**
     * "Install as a separate app" ([dev.tvremote.core.ontv.AppCopy]): the same APK again, rewritten as `<package>.copy`, signed with
     * this client's own key, installed beside the built-in app; then started, and checked still alive after 5 s (an app that checks its
     * own signature closes at once: that is said, and the page offers Remove). Not a background job, as with installUpload.
     */
    fun installCopy(r: HttpServer.Request): String {
        if (r.length <= 0) throw IllegalArgumentException("empty upload")
        val cancel = current.get()
        val src = java.io.File.createTempFile("tvr-copy-", ".apk"); val out = java.io.File.createTempFile("tvr-copy-", ".out.apk")
        try {
            src.outputStream().use { o ->
                val b = ByteArray(65536); var left = r.length
                while (left > 0) { cancel?.check(); val n = r.body.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (n < 0) break; o.write(b, 0, n); left -= n }
            }
            val pkg = try { dev.tvremote.core.ontv.AppCopy.make(src, out, dir) { cancel?.check() } }
                      catch (e: Cancelled) { throw e } catch (e: IOException) { throw IllegalArgumentException(e.message) }
            try {
                out.inputStream().use { copy.push(it, out.length(), TMP_APK, null, 2) }
                pmInstall(TMP_APK)
            } catch (e: Cancelled) { throw e } catch (e: IOException) { throw IllegalArgumentException(e.message) } finally { sh("rm -f $TMP_APK") }
            val adb = link.get()
            val started = adb.shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 2>&1; monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1", 30000)
            if (started.contains("Events injected: 1")) {
                for (i in 0 until 5) { cancel?.check(); Thread.sleep(1000) }
                if (adb.shell("pidof $pkg 2>/dev/null; echo .").trim() == ".") throw IllegalArgumentException("copy closed: $pkg")
            }
            return pkg
        } finally { src.delete(); out.delete() }
    }

    /** 📥 a backup made by 💾, sent to the TV whole, then installed and put back there. Not a background job, as with installUpload. */
    fun restore(r: HttpServer.Request) {
        if (r.length <= 0) throw IllegalArgumentException("empty upload")
        val tar = "/data/local/tmp/tvr-rs-%08x.tar".format(java.util.Random().nextInt())
        copy.push(r.body, r.length, tar, null, 2)
        val adb = link.get()
        val out = try { adb.shell(TvShell.restore(tar), 600000) } finally { adb.shell("rm -f $tar") }
        if (!out.contains("Success")) throw IllegalArgumentException(out.trim().takeLast(300).ifEmpty { "restore failed" })
        val pkg = out.trim().substringAfterLast('\n').removePrefix("package").trim()   // the last line names it
        if (PKG.matcher(pkg).matches()) {                                       // as pmInstall: some boxes remove what they do not allow
            Thread.sleep(3000)
            if (!adb.shell("pm path $pkg 2>/dev/null; echo .").contains("package:")) throw IllegalArgumentException("removed by the TV right after install: $pkg")
        }
    }

    /** The link opened, its redirects followed: also from http to https, which the computer's Java will not do by itself
     *  (Android's does), so a short link or an old http one works the same everywhere. */
    private fun follow(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "tv-remote")
            current.get()?.onStop { c.disconnect() }                  // a download waiting on a slow server ends at once
            val to = if (c.responseCode in 301..308 && c.responseCode != 304) c.getHeaderField("Location") else null
            if (to == null) return c
            c.disconnect()
            u = URL(u, to)
            if (u.protocol != "http" && u.protocol != "https") throw IOException("only http/https links")
        }
        throw IOException("too many redirects")
    }

    /** This device downloads the link and passes it on to the TV, into Download or straight into the installer. */
    private fun fetch(url: String, install: Boolean): JSONObject {
        if (!url.matches(Regex("(?i)https?://\\S+"))) throw IllegalArgumentException("only http/https links")
        val shown = if (url.length > 60) url.substring(0, 60) else url
        return job((if (install) "install " else "download ") + shown, if (install) "install" else "download", shown) { j ->
            // TV Setup itself, the first time: the release's file, checked against its signed SHA256SUMS like every update
            val setup = if (install && url.endsWith("/releases/latest/download/tv-setup.apk"))
                Release.newer("0", "tv", "tv-setup.apk") ?: throw IOException(Release.UNSIGNED) else null
            val c = follow(setup?.url ?: url)
            try {
                if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
                val len = if (c.contentLength >= 0) c.contentLength.toLong() else -1L
                var name: String? = null
                val cd = c.getHeaderField("Content-Disposition")
                val m = if (cd == null) null else Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").matcher(cd)
                if (m != null && m.find()) name = URLDecoder.decode(m.group(1), "UTF-8")
                if (name == null) { val p = c.url.path; name = URLDecoder.decode(p.substring(p.lastIndexOf('/') + 1), "UTF-8") }
                name = safeName(name)
                val dest = if (install) TMP_APK else "/sdcard/Download/$name"
                j.put("msg", name)
                val md = MessageDigest.getInstance("SHA-256")
                DigestInputStream(c.inputStream, md).use { copy.push(it, len, dest, j, if (install) 2 else 1) }
                if (setup != null) try { setup.check(md) } catch (e: IOException) { sh("rm -f $TMP_APK"); throw e }
                if (!install) { j.put("dest", dest); "saved $dest" }
                else try { pmInstall(dest) } finally { sh("rm -f $TMP_APK") }
            } finally {
                c.disconnect()
            }
        }
    }
}
