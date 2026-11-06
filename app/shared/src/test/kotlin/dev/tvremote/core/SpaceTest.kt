package dev.tvremote.core

import dev.tvremote.core.ontv.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The storage dialog's read of `dumpsys diskstats`: the biggest apps a person can trim. */
class SpaceTest {
    private val out = "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-37 6082144 2253676 3686256 38% /data/user/0\n#~#\n" +
        "App Size: 1\nPackage Names: [\"com.big.game\",\"com.sys.updated\",\"com.sys.plain\",\"com.keep.me\",\"com.small\"]\n" +
        "App Sizes: [500,300,900,800,10]\nApp Data Sizes: [100,50,0,0,5]\nCache Sizes: [10,5,0,0,1]\n#~#\n" +
        "package:com.big.game\npackage:com.keep.me\npackage:com.small\n#~#\n" +
        "package:/data/app/x/base.apk=com.sys.updated\n"

    @Test fun topAppsSkipSystemOnesWithoutUpdatesAndWhatTheTvNeeds() {
        val t = Space.top(out, { it == "com.keep.me" })
        assertEquals(listOf("com.big.game", "com.sys.updated", "com.small"), t.map { it.pkg })
        assertEquals(610L, t[0].bytes); assertEquals(100L, t[0].data); assertEquals(10L, t[0].cache)
        assertEquals(listOf(true, false, true), t.map { it.user })
    }

    @Test fun limitAndFree() {
        assertEquals(1, Space.top(out, { false }, 1).size)
        assertEquals(3686256L * 1024, Space.free(out))
        assertNull(Space.free("df: nope"))
        assertEquals(0, Space.top("", { false }).size)               // a build without those lines: an empty list, not a crash
    }
}
