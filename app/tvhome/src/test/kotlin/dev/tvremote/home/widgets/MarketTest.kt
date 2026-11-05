package dev.tvremote.home.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketTest {
    private fun res(n: String) = javaClass.getResourceAsStream("/info/$n")!!.readBytes().toString(Charsets.UTF_8)

    @Test fun bankRatesInTheOrderShown() {
        val r = Market.parseVcb(res("vcb.xml"))
        assertEquals(listOf("USD", "EUR", "JPY", "KRW", "CNY", "GBP").filter { c -> r.any { it.code == c } }, r.map { it.code })
        val usd = r.first { it.code == "USD" }
        assertEquals(25640.0, usd.buy, 0.01); assertEquals(26020.0, usd.sell, 0.01)
        assertTrue(r.all { it.sell > 0 })
    }

    @Test fun btmcPerLuong() {
        val g = Market.parseBtmc(res("btmc.json"))!!
        assertEquals(137_100_000L, g.sjcBuy); assertEquals(141_700_000L, g.sjcSell)
        assertTrue(g.ringBuy > 0 && g.ringSell >= g.ringBuy)
    }

    @Test fun pnjPerLuong() {
        val g = Market.parsePnj(res("pnj.json"))!!
        assertEquals(138_700_000L, g.sjcBuy); assertEquals(141_700_000L, g.sjcSell)
    }

    @Test fun binance() {
        val b = Market.parseBinance(res("binance.json"))!!
        assertEquals(80459.22, b.usd, 0.01); assertEquals(-1.068, b.pct, 0.001)
    }

    @Test fun fuelFromSaigonPetro() {                                  // its home page's price marquee, saved 2026-11-05
        val f = Market.parseFuel(res("saigonpetro.html"), "Saigon Petro")!!
        assertEquals(21_150L, f.ron95); assertEquals(20_890L, f.e5); assertEquals(28_690L, f.diesel); assertEquals("Saigon Petro", f.src)
    }

    @Test fun fuelFromWebgia() {                                       // Petrolimex's table, zone 1 first; RON 95 "mức 5" is not the one shown
        val f = Market.parseFuel(res("webgia.html"))!!
        assertEquals(21_150L, f.ron95); assertEquals(20_890L, f.e5); assertEquals(28_690L, f.diesel)
    }

    @Test fun fuelSinceTheLastChange() {
        assertEquals(21_150.0 to null, Market.changeStep(null, null, 21_150.0))                 // the first price: nothing to compare with
        assertEquals(21_150.0 to null, Market.changeStep(21_150.0, null, 21_150.0))             // the same price again
        assertEquals(20_800.0 to 21_150.0, Market.changeStep(21_150.0, null, 20_800.0))         // a change: the old price is the reference
        assertEquals(20_800.0 to 21_150.0, Market.changeStep(20_800.0, 21_150.0, 20_800.0))     // and stays until the next change
    }

    @Test fun notTheShapeGivesNull() {
        assertNull(Market.parseFuel("<html>blocked</html>")); assertNull(Market.parseFuel("<span>Xăng E5 RON 92</span><span>20.890 đ</span>"))
        assertNull(Market.parseBtmc("{}")); assertNull(Market.parsePnj("nope")); assertNull(Market.parseBinance("{\"code\":-1}"))
        assertTrue(Market.parseVcb("<html>blocked</html>").isEmpty())
    }

    @Test fun savedAndReadBack() {
        val s = Market.Snap(Market.parseVcb(res("vcb.xml")), Market.parseBtmc(res("btmc.json")), Market.parseBinance(res("binance.json")), 1, 2, 3)
        val b = Market.fromJson(Market.toJson(s).toString())!!
        assertEquals(s.rates.size, b.rates.size); assertEquals(s.gold!!.sjcSell, b.gold!!.sjcSell); assertNotNull(b.btc); assertEquals(3L, b.at)
        val f = Market.fromJson(Market.toJson(Market.Snap(emptyList(), null, null, 1, 2, 3, Market.parseFuel(res("webgia.html"), "W"), 9)).toString())!!
        assertEquals(28_690L, f.fuel!!.diesel); assertEquals("W", f.fuel!!.src); assertEquals(9L, f.at)
        assertNull(Market.fromJson(Market.toJson(s).toString())!!.fuel)                              // a snapshot from before fuel
    }
}

class MarketTrendTest {
    @org.junit.Test fun `yesterday's last value becomes the reference on a new day`() {
        org.junit.Assert.assertEquals(100.0, Market.refStep("20261024", 100.0, null, "20261025")!!, 0.0)
        org.junit.Assert.assertEquals(90.0, Market.refStep("20261025", 100.0, 90.0, "20261025")!!, 0.0)    // same day: keep the one from before
        org.junit.Assert.assertNull(Market.refStep("", null, null, "20261025"))                           // the first day: nothing to compare with
    }

    @org.junit.Test fun fuelRowWithoutPriceBorrowsNothing() {
        val f = Market.parseFuel("<tr><td>Xăng RON 95-III</td><td>21.150</td></tr><tr><td>Xăng E5 RON 92-II</td><td>-</td></tr><tr><td>Dầu DO 0,05S-II</td><td>19.000 đ</td></tr>")!!
        org.junit.Assert.assertEquals(21150L, f.ron95)
        org.junit.Assert.assertEquals(0L, f.e5)                              // was the diesel price
        org.junit.Assert.assertEquals(19000L, f.diesel)
    }
}
