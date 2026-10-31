package dev.tvremote.core.web
import java.util.Locale

/** Where files may go on the TV and whether they fit: the paths and names the page sends are cleaned here before any shell sees them. */
internal object TvPaths {
    const val CAST_DIR = "/sdcard/Download"
    const val TMP_DIR = "/data/local/tmp"
    const val TMP_APK = "$TMP_DIR/tvremote-install.apk"
    private const val RESERVE = 16L shl 20                          // never fill the TV to the last byte: it needs room to run
    private val DIGITS = Regex("\\d+")

    /** Bytes a copy of `size` needs free: `factor` times it (an APK is copied, then installed) and the reserve. */
    internal fun needed(size: Long, factor: Int): Long = size * factor + RESERVE
    internal fun roomFor(size: Long, factor: Int, free: Long): Boolean = free >= needed(size, factor)

    /** The "Available" column of `df -k`'s last line, in bytes (a long device name may wrap onto the line before). */
    internal fun parseDfFree(out: String): Long? {
        val last = out.trim().lines().lastOrNull { it.isNotBlank() }?.trim()?.split(Regex("\\s+")) ?: return null
        val at = if (last.size >= 6 && last[1].matches(DIGITS)) 3 else if (last.size == 5 && last[0].matches(DIGITS)) 2 else return null
        return last[at].takeIf { it.matches(DIGITS) }?.toLong()?.times(1024)
    }

    /** Only the shared storage and USB drives; no "..". */
    internal fun safePath(p: String?): String {
        val b = StringBuilder()
        for (part in (if (p.isNullOrEmpty()) "/sdcard/Download" else p).jsplit("/")) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") throw IllegalArgumentException("bad path")
            b.append('/').append(part)
        }
        val s = b.toString()
        if (!(s == "/sdcard" || s == "/storage" || s.startsWith("/sdcard/") || s.startsWith("/storage/"))) throw IllegalArgumentException("path must be under /sdcard or /storage")
        return s
    }

    internal fun safeName(n: String?): String {
        val s = (n ?: "").replace(Regex(".*[/\\\\]"), "").replace(Regex("[^\\p{L}\\p{N}_.\\- ()\\[\\]]"), "_").replace(Regex("^[. ]+|[. ]+\$"), "")
        return if (s.isEmpty()) "file" else if (s.length > 120) s.substring(0, 120) else s
    }

    /** A whole storage, never deleted or renamed: /sdcard, /storage, a volume, and /storage/emulated/0 or /storage/self/primary. */
    internal fun root(path: String): Boolean {
        val parts = path.split("/").filter { it.isNotEmpty() }
        return path == "/sdcard" || parts.firstOrNull() == "storage" && (parts.size <= 2 || parts.size == 3 && parts[1] in setOf("emulated", "self"))
    }

    internal fun mime(path: String): String = when (path.substring(path.lastIndexOf('.') + 1).lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "ogg" -> "audio/ogg"
        "apk" -> "application/vnd.android.package-archive"; "pdf" -> "application/pdf"; "txt" -> "text/plain"
        else -> "*/*"
    }
}
