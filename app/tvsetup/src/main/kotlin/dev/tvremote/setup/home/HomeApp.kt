package dev.tvremote.setup.home

import android.content.Context
import android.os.PowerManager
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.adb.Pkg
import dev.tvremote.setup.HomeInfo
import dev.tvremote.setup.ManageActivity
import dev.tvremote.setup.RemoteService
import dev.tvremote.setup.health.Stats
import dev.tvremote.setup.rules.Clock
import dev.tvremote.setup.rules.Lockout
import dev.tvremote.setup.rules.Rules
import org.json.JSONArray
import org.json.JSONObject

/**
 * What TV Home's long-press menu of one app may ask of this app (HomeInfo "app"; same signing-key trust as its other calls):
 * the app's lock and daily limit (the same [Rules] storage the web Apps / Automation tabs use, cleaned by [Rules.apply]), and
 * two fixed maintenance acts. `arg` is {pkg, op, ...}; the answer {result, ...state}:
 *   op ""      read: {pin: a code is set, prot: an app the menu may not lock or stop, locked, limit (min a day, 0 none), used (s today), adb: the shell can be reached, kids}
 *   op lock    {on, code}: changing a lock asks the parent's code (PIN, or the Authenticator's), like every other way in
 *   op limit   {minutes, code}: 0 removes it
 *   op verify  {code}: only asks whether the parent's code is right (TV Home asks it once on entering Lock & limits); wrong carries `left` tries
 *   op pin     {pin}: makes the FIRST code (4-8 digits) from the TV; once a code exists it is "bad" (changing it takes a phone or computer, no old code needed)
 *   op manage  opens TV Setup's Manage screen on the Health tab on the TV (TV Home's "See more" on the use chart)
 *   op stop    force stop over this app's ADB link; no code, it only closes an app
 *   op cache / cache_state / cache_cancel / week: clear cache runs on a thread of its own (below), the answer is "running" and TV Home asks
 *              cache_state until it is done; week = this app's use in seconds on each of the last 7 days (today first)
 * result: ok, code (a code is needed), wrong (+ `left` tries), wait (+ `wait` seconds, the same lockout as the PIN screen), nopin, bad, noadb, failed,
 *         running / partial (cache: the outside cache went, the inner one cannot be reached from the shell on this Android; `kb` freed).
 * A shell line is built here from [Pkg]-checked names only: a caller's own words never reach the shell.
 */
object HomeApp {
    private const val SELF = "dev.tvremote.setup"
    private const val HOME = HomeInfo.HOME

    /** Apps TV Home may not lock, limit or stop: the ones that run the lock and the home screen itself. */
    fun protectedPkg(pkg: String) = pkg == SELF || pkg == HOME || pkg == "dev.tvremote.setup.keys"

    /** The shell line of a maintenance act on [pkg], null for any other word or a bad package name. */
    fun command(op: String, pkg: String): String? {
        if (!Pkg.valid(pkg)) return null
        return when (op) {
            "stop" -> if (protectedPkg(pkg)) null else "am force-stop $pkg"
            else -> null
        }
    }

    /**
     * The cache lines for [pkg], null for a bad name. The inner cache: `cmd package clear --cache-only` (not the `pm` script, which
     * leaves its child running when killed), with a hard cap. Measured on Android 14 (emulator and an FPT box): the shell has no
     * INTERNAL_DELETE_CACHE_FILES, PackageManager logs "silently ignoring" and never answers, so the command waits for ever
     * ("rc=137" here = cut by the cap = this Android does not allow it). The outside cache (/sdcard/Android/data/<pkg>/cache) the shell can
     * always delete. Output: `kb=<outside cache before>` then, after the inner try, `rc=<code>`.
     */
    fun cacheLine(pkg: String, inner: Boolean): String? {
        if (!Pkg.valid(pkg)) return null
        val out = "/sdcard/Android/data/$pkg/cache"
        val kb = "echo kb=$(du -sk $out 2>/dev/null | cut -f1); rm -rf $out/* $out/.[!.]* 2>/dev/null"
        return if (inner) "$kb; timeout -s KILL $INNER_CAP_S cmd package clear --cache-only $pkg >/dev/null 2>&1 </dev/null; echo rc=\$?" else kb
    }
    private const val INNER_CAP_S = 6

    /** The cache job's result from the shell [out]: "ok" (inner cache gone), "partial" (only the outside one), "failed"; with the KB freed outside. */
    fun cacheResult(out: String): Pair<String, Long> {
        val kb = Regex("kb=(\\d*)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val rc = Regex("rc=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val innerOk = rc == 0 && !out.contains("Exception") && !out.contains("Error")
        return (if (innerOk) "ok" else if (kb > 0 || rc != null) "partial" else "failed") to kb
    }

    /** The seconds of [pkg] on each of the last 7 days, today first: [today] from the live count, [all] the kept usage.json. */
    fun week(today: JSONObject, all: JSONObject, pkg: String, now: Long): JSONArray {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val out = JSONArray()
        for (d in 0..6) out.put((if (d == 0) today else all.optJSONObject(fmt.format(java.util.Date(now - d * 86_400_000L))) ?: JSONObject()).optLong(pkg))
        return out
    }

    private class Job(var state: String = "running", var kb: Long = 0L, var cancelled: Boolean = false)
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    @Volatile private var innerBlocked = false                     // once the inner cache has hit the cap on this TV, later jobs skip the wait

    /** The Rules action behind a lock / limit / pin request, null when it is none of those or the package is not allowed. */
    fun rulesAction(d: JSONObject): JSONObject? {
        val pkg = d.optString("pkg")
        return when (d.optString("op")) {
            "lock" -> if (Pkg.valid(pkg) && !protectedPkg(pkg)) JSONObject().put("do", "lock").put("pkg", pkg).put("on", d.optBoolean("on")) else null
            "limit" -> if (Pkg.valid(pkg) && !protectedPkg(pkg)) JSONObject().put("do", "limit").put("pkg", pkg).put("minutes", d.optInt("minutes").coerceIn(0, 24 * 60)) else null
            "pin" -> JSONObject().put("do", "pin").put("pin", d.optString("pin"))
            else -> null
        }
    }

    /** The app's rules as TV Home draws them. [usedSec] is today's use of the app. */
    fun state(o: JSONObject, pkg: String, usedSec: Long, adb: Boolean): JSONObject =
        JSONObject().put("pin", Rules.hasCode(o)).put("locked", pkg in Rules.locked(o))
            .put("limit", o.optJSONObject("limits")?.optInt(pkg) ?: 0).put("used", usedSec)
            .put("adb", adb).put("kids", Rules.kidsMode(o)).put("prot", protectedPkg(pkg))
            .put("len", o.optJSONObject("lock")?.optInt("len", 4) ?: 4).put("totp", Rules.methods(o).optBoolean("totp"))   // how long a PIN is, and whether a 6-digit Authenticator code also opens it: TV Home's keypad submits at those lengths

    fun handle(c: Context, arg: String?): JSONObject {
        val d = try { JSONObject(arg ?: "{}") } catch (e: Exception) { return JSONObject().put("result", "bad") }
        val pkg = d.optString("pkg")
        if (!Pkg.valid(pkg)) return JSONObject().put("result", "bad")
        val op = d.optString("op")
        val now = System.currentTimeMillis()
        fun answer(result: String, extra: JSONObject? = null): JSONObject {
            val o = Rules.load(c)
            val used = try {
                val on = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                Stats(c).today(now, on).first.optLong(pkg)
            } catch (e: Exception) { 0L }
            val s = state(o, pkg, used, adbReachable(c)).put("result", result)
            extra?.keys()?.forEach { s.put(it, extra.get(it)) }
            return s
        }
        if (op.isEmpty()) return answer("ok")
        if (op == "stop") {
            val line = command(op, pkg) ?: return answer("bad")
            return answer(shell(c, line, 3000L)?.let { if (failedText(it)) "failed" else "ok" } ?: "noadb")
        }
        if (op == "cache") return answer(startCache(c, pkg))
        if (op == "cache_state" || op == "cache_cancel") {
            val j = jobs[pkg] ?: return answer("idle")
            if (op == "cache_cancel") { j.cancelled = true; jobs.remove(pkg); return answer("idle") }
            if (j.state != "running") jobs.remove(pkg)
            return answer(j.state, JSONObject().put("kb", j.kb))
        }
        if (op == "week") {
            val all = try { JSONObject(java.io.File(c.getExternalFilesDir(null), "stats/usage.json").readText()) } catch (e: Exception) { JSONObject() }
            val on = try { (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (e: Exception) { true }
            val today = try { Stats(c).today(now, on).first } catch (e: Exception) { JSONObject() }
            return answer("ok", JSONObject().put("days", week(today, all, pkg, now)))
        }
        if (op == "manage") {                                           // TV Setup's own Manage screen on the Health tab (History is a button there); TV Home cannot start it
            return answer(try {
                c.startActivity(android.content.Intent(c, ManageActivity::class.java).putExtra(ManageActivity.PASS, RemoteService.selfPass)
                    .putExtra(ManageActivity.TAB, "health").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); "ok"
            } catch (e: Exception) { "failed" })
        }
        if (op == "verify") {
            if (!Rules.hasCode(Rules.load(c))) return answer("nopin")
            val gate = check(c, Rules.load(c), d.optString("code"), now, pkg)
            return if (gate == null) answer("ok") else answer(gate.first, JSONObject().put("wait", gate.second).put("left", maxOf(0, Lockout.TRIES - Lockout.streak(c))))
        }
        val action = rulesAction(d) ?: return answer("bad")
        val o = Rules.load(c)
        if (op == "pin") {                                               // only while none is set: after that the code changes from a phone or computer
            if (Rules.hasCode(o)) return answer("bad")
            return try { Rules.change(c, action); answer("ok") } catch (e: IllegalArgumentException) { answer("bad") }
        }
        if (!Rules.hasCode(o)) return answer("nopin")
        val gate = check(c, o, d.optString("code"), now, pkg)
        if (gate != null) return answer(gate.first, JSONObject().put("wait", gate.second).put("left", maxOf(0, Lockout.TRIES - Lockout.streak(c))))
        return try { Rules.change(c, action); answer("ok") } catch (e: IllegalArgumentException) { answer("bad") }
    }

    /** null when [code] opens the rules; else (result, seconds to wait). The same lockout as the PIN screen: 5 wrong in a row = a wait. */
    private fun check(c: Context, o: JSONObject, code: String, now: Long, pkg: String): Pair<String, Long>? {
        val until = Lockout.until(c, now)
        if (until > now) return "wait" to (until - now + 999) / 1000
        if (code.isEmpty()) return "code" to 0L
        if (Rules.pinOk(o, code) || Rules.totpOk(o, code, now, Clock.driftMs(c))) { Lockout.right(c); return null }
        val wait = Lockout.wrong(c, now)
        if (Lockout.streak(c) == 3) RemoteService.wrongCodes(pkg)
        return if (wait > 0) "wait" to (wait + 999) / 1000 else "wrong" to 0L
    }

    /** ADB debugging is on and TV Setup can reach it (the work is the same as the power button's). */
    private fun adbReachable(c: Context): Boolean = try {
        android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
    } catch (e: Exception) { false }

    private fun failedText(out: String) = out.contains("Exception") || out.contains("Error:") || out.contains("not found")

    /** The cache job on a thread of its own: it can take [INNER_CAP_S] s and a binder thread must not wait. "running" at once, or what it already is. */
    private fun startCache(c: Context, pkg: String): String {
        if (!Pkg.valid(pkg)) return "bad"
        if (jobs[pkg]?.state == "running") return "running"
        val job = Job().also { jobs[pkg] = it }
        Thread({
            val outside = cacheLine(pkg, false)!!
            val first = shell(c, outside, 4000L)
            if (first == null) { job.state = "noadb"; return@Thread }
            val inner = if (innerBlocked) null else shell(c, cacheLine(pkg, true)!!, (INNER_CAP_S + 4) * 1000L)
            if (job.cancelled) return@Thread
            val kb = Regex("kb=(\\d*)").find(first)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val r = if (inner == null) "partial" to kb else cacheResult(inner).let { (st, _) -> st to kb }
            if (r.first == "partial" && inner != null) innerBlocked = true
            job.kb = r.second; job.state = r.first
        }, "home-cache").apply { isDaemon = true }.start()
        return "running"
    }

    /** The shell's text, or null when ADB cannot be reached ("noadb"). Blocks up to [ms]: never on the main thread (a binder thread is fine). */
    private fun shell(c: Context, line: String, ms: Long): String? {
        val live = try { RemoteService.adb() } catch (e: Exception) { null }
        val own = if (live == null) try { AdbLink(c.filesDir, "127.0.0.1").get() } catch (e: Exception) { return null } else null
        val adb = live ?: own!!
        return try { adb.shell(line, ms) }
        catch (e: Exception) { "Exception" }
        finally { try { own?.close() } catch (e: Exception) { } }
    }
}
