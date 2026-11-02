package dev.tvremote.games

import org.junit.Assert.assertEquals
import org.junit.Test

class LangTest {
    @Test fun emptyChoiceFollowsTheTv() {
        assertEquals("vi", Lang.decide("", "vi"))
        assertEquals("en", Lang.decide("", "en"))
        assertEquals("ru", Lang.decide("", "ru"))
        assertEquals("en", Lang.decide("", "zz"))                     // English is every other language's fallback
    }

    @Test fun aChoiceBeatsTheTv() {
        assertEquals("vi", Lang.decide("vi", "en"))
        assertEquals("en", Lang.decide("en", "vi"))
        assertEquals("ru", Lang.decide("ru", "en"))
        assertEquals("en", Lang.decide("xx", "vi"))                   // an unknown saved value is English, not the TV's
    }
}
