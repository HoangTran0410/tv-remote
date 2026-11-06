package dev.tvremote.core.adb

/**
 * "Which apps have a screen for this intent?", asked of the TV once, for every site that needs it. `cmd package query-activities`
 * is Android 9+ (from memory of AOSP's PackageManagerShellCommand, ~70% sure it is P and not N; Android 5-6 has no `cmd` at all,
 * "cmd: not found"). Below that the answer comes from the package manager's resolver table (`dumpsys package`, filtered on the TV
 * to its filter lines, bounded by [CAP] lines), read once per [Activities] and kept. [query] gives null when neither way worked,
 * so a caller that PROTECTS apps with the answer can refuse instead of reading "nothing" as "nothing to protect".
 */
class Activities(private val sh: (String) -> String) {
    /** One intent filter of the resolver table: the screen it belongs to and what it accepts. */
    class Filter(val comp: String, val actions: Set<String>, val cats: Set<String>, val types: Set<String>)

    private var native: Boolean? = null                              // null: not known yet
    private var table: List<Filter>? = null
    private var tableTried = false

    /** "pkg/.Screen" of every screen taking [action] (with [category], and [mime] as a data type), or null: the TV could not say. */
    fun query(action: String, category: String? = null, mime: String? = null): List<String>? {
        if (native != false) {
            val got = brief(sh("cmd package query-activities --brief ${dev.tvremote.core.ontv.TvUser.USER} -a $action" + (if (category != null) " -c android.intent.category.$category" else "") +
                (if (mime != null) " -t $mime" else "") + " 2>/dev/null"))
            if (got.isNotEmpty()) { native = true; return got }
            if (native == null) native = brief(sh("cmd package query-activities --brief ${dev.tvremote.core.ontv.TvUser.USER} -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null")).isNotEmpty()
            if (native == true) return got                           // the command works: nothing takes this intent
        }
        val t = filters() ?: return null
        return t.filter { action in it.actions && (category == null || "android.intent.category.$category" in it.cats) &&
            (mime == null || it.types.any { ty -> typeMatches(ty, mime) }) }.map { it.comp }.distinct()
    }

    /** The packages of [query], or null. */
    fun packages(action: String, category: String? = null, mime: String? = null): Set<String>? =
        query(action, category, mime)?.map { it.substringBefore('/') }?.toSet()

    private fun filters(): List<Filter>? {
        if (!tableTried) {
            tableTried = true
            table = parseResolvers(sh(RESOLVERS))
        }
        return table
    }

    companion object {
        /** The dump keeps ~300 B per filter after grep; the cap keeps a huge TV's answer bounded (~30 000 lines is far more than any TV has). */
        const val CAP = 30000
        /** Filter lines only, cut on the TV: the whole dump is ~600 KB (emulator), this ~50-150 KB. */
        const val RESOLVERS = "dumpsys package 2>/dev/null | grep -e 'Resolver Table:' -e ' filter ' -e 'Action: ' -e 'Category: ' -e 'Type: ' | head -n $CAP"
        private val COMP = Regex("[\\w.]+/[\\w.$]+")
        private val ENTRY = Regex("^\\s+[0-9a-f]+ ([\\w.]+/[\\w.$]+) filter [0-9a-f]+\\s*$")
        private val QUOTED = Regex("\"([^\"]*)\"")

        /** Lines of `--brief` output that are a screen ("pkg/.Act"); the "priority=..." lines and any error text are not. */
        fun brief(out: String): List<String> = out.split("\n").map { it.trim() }.filter { COMP.matches(it) }.distinct()

        /** A filter's type (a wildcard such as video + slash + star) against the one asked for. */
        fun typeMatches(filter: String, mime: String): Boolean =
            filter == mime || filter == "*/*" || (filter.endsWith("/*") && mime.startsWith(filter.dropLast(1)))

        /**
         * The activity part of `dumpsys package` as seen through grep: "Activity Resolver Table:" opens it, the other tables (service,
         * receiver, provider) close it. The same filter is listed under each of its actions and types: merged by component + filter.
         * Null when there is no activity table in [dump] (the dump failed or is another shape).
         */
        fun parseResolvers(dump: String): List<Filter>? {
            var inActivities = false
            var seen = false
            val acts = LinkedHashMap<String, MutableSet<String>>(); val cats = HashMap<String, MutableSet<String>>(); val types = HashMap<String, MutableSet<String>>()
            var key: String? = null
            for (raw in dump.split("\n")) {
                val line = raw.trimEnd()
                if (line.endsWith("Resolver Table:")) { inActivities = line.trim() == "Activity Resolver Table:"; seen = seen || inActivities; key = null; continue }
                if (!inActivities) continue
                val e = ENTRY.find(line)
                if (e != null) {
                    key = e.groupValues[1] + "#" + line.substringAfterLast(' ')
                    acts.getOrPut(key) { HashSet() }; cats.getOrPut(key) { HashSet() }; types.getOrPut(key) { HashSet() }
                    continue
                }
                val k = key ?: continue
                val v = QUOTED.find(line)?.groupValues?.get(1) ?: continue
                val t = line.trim()
                when {
                    t.startsWith("Action:") -> acts[k]!!.add(v)
                    t.startsWith("Category:") -> cats[k]!!.add(v)
                    t.startsWith("Type:") || t.startsWith("StaticType:") || t.startsWith("DynamicType:") -> types[k]!!.add(v)
                }
            }
            if (!seen) return null
            return acts.map { (k, a) -> Filter(k.substringBefore('#'), a, cats[k]!!, types[k]!!) }
        }
    }
}
