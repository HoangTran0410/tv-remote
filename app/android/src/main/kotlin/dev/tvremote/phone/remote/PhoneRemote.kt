package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.ir.irAvailable
import dev.tvremote.phone.ir.irOpen
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.manage.web
import dev.tvremote.phone.media.askLink
import dev.tvremote.phone.media.askText
import dev.tvremote.phone.media.clipDialog
import dev.tvremote.phone.media.openInstalled
import dev.tvremote.phone.media.openLink
import dev.tvremote.phone.media.pickFile
import dev.tvremote.phone.media.pickMedia
import dev.tvremote.phone.media.quickOpen
import dev.tvremote.phone.media.say
import dev.tvremote.phone.media.screenOff
import dev.tvremote.phone.picker.name
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.picture.airCan
import dev.tvremote.phone.picture.airMode
import dev.tvremote.phone.picture.airPick
import dev.tvremote.phone.picture.drawLit
import dev.tvremote.phone.picture.startDrawing
import dev.tvremote.phone.picture.stopDrawing
import dev.tvremote.phone.scenes.scenesMenu
import dev.tvremote.phone.scenes.setTimer
import dev.tvremote.phone.scenes.timerCustom
import dev.tvremote.phone.scenes.timerMenu
import dev.tvremote.phone.scenes.timerPlus
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.HELD
import dev.tvremote.phone.ui.IconButton
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.cell
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.menu
import dev.tvremote.phone.ui.say
import dev.tvremote.phone.ui.setOn

import dev.tvremote.core.T
import android.content.Intent
import android.widget.LinearLayout
import dev.tvremote.core.ontv.Home
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.scenes.RemoteSpec
import dev.tvremote.core.scenes.Scenes
import dev.tvremote.core.picture.Tv

// The remote's keys and menu entries are in app/shared/web/remote.json, which the web remote draws from too (js/remote_spec.js):
// this file draws them as native views and says what each action name in the file does on the phone. Add, reorder or rename an
// entry in the file; a new action name needs a handler in [phoneActions] here and in REMOTE_DO there (tests/remote.test.js and
// RemoteSpecTest check both).

private var loaded: RemoteSpec.Spec? = null

/** remote.json for this app (it is in the app's assets next to the web page's files), read once. */
internal fun MainActivity.remoteSpec(): RemoteSpec.Spec =
    loaded ?: RemoteSpec.parse(assets.open("remote.json").use { String(it.readBytes(), Charsets.UTF_8) }, "phone").also { loaded = it }

private fun act(f: MainActivity.(String?) -> Unit) = f
private fun hook(f: MainActivity.(IconButton) -> Unit) = f

/** What each "do" in remote.json does here; `it` is the entry's "arg". */
/** The groups an entry opens ("do": "menu", "arg": its name): their titles (REMOTE_GROUPS on the page). */
private val remoteGroups = mapOf("get" to "rkey.open_get", "send" to "rkey.open_send", "timerMore" to "rkey.timer_more", "tools" to "rkey.tools", "tvkeys" to "rkey.tools_keys")

internal val phoneActions: Map<String, MainActivity.(String?) -> Unit> = mapOf(
    "shot" to act { shot() },
    "open" to act { quickOpen() },
    "apps" to act { if (kind() != dev.tvremote.core.remote.Remote.Kind.ANDROID) openInstalled() else if (!needsAdb()) managing.open(host, "apps") },   // an Android TV's apps: the Apps tab, tiles with a search
    "menu" to act { m -> menu(T(remoteGroups[m] ?: return@act), -1, remoteMenu(m!!, setOfNotNull("ir".takeIf { irAvailable() }, "native".takeIf { kind() != Remote.Kind.ANDROID }))) },   // "ir": this phone has an IR LED; "native": LG, Samsung, Roku, VIDAA
    "key" to act { k -> val code = Scenes.KEYS[k] ?: return@act; if (code in Tv.OPENERS) opens(code) else press(code) },   // a key from a menu (Tools)
    "timer" to act { timerMenu() },
    "scenes" to act { scenesMenu() },
    "privacy" to act { trackingMenu() },
    "notify" to act { if (!needsAdb()) managing.open(host, then = "fromRemote(notifyDialog)") },   // the page's dialogs (js/notify.js, dim.js, setup.js): one copy
    "draw" to act { if (drawing) stopDrawing() else startDrawing() },
    "search" to act { askText(T("remote.search_play_store"), T("phone_remote.e_g_vlc"), T("remote.search")) { openLink("market://search?c=apps&q=" + java.net.URLEncoder.encode(it, "UTF-8")) } },   // the TV's Play Store takes searches only this way
    "package" to act { askText(T("remote.package_id"), "org.videolan.vlc", T("apps.open")) { storePage(it) } },
    "link" to act { askText(T("remote.link"), "https://youtu.be/...", T("apps.open")) {
        val l = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(it)) it else "https://$it"
        askLink(Home.youtubeUrl(l) ?: l) { openLink(l) }                    // the page's pin box, as when a link is shared to us
    } },
    "media" to act { pickMedia() },
    "file" to act { pickFile(arrayOf("audio/*", "video/*", "image/*")) },
    "cast" to act {
        try { startActivity(Intent("android.settings.CAST_SETTINGS")) }
        catch (e: Exception) { say(T("phone_remote.this_phone_has_no"), true) }
    },
    "store" to act { storePage(it ?: "") },
    "timerPlus" to act { timerPlus() },
    "timerCancel" to act { setTimer(0) },
    "timerCustom" to act { timerCustom() },
    "automation" to act { c -> if (!needsAdb()) managing.open(host, "auto", c?.filter { it.isLetter() }?.let { "goTo('auto','$it')" }) },
    "clip" to act { clipDialog() },
    "screenOff" to act { screenOff() },
    "dim" to act { if (!needsAdb()) managing.open(host, then = "fromRemote(dimOpen)") },
    "share" to act { if (!needsAdb()) managing.open(host, then = "fromRemote(shareDialog)") },
    "inputs" to act { inputsMenu() },
    "air" to act { if (airMode() != "mouse" && !airCan()) say(T("air.needs_adb"), true); airPick("mouse") },
    "gamespad" to act { if (!needsAdb()) gamesPointerOpen() },
    "ir" to act { irOpen(entry ?: host) },
    "manage" to act { if (!needsAdb()) managing.open(host) },          // Tiện ích → Quản lý TV: the top-right icon, with its name
    "homeCustom" to act { if (!needsAdb()) managing.open(host, "tv", "fromRemote(homeOpen)") },   // the page's TV Home dialog: one place for it
    "rescue" to act { rescueTv() },
)

/** Things an action's button needs to know about itself once drawn (the ✏️ is lit while drawing). */
internal val phoneButtonHooks: Map<String, MainActivity.(IconButton) -> Unit> = mapOf(
    "draw" to hook { drawBtn = it; drawLit() },
    "shot" to hook { shotBtn = it },
    "air" to hook { airBtn = it; it.setOn(airMode() == "mouse", 16) },
)

internal fun MainActivity.storePage(id: String) = openLink("https://play.google.com/store/apps/details?id=$id")

/** What the connected TV can do: an LG or Samsung TV lacks ADB, the Play Store and some keys (Remote.Kind). */
internal fun MainActivity.kind(): String = basic?.kind ?: Remote.Kind.ANDROID
internal fun MainActivity.caps(): Set<String> = Remote.Kind.caps(kind())

/** The buttons drawn from remote.json, to grey those this TV cannot do ([greyRows], from layoutRemote). */
private val drawn = ArrayList<Pair<IconButton, RemoteSpec.Entry>>()

/** The tag of a button this TV cannot do: greyed, not disabled, so a press can tell what it would need. */
internal const val KIND_OFF = "kind-off"

internal fun MainActivity.kindOff() =
    android.widget.Toast.makeText(this, T("core.only_android"), android.widget.Toast.LENGTH_SHORT).show()

internal fun MainActivity.greyRows() {
    val caps = caps()
    val keys = basic?.takeIf { kind() != Remote.Kind.ANDROID }?.keys
    for ((b, e0) in drawn) {
        val e = e0.forKind(kind())                                    // remote.json "kinds": a VIDAA's Settings key reads Menu
        if (e0.kinds.isNotEmpty()) { b.setIcon("@" + e.icon, b.currentTextColor); b.contentDescription = e.words(); b.caption = e.words() }
        val ok = e.can(caps) && (keys == null || e.key == null || Scenes.KEYS[e.key] in keys)
        b.tag = if (ok) null else KIND_OFF                            // still pressable: a press says why (kindOff)
        b.alpha = if (ok) 1f else 0.3f
    }
}

/** A row of the remote's keys from remote.json: one icon button each, the key held / tapped like the hand-made ones, or the action run. */
internal fun MainActivity.remoteRow(name: String, index: Int, height: Int, minKeys: Int = 4): LinearLayout {
    val row = LinearLayout(this)
    row.weightSum = maxOf(minKeys.toFloat(), remoteSpec().rows[name]!![index].size.toFloat())   // a short row keeps the others' key width
    remoteSpec().rows[name]!![index].forEachIndexed { i, e ->
        val b = icon("@" + e.icon, e.words(), BTN)
        val action = e.action
        if (action != null) {
            val run = phoneActions[action] ?: error("remote.json: no phone handler for \"$action\"")
            b.setOnClickListener { if (b.tag === KIND_OFF) kindOff() else run(this, e.arg) }
            phoneButtonHooks[action]?.invoke(this, b)
        } else {
            val code = Scenes.KEYS[e.key] ?: error("remote.json: unknown key ${e.key}")
            if (code in Tv.OPENERS) {
                val held = box(HELD, 16)
                b.setOnClickListener { if (b.tag === KIND_OFF) kindOff() else { flash(b, held); opens(code) } }
            } else bindKey(b, code, box(HELD, 16))
        }
        row.addView(b, cell(height, i > 0))
        drawn.removeAll { it.second.id == e.id }; drawn.add(b to e)
    }
    for (i in row.childCount until minKeys) row.addView(android.view.View(this), cell(height, true))   // empty cells: their gaps too, or the short row's keys come out wider
    return captioned(row)
}

/** A menu from remote.json for [menu]: [conditions] says which "when" entries show, [lists] stands in for each "expand" entry. */
internal fun MainActivity.remoteMenu(name: String, conditions: Set<String> = emptySet(), lists: Map<String, () -> List<Triple<String, String, () -> Unit>>> = emptyMap()): List<Triple<String, String, () -> Unit>> {
    val items = ArrayList<Triple<String, String, () -> Unit>>()
    val caps = caps()
    for (e in remoteSpec().menus[name]!!) {
        if (e.whenCond != null && e.whenCond !in conditions || !e.can(caps)) continue   // what this TV cannot do is left out
        if (e.expand != null) { lists[e.expand]?.let { items.addAll(it()) }; continue }
        if (e.action == "menu") {                                     // a group: opened with the same conditions and lists
            val sub = remoteMenu(e.arg ?: "", conditions, lists)
            if (sub.size == 1) items.add(sub[0])                      // a group of one is that one (an LG: the screen off only)
            else if (sub.isNotEmpty()) items.add(Triple("@" + e.icon + "|" + e.words(), e.subWords()) { menu(T(remoteGroups[e.arg] ?: ""), -1, sub) })
            continue                                                  // none: nothing in it on this TV
        }
        val act = e.action ?: "key"                                   // an entry with a "key" (Menu, Info, Subtitles) is the key action with that key
        val arg = if (e.action == null) e.key else e.arg
        val run = phoneActions[act] ?: error("remote.json: no phone handler for \"$act\"")
        items.add(Triple("@" + e.icon + (if (e.tint.isEmpty()) "" else ":" + e.tint) + "|" + e.words(), e.subWords()) { run(this, arg) })
    }
    return items
}

/**
 * LG, Samsung, Roku, VIDAA let no app switch their tracking off: the paths in their own menus (the guide's #tracking-other; the page's
 * trackingMenu), each a tap that opens Settings on the TV and this list again, so the path stays in front of the person.
 */
internal fun MainActivity.trackingMenu() {
    val k = kind(); if (k == Remote.Kind.ANDROID) return
    val open: () -> Unit = { press(Scenes.KEYS[if (k == Remote.Kind.ROKU) "HOME" else "SETTINGS"]!!); window.decorView.postDelayed({ trackingMenu() }, 300) }
    menu(T("track.title", "tv" to Remote.Kind.name(k)), 0, listOf(
        Triple("@settings|" + T("track.open"), T("track.open_sub"), open),
        Triple("@eye|" + T("track.acr"), T("track.${k}_acr"), open),
        Triple("@tag|" + T("track.ads"), T("track.${k}_ads"), open),
        Triple("@mic|" + T("track.mic"), T("track.${k}_mic"), open),
        Triple("@guide|" + T("track.guide"), T("track.guide_sub")) { web("https://tvremote.pages.dev/guide#tracking-other") }))
}
