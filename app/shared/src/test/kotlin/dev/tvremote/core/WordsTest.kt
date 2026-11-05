package dev.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsTest {
    @Test fun readsThePagesFiles() {
        assertEquals("Đổi TV", Words.get("vi", "page.change_tv").trim())
        assertEquals("Change TV", Words.get("en", "page.change_tv").trim())
        assertEquals("Change TV", Words.get("xx", "page.change_tv").trim())   // a language without a file: English
        assertEquals("no.such_id", Words.get("en", "no.such_id"))
    }

    @Test fun placeholdersAndPlurals() {
        assertEquals("· 1 TV", Words.get("en", "fleet.count", "n" to 1))
        assertEquals("· 3 TVs", Words.get("en", "fleet.count", "n" to 3))
    }

    @Test fun picksALanguageItHas() {
        assertEquals("vi", Words.pick("vi-VN"))
        assertEquals("en", Words.pick("en_US"))
        assertEquals("en", Words.pick("de"))
        assertEquals("en", Words.pick(""))
    }

    // the Phone app turns its Settings, About and dialogs right to left for Arabic, told by the language's own name in langs.json
    @Test fun rightToLeftByTheLanguagesName() {
        val was = Words.lang
        try {
            Words.lang = "ar"; assertEquals(true, Words.rtl)
            Words.lang = "en"; assertEquals(false, Words.rtl)
            Words.lang = "vi"; assertEquals(false, Words.rtl)
        } finally { Words.lang = was }
    }
}
