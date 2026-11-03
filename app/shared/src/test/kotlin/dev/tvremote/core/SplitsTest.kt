package dev.tvremote.core
import dev.tvremote.core.manage.Splits
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SplitsTest {
    private fun names(p: Splits.Plan) = p.parts.map { it.name }
    private fun e(vararg n: String) = n.map { Splits.Entry(it, 10) }

    @Test fun listsUnzipOutput() {
        val l = Splits.list("Archive:  a.zip\n    Length      Date    Time    Name\n ---------  ---------- -----   ----\n   1234  2024-01-02 10:00   split_config.en.apk\n  55  2024-01-02 10:00   Android/obb/com.x/main.1.com.x.obb\n ---------                     -------\n   1289                     2 files\n")
        assertEquals(listOf("split_config.en.apk", "Android/obb/com.x/main.1.com.x.obb"), l.map { it.name })
        assertEquals(1234L, l[0].size)
    }

    @Test fun apkmirrorPicksCpuDensityLanguage() {
        val p = Splits.pick(e("base.apk", "split_config.arm64_v8a.apk", "split_config.armeabi_v7a.apk", "split_config.x86.apk", "split_config.xxhdpi.apk",
            "split_config.hdpi.apk", "split_config.xxxhdpi.apk", "split_config.en.apk", "split_config.vi.apk", "split_config.fr.apk"), listOf("arm64-v8a", "armeabi-v7a"), 320, listOf("vi"))
        // 320 sits between hdpi (240) and xxhdpi (480): the nearer, hdpi
        assertEquals(listOf("base.apk", "split_config.en.apk", "split_config.vi.apk", "split_config.arm64_v8a.apk", "split_config.hdpi.apk"), names(p))
    }

    @Test fun bundletoolSplitsAndStandalones() {
        val p = Splits.pick(e("standalones/standalone-arm64.apk", "splits/base-master.apk", "splits/base-armeabi_v7a.apk", "splits/base-xhdpi.apk", "splits/base-en.apk",
            "splits/base-master_2.apk", "toc.pb"), listOf("armeabi-v7a"), 320, emptyList())
        assertEquals(listOf("splits/base-master.apk", "splits/base-en.apk", "splits/base-master_2.apk", "splits/base-armeabi_v7a.apk", "splits/base-xhdpi.apk"), names(p))
    }

    @Test fun xapkWithObbAndFeature() {
        val p = Splits.pick(e("com.game.apk", "config.arm64_v8a.apk", "config.xxhdpi.apk", "feature_extra.apk", "Android/obb/com.game/main.5.com.game.obb", "manifest.json"),
            listOf("arm64-v8a"), 480, listOf("en"))
        assertEquals("com.game.apk", p.parts[0].name)
        assertTrue(names(p).containsAll(listOf("feature_extra.apk", "config.arm64_v8a.apk", "config.xxhdpi.apk")))
        assertEquals("com.game", Splits.obbPackage(p.obbs.single()))
    }

    @Test fun noMatchingCpuIsSaidInPlainTerms() {
        try { Splits.pick(e("base.apk", "split_config.x86.apk"), listOf("arm64-v8a"), 320, emptyList()); fail() }
        catch (x: IOException) { assertEquals("apk is built for x86, the tv runs arm64-v8a", x.message) }
    }

    @Test fun noBaseIsMissing() {
        try { Splits.pick(e("split_config.en.apk", "split_config.xhdpi.apk"), listOf("arm64-v8a"), 320, listOf("en")); fail() }
        catch (x: IOException) { assertEquals(Splits.MISSING, x.message) }
    }

    @Test fun smallThings() {
        assertEquals(240, Splits.density("Physical density: 320\nOverride density: 240"))
        assertEquals(listOf("vi", "en"), Splits.langs("vi_VN", "", "en-US"))
        assertEquals("a\\[1]\\*.apk", Splits.pattern("a[1]*.apk"))
        assertEquals("new", Splits.how("", "5")); assertEquals("update", Splits.how("versionCode=4 minSdk=21", "5"))
        assertEquals("same", Splits.how("versionCode=5", "5")); assertEquals("older", Splits.how("versionCode=9", "5"))
    }
}
