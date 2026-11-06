package dev.tvremote.core.ontv
import dev.tvremote.core.adb.Adb
import dev.tvremote.home.HomeConfig
import java.io.File
import dev.tvremote.core.adb.Pkg
import java.io.IOException

/**
 * Installing a launcher of the gallery on one TV and making it the home screen, over that TV's ADB: the same steps for the page (Manage),
 * the several-TVs-at-once screens and TV Setup's own screen. Whoever calls it decides what to do about the person's answer ([askOnTv], [await]).
 * Failures are thrown as "launcher:<what>[:<detail>]", which every page turns into words (friendly() in js/core.js, Errors.kt).
 */
class LauncherInstall(private val adb: Adb, private val dir: File, private val g: Launchers.Gallery, private val lang: String = "en") {
    /** [step] (download / install / swap) and how far, for a progress line; [cancelled]: the person's Stop. */
    fun interface Progress { fun at(step: String, pct: Int) }

    companion object {
        private val DOWNLOAD = Any()

        /** TV Setup has the confirmation screen and the watchdog from this feature on ([SetupNeed.MIN]). */
        fun tvSetupHelps(sh: (String) -> String): Boolean = SetupNeed.ok(SetupNeed.have(sh), "launcher")

        /** An APK kept for the next TV of a batch: one download for all (named by its hash, so another version is another file). */
        fun cached(dir: File, e: Launchers.Entry, a: Launchers.Apk) = File(dir, "launcher-${e.id}-${a.sha256.take(10)}.apk")

        /** Old downloads go: a launcher is 10 MB, and the folder is the app's own. */
        fun sweep(dir: File) {
            val old = System.currentTimeMillis() - 3600_000L
            dir.listFiles { f -> f.name.startsWith("launcher-") && f.name.endsWith(".apk") && f.lastModified() < old }?.forEach { it.delete() }
        }
    }

    private val sh: (String) -> String = { adb.shell(it) }
    private val swap = LauncherSwap(sh)

    fun facts(): Launchers.Facts = Launchers.facts(sh)

    @Throws(IOException::class)
    fun installed(pkg: String): Boolean = Pkg.installed(sh, pkg)

    /**
     * The launcher on the TV and chosen as its home. With [confirmSec] > 0 the record waits for Keep ([await]); 0 is for a batch, where nobody
     * is at each TV: the home is still checked, rolled back at once if it did not land, and the watchdog guards it after.
     * @return the swap's state: "pending" or "kept" (everything else is thrown)
     */
    @Throws(IOException::class)
    fun run(e: Launchers.Entry, confirmSec: Int, progress: Progress? = null, cancelled: () -> Boolean = { false }): String {
        val f = facts()
        val fit = Launchers.fits(e, f)
        if (!fit.ok) throw IOException("launcher:fit:" + fit.why)
        val before = swap.homeNow()                                   // before the APK goes in: Android 9 forgets it then (LauncherSwap.take)
        if (!installed(e.pkg)) {
            if (e.kind == "play") throw IOException("launcher:play")
            val a = if (e.kind == "self") Launchers.self() else Launchers.apk(e, f.abis) ?: throw IOException("launcher:fit:abi")
            val file = cached(dir, e, a)
            synchronized(DOWNLOAD) {                                  // several TVs at once: the first downloads, the others find the file
                if (!(file.isFile && sha(file) == a.sha256)) Launchers.download(a, file, { progress?.at("download", it * 6 / 10) }, cancelled)
            }
            if (cancelled()) throw IOException("stopped")
            progress?.at("install", 65)
            val out = try { Setup(adb, dir, lang) { }.install(file) } finally { if (cancelled()) file.delete() }
            if (!out.contains("Success")) throw IOException("launcher:install:" + out.trim().lines().lastOrNull().orEmpty().take(200))
            if (!installed(e.pkg)) throw IOException("launcher:install:removed by the TV right after install")   // some boxes drop apps they do not allow
            progress?.at("install", 75)
            // compiled ahead now, not on first use: a home screen started cold on a slow TV chip took ~1.6 s, 0.4 s compiled (cleanyourtv #40)
            try { adb.shell((if (e.kind == "self") HomeConfig.GRANT_WIFI else "") + "cmd package compile -m speed -f ${e.pkg} >/dev/null 2>&1", 120000) } catch (x: IOException) { }
        }
        else sh(AppRestore.command(e.pkg))                            // on the TV but turned off (the gallery says "disabled"): back on first
        english(e, f.sdk)
        progress?.at("swap", 85)
        if (cancelled()) throw IOException("stopped")
        if (!swap.take(e.pkg, confirmSec, before)) {                  // not the home screen: already put back
            val why = swap.refused.ifEmpty { swap.state()?.why ?: "" }
            if (why in setOf("home", "interrupted", "wifi_unknown", LauncherSwap.KEYS, ""))   // the TVs the swap does not know yet, to the census (Setup.report)
                try { Setup.report("launcher:" + why.ifEmpty { "?" }, adb.shell(Setup.ABOUT), e.pkg + (if (swap.opened.isNotEmpty()) " (Home opens ${swap.opened})" else "")) } catch (x: IOException) { }
            throw IOException(if (why in LauncherSwap.REFUSED) "launcher:rolledback:$why" else "launcher:home")   // what the person can act on
        }
        if (swap.via in setOf("home", "wifi_unknown"))               // Home by the key filter on a TV the swap does not know yet: to the census too
            try { Setup.report("launcher:key:" + swap.via, adb.shell(Setup.ABOUT), e.pkg + (if (swap.opened.isNotEmpty()) " (Home opens ${swap.opened})" else "")) } catch (x: IOException) { }
        return swap.state()?.state ?: "kept"
    }

    /**
     * A launcher without the TV's language is told to show English ([Launchers.english]), through Android 13's language per app, which
     * changes nothing else on the TV. A language the person chose for it stays. True when it was set.
     */
    fun english(e: Launchers.Entry, sdk: Int): Boolean {
        if (sdk < 33 || !Launchers.english(e, Launchers.tvLang(sh))) return false
        if (!sh("cmd locale get-app-locales ${e.pkg} 2>&1").trimEnd().endsWith("[]")) return false   // one already set, by the person or by us
        return !sh("cmd locale set-app-locales ${e.pkg} --locales en-US 2>&1").contains("rror")
    }

    /** The question on the TV's own screen, when TV Setup there can show it ([tvSetupHelps]). True when it was asked. */
    fun askOnTv(e: Launchers.Entry): Boolean {
        val r = swap.state()?.takeIf { it.state == "pending" } ?: return false
        if (!tvSetupHelps(sh)) return false
        val out = sh("am start -n ${RulesClient.SETUP}/.LauncherConfirmActivity --es pkg ${e.pkg} --es name ${Adb.q(e.name)} --el until ${r.until} 2>&1")
        return !out.contains("Error")
    }

    /**
     * Waits for the answer: kept / rolledback:<why>. The pages and the TV's screen answer by [LauncherSwap.keep] / [LauncherSwap.rollback]; without
     * an answer in time the swap is rolled back here (and by the watchdog, if this caller is gone). [tick] gets the seconds left.
     */
    @Throws(IOException::class)
    fun await(tick: (Int) -> Unit = { }, cancelled: () -> Boolean = { false }): String {
        while (true) {
            val r = swap.state() ?: return "rolledback:gone"
            when (r.state) {
                "kept" -> return "kept"
                "rolledback" -> return "rolledback:" + r.why
                "pending" -> {
                    val left = (r.until - swap.tvNow()).toInt()
                    if (left < -5 || cancelled()) { swap.rollback(if (cancelled()) "user" else "timeout"); return "rolledback:" + if (cancelled()) "user" else "timeout" }
                    tick(maxOf(0, left))
                }
                else -> return "rolledback:" + r.why
            }
            Thread.sleep(1000)
        }
    }

    fun keep() = swap.keep()
    fun back(why: String = "user") = swap.rollback(why)

    private fun sha(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n <= 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }
}
