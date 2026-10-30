package dev.tvremote.home

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone or computer sends TV Home is cleaned the same way on both sides: nothing out of range is ever kept. */
class HomeConfigTest {
    init {   // the ready-made themes are the site's list (TV Home sets them at start): the shipped file, as the apply tests below need them
        HomeConfig.ready = HomeConfig.parseReady(generateSequence(java.io.File("").absoluteFile) { it.parentFile }.map { java.io.File(it, "docs/home/themes.json") }.first { it.isFile }.readText(), "99.0.0")
    }
    private fun dock(c: JSONObject) = c.getJSONArray("dock").let { a -> List(a.length()) { a.getString(it) } }
    private fun hidden(c: JSONObject) = c.getJSONArray("hidden").let { a -> List(a.length()) { a.getString(it) } }

    @Test fun colsDefaultToFiveAndStayInRange() {
        assertEquals(5, HomeConfig.clean(JSONObject()).getInt("cols"))
        assertEquals(4, HomeConfig.clean(JSONObject().put("cols", 2)).getInt("cols"))
        assertEquals(8, HomeConfig.clean(JSONObject().put("cols", 99)).getInt("cols"))
        assertEquals(6, HomeConfig.clean(JSONObject().put("cols", "6")).getInt("cols"))
        assertEquals(7, HomeConfig.merge(JSONObject(), JSONObject().put("cols", 7)).getInt("cols"))
        assertEquals(7, HomeConfig.clean(HomeConfig.clean(JSONObject().put("cols", 7))).getInt("cols"))
    }

    @Test fun emptyGivesTheDefaults() {
        val c = HomeConfig.clean(JSONObject())
        assertEquals(emptyList<String>(), dock(c))
        assertEquals("night", c.getString("wall"))
        assertEquals(true, c.getBoolean("clock24"))
        assertEquals(true, c.getBoolean("greet"))
        assertEquals(true, c.getBoolean("anim"))
    }

    @Test fun theGreetingSaysTheTimeTheNameOrTheOwnWords() {
        fun g(o: JSONObject) = HomeConfig.greeting(HomeConfig.clean(o), "Good evening")
        assertEquals("Good evening", g(JSONObject()))
        assertEquals("Good evening, Hoang", g(JSONObject().put("gstyle", "name").put("gname", "  Hoang \n")))
        assertEquals("Good evening", g(JSONObject().put("gstyle", "name")))             // no name yet: the time alone, not "Good evening, "
        assertEquals("Hi family", g(JSONObject().put("gstyle", "own").put("gtext", "Hi   family")))
        assertEquals("Good evening", g(JSONObject().put("gstyle", "own")))
        assertEquals("Good evening", g(JSONObject().put("gstyle", "shout").put("gname", "x")))
        assertEquals(HomeConfig.GTEXT_MAX, HomeConfig.clean(JSONObject().put("gtext", "a".repeat(500))).getString("gtext").length)
    }

    @Test fun theOwnWallIsOneToFourColoursAndADirection() {
        val d = HomeConfig.clean(JSONObject())
        assertEquals(HomeConfig.WCOL_DEFAULT, d.getString("wcol")); assertEquals("tl_br", d.getString("wdir"))
        val o = HomeConfig.clean(JSONObject().put("wall", "own").put("wcol", "#FF0000, 00ff00,zzz,0000ff,111111,222222").put("wdir", "radial"))
        assertEquals("own", o.getString("wall"))
        assertEquals("ff0000,00ff00,0000ff,111111", o.getString("wcol"))           // bad parts dropped, at most four
        assertEquals("radial", o.getString("wdir"))
        assertEquals(0xFFFF0000.toInt(), HomeConfig.ownColors(o)[0])
        assertEquals("tl_br", HomeConfig.clean(JSONObject().put("wdir", "up")).getString("wdir"))
        assertEquals(HomeConfig.WCOL_DEFAULT, HomeConfig.clean(JSONObject().put("wcol", "red")).getString("wcol"))
    }

    @Test fun theHeadersTwoLinesAreTemplatesOrOwnWordsWithTheirOwnType() {
        val t = mapOf("greet" to "Good evening", "date" to "Monday, 26 October", "weekday" to "Monday", "day" to "26", "month" to "10", "lunar" to "17/9", "canchi" to "Bính Ngọ", "lunarLine" to "Lunar 17/9 · Bính Ngọ")
        fun d(o: JSONObject) = HomeConfig.headLine(HomeConfig.clean(o), "d", t)
        fun g(o: JSONObject) = HomeConfig.headLine(HomeConfig.clean(o), "g", t)
        assertEquals("Monday, 26 October", d(JSONObject()))                               // as before the two lines could change
        assertEquals("Good evening", g(JSONObject()))
        assertEquals("Lunar 17/9 · Bính Ngọ", d(JSONObject().put("dstyle", "lunar")))
        assertEquals("Monday, 26 October", g(JSONObject().put("gstyle", "date")))
        assertEquals("26/10 · 17/9 · {nope}", d(JSONObject().put("dstyle", "own").put("dtext", "{day}/{month} · {lunar} · {nope}")))   // an unknown word stays as typed
        assertEquals("Monday, 26 October", d(JSONObject().put("dstyle", "own")))          // nothing typed yet: the date, not an empty line
        assertEquals("date", HomeConfig.clean(JSONObject().put("dstyle", "name")).getString("dstyle"))   // a name is the greeting's
        val c = HomeConfig.clean(JSONObject().put("gsize", "huge").put("dcolor", "red").put("dcaps", true))
        assertEquals("s", c.getString("gsize")); assertEquals("red", c.getString("dcolor")); assertTrue(c.getBoolean("dcaps")); assertFalse(c.getBoolean("gbold"))
    }

    @Test fun theDockKeepsRealPackagesOnceAndAtMostSix() {
        val c = HomeConfig.clean(JSONObject().put("dock", JSONArray(listOf("a.b", "a.b", "; rm -rf /", "x", "c.d", "e.f", "g.h", "i.j", "k.l", "m.n"))))
        assertEquals(listOf("a.b", "c.d", "e.f", "g.h", "i.j", "k.l"), dock(c))
    }

    @Test fun sizesAreKnownOnesAndDefaultToTheCardsKind() {
        val none = HomeConfig.clean(JSONObject())
        assertEquals(0, none.getJSONObject("wsize").length())                    // nothing chosen: the defaults apply
        assertFalse(none.has("wlayout"))
        assertEquals(setOf("system", "usage", "calendar", "weather", "air", "market", "news", "prayer") + HomeQr.KEYS, HomeConfig.bigs(none))   // QR cards are big by default
        val c = HomeConfig.clean(JSONObject().put("wsize", JSONObject().put("system", "small").put("lunar", "big").put("timer", "huge").put("bogus", "big")))
        assertEquals("small", c.getJSONObject("wsize").getString("system"))
        assertEquals("big", c.getJSONObject("wsize").getString("lunar"))
        assertFalse(c.getJSONObject("wsize").has("timer"))                       // an unknown size: dropped
        assertFalse(c.getJSONObject("wsize").has("bogus"))
        assertEquals(setOf("usage", "calendar", "weather", "air", "market", "news", "lunar", "prayer") + HomeQr.KEYS, HomeConfig.bigs(c))
        val m = HomeConfig.merge(JSONObject().put("widgets", JSONArray(listOf("weather", "calendar"))), JSONObject().put("wsize", JSONObject().put("weather", "small")))
        assertEquals("small", m.getJSONObject("wsize").getString("weather"))
        assertEquals("weather", m.getJSONArray("widgets").getString(0))          // moving never sorts them back
    }

    @Test fun optionsAreKnownOnesOfKnownCards() {
        val none = HomeConfig.clean(JSONObject())
        assertEquals(0, none.getJSONObject("wopt").length())                                       // nothing chosen: the defaults apply
        assertFalse(none.has("wstyle"))
        val c = HomeConfig.clean(JSONObject().put("wopt", JSONObject()
            .put("clock", JSONObject().put("face", "minimal").put("theme", "light").put("zzz", "x")).put("lunar", JSONObject().put("face", "roman")).put("bogus", JSONObject().put("look", "tonal"))
            .put("weather", JSONObject().put("look", "tonal")).put("system", JSONObject().put("look", "wood"))))
        assertEquals("minimal", c.getJSONObject("wopt").getJSONObject("clock").getString("face"))     // independent axes: minimal AND light
        assertEquals("light", c.getJSONObject("wopt").getJSONObject("clock").getString("theme"))
        assertEquals(2, c.getJSONObject("wopt").getJSONObject("clock").length())                  // an unknown option: dropped
        assertFalse(c.getJSONObject("wopt").has("lunar"))                                          // an option the card does not have
        assertFalse(c.getJSONObject("wopt").has("bogus")); assertFalse(c.getJSONObject("wopt").has("system"))
        assertEquals("tonal", HomeConfig.optValue(c, "weather", "look"))
        assertEquals("glass", HomeConfig.optValue(c, "air", "look"))                              // missing: the default
        assertEquals("roman", HomeConfig.optValue(JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("face", "roman"))), "clock", "face"))
        assertEquals("glass", HomeConfig.optValue(c, "clock", "look"))                             // the clock's look: glass / solid / none
        val bare = HomeConfig.clean(JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("look", "tonal"))
            .put("lunar", JSONObject().put("look", "none")).put("system", JSONObject().put("look", "solid"))))
        assertFalse(bare.getJSONObject("wopt").has("clock"))                                     // no tonal clock
        assertEquals("none", HomeConfig.optValue(bare, "lunar", "look")); assertEquals("solid", HomeConfig.optValue(bare, "system", "look"))
        assertEquals("glass", HomeConfig.optValue(c, "calendar", "look")); assertEquals("glass", HomeConfig.optValue(c, "qr1", "look"))   // every other card has it
        assertFalse(HomeConfig.clean(JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("face", "classic")))).getJSONObject("wopt").has("clock"))   // a default is not kept
        assertEquals("big", HomeConfig.optValue(c, "weather", "size")); assertEquals("small", HomeConfig.optValue(c, "clock", "size"))   // the size is read from "wsize"
    }

    @Test fun oldStylesBecomeOptions() {
        val c = HomeConfig.clean(JSONObject().put("wstyle", JSONObject().put("clock", "light").put("weather", "tonal").put("lunar", "light").put("zzz", "x")))
        assertFalse(c.has("wstyle"))
        assertEquals("classic", HomeConfig.optValue(c, "clock", "face")); assertEquals("light", HomeConfig.optValue(c, "clock", "theme"))   // "light" was a light classic
        assertEquals("tonal", HomeConfig.optValue(c, "weather", "look"))
        assertEquals(2, c.getJSONObject("wopt").length())
        val mn = HomeConfig.clean(JSONObject().put("wstyle", JSONObject().put("clock", "minimal")))
        assertEquals("minimal", HomeConfig.optValue(mn, "clock", "face")); assertEquals("dark", HomeConfig.optValue(mn, "clock", "theme"))   // "minimal" was forced dark
        // wopt wins over a leftover wstyle for a card that has an entry
        val both = HomeConfig.clean(JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("face", "roman"))).put("wstyle", JSONObject().put("clock", "light")))
        assertEquals("roman", HomeConfig.optValue(both, "clock", "face")); assertEquals("dark", HomeConfig.optValue(both, "clock", "theme"))
        // an older client's write: its "wstyle" turns into options, and replaces the card's whole face
        val base = JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("face", "roman").put("theme", "light")))
        val m = HomeConfig.merge(base, JSONObject().put("wstyle", JSONObject().put("clock", "minimal")))
        assertEquals("minimal", HomeConfig.optValue(m, "clock", "face")); assertEquals("dark", HomeConfig.optValue(m, "clock", "theme"))
    }

    @Test fun optionDeltasMergePerCardAndOption() {
        val base = JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("face", "minimal")))
        val m = HomeConfig.merge(base, JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("theme", "light")).put("weather", JSONObject().put("look", "tonal"))))
        assertEquals("minimal", HomeConfig.optValue(m, "clock", "face")); assertEquals("light", HomeConfig.optValue(m, "clock", "theme")); assertEquals("tonal", HomeConfig.optValue(m, "weather", "look"))
        val back = HomeConfig.merge(m, JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("theme", "dark"))))      // set back to the default
        assertEquals("dark", HomeConfig.optValue(back, "clock", "theme")); assertEquals("minimal", HomeConfig.optValue(back, "clock", "face"))
        val f = HomeConfig.forward(JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("theme", "dark").put("face", "neon")).put("future", JSONObject().put("tone", "bold")).put("Bad Key", JSONObject().put("a", "b"))))
        assertEquals("dark", f.getJSONObject("wopt").getJSONObject("clock").getString("theme"))     // a default reaches the TV: it is a change
        assertFalse(f.getJSONObject("wopt").getJSONObject("clock").has("face"))
        assertEquals("bold", f.getJSONObject("wopt").getJSONObject("future").getString("tone"))     // a newer TV Home's card keeps its option
        assertFalse(f.getJSONObject("wopt").has("Bad Key"))
    }

    @Test fun schemaListsWhatEachCardOffers() {
        val s = HomeConfig.schema()
        for (k in HomeConfig.WIDGETS) assertEquals("size", s.getJSONArray(k).getJSONObject(0).getString("id"))   // every card has a size, in "wsize"
        assertEquals("wsize", s.getJSONArray("clock").getJSONObject(0).getString("store"))
        val clock = s.getJSONArray("clock")
        assertEquals(listOf("size", "face", "theme", "look"), List(clock.length()) { clock.getJSONObject(it).getString("id") })
        val face = clock.getJSONObject(1)
        assertEquals("classic", face.getString("default")); assertEquals("home.o_face", face.getString("label"))
        assertEquals(listOf("classic", "minimal", "roman", "modern", "digital"), List(5) { face.getJSONArray("values").getJSONObject(it).getString("id") })
        assertEquals("home.ov_minimal", face.getJSONArray("values").getJSONObject(1).getString("w"))
        assertEquals(3, s.getJSONArray("calendar").length())                                  // size + look + layout (month / date)
        for (k in HomeConfig.WIDGETS) assertEquals(k != "clock", HomeConfig.options(k).first { it.id == "look" }.values.contains("tonal"))   // every card has a look, all but the clock a tonal one
        assertEquals("look", s.getJSONArray("weather").getJSONObject(1).getString("id"))
        for (k in HomeConfig.WIDGETS) for (o in HomeConfig.options(k)) assertTrue(o.default in (if (o.type == "toggle") listOf("off", "on") else o.values))
    }

    @Test fun themesKeepTheirLooksAsOptions() {
        val old = HomeConfig.pickTheme(JSONObject().put("widgets", JSONArray(listOf("clock"))).put("wstyle", JSONObject().put("clock", "light")))   // a theme saved before the options
        assertEquals("light", old.getJSONObject("wopt").getJSONObject("clock").getString("theme"))
        val n = HomeConfig.merge(JSONObject().put("wopt", JSONObject().put("clock", JSONObject().put("face", "roman"))),
            JSONObject().put("theme", JSONObject().put("save", "x")))
        val id = n.getJSONArray("themes").getJSONObject(0).getString("id")
        val m = HomeConfig.merge(JSONObject().put("themes", JSONArray().put(JSONObject().put("id", "t9").put("name", "Old").put("conf", JSONObject().put("wstyle", JSONObject().put("weather", "tonal"))))),
            JSONObject().put("theme", JSONObject().put("apply", "t9")))
        assertEquals("tonal", HomeConfig.optValue(m, "weather", "look")); assertEquals("classic", HomeConfig.optValue(m, "clock", "face"))   // a theme replaces the looks
        assertTrue(id.isNotEmpty())
    }

    @Test fun anOldLayoutBecomesTheSizesThatGaveTheSamePicture() {
        val names = listOf("system", "usage", "calendar", "lunar")
        fun sizes(layout: String): List<String> {
            val c = HomeConfig.clean(JSONObject().put("wlayout", layout).put("widgets", JSONArray(names)))
            return names.map { c.getJSONObject("wsize").optString(it, "?") }
        }
        assertEquals(listOf("?", "?", "?", "?"), sizes("row"))
        assertEquals(listOf("big", "small", "small", "small"), sizes("big"))
        assertEquals(listOf("big", "big", "small", "small"), sizes("big2"))
        assertEquals(listOf("small", "small", "big", "small"), sizes("flip"))
        assertEquals(listOf("small", "small", "small", "small"), sizes("quad"))
        // sizes already chosen win over a layout left in an old copy
        val both = HomeConfig.clean(JSONObject().put("wlayout", "quad").put("wsize", JSONObject()).put("widgets", JSONArray(listOf("system"))))
        assertEquals(0, both.getJSONObject("wsize").length())
    }

    /** The packing gives what tests/home_pack_cases.json says (the page's homePack reads the same table). */
    @Test fun packingFollowsTheSharedCases() {
        val cases = JSONObject(java.io.File("tests/home_pack_cases.json").readText()).getJSONArray("cases")
        fun list(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val p = HomeConfig.pack(list(c.getJSONArray("keys")), list(c.getJSONArray("big")).toSet(), c.optBoolean("note"))
            val cols = c.getJSONArray("cols")
            assertEquals(c.getString("name"), (0 until cols.length()).map { list(cols.getJSONArray(it)) }, p.cols.map { it.keys })
            assertEquals(c.getString("name"), list(c.getJSONArray("waiting")), p.waiting)
            assertEquals(c.getString("name"), true, p.cols.sumOf { it.weight } <= HomeConfig.BUDGET)
        }
    }

    @Test fun widgetsAreKnownOnesOnceInTheirOrderAsSent() {
        val c = HomeConfig.clean(JSONObject().put("widgets", JSONArray(listOf("usage", "bogus", "usage", "system"))))
        val w = c.getJSONArray("widgets").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("usage", "system"), w)
        assertEquals(0, HomeConfig.clean(JSONObject()).getJSONArray("widgets").length())   // none until asked for
    }

    @Test fun theWeatherCardAndItsPlace() {
        val c = HomeConfig.clean(JSONObject().put("widgets", JSONArray(listOf("weather"))).put("place", "  Đà   Nẵng " + "x".repeat(100)))
        assertEquals("weather", c.getJSONArray("widgets").getString(0))
        assertEquals(HomeConfig.PLACE_MAX, c.getString("place").length)
        assertEquals("Đà Nẵng x", c.getString("place").take(9))
        assertEquals("", HomeConfig.clean(JSONObject()).getString("place"))      // automatic until a city is typed
    }

    @Test fun anAppInTheDockIsNeverHidden() {
        val c = HomeConfig.clean(JSONObject().put("dock", JSONArray(listOf("a.b"))).put("hidden", JSONArray(listOf("a.b", "c.d"))))
        assertEquals(listOf("c.d"), hidden(c))
    }

    @Test fun theGridOrderIsKeptWithoutRepeatsOrJunk() {
        val c = HomeConfig.clean(JSONObject().put("order", JSONArray(listOf("a.b", "../x", "c.d", "a.b"))))
        assertEquals(listOf("a.b", "c.d"), List(c.getJSONArray("order").length()) { c.getJSONArray("order").getString(it) })
        assertEquals(0, HomeConfig.clean(JSONObject()).getJSONArray("order").length())
    }

    @Test fun anUnknownWallOrFieldIsDropped() {
        val c = HomeConfig.clean(JSONObject().put("wall", "../../etc").put("evil", 1).put("photo", -5))
        assertEquals("night", c.getString("wall"))
        assertFalse(c.has("evil"))
        assertEquals(0L, c.getLong("photo"))
    }

    @Test fun playNextPlacementIsOneOfTheFourAndDefaultsToTheBottom() {
        assertEquals("bottom", HomeConfig.clean(JSONObject()).getString("watchAt"))
        for (k in listOf("top", "under1", "bottom", "tiles")) assertEquals(k, HomeConfig.clean(JSONObject().put("watchAt", k)).getString("watchAt"))
        assertEquals("bottom", HomeConfig.clean(JSONObject().put("watchAt", "middle")).getString("watchAt"))   // unknown: the default
        assertEquals("tiles", HomeConfig.merge(HomeConfig.clean(JSONObject()), JSONObject().put("watchAt", "tiles")).getString("watchAt"))
        assertFalse(HomeConfig.pickTheme(HomeConfig.clean(JSONObject().put("watchAt", "top"))).has("watchAt"))   // where things sit is not a look
    }

    @Test fun mergeChangesOnlyWhatIsSent() {
        val old = HomeConfig.clean(JSONObject().put("dock", JSONArray(listOf("a.b"))).put("wall", "ocean"))
        val n = HomeConfig.merge(old, JSONObject().put("clock24", false))
        assertEquals(listOf("a.b"), dock(n))
        assertEquals("ocean", n.getString("wall"))
        assertFalse(n.getBoolean("clock24"))
    }

    @Test fun everythingOnTheFirstScreenIsOnUntilTurnedOff() {
        val c = HomeConfig.clean(JSONObject())
        for (k in HomeConfig.SHOWS) assertTrue(k, c.getBoolean(k))
        val n = HomeConfig.merge(c, JSONObject().put("showSuggest", false).put("showWatch", false))
        assertFalse(n.getBoolean("showSuggest")); assertFalse(n.getBoolean("showWatch"))
        assertTrue(n.getBoolean("showAdd") && n.getBoolean("showGear"))
        assertTrue(HomeConfig.clean(JSONObject().put("showAdd", "junk")).getBoolean("showAdd"))   // not a boolean: the default
        assertFalse(HomeConfig.pickTheme(n).has("showWatch"))     // a look does not hide the way to the settings
    }

    @Test fun theRetiredPowerOptionIsDroppedFromOldSettings() {      // the bar has no power button any more: its actions are in the Control Center
        assertFalse(HomeConfig.clean(JSONObject().put("power", false)).has("power"))
        assertFalse(HomeConfig.merge(HomeConfig.clean(JSONObject()), JSONObject().put("power", false)).has("power"))
    }

    @Test fun animIsABooleanAndMerges() {
        assertFalse(HomeConfig.clean(JSONObject().put("anim", false)).getBoolean("anim"))
        assertEquals(true, HomeConfig.clean(JSONObject().put("anim", "no")).getBoolean("anim"))   // not a boolean: the default
        val n = HomeConfig.merge(HomeConfig.clean(JSONObject()), JSONObject().put("anim", false))
        assertFalse(n.getBoolean("anim"))
    }

    @Test fun everyBuiltInWallIsAccepted() {
        for (w in HomeConfig.WALLS - HomeConfig.WEB) assertEquals(w, HomeConfig.clean(JSONObject().put("wall", w)).getString("wall"))
        assertEquals(true, HomeConfig.WALLS.size >= 16)
    }

    private fun walls(c: JSONObject) = c.getJSONArray("walls").let { a -> List(a.length()) { a.getString(it) } }

    @Test fun theSetIsNeverEmptyAndAlwaysHoldsTheWallOnShow() {
        val none = HomeConfig.clean(JSONObject())
        assertEquals(listOf("night"), walls(none))
        assertEquals("off", none.getString("wevery"))
        val c = HomeConfig.clean(JSONObject().put("wall", "dawn").put("walls", JSONArray(listOf("peaks", "bogus", "peaks", "bing", "photo3", "; rm", "dunes"))))
        assertEquals(listOf("peaks", "bing", "photo3", "dunes"), walls(c))     // unknown ids and repeats go
        assertEquals("peaks", c.getString("wall"))                              // what shows is one of the set
        assertEquals(HomeConfig.WALLS.size - 1, walls(HomeConfig.clean(JSONObject().put("walls", JSONArray(HomeConfig.WALLS + HomeConfig.WALLS)))).size)   // twice the list: each once, the web page never in a set
        assertEquals("off", HomeConfig.clean(JSONObject().put("wevery", "every second")).getString("wevery"))
        for (e in HomeConfig.EVERY) assertEquals(e, HomeConfig.clean(JSONObject().put("wevery", e)).getString("wevery"))
    }

    @Test fun aClientThatPicksOneWallMakesTheSetThatOne() {
        val old = HomeConfig.clean(JSONObject().put("walls", JSONArray(listOf("dawn", "peaks"))).put("wevery", "1h"))
        val one = HomeConfig.merge(old, JSONObject().put("wall", "waves"))
        assertEquals(listOf("waves"), walls(one))
        assertEquals("1h", one.getString("wevery"))
        val set = HomeConfig.merge(old, JSONObject().put("walls", JSONArray(listOf("peaks", "waves"))))
        assertEquals("peaks", set.getString("wall"))                           // the one showing left the set: the first of it shows
        val keep = HomeConfig.merge(old, JSONObject().put("wevery", "off"))
        assertEquals(listOf("dawn", "peaks"), walls(keep))                     // turning it off keeps the set for later
    }

    @Test fun aWebPageNeedsAGoodAddressAndIsNeverInTheRotation() {
        fun web(u: String?, vararg extra: Pair<String, Any>) = HomeConfig.clean(JSONObject().put("wall", "web").apply { if (u != null) put("wurl", u) }.also { o -> extra.forEach { o.put(it.first, it.second) } })
        val ok = web("http://192.168.1.10:2283/?a=b#c")
        assertEquals("web", ok.getString("wall"))
        assertEquals("http://192.168.1.10:2283/?a=b#c", ok.getString("wurl"))
        assertEquals(listOf("web"), walls(ok))
        assertEquals("HTTPS://x.io", web("  HTTPS://x.io  ").getString("wurl"))                      // trimmed, any case of the scheme
        for (bad in listOf("", "x.io", "ftp://x.io", "javascript:alert(1)", "file:///sdcard/a.html", "http://", "http:///a", "http://a b", "http://x.io/" + "a".repeat(500))) {
            val c = web(bad)
            assertEquals(bad, "", c.getString("wurl"))                                              // dropped
            assertEquals(bad, "night", c.getString("wall"))                                         // web with no good address: like an unknown wall
        }
        assertEquals("night", web(null).getString("wall"))
        assertEquals("http://x.io/" + "a".repeat(488), web("http://x.io/" + "a".repeat(488)).getString("wurl"))   // exactly 500 stays
        // it stays out of a set sent with others; the others are kept
        val mixed = HomeConfig.clean(JSONObject().put("wall", "dawn").put("wurl", "http://x.io").put("walls", JSONArray(listOf("dawn", "web", "peaks"))))
        assertEquals(listOf("dawn", "peaks"), walls(mixed))
        assertEquals("http://x.io", mixed.getString("wurl"))                                         // the address is kept for when it is chosen again
        // choosing it from a rotating set makes the set that one; choosing another leaves it
        val old = HomeConfig.clean(JSONObject().put("walls", JSONArray(listOf("dawn", "peaks"))).put("wevery", "1h").put("wurl", "http://x.io"))
        val on = HomeConfig.merge(old, JSONObject().put("wall", "web"))
        assertEquals("web", on.getString("wall")); assertEquals(listOf("web"), walls(on))
        assertEquals(listOf("waves"), walls(HomeConfig.merge(on, JSONObject().put("wall", "waves"))))
        assertEquals("night", HomeConfig.merge(on, JSONObject().put("wurl", "nope")).getString("wall"))   // its address cleared while showing
        assertEquals("http://x.io", HomeConfig.forward(on).getString("wurl"))
    }

    @Test fun webUpdateModeIsWhitelistedAndKeepsToday() {
        val d = HomeConfig.clean(JSONObject())
        assertEquals("live", d.getString("wmode")); assertEquals(5, d.getInt("wsnap")); assertEquals(false, d.getBoolean("wkeep"))   // today's behaviour
        for (m in HomeConfig.WMODES) assertEquals(m, HomeConfig.clean(JSONObject().put("wmode", m)).getString("wmode"))
        assertEquals("live", HomeConfig.clean(JSONObject().put("wmode", "SNAP")).getString("wmode"))   // exact words only
        assertEquals("live", HomeConfig.clean(JSONObject().put("wmode", "; rm")).getString("wmode"))
        for (m in HomeConfig.WSNAPS) assertEquals(m, HomeConfig.clean(JSONObject().put("wsnap", m)).getInt("wsnap"))
        for (bad in listOf(0, 2, 3, -5, 61, 1440, Int.MAX_VALUE)) assertEquals(bad.toString(), 5, HomeConfig.clean(JSONObject().put("wsnap", bad)).getInt("wsnap"))
        assertEquals(true, HomeConfig.clean(JSONObject().put("wkeep", true)).getBoolean("wkeep"))
        val m = HomeConfig.merge(HomeConfig.clean(JSONObject().put("wmode", "snap").put("wsnap", 30).put("wkeep", true)), JSONObject().put("wall", "dawn"))
        assertEquals("snap", m.getString("wmode")); assertEquals(30, m.getInt("wsnap")); assertEquals(true, m.getBoolean("wkeep"))   // kept when another wall is chosen
        assertEquals(60, HomeConfig.merge(m, JSONObject().put("wsnap", 60)).getInt("wsnap"))
        assertEquals("snap", HomeConfig.forward(m).getString("wmode"))
    }

    @Test fun photosAreFilesNamedByTheirNumber() {
        assertEquals("wall.jpg", HomeConfig.photoName("photo"))
        assertEquals("wall2.jpg", HomeConfig.photoName("photo2"))
        assertEquals("wall10.jpg", HomeConfig.photoName("photo10"))
        assertEquals(HomeConfig.PHOTO, HomeConfig.photoPath("photo"))
        assertEquals(HomeConfig.PHOTO_MAX, HomeConfig.PHOTOS.size)
        assertFalse(HomeConfig.isPhoto("photo11"))
    }

    @Test fun nextSkipsWhatCannotBeShown() {
        val w = listOf("a", "b", "c")
        assertEquals("b", HomeConfig.next(w, "a") { true })
        assertEquals("a", HomeConfig.next(w, "c") { true })                    // round the end
        assertEquals("c", HomeConfig.next(w, "a") { it != "b" })               // a photo gone: skipped
        assertEquals(null, HomeConfig.next(w, "a") { it == "a" })              // nothing else to show
        assertEquals("a", HomeConfig.next(w, "zzz") { true })                  // the current one is not in the set: start at the front
        assertEquals(null, HomeConfig.next(emptyList(), "a") { true })
    }

    @Test fun dueAndWait() {
        val h = 3_600_000L
        assertFalse(HomeConfig.due("off", 0, 99 * h, 0))
        assertFalse(HomeConfig.due("show", 0, 99 * h, 0))                      // "show" moves on when Home is shown, not by time
        assertFalse(HomeConfig.due("1h", 10 * h, 10 * h + 59 * 60_000, 0))
        assertEquals(true, HomeConfig.due("1h", 10 * h, 11 * h, 0))
        assertEquals(true, HomeConfig.due("30m", 10 * h, 10 * h + 30 * 60_000, 0))
        assertEquals(true, HomeConfig.due("6h", 10 * h, 9 * h, 0))             // the clock went back: not frozen
        val day = 24 * h
        assertFalse(HomeConfig.due("day", 5 * day + 1 * h, 5 * day + 23 * h, 0))
        assertEquals(true, HomeConfig.due("day", 5 * day + 23 * h, 6 * day + 1 * h, 0))   // the next calendar day, though only 2 hours later
        assertFalse(HomeConfig.due("day", 5 * day + 23 * h, 6 * day + 1 * h, -3 * h))     // in a zone 3 hours behind it is still the same evening
        assertEquals(20 * 60_000L, HomeConfig.wait("1h", 10 * h, 10 * h + 40 * 60_000, 0))
        assertEquals(0L, HomeConfig.wait("1h", 10 * h, 13 * h, 0))
        assertEquals(h, HomeConfig.wait("day", 5 * day, 6 * day - h, 0))      // until midnight
        assertEquals(null, HomeConfig.wait("show", 0, 0, 0))
        assertEquals(null, HomeConfig.wait("off", 0, 0, 0))
    }

    @Test fun everySceneHasItsRecipe() {
        val spec = JSONObject(WallSpec.JSON)
        assertEquals(HomeConfig.ART.toSet(), spec.keys().asSequence().toSet())
        for (id in HomeConfig.ART) {
            val o = spec.getJSONObject(id)
            assertEquals(true, o.getJSONArray("bg").length() >= 2)
            assertEquals(true, o.getDouble("shade") in 0.3..1.0)
            assertEquals(true, o.getJSONArray("layers").length() >= 1)
        }
    }

    @Test fun noteIsOneShortLine() {
        assertEquals("Tắt TV lúc 9h nhé", HomeConfig.clean(JSONObject().put("note", "  Tắt TV\n lúc   9h nhé ")).getString("note"))
        assertEquals(HomeConfig.NOTE_MAX, HomeConfig.clean(JSONObject().put("note", "x".repeat(500))).getString("note").length)
        assertEquals("", HomeConfig.clean(JSONObject()).getString("note"))
    }

    @Test fun dreamIsOffUntilSet() {
        assertFalse(HomeConfig.clean(JSONObject()).getBoolean("dream"))
        assertEquals(true, HomeConfig.merge(HomeConfig.clean(JSONObject()), JSONObject().put("dream", true)).getBoolean("dream"))
    }

    /** An older client keeps a newer TV Home's cards it does not know, and their sizes; junk names still go. */
    @Test fun aClientForwardsCardsItDoesNotKnow() {
        val f = HomeConfig.forward(JSONObject().put("widgets", JSONArray(listOf("system", "radar", "BAD name", "system")))
            .put("wsize", JSONObject().put("radar", "big").put("system", "huge")))
        assertEquals(listOf("system", "radar"), (0 until f.getJSONArray("widgets").length()).map { f.getJSONArray("widgets").getString(it) })
        assertEquals("big", f.getJSONObject("wsize").getString("radar"))
        assertFalse(f.getJSONObject("wsize").has("system"))
        assertEquals(listOf("system"), HomeConfig.clean(f).getJSONArray("widgets").let { a -> (0 until a.length()).map { a.getString(it) } })   // TV Home's own decides
    }
    @Test fun keysAndPowerOnAppAreOffByDefaultAndKeepOnlyFreeKeys() {
        val c = HomeConfig.clean(JSONObject())
        assertFalse(c.getBoolean("keyon")); assertFalse(c.getBoolean("autoon")); assertFalse(c.getBoolean("autoall")); assertEquals("", c.getString("autoapp")); assertEquals(0, c.getJSONObject("keyapps").length())
        val m = HomeConfig.clean(JSONObject().put("keyon", true).put("autoon", true).put("autoapp", "a.b")
            .put("keyapps", JSONObject().put("183", "x.y").put("7", "x.z").put("170", "p.q").put("24", "no.vol").put("183x", "bad.one").put("184", "not a pkg")))
        assertEquals(mapOf(183 to "x.y", 7 to "x.z", 170 to "p.q"), HomeConfig.keyApps(m.getJSONObject("keyapps")))   // volume up (24), a bad name, a bad package: dropped
        assertEquals("a.b", m.getString("autoapp"))
        assertEquals("", HomeConfig.clean(JSONObject().put("autoon", true).put("autoapp", "x; rm -rf /")).getString("autoapp"))
        assertEquals("", HomeConfig.clean(JSONObject().put("autoon", false).put("autoall", true).put("autoapp", "a.b")).getString("autoapp"))   // switched off: no app left behind that "always" would still open
    }

    @Test fun keysSurviveTheForwardAndTheMerge() {
        val f = HomeConfig.forward(JSONObject().put("keyon", true).put("keyapps", JSONObject().put("185", "a.b")))
        assertEquals("a.b", f.getJSONObject("keyapps").getString("185"))
        val n = HomeConfig.merge(f, JSONObject().put("autoon", true))
        assertEquals(true, n.getBoolean("keyon")); assertEquals(true, n.getBoolean("autoon")); assertEquals("a.b", n.getJSONObject("keyapps").getString("185"))
    }

    @Test fun qrCardsAreKeptAndTheirKeyIsACardOnlyWithTheirContent() {
        val card = JSONObject().put("tpl", "link").put("f", JSONObject().put("url", "example.com")).put("cap", "Menu")
        val c = HomeConfig.clean(JSONObject().put("qrs", JSONObject().put("qr1", card).put("qr9", card).put("qr2", JSONObject().put("tpl", "wifi")))
            .put("widgets", JSONArray(listOf("clock", "qr1", "qr2", "qr3"))))
        assertEquals(listOf("qr1"), c.getJSONObject("qrs").keys().asSequence().toList())      // qr9 is no key, qr2 has nothing in it
        assertEquals(listOf("clock", "qr1"), List(c.getJSONArray("widgets").length()) { c.getJSONArray("widgets").getString(it) })
        assertEquals("big", HomeConfig.defaultSize("qr1"))                                      // read from the sofa
        // taking a card out of "qrs" takes its place off the screen too; the others stay
        val n = HomeConfig.merge(c, JSONObject().put("qrs", JSONObject()))
        assertEquals(listOf("clock"), List(n.getJSONArray("widgets").length()) { n.getJSONArray("widgets").getString(it) })
        // a size chosen for a QR card is kept
        val s = HomeConfig.merge(c, JSONObject().put("wsize", JSONObject().put("qr1", "small")))
        assertEquals("small", s.getJSONObject("wsize").getString("qr1"))
        assertTrue(HomeConfig.clean(s).getJSONObject("qrs").getJSONObject("qr1").getJSONObject("f").getString("url") == "example.com")
    }

    @Test fun liveTilesRoundTrip() {
        val k = HomeConfig.liveKey("dev.tvremote.setup", "ram")
        assertEquals("tile:dev.tvremote.setup/ram", k)
        assertTrue(HomeConfig.isLive(k)); assertFalse(HomeConfig.isLive("dev.tvremote.setup")); assertFalse(HomeConfig.isLive("tile:x/ram; rm"))
        val c = HomeConfig.clean(JSONObject().put("tiles", JSONArray(listOf(k, k, "junk", "tile:Bad Pkg/ram")))
            .put("dock", JSONArray(listOf("com.a.b", k))).put("hidden", JSONArray(listOf("tile:com.x.y/z1"))).put("order", JSONArray(listOf(k))))
        assertEquals(listOf(k), c.getJSONArray("tiles").let { a -> List(a.length()) { a.getString(it) } })
        assertEquals(listOf("com.a.b", k), dock(c))                    // a tile sits among the apps in the dock, the hidden list and the order
        assertEquals(listOf("tile:com.x.y/z1"), hidden(c))
        assertEquals(c.toString(), HomeConfig.clean(JSONObject(c.toString())).toString())   // cleaning twice changes nothing
        assertEquals("dev.tvremote.setup", HomeConfig.liveProvider(k)); assertEquals("ram", HomeConfig.liveId(k))
        assertEquals(0, HomeConfig.clean(JSONObject()).getJSONArray("tiles").length())
        assertEquals(HomeConfig.TILES_MAX, HomeConfig.clean(JSONObject().put("tiles", JSONArray((1..30).map { "tile:com.x.y/t$it" }))).getJSONArray("tiles").length())
    }

    @Test fun videoWallsAndTheirSettings() {
        assertTrue(HomeConfig.isVideo("vid:0a1b2c3d")); assertFalse(HomeConfig.isVideo("vid:0A1B2C3D")); assertFalse(HomeConfig.isVideo("vid:../etc")); assertFalse(HomeConfig.isVideo("vid:1234"))
        val c = HomeConfig.clean(JSONObject().put("wall", "vid:0a1b2c3d").put("walls", JSONArray(listOf("vid:0a1b2c3d", "vid:zz", "night", "vid:0a1b2c3d")))
            .put("vsound", true).put("vvol", 77).put("vfps", "24").put("vres", "999"))
        assertEquals("vid:0a1b2c3d", c.getString("wall"))
        assertEquals(listOf("vid:0a1b2c3d", "night"), c.getJSONArray("walls").let { a -> List(a.length()) { a.getString(it) } })
        assertFalse(c.has("vsound")); assertFalse(c.has("vvol")); assertEquals("24", c.getString("vfps")); assertEquals("", c.getString("vres"))
        val d = HomeConfig.clean(JSONObject())
        assertEquals("", d.getString("vfps"))
        assertEquals("night", HomeConfig.clean(JSONObject().put("wall", "vid:nothex!!")).getString("wall"))
    }

    // ---- themes ----
    private fun op(vararg kv: Pair<String, String>) = JSONObject().put("theme", JSONObject().also { o -> kv.forEach { (k, v) -> o.put(k, v) } })
    private fun themes(c: JSONObject) = c.getJSONArray("themes").let { a -> List(a.length()) { a.getJSONObject(it) } }

    @Test fun builtinThemesAreWholeCleanLooks() {
        assertTrue(HomeConfig.BUILTIN.size >= 4)
        for ((id, conf) in HomeConfig.BUILTIN) {
            assertEquals(id, HomeConfig.THEME_KEYS.sorted(), conf.keys().asSequence().toList().sorted())              // every key, in order: an apply leaves nothing of the look before
            assertEquals(id, HomeConfig.pickTheme(HomeConfig.clean(conf)).length(), conf.length())   // already clean: cleaning changes nothing
            assertTrue(HomeConfig.knownWall(conf.getString("wall")))
        }
    }

    @Test fun applySwitchesTheLookAndKeepsTheRest() {
        val old = JSONObject().put("dock", JSONArray(listOf("com.a.b"))).put("lang", "vi").put("keyon", true).put("wall", "ocean").put("cols", 8).put("focus", "ring")
            .put("qrs", JSONObject().put("qr1", JSONObject().put("tpl", "link").put("f", JSONObject().put("url", "https://x.dev")).put("cap", "x").put("show", true)))
            .put("widgets", JSONArray(listOf("system", "qr1")))
        val n = HomeConfig.merge(old, op("apply" to "cinema"))
        assertEquals("web", n.getString("wall")); assertEquals(55, n.getInt("shade")); assertEquals(6, n.getInt("cols")); assertEquals("ring", n.getString("focus"))
        assertEquals(listOf("com.a.b"), dock(n)); assertEquals("vi", n.getString("lang")); assertTrue(n.getBoolean("keyon"))      // the apps, language, keys are not a look
        assertEquals(listOf("qr1"), n.getJSONArray("widgets").let { a -> List(a.length()) { a.getString(it) } })              // the cards go, the QR card stays
        assertTrue(n.getJSONObject("qrs").has("qr1"))
        assertEquals("cinema", HomeConfig.themeNow(n))
        assertFalse(n.has("theme"))                                                                                            // an operation, never stored
    }

    @Test fun unsavedLookIsKeptBeforeASwitch() {
        var c = HomeConfig.merge(JSONObject(), JSONObject().put("wall", "teal").put("cols", 7).put("shade", 70))
        assertFalse(c.has("before"))
        c = HomeConfig.merge(c, op("apply" to "cinema"))                                                                       // the look matched nothing: kept
        assertEquals("teal", c.getJSONObject("before").getString("wall")); assertEquals("cinema", HomeConfig.themeNow(c))
        c = HomeConfig.merge(c, op("apply" to "tech"))                                                                         // cinema is a saved look: the slot stays
        assertEquals("teal", c.getJSONObject("before").getString("wall"))
        c = HomeConfig.merge(c, op("apply" to "before"))                                                                       // the way back; the look left (tech) matches a theme
        assertEquals("teal", c.getString("wall")); assertEquals(7, c.getInt("cols")); assertEquals("before", HomeConfig.themeNow(c))
        c = HomeConfig.merge(c, JSONObject().put("wall", "ocean")); c = HomeConfig.merge(c, op("apply" to "calm"))             // a new unsaved look replaces the slot
        assertEquals("ocean", c.getJSONObject("before").getString("wall"))
        assertTrue(HomeConfig.themeUses(c, "ocean")); assertFalse(HomeConfig.themeUses(c, "teal"))                             // the slot keeps its pictures
        c = HomeConfig.merge(c, op("del" to "before")); assertFalse(c.has("before"))
        c = HomeConfig.merge(c, op("apply" to "before")); assertEquals("calm", HomeConfig.themeNow(c))                        // nothing there: nothing happens
    }

    @Test fun saveApplyDeleteMine() {
        var c = HomeConfig.merge(JSONObject(), JSONObject().put("wall", "teal").put("cols", 7).put("shade", 70))
        c = HomeConfig.merge(c, op("save" to "  Evening  "))
        assertEquals(1, themes(c).size); assertEquals("Evening", themes(c)[0].getString("name")); assertEquals("t1", themes(c)[0].getString("id"))
        assertEquals("t1", HomeConfig.themeNow(c))
        val t1 = themes(c)[0].getJSONObject("conf")
        assertEquals("teal", t1.getString("wall")); assertEquals(7, t1.getInt("cols"))
        c = HomeConfig.merge(c, JSONObject().put("wall", "ocean").put("cols", 4))
        assertEquals("", HomeConfig.themeNow(c))
        c = HomeConfig.merge(c, op("save" to "evening"))                                                                       // same name: saved over, not a second
        assertEquals(1, themes(c).size); assertEquals("ocean", themes(c)[0].getJSONObject("conf").getString("wall"))
        c = HomeConfig.merge(c, op("save" to "Day"))
        assertEquals(listOf("t1", "t2"), themes(c).map { it.getString("id") })
        c = HomeConfig.merge(c, op("apply" to "calm")); c = HomeConfig.merge(c, op("apply" to "t1"))
        assertEquals("ocean", c.getString("wall")); assertEquals(4, c.getInt("cols"))
        c = HomeConfig.merge(c, op("del" to "t1"))
        assertEquals(listOf("t2"), themes(c).map { it.getString("id") })
        assertEquals(themes(c).size, themes(HomeConfig.merge(c, op("apply" to "nothing"))).size); assertEquals(c.getString("wall"), HomeConfig.merge(c, op("apply" to "nothing")).getString("wall"))                                  // an unknown id changes nothing
        // survives clean and a trip through forward (what the clients send)
        assertEquals(1, themes(HomeConfig.clean(c)).size)
        assertEquals("t2", HomeConfig.forward(JSONObject().put("theme", JSONObject().put("apply", "t2").put("junk", 1))).getJSONObject("theme").let { it.getString("apply") })
        assertFalse(HomeConfig.forward(JSONObject().put("theme", JSONObject())).has("theme"))
    }

    @Test fun themesAreCapped() {
        var c = JSONObject()
        for (i in 1..HomeConfig.THEMES_MAX + 3) c = HomeConfig.merge(c, op("save" to "T$i"))
        assertEquals(HomeConfig.THEMES_MAX, themes(c).size)
        assertEquals(HomeConfig.THEME_NAME_MAX, themes(HomeConfig.merge(JSONObject(), op("save" to "x".repeat(60))))[0].getString("name").length)
        val bad = JSONObject().put("themes", JSONArray().put(JSONObject().put("id", "../x").put("name", "a").put("conf", JSONObject())).put(JSONObject().put("id", "t1").put("name", " ").put("conf", JSONObject())))
        assertEquals(0, themes(HomeConfig.clean(bad)).size)
    }

    @Test fun aThemeKeepsItsFilesAliveAndAGoneOneIsSkipped() {
        val vid = "vid:0a1b2c3d"
        var c = HomeConfig.merge(JSONObject(), JSONObject().put("wall", vid).put("walls", JSONArray(listOf(vid, "photo2"))).put("wevery", "1h"))
        c = HomeConfig.merge(c, op("save" to "Mine"))
        c = HomeConfig.merge(c, JSONObject().put("wall", "night"))
        assertTrue(HomeConfig.themeUses(c, vid)); assertTrue(HomeConfig.themeUses(c, "photo2")); assertFalse(HomeConfig.themeUses(c, "photo3"))
        assertFalse(HomeConfig.themeUses(HomeConfig.merge(c, op("del" to "t1")), vid))                                         // the theme gone: free to delete
        // the video's file is gone: the photo of the set shows, never a black screen on a missing file
        val n = HomeConfig.merge(c, op("apply" to "t1")) { it != vid }
        assertEquals("photo2", n.getString("wall")); assertEquals(listOf("photo2"), walls(n))
        assertEquals("night", HomeConfig.merge(c, op("apply" to "t1")) { false }.getString("wall"))
    }

    @Test fun namesAreCleaned() {
        val long = "x".repeat(50)
        val g = JSONObject().put("com.google.android.youtube.tv", "  YouTube   TV ").put("dev.cobalt.youtube", long).put("pin:dev.a.b/c", "Kids")
            .put("tile:dev.tvremote.setup/ram", "Tile").put("bad pkg", "Nope").put("com.empty.app", "   ")
        val n = HomeConfig.clean(JSONObject().put("names", g)).getJSONObject("names")
        assertEquals("YouTube TV", n.getString("com.google.android.youtube.tv"))
        assertEquals(HomeConfig.NAME_MAX, n.getString("dev.cobalt.youtube").length)
        assertEquals("Kids", n.getString("pin:dev.a.b/c"))
        assertEquals(3, n.length())                                    // a live widget, a bad key and an empty name are dropped
        val many = JSONObject().also { o -> (1..150).forEach { o.put("com.app.n$it", "N$it") } }
        assertEquals(HomeConfig.NAMES_MAX, HomeConfig.clean(JSONObject().put("names", many)).getJSONObject("names").length())
        assertEquals(0, HomeConfig.clean(JSONObject()).getJSONObject("names").length())
        assertEquals(n.toString(), HomeConfig.clean(JSONObject().put("names", n)).getJSONObject("names").toString())
        val m = HomeConfig.merge(HomeConfig.clean(JSONObject().put("names", n)), JSONObject().put("names", JSONObject()))
        assertEquals(0, m.getJSONObject("names").length())             // an empty map clears them all
    }

    @Test fun marketAndNewsCardsHaveTheirOptionsAndOwnFeeds() {
        assertEquals(listOf("size", "look", "layout", "show", "btc"), HomeConfig.options("market").map { it.id })
        assertEquals(listOf("size", "look", "src"), HomeConfig.options("news").map { it.id })
        assertTrue("market" in HomeConfig.WIDGETS && "news" in HomeConfig.WIDGETS)
        val c = HomeConfig.clean(JSONObject().put("feeds", JSONArray(listOf("https://a.x/r.xml", "ftp://no", "https://a.x/r.xml", "https://b.x/1", "https://b.x/2", "https://b.x/3", "https://b.x/4", "javascript:1"))))
        val f = c.getJSONArray("feeds")
        assertEquals(HomeConfig.FEEDS_MAX, f.length())                           // only web links, once each, at most five
        assertEquals("https://a.x/r.xml", f.getString(0)); assertEquals("https://b.x/1", f.getString(1))
        assertEquals(0, HomeConfig.clean(JSONObject()).getJSONArray("feeds").length())
    }

    @Test fun communityThemesKeepTheirSenderAndNoCapCutsThemOff() {
        fun one(id: String, extra: String) = "{\"id\":\"$id\",\"names\":{\"en\":\"N $id\"},\"conf\":{\"wall\":\"night\"}$extra}"
        val list = (1..60).map { one("p$it", "") } + one("c1", ",\"community\":true,\"by\":\"  Lan  Anh \"") + one("c2", ",\"community\":true")   // c2: no sender, so not a community one
        val r = HomeConfig.parseReady("{\"v\":1,\"themes\":[" + list.joinToString(",") + "]}", "99.0.0")
        assertEquals(62, r.size)                                                // more than the old cap of 40
        assertTrue(r.first { it.id == "c1" }.community); assertEquals("Lan Anh", r.first { it.id == "c1" }.by)
        assertFalse(r.first { it.id == "c2" }.community); assertFalse(r.first { it.id == "p1" }.community)
    }

    @Test fun prayerWidgetOptions() {
        assertEquals(listOf("size", "look", "method", "asr", "hijri"), HomeConfig.options("prayer").map { it.id })
        assertEquals("auto", HomeConfig.optValue(JSONObject(), "prayer", "method"))
        assertEquals("big", HomeConfig.defaultSize("prayer"))
        assertTrue("prayer" in HomeConfig.TONAL && "prayer" in HomeConfig.schema().keys().asSequence().toList())
        val c = HomeConfig.clean(JSONObject().put("wopt", JSONObject().put("prayer", JSONObject().put("method", "jakim").put("asr", "hanafi").put("hijri", "hijri_p1").put("hijri2", "x"))))
        assertEquals("jakim", HomeConfig.optValue(c, "prayer", "method")); assertEquals("hanafi", HomeConfig.optValue(c, "prayer", "asr")); assertEquals("hijri_p1", HomeConfig.optValue(c, "prayer", "hijri"))
        val bad = HomeConfig.clean(JSONObject().put("wopt", JSONObject().put("prayer", JSONObject().put("method", "nope"))))
        assertEquals("auto", HomeConfig.optValue(bad, "prayer", "method"))
    }
}
