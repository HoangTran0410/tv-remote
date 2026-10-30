package dev.tvremote.phone

import dev.tvremote.core.T
import android.content.Intent
import android.os.Build
import android.widget.Toast
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.picture.Tv

// 🎧 The TV's sound on the phone, two ways, picked each time: only here (the TV's speakers quiet: headphones on, the house
// asleep; they come back the moment listening stops), or both (the TV keeps playing: scrcpy's playback capture, Android 13+;
// the phone lags the TV a little and apps that refuse capture are silent). Opus at 64 kbit/s (160 with ✨), about 8 KB/s, decoded here (AudioOut): next to the picture's up to 2 Mbit/s it
// costs the network and the TV almost nothing, and it keeps going with the phone's screen off (ListenService).

/** 🎧 on the top bar: on or off at once, the way picked last (the chip under the picture, [listenMenu]). The session restarts either way, as for the picture. */
internal fun MainActivity.toggleListen() {
    if (needsAdb()) return
    if (listen) { stopListen(null); return }
    listen = true
    listenShare = prefs.getBoolean("listenShare", false)
    listenTvLoud = false
    soundTries = 0
    restartForSound()
    listenService(true)
    Toast.makeText(this, if (listenShare) T("listen.opening_the_tvs_sound")
        else T("listen.opening_the_tvs_sound_2"), Toast.LENGTH_SHORT).show()
}

/** The way to hear the TV, from the chip under the picture; while listening, the change applies at once. */
internal fun MainActivity.listenMenu() {
    val share = prefs.getBoolean("listenShare", false)
    val menu = android.widget.PopupMenu(this, listenChip)
    menu.menu.add(1, 0, 0, T("listen.only_the_phone")).also { it.icon = listenArt(false); it.isChecked = !share }
    menu.menu.add(1, 1, 1, T("listen.tv_and_phone")).also { it.icon = listenArt(true); it.isChecked = share }
    menu.menu.setGroupCheckable(1, true, true)                    // the system draws the chosen one's mark, not a ✓ in the words
    if (android.os.Build.VERSION.SDK_INT >= 29) menu.setForceShowIcon(true)
    menu.setOnMenuItemClickListener { item ->
        if ((item.itemId == 1) != share) { prefs.edit().putBoolean("listenShare", item.itemId == 1).apply(); soundChanged() }
        true
    }
    menu.show()
}

/** HQ under the picture: 160 kbit/s instead of 64. */
internal fun MainActivity.toggleHq() {
    prefs.edit().putBoolean("listenHq", !prefs.getBoolean("listenHq", false)).apply()
    soundChanged()
}

/** "Đều" under the picture: half a second queued from the start, no stall (SoundPace). */
internal fun MainActivity.toggleEven() {
    prefs.edit().putBoolean("listenEven", !prefs.getBoolean("listenEven", false)).apply()
    soundChanged()
}

/** What the sound learnt about each TV, kept across starts: a TV that sends in bursts starts with enough queued. */
internal fun MainActivity.soundPace() {
    val p = dev.tvremote.core.picture.SoundPace
    p.even = prefs.getBoolean("listenEven", false)
    try { val o = org.json.JSONObject(prefs.getString("soundLead", "{}") ?: "{}"); o.keys().forEach { p.learned[it] = o.getInt(it) } } catch (ignored: Exception) { }
    p.changed = { prefs.edit().putString("soundLead", org.json.JSONObject(p.learned as Map<*, *>).toString()).apply() }
}

private fun MainActivity.soundChanged() {
    if (listen) { listenShare = prefs.getBoolean("listenShare", false); restartForSound() } else layoutRemote()
}

/** Back to the TV's speakers; [why] is said when it was not the person's choice (e.g. the TV cannot send its sound). */
internal fun MainActivity.stopListen(why: String?) {
    if (!listen) return
    listen = false
    listenService(false)
    stopSound()
    layoutRemote()
    if (why != null) Toast.makeText(this, why, Toast.LENGTH_LONG).show()
}

private fun MainActivity.restartForSound() {
    stopSound()
    layoutRemote()
    startSound()
}

/**
 * The sound in a session of its own, on its own connection: the picture stops and starts again as the app goes to the
 * background and back, a quality changes, the session reconnects, and none of it cuts the sound; nor does the picture
 * crowd it out on a slow network (one TCP connection waits for whatever is late on it, the sound included).
 */
internal fun MainActivity.startSound() {
    val h = host ?: return
    if (!listen || sound != null || basic != null || kindOf(entry ?: h) != Remote.Kind.ANDROID) return
    lateinit var s: Tv
    s = Tv(this, h, null, 0, 0, false, object : Tv.Listener {
        override fun onStatus(key: String, detail: String) { ui.post { if (sound === s) soundStatus(key, detail) } }
        override fun onVideoSize(w: Int, h: Int) {}
        override fun onNetwork(mac: String, lan: String, tailscale: String) {}
    }, audio = "opus", wantControl = false, audioShare = listenShare, audioBitRate = if (prefs.getBoolean("listenHq", false)) 160000 else 64000)
    sound = s
}

internal fun MainActivity.stopSound() {
    sound?.stop()
    sound = null
}

private fun MainActivity.soundStatus(key: String, detail: String) {
    val h = host ?: return
    when (key) {
        "live" -> soundTries = 0
        "arate" -> if (!video || bgListen) status.text = title(entry ?: h) + " · 🎧 " + detail + " KB/s"
        "audio" -> if (detail == "tvloud") { listenTvLoud = true; Toast.makeText(this, T("listen.tv_still_loud"), Toast.LENGTH_LONG).show(); layoutRemote() }
            else if (detail == "tvsilent") listenTvLoud = false
            else if (detail == "noshare") { listenShare = false; Toast.makeText(this, T("listen.private_only"), Toast.LENGTH_LONG).show() }
            else stopListen(T("listen.this_tv_cannot_send"))
        "closed", "error" -> {                                        // the network dropped (4G, a tunnel): dialled again, a few times
            sound = null
            if (!listen) return
            if (detail.contains("cannot send its sound")) { stopListen(T("listen.this_tv_cannot_send")); return }
            if (soundTries++ < 6) ui.postDelayed({ startSound() }, 1000L * soundTries)   // and once more each time the app is opened
        }
    }
}

internal fun MainActivity.listenService(on: Boolean) {
    val i = Intent(this, ListenService::class.java)
    if (on) askNotifications()
    try {
        if (!on) ListenService.stop(this)
        else { ListenService.starting(); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i) }
    } catch (ignored: Exception) {                                    // no notification allowed: the sound still plays while the app is open
    }
}
