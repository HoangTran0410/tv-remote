package dev.tvremote.setup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.tvremote.core.T
import dev.tvremote.core.adb.Adb
import dev.tvremote.setup.link.Scope
import dev.tvremote.setup.link.Tls

/**
 * Gives a TV Setup pass to a phone or computer that already has ADB on this TV (it could do everything anyway). With it,
 * that device can later ask TV Setup to answer the TV's "Allow USB debugging?" for its own key, when the person is away.
 * Only the shell can send this broadcast (see the manifest), so no app on the TV can get a pass this way.
 * With `--es want fp` it answers the fingerprint of the HTTPS certificate instead (no pass): the shell is the one channel
 * already trusted, so the device pins the HTTPS port to it. An older TV Setup ignores that and gives a pass, which the
 * device takes for what it is. With `--es want adbkey`: the fingerprint of TV Setup's own ADB key and whether it is asking
 * for it, and it asks now (Adb.holdFirstAsk), so that device answers the question for it. With `--es want invite`: a one-time invite and this TV's home address, for a QR another phone
 * scans (Invite.kt), with `--es scope full|remote|view` for what the browser that opens it may do (Scope.kt); clients
 * check the TV Setup version first, as an older one would hand out a pass instead.
 */
class TrustReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        resultCode = Activity.RESULT_OK
        if (i.getStringExtra("want") == "fp") { resultData = Tls.fingerprint(c); return }
        if (i.getStringExtra("want") == "adbkey") {                  // a device just allowed offers to answer our own question (AdbPrompt.helpSetup)
            val state = Adb.askState()
            Adb.askNow()
            resultData = Adb(c.filesDir).fingerprint() + " " + state.ifEmpty { "-" }
            return
        }
        if (i.getStringExtra("want") == "invite") {                  // a QR to share (Invite.kt): the web page must be on to open it
            if (!RemoteService.enabled(c)) RemoteService.setEnabled(c, true, background = true)   // a shell broadcast: TV Setup may be in the background
            resultData = RemoteService.newInvite(i.getStringExtra("scope") ?: Scope.REMOTE) + " " + RemoteService.lanIp()
            return
        }
        val name = when (i.getStringExtra("app")) {
            "phone" -> T("trust.phone_app")
            "computer" -> T("trust.computer_app")
            else -> "App"
        }
        resultData = RemoteService.newToken(c, "app", name, "", i.getStringExtra("tls") == "1")   // an app that pins the HTTPS port says so: its pass is no good over HTTP
    }
}
