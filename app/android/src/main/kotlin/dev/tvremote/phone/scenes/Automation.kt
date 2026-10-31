package dev.tvremote.phone.scenes

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.media.askText
import dev.tvremote.phone.media.warmAppNames
import dev.tvremote.phone.picker.message
import dev.tvremote.phone.picker.name
import dev.tvremote.phone.picker.offerSetup
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.remote.kind
import dev.tvremote.phone.remote.remoteMenu
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.menu

import dev.tvremote.core.T
import android.widget.Toast
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.ontv.RemoteLabels
import dev.tvremote.core.ontv.RulesClient
import dev.tvremote.core.ontv.SetupNeed
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.scenes.Voice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The remote's 😴 and 🎬 buttons and what the 🎤 hears: TV Setup's sleep timer (it runs on the TV, see Rules.kt there), this
// phone's scenes (SceneStore), and sentences carried out here before the TV's Assistant is asked (Voice.kt). Bedtime, app
// limits and editing scenes are on the Automation tab of the Manage screen.

private fun MainActivity.toast(text: String) = Toast.makeText(this, dev.tvremote.tvui.Marks.plain(text), Toast.LENGTH_SHORT).show()   // a toast has no icons: no marks

/** The TV's clock to this phone's: "23:10" for the moment the timer ends. */
private fun hm(tvTime: Long, tvNow: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(tvTime - (tvNow - System.currentTimeMillis())))

/** Asks TV Setup on a worker thread, answers on the UI thread. */
private fun MainActivity.rules(change: JSONObject?, then: (JSONObject) -> Unit) {
    val tv = tv
    if (tv == null || !tv.ready()) return
    Thread {
        val r = try { RulesClient.call({ tv.shell(it) }, change) } catch (e: Exception) { JSONObject().put("error", e.message ?: "") }
        ui.post { if (!isFinishing) then(r) }
    }.start()
}

private fun MainActivity.needsSetup(r: JSONObject): Boolean {
    if (!r.has("setup") && !r.has("error")) return false
    if (r.optString("setup") == "busy") {                             // new enough, starting again after an update: not a question
        toast(T("automation.tv_setup_is_starting"))
        return true
    }
    if (r.has("setup") && offerSetup(SetupNeed.Missing("rules", r.optString("have")))) return true
    Popup.Builder(this).setTitle(T("automation.tv_setup_is_needed"))
        .setMessage(if (r.has("error")) r.optString("error") else T("automation.the_sleep_timer_bedtime"))
        .setPositiveButton(T("automation.open_automation")) { _, _ -> managing.open(host, "auto") }
        .setNegativeButton(T("common.close"), null).show()
    return true
}

internal fun MainActivity.setTimer(minutes: Int) {
    rules(JSONObject().put("do", "sleep").put("minutes", minutes)) { r ->
        if (!needsSetup(r)) toast(if (minutes > 0) T("automation.the_tv_turns_off", "time" to hm(r.optLong("sleep_at"), r.optLong("now"))) else T("auto.sleep_cancelled"))
    }
}

/** "40" is minutes from now, "23:30" / "23h30" / "23h" a time of day (the next one); 0 = not understood. Same as sleepMinutes in auto.js. */
internal fun sleepMinutes(v: String, now: java.util.Calendar = java.util.Calendar.getInstance()): Int {
    val s = v.trim().lowercase()
    if (s.isNotEmpty() && s.all { it.isDigit() }) return s.toIntOrNull()?.takeIf { it in 1..1440 } ?: 0
    val m = Regex("""^(\d{1,2})\s*[:hg.]\s*(\d{2})?$""").find(s) ?: return 0
    val h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
    if (h > 23 || min > 59) return 0
    val at = (now.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    if (!at.after(now)) at.add(java.util.Calendar.DATE, 1)
    return maxOf(1, Math.round((at.timeInMillis - now.timeInMillis) / 60000.0).toInt())
}

internal fun MainActivity.timerPlus() {
    rules(JSONObject().put("do", "sleep_add").put("minutes", 15)) { x -> toast(T("automation.the_tv_turns_off", "time" to hm(x.optLong("sleep_at"), x.optLong("now")))) }
}

internal fun MainActivity.timerCustom() {
    askText(T("remote.turn_off_when"), T("automation.e_g_40_minutes"), T("remote.set_timer")) {
        val m = sleepMinutes(it)
        if (m > 0) setTimer(m) else toast(T("auto.sleep_bad"))
    }
}

/** 😴: a few lengths to pick from, and where the timer stands (the other entries: remote.json). */
internal fun MainActivity.timerMenu() {
    if (kind() != Remote.Kind.ANDROID) { menu(T("remote.tv_screen"), -1, remoteMenu("timer")); return }   // LG: the screen off only
    if (needsAdb()) return
    rules(null) { r ->
        if (needsSetup(r)) return@rules
        val at = r.optLong("sleep_at")
        val lengths = { ms: IntArray ->
            ms.map { m ->
                Triple(if (m < 60) T("automation.off_in_min", "n" to m) else if (m % 60 > 0) T("automation.off_in_h_30", "n" to m / 60) else T("automation.off_in_h", "n" to m / 60), "") { setTimer(m) }
            }
        }
        val items = remoteMenu("timer", if (at > 0) setOf("timerOn") else emptySet(), mapOf("lengths" to { lengths(intArrayOf(30, 60, 120)) }, "lengthsMore" to { lengths(intArrayOf(15, 45, 90)) }))
        menu(if (at > 0) T("automation.the_tv_turns_off_2", "time" to hm(at, r.optLong("now"))) else T("remote.sleep_timer"), if (at > 0) 0 else -1, items)
    }
}

/** 🎬: this phone's scenes, and the way to make more (remote.json). */
internal fun MainActivity.scenesMenu() {
    val scenes = { SceneStore.list(this).map { sc -> Triple((sc.optString("icon") + "  " + sc.optString("name")).trim(), "") { runScene(sc) } } }
    menu(T("remote.scenes"), -1, remoteMenu("scenes", emptySet(), mapOf("scenes" to scenes)))
}

internal fun MainActivity.runScene(sc: JSONObject) {
    val steps = sc.optJSONArray("steps") ?: JSONArray()
    val tv = tv
    val b = basic
    val target = if (tv != null && tv.ready()) Scenes.Shell({ tv.shell(it) }) else if (b is dev.tvremote.core.remote.Atv) Headless.keysOnly(b) else if (b != null) dev.tvremote.core.remote.RemoteSteps(b) { } else return
    toast(T("auto.running", "name" to sc.optString("name")))
    Thread {
        val failed = Scenes.run(steps, target)
        ui.post {
            if (isFinishing || (failed != null && offerSetup(java.io.IOException(failed)))) return@post   // e.g. its timer needs a newer TV Setup
            toast(if (failed == null) T("auto.done", "name" to sc.optString("name")) else T("automation.some_steps_did_not", "steps" to failed))
        }
    }.start()
}

// ---- what the 🎤 hears

/** Carries out a sentence here when it can (Voice.kt); otherwise the TV's search / Assistant gets it, as before. */
internal fun MainActivity.voice(sentence: String) {
    val scenes = SceneStore.list(this).associate { it.optString("id") to it.optString("name") }
    Voice.parse(sentence, appNames, scenes)?.let { doVoice(it); return }
    val tv = tv
    val ip = host
    if (appNamesFor != ip && ip != null && tv != null && tv.ready() && Voice.wantsApp(sentence)) {
        warmAppNames { Voice.parse(sentence, appNames, scenes)?.let { doVoice(it) } ?: ask(sentence) }   // "open <app>": the names of this TV's apps, asked once per TV
        return
    }
    ask(sentence)
}

private fun MainActivity.doVoice(c: Voice.Command) {
    when (c) {
        is Voice.Command.Scene -> SceneStore.find(this, c.id)?.let { runScene(it) }
        is Voice.Command.Timer -> if (!needsAdb()) setTimer(c.minutes)
        is Voice.Command.App -> {
            toast(T("automation.opening", "name" to c.name))
            val tv = tv
            if (tv != null && tv.ready()) Thread { try { Scenes.launch({ tv.shell(it) }, c.pkg) } catch (e: Exception) { ui.post { toast(e.message ?: "") } } }.start()
            else basic?.launch(c.pkg)
        }
        is Voice.Command.Close -> {
            val tv = tv
            if (tv == null || !tv.ready()) { needsAdb(); return }              // am force-stop is ADB's; the keys-only link cannot
            if (dev.tvremote.core.ontv.Setup.mustKeep(c.pkg)) return           // the TV needs it (the list's "never_close" says so too)
            toast(T("auto.closed_app", "name" to c.name))
            Thread { try { tv.shell("am force-stop " + dev.tvremote.core.adb.Adb.q(c.pkg)) } catch (e: Exception) { ui.post { toast(e.message ?: "") } } }.start()
        }
        is Voice.Command.Channel -> {
            toast(T("automation.channel", "name" to c.name))
            val link = Voice.channelLink(c.name)
            val tv = tv
            // the TV app is named so the link cannot land in a browser; without ADB the remote protocol sends the link alone
            if (tv != null && tv.ready()) Thread {
                try { if (!dev.tvremote.core.ontv.Channels.open({ tv.shell(it) }, c.name)) ui.post { toast(T("core.fr_channels_missing")) } }   // not installed
                catch (e: Exception) { ui.post { toast(e.message ?: "") } }
            }.start()
            else basic?.launch(link)
        }
        is Voice.Command.Key -> {
            val code = Scenes.KEYS[c.key] ?: return
            for (i in 0 until c.times) ui.postDelayed({ press(code, false) }, i * 150L)
            toast(T("auto.pressed", "key" to c.key.lowercase().replace('_', ' ')) + if (c.times > 1) " × ${c.times}" else "")
        }
    }
}
