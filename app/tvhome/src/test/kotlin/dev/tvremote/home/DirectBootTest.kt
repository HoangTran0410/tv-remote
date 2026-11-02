package dev.tvremote.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * HomeActivity is directBootAware: after a reboot Android may start it before the TV's first unlock, when reading any of TV
 * Home's own storage throws ("SharedPreferences in credential encrypted storage are not available"). 0.21.0 read the shortcut
 * pins above the unlock check and crashed on a Xiaomi at boot (census 621ab211). Only these lines may run before it; anything
 * new goes below it (or here, once it is known to touch no storage, or only device-protected storage like CrashLoop).
 */
class DirectBootTest {
    private val safe = listOf(
        "super.onCreate(b)",
        "if (CrashLoop.tripped(this)) {",                              // device-protected storage
        "dev.tvremote.home.apps.SystemSettings.open(this)",
        "finish(); return",
        "}",
        "wantStart = intent?.takeIf { it.action in START_ACTIONS }",     // kept, run in onResume (which waits for the unlock)
        "Font.load(this)",                                             // the APK's assets
        "InputOpen.sync(this)",                                        // the package manager
    )

    @Test fun nothingReadsStorageBeforeTheUnlockCheck() {
        val src = File("src/main/kotlin/dev/tvremote/home/HomeActivity.kt").readText()
        val start = src.indexOf("override fun onCreate(b: Bundle?) {")
        val end = src.indexOf("isUserUnlocked", start)
        val before = src.substring(start, src.lastIndexOf('\n', end)).lines().drop(1)
            .map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }
        assertEquals("move these below the isUserUnlocked check", emptyList<String>(), before.filter { it !in safe })
    }
}
