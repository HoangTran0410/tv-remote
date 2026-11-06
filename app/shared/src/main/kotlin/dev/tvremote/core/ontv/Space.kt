package dev.tvremote.core.ontv

/**
 * What the page shows when an install does not fit: the TV's free bytes and the apps that use the most space, which the person can
 * trim (remove, take the updates off, clear the data). One shell call of ~0.15 s and ~9 KB (`dumpsys diskstats` lists the size of
 * every app's code, data and cache; an Android 9 and an Android 14 build were read alike). A build without those lines gives an
 * empty list: the dialog then still has the numbers and the cache button.
 */
object Space {
    private const val SEP = "printf '\\043~\\043\\n'; "
    /** Shell: free of /data, diskstats, the apps the person installed, the system apps that were updated (their file is under /data/app). */
    const val READ = "df -k /data | tail -2; $SEP" + "dumpsys diskstats; $SEP" + "pm list packages -3; $SEP" + "pm list packages -f -s | grep '^package:/data/'"

    class App(val pkg: String, val bytes: Long, val data: Long, val cache: Long, val user: Boolean)

    private fun longs(out: String, key: String): List<Long> =
        Regex("^$key: \\[([^]]*)]", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.split(",")?.map { it.trim().toLongOrNull() ?: 0L } ?: emptyList()

    private fun names(out: String): List<String> =
        Regex("^Package Names: \\[([^]]*)]", RegexOption.MULTILINE).find(out)?.groupValues?.get(1)?.split(",")?.map { it.trim().trim('"') } ?: emptyList()

    /** The biggest [top] apps a person can act on: installed by them, or a system app with updates; never one the TV needs ([keep]). */
    fun top(out: String, keep: (String) -> Boolean, top: Int = 5): List<App> {
        val part = out.split("#~#\n")
        val disk = part.getOrElse(1) { "" }
        val user = part.getOrElse(2) { "" }.lines().filter { it.startsWith("package:") }.map { it.substring(8).trim() }.toSet()
        val updated = part.getOrElse(3) { "" }.lines().filter { it.startsWith("package:") }.map { it.substringAfterLast('=').trim() }.toSet()
        val pkgs = names(disk); val code = longs(disk, "App Sizes"); val data = longs(disk, "App Data Sizes"); val cache = longs(disk, "Cache Sizes")
        return pkgs.indices.mapNotNull { i ->
            val p = pkgs[i]
            if (p.isEmpty() || keep(p) || (p !in user && p !in updated)) return@mapNotNull null
            val c = code.getOrElse(i) { 0L }; val d = data.getOrElse(i) { 0L }; val k = cache.getOrElse(i) { 0L }
            App(p, c + d + k, d, k, p in user)
        }.sortedByDescending { it.bytes }.take(top)
    }

    /** Free bytes of /data, from the first part. Null when the TV did not say. */
    fun free(out: String): Long? = dev.tvremote.core.manage.TvPaths.parseDfFree(out.substringBefore("#~#"))
}
