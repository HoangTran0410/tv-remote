package dev.tvremote.core.adb
import dev.tvremote.core.WireLog

/**
 * Some firmware (TCL ak30a5, cleanyourtv #37 / #98) patches `pm install` to refuse installs from the shell ("install apk has be
 * disabled from pm by system default") but leaves the PackageInstaller session commands alone, which is the way the TV's own app
 * store installs. So when `pm install` says that, the same APK goes in through a session: create, write, commit. One pm command
 * per shell call, the APK streamed from a file the shell can read (`-` is stdin: Android 7 to 14 all take it).
 */
object PmInstall {
    /** What the error says when both ways failed (Errors.say and `friendly()` in js/core.js turn it into words). */
    const val BLOCKED = "install blocked by this TV's firmware"
    private val REFUSED = Regex("disabled from pm|install apk has be disabled", RegexOption.IGNORE_CASE)
    private val ID = Regex("\\[(\\d+)]")

    /** `pm install` printed the firmware's refusal (not an ordinary failure such as a downgrade: a session would meet those too). */
    fun refused(out: String): Boolean = !out.contains("Success") && REFUSED.containsMatchIn(out)

    /**
     * [out] is what `pm install` printed. Anything but the firmware's refusal comes back as it is; the refusal is tried again
     * through a session over [sh] (one shell call, its output back), for the APK at [quoted] (already shell-quoted) of [size] bytes.
     * "Success" when the session worked, otherwise [BLOCKED] with what the session said.
     */
    fun fallback(out: String, sh: (String) -> String, quoted: String, size: Long, between: () -> Unit = {}): String {
        if (!refused(out)) return out
        WireLog.all.add("·", "pm install refused by the firmware: trying a session")
        val s = session(sh, quoted, size, between)
        return if (s.contains("Success")) s else "$BLOCKED: ${s.trim().lines().lastOrNull().orEmpty()}"
    }

    /** One APK of a split bundle: [src] the shell words that print it (`unzip -p bundle entry`), its [size], the [name] pm files it under. */
    class Part(val src: String, val size: Long, val name: String)

    /** A split bundle in one session: create (-S the sum), write each part from its [Part.src], commit; abandoned when a step fails. */
    fun bundle(sh: (String) -> String, parts: List<Part>, between: () -> Unit = {}): String {
        val made = sh("pm install-create -r -S ${parts.sumOf { it.size }} 2>&1")
        val id = ID.find(made)?.groupValues?.get(1) ?: return made.ifBlank { "install-create gave no session" }
        var done = false
        try {
            for (p in parts) {
                between()
                val wrote = sh("${p.src} | pm install-write -S ${p.size} $id ${p.name} - 2>&1")
                if (!wrote.contains("Success")) return wrote.ifBlank { "install-write gave no answer" }
            }
            between()
            val out = sh("pm install-commit $id 2>&1")
            done = true
            return out
        } finally {
            if (!done) try { sh("pm install-abandon $id 2>&1") } catch (ignored: Exception) { }
        }
    }

    /** create, write, commit; the session is abandoned when a step fails, so no half-written one stays on the TV. */
    fun session(sh: (String) -> String, quoted: String, size: Long, between: () -> Unit = {}): String {
        val made = sh("pm install-create -r -S $size 2>&1")
        val id = ID.find(made)?.groupValues?.get(1) ?: return made.ifBlank { "install-create gave no session" }
        var done = false
        try {
            between()
            val wrote = sh("cat $quoted | pm install-write -S $size $id base.apk - 2>&1")
            if (!wrote.contains("Success")) return wrote.ifBlank { "install-write gave no answer" }
            between()
            val out = sh("pm install-commit $id 2>&1")
            done = true
            return out
        } finally {
            if (!done) try { sh("pm install-abandon $id 2>&1") } catch (ignored: Exception) { }
        }
    }
}
