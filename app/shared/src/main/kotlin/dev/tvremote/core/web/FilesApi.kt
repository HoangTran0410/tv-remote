package dev.tvremote.core.web
import dev.tvremote.core.Words
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.web.TvPaths.CAST_DIR
import dev.tvremote.core.web.TvPaths.safeName
import dev.tvremote.core.web.TvPaths.mime
import dev.tvremote.core.web.TvPaths.root
import dev.tvremote.core.web.TvPaths.safePath
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/** The Files tab: the TV's shared storage and USB drives, browsed, changed, downloaded, and files sent there or opened. */
internal class FilesApi(core: ManageCore, private val copy: TvCopy, private val installs: InstallApi, private val setupApi: SetupApi) : ManagePart(core) {
    override fun route(what: String, post: Boolean, r: HttpServer.Request): HttpServer.Response? {
        when (what) {
            "files" -> return HttpServer.Response.json(files(r.q("path")))
            "file" -> return download(r)
        }
        if (!post) return null
        return when (what) {
            "upload" -> HttpServer.Response.json(upload(r))
            "file/delete", "file/rename", "file/mkdir", "file/open" -> fileOp(what.substring(5), r.json())
            else -> null
        }
    }

    private val LS = Pattern.compile("^([dl-])\\S+\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d)\\s+(.+)\$")

    // ---------------------------------------------------------------- files
    private fun files(raw: String): JSONObject {
        val path = safePath(raw)
        val q = Adb.q("$path/")                                       // the trailing / follows the /sdcard symlink
        val part = sh("ls -lA $q 2>&1 | cat; echo '#--'; df -k $q 2>/dev/null | tail -1; echo '#--'; ls /storage 2>/dev/null").jsplit("#--", -1)
        val items = ArrayList<JSONObject>()
        for (line in part[0].jsplit("\n")) {
            val m = LS.matcher(line.trim())
            if (m.matches()) {
                val name = m.group(4)!!.jsplit(" -> ")[0]
                items.add(JSONObject().put("name", name).put("dir", m.group(1) != "-").put("size", m.group(2)!!.toLong()).put("mtime", m.group(3)))
            } else if (line.contains("No such file") || line.contains("Permission denied")) throw IllegalArgumentException(line.trim())
        }
        items.sortWith { a, b ->
            if (a.optBoolean("dir") != b.optBoolean("dir")) (if (a.optBoolean("dir")) -1 else 1)
            else a.optString("name").lowercase(Locale.ROOT).compareTo(b.optString("name").lowercase(Locale.ROOT))
        }
        val out = JSONObject().put("path", path).put("items", JSONArray(items))
        val df = if (part.size > 1) part[1].trim().jsplit("\\s+") else emptyArray<String>()
        if (df.size > 3 && df[1].matches(DIGITS) && df[3].matches(DIGITS)) out.put("space", JSONObject().put("total", df[1].toLong() * 1024).put("free", df[3].toLong() * 1024))
        val usb = JSONArray()
        if (part.size > 2) for (v in part[2].trim().jsplit("\\s+"))
            if (v.isNotEmpty() && v != "emulated" && v != "self" && !v.startsWith("sdcard")) usb.put(v)
        return out.put("volumes", usb)
    }

    /** Puts a file from this device into the TV's Download folder and has the TV open it: a photo, a video, a song. */
    @Throws(IOException::class)
    fun cast(input: InputStream, length: Long, name: String, job: JSONObject? = null, cancel: Cancel? = null) {
        val path = CAST_DIR + "/" + safeName(name)
        sh("mkdir -p " + Adb.q(CAST_DIR))
        current.set(cancel)
        try { copy.push(input, length, path, job) } finally { current.remove() }
        fileOp("open", JSONObject().put("path", path))
    }

    private fun fileOp(op: String, d: JSONObject): HttpServer.Response {
        val path = safePath(d.optString("path"))
        var out: String
        when (op) {
            "delete" -> {
                if (root(path)) throw IllegalArgumentException("refusing to delete a storage root")
                out = sh("rm -rf " + Adb.q(path) + " 2>&1")
            }
            "mkdir" -> {
                val name = path.substring(path.lastIndexOf('/') + 1)
                if (root(path) || name != safeName(name)) throw IllegalArgumentException("bad name")
                out = sh("mkdir " + Adb.q(path) + " 2>&1")
            }
            "rename" -> {
                val name = d.optString("name")
                if (root(path) || name != safeName(name)) throw IllegalArgumentException("bad name")
                val dst = Adb.q(path.substring(0, path.lastIndexOf('/')) + "/" + name)
                out = sh("if [ -e $dst ]; then echo 'a file with that name already exists'; else mv " + Adb.q(path) + " $dst 2>&1; fi")
            }
            else -> {                                                 // open on the TV with whatever app handles the type
                val real = if (path.startsWith("/sdcard")) "/storage/emulated/0" + path.substring(7) else path
                val args = "-a android.intent.action.VIEW -d " + Adb.q("file://" + real.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }) + " -t " + Adb.q(mime(path))
                // a video or song: TV Setup's own player (aspect kept, loops) when it is there; the TV's own player stretches and plays once
                val m = mime(path)
                if ((m.startsWith("video/") || m.startsWith("audio/")) && playOwn(real, m)) return HttpServer.Response.ok()
                // Google TV answers files it cannot show with a do-nothing stub (frameworkpackagestubs): that is "no app" too
                out = if (sh("cmd package resolve-activity --brief $args 2>&1").contains("frameworkpackagestubs")) "Error" else sh("am start $args 2>&1")
                out = if (out.contains("Error") || out.lowercase(Locale.ROOT).contains("unable to resolve")) "the TV has no app that can open this" else ""
            }
        }
        if (out.trim().isNotEmpty()) throw IllegalArgumentException(out.trim())
        return HttpServer.Response.ok()
    }

    /**
     * Plays [real] in TV Setup's PlayActivity. TV Setup has no storage permission, so the shell copies the file into its own folder
     * (one file kept, the last). False when TV Setup is missing or older: the caller falls back to the TV's own player.
     */
    private fun playOwn(real: String, mime: String): Boolean {
        val dir = "/sdcard/Android/data/dev.tvremote.setup/files/play"
        val name = "play." + real.substringAfterLast('.', "mp4").filter { it.isLetterOrDigit() }.take(5)
        val out = sh("cmd package resolve-activity --brief -n dev.tvremote.setup/.PlayActivity 2>&1 | grep -q PlayActivity || exit 3; " +
            "mkdir -p $dir && rm -f $dir/* && cp " + Adb.q(real) + " $dir/$name && chmod 644 $dir/$name || exit 4; " +   // cp makes it 600, the shell's: TV Setup could not read it and closed (FPT)
            "am start -n dev.tvremote.setup/.PlayActivity -d file://$dir/$name -t " + Adb.q(mime) + " 2>&1")
        return !out.contains("Error") && !out.contains("Exception") && out.contains("Starting")
    }

    private fun download(r: HttpServer.Request): HttpServer.Response {
        val path = safePath(r.q("path"))
        val adb = link.get()
        // a file this shell may read, or the size promised would be followed by cat's complaint (a folder, a file of an app's own)
        val size = adb.shell("[ -f ${Adb.q(path)} ] && [ -r ${Adb.q(path)} ] && stat -c %s " + Adb.q(path) + " 2>/dev/null").trim()
        if (!size.matches(DIGITS)) throw IllegalArgumentException("cannot read this file")
        val inline = r.q("inline") == "1"
        val out = HttpServer.Response.stream(if (inline) mime(path).replace("*/*", "application/octet-stream") else "application/octet-stream", size.toLong()) { o ->
            pipe(adb.exec("cat " + Adb.q(path) + " 2>/dev/null"), o)
        }
        out.patient = true
        if (!inline) out.headers["Content-Disposition"] = "attachment; filename*=UTF-8''" + URLEncoder.encode(path.substring(path.lastIndexOf('/') + 1), "UTF-8").replace("+", "%20")
        return out
    }

    /**
     * A file from the browser, into a folder on the TV or (kind=install) straight into the installer. The file arrives in the
     * request body and has to be read before we answer, so the answer is a job that is already finished.
     */
    private fun upload(r: HttpServer.Request): JSONObject {
        val name = safeName(r.q("name"))
        // kind + name (+ dest): what the page words in its own language (title and msg stay for older pages and the Phone app)
        val done = { title: String, msg: String -> JSONObject().put("id", "u" + core.jobs.nextId()).put("title", title).put("status", "done").put("pct", 100).put("msg", msg)
            .put("kind", title.substringBefore(' ')).put("name", name) }
        val uid = r.q("uid").take(64)                                 // the page's name for this upload: POST /api/cancel {id} stops it
        val cancel = Cancel()
        if (uid.isNotEmpty()) stops[uid] = cancel
        current.set(cancel)
        try {
            if (r.q("kind") == "install") { installs.installUpload(r); return done("install $name", "installed") }
            if (r.q("kind") == "installcopy") { val pkg = installs.installCopy(r); return done("install $name", "installed $pkg") }
            if (r.q("kind") == "restore") { installs.restore(r); return done("restore $name", "restored") }
            if (r.q("kind") == "welcome") { setupApi.welcomeUpload(r, name); return done("welcome $name", Words.get(r.lang, "welcome.saved")) }
            val dest = safePath(r.q("dest")) + "/" + name
            copy.push(r.body, r.length, dest, null)
            return done("upload $name", "saved $dest").put("dest", dest)
        } finally {
            current.remove()
            if (uid.isNotEmpty()) stops.remove(uid)
        }
    }
}
