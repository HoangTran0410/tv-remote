package dev.tvremote.setup.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeTest {
    private fun may(scope: String, path: String, post: Boolean = false, q: Map<String, String> = emptyMap(), body: JSONObject = JSONObject()) =
        Scope.allows(scope, path, post, q) { body }

    @Test fun ownerDoesEverything() {
        for (p in listOf("/api/shell", "/api/install", "/api/adb/allow", "/api/share", "/api/paired/scope", "/api/whatever/new"))
            assertTrue(p, may(Scope.FULL, p, true))
    }

    @Test fun remotePressesButNeverManages() {
        for (p in listOf("/api/key", "/api/type", "/api/touch", "/api/app/launch", "/api/draw", "/api/playing")) assertTrue(p, may(Scope.REMOTE, p, true))
        assertTrue(may(Scope.REMOTE, "/api/apps"))
        assertTrue(may(Scope.VIEW, "/api/app/meta")); assertFalse(may(Scope.VIEW, "/api/app/meta", true))   // the names beside the icons
        assertTrue(may(Scope.REMOTE, "/api/inputs")); assertTrue(may(Scope.REMOTE, "/api/input", true)); assertFalse(may(Scope.VIEW, "/api/input", true))
        assertFalse(may(Scope.REMOTE, "/api/apps", q = mapOf("system" to "1")))   // every package: Manage
        for (p in listOf("/api/shell", "/api/install", "/api/upload", "/api/adb/allow", "/api/share", "/api/app/uninstall", "/api/paired/scope", "/api/setup", "/api/reboot", "/api/procs/kill"))
            assertFalse(p, may(Scope.REMOTE, p, true))
        for (p in listOf("/api/files", "/api/file", "/api/health", "/api/procs", "/api/log", "/api/wirelog", "/api/app/backup")) assertFalse(p, may(Scope.REMOTE, p))
    }

    @Test fun aNotificationIsForRemoteAndUpButNotForViewOnly() {
        assertTrue(may(Scope.FULL, "/api/notify", true))
        assertTrue(may(Scope.REMOTE, "/api/notify", true))
        assertFalse(may(Scope.VIEW, "/api/notify", true))
        assertFalse(may(Scope.REMOTE, "/api/notify"))               // a GET is nothing
    }

    @Test fun remoteSleepTimerOnly() {
        assertTrue(may(Scope.REMOTE, "/api/rules", true, body = JSONObject().put("do", "sleep").put("minutes", 30)))
        assertTrue(may(Scope.REMOTE, "/api/rules", true, body = JSONObject().put("do", "sleep_add").put("minutes", 15)))
        for (d in listOf("bedtime", "limit", "extra", "volume", "ram", "snooze", "holdkeys", "mic")) assertFalse(d, may(Scope.REMOTE, "/api/rules", true, body = JSONObject().put("do", d)))
    }

    @Test fun remoteRunsSavedScenesOnly() {
        assertTrue(may(Scope.REMOTE, "/api/scene/run", true, body = JSONObject().put("id", "s1")))
        assertFalse(may(Scope.REMOTE, "/api/scene/run", true, body = JSONObject().put("steps", org.json.JSONArray())))
        assertFalse(may(Scope.REMOTE, "/api/scenes", true))
        assertFalse(may(Scope.REMOTE, "/api/scenes", q = mapOf("download" to "1")))
    }

    @Test fun viewOnlyWatches() {
        for (p in listOf("/api/state", "/api/video", "/api/audio", "/api/screen", "/api/playing")) assertTrue(p, may(Scope.VIEW, p))
        for (p in listOf("/api/key", "/api/type", "/api/touch", "/api/playing", "/api/draw", "/api/app", "/api/clip", "/api/rules")) assertFalse(p, may(Scope.VIEW, p, true))
        assertFalse(may(Scope.VIEW, "/api/clip"))                      // the TV's clipboard may hold what someone copied
        assertTrue(may(Scope.VIEW, "/api/paired/forget", true))        // itself only: RemoteService.route
    }

    @Test fun unknownScopeIsTheOwnersFromBefore() {
        assertEquals(Scope.FULL, Scope.of(null))
        assertEquals(Scope.FULL, Scope.of("root"))
        assertEquals(Scope.VIEW, Scope.of("view"))
    }
}
