package dev.tvremote.update

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import dev.tvremote.core.B64

/**
 * Update check shared by every app (here in src/sign, beside Ed25519, so TV Home, TV Channels and TV Games have it without
 * :shared; SelfUpdate is theirs): asks GitHub for the latest release of this project and, if it is newer than the running
 * app, gives the download link of this app's APK. Nothing else is sent anywhere. B64, not java.util.Base64 (Android 8+).
 *
 * A release lists the SHA-256 of each of its files in SHA256SUMS, signed (SHA256SUMS.sig, Ed25519) by a key that never goes
 * to GitHub (dev/release/Sign.java, dev/release/release.sh): a file that does not match is deleted, not installed, so neither a broken
 * download nor someone who took over the repository or a GitHub token can put an update on anyone's device. A release
 * without them is not offered yet (dev/release/release.sh adds them last, once the computer apps are attached).
 */
class Release private constructor(val version: String, val url: String, val notes: String, val sha256: String,
                                   val published: Long = 0) {     // ms, GitHub's published_at; 0 when unknown (the release page way, [byTag])
    fun interface Progress { fun at(percent: Int) }

    /** Downloads this file to [to] and checks it against the signed SHA256SUMS: a file that does not match is deleted. */
    @Throws(IOException::class)
    fun download(to: File, progress: Progress?) {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            val md = MessageDigest.getInstance("SHA-256")
            c.inputStream.use { input -> FileOutputStream(to).use { out -> copy(input, out, c.contentLength, progress, md) } }
            check(md)
        } catch (e: Exception) {
            to.delete(); throw e
        } finally {
            c.disconnect()
        }
    }

    /** For a download that goes elsewhere (straight to the TV): [md] has seen every byte of it. */
    @Throws(IOException::class)
    fun check(md: MessageDigest) {
        if (hex(md.digest()) != sha256) throw IOException(MISMATCH)
    }

    companion object {
        const val REPO = "HoangTran0410/tv-remote"
        /** The keys a release's SHA256SUMS may be signed with (raw Ed25519 public keys): the one in use, and a spare kept
         *  apart from it, so losing one does not leave every installed app refusing updates. */
        val KEYS = listOf(
            "8bda551ed91896e42bafabe7cee2b95f8401e33da105b3a092c333db4dc96b3a",
            "510ce48a7e710113b56e9c51c70ce3023be5188c722e4e304c159729481d9149",
        )
        const val MISMATCH = "update file does not match its signed checksum"   // Errors.say words it
        const val UNSIGNED = "update list not signed by this project"
        var api = "https://api.github.com"                            // tests of the computer app's updater: a fake GitHub (TVR_RELEASES)
        var testKeys = emptyList<String>()                            // with that fake GitHub only: the key it signs with (TVR_RELEASE_KEY)

        /**
         * The latest release, if it carries a newer version of this app. Each app has its own version number: a release lists
         * them in a small file, versions.json ({"android": "2.1.0", "tv": "2.0.3"}), so an app is only offered an update when
         * it changed itself. Releases without that file (before 2.1) are compared by their tag. null when up to date.
         */
        @Throws(Exception::class)
        fun newer(current: String, app: String, asset: String): Release? {
            val c = open("$api/repos/$REPO/releases/latest")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (c.responseCode == 404) return null                // no release published yet
                if ((c.responseCode == 403 || c.responseCode == 429) && api == "https://api.github.com") return byTag(current, app, asset)
                if (c.responseCode != 200) throw IOException("GitHub HTTP " + c.responseCode)
                val o = JSONObject(text(c))
                val version = o.getString("tag_name").replaceFirst(Regex("^v"), "")
                var apk: String? = null
                var versions: String? = null
                var sums: String? = null
                var sig: String? = null
                val assets = o.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val u = a.getString("browser_download_url")
                    when (a.getString("name")) {
                        asset -> apk = u
                        "versions.json" -> versions = u
                        "SHA256SUMS" -> sums = u
                        "SHA256SUMS.sig" -> sig = u
                    }
                }
                if (apk == null || sums == null || sig == null) return null   // still being put together (or from before the checksums)
                return checked(current, app, asset, version, apk, versions, sums, sig, o.optString("body", "").trim(), time(o.optString("published_at")))
            } finally {
                c.disconnect()
            }
        }

        /**
         * The API's 60 asks an hour are per address, and one home shares one: a phone, a computer and the TV apps checking
         * used them up (GitHub HTTP 403). The release page is not counted that way: its redirect names the latest tag, and the
         * files sit at fixed links under it. The notes are not there; the signed checksums are, so nothing is trusted less.
         */
        private fun byTag(current: String, app: String, asset: String): Release? {
            val c = open("https://github.com/$REPO/releases/latest")
            c.instanceFollowRedirects = false
            val tag = try {
                c.getHeaderField("Location")?.substringAfterLast("/tag/", "")?.takeIf { it.isNotEmpty() && it.length < 40 }
            } finally { c.disconnect() } ?: throw IOException("GitHub HTTP 403")
            val base = "https://github.com/$REPO/releases/download/$tag/"
            val sums = base + "SHA256SUMS"
            if (!exists(sums)) return null                            // still being put together (or from before the checksums)
            val versions = (base + "versions.json").takeIf { exists(it) }
            return checked(current, app, asset, tag.replaceFirst(Regex("^v"), ""), base + asset, versions, sums, base + "SHA256SUMS.sig", "")
        }

        private fun exists(url: String): Boolean {
            val c = open(url)
            c.requestMethod = "HEAD"
            try { return c.responseCode == 200 } finally { c.disconnect() }
        }

        private fun checked(current: String, app: String, asset: String, tagVersion: String, apk: String, versions: String?,
                            sums: String, sig: String, body: String, published: Long = 0): Release? {
            var version = tagVersion
            if (versions != null) version = JSONObject(get(versions)).optString(app, version)
            if (compare(version, current) <= 0) return null
            val list = get(sums)
            if (!signed(list.toByteArray(Charsets.UTF_8), get(sig))) throw IOException(UNSIGNED)
            val signedVersion = signedVersion(list, app)
            if (signedVersion != null && signedVersion != version) throw IOException(UNSIGNED)   // an old signed list under a newer version.json
            val sha = sum(list, asset) ?: return null
            return Release(version, apk, body, sha, published)
        }

        /** GitHub's "2026-11-07T10:00:00Z" in ms; 0 when absent or not that shape (java.time is Android 8+, minSdk is 21). */
        fun time(iso: String?): Long = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso ?: "")?.time ?: 0L
        } catch (e: Exception) { 0L }

        private fun get(url: String): String {
            val c = open(url)
            try {
                if (c.responseCode != 200) throw IOException("GitHub HTTP " + c.responseCode)
                return text(c)
            } finally {
                c.disconnect()
            }
        }

        /** [sig] (base64) is one of [KEYS]' signature of [list]. */
        fun signed(list: ByteArray, sig: String): Boolean {
            val raw = try { B64.decode(sig.trim()) } catch (e: IllegalArgumentException) { return false }
            return (KEYS + testKeys).any { k -> Ed25519.verify(ByteArray(32) { k.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, list, raw) }
        }

        /** The SHA-256 of [name] in a SHA256SUMS list ("<hex>  <name>" per line, as sha256sum writes it). */
        fun sum(list: String, name: String): String? = list.lineSequence()
            .map { it.trim().split(Regex("\\s+\\*?"), 2) }
            .firstOrNull { it.size == 2 && it[1] == name && it[0].matches(Regex("[0-9a-fA-F]{64}")) }?.get(0)?.lowercase()

        /** The version [app] has in the signed "version web=0.4.1 android=0.4.1 tv=0.4.1" line, null for releases without it. */
        fun signedVersion(list: String, app: String): String? = list.lineSequence()
            .map { it.trim() }.firstOrNull { it.startsWith("version ") }
            ?.removePrefix("version ")?.split(Regex("\\s+"))
            ?.map { it.split("=", limit = 2) }?.firstOrNull { it.size == 2 && it[0] == app }?.get(1)

        fun hex(b: ByteArray): String = b.joinToString("") { String.format("%02x", it) }

        private fun text(c: HttpURLConnection): String {
            val buf = ByteArrayOutputStream()
            c.inputStream.use { copy(it, buf, 0, null, null) }
            return buf.toString("UTF-8")
        }

        /** [Release.brief]'s answer: the lines to show, and how many more the full notes have. */
        class Brief(val items: List<String>, val more: Int)

        /**
         * The notes made short for an update popup. They are one Markdown body for every app and both languages (~40 long
         * bullets, Vietnamese first): cut at 900 characters, an English reader saw only Vietnamese. So: the reader's section
         * ("**Tiếng Việt**" / "**English**"), its top-level bullets, each to its first sentence, those naming [prefer] (this
         * app) first, at most [max]; the rest is a link to the release page.
         */
        fun brief(notes: String, vi: Boolean, prefer: List<String> = emptyList(), max: Int = 6): Brief {
            val lines = notes.replace("\r", "").lines()
            val head = Regex("^\\*\\*(.+)\\*\\*$")
            val at = lines.indexOfFirst { it.trim() == if (vi) "**Tiếng Việt**" else "**English**" }
            val section = if (at < 0) lines else lines.drop(at + 1).takeWhile { !head.matches(it.trim()) }
            val bullets = section.filter { it.startsWith("- ") }.map { it.substring(2) }
                .ifEmpty { if (at < 0) section.filter { it.isNotBlank() && !head.matches(it.trim()) } else emptyList() }
            val clean = bullets.map { short(it.replace("**", "").replace("`", "").trim()) }.filter { it.isNotEmpty() }
            val (mine, rest) = clean.partition { b -> prefer.any { b.contains(it, ignoreCase = true) } }
            val all = mine + rest
            return Brief(all.take(max), maxOf(0, all.size - max))
        }

        /** The first sentence or clause (past a "TV Home:" kind of start; not at a bracket, which cut "the wallpaper (…)" short), at most ~120 characters, cut at a space. */
        private fun short(s: String): String {
            var end = s.length
            for ((stop, from) in listOf(". " to 24, "; " to 24, ": " to 44)) { val i = s.indexOf(stop, from); if (i in 0 until end) end = i }
            var t = s.substring(0, end).trimEnd('.', ',', ':', ' ')
            if (t.length > 120) t = t.substring(0, t.lastIndexOf(' ', 118).takeIf { it > 60 } ?: 118).trimEnd(',', ':', ' ') + "…"
            return t
        }

        /** "1.10.0" is newer than "1.9": compares the numbers between the dots. */
        fun compare(a: String, b: String): Int {
            val la = legacy(a)
            if (la != legacy(b)) return if (la) -1 else 1
            val x = a.trim().split(".")
            val y = b.trim().split(".")
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = num(x, i).compareTo(num(y, i))
                if (d != 0) return d
            }
            return 0
        }

        /**
         * The numbers before they restarted at 0.1.0 (October 2026: 1.3.0 up to 2.24.2) are older than every later one, so a TV
         * or computer still on them is offered the update. dev/release/release.sh refuses to reach 1.3.0 while this is here: drop it then.
         */
        fun legacy(v: String): Boolean {
            val p = v.trim().split(".")
            return num(p, 0) == 2 || (num(p, 0) == 1 && num(p, 1) >= 3)
        }

        private fun num(parts: List<String>, i: Int): Int =
            if (i < parts.size) parts[i].replace(Regex("\\D.*"), "").toIntOrNull() ?: 0 else 0

        private fun open(url: String): HttpURLConnection {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "tv-remote")
            return c
        }

        private fun copy(input: InputStream, out: OutputStream, total: Int, progress: Progress?, md: MessageDigest?) {
            val b = ByteArray(65536)
            var done = 0L
            var last = -1
            while (true) {
                val n = input.read(b)
                if (n <= 0) break
                out.write(b, 0, n)
                md?.update(b, 0, n)
                done += n
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                if (progress != null && pct != last) { last = pct; progress.at(pct) }
            }
            if (total > 0 && done != total.toLong()) throw IOException("download incomplete ($done of $total bytes)")   // a dropped connection ends the stream like a finished one
        }
    }
}
