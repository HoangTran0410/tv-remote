package dev.tvremote.core.manage
import dev.tvremote.core.web.Cancel
import java.util.Collections
import org.json.JSONObject

/** The installs, downloads and updates the page polls (`/api/job?id=`), their Stops, and what is under way for the phone's notification. */
class Jobs {
    private val all: MutableMap<String, JSONObject> = Collections.synchronizedMap(LinkedHashMap<String, JSONObject>())
    private val nextJob = java.util.concurrent.atomic.AtomicInteger(1)

    /** The Stop of every transfer or install under way, by the id the page knows it by (a job's id, or the upload's `uid`). */
    val stops = java.util.concurrent.ConcurrentHashMap<String, Cancel>()
    /** The Stop of the work the current thread is doing (set by [job], by an upload request, by an install or a cast). */
    val current = ThreadLocal<Cancel?>()

    val working = java.util.concurrent.ConcurrentHashMap<Any, String>()
    /** Told when work starts or ends (the phone keeps itself running meanwhile, and asks before leaving the TV). */
    @Volatile var onWork: ((String) -> Unit)? = null

    /** What is under way, as upload / install / update / tailscale / work; "" when nothing. */
    fun working(): String = working.values.firstOrNull() ?: ""

    /** How far the newest running job is, 0..100, or -1 when none tells (the phone's notification bar). */
    fun progress(): Int = synchronized(all) { all.values.lastOrNull { it.optString("status") == "running" }?.optInt("pct", -1) ?: -1 }

    inline fun <T> at(what: String, f: () -> T): T {
        val k = Any()
        working[k] = what
        onWork?.invoke(working())
        try { return f() } finally { working.remove(k); onWork?.invoke(working()) }
    }

    operator fun get(id: String): JSONObject? = all[id]
    fun nextId(): Int = nextJob.getAndIncrement()

    /** [kind] + [name]: the same title as structured parts for the page to word (files.job_<kind>), the title staying for old pages. */
    fun job(title: String, kind: String?, name: String?, work: (JSONObject) -> String): JSONObject {
        val j = JSONObject().put("id", nextId().toString()).put("title", title).put("status", "running").put("pct", 0).put("msg", "")
        if (kind != null) j.put("kind", kind).put("name", name ?: "")
        all[j.getString("id")] = j
        while (all.size > 20) all.remove(all.keys.iterator().next())
        val what = title.substringBefore(' ').takeIf { it in setOf("install", "update", "tailscale") } ?: "work"
        val cancel = Cancel()
        stops[j.getString("id")] = cancel
        Thread({
            current.set(cancel)
            try {
                val msg = at(what) { work(j) }
                j.put("status", "done").put("pct", 100).put("msg", msg)
            } catch (e: Exception) {
                // the person's Stop is not an error, whatever the stopped work threw on its way out
                try { j.put("status", if (cancel.stopped) "cancelled" else "error").put("msg", if (cancel.stopped) "stopped" else e.message ?: e.javaClass.simpleName) } catch (ignored: Exception) { }
            } finally {
                current.remove()
                stops.remove(j.getString("id"))
            }
        }, "job").apply { isDaemon = true }.start()
        return snapshot(j)
    }

    companion object {
        /**
         * A copy of a job while its thread is still putting into it. A key added meanwhile breaks the walk through it: Android's
         * org.json throws, the JVM's returns null from toString(), and `JSONObject(null)` was a 500 to a job that ran fine.
         */
        fun snapshot(j: JSONObject): JSONObject {
            repeat(20) {
                try { j.toString()?.let { return JSONObject(it) } } catch (e: ConcurrentModificationException) { }
                Thread.sleep(5)
            }
            return JSONObject().put("id", j.optString("id")).put("status", "running").put("pct", 0).put("msg", "")
        }
    }
}
