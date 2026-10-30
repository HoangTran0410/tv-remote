package dev.tvremote.home.apps

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Play Next row's "Latest first": by the last engagement time, apps that give none after those that do, otherwise as given. */
class WatchSortTest {
    private fun item(k: String, at: Long) = Watch.Item(k, "p", k, "", "", "p", at = at)
    private val given = listOf(item("a", 10), item("b", 0), item("c", 30), item("d", 20), item("e", 0))

    @Test fun defaultKeepsTheOrderGiven() = assertEquals("abcde", Watch.sorted(given, "default").joinToString("") { it.key })
    @Test fun unknownValueKeepsTheOrderGiven() = assertEquals("abcde", Watch.sorted(given, "").joinToString("") { it.key })
    @Test fun latestPutsTheNewestFirstAndKeepsTiesInOrder() = assertEquals("cdabe", Watch.sorted(given, "latest").joinToString("") { it.key })
}
