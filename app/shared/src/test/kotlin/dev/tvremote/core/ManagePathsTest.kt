package dev.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import dev.tvremote.core.manage.Apk
import dev.tvremote.core.manage.Procs
import dev.tvremote.core.manage.TvPaths

private typealias Proc = Procs.Proc

/** The checks that keep the file manager inside the TV's shared storage. */
class ManagePathsTest {
    @Test fun pathsStayInSharedStorage() {
        assertEquals("/sdcard/Download", TvPaths.safePath(""))
        assertEquals("/sdcard/Movies/a b", TvPaths.safePath("/sdcard//Movies/./a b/"))
        assertEquals("/storage/ABCD-1234", TvPaths.safePath("/storage/ABCD-1234"))
    }

    @Test(expected = IllegalArgumentException::class) fun noDotDot() { TvPaths.safePath("/sdcard/../data") }
    @Test(expected = IllegalArgumentException::class) fun noOtherRoots() { TvPaths.safePath("/data/local/tmp") }

    @Test fun safeNames() {
        assertEquals("passwd", TvPaths.safeName("../../etc/passwd"))
        assertEquals("a_b.txt", TvPaths.safeName("a;b.txt"))
        assertEquals("phim Việt (1).mp4", TvPaths.safeName("phim Việt (1).mp4"))
        assertEquals("file", TvPaths.safeName("..."))
        assertEquals(120, TvPaths.safeName("x".repeat(300)).length)
    }

    @Test fun memoryPerApp() {
        val sample = """Total PSS by process:
    119,516K: com.google.android.youtube.tv (pid 4709 / activities)
     73,623K: com.google.android.katniss:search (pid 4380)
     40,000K: com.google.android.katniss:interactor (pid 24727)
      3,000K: com.tiny.app (pid 1)

Total PSS by OOM adjustment:
    119,516K: com.google.android.youtube.tv (pid 4709 / activities)
"""
        assertEquals(listOf("com.google.android.youtube.tv" to 116, "com.google.android.katniss" to 110), Procs.memoryByApp(sample))
        assertEquals(emptyList<Pair<String, Int>>(), Procs.memoryByApp("nothing"))
        val oom = """Total RSS by OOM adjustment:
    200,000K: com.google.android.youtube.tv (pid 4709 / activities)

Total PSS by OOM adjustment:
    150,000K: Native
         60,000K: zygote64 (pid 341)
    119,516K: Foreground
        119,516K: com.google.android.youtube.tv (pid 4709 / activities)
     73,623K: Cached
         73,623K: com.google.android.katniss:search (pid 4380)
"""                                                                   // meminfo --oom: no "by process", PSS under group heads
        assertEquals(listOf("com.google.android.youtube.tv" to 116, "com.google.android.katniss" to 71), Procs.memoryByApp(oom))
    }

    @Test fun taskManager() {
        val sample = """Total PSS by OOM adjustment:
    307,656K: Native
         49,156K: app_process (pid 5284)
         16,486K: android.hardware.graphics.composer3-service.ranchu (pid 356)
    148,979K: System
        148,979K: system (pid 511)
     70,393K: Persistent
         47,985K: com.android.systemui (pid 732)
           4,995K: com.android.se (pid 871)
    148,933K: Foreground
         77,285K: com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0 (pid 5417)
         71,648K: com.netflix.ninja (pid 5382 / activities)
    115,189K: Perceptible
         61,749K: com.google.android.inputmethod.latin (pid 1076)
         22,557K: com.google.android.apps.tv.launcherx:coreservices (pid 1171)
     46,319K: A Services
         46,319K: com.spotify.tv.android (pid 1134)
     62,429K: Home
         62,429K: com.google.android.apps.tv.launcherx (pid 1175 / activities)
    352,376K: Cached
         31,952K: com.spotify.tv.android:background (pid 2489)
         21,124K: com.google.android.youtube.tv (pid 2016)
         10,977K: android.process.acore (pid 1287)
          8,406K: com.android.keychain (pid 1257)

Total PSS by category:
"""
        val keep = Procs.keepNow("com.google.android.apps.tv.launcherx/.home.HomeActivity\ncom.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME\n" +
            "package:com.netflix.ninja\npackage:com.spotify.tv.android\n" +
            "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity\ncom.netflix.ninja/.MainActivity")
        assertEquals(listOf(
            Proc("com.google.android.apps.tv.launcherx", 82, "home", "home"),   // its coreservices process counts for it
            Proc("com.spotify.tv.android", 76, "bg", null),                      // the busiest of its processes: playing
            Proc("com.netflix.ninja", 69, "front", null),
            Proc("com.google.android.inputmethod.latin", 60, "bg", "keyboard"),
            Proc("com.android.systemui", 46, "system", "system"),
            Proc("com.google.android.youtube.tv", 20, "cached", null),
            Proc("android.process.acore", 10, "cached", "system"),
            Proc("com.android.keychain", 8, "cached", "system"),          // a system app with no tile: not for people to stop
            Proc("com.android.se", 4, "system", "system"),
        ), Procs.procsOf(sample, keep))
        assertEquals("us", Procs.keepWhy("dev.tvremote.setup", keep))
        assertEquals("us", Procs.keepWhy("dev.tvremote.setup.keys", keep))
        assertEquals("us", Procs.keepWhy("com.tailscale.ipn", keep))
        for (p in listOf("dev.tvremote.channels", "dev.tvremote.games", "dev.tvremote.phone")) assertNotEquals("us", Procs.keepWhy(p, keep), p)   // ordinary apps
        assertEquals(emptyList<Proc>(), Procs.procsOf("nothing", keep))
    }

    @Test fun mimeTypes() {
        assertEquals("video/mp4", TvPaths.mime("/sdcard/a.MP4"))
        assertEquals("application/vnd.android.package-archive", TvPaths.mime("x.apk"))
        assertEquals("*/*", TvPaths.mime("x.unknown"))
    }
}

/** The package name read from an APK's binary manifest, to check after an install that the TV kept the app. */
class ApkPackageTest {
    @Test fun readsPackage() {
        val m = javaClass.classLoader!!.getResourceAsStream("setup-manifest.bin").readBytes()
        assertEquals("dev.tvremote.setup", Apk.pkg(m))
    }

    @Test fun infoAndFit() {
        val i = Apk.info(javaClass.classLoader!!.getResourceAsStream("setup-manifest.bin").readBytes())
        assertEquals("dev.tvremote.setup", i["package"])
        assertEquals("21", i["minSdkVersion"])
        assertEquals(true, i["versionCode"]?.toIntOrNull() != null)
        assertEquals(listOf("arm64-v8a", "x86"), Apk.abis(listOf("lib/arm64-v8a/a.so", "lib/x86/a.so", "lib/x86/b.so", "lib/readme.txt", "assets/lib/x/c.so")))
        assertEquals(null, Apk.fits(i, emptyList(), 28, listOf("armeabi-v7a")))
        assertEquals("apk needs sdk 29, the tv has sdk 28", Apk.fits(mapOf("minSdkVersion" to "29"), emptyList(), 28, emptyList()))
        assertEquals("apk is built for arm64-v8a, the tv runs armeabi-v7a,armeabi", Apk.fits(emptyMap(), listOf("arm64-v8a"), 28, listOf("armeabi-v7a", "armeabi")))
        assertEquals(null, Apk.fits(emptyMap(), listOf("arm64-v8a", "armeabi-v7a"), 28, listOf("armeabi-v7a")))
    }

    @Test fun garbageIsNull() {
        assertEquals(null, Apk.pkg(ByteArray(0)))
        assertEquals(null, Apk.pkg("not a manifest at all".toByteArray()))
    }
}
