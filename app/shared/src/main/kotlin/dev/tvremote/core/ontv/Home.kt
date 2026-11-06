package dev.tvremote.core.ontv
import dev.tvremote.core.B64
import dev.tvremote.core.adb.Adb
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.LinkUrl
import dev.tvremote.home.Target
import dev.tvremote.update.Release
import org.json.JSONObject
import java.io.File
import dev.tvremote.core.adb.Pkg
import java.io.IOException

/**
 * TV Home, this project's launcher (app/tvhome), set up from the phone or the computer: its favourites, hidden apps, background
 * and clock, through a broadcast only the shell can send (HomeReceiver there), like [RulesClient]. The settings' shape and
 * limits are [HomeConfig], compiled into both sides.
 *   read:   {installed: false} or {installed, version, home (the TV's home is TV Home now), config, apps: [{pkg, label}], photo,
 *           latest (a newer TV Home in the latest release, when there is one)}
 */
object Home {
    /** The settings and TV Home's view of the TV's apps; [change] (any fields of [HomeConfig]) is saved first. */
    @Throws(IOException::class)
    fun call(sh: (String) -> String, change: JSONObject?, extra: String = ""): JSONObject {   // [extra]: more receiver arguments (drop, thumbs)
        val arg = " $extra" + if (change == null) "" else " --es set " + B64.encode(HomeConfig.forward(change).let { c ->
            JSONObject().also { o -> for (k in change.keys()) if (c.has(k)) o.put(k, c.get(k)) } }.toString().toByteArray(Charsets.UTF_8))
        // the screensaver is a system setting: TV Home may write it once the shell has granted it that (only ADB can)
        val grant = if (change?.optBoolean("dream") == true) "pm grant ${HomeConfig.PKG} ${HomeConfig.WRITE_SECURE} 2>&1; " else ""
        val out = sh(grant + (if (change != null) HomeConfig.GRANT_WIFI else "") + "am broadcast --include-stopped-packages -n ${HomeConfig.RECEIVER}$arg 2>&1; echo '#~#'; " + LauncherSwap.RESOLVE_HOME)
        val m = Regex("data=\"([A-Za-z0-9+/=]+)\"").find(out)
            ?: if (Pkg.failed(out.substringBefore("#~#"))) throw IOException("pm: could not ask: " + out.substringBefore("#~#").trim().take(120)) else return JSONObject().put("installed", false)            // no receiver: TV Home is not on this TV
        val r = JSONObject(String(B64.decode(m.groupValues[1]), Charsets.UTF_8))
        if (r.has("error")) throw IOException(r.optString("error"))
        r.put("installed", true).put("home", out.substringAfter("#~#").contains(HomeConfig.PKG + "/"))
        latest(r.optString("version"))?.let { r.put("latest", it.version) }
        if (unasked) r.put("offline", true)                           // as OwnApp.state
        return r
    }

    /** TV Home's settings as it reports them; null without TV Home. No GitHub check, unlike [call]. */
    fun ownConfig(sh: (String) -> String): JSONObject? {
        val m = Regex("data=\"([A-Za-z0-9+/=]+)\"").find(sh("am broadcast --include-stopped-packages -n ${HomeConfig.RECEIVER} 2>&1")) ?: return null
        return JSONObject(String(B64.decode(m.groupValues[1]), Charsets.UTF_8)).optJSONObject("config") ?: JSONObject()
    }
    /** The names the person gave apps on TV Home (its "names"), by package; empty without TV Home. */
    fun ownNames(sh: (String) -> String): Map<String, String> = names(ownConfig(sh))

    /** The Apps tab's Rename: [name] becomes the app's name on TV Home (the same "names" its own Rename writes), empty = the app's own.
     *  Returns the names now, or null when TV Home is not on the TV. */
    @Throws(IOException::class)
    fun rename(sh: (String) -> String, pkg: String, name: String): Map<String, String>? {
        val cfg = ownConfig(sh) ?: return null
        val g = JSONObject(cfg.optJSONObject("names")?.toString() ?: "{}")
        val n = name.replace(Regex("\\s+"), " ").trim().take(HomeConfig.NAME_MAX)
        if (n.isEmpty()) g.remove(pkg) else g.put(pkg, n)
        call(sh, JSONObject().put("names", g))
        return names(JSONObject().put("names", g))
    }

    /** The tiles pinned on TV Home's grid (its "pinned"), for the voice to open by name; empty without TV Home or a TV Home that does not report them. */
    fun ownPins(sh: (String) -> String): List<dev.tvremote.core.scenes.Voice.PinRef> {
        val m = Regex("data=\"([A-Za-z0-9+/=]+)\"").find(sh("am broadcast --include-stopped-packages -n ${HomeConfig.RECEIVER} 2>&1")) ?: return emptyList()
        return pins(JSONObject(String(B64.decode(m.groupValues[1]), Charsets.UTF_8)).optJSONObject("config"))
    }
    fun pins(config: JSONObject?): List<dev.tvremote.core.scenes.Voice.PinRef> {
        val a = config?.optJSONArray("pinned") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("key").startsWith("pin:") && it.optString("title").isNotBlank() }
            .map { dev.tvremote.core.scenes.Voice.PinRef(it.getString("key"), it.getString("title"), it.optString("app"), it.optString("link").ifBlank { null }) }
    }

    /** Asks TV Home (its OpenActivity, which opens only what is on its grid) to open the pinned tile [key]: only the home screen may start a shortcut. */
    @Throws(IOException::class)
    fun openPin(sh: (String) -> String, key: String) {
        if (!PIN_KEY.matches(key)) throw IOException("pin_bad_link")
        val (r, _) = AppRestore.run(sh, HomeConfig.PKG, "am start -n ${HomeConfig.PKG}/.OpenActivity --es t " + Adb.q(key) + " 2>&1") { !it.contains("Error") }
        if (r.contains("Error")) throw IOException("home:missing")
    }
    private val PIN_KEY = Regex("^pin:[A-Za-z][\\w.]*/[\\w.:@+=,~-]{1,200}$")

    fun names(config: JSONObject?): Map<String, String> {
        val n = config?.optJSONObject("names") ?: return emptyMap()
        return n.keys().asSequence().mapNotNull { k -> n.optString(k).trim().takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
    }

    @Volatile private var unasked = false                              // the last [latest] could not reach GitHub
    @Volatile private var checked: Triple<Long, String, Release?>? = null
    /** A newer TV Home than [version], asked of GitHub at most every 6 hours; null when none, or GitHub cannot be asked. */
    private fun latest(version: String): Release? {
        unasked = false
        checked?.let { (at, v, r) -> if (v == version && System.currentTimeMillis() - at < 6 * 3600_000L) return r }
        val r = try { Release.newer(version, "home", Launchers.SELF_APK) } catch (e: Exception) { unasked = true; return null }
        checked = Triple(System.currentTimeMillis(), version, r)
        return r
    }

    /** The newer TV Home put over the one on the TV (its settings stay): checked against the release's signed SHA256SUMS first. */
    @Throws(IOException::class)
    fun update(adb: Adb, dir: File?, lang: String, go: () -> Boolean = { true }, progress: ((Int) -> Unit)? = null): JSONObject {     // go, progress: as OwnApp.install
        val sh: (String) -> String = { adb.shell(it) }
        val now = call(sh, null)
        if (!now.optBoolean("installed")) throw IOException("home:missing")
        checked = null
        val r = latest(now.optString("version")) ?: return now
        val file = File(dir ?: File(System.getProperty("java.io.tmpdir")), "tv-home-${r.version}.apk")
        PlayProtect.check(dir, r.sha256)                              // refused before: no new "Harmful app blocked" on the TV
        try {
            r.download(file, progress?.let { f -> dev.tvremote.update.Release.Progress { f(it) } })
            if (!go()) return now
            val shown = sh("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'").contains(HomeConfig.PKG + "/")
            val out = Setup(adb, dir, lang) { }.install(file)
            PlayProtect.check(dir, r.sha256, out)
            if (!out.contains("Success")) throw IOException("launcher:install:" + out.trim().lines().lastOrNull().orEmpty().take(200))
            // the install kills the home on screen and Android does not start it again: the last app used came up in its place
            // (an FPT box showed TV Channels, nothing pressed). Also in standby (AutoUpdate's time), where it waited for the wake-up
            if (shown) sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1")
        } finally { file.delete() }
        try { adb.shell(HomeConfig.GRANT_WIFI + "cmd package compile -m speed -f ${HomeConfig.PKG} >/dev/null 2>&1", 120000) } catch (x: IOException) { }   // as LauncherInstall
        checked = null
        return call(sh, null)
    }

    /** A photo as a background: put where TV Home reads it, then chosen (and added to the rotation's set when that is on).
     *  [jpeg] is already the screen's size (the page makes it so). A TV Home without rotation keeps its one photo, wall.jpg. */
    @Throws(IOException::class)
    fun photo(adb: Adb, jpeg: ByteArray): JSONObject {
        val sh: (String) -> String = { adb.shell(it) }
        val now = call(sh, null)
        if (!now.optBoolean("installed")) throw IOException("home:missing")   // also makes TV Home's folder, where the shell may write
        val conf = now.optJSONObject("config") ?: JSONObject()
        if (!conf.optBoolean("wrotate")) {
            if (!adb.put(HomeConfig.PHOTO, jpeg, true)) throw IOException("home:photo")
            return call(sh, JSONObject().put("wall", "photo").put("photo", System.currentTimeMillis()))
        }
        val used = now.optJSONArray("photos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") } } ?: emptyList()
        val id = HomeConfig.PHOTOS.firstOrNull { it !in used } ?: throw IOException("home:photos_full")
        if (!adb.put(HomeConfig.photoPath(id), jpeg, true)) throw IOException("home:photo")
        val set = conf.optJSONArray("walls")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val walls = if (conf.optString("wevery", "off") == "off") listOf(id) else set + id
        return call(sh, JSONObject().put("walls", org.json.JSONArray(walls)).put("wall", id).put("photo", System.currentTimeMillis()))
    }

    /**
     * The person's own picture for the tile [target] ("pics"): [jpeg] (the page's 480 x 270 one, at most [HomeConfig.PIC_BYTES]) goes to TV Home's
     * pics folder under a new id, then the setting names it; TV Home drops the files no setting names. A TV Home from before "pics" answers `home:pic_old`.
     */
    @Throws(IOException::class)
    fun appPic(adb: Adb, target: String, jpeg: ByteArray): JSONObject {
        if (target == HomeConfig.FRAME) return framePic(adb, jpeg)
        if (!Target.valid(target) || HomeConfig.isLive(target) || HomeConfig.isFolder(target)) throw IllegalArgumentException("pic target")
        if (jpeg.size < 4 || jpeg.size > HomeConfig.PIC_BYTES || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) throw IllegalArgumentException("pic image")
        val sh: (String) -> String = { adb.shell(it) }
        val now = call(sh, null)
        if (!now.optBoolean("installed")) throw IOException("home:missing")
        val conf = now.optJSONObject("config") ?: JSONObject()
        if (!conf.has("pics")) throw IOException("home:pic_old")        // a TV Home from before own pictures
        val have = conf.optJSONObject("pics") ?: JSONObject()
        if (!have.has(target) && have.length() >= HomeConfig.PICS_MAX) throw IOException("home:pic_full")
        val id = String.format("%08x", java.security.SecureRandom().nextInt())
        val path = HomeConfig.picPath(id)
        sh("mkdir -p " + path.substringBeforeLast('/'))
        if (!adb.put(path, jpeg, true)) throw IOException("home:photo")
        return call(sh, JSONObject().put("pics", JSONObject(have.toString()).put(target, id)))
    }

    /**
     * One more photo for the photo frame widget ("frame"): the same small JPEG and folder as [appPic], its id added at the end of the
     * list. A TV Home from before the frame answers `home:frame_old`, a full one `home:frame_full`.
     */
    @Throws(IOException::class)
    private fun framePic(adb: Adb, jpeg: ByteArray): JSONObject {
        if (jpeg.size < 4 || jpeg.size > HomeConfig.PIC_BYTES || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) throw IllegalArgumentException("pic image")
        val sh: (String) -> String = { adb.shell(it) }
        val now = call(sh, null)
        if (!now.optBoolean("installed")) throw IOException("home:missing")
        val conf = now.optJSONObject("config") ?: JSONObject()
        if (!conf.has("frame")) throw IOException("home:frame_old")
        val have = HomeConfig.frame(conf.optJSONArray("frame"))
        if (have.size >= HomeConfig.FRAME_MAX) throw IOException("home:frame_full")
        val id = String.format("%08x", java.security.SecureRandom().nextInt())
        val path = HomeConfig.picPath(id)
        sh("mkdir -p " + path.substringBeforeLast('/'))
        if (!adb.put(path, jpeg, true)) throw IOException("home:photo")
        return call(sh, JSONObject().put("frame", org.json.JSONArray(have + id)))
    }

    /**
     * A video or GIF as the background: [size] bytes from [feed] go to TV Home's staging folder as they arrive (never all in memory:
     * up to [HomeConfig.VIDEO_MAX_BYTES]), then TV Home takes the file in by its name, makes it light if it must be and enters it in its
     * media library; the answer is its settings with `video.job` (the work under way: the client asks again until it ends).
     */
    @Throws(IOException::class)
    fun video(adb: Adb, name: String, size: Long, feed: (Adb.Stream) -> Unit): JSONObject {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in HomeConfig.VIDEO_EXT) throw IllegalArgumentException("video type")
        if (size <= 0 || size > HomeConfig.VIDEO_MAX_BYTES) throw IllegalArgumentException("video size")
        val sh: (String) -> String = { adb.shell(it) }
        val now = call(sh, null)
        if (!now.optBoolean("installed")) throw IOException("home:missing")
        if (now.optJSONObject("video") == null) throw IOException("home:video_old")        // a TV Home from before video
        val free = now.optJSONObject("video")!!.optLong("free")
        if (free < size * 2 + 100L * 1024 * 1024) throw IOException("home:video_space")
        val file = "in" + System.currentTimeMillis().toString(36) + "." + ext
        sh("mkdir -p ${HomeConfig.VIDEO_STAGING}")
        if (!adb.put(HomeConfig.VIDEO_STAGING + "/" + file, size, true, null, feed)) throw IOException("home:video")
        return call(sh, null, "--es addvideo $file --es vname " + B64.encode(name.toByteArray(Charsets.UTF_8)))
    }

    /** Where a photo ("photo", "photo2" ..) or a video ("vid:<8 hex>") of the person's lives on the TV (what a theme file carries along), or null for any other id. */
    fun mediaPath(id: String): String? = when {
        HomeConfig.isPhoto(id) -> HomeConfig.photoPath(id)
        HomeConfig.isVideo(id) -> HomeConfig.VIDEO_STAGING.substringBeforeLast('/') + "/" + id.removePrefix(HomeConfig.VIDEO_PREFIX) + ".mp4"
        else -> null
    }

    /** TV Home's QR screen for a photo from a phone, over its home screen: the page on the TV itself has no photos to pick. */
    @Throws(IOException::class)
    fun photoQr(sh: (String) -> String) {
        if (!sh("am start -a ${HomeConfig.PHOTO_QR} -n ${HomeConfig.PKG}/.HomeActivity 2>&1").contains("Starting")) throw IOException("home:photo")
    }

    /** A photo or a video taken out: TV Home deletes the file and its place in the set (it owns them); a video the setting still uses stays (error "home:video_inuse"). */
    @Throws(IOException::class)
    fun drop(adb: Adb, id: String): JSONObject {
        if (!HomeConfig.isPhoto(id) && !HomeConfig.isVideo(id)) throw IllegalArgumentException("photo id")
        return call({ adb.shell(it) }, null, "--es drop $id")
    }

    // ---- YouTube links: pinned to TV Home's grid, or opened on the TV at once (the same calls for the page and the Phone app's share sheet)
    private val YT_HOST = Regex("^(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)$")
    private const val YT_TV = "com.google.android.youtube.tv"

    /** [s] as a https YouTube link, or null: what reaches the TV's shell is only ever this (the host is checked, the quotes are Adb.q's). */
    fun youtubeUrl(s: String?): String? {
        val u = (s ?: "").trim()
        val m = Regex("^https://([^/?#:@]+)(/\\S*)?$", RegexOption.IGNORE_CASE).find(u) ?: return null
        return if (YT_HOST.matches(m.groupValues[1].lowercase())) u else null
    }

    /** Pins the link ([d]: url, name?, img?) on TV Home's grid; a TV Home from before pins answers without "pinOk" and gets `home:pin_old`. */
    @Throws(IOException::class)
    fun pin(sh: (String) -> String, d: JSONObject): JSONObject {
        val u = LinkUrl.clean(d.optString("url")) ?: throw IOException("pin_bad_link")
        val o = JSONObject().put("url", u)
        d.optString("pkg").let { if (PKG.matches(it)) o.put("pkg", it) }     // the app the person picked to open this kind of link
        d.optString("name").trim().take(120).let { if (it.isNotEmpty()) o.put("name", it) }
        d.optString("img").let { if (it.startsWith("https://") && it.length < 400) o.put("img", it) }
        val r = call(sh, null, "--es pin " + B64.encode(o.toString().toByteArray(Charsets.UTF_8)))
        if (!r.optBoolean("installed")) throw IOException("home:missing")
        if (!r.optBoolean("pinOk") && r.optJSONObject("config")?.optBoolean("pinOk") != true) throw IOException("home:pin_old")
        if (youtubeUrl(u) == null && !r.optBoolean("linkOk") && r.optJSONObject("config")?.optBoolean("linkOk") != true) throw IOException("home:pin_old")   // a TV Home from before any link: YouTube only
        return r
    }

    private val PKG = Regex("^[A-Za-z][\\w.]{0,150}$")

    /** What the TV would do with the link ([d]: url): TV Home's answer {url, kind: app|browser|none, apps: [{pkg, label, logo}], choice}; an older TV Home gets `home:pin_old`. */
    @Throws(IOException::class)
    fun link(sh: (String) -> String, d: JSONObject): JSONObject {
        val u = LinkUrl.clean(d.optString("url")) ?: throw IOException("pin_bad_link")
        val r = call(sh, null, "--es link " + B64.encode(JSONObject().put("url", u).toString().toByteArray(Charsets.UTF_8)))
        if (!r.optBoolean("installed")) throw IOException("home:missing")
        if (!r.optBoolean("linkOk")) throw IOException("home:pin_old")
        return r
    }

    /** Opens [url] once on the TV, as a link ("Try on the TV"): in [pkg] when given and a package name, else whatever the TV picks; never a bare VIEW that reaches a stub. */
    @Throws(IOException::class)
    fun openLink(sh: (String) -> String, url: String, pkg: String?) {
        val u = LinkUrl.clean(url) ?: throw IOException("pin_bad_link")
        val p = pkg?.takeIf { PKG.matches(it) }
        val r = sh("am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d " + Adb.q(u) + (if (p != null) " -p $p" else "") + " 2>&1")
        if (r.contains("Error") || r.lowercase().contains("unable to resolve")) throw IOException("pin_no_app")
    }

    /** Packages that take a YouTube link but are not a YouTube app: the framework's stub, downloaders, browsers (those also take any https page). */
    private val NOT_YT = Regex("frameworkpackagestubs|downloader|\\.browser|chrome|firefox|puffin|silk|opera|tvbro|webview")

    /** The packages in `query-activities --brief` output ("pkg/.Activity" lines), in the order the TV listed them. */
    internal fun queryPackages(out: String): List<String> =
        out.lines().map { it.trim() }.filter { Regex("^[A-Za-z][\\w.]*/\\S+$").matches(it) }.map { it.substringBefore('/') }.distinct()

    /** The app that opens [yt] links: [prefer] if the TV has it, stock YouTube for TV, else the first other app that is no stub, browser or downloader. */
    internal fun youtubeApp(yt: List<String>, web: List<String>, prefer: String? = null): String? {
        val apps = yt.filter { it !in web && !NOT_YT.containsMatchIn(it) }
        return apps.firstOrNull { it == prefer } ?: apps.firstOrNull { it == YT_TV } ?: apps.firstOrNull()
    }

    /**
     * Opens the link in the YouTube app the TV really has: many TVs run a mod (TizenTube, SmartTube, ReVanced...) with the stock app turned
     * off, so the app is asked of the TV (one shell call) and never assumed; no bare VIEW either (it could open a downloader or a stub).
     */
    @Throws(IOException::class)
    fun openYoutube(sh: (String) -> String, url: String, prefer: String? = null) {
        val u = youtubeUrl(url) ?: throw IOException("pin_bad_link")
        val q = "cmd package query-activities --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "
        val out = sh(q + Adb.q(u) + " 2>&1; echo '#~#'; " + q + "https://example.com/ 2>&1")
        if (out.isBlank()) throw IOException("not connected")            // no ADB link just then: not "no YouTube app"
        val pkg = youtubeApp(queryPackages(out.substringBefore("#~#")), queryPackages(out.substringAfter("#~#", "")), prefer)
            ?: throw IOException("pin_no_app")
        val r = sh("am start -a android.intent.action.VIEW -d " + Adb.q(u) + " -p " + pkg + " 2>&1")
        if (r.contains("Error") || r.lowercase().contains("unable to resolve")) throw IOException("pin_no_app")
    }}
