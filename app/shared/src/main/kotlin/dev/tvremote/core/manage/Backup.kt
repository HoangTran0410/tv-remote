package dev.tvremote.core.manage
import dev.tvremote.core.adb.Pkg
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Move to a new TV": the parts of the backup file only this side can read, as pure functions (the page makes the rest of the file
 * from the routes it already uses: js/backup.js). Read here: the model, the apps Optimize TV turned off ([dev.tvremote.core.ontv.Setup.OFF_LIST])
 * and the third-party apps with their names. Nothing here holds a secret: a PIN, a code or a token is never read.
 */
internal object Backup {
    const val MAX_APPS = 500
    private val PKG = Regex(Pkg.PATTERN)

    /** The packages in Undo's list (one per line); a line that is not a package name is dropped, each once, in file order. */
    fun offList(text: String): List<String> = text.lines().map { it.trim() }.filter { PKG.matches(it) }.distinct()

    /**
     * The third-party apps worth listing for a new TV: not this project's own (its apps come with it), sorted by name,
     * each {pkg, label}. [label] gives a package's name.
     */
    fun appsOf(pkgs: Collection<String>, label: (String) -> String): JSONArray {
        val rows = pkgs.filter { PKG.matches(it) && !it.startsWith("dev.tvremote.") }.distinct().take(MAX_APPS).map { it to label(it).ifBlank { it } }
        val out = JSONArray()
        for ((p, l) in rows.sortedWith(compareBy({ it.second.lowercase() }, { it.first }))) out.put(JSONObject().put("pkg", p).put("label", l))
        return out
    }

    /** What a restore asks for ("optimize": a JSON list of package names): valid names only, each once, at most [MAX_APPS]. Not a list: empty. */
    fun wanted(list: JSONArray?): List<String> {
        val out = LinkedHashSet<String>()
        for (i in 0 until (list?.length() ?: 0)) {
            val p = (list!!.opt(i) as? String)?.trim() ?: continue
            if (PKG.matches(p)) out.add(p)
            if (out.size >= MAX_APPS) break
        }
        return out.toList()
    }

    /** What a restore will do with each wanted app: turn off ([todo]), nothing as it is off already, or skip as it is not on this TV. */
    class Plan(val todo: List<String>, val already: List<String>, val missing: List<String>)

    /** [have] is every package on the TV, [off] those already turned off. */
    fun plan(wanted: List<String>, have: Set<String>, off: Set<String>): Plan = Plan(
        todo = wanted.filter { it in have && it !in off }, already = wanted.filter { it in off },
        missing = wanted.filter { it !in have && it !in off })

    fun result(plan: Plan, done: Int, kept: List<String>, failed: List<String>): JSONObject =
        JSONObject().put("done", done).put("already", JSONArray(plan.already)).put("missing", JSONArray(plan.missing))
            .put("kept", JSONArray(kept)).put("failed", JSONArray(failed))
}
