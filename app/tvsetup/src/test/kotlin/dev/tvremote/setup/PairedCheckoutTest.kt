package dev.tvremote.setup

import dev.tvremote.setup.link.Paired
import org.junit.Assert.assertEquals
import org.junit.Test

/** Check-out forgets the guests' browsers and keeps the owner's apps (Paired.withoutBrowsers). */
class PairedCheckoutTest {
    private fun e(id: String, kind: String) = Paired.Entry(id, "h$id", id, "", 0L, 0L, kind, false)

    @Test fun browsersGoAppsStay() {
        val l = listOf(e("a", "web"), e("b", "app"), e("c", "web"), e("d", "app"))
        assertEquals(listOf("b", "d"), Paired.withoutBrowsers(l).map { it.id })
    }

    @Test fun nothingToForget() {
        val l = listOf(e("b", "app"))
        assertEquals(l.map { it.id }, Paired.withoutBrowsers(l).map { it.id })
        assertEquals(emptyList<String>(), Paired.withoutBrowsers(emptyList()).map { it.id })
    }
}
