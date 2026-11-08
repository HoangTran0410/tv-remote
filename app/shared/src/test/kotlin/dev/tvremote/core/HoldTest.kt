package dev.tvremote.core
import dev.tvremote.core.manage.Manage
import org.junit.Assert.assertEquals
import org.junit.Test

/** `/api/hold`: a file made in the page, saved as a plain download (issue #25); its name goes into Content-Disposition. */
class HoldTest {
    @Test fun nameKeepsOnlyTheFileName() {
        assertEquals("tv-backup.json", Manage.holdName("tv-backup.json"))
        assertEquals("x.json", Manage.holdName("../../etc/x.json"))
        assertEquals("y.png", Manage.holdName("C:\\a\\y.png"))
        assertEquals("ab.json", Manage.holdName("a\"b;.json".replace(".json", "") + ".json"))
        assertEquals("file", Manage.holdName("  "))
        assertEquals("Mi TV — sao lưu.json", Manage.holdName("Mi TV — sao lưu.json"))
    }
}
