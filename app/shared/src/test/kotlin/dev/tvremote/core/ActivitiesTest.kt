package dev.tvremote.core

import dev.tvremote.core.adb.Activities
import dev.tvremote.core.ontv.Setup
import org.junit.Assert.*
import org.junit.Test

/** Android 8 and older: no `cmd package query-activities`; the answer comes from the resolver table, or it is "unknown" (never "none"). */
class ActivitiesTest {
    private val filters = """
Activity Resolver Table:
  Non-Data Actions:
      android.intent.action.MAIN:
        3c4d dev.tv/.Leanback filter aa
          Action: "android.intent.action.MAIN"
          Category: "android.intent.category.LEANBACK_LAUNCHER"
        1a2b dev.set/.Main filter 9f
          Action: "android.settings.SETTINGS"
          Category: "android.intent.category.DEFAULT"
        5e6f dev.inp/.Input filter bb
          Action: "android.intent.action.VIEW"
          Type: "vnd.android.cursor.item/*"
Receiver Resolver Table:
        7788 dev.rcv/.R filter cc
          Action: "android.settings.SETTINGS"
"""
    private fun tv(native: String, dumpsys: String = filters): (String) -> String = { c ->
        if (c.startsWith("cmd package")) native else if (c.startsWith("dumpsys package")) dumpsys else ""
    }

    @Test fun oldAndroidMessagesAreNotScreens() {
        for (m in listOf("Unknown command: query-activities", "cmd: not found", "/system/bin/sh: cmd: not found")) {
            val a = Activities(tv(m))
            assertEquals(setOf("dev.set"), a.packages("android.settings.SETTINGS"))     // receivers are not screens
            assertEquals(setOf("dev.tv"), a.packages("android.intent.action.MAIN", "LEANBACK_LAUNCHER"))
            assertEquals(setOf("dev.inp"), a.packages("android.intent.action.VIEW", mime = "vnd.android.cursor.item/channel"))
        }
    }

    @Test fun neitherWayWorksGivesNullNotEmpty() {
        assertNull(Activities(tv("cmd: not found", "Can't find service: package")).query("android.settings.SETTINGS"))
    }

    @Test fun nativeAnswerWins() {
        val a = Activities(tv("priority=0 preferredOrder=0\n  dev.x/.Main\n", "boom"))
        assertEquals(listOf("dev.x/.Main"), a.query("android.settings.SETTINGS"))
    }

    @Test fun nativeEmptyIsAnAnswerWhenTheCommandWorks() {
        val a = Activities { c -> if (c.contains("-a android.intent.action.VIEW")) "" else "  dev.home/.H\n" }
        assertEquals(emptyList<String>(), a.query("android.intent.action.VIEW"))
    }

    @Test fun inUseOnOldAndroidUsesTheTable() {
        val sh = { c: String -> if (c.contains("echo q0")) "0\n0\n0\nq0" else if (c.startsWith("dumpsys package 2>")) filters else "" }
        assertTrue(Setup.inUse(sh, "dev.set"))
        assertFalse(Setup.inUse(sh, "dev.tv"))
    }

    @Test fun inUseFailsClosedWhenTheTvCannotSay() {
        assertTrue(Setup.inUse({ c -> if (c.contains("echo q0")) "0\n0\n0\nq0" else "" }, "dev.set"))
    }
}
