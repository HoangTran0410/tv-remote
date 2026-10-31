package dev.tvremote.phone.media

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.manage.web
import dev.tvremote.phone.picker.message
import dev.tvremote.phone.picker.name
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.remote.remoteMenu
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.inDialog
import dev.tvremote.phone.ui.input
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.menu
import dev.tvremote.phone.ui.say

import dev.tvremote.core.T
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import dev.tvremote.core.adb.Adb
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.ontv.Channels
import dev.tvremote.core.ontv.Home
import dev.tvremote.core.manage.Manage

// Things sent to the TV: links, shared links and files from other apps, photos / videos / songs from this phone, the Play Store.

/** Makes the TV open a link: a Play Store page, a YouTube video, a web address. Works with and without ADB. */
internal fun MainActivity.openLink(link: String) {
    val b = basic
    val tv = tv
    if (b != null) b.launch(link)
    else if (tv != null && tv.ready()) Thread {
        try {
            // a stream (.m3u8, .mp4 ...) plays in TV Channels' player: most TVs have nothing that opens it. Without TV Channels it
            // goes as any link, and the person hears that TV Channels would play it
            if (Channels.isStream(link)) {
                val r = Channels.playResult({ tv.shell(it) }, link)
                if (r == Channels.Play.OK) return@Thread
                // too old is not missing: the fix is the update, in the Manage screen's TV Channels card
                ui.post { say(T(if (r == Channels.Play.OLD) "core.fr_channels_old" else "remote.stream_needs_channels"), false) }
            }
            tv.shell("am start -a android.intent.action.VIEW -d " + Adb.q(link))
        } catch (ignored: Exception) { }
    }.start()
}

// ---------------------------------------------------------------- "Share → TV Remote" from other apps, and playing a file on the TV
/** On the remote screen the picker's message line is out of sight: say it in a toast there. */
internal fun MainActivity.say(text: String, error: Boolean) {
    if (host == null) message(text, error) else Toast.makeText(this, text, if (error) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** Another app shared a link or a file with us: keep it until a TV is connected, then send it there. */
internal fun MainActivity.takeShare(i: Intent?): Boolean {
    if (i == null || Intent.ACTION_SEND != i.action) return false
    shared = i
    setIntent(Intent(this, MainActivity::class.java))             // not again after a rotation
    sendShared(0)
    return true
}

internal fun MainActivity.sendShared(tries: Int) {
    val i = shared ?: return
    if (!live()) {
        if (host == null) message(T("media.choose_a_tv_to"), false)
        if (tries < 90) ui.postDelayed({ if (!isFinishing) sendShared(tries + 1) }, 1000) else shared = null
        return
    }
    shared = null
    @Suppress("DEPRECATION")
    val file = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    // Any app can send us this: only a content:// of another app (file:// would be opened with our own rights, our keys included),
    // and only web links (market://, intent: … would be run on the TV unseen).
    if (file != null) {
        if (file.scheme == "content" && file.authority?.startsWith(packageName) != true) confirmShare(sharedName(file)) { castFile(file) }
        else say(T("media.this_file_cannot_be"), true)
        return
    }
    val link = Regex("(?i)https?://\\S+").find(i.getStringExtra(Intent.EXTRA_TEXT) ?: "")?.value
    if (link == null) say(T("media.what_was_shared_has"), true)
    else askLink(Home.youtubeUrl(link) ?: link) { confirmShare(link) { openLink(link); Toast.makeText(this, T("notify.sent"), Toast.LENGTH_SHORT).show() } }
}

/**
 * A link shared to us or typed in the remote's Open a link: the page's pin box (js/home_pin.js: which app or browser opens it on this
 * TV, Pin to TV Home, Try on the TV), the one copy every client shows. [keysOnly]: no ADB to ask the TV, so the link is only opened.
 */
internal fun MainActivity.askLink(url: String, keysOnly: () -> Unit) {
    val tv = tv
    if (basic != null || tv == null || !tv.ready()) { keysOnly(); return }
    managing.open(host ?: return, then = "fromRemote(() => homePin(" + org.json.JSONObject.quote(url) + "))")
}

/** Any app can hand us a file or a link: it reaches the TV only once the person has seen what and where. */
private fun MainActivity.confirmShare(what: String, send: () -> Unit) {
    Popup.Builder(this).setTitle(T("media.send_to_the_tv"))
        .setMessage(what + "\n\u2192 " + title(host ?: ""))
        .setPositiveButton(T("notify.send")) { _, _ -> send() }
        .setNegativeButton(T("common.cancel"), null).show()
}

private fun MainActivity.sharedName(uri: Uri): String = try {
    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (ignored: Exception) { null } ?: T("media.a_file")

/** A photo, video or song from this phone: copied to the TV's Download folder, then opened by the TV's own viewer or player. */
internal fun MainActivity.castFile(uri: Uri) {
    // Not tv?.ready(): the file picker stops the picture session (onStop) and it is still reopening when we get here.
    // The copy makes its own ADB connection; only the keys-only mode means there is no ADB at all.
    val ip = host
    if (ip == null || basic != null) {
        say(T("media.playing_a_file_on"), true)
        return
    }
    var name = "shared"
    var size = -1L
    try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).let { if (it >= 0) c.getString(it)?.let { n -> name = n } }
                c.getColumnIndex(android.provider.OpenableColumns.SIZE).let { if (it >= 0 && !c.isNull(it)) size = c.getLong(it) }
            }
        }
    } catch (ignored: Exception) {
    }
    if (!name.contains('.')) android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri))?.let { name += ".$it" }
    // a box with the progress and a Stop: a big video sent by mistake can be called back (the part already on the TV is deleted)
    val cancel = dev.tvremote.core.web.Cancel()
    val job = org.json.JSONObject()
    val sending = T("media.sending_to_the_tv", "name" to name)
    val box = Popup.Builder(this).setMessage("$sending...").setCancelable(false)
        .setNegativeButton(T("page.stop")) { _, _ -> cancel.stop() }.show()
    val tick = object : Runnable {
        override fun run() {
            if (!box.isShowing || isFinishing) return
            val pct = job.optInt("pct", 0)
            box.setMessage(sending + if (pct > 0) " · $pct%" else "...")
            ui.postDelayed(this, 500)
        }
    }
    ui.post(tick)
    Thread {
        val link = AdbLink(filesDir, ip)
        val err = try {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw java.io.IOException("cannot read the file")
                Manage(link, null, cacheDir).cast(input, size, name, job, cancel)
            }
            null
        } catch (e: Exception) { dev.tvremote.core.Errors.say(e, dev.tvremote.core.Words.lang) } finally { link.close() }
        ui.post {
            try { box.dismiss() } catch (ignored: Exception) { }
            if (isFinishing) return@post
            if (cancel.stopped) say(T("media.stopped_the_half_sent"), false)
            else if (err == null) say(T("media.playing_on_the_tv", "name" to name), false)
            else say(T("media.could_not_play_it", "error" to err), true)
        }
    }.start()
}

/**
 * The system photo picker: a sheet over this app (Android 13+, or older phones with a recent Google Play system update),
 * so the remote stays where it is and needs no permission to read photos. Without it, the file picker.
 */
internal fun MainActivity.pickMedia() {
    val sheet = Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)    // no type: photos and videos both
    if (Build.VERSION.SDK_INT >= 33 || sheet.resolveActivity(packageManager) != null) {
        try { startActivityForResult(sheet, PICK_CAST); return } catch (ignored: Exception) { }
    }
    // Android 12 and older: Google Play services carries the same picker under this name (as androidx PickVisualMedia uses)
    val gms = Intent("androidx.activity.result.contract.action.PICK_IMAGES")
    if (gms.resolveActivity(packageManager) != null) {
        try { startActivityForResult(gms, PICK_CAST); return } catch (ignored: Exception) { }
    }
    pickFile(arrayOf("image/*", "video/*"))                       // the full-screen file picker: the copy works all the same
}

internal fun MainActivity.pickFile(types: Array<String>) {
    try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, types).addCategory(Intent.CATEGORY_OPENABLE), PICK_CAST) }
    catch (e: Exception) { say(T("media.this_phone_has_no"), true) }
}

internal fun MainActivity.askText(title: String, hint: String, ok: String, value: String = "", then: (String) -> Unit) {
    val box = input(hint)
    if (value.isNotEmpty()) { box.setText(value); box.selectAll() }
    val wrap = inDialog(box)
    Popup.Builder(this).setTitle(title).setView(wrap)
        .setPositiveButton(ok) { _, _ -> box.text.toString().trim().let { if (it.isNotEmpty()) then(it) } }
        .setNegativeButton(T("common.cancel"), null).show()
    box.postDelayed({                                                // the keyboard up: a name is typed at once, not after a tap
        box.requestFocus()
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(box, 0)
    }, 150)
}

/** Things the TV can be told to open straight from the phone, saving the hunt with the TV's own keyboard (the entries: remote.json). */
internal fun MainActivity.quickOpen() {
    if (live()) menu(T("files.open_on_tv"), -1, remoteMenu("open"))
}

/** Open an installed app on an LG, Samsung, Roku...: their apps, from their remote link. */
internal fun MainActivity.openInstalled() {
    val b = basic ?: return
    Thread {
        val apps = try { b.apps().sortedBy { it.label.lowercase() } } catch (e: Exception) { emptyList() }
        ui.post {
            if (isFinishing || basic !== b) return@post
            menu(T("rkey.open_apps"), -1, apps.map { a -> Triple("@play|" + a.label, "") { b.launch(a.id) } })
        }
    }.start()
}
