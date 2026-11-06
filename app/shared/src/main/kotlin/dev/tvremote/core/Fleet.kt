package dev.tvremote.core
import java.io.IOException
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.Pkg
import dev.tvremote.core.ontv.Setup
import dev.tvremote.core.adb.TvShell

/**
 * Several saved TVs at once, as the phone app does it from its list of TVs (the computer: app/desktop Fleet.kt, same reading and
 * actions): what each TV is, in one round trip, and the actions that are not an install (that one is Manage.install).
 * TV Setup has no list of TVs: it manages only the TV it runs on.
 */
object Fleet {
    /** The parts between "#~#" lines: make, model, Android, sdk · df of /data · MemTotal · user apps · TV Setup's version. */
    const val INFO = "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; " +
        "printf '\\043~\\043\\n'; df -k /data | tail -1; printf '\\043~\\043\\n'; grep MemTotal /proc/meminfo; printf '\\043~\\043\\n'; " +
        "pm list packages -3; printf '\\043~\\043\\n'; dumpsys package dev.tvremote.setup | grep -m1 versionName"

    data class Info(val model: String, val android: String, val sdk: Int, val diskTotal: Long, val diskFree: Long, val ram: Long,
                    val apps: List<String>, val setup: String)

    fun parse(out: String): Info {
        val p = out.replace("\r", "").split("#~#\n") + List(5) { "" }
        val head = p[0].split("\n") + List(4) { "" }
        val df = p[1].trim().split(Regex("\\s+"))
        val disk = df.size >= 4 && df[1].all { it.isDigit() } && df[3].all { it.isDigit() } && df[1].isNotEmpty() && df[3].isNotEmpty()
        return Info(
            model = "${head[0].trim()} ${head[1].trim()}".trim(), android = head[2].trim(), sdk = head[3].trim().toIntOrNull() ?: 0,
            diskTotal = if (disk) df[1].toLong() * 1024 else 0, diskFree = if (disk) df[3].toLong() * 1024 else 0,
            ram = Regex("MemTotal:\\s+(\\d+)").find(p[2])?.groupValues?.get(1)?.toLong()?.times(1024) ?: 0,
            apps = p[3].split("\n").filter { it.startsWith("package:") }.map { it.substring(8).trim() }.sorted(),
            setup = Regex("versionName=(\\S+)").find(p[4])?.groupValues?.get(1) ?: "")
    }

    @Throws(IOException::class)
    fun info(adb: Adb): Info = parse(adb.shell(INFO, 15000))

    /** Refused here, before any TV is touched: a name that is not a package, or an app the TV or this project needs. */
    fun canUninstall(pkg: String): String? =
        if (!Pkg.valid(pkg)) "bad package name"
        else if (Setup.mustKeep(pkg)) "protected: the TV needs this app" else null

    @Throws(IOException::class)
    fun uninstall(adb: Adb, pkg: String) {
        canUninstall(pkg)?.let { throw IOException(it) }
        if (dev.tvremote.core.ontv.LauncherSwap.last(adb.shell(dev.tvremote.core.ontv.LauncherSwap.RESOLVE_HOME)).startsWith("$pkg/"))
            throw IOException("protected: the TV needs this app")                   // its home screen
        if (Setup.inUse({ adb.shell(it) }, pkg)) throw IOException("protected: the TV needs this app")
        val out = adb.shell("pm uninstall $pkg 2>&1").trim()
        if (!out.contains("Success")) throw IOException(out.ifEmpty { "uninstall failed" })
    }

    /** Several apps off one TV: those it has (the others were picked for the other TVs). How many went; what failed, thrown. */
    @Throws(IOException::class)
    fun uninstall(adb: Adb, pkgs: List<String>): Int {
        pkgs.forEach { p -> canUninstall(p)?.let { throw IOException(it) } }
        val has = Pkg.list({ adb.shell(it) })
        val bad = ArrayList<String>()
        var n = 0
        for (p in pkgs) if (p in has) try { uninstall(adb, p); n++ } catch (e: IOException) { bad += "$p: ${e.message}" }
        if (bad.isNotEmpty()) throw IOException(bad.joinToString("; "))
        return n
    }

    @Throws(IOException::class)
    fun reboot(adb: Adb) {
        adb.shell(TvShell.REBOOT_PREP, 15000)
        try { adb.open("reboot:").use { it.readAll(3000) } } catch (ignored: Exception) { }   // the link drops as the TV goes down
    }
}
