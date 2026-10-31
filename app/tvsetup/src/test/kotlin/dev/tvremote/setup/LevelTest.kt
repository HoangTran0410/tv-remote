package dev.tvremote.setup

import dev.tvremote.home.HomeConfig
import dev.tvremote.setup.home.LiveWidgets
import org.junit.Assert.assertEquals
import org.junit.Test

/** The RAM live widget and TV Home's System widget colour a fill the same way: one function ([HomeConfig.level], amber 70, red 85). */
class LevelTest {
    @Test fun edges() {
        assertEquals("ok", HomeConfig.level(69)); assertEquals("warn", HomeConfig.level(70))
        assertEquals("warn", HomeConfig.level(84)); assertEquals("bad", HomeConfig.level(85))
    }

    @Test fun theLiveWidgetSaysTheSame() { for (p in 0..100) assertEquals("at $p %", HomeConfig.level(p), LiveWidgets.accent(p)) }
}
