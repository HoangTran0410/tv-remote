package dev.tvremote.setup.rules

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MicTvLangTest {
    private fun set(o: JSONObject, lang: String) = Rules.apply(o, JSONObject().put("do", "mic_tv_lang").put("lang", lang), 0L)

    @Test fun tags_and_empty_are_kept() {
        val o = JSONObject()
        for (t in listOf("ar-SA", "fr", "fil-PH", "")) { set(o, t); assertEquals(t, o.optString("mic_tv_lang")) }
    }

    @Test fun anything_else_is_refused_and_changes_nothing() {
        val o = JSONObject(); set(o, "ar-SA")
        for (t in listOf("ar_SA", "AR-sa", "a", "arabic", "ar-SAU", "en-US;x", " ar")) {
            try { set(o, t); fail("accepted $t") } catch (e: IllegalArgumentException) { }
        }
        assertEquals("ar-SA", o.optString("mic_tv_lang"))
    }

    @Test fun add_on_language_is_not_touched() {
        val o = JSONObject().put("mic_lang", "vi"); set(o, "ar-SA")
        assertEquals("vi", o.optString("mic_lang"))
    }
}
