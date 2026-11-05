package dev.tvremote.home.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SourcesTest {
    private val site = File("../../docs/home/feeds.json")

    @Test fun theSitesListParses() {
        val a = Sources.parse(site.readText())!!
        assertTrue(a.rates != null && a.gold.size >= 1 && a.crypto != null)
        assertEquals(listOf("saigonpetro", "webgia"), a.fuel.map { it.kind })           // the official seller first, the copy of Petrolimex's table second
        assertTrue(a.news.any { it.lang == "vi" } && a.news.any { it.lang == "en" })
    }

    @Test fun englishFeedsWhenNoFeedInTheLanguage() {
        val a = Sources.parse(site.readText())!!
        assertTrue(Sources.feedsFor("vi", a).all { it.lang == "vi" })
        assertTrue(Sources.feedsFor("fr", a).all { it.lang == "en" })
    }

    @Test fun plainHttpAndUnknownKindsAreDropped() {
        val a = Sources.parse("""{"v":1,"rev":2,"rates":{"kind":"vcb","url":"http://x"},"gold":[{"kind":"zzz","url":"https://x"},{"kind":"pnj","url":"https://x"}],"news":[{"id":"a","url":"http://x"}]}""")!!
        assertNull(a.rates); assertEquals(1, a.gold.size); assertTrue(a.news.isEmpty())
        assertNull(Sources.parse("""{"v":2}"""))
    }
}
