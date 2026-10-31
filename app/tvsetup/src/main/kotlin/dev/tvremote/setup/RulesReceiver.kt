package dev.tvremote.setup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.setup.health.Nightly
import dev.tvremote.setup.health.Support
import dev.tvremote.setup.home.Reoptimize
import dev.tvremote.setup.keys.KeysAddon
import dev.tvremote.setup.rules.CodeWrong
import dev.tvremote.setup.rules.Rules
import org.json.JSONArray
import org.json.JSONObject

/**
 * The timers and limits ([Rules]) for a phone or computer that has ADB on this TV (it could do anything anyway). Only the shell
 * can send this broadcast (see the manifest). Extra "set": one action as base64 JSON; none reads. The answer, also base64 JSON,
 * is the state the pages show (see RulesClient.kt in the shared code). Extra "get" = "power": the end of stats/power.jsonl
 * instead ({"power": lines}), for TVs whose shell cannot read this app's folder (a TCL on Android 11). "get" = "file" (with
 * `--es name stats/x --ei lines n`): the end of any file in this app's folder, gzipped (SetupFiles.kt in the shared code:
 * 30 days of samples is near a megabyte, more than a broadcast's answer can carry). "power" stays for clients before it.
 */
class RulesReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getStringExtra("get") == "reopt") {                  // look now instead of at the next day's turn (tests, a person over ADB)
            resultCode = Activity.RESULT_OK
            resultData = Base64.encodeToString(JSONObject().put("started", Reoptimize.now()).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return
        }
        if (i.getStringExtra("get") == "nightly") {                // the night's maintenance now, ignoring the hour and the screen (tests)
            resultCode = Activity.RESULT_OK
            resultData = Base64.encodeToString(JSONObject().put("started", Nightly.now()).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            return
        }
        if (i.getStringExtra("get") == "file") {
            resultCode = Activity.RESULT_OK
            resultData = try { file(c, i.getStringExtra("name") ?: "", i.getIntExtra("lines", 1000)) } catch (e: Exception) { null }
            return
        }
        val answer = if (i.getStringExtra("get") == "power") try {
            val f = java.io.File(c.getExternalFilesDir(null), "stats/power.jsonl")
            JSONObject().put("power", if (f.exists()) f.readLines().takeLast(1500).joinToString("\n") else "")
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        } else try {
            val set = i.getStringExtra("set")
            val change = if (set.isNullOrEmpty()) JSONObject() else JSONObject(String(Base64.decode(set, Base64.DEFAULT), Charsets.UTF_8))
            if (change.optString("do") == "forget_web") {           // check-out (Checkout.kt): only the shell reaches this receiver (DUMP), as every client call here
                resultCode = Activity.RESULT_OK
                resultData = Base64.encodeToString(JSONObject().put("forgot", RemoteService.rotateWeb(c)).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                return
            }
            if (change.optString("do") == "scenepin") {             // a phone or computer's "Add to TV Home" on a scene (Scenes.pinThroughSetup)
                resultCode = Activity.RESULT_OK
                resultData = Base64.encodeToString(ScenePin.request(c, Scenes.clean(JSONArray().put(change.optJSONObject("scene") ?: JSONObject())).optJSONObject(0) ?: JSONObject().put("error", "no scene")).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                return
            }
            if (change.optString("do") == "scenes") {               // a phone / computer saved its scenes (Scenes.publishThroughSetup): the TV's + Add lists them
                ScenePin.publish(c, Scenes.clean(change.optJSONArray("scenes") ?: JSONArray()))
                resultCode = Activity.RESULT_OK
                resultData = Base64.encodeToString("{\"ok\":true}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                return
            }
            if (change.optString("do") == "key") {                  // a client's key (RulesClient.key): the real press's decision, else it sends the key itself
                resultCode = Activity.RESULT_OK
                resultData = Base64.encodeToString(JSONObject().put("acted", KeysBridge.press(c, change.optInt("code"))).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                return
            }
            if (change.optString("do") == "keys_addon") {           // Screen.java (the key that wakes the screen) needs TV Setup Keys: put it on the TV, answer when done
                val pending = goAsync()
                Thread({
                    val ok = try { RemoteService.adb()?.let { KeysAddon.ensure(c, it) } == true } catch (e: Exception) { false }
                    pending.resultCode = Activity.RESULT_OK
                    pending.resultData = Base64.encodeToString(JSONObject().put("installed", ok).toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    pending.finish()
                }, "keys-addon").apply { isDaemon = true }.start()
                return
            }
            if (change.optString("do") == "telegram") {             // the Telegram card of the phone and computer apps (Manage "telegram"): Telegram answers slowly, so off the main thread
                val pending = goAsync()
                Thread({
                    val out = try { RemoteService.telegramApi(change.optJSONObject("req") ?: JSONObject()) } catch (e: Exception) { JSONObject().put("failed", e.message ?: e.javaClass.simpleName) }
                    pending.resultCode = Activity.RESULT_OK
                    pending.resultData = Base64.encodeToString(out.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    pending.finish()
                }, "telegram-card").apply { isDaemon = true }.start()
                return
            }
            if (change.optString("do") == "support") {              // the phone, the computer (Manage "/api/support/tv"): let support look at this TV
                val pending = goAsync()
                Thread({
                    val out = Support.act(c, change.optString("act"))
                    pending.resultCode = Activity.RESULT_OK
                    pending.resultData = Base64.encodeToString(out.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    pending.finish()
                }, "support-card").apply { isDaemon = true }.start()
                return
            }
            val state = Rules.change(c, change)
            if (Rules.active(c) && !RemoteService.isRunning()) {
                try { RemoteService.start(c, true) } catch (e: Exception) { }   // may be refused from the background: RulesClient starts it through the shell then
            }
            if (change?.optString("do") == "unlock" && change.optInt("minutes") > 0) RemoteService.unlocked("remote", change.optString("pkg"))
            state.put("running", RemoteService.isRunning())
        } catch (e: CodeWrong) {                                   // the Authenticator's code was wrong: with the clock's facts, so the page can say why
            JSONObject().put("error", "code_wrong").put("clock", e.clock)
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName)
        }
        resultCode = Activity.RESULT_OK
        resultData = Base64.encodeToString(answer.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** The last [lines] lines of [name] under this app's external files folder, gzipped and in base64; "" when there is none. */
    private fun file(c: Context, name: String, lines: Int): String {
        val root = c.getExternalFilesDir(null) ?: return ""
        val f = java.io.File(root, name).canonicalFile
        if (!f.path.startsWith(root.canonicalPath + "/")) return ""  // its own folder only: no "..", no links out
        val text = if (f.isFile) f.readLines().takeLast(lines.coerceIn(1, 30000)).joinToString("\n") else ""
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bytes).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }
}
