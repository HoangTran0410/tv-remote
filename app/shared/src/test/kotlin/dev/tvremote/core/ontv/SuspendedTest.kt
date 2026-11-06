package dev.tvremote.core.ontv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspendedTest {
    @Test fun `Android 14 lines, real output of the API 34 emulator, give the app and who suspended it`() {
        val out = """
            [com.android.settings]|    User 0: ceDataInode=311712 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
            [com.android.settings]|      suspendingPackage=com.android.shell dialogInfo=null
        """.trimIndent()
        assertEquals(mapOf("com.android.settings" to setOf("com.android.shell")), Suspended.parse(out))
    }

    @Test fun `TCL G10 Google TV on Android 12, a support case, YouTube Netflix and TV Setup locked by the Google TV launcher`() {
        val out = listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.tcl.tv", "dev.tvremote.setup").joinToString("\n") {
            "[$it]|    User 0: ceDataInode=2 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false\n" +
            "[$it]|      suspendingPackage=com.google.android.apps.tv.launcherx dialogInfo=null"
        }
        val apps = Suspended.parse(out)
        assertEquals(4, apps.size)
        assertEquals(apps.keys.toList(), Suspended.of(apps, "com.google.android.apps.tv.launcherx"))
        assertTrue(apps.values.all { it == setOf("com.google.android.apps.tv.launcherx") })
    }

    @Test fun `Android 9 and 10 print the suspender on the user line`() {
        val out = "[com.google.android.youtube.tv]|    User 0: ceDataInode=1 installed=true hidden=false suspended=true suspendingPackage=com.google.android.apps.tv.launcherx dialogMessage=null stopped=false notLaunched=false enabled=0"
        assertEquals(mapOf("com.google.android.youtube.tv" to setOf("com.google.android.apps.tv.launcherx")), Suspended.parse(out))
    }

    @Test fun `two suspenders, two users and nothing suspended`() {
        val out = "[a.b]|  suspended=true\n[a.b]|    suspendingPackage=x.y dialogInfo=null\n[a.b]|    suspendingPackage=z.w dialogInfo=null\n[a.b]|  suspended=true\n[a.b]|    suspendingPackage=x.y dialogInfo=null\n"
        assertEquals(setOf("x.y", "z.w"), Suspended.parse(out)["a.b"])
        assertEquals(listOf("a.b"), Suspended.of(Suspended.parse(out), "z.w"))
        assertTrue(Suspended.parse("").isEmpty())
        assertTrue(Suspended.parse("\n  \n").isEmpty())
    }
}
