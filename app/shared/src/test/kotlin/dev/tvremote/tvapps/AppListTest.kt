package dev.tvremote.tvapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppListTest {
    private val cert = "a".repeat(64)
    private fun parse(apps: String) = AppList.parse("""{"cats":[],"apps":[$apps]}""".toByteArray(), "https://x/")

    // TV Setup Voice: TV Setup installs it by its rule (and removes it when the rule is off), so the list gives no APK for it
    @Test fun anAppTvSetupInstallsNeedsNoApk() {
        val a = parse("""{"id":"tv-voice","pkg":"p.vi","also":["p.en"],"name":"V","setup":"voice","certs":["$cert"]}""").apps.single()
        assertEquals("voice", a.setup)
        assertEquals(listOf("p.vi", "p.en"), a.pkgs)
        assertEquals("", AppList.why(a, 34, listOf("arm64-v8a")))      // fits every processor: TV Setup picks the APK
        assertEquals("sdk", AppList.why(a, 19, emptyList()))
    }

    @Test fun anAppWithoutApkOrSetupIsLeftOut() {
        assertNull(parse("""{"id":"x","pkg":"p","name":"X","certs":["$cert"],"apk":{}}""")["x"])
        assertEquals(1, parse("""{"id":"x","pkg":"p","name":"X","certs":["$cert"],"apk":{"universal":"https://a/b.apk"}}""").apps.size)
    }

    @Test fun theSignedListParses() {                                    // docs/apps/apps.json as published: every entry kept, TV Voice through TV Setup
        val f = java.io.File("../../docs/apps/apps.json").takeIf { it.isFile } ?: return
        val l = AppList.parse(f.readBytes(), "https://x/")
        assertEquals(org.json.JSONObject(f.readText()).getJSONArray("apps").length(), l.apps.size)
        assertEquals("voice", l["tv-voice"]?.setup)
    }
}
