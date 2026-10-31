package dev.tvremote.setup.keys

import android.content.Context
import dev.tvremote.update.SignedList
import java.io.File
import org.json.JSONObject

/**
 * One TV model's odd remote buttons, fixed with no new APK: the site's signed list docs/setup/keys.json (dev/sign/sign_keys.py), else
 * GitHub, else the copy kept in files/, else the one packed in the APK. Same pattern as TV Home's feeds ([dev.tvremote.home.widgets.Sources]).
 * An entry only picks one of [KeyFix]'s own actions for a key on a model; navigation, volume and power keys are never taken ([NEVER]).
 */
object KeyList {
    private val BASES = listOf("https://tvremote.pages.dev/setup/", "https://raw.githubusercontent.com/HoangTran0410/tv-remote/main/docs/setup/")
    private const val NAME = "keys.json"
    private const val DAY = 24 * 3600_000L
    private val ACTS = setOf(KeyFix.INPUT, KeyFix.HOME, KeyFix.SETTINGS, KeyFix.APP)
    /** Home, Back, the D-pad and OK, volume and mute, power, menu, sleep / wake: a bad entry must not take the remote away. */
    val NEVER = setOf(3, 4, 19, 20, 21, 22, 23, 24, 25, 26, 66, 82, 164, 223, 224)

    class All(val rev: Long, val keys: List<KeyFix.DeviceKey>)

    @Volatile var now: All = All(-1, emptyList())

    /** The list as the app uses it: entries with a model or device, an allowed code and an action this build knows. Null when it is not a list. */
    fun parse(json: String): All? = try {
        val o = JSONObject(json)
        if (o.optInt("v") != 1) null
        else {
            val a = o.optJSONArray("keys")
            All(o.optLong("rev"), (0 until (a?.length() ?: 0)).mapNotNull { i ->
                val k = a!!.optJSONObject(i) ?: return@mapNotNull null
                val model = k.optString("model"); val device = k.optString("device"); val code = k.optInt("code"); val act = k.optString("act")
                val pkgs = k.optJSONArray("pkgs")?.let { p -> (0 until p.length()).map { p.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
                if ((model.isEmpty() && device.isEmpty()) || code <= 0 || code in NEVER || act !in ACTS || (act == KeyFix.APP && pkgs.isEmpty())) null
                else KeyFix.DeviceKey(model, device, code, KeyFix.Rule(act, pkgs))
            })
        }
    } catch (e: Exception) { null }

    private fun rev(b: ByteArray) = try { JSONObject(String(b, Charsets.UTF_8)).optLong("rev", 0) } catch (e: Exception) { 0L }

    /** Reads the kept / packed list now; once a day asks the site, and on a newer list calls [changed] (the keys' plan is made again). */
    fun start(c: Context, changed: () -> Unit) {
        val app = c.applicationContext
        val f = File(app.filesDir, NAME)
        var bytes = SignedList.kept(f)
        val packed = SignedList.packed({ app.assets.open(it) }, NAME)
        if (packed != null && rev(packed) > (bytes?.let { rev(it) } ?: -1)) {   // an APK newer than the site carries a newer list
            bytes = packed
            try { SignedList.keep(f, packed, app.assets.open("$NAME.sig").use { String(it.readBytes(), Charsets.UTF_8) }); f.setLastModified(1) } catch (e: Exception) { }
        }
        bytes?.let { parse(String(it, Charsets.UTF_8)) }?.let { now = it }
        if (dev.tvremote.guard.Age.fresh(f.lastModified(), DAY)) return
        Thread({
            try {
                SignedList.fetch(BASES, NAME, 1 shl 16, connectMs = 6000, readMs = 8000) { list, sig, _ ->
                    val p = parse(String(list, Charsets.UTF_8))
                    if (p == null || p.rev < now.rev) null
                    else { SignedList.keep(f, list, sig); val newer = p.rev > now.rev; now = p; if (newer) changed(); true }
                }
            } catch (e: Exception) { }
        }, "keylist").apply { isDaemon = true }.start()
    }
}
