package dev.tvremote.phone.picker

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.R
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.media.say
import dev.tvremote.phone.remote.keys
import dev.tvremote.phone.remote.kindOf
import dev.tvremote.phone.remote.pair
import dev.tvremote.phone.remote.watchAdb
import dev.tvremote.phone.ui.DL_CODE
import dev.tvremote.phone.ui.DlApp
import dev.tvremote.phone.ui.HOME_APP
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.SETUP_APP
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.col
import dev.tvremote.phone.ui.gap
import dev.tvremote.phone.ui.inDialog
import dev.tvremote.phone.ui.input
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.menu
import dev.tvremote.phone.ui.say

import dev.tvremote.core.T
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import dev.tvremote.core.Diagnose
import dev.tvremote.core.remote.Atv

// Helping somebody turn ADB on with the keys alone: installing TV Setup through Downloader, finding Settings, other home screens.

/** The TV's screen is not where a job left it: someone pressed keys on the TV in the middle. */
internal class TvMoved : Exception()

/** The TV's home screens (Google TV, Android TV, makers'): where Back or Home pressed in the middle lands. */
internal val TV_HOME = Regex("launcher|\\.home\\b|tvhome", RegexOption.IGNORE_CASE)

internal fun MainActivity.tvFree(): Boolean {
    if (tvBusy.compareAndSet(false, true)) return true
    Toast.makeText(this, T("adb_help.the_tv_is_still"), Toast.LENGTH_SHORT).show()
    return false
}
internal fun MainActivity.tvJob(work: () -> Unit) {
    if (!tvFree()) return
    Toast.makeText(this, T("adb_help.working_on_the_tv"), Toast.LENGTH_SHORT).show()
    Thread { try { work() } catch (ignored: Exception) { } finally { tvBusy.set(false) } }.start()
}

/** Everything the keys-only mode can do towards turning ADB on, in the order one needs it. */
internal fun MainActivity.adbHelp() {
    menu(T("setup.turn_on_adb"), 0, listOf(
        Triple("@zap|" + T("adb_help.automatic_install_tv_setup"), T("adb_help.just_confirm_on_the")) { installSetup() },
        Triple("@gear|" + T("adb_help.by_hand_open_tv"), "") { findSettings() },
        Triple("@home|" + T("adb_help.install_another_home_screen"), T("adb_help.when_the_tv_hides")) { otherHome() },
        Triple("@wifi|" + T("setup.wireless_title"), T("adb_help.android_11_or_newer")) { wirelessPair() },
        Triple("@refresh|" + T("adb_help.full_connection"), "") {
            val ip = host
            adbFailed.remove(ip)
            val e = entry
            disconnect(null)
            if (ip != null) connect(ip, e)
        },
        Triple("@store|" + T("adb_help.open_the_play_store"), "") { basic?.launch("https://play.google.com/store/apps"); Unit },
        Triple("@package|" + T("adb_help.open_an_app_by"), "") {
            val box = input("com.example.app")
            val wrap = inDialog(box)
            Popup.Builder(this).setTitle(T("setup.pkg_ask")).setView(wrap)
                .setPositiveButton(T("apps.open")) { _, _ ->
                    val what = box.text.toString().trim()
                    val b = basic
                    if (what.isNotEmpty() && b != null) { if (what.contains(":")) b.launch(what) else tvJob { openOnTv(b, what) { false } } }
                }
                .setNegativeButton(T("common.cancel"), null).show()
            Unit
        },
        Triple("@link|" + T("setup.direct_title"), T("adb_help.a_fallback_not_reliable")) { installSetupDirect() }))
}

/**
 * The old short way, kept as a fallback: the TV gets the APK link itself and whatever app takes it downloads it. Some TVs
 * then install it; others (TCL's browser) download it and sit on a white page. So when nobody says it worked within a
 * minute, the Downloader ways are offered.
 */
internal fun MainActivity.installSetupDirect() {
    val a = basic ?: return
    if (!tvFree()) return
    var answered = false
    val box = Popup.Builder(this).setTitle(T("setup.direct_title"))
        .setMessage(T("setup.sending_link"))
        .setPositiveButton(T("adb_help.installed")) { _, _ -> answered = true; tvJob { openOnTv(a, "dev.tvremote.setup") { false } } }
        .setNeutralButton(T("adb_help.other_ways")) { _, _ -> answered = true; installSetupByHand() }
        .setNegativeButton(T("common.close")) { _, _ -> answered = true }.setOnCancelListener { answered = true }.show()
    box.setCanceledOnTouchOutside(false)
    Thread {
        try {
            a.launch("https://github.com/hoangtran0410/tv-remote/releases/latest/download/tv-setup.apk")
            for (press in 0 until 3) {                                // "Open with": OK takes what is offered
                Thread.sleep(1500)
                if (a.app != "android" || answered) break
                a.pulse(23, 110)
            }
        } catch (ignored: Exception) {
        } finally {
            tvBusy.set(false)
        }
        ui.post {
            if (box.isShowing) box.setMessage(T("setup.direct_body"))
        }
    }.start()
    lateinit var later: Runnable
    later = Runnable {                                            // a minute with no answer: most likely stuck, show the sure way
        if (answered || !box.isShowing || isFinishing) return@Runnable
        if (basic === a && a.app.contains("installer")) { ui.postDelayed(later, 30000); return@Runnable }   // the TV's installer is up: the person is busy there
        box.dismiss()
        Popup.Builder(this).setTitle(T("adb_help.not_installed_yet"))
            .setMessage(T("adb_help.many_tvs_do_not"))
            .setPositiveButton(T("adb_help.install_through_downloader")) { _, _ -> installSetup() }
            .setNeutralButton(T("adb_help.step_by_step")) { _, _ -> installSetupByHand() }
            .setNegativeButton(T("common.close"), null).show()
    }
    ui.postDelayed(later, 60000)
}

/**
 * Wireless debugging (Android 11+): the TV shows a 6-digit code and an IP:port to pair with. Pairing, then the TLS connect
 * port (found by mDNS, typed when nothing answered), then the usual connection; no "Allow USB debugging?" question.
 */
internal fun MainActivity.wirelessPair(target: String? = host) {
    val ip = target
    val addr = input(T("setup.ph_addr"))
    val code = input(T("setup.ph_code6"))
    addr.setSingleLine(true); code.inputType = android.text.InputType.TYPE_CLASS_NUMBER
    val body = col()
    body.addView(label(T("setup.wireless_steps"), 14, TEXT))
    body.addView(addr, gap(8)); body.addView(code, gap(8))
    Thread {                                                               // prefilled when the TV announces itself on the network
        try {
            val (pairing, _) = dev.tvremote.core.adb.AdbTls.discover()
            val p = pairing.firstOrNull { it.ip == ip } ?: pairing.firstOrNull()
            if (p != null) ui.post { if (addr.text.isEmpty()) addr.setText("${p.ip}:${p.port}") }
        } catch (ignored: Exception) { }
    }.start()
    Popup.Builder(this).setTitle(T("setup.wireless_title")).setView(inDialog(body))
        .setPositiveButton(T("keys_only.pair")) { _, _ ->
            val m = Regex("^(?:(.+):)?(\\d{2,5})$").find(addr.text.toString().trim())
            val c = dev.tvremote.core.adb.AdbTls.cleanCode(code.text.toString())
            if (m == null || c == null) { Toast.makeText(this, T("setup.enter_addr_code"), Toast.LENGTH_LONG).show(); return@setPositiveButton }
            val dest = m.groupValues[1].ifEmpty { ip ?: "" }
            Toast.makeText(this, T("setup.pairing"), Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    dev.tvremote.core.adb.AdbTls.pair(dev.tvremote.core.adb.Adb(filesDir), dest, m.groupValues[2].toInt(), c)
                    var port = 0
                    for (i in 0..2) if (port <= 0) port = dev.tvremote.core.adb.AdbTls.discoverConnect(dest)
                    if (port > 0) ui.post { pairedAt(dest, port) } else ui.post { askConnectPort(dest) }
                } catch (e: Exception) {
                    ui.post { Toast.makeText(this, dev.tvremote.core.Errors.say(e, dev.tvremote.core.Words.lang), Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
        .setNegativeButton(T("common.cancel"), null).show()
}

/**
 * The TV has no ADB on 5555 but announces Wireless debugging (Android 11+; the TCL China sets): one question that goes straight
 * to pairing, in place of an error. [entry] is how the TV is saved (its name tells a TCL).
 */
internal fun MainActivity.offerWireless(ip: String, entry: String?) {
    val lang = dev.tvremote.core.Words.lang
    val (title, fix) = Diagnose.say(Diagnose.WIRELESS, Diagnose.Facts(kindOf(entry ?: ip), ip, emptyMap(), Diagnose.Net.NA, false, null, brand = name(entry ?: ip)), lang)
    Popup.Builder(this).setTitle(title).setMessage(fix)
        .setPositiveButton(T("keys_only.pair")) { _, _ -> wirelessPair(ip) }
        .setNegativeButton(T("common.close"), null).show()
}

private fun MainActivity.askConnectPort(target: String) {
    val box = input(T("adb_help.connect_port"))
    box.inputType = android.text.InputType.TYPE_CLASS_NUMBER
    Popup.Builder(this).setTitle(T("adb_help.paired_connect_port"))
        .setMessage(T("adb_help.on_the_wireless_debugging"))
        .setView(inDialog(box))
        .setPositiveButton(T("page.connect")) { _, _ -> box.text.toString().trim().toIntOrNull()?.let { pairedAt(target, it) } }
        .setNegativeButton(T("common.cancel"), null).show()
}

private fun MainActivity.pairedAt(target: String, port: Int) {
    dev.tvremote.core.adb.AdbTls.save(filesDir, target, port)
    adbFailed.remove(target)
    val e = entry
    disconnect(null)
    connect(target, e)
}

/** The step-by-step ways are TV Setup's (Downloader's page by hand); for TV Home the list of home screens is the way back. */
private fun MainActivity.byHand(app: DlApp) = if (app === SETUP_APP) installSetupByHand() else otherHome()

/**
 * Getting our TV app onto a TV that has no ADB yet, in one tap: the Downloader app (installed from the Play Store when
 * missing) fetches it, and the phone opens Downloader, types the link and presses Go. What only the person can do is
 * confirm the install on the TV.
 */
internal fun MainActivity.installSetup(app: DlApp = SETUP_APP) {
    val a = basic ?: return
    if (!tvFree()) return
    val link = "github.com/hoangtran0410/tv-remote/releases/latest/download/tv-setup.apk"
    val dl = "com.esaba.downloader"
    var stop = false
    var installed = false                                         // the person says the TV has finished installing Downloader
    val box = Popup.Builder(this).setTitle(T(app.title)).setMessage(T("adb_help.opening_downloader_on_the"))
        .setNeutralButton(T("adb_help.installed"), null)
        .setNegativeButton(T("apps.stop")) { _, _ -> stop = true }.setOnCancelListener { stop = true }.show()
    val doneBtn = box.getButton(Popup.BUTTON_NEUTRAL)
    var step = 1                                                  // of 3, said before the words; and to leave the TV's remote alone
    val say = { id: String -> ui.post { if (box.isShowing) box.setMessage(T("adb_help.step_3", "step" to step, "msg" to T(id))) } }
    say("adb_help.opening_downloader_on_the")
    // The box changes its words as it goes. It must not change its size with them, or the buttons slide away from under
    // a finger that is still tapping, and a tap that lands beside the box must not close it and stop the whole thing.
    box.setCanceledOnTouchOutside(false)
    box.findViewById<TextView>(android.R.id.message)?.minLines = 7
    // greyed out while the check runs; its words stay, or the buttons would shift under a finger that is still tapping
    val checking = { on: Boolean -> ui.post { doneBtn.isEnabled = !on; doneBtn.alpha = if (on) 0.4f else 1f } }
    doneBtn.setOnClickListener { if (!installed) { installed = true; checking(true); say("adb_help.checking_on_the_tv") } }   // set here so that the box stays open; one check at a time
    // waits until the TV says [pkg] is in front; false when it does not within [seconds], or the person stopped it
    val front = { pkg: String, seconds: Int ->
        var ok = false
        for (i in 0 until seconds * 5) { if (stop || basic !== a) break; if (a.app == pkg) { ok = true; break }; Thread.sleep(200) }
        ok
    }
    Thread {
        try {
            if (a.app.isEmpty()) {                                    // this TV does not say what is on its screen: no way to follow along
                ui.post { box.dismiss(); byHand(app) }
                return@Thread
            }
            // Downloader first, always: a TV's own browser takes the APK link too and downloads it, then stays on a white page,
            // unable to hand the file to the installer. Downloader's own link (downloader://) opens nothing else.
            var there = openOnTv(a, dl) { stop }
            if (!there && !stop && basic === a) {                     // its Play Store page was told to install it
                say("adb_help.the_tv_has_no")
                while (!stop && basic === a && !there) {
                    for (i in 0 until 600) { if (stop || installed || a.app == dl) break; Thread.sleep(200) }
                    if (stop || basic !== a) break
                    checking(true)
                    say("adb_help.opening_downloader_on_the_tv")
                    there = a.app == dl || openOnTv(a, dl) { stop }
                    installed = false
                    checking(false)
                    if (!there) say("adb_help.the_tv_still_has")
                }
            }
            if (!there) {
                ui.post { if (box.isShowing) box.dismiss(); if (!stop && basic === a) byHand(app) }
                return@Thread
            }
            step = 2
            say("adb_help.handing_the_download_link")
            Thread.sleep(3000)
            a.launch("downloader://${app.code}")                    // its own link: opens Downloader alone
            for (press in 0 until 3) {                                // "Open with", should another app claim it too
                Thread.sleep(1500)
                if (a.app != "android" || stop) break
                a.pulse(23, 110)
            }
            Thread.sleep(2500)
            val from = a.app
            step = 3
            say("adb_help.link_entered_waiting_for")
            for (i in 0 until 240) {                                  // up to 2 minutes, until the TV's installer (or its permission screen) comes up
                if (stop || basic !== a || a.app != from) break
                Thread.sleep(500)
            }
            if (!stop && TV_HOME.containsMatchIn(a.app)) throw TvMoved()   // back home instead: Back or Home pressed on the TV
            ui.post {
                if (box.isShowing) box.dismiss()
                if (stop || basic !== a || isFinishing) return@post
                Popup.Builder(this).setTitle(T("allow.look_at_the_tv"))
                    .setMessage(T(app.look))
                    .setPositiveButton(T(app.open)) { _, _ -> if (app === HOME_APP) openHome(a) else tvJob { openOnTv(a, app.pkg) { false } } }
                    .setNeutralButton(T("adb_help.step_by_step")) { _, _ -> byHand(app) }
                    .setNegativeButton(T("common.close"), null).show()
            }
        } catch (e: TvMoved) {
            ui.post {
                if (box.isShowing) box.dismiss()
                if (stop || isFinishing) return@post
                Popup.Builder(this).setTitle(T(app.title))
                    .setMessage(T("setup.moved"))
                    .setPositiveButton(T("core.try_again")) { _, _ -> installSetup(app) }
                    .setNeutralButton(T("adb_help.step_by_step")) { _, _ -> byHand(app) }
                    .setNegativeButton(T("common.close"), null).show()
            }
        } catch (ignored: Exception) {
        } finally {
            tvBusy.set(false)
        }
    }.start()
}

/**
 * Brings the app [pkg] to the front on the TV, on a worker thread; true once the TV says it is there. Asking for the app by
 * name works on some TVs and not on others (and not every time), so the sure way follows: its Play Store page, where OK
 * presses the main button. That is "Open" when the app is installed; when it is not, the same press starts installing it,
 * and this returns false.
 */
internal fun MainActivity.openOnTv(a: dev.tvremote.core.remote.Remote, pkg: String, play: Boolean = true, stop: () -> Boolean): Boolean {
    val front = { want: String, seconds: Int ->
        var ok = false
        for (i in 0 until seconds * 5) { if (stop() || basic !== a) break; if (a.app == want) { ok = true; break }; Thread.sleep(200) }
        ok
    }
    if (a.app == pkg) return true
    a.launch(pkg)
    if (front(pkg, 4)) return true
    if (!play) return false                                       // not on the Play Store (TV Home): its page has nothing to install
    a.launch("https://play.google.com/store/apps/details?id=$pkg")
    if (!front("com.android.vending", 10)) return false
    Thread.sleep(3000)                                            // the page draws, with its main button in focus
    if (a.app != "com.android.vending") throw TvMoved()           // someone used the TV's remote: OK would press something else
    a.pulse(23, 110)
    return front(pkg, 15)
}

/**
 * Drives Downloader's home page to load [link], on a worker thread. The page has a pointer and no field in focus, so:
 * hold Up until the pointer leaves the page and the toolbar takes the focus; go to its right end and two back, which is
 * the address field wherever the focus first landed; OK opens the field with the TV's keyboard; type; Enter loads.
 */
internal fun MainActivity.downloaderGo(a: dev.tvremote.core.remote.Remote, link: String) {
    for (i in 0 until 40) a.pulse(19, 70)                          // many short holds: over this protocol a key cannot be kept down, each one nudges the pointer
    for (i in 0 until 4) a.pulse(22, 110)
    for (i in 0 until 2) a.pulse(21, 110)
    Thread.sleep(40 * 90L + 6 * 350L)                             // the keys above are queued; let them play out
    a.pulse(23, 110)
    Thread.sleep(2500)                                            // the field and the keyboard open
    (a as? Atv)?.keys(link) ?: a.text(link)
    Thread.sleep(link.length * 60L + 1000)
    a.key(66, Atv.SHORT)
}

/** The same, one step at a time, for when the automatic run loses its way. */
internal fun MainActivity.installSetupByHand() {
    val link = "github.com/hoangtran0410/tv-remote/releases/latest/download/tv-setup.apk"
    menu(T("setup.install_setup_title"), -1, listOf(
        Triple(T("adb_help.1_open_downloader_on"), T("adb_help.not_there_install_it")) {
            basic?.let { b -> tvJob { openOnTv(b, "com.esaba.downloader") { false } } }
            ui.postDelayed({ if (basic != null && !isFinishing) installSetupByHand() }, 600)
        },
        Triple(T("adb_help.2_download_tv_setup"), T("adb_help.code", "dl_code" to DL_CODE)) {
            basic?.let { b -> tvJob { b.launch("downloader://$DL_CODE") } }
            Toast.makeText(this, T("adb_help.asking_downloader_to_download"), Toast.LENGTH_LONG).show()
            ui.postDelayed({ if (basic != null && !isFinishing) installSetupByHand() }, 600)
        },
        Triple(T("adb_help.2b_no_luck_type"), T("adb_help.the_app_types_the")) {
            basic?.let { b -> tvJob { downloaderGo(b, DL_CODE) } }
            Toast.makeText(this, T("adb_help.entering_the_link_on"), Toast.LENGTH_LONG).show()
            ui.postDelayed({ if (basic != null && !isFinishing) installSetupByHand() }, 600)
        },
        Triple(T("adb_help.3_open_tv_setup"), T("adb_help.then_turn_on_usb")) { basic?.let { b -> tvJob { openOnTv(b, "dev.tvremote.setup") { false } } }; Unit },
        Triple(T("adb_help.copy_the_link"), T("adb_help.to_type_in_downloader", "dl_code" to DL_CODE, "link" to link)) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("tv-setup", link))
            Toast.makeText(this, T("health.copied"), Toast.LENGTH_SHORT).show()
        }))
}

internal fun MainActivity.isSettings(pkg: String) = pkg.contains("settings", true)

/** Tries the Settings key, then the links that open Settings on other TVs, watching which app the TV says is in front. */
internal fun MainActivity.findSettings() {
    val a = basic ?: return
    if (!tvFree()) return
    Toast.makeText(this, T("setup.trying_settings"), Toast.LENGTH_SHORT).show()
    Thread {
        try {
            val tries = listOf<() -> Unit>({ a.key(176, Atv.SHORT) }, { a.launch("android-app://com.android.tv.settings") }, { a.launch("intent:#Intent;action=android.settings.SETTINGS;end") })
            var ok = isSettings(a.app)
            for (attempt in tries) {
                if (ok) break
                for (i in 0 until 30) { if (a.ready()) break; Thread.sleep(200) }   // the TV may have dropped us after the last try
                attempt()
                for (i in 0 until 12) { Thread.sleep(200); if (isSettings(a.app)) { ok = true; break } }
            }
            val blind = a.app.isEmpty()                                // this TV does not say what is on its screen
            ui.post {
                if (isFinishing || basic !== a) return@post
                if (ok) adbSteps()
                else if (blind) Popup.Builder(this).setMessage(T("setup.settings_opened_q"))
                    .setPositiveButton(T("apps.yes")) { _, _ -> adbSteps() }.setNegativeButton(T("apps.no")) { _, _ -> lookOnHome() }.show()
                else lookOnHome()
            }
        } catch (ignored: Exception) {
        } finally {
            tvBusy.set(false)
        }
    }.start()
}

/** The keys and links did nothing: the TV's own home screen may still have Settings somewhere, before anything gets installed. */
internal fun MainActivity.lookOnHome() {
    Popup.Builder(this).setTitle(T("setup.look_title"))
        .setMessage(T("adb_help.look_on_the_tv"))
        .setPositiveButton(T("adb_help.found_it")) { _, _ -> adbSteps() }
        .setNegativeButton(T("setup.none_other_home")) { _, _ -> otherHome() }.show()
}

/**
 * Opens TV Home, which is not on the Play Store, so "installed?" has one honest test: the TV does not bring it to the front.
 * Then it is put on through Downloader like TV Setup (the TV's remote protocol cannot list its apps).
 */
internal fun MainActivity.openHome(a: dev.tvremote.core.remote.Remote, then: () -> Unit = {}) {
    if (!tvFree()) return
    Toast.makeText(this, T("adb_help.working_on_the_tv"), Toast.LENGTH_SHORT).show()
    Thread {
        var there = false
        try {
            if (a.app.isEmpty()) { a.launch(HOME_APP.pkg); there = true }   // this TV does not say what is on its screen: the person sees whether it opened
            else there = openOnTv(a, HOME_APP.pkg, play = false) { false }
        } catch (ignored: Exception) { } finally { tvBusy.set(false) }
        ui.post { if (isFinishing || basic !== a) return@post; if (there) then() else installSetup(HOME_APP) }
    }.start()
}

internal fun MainActivity.otherHome() {
    val a = basic ?: return
    val own = Triple("TV Home", T("setup.home_own_sub")) {
        openHome(a) {
            Popup.Builder(this).setTitle("TV Home").setMessage(T("setup.home_look_own"))
                .setPositiveButton(T("setup.adb_steps_btn")) { _, _ -> adbSteps() }
                .setNeutralButton(T("adb_help.open_again")) { _, _ -> openHome(a); ui.postDelayed({ if (basic === a && !isFinishing) adbSteps() }, 800) }
                .setNegativeButton(T("setup.choose_another")) { _, _ -> otherHome() }.show()
        }
    }
    menu(T("setup.via_other_home"), -1, listOf(own) + HOMES.map { (name, pkg) ->
        Triple(name, if (a.app == pkg) T("adb_help.open_on_the_tv") else T("adb_help.opens_it_or_its")) {
            tvJob { openOnTv(a, pkg) { false } }
            Popup.Builder(this).setTitle(name).setMessage(T("adb_help.look_at_the_tv"))
                .setPositiveButton(T("setup.adb_steps_btn")) { _, _ -> adbSteps() }
                .setNeutralButton(T("adb_help.open_again")) { _, _ -> tvJob { openOnTv(a, pkg) { false } }; ui.postDelayed({ if (basic === a && !isFinishing) adbSteps() }, 800) }
                .setNegativeButton(T("setup.choose_another")) { _, _ -> otherHome() }.show()
            Unit
        }
    })
}

// The steps are the web's (setup.js adbGuide): one copy for the phone, the TV's page and the computer. ADB is off here, so the
// page cannot ask the TV what it is: the maker and model it gave over the remote protocol pick its maker's way. Once the
// switch is on, watchAdb() moves to the full connection by itself.
internal fun MainActivity.adbSteps() {
    val words = listOfNotNull(basic?.vendor, basic?.model).joinToString(" ")
    managing.open(host ?: return, then = "fromRemote(() => showAdbGuide(" + org.json.JSONObject.quote(words) + "))")
}
