package dev.tvremote.home

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBarConfigTest {
    @Test fun theHomeBarIsOffUntilAskedFor() {
        assertFalse(HomeConfig.clean(JSONObject()).getBoolean("homebar"))
        assertTrue(HomeConfig.clean(JSONObject().put("homebar", true)).getBoolean("homebar"))
        assertFalse(HomeConfig.clean(JSONObject().put("homebar", "yes")).getBoolean("homebar"))   // only a real true turns it on
    }

    @Test fun oneClockOnTheScreen() {
        fun c(vararg w: String) = JSONObject().put("widgets", org.json.JSONArray(w.toList()))
        assertTrue(HomeConfig.barClock(c("weather")) && HomeConfig.barDate(c("weather")))
        assertFalse(HomeConfig.barClock(c("clock"))); assertFalse(HomeConfig.barDate(c("clock")))      // the clock widget is the time and the day
        assertTrue(HomeConfig.barClock(c("calendar"))); assertFalse(HomeConfig.barDate(c("calendar")))
        assertTrue(HomeConfig.barDate(c("clock").put("dstyle", "own")))                               // the person's own words stay
        assertFalse(HomeConfig.barClock(c().put("showClock", false)))
        assertTrue(HomeConfig.barClock(c("lunar")))                                                    // the widget taken off: the bar's is back
    }
}
