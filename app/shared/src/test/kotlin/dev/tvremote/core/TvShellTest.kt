package dev.tvremote.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import dev.tvremote.core.adb.TvShell

/** TvShell gives what tests/tvshell_cases.json says, for every case (the page reads the same output: js/tvread.js). */
class TvShellTest {
    private val cases = JSONObject(File("tests/tvshell_cases.json").readText())

    @Test fun constants() {
        val c = cases.getJSONObject("consts")
        assertEquals(c.getString("CLEAN"), TvShell.CLEAN)
        assertEquals(c.getString("REBOOT_PREP"), TvShell.REBOOT_PREP)
        assertEquals(c.getString("TVSET_READ"), TvShell.TVSET_READ)
        assertEquals(c.getString("BOOT"), TvShell.BOOT)
        assertEquals(c.getString("SETTINGS_READ"), TvShell.SETTINGS_READ)
    }

    @Test fun calls() {
        val calls = cases.getJSONArray("calls")
        for (i in 0 until calls.length()) {
            val o = calls.getJSONObject(i)
            val a = o.getJSONArray("call")
            val got = try {
                when (a.getString(0)) {
                    "backup" -> TvShell.backup(a.getString(1))
                    "restore" -> TvShell.restore(a.getString(1))
                    "log" -> TvShell.log(a.getString(1), a.getString(2))
                    "app_info" -> TvShell.appInfo(a.getString(1))
                    "perm" -> TvShell.perm(a.getString(1), a.getString(2), a.getBoolean(3))
                    "setting" -> TvShell.setting(a.getString(1), a.getString(2), a.getString(3))
                    else -> TvShell.tvset(a.getString(1), a.get(2).toString())
                }
            } catch (e: IllegalArgumentException) { null }
            assertEquals(a.toString(), if (o.isNull("out")) null else o.getString("out"), got)
        }
    }

    /** search() run by a real sh against a fake TV: the YouTube app is found by its links, browsers and Downloader are not (FPT, 2026-11-01). */
    @Test fun searchPicksTheYoutubeApp() {
        fun run(youtube: String, any: String): String {
            val fake = "cmd() { case \"$*\" in *youtube.com*) printf '$youtube';; *) printf '$any';; esac; }; pm() { :; }; " +
                "exec 3>&1; am() { echo \"started $*\" >&3; }; "
            val p = ProcessBuilder("sh", "-c", fake + TvShell.search("tom and jerry")).redirectErrorStream(true).start()
            return p.inputStream.bufferedReader().readText().trim()
        }
        val browsers = "    com.esaba.downloader/.Main\\n    com.android.tv.frameworkpackagestubs/.BrowserStub\\n"
        val fpt = run("    dev.cobalt.youtube/dev.cobalt.app.MainActivity\\n$browsers", browsers)
        org.junit.Assert.assertTrue(fpt, fpt.contains("-p dev.cobalt.youtube") && fpt.contains("tom+and+jerry"))
        val none = run(browsers, browsers)
        org.junit.Assert.assertTrue(none, none.contains("GLOBAL_SEARCH"))
    }
}
