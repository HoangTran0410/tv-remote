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
}
