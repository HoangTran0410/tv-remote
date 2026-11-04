package dev.tvremote.core.web
import dev.tvremote.core.scenes.VoicePhrases
/**
 * The page all three remotes serve (app/shared/web), read through [open]: this app's assets, or the folder itself on the computer. The computer's server sends the very same
 * files; [core] tells the page which of the three it is talking to ("tv" or "phone" here, see js/core.js).
 */
class WebPage(private val open: (String) -> java.io.InputStream, private val core: String, private val stamp: String) {
    private val cache = HashMap<String, ByteArray>()
    private val zipped = HashMap<String, ByteArray>()                // gzip of the same bytes, made once: a weak TV must not compress per request

    /** null when the path is not one of the page's files. [acceptEncoding] is the request's header: text files go out gzipped when it allows (1.2 MB -> 0.3 MB on the first load). */
    fun serve(path: String, acceptEncoding: String = ""): HttpServer.Response? {
        val name = if (path == "/" || path == "/index.html") "index.html" else path.drop(1)   // drop: "GET  HTTP/1.1" has an empty path
        if (!FILE.matches(name)) return null
        val bytes = (if (name == VoicePhrases.NAME) VoicePhrases.json() else null) ?: synchronized(cache) {   // the voice phrases: the freshest signed list the app holds, not a copy made once
            cache[name] ?: try {
                var b = open(name).use { it.readBytes() }
                if (name == "index.html") b = page(String(b, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                b.also { if (room(cache, it.size)) cache[name] = it }
            } catch (e: Exception) { null }
        } ?: return null
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"; "css" -> "text/css; charset=utf-8"; "png" -> "image/png"
            "json" -> if (name.startsWith("i18n/") || name.startsWith("voice/")) "application/json; charset=utf-8" else "application/manifest+json"; else -> "text/javascript; charset=utf-8"
        }
        val gz = if (name.endsWith(".png") || bytes.size < 1024 || !GZIP.containsMatchIn(acceptEncoding)) null else gzip(name, bytes)
        val out = HttpServer.Response.of(type, gz ?: bytes)
        if (!name.endsWith(".png")) out.headers["Vary"] = "Accept-Encoding"     // a cache must not hand the plain copy to a browser that wants gzip, or the reverse
        if (gz != null) out.headers["Content-Encoding"] = "gzip"
        // the page names its files with a stamp that changes with every update of the app, so they can be kept; the page itself never is
        out.headers["Cache-Control"] = if (name == "index.html") "no-store" else if (name == VoicePhrases.NAME) "max-age=600" else "max-age=604800"
        return out
    }

    private fun gzip(name: String, plain: ByteArray): ByteArray? = synchronized(zipped) {
        zipped[name] ?: run {
            val b = java.io.ByteArrayOutputStream(plain.size / 3)
            java.util.zip.GZIPOutputStream(b).use { it.write(plain) }
            b.toByteArray().takeIf { it.size < plain.size }.also { if (it != null && room(zipped, it.size)) zipped[name] = it }
        }
    }

    /** Whether one more file fits: by bytes, not a count (the page has ~80 files, 1.4 MB, and a count that falls short re-reads and re-zips the rest on every request). */
    private fun room(m: Map<String, ByteArray>, add: Int) = m.size < 256 && m.values.sumOf { it.size } + add <= BUDGET

    private fun page(html: String): String =
        LINK.replace(html.replaceFirst("<html ", "<html data-core=\"$core\" ")) { it.groupValues[1] + "?v=" + stamp + "\"" }

    private companion object {
        const val BUDGET = 4 shl 20
        val FILE = Regex("index\\.html|css/[a-z0-9_-]+\\.css|stats\\.js|jmuxer\\.min\\.js|js/[a-z0-9_-]+\\.js|manifest\\.json|remote\\.json|i18n/(?:[a-z]{2,3}|langs)\\.json|icon-[a-z0-9]+\\.png|voice/phrases\\.json")   // manifest and icons: "Add to Home screen"; remote.json: the remote's keys, which the Phone app reads too
        val GZIP = Regex("(?i)(^|[\\s,])gzip\\s*(,|;(?!\\s*q=0(\\.0*)?\\s*(,|$))|$)")   // "gzip" as a token, not "gzip;q=0"
        val LINK = Regex("((?:src|href)=\"(?:css/[^\"?]+|stats\\.js|js/[^\"?]+))\"")
    }
}
