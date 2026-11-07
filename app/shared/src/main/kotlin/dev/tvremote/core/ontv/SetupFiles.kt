package dev.tvremote.core.ontv

import dev.tvremote.core.B64
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * Reading a file TV Setup keeps on the TV (its external files folder: stats/, ...) from a phone or computer with ADB.
 * The shell reads it where it may; some TVs do not let the shell into apps' folders (a TCL on Android 11: "Permission
 * denied"), and there TV Setup hands it over itself through RulesReceiver (`--es get file`, gzipped: a broadcast's answer
 * holds about a megabyte). Any new reader of TV Setup's files goes through here; a writer
 * that meets the same wall uses `content write` into HomeInfo.openFile (Video.file).
 */
object SetupFiles {
    const val DIR = "/sdcard/Android/data/dev.tvremote.setup/files"
    private val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_./-]*")

    /** The last [lines] lines of [name] (under [DIR], e.g. "stats/samples.jsonl"); "" when there is none. When the shell may
     *  not read it and TV Setup cannot hand it over (missing, or older than `get file`): [SetupNeed.Missing] "files". */
    fun read(sh: (String) -> String, name: String, lines: Int): String {
        require(NAME.matches(name) && ".." !in name) { "bad name" }
        val out = sh("tail -n $lines $DIR/$name 2>&1")
        if (!out.startsWith("tail:")) return out
        if (!out.lineSequence().first().contains("denied")) return ""     // no such file yet
        return viaSetup(sh, name, lines) ?: legacy(sh, name)
            ?: throw SetupNeed.Missing("files", SetupNeed.have(sh))
    }

    /** TV Setup reads it itself; null without an answer (no TV Setup, or one older than `get file`). */
    fun viaSetup(sh: (String) -> String, name: String, lines: Int): String? {
        val out = sh("am broadcast -n ${RulesClient.SETUP}/.RulesReceiver --es get file --es name $name --ei lines $lines 2>&1")
        val m = Regex("data=\"([A-Za-z0-9+/=]*)\"").find(out) ?: return null
        if (m.groupValues[1].isEmpty()) return ""                     // it has no such file
        return try { String(GZIPInputStream(B64.decode(m.groupValues[1]).inputStream()).readBytes(), Charsets.UTF_8) } catch (e: Exception) { null }
    }

    /** TV Setups before `get file` knew this one file only. */
    private fun legacy(sh: (String) -> String, name: String): String? = if (name != "stats/power.jsonl") null else {
        val m = Regex("data=\"([A-Za-z0-9+/=]+)\"").find(sh("am broadcast -n ${RulesClient.SETUP}/.RulesReceiver --es get power 2>&1"))
        m?.let { try { JSONObject(String(B64.decode(it.groupValues[1]), Charsets.UTF_8)).optString("power") } catch (e: Exception) { null } }
    }
}
