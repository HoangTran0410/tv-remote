package dev.tvremote.core.manage
import java.io.IOException

/**
 * Split-APK bundles (.apks from bundletool / SAI, .xapk from APKPure, .apkm from APKMirror: all plain zips of APKs, sometimes
 * with an Android/obb/ folder) read from `unzip -l` on the TV, and the few parts that fit that TV chosen: what is not a
 * "config" split (the base, features) always, one CPU split, the nearest screen density, the TV's language and English.
 * Pure text in, text out, so the choosing is unit tested (SplitsTest). The errors are words the page translates.
 */
internal object Splits {
    class Entry(val name: String, val size: Long)
    /** [parts] to install, the base first; [obbs] go to /sdcard/Android/obb/<package>/ after it. */
    class Plan(val parts: List<Entry>, val obbs: List<Entry>)

    const val MISSING = "bundle:missing"                             // no base APK in it
    const val LOCKED = "bundle:locked"                                // its parts cannot be read: a password, or a damaged file

    private val LINE = Regex("""^\s*(\d+)\s+\S+\s+\S+\s+(.+?)\s*$""")
    private val DENSITY = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
    private val ANY = setOf("nodpi", "anydpi")
    private val LANG = Regex("^[a-z]{2,3}([_-]r?[A-Za-z]{2,4})?$")
    private val OBB = Regex("^Android/obb/([A-Za-z]\\w*(?:\\.\\w+)+)/[\\w.-]+\\.obb$")   // a package name only: never ../ out of obb/

    /** `unzip -l` lines as (name, size); the header and the totals line do not match. */
    fun list(listing: String): List<Entry> = listing.lines().mapNotNull { l ->
        LINE.find(l)?.let { Entry(it.groupValues[2], it.groupValues[1].toLongOrNull() ?: 0L) }
    }

    fun hasApks(all: List<Entry>) = all.any { it.name.endsWith(".apk", true) && !skipped(it.name) }

    // bundletool also writes whole-app APKs for old phones and instant apps beside the splits: not for this install
    private fun skipped(n: String) = n.startsWith("standalones/") || n.startsWith("instant/") || n.startsWith("archive/")

    /** The part of a file name that says what the split is for ("arm64_v8a", "xxhdpi", "en"), or null for a base or a feature. */
    fun qualifier(entry: String): String? {
        val stem = entry.substringAfterLast('/').removeSuffix(".apk").removeSuffix(".APK")
        Regex("^(?:split_)?config\\.(.+)$").find(stem)?.let { return it.groupValues[1].replace('-', '_') }
        return if (stem.contains('-')) stem.substringAfterLast('-').replace('-', '_') else null
    }

    private fun isAbi(q: String) = q.startsWith("arm") || q.startsWith("x86") || q.startsWith("mips") || q.startsWith("riscv")
    private fun isDensity(q: String) = q in DENSITY || q in ANY
    private fun isLang(q: String) = LANG.matches(q.replace('_', '-'))

    /**
     * [abis] the TV's CPUs best first (`ro.product.cpu.abilist`), [dpi] its screen density, [langs] its languages ("vi").
     * Throws "apk is built for <abis>, the tv runs <abis>" when the bundle has CPU splits and none is the TV's, [MISSING] without a base.
     */
    fun pick(all: List<Entry>, abis: List<String>, dpi: Int, langs: List<String>): Plan {
        val apks = all.filter { it.name.endsWith(".apk", true) && !skipped(it.name) }
        val keep = ArrayList<Entry>()
        val cpu = ArrayList<Pair<String, Entry>>(); val dens = ArrayList<Pair<Int, Entry>>()
        for (e in apks) {
            val q = qualifier(e.name)
            when {
                q == null || q.startsWith("master") -> keep.add(e)
                isAbi(q) -> cpu.add(q to e)
                isDensity(q) -> if (q in ANY) keep.add(e) else dens.add(DENSITY.getValue(q) to e)
                isLang(q) -> if (q.substringBefore('_').lowercase() in langs + "en") keep.add(e)
                else -> keep.add(e)                                   // features and anything unknown: leave it in
            }
        }
        if (keep.none { qualifier(it.name).let { q -> q == null || q.startsWith("master") } }) throw IOException(MISSING)
        if (cpu.isNotEmpty()) {
            val names = cpu.map { it.first }
            val best = abis.map { it.replace('-', '_') }.firstOrNull { it in names }
                ?: throw IOException("apk is built for ${names.joinToString(",").replace('_', '-')}, the tv runs ${abis.joinToString(",")}")
            keep.addAll(cpu.filter { it.first == best }.map { it.second })
        }
        if (dens.isNotEmpty()) {
            val want = if (dpi > 0) dpi else 320
            val near = dens.map { it.first }.distinct().minWith(compareBy<Int> { Math.abs(it - want) }.thenByDescending { it })
            keep.addAll(dens.filter { it.first == near }.map { it.second })
        }
        // the base first: pm wants it named base.apk, and an app without a "master" entry has its own name for it
        val base = keep.firstOrNull { qualifier(it.name) == null } ?: keep.first { qualifier(it.name)!!.startsWith("master") }
        val parts = listOf(base) + keep.filter { it !== base }
        return Plan(parts, all.filter { OBB.matches(it.name) })
    }

    /** The package of an .obb entry's folder (Android/obb/<package>/main.1.<package>.obb). */
    fun obbPackage(e: Entry): String? = OBB.find(e.name)?.groupValues?.get(1)

    /** A zip entry name as `unzip` takes it: its wildcard characters taken literally. */
    fun pattern(name: String): String = name.replace(Regex("[\\\\*?\\[]")) { "\\" + it.value }

    /** The TV's language(s) from its locale property ("en-US", "vi_VN", "" when unset), as plain codes. */
    fun langs(vararg props: String): List<String> =
        props.map { it.trim().substringBefore('-').substringBefore('_').lowercase() }.filter { it.length in 2..3 }.distinct()

    /** The density `wm density` says ("Physical density: 320", then "Override density: 240" when set): the last number. */
    fun density(wm: String): Int = Regex("density:\\s*(\\d+)").findAll(wm).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** New / an update / the same version, from the version the TV has (`dumpsys package` "versionCode=12", "" when absent) and the file's. */
    fun how(have: String, file: String?): String {
        val h = Regex("versionCode=(\\d+)").find(have)?.groupValues?.get(1)?.toLongOrNull() ?: return "new"
        val f = file?.toLongOrNull() ?: return "update"
        return if (f > h) "update" else if (f == h) "same" else "older"
    }
}
