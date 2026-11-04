package dev.tvremote.core

import dev.tvremote.core.web.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** TV Setup's API answers only its own page: a web game on :8780 of the same TV is the same "site" to the browser. */
class CrossOriginTest {
    private fun r(vararg h: Pair<String, String>) = HttpServer.Request().apply { headers["host"] = "192.168.1.5:8765"; headers.putAll(h) }
    @Test fun itsOwnPage() = assertEquals(false, HttpServer.crossOrigin(r("origin" to "http://192.168.1.5:8765", "sec-fetch-site" to "same-origin")))
    @Test fun noBrowser() = assertEquals(false, HttpServer.crossOrigin(r()))                  // Home Assistant, Shortcuts, curl
    @Test fun anotherPort() = assertEquals(true, HttpServer.crossOrigin(r("origin" to "http://192.168.1.5:8780")))
    @Test fun sameSiteFetch() = assertEquals(true, HttpServer.crossOrigin(r("sec-fetch-site" to "same-site")))
    @Test fun typedInTheBar() = assertEquals(false, HttpServer.crossOrigin(r("sec-fetch-site" to "none")))
}
