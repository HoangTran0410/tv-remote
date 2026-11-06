package dev.tvremote.core.ontv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspendedTest {
    @Test fun android14LinesGiveTheAppAndWhoSuspendedIt() {
        val out = """
            [com.android.settings]|    User 0: ceDataInode=311712 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
            [com.android.settings]|      suspendingPackage=com.android.shell dialogInfo=null
        """.trimIndent()
        assertEquals(mapOf("com.android.settings" to setOf("com.android.shell")), Suspended.parse(out))
    }

    @Test fun tclG10LockedByTheGoogleTvLauncher() {
        val out = listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.tcl.tv", "dev.tvremote.setup").joinToString("\n") {
            "[$it]|    User 0: ceDataInode=2 installed=true hidden=false suspended=true distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false\n" +
            "[$it]|      suspendingPackage=com.google.android.apps.tv.launcherx dialogInfo=null"
        }
        val apps = Suspended.parse(out)
        assertEquals(4, apps.size)
        assertEquals(apps.keys.toList(), Suspended.of(apps, "com.google.android.apps.tv.launcherx"))
        assertTrue(apps.values.all { it == setOf("com.google.android.apps.tv.launcherx") })
    }

    @Test fun android9And10PrintTheSuspenderOnTheUserLine() {
        val out = "[com.google.android.youtube.tv]|    User 0: ceDataInode=1 installed=true hidden=false suspended=true suspendingPackage=com.google.android.apps.tv.launcherx dialogMessage=null stopped=false notLaunched=false enabled=0"
        assertEquals(mapOf("com.google.android.youtube.tv" to setOf("com.google.android.apps.tv.launcherx")), Suspended.parse(out))
    }

    @Test fun aProfileSuspensionIsLiftedUnderThatUser() {
        val lx = "com.google.android.apps.tv.launcherx"
        val out = """
            [com.netflix.ninja]|    User 10: ceDataInode=2 installed=true hidden=false suspended=true distractionFlags=0 stopped=false
            [com.netflix.ninja]|      suspendingPackage=$lx dialogInfo=null
            [com.google.android.youtube.tv]|    User 0: ceDataInode=2 installed=true hidden=false suspended=true distractionFlags=0 stopped=false
            [com.google.android.youtube.tv]|      suspendingPackage=com.android.shell dialogInfo=null
            [com.google.android.youtube.tv]|    User 11: ceDataInode=2 installed=true hidden=false suspended=true distractionFlags=0 stopped=false
            [com.google.android.youtube.tv]|      suspendingPackage=$lx dialogInfo=null
        """.trimIndent()
        assertEquals(setOf(10, 11), Suspended.users(out, lx))
        assertEquals(setOf(0), Suspended.users(out, "com.android.shell"))
        assertEquals(listOf("pm clear --user 10 $lx 2>&1", "pm clear --user 11 $lx 2>&1"), Suspended.lift(lx, listOf("a.b"), Suspended.users(out, lx)))
        assertEquals(listOf("pm unsuspend com.google.android.youtube.tv 2>&1"), Suspended.lift("com.android.shell", listOf("com.google.android.youtube.tv"), setOf(0)))
        val old = "[a.b]|    User 10: ceDataInode=1 installed=true suspended=true suspendingPackage=$lx dialogMessage=null"   // Android 9-10: one line
        assertEquals(setOf(10), Suspended.users(old, lx))
        assertEquals(listOf("pm clear $lx 2>&1"), Suspended.lift(lx, listOf("a.b"), Suspended.users("[a.b]|  suspendingPackage=$lx", lx)))   // no user printed: 0
    }

    @Test fun twoSuspendersTwoUsersAndNothingSuspended() {
        val out = "[a.b]|  suspended=true\n[a.b]|    suspendingPackage=x.y dialogInfo=null\n[a.b]|    suspendingPackage=z.w dialogInfo=null\n[a.b]|  suspended=true\n[a.b]|    suspendingPackage=x.y dialogInfo=null\n"
        assertEquals(setOf("x.y", "z.w"), Suspended.parse(out)["a.b"])
        assertEquals(listOf("a.b"), Suspended.of(Suspended.parse(out), "z.w"))
        assertTrue(Suspended.parse("").isEmpty())
        assertTrue(Suspended.parse("\n  \n").isEmpty())
    }
}
