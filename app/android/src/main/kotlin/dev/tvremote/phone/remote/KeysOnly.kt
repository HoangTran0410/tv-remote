package dev.tvremote.phone.remote

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.media.say
import dev.tvremote.phone.picker.message
import dev.tvremote.phone.picker.name
import dev.tvremote.phone.picker.offerWireless
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.picker.save
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.inDialog
import dev.tvremote.phone.ui.input
import dev.tvremote.phone.ui.say

import dev.tvremote.core.T
import android.os.Build
import android.view.View
import android.text.InputType
import android.widget.FrameLayout
import android.widget.Toast
import dev.tvremote.core.remote.Atv
import dev.tvremote.core.remote.CodePairing
import dev.tvremote.core.remote.Vidaa
import dev.tvremote.core.remote.Remote
import java.util.Locale

// When the TV's ADB is off: the keys-only connection (Android TV Remote protocol), pairing it, and noticing when ADB comes on.
// LG and Samsung TVs only ever have this connection, through their own adapter (Remote.kt): the TV asks itself whether to let
// the phone in, there is no code to type and no ADB to wait for.

/** What the TV at [ip] is: saved with it ("kind:<ip>"), found by the scan, or Android TV. */
internal fun MainActivity.kindOf(ip: String): String = Remote.Kind.of(pref("kind:$ip"))

private fun MainActivity.keysLine(a: Remote) = if (a.kind == Remote.Kind.ANDROID) T("keys_only.keys_only") else T(if (a.kind == Remote.Kind.VIDAA) "keys_only.tv_beta_vidaa" else "keys_only.tv_beta", "kind" to Remote.Kind.name(a.kind))

internal fun MainActivity.refused(err: String?): Boolean {
    val e = err?.lowercase(Locale.ROOT) ?: ""
    return e.contains("refused") && !e.contains("refused:") || e.contains("econnrefused")
}

/**
 * ADB did not work. Before saying so, see whether the TV answers as an Android TV: then the keys-only remote takes over
 * (straight away when this phone is paired with it, after asking otherwise). [error] is shown only when that is not possible.
 */
internal fun MainActivity.offerBasic(ip: String, error: String) {
    Thread {
        val plain = open(ip, 5555, 1500)
        if (plain) adbFailed.add(ip)                               // the port answers, yet ADB did not work (not allowed on the TV, say): do not keep retrying by ourselves
        val wireless = !plain && dev.tvremote.core.adb.AdbTls.available() && dev.tvremote.core.adb.AdbTls.wireless(ip).any   // Android 11+ with only Wireless debugging: pairing, not an error
        val keys = open(ip, 6466, 2500)                            // an Android TV: it takes keys
        val other = if (keys || wireless) null else Remote.Kind.detect(ip, wireless = { false })?.takeIf { it != Remote.Kind.ANDROID }   // an address typed in: maybe an LG, Samsung or Roku TV
        ui.post {
            if (isFinishing || host != ip || tv != null) return@post   // the person has moved on
            if (wireless) { val e = entry; disconnect(null); offerWireless(ip, e); return@post }
            if (other != null) { prefs.edit().putString("kind:$ip", other).apply(); disconnect(null); startBasic(ip, false); return@post }
            if (!keys) { disconnect(error); return@post }
            disconnect(null)
            if (prefs.getBoolean("atv:$ip", false)) startBasic(ip, false)
            else Popup.Builder(this).setTitle(T("keys_only.adb_debugging_is_off"))
                .setMessage(T("keys_only.you_can_still_press"))
                .setPositiveButton(T("keys_only.pair_with_a_code")) { _, _ -> startBasic(ip, false) }
                .setNegativeButton(T("common.close")) { _, _ -> message(error, true) }
                .setOnCancelListener { message(error, true) }.show()
        }
    }.start()
}

internal fun MainActivity.startBasic(ip: String, justPaired: Boolean) {
    message(T("keys_only.connecting_to", "ip" to ip), false)
    val a = Remote.of(kindOf(entry ?: ip), filesDir, ip)
    Thread {
        var asked = false
        while (!a.ready() && !isFinishing) {                          // LG / Samsung: the TV asks; say so while it does
            if (a.asking && !asked) { asked = true; ui.post { message(T("keys_only.look_at_the_tv"), false) } }
            try { Thread.sleep(300) } catch (e: InterruptedException) { break }
            if (a.kind == Remote.Kind.ANDROID) break
        }
    }.apply { isDaemon = true }.start()
    Thread {
        try {
            if (a is Vidaa) vidaaCert()
            a.connect { ui.post { if (basic === a) keysLost(a, ip) } }
            ui.post {
                if (isFinishing || host != null) { a.close(); return@post }
                if (a.kind == Remote.Kind.ANDROID) prefs.edit().putBoolean("atv:$ip", true).apply()
                else {
                    val e = prefs.edit().putString("kind:" + (entry ?: ip), a.kind)
                    if (a.mac.isNotEmpty()) e.putString("mac:" + (entry ?: ip), a.mac)
                    if (Regex("^(10|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\.").containsMatchIn(ip)) e.putString("lan:" + (entry ?: ip), ip)   // for Wake-on-LAN from home
                    e.apply()
                }
                basic = a
                host = ip
                save(entry ?: ip, a.model)
                message(null, false)
                show(true)
                layoutRemote()
                status.text = title(ip) + " · " + keysLine(a)
                if (a is Atv) watchAdb(a, ip)
            }
        } catch (e: Remote.Refused) {
            a.close()
            ui.post { message(T("keys_only.the_tv_said_no"), true) }
        } catch (e: Atv.NotPaired) {
            a.close()
            if (justPaired) ui.post { message(T("keys_only.the_tv_did_not"), true) }
            else pair(ip)
        } catch (e: Exception) {
            a.close()
            ui.post { message(explain(e.message), true) }
        }
    }.start()
}

/** The keys-only connection ended: away, it is opened again on return (onStart); on screen, at once. */
internal fun MainActivity.keysLost(a: Remote, ip: String) {
    basic = null
    a.close()
    if (foreground) resumeBasic(ip)
    else { keysAway = true; status.text = title(entry ?: ip) + " · " + T("phone.reconnects_when_the_app") }
}

/** The keys-only connection again, to a TV this phone is paired with, staying on the remote; the list of TVs only if it fails. */
internal fun MainActivity.resumeBasic(ip: String) {
    waitFor(title(entry ?: ip), T("keys_only.reconnecting"))
    Thread {
        val a = Remote.of(kindOf(entry ?: ip), filesDir, ip)
        try {
            a.connect { ui.post { if (basic === a) keysLost(a, ip) } }
            ui.post {
                if (isFinishing || host != ip || basic != null || tv != null) { a.close(); return@post }   // the person has moved on
                basic = a
                waiting.visibility = View.GONE
                status.text = title(entry ?: ip) + " · " + keysLine(a)
                layoutRemote()
                if (a is Atv) watchAdb(a, ip)
            }
        } catch (e: Exception) {
            a.close()
            ui.post { if (host == ip && basic == null && tv == null) disconnect(explain(e.message)) }
        }
    }.start()
}

/** While on the keys-only remote: the moment the TV starts answering on its ADB port, move over to the full connection. */
internal fun MainActivity.watchAdb(a: Atv, ip: String) {
    if (ip in adbFailed) return
    Thread {
        while (basic === a && !isFinishing) {
            Thread.sleep(4000)
            if (basic === a && open(ip, 5555, 1200)) {
                ui.post {
                    if (basic !== a || isFinishing) return@post
                    Toast.makeText(this, T("keys_only.adb_is_on_switching"), Toast.LENGTH_LONG).show()
                    val e = entry
                    disconnect(null)
                    connect(ip, e)
                }
                return@Thread
            }
        }
    }.apply { isDaemon = true }.start()
}

/** Hisense's certificate, read once from Hisense's own app on this phone (never shipped with ours): VIDAA TVs from 2022 on want it. */
internal fun MainActivity.vidaaCert() {
    if (Vidaa.hasCert(filesDir)) return
    for (pkg in Vidaa.APPS) try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        for (apk in listOfNotNull(info.sourceDir) + (info.splitSourceDirs ?: emptyArray()))
            if (java.io.File(apk).let { f -> f.inputStream().use { Vidaa.importCert(filesDir, it, f.length()) } }) return
    } catch (ignored: Exception) { }
}

/** On a worker thread: the TV shows a code, the person types it here (6 characters on an Android TV, 4 digits on a Hisense). */
internal fun MainActivity.pair(ip: String) {
    val vidaa = kindOf(entry ?: ip) == Remote.Kind.VIDAA
    val p = Remote.of(kindOf(entry ?: ip), filesDir, ip) as CodePairing
    try { p.startPairing() } catch (e: Exception) {
        p.close()
        ui.post { message(T("keys_only.could_not_pair_with", "error" to e.message), true) }
        return
    }
    ui.post {
        message(null, false)
        val code = input(if (vidaa) T("keys_only.the_4_digits_on") else T("keys_only.the_6_characters_on"))
        code.inputType = if (vidaa) android.text.InputType.TYPE_CLASS_NUMBER
            else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val wrap = inDialog(code)
        Popup.Builder(this).setTitle(T("keys_only.type_the_code_shown")).setView(wrap)
            .setPositiveButton(T("keys_only.pair")) { _, _ ->
                val typed = code.text.toString()
                Thread {
                    try {
                        p.finishPairing(typed)
                        ui.post { startBasic(ip, true) }
                    } catch (e: Exception) {
                        p.close()
                        ui.post { message(T("keys_only.wrong_code_tap_the"), true) }
                    }
                }.start()
            }
            .setNegativeButton(T("common.cancel")) { _, _ -> p.close() }
            .setOnCancelListener { p.close() }.show()
    }
}

internal fun MainActivity.explain(err: String?): String {
    val e = err?.lowercase(Locale.ROOT) ?: ""
    if (e.contains(dev.tvremote.core.remote.Roku.LIMITED) || err?.contains(Vidaa.NO_CERT) == true || err?.contains(dev.tvremote.core.Lan.BLOCKED) == true) return dev.tvremote.core.Errors.say(java.io.IOException(err), dev.tvremote.core.Words.lang)
    if (refused(err))
        return T("keys_only.the_tv_refused_the")
    if (e.contains("timed out") || e.contains("timeout") || e.contains("unreachable") || e.contains("no route") || e.contains("failed to connect"))
        return T("keys_only.cannot_reach_the_tv") + "\n\n" +
            T("keys_only.if_the_tv_is") + "\n\n(" + err + ")"
    return T("keys_only.error", "error" to err)
}
