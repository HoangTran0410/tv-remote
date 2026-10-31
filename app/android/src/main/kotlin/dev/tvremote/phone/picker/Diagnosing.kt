package dev.tvremote.phone.picker

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.remote.kindOf
import dev.tvremote.phone.ui.Popup

import dev.tvremote.core.T
import dev.tvremote.core.Diagnose

// "Why can't I reach the TV?" on the native screens: where a failed connect would put its raw error on the picker, a quick
// self-check (Diagnose.kt, the same one the page asks at /api/diagnose) puts ONE conclusion and what to do. The progress line
// stays until the answer; any other message (a TV tapped, a new try) replaces it, and then the answer is dropped: that is the
// cancel. The raw error comes back if the check itself breaks.

/** Replaces [error], shown on the picker for the TV at [ip] (saved as [entry]), by the conclusion. */
internal fun MainActivity.diagnose(ip: String, entry: String?, error: String) {
    val wait = T("diagnosing.checking_the_network_and")
    message(wait, false)
    val kind = kindOf(entry ?: ip)
    val saved = name(entry ?: ip)
    Thread {
        val r = try { Diagnose.run(Diagnose.Real(), ip, kind, saved, error, dev.tvremote.core.Words.lang) } catch (e: Exception) { null }
        ui.post {
            if (isFinishing || pickerMsg.text.toString() != wait) return@post     // the person has moved on
            if (r == null) { message(error, true); return@post }
            var text = Diagnose.text(r, dev.tvremote.core.Words.lang)
            if (r.code == Diagnose.OFF || r.code == Diagnose.CLOSED)
                text += "\n" + T("diagnosing.or_tap_the_power")
            if (r.code == Diagnose.UP) text += "\n\n(" + error + ")"                // it answers now: the error may still say more
            message(text, r.code != Diagnose.UP)
            if (r.code == Diagnose.WIRELESS) offerWireless(ip, entry)
            val found = r.found
            if (r.code == Diagnose.MOVED && found != null) Popup.Builder(this).setTitle(T("diagnosing.the_tv_has_a"))
                .setMessage(T("diagnosing.is_at_it_was", "name" to found.name, "ip" to found.ip, "ip2" to ip))
                .setPositiveButton(T("diagnosing.use_the_new_address")) { _, _ -> connect(found.ip, entry) }
                .setNegativeButton(T("common.close"), null).show()
        }
    }.start()
}
