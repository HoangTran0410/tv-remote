package dev.tvremote.core.picture

import dev.tvremote.core.scenes.Scenes
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Only this device" on Android 13+: the sound is taken by playback capture, which leaves the TV's speakers playing, so the TV's music
 * volume is turned to 0 over the session's ADB while someone listens and put back the way it was when listening stops (Roku's
 * Private Listening). Older TVs need none of this: their capture takes the whole output and the speakers go silent by themselves.
 *
 * The volume before is written on the TV ([MARK]) first. A crash, a dropped link or a phone that went away leaves that file behind,
 * and the next session that connects ([recover]) puts the volume back and removes it. Everything goes through `sh` (one ADB shell
 * command in, its output out), so the tests run it on a fake TV. What the person set meanwhile wins: if the volume is no longer 0
 * at the end, it is theirs and only the file goes.
 */
object PrivateListen {
    const val MARK = "/data/local/tmp/tvr-listen-vol"
    /** Sessions of this process that hold the TV quiet: a [recover] of another one must not undo a listener that is still there. */
    private val holders = AtomicInteger()

    /** What a TV answers to `cmd media_session volume --stream 3 --get`: "volume is 6 in range [0..15]". */
    fun level(sh: (String) -> String): Int? =
        Regex("volume is (\\d+)").find(sh("cmd media_session volume --stream 3 --get 2>&1"))?.groupValues?.get(1)?.toIntOrNull()

    /** The volume a session left behind ([MARK]), or null: the file's text, as the start facts read it. */
    fun left(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..1000 }

    /**
     * What to do at the end: the level to put back, or null when nothing is owed. [mark]: the file's content; [now]: the volume now
     * (null: not readable). Back only when the volume still sits at 0, where we left it; a level the person set since is theirs.
     */
    fun owed(mark: Int?, now: Int?): Int? = if (mark != null && mark > 0 && now == 0) mark else null

    /**
     * Turns the TV quiet. true: it is (or already was) at 0 and [release] puts it back; false: this TV keeps its volume (a fixed
     * level through HDMI-CEC, a command refused), nothing is left changed and the caller says the TV keeps playing.
     */
    fun engage(sh: (String) -> String): Boolean {
        val was = left(sh("cat $MARK 2>/dev/null"))                    // a mark of an earlier run is the true "before"
        val now = level(sh) ?: return false
        val before = was ?: now
        if (before == 0) return true                                   // quiet already, nothing of ours to put back
        sh("echo $before > $MARK")
        val quiet = try { Scenes.volume(sh, 0); level(sh) == 0 } catch (e: Exception) { false }
        if (!quiet) { try { Scenes.volume(sh, before) } catch (e: Exception) { }; sh("rm -f $MARK"); return false }
        holders.incrementAndGet()
        return true
    }

    /** Puts the volume back (when still at 0) and removes the mark. [held]: this session called [engage] with true. */
    fun release(sh: (String) -> String, held: Boolean = true) {
        if (held) drop()
        putBack(sh, left(sh("cat $MARK 2>/dev/null")))
    }

    @Synchronized private fun drop() { if (holders.get() > 0) holders.decrementAndGet() }   // no updateAndGet: API 24, minSdk 21

    /** At a new connection: undoes a mute left behind by a run that never got to [release], unless a listener of this process holds it. */
    fun recover(sh: (String) -> String, mark: Int?) {
        if (mark == null || holders.get() > 0) return
        putBack(sh, mark)
    }

    private fun putBack(sh: (String) -> String, mark: Int?) {
        if (mark == null) return
        val back = owed(mark, level(sh))
        if (back != null) Scenes.volume(sh, back)
        sh("rm -f $MARK")
    }

    /** Something plays on the TV right now (an audio player in the started state): quiet capture then means an app that refuses capture. */
    fun playing(sh: (String) -> String): Boolean =
        sh("dumpsys audio 2>/dev/null | grep -c 'AudioPlaybackConfiguration.*state:started'").trim().toIntOrNull()?.let { it > 0 } == true

    /** Test hook: no session holds the quiet. */
    internal fun reset() { holders.set(0) }
}
