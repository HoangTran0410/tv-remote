package dev.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.tvremote.core.web.Cancel
import dev.tvremote.core.web.Cancelled
import dev.tvremote.core.manage.TvPaths
import dev.tvremote.core.web.NoRoom

/** Stop, and the check that the TV has room before a file is sent. */
class TransferTest {
    private val df = "Filesystem       1K-blocks     Used Available Use% Mounted on\n/dev/fuse         12345678  9000000   1200000  89% /storage/emulated\n"

    @Test fun dfAvailableColumn() {
        assertEquals(1_200_000L * 1024, TvPaths.parseDfFree(df))
        assertEquals(500L * 1024, TvPaths.parseDfFree("Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-9\n 100 200 500 70% /data"))   // a long name wraps
        assertNull(TvPaths.parseDfFree(""))
        assertNull(TvPaths.parseDfFree("df: /nope: No such file or directory"))
    }

    @Test fun roomRule() {
        val mb = 1L shl 20
        assertTrue(TvPaths.roomFor(100 * mb, 1, 200 * mb))
        assertFalse(TvPaths.roomFor(100 * mb, 1, 110 * mb))               // the reserve is kept
        assertFalse(TvPaths.roomFor(100 * mb, 2, 200 * mb))               // an APK: the copy and the installed app
        assertTrue(TvPaths.roomFor(100 * mb, 2, 300 * mb))
        assertEquals(2 * 100 * mb + 16 * mb, TvPaths.needed(100 * mb, 2))
    }

    @Test fun refusalIsSaidWithSizesAndWhatToDo() {
        val e = NoRoom(3_400_000_000L, 1_200_000_000L, 3_416_000_000L, false)
        assertEquals("storage full: file=3400000000 free=1200000000 need=3416000000 apk=0", e.message)
        assertEquals("TV chỉ còn trống 1.2 GB, không đủ chép tệp 3.4 GB (cần trống ít nhất 3.4 GB). Xóa bớt app hoặc tệp (tab Sức khỏe / Tệp) rồi thử lại.", Errors.say(e, "vi"))
        assertTrue(Errors.say(e, "en").startsWith("The TV has only 1.2 GB free"))
        assertTrue(Errors.say(NoRoom(1, 1, 1, true), "en").contains("APK"))
        assertNotNull(NoRoom.parse("x " + e.message))
        assertNull(NoRoom.parse("No space left"))
    }

    @Test fun stopIsNotAnError() {
        val c = Cancel()
        var hooked = 0
        c.onStop { hooked++ }
        c.check()
        c.stop()
        assertEquals(1, hooked)
        c.onStop { hooked++ }                                            // late: runs at once
        assertEquals(2, hooked)
        assertTrue(runCatching { c.check() }.exceptionOrNull() is Cancelled)
        assertEquals("Đã dừng.", Errors.say(Cancelled(), "vi"))
    }
}
