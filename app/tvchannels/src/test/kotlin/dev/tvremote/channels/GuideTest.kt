package dev.tvremote.channels

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideTest {
    private val tv360 = Providers.Provider("tv360", "tv360", "TV360", "vn", false,
        JSONObject().put("guide", "https://x/{id}").put("channels", JSONObject().put("Cinemax", "239").put("VTV3", "4")))
    private fun ch(id: String, name: String) = Channel(id, name, "", "", "https://x/$id.m3u8")

    @Test fun aNameMatchesOnlyInTheProvidersCountry() {
        assertNull(Guide.source(listOf(tv360), listOf(ch("Cinemax.us", "Cinemax"))))        // the US feed: other films
        assertEquals("239", Guide.source(listOf(tv360), listOf(ch("Cinemax.vn", "Cinemax")))?.second)
        assertEquals("4", Guide.source(listOf(tv360), listOf(ch("n:VTV3 HD", "VTV3 HD")))?.second)   // a person's own list: no country
        assertEquals("239", Guide.source(listOf(tv360), listOf(ch("Cinemax.us", "Cinemax"), ch("n:Cinemax", "Cinemax")))?.second)
    }

    @Test fun aCacheFolderEmptiedUnderItIsMadeAgainNotACrash() {               // census 7128da05
        val root = java.nio.file.Files.createTempDirectory("guide").toFile()
        val dir = java.io.File(root, "guide")                                  // gone: the cache was emptied after read()'s mkdirs
        Guide.keep(java.io.File(dir, "tv360-232-2026-11-10.json"), "[]")
        assertEquals("[]", java.io.File(dir, "tv360-232-2026-11-10.json").readText())
        val blocked = java.io.File(root, "file").apply { writeText("") }       // can't be a folder: still no exception
        Guide.keep(java.io.File(blocked, "x.json"), "[]")
        root.deleteRecursively()
    }
}
