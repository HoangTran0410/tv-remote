package dev.tvremote.tvapps

import dev.tvremote.update.SignedList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * TV Apps' list: free and open-source apps for a TV (docs/apps/apps.json, signed by dev/sign/sign_apps.py), read from the site,
 * else the repository, else the copy kept from the last time. No copy is packed in an APK: installing needs the Internet anyway.
 * Each app names its author's signing certificates and where its latest APK is (the author's release, never a copy of ours), so
 * a new release of an app needs no new list. Plain java: :shared (the clients and TV Setup), the computer app and TV Apps (app/tvapps).
 */
object AppList {
    // TVR_TVAPPS (computer): a list to try before it is published, e.g. http://127.0.0.1:8000/apps/ (still signature-checked)
    var bases = System.getenv("TVR_TVAPPS")?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(if (it.endsWith("/")) it else "$it/") }
        ?: listOf("https://tvremote.pages.dev/apps/", "https://raw.githubusercontent.com/HoangTran0410/tv-remote/main/docs/apps/")
    var keys = SignedList.KEYS                                        // tests: a key made for them
    const val NAME = "apps.json"
    private const val MAX = 1 shl 20
    const val NO_NET = "apps:offline"                                 // nothing kept and nothing reached: the screens say "No internet · Retry"
    const val BAD_CERT = "apps:cert"                                  // the APK is not signed by the app's author
    const val NO_APK = "apps:noapk"                                   // no APK for this TV's processor

    /** Where the latest APK for one processor comes from: a fixed link, or a GitHub repository's latest release and a pattern for the file. */
    class Source(val url: String, val repo: String, val match: String)
    class App(val id: String, val pkg: String, val name: String, val cat: String, val desc: Map<String, String>, val notes: Map<String, String>,
              val icon: String, val iconSha: String, val source: String, val license: String, val certs: List<String>, val minSdk: Int,
              val apk: Map<String, Source>, val setup: String = "", val also: List<String> = emptyList()) {
        /** On the TV under any of these (TV Setup Voice: one package per language). */
        val pkgs get() = listOf(pkg) + also
        fun desc(lang: String) = desc[lang] ?: desc["en"] ?: ""
        fun notes(lang: String) = notes[lang] ?: notes["en"] ?: ""
    }
    class Cat(val id: String, val name: Map<String, String>) { fun name(lang: String) = name[lang] ?: name["en"] ?: id }
    class List_(val updated: String, val cats: List<Cat>, val apps: List<App>, val base: String) {
        operator fun get(id: String) = apps.firstOrNull { it.id == id }
    }

    private fun words(o: JSONObject?): Map<String, String> = o?.keys()?.asSequence()?.associateWith { o.getString(it) } ?: emptyMap()

    /** The list from its bytes (already signature-checked); an entry missing what an install needs is left out, not the whole list. */
    fun parse(bytes: ByteArray, base: String): List_ {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val cats = o.optJSONArray("cats") ?: JSONArray()
        val apps = o.optJSONArray("apps") ?: JSONArray()
        return List_(o.optString("updated"), (0 until cats.length()).map { cats.getJSONObject(it) }.map { Cat(it.getString("id"), words(it.optJSONObject("name"))) },
            (0 until apps.length()).mapNotNull { i -> try { app(apps.getJSONObject(i)) } catch (e: Exception) { null } }, base)
    }

    private fun app(a: JSONObject): App? {
        val certs = a.getJSONArray("certs").let { c -> (0 until c.length()).map { c.getString(it).lowercase().replace(":", "") } }
        if (certs.isEmpty() || certs.any { !it.matches(Regex("[0-9a-f]{64}")) }) return null
        val apk = LinkedHashMap<String, Source>()
        val ao = a.optJSONObject("apk") ?: JSONObject()                // none: only an app TV Setup installs ("setup" below)
        for (abi in ao.keys()) {
            val v = ao.get(abi)
            val s = if (v is String) Source(v, "", "") else (v as JSONObject).let { Source("", it.getString("repo"), it.getString("match")) }
            if (s.url.isNotEmpty() && !s.url.startsWith("https://")) return null
            if (s.repo.isNotEmpty() && !s.repo.matches(Regex("[\\w.-]+/[\\w.-]+"))) return null
            apk[abi] = s
        }
        // "setup": TV Setup installs it on that screen of its own ("voice": by its rule, which also removes it), so no "apk":
        // a client from before "setup" drops the entry instead of installing it beside the rule (TV Setup would take it off again)
        val setup = a.optString("setup")
        if (apk.isEmpty() && setup.isEmpty()) return null
        val also = a.optJSONArray("also")?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
        return App(a.getString("id"), a.getString("pkg"), a.getString("name"), a.optString("cat"), words(a.optJSONObject("desc")),
            words(a.optJSONObject("notes")), a.optString("icon"), a.optString("icon_sha"), a.optString("source"), a.optString("license"), certs, a.optInt("min_sdk", 21), apk, setup, also)
    }

    /** The APK for a TV: the first of its ABIs (best first, as getprop ro.product.cpu.abilist lists them) the app has, else "universal". */
    fun pick(a: App, abis: List<String>): Pair<String, Source>? =
        abis.firstNotNullOfOrNull { abi -> a.apk[abi]?.let { abi to it } } ?: a.apk["universal"]?.let { "universal" to it }

    /** "" when the app fits this TV, else sdk / abi (each screen puts it in its own words). */
    fun why(a: App, sdk: Int, abis: List<String>) = when { sdk in 1 until a.minSdk -> "sdk"; a.setup.isEmpty() && pick(a, abis) == null -> "abi"; else -> "" }

    /** The package of TV Setup, which installs the apps with a "setup" screen. */
    const val SETUP_PKG = "dev.tvremote.setup"

    @Volatile private var mem: Pair<Long, List_>? = null
    private val asking = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * The list: asked again after a minute (the screens call this on every opening); [dir] keeps a copy. With a copy kept it is
     * returned at once and the site asked behind it (the next opening has the new one): offline, a name lookup can hang ~20 s on
     * "Loading". [fresh] (Try again) waits for the site.
     */
    @Throws(IOException::class)
    fun fetch(dir: File?, fresh: Boolean = false): List_ {
        mem?.let { if (!fresh && System.currentTimeMillis() - it.first < 60_000) return it.second }
        val kept = dir?.let { File(it, NAME) }
        if (!fresh) (mem?.second ?: kept?.let { SignedList.kept(it, keys) }?.let { parse(it, bases.first()) })?.let { l ->
            if (asking.compareAndSet(false, true)) Thread { try { ask(kept) } catch (e: IOException) { } finally { asking.set(false) } }.start()
            return l
        }
        return try { ask(kept) } catch (e: IOException) {
            kept?.let { SignedList.kept(it, keys) }?.let { parse(it, bases.first()) } ?: throw IOException(NO_NET, e)
        }
    }

    private fun ask(kept: File?): List_ = SignedList.fetch(bases, NAME, MAX, keys) { list, sig, base ->
        if (kept != null) try { SignedList.keep(kept, list, sig) } catch (e: IOException) { }
        parse(list, base)
    }.also { mem = System.currentTimeMillis() to it }

    private val icons = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** The app's icon (PNG, from the list's own site), checked against the SHA-256 the signed list gives and kept in [dir]. */
    @Throws(IOException::class)
    fun icon(l: List_, a: App, dir: File?): ByteArray {
        if (a.icon.isEmpty() || !a.iconSha.matches(Regex("[0-9a-f]{64}"))) throw IOException("no icon")
        icons[a.iconSha]?.let { return it }
        val f = dir?.let { File(it, "icons/" + a.iconSha + ".png") }
        val b = f?.takeIf { it.isFile }?.readBytes()?.takeIf { ApkCert.sha256(it) == a.iconSha }
            ?: SignedList.get(l.base + a.icon, 512 * 1024).also { if (ApkCert.sha256(it) != a.iconSha) throw IOException("icon does not match the list")
                if (f != null) try { f.parentFile?.mkdirs(); f.writeBytes(it) } catch (x: IOException) { } }
        icons[a.iconSha] = b
        return b
    }

    /** The link of the latest APK: a fixed link as it is, a GitHub release's file picked by its pattern (the API, ~1 request). */
    @Throws(IOException::class)
    fun resolve(s: Source): String {
        if (s.url.isNotEmpty()) return s.url
        val rel = JSONObject(String(SignedList.get("https://api.github.com/repos/${s.repo}/releases/latest", MAX), Charsets.UTF_8))
        val re = Regex(s.match)
        val assets = rel.optJSONArray("assets") ?: JSONArray()
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (re.matches(a.getString("name"))) return a.getString("browser_download_url").takeIf { it.startsWith("https://") } ?: break
        }
        throw IOException(NO_APK)
    }

    /**
     * Downloads the app's APK for these [abis] into [dir] and checks it is signed by its author ([ApkCert]); the file, or an
     * IOException ([NO_APK], [BAD_CERT], the network's). [pct] gets 0-100; [stop] true ends it (the file is removed).
     */
    @Throws(IOException::class)
    fun download(a: App, abis: List<String>, dir: File, pct: (Int) -> Unit, stop: () -> Boolean): File = try { get(a, abis, dir, pct, stop) }
        catch (e: java.net.UnknownHostException) { throw IOException(NO_NET, e) }   // offline: Java's own words name a host
        catch (e: java.net.ConnectException) { throw IOException(NO_NET, e) }
        catch (e: java.net.NoRouteToHostException) { throw IOException(NO_NET, e) }

    private fun get(a: App, abis: List<String>, dir: File, pct: (Int) -> Unit, stop: () -> Boolean): File {
        val src = pick(a, abis)?.second ?: throw IOException(NO_APK)
        val f = File(dir, "tvapps-${a.id}.apk")
        val c = URL(resolve(src)).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "tv-remote")
        try {
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L    // contentLengthLong is API 24
            if (total > 400L shl 20) throw IOException("file too big")
            var got = 0L; var last = -1
            c.inputStream.use { i -> FileOutputStream(f).use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (stop()) throw IOException("stopped")
                    val n = i.read(buf); if (n <= 0) break
                    o.write(buf, 0, n); got += n
                    if (got > 400L shl 20) throw IOException("file too big")
                    if (total > 0) (got * 100 / total).toInt().let { if (it != last) { last = it; pct(it) } }
                }
            } }
        } catch (e: IOException) { f.delete(); throw e } finally { c.disconnect() }
        val certs = try { ApkCert.read(f) } catch (e: IOException) { emptyMap() }
        if (!ApkCert.allPinned(certs, a.certs)) { f.delete(); throw IOException(BAD_CERT) }
        return f
    }
}
