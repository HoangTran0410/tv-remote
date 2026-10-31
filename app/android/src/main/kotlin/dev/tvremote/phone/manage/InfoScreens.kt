package dev.tvremote.phone.manage

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.R
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.media.setTalkMode
import dev.tvremote.phone.picker.message
import dev.tvremote.phone.remote.kind
import dev.tvremote.phone.ui.ACCENT
import dev.tvremote.phone.ui.ACCENT_TEXT
import dev.tvremote.phone.ui.BG
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.cell
import dev.tvremote.phone.ui.chips
import dev.tvremote.phone.ui.col
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.gap
import dev.tvremote.phone.ui.head
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.note
import dev.tvremote.phone.ui.row
import dev.tvremote.phone.ui.setInk
import dev.tvremote.phone.ui.setLabel
import dev.tvremote.phone.ui.setOn
import dev.tvremote.phone.ui.setToggle

import dev.tvremote.core.T
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

// The Settings and About screens: plain pages over the picker.

// ---------------------------------------------------------------- settings screen
internal fun MainActivity.buildSettings(): View {
    val box = col()
    box.setPadding(dp(16), dp(20), dp(16), dp(24))
    val title = label(T("page.settings"), 24, TEXT)
    title.typeface = Typeface.DEFAULT_BOLD
    val top = LinearLayout(this)
    top.gravity = Gravity.CENTER_VERTICAL
    top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
    val done = icon("@check", T("drawing.done"), ACCENT)
    done.setOnClickListener { settings.visibility = View.GONE }
    top.addView(done, LinearLayout.LayoutParams(dp(52), dp(44)))
    box.addView(top)

    box.addView(head(T("info_screens.language")), gap(18))
    val now = pref("lang")
    val langs = listOf("" to "@phone|" + T("info_screens.same_as_the_phone")) + dev.tvremote.i18n.Langs.NAMES.map { (c, n) -> c to "@globe|$n" }
    for ((code, words) in langs) {
        val b = row(words + (if (code == now) "   ✓" else "")) {
            if (code == now) return@row
            prefs.edit().putString("lang", code).apply()
            recreate()                                            // every label is built in onCreate
        }
        if (code == now) b.setInk(ACCENT_TEXT)
        box.addView(b)
    }

    box.addView(head(T("info_screens.appearance")), gap(18))
    val theme = pref("theme").ifEmpty { "auto" }
    val modes = LinearLayout(this)
    for ((i, m) in listOf("auto" to T("info_screens.auto"), "light" to T("info_screens.light"), "dark" to T("info_screens.dark")).withIndex()) {
        val on = m.first == theme
        val art = mapOf("auto" to "contrast", "light" to "wake", "dark" to "moon")[m.first]
        val b = button("@$art|" + m.second, BTN).apply { setOn(on) }
        b.setPadding(dp(6), 0, dp(6), 0)
        b.contentDescription = m.second
        b.setOnClickListener {
            if (on) return@setOnClickListener
            prefs.edit().putString("theme", m.first).apply()
            recreate()                                            // the colours are read while the views are built
        }
        modes.addView(b, cell(44, i > 0))
    }
    box.addView(modes, gap(6))

    box.addView(head(T("talk.setting_head")), gap(18))
    val talkBox = android.widget.FrameLayout(this)
    lateinit var talkChips: () -> Unit
    talkChips = {
        talkBox.removeAllViews()
        talkBox.addView(chips(listOf("quick" to T("talk.mode_quick_short"), "old" to T("talk.mode_old_short")), pref("talkMode")) { setTalkMode(it); talkChips() })
    }
    talkChips()
    box.addView(talkBox, gap(6))

    box.addView(head(T("info_screens.the_phone_s_volume")), gap(18))
    lateinit var vol: Button
    val volLabel = { vol.setToggle(prefs.getBoolean("volKeysTv", true), T("info_screens.set_the_tv_s")) }
    vol = row("") {
        prefs.edit().putBoolean("volKeysTv", !prefs.getBoolean("volKeysTv", true)).apply()
        volLabel()
    }
    volLabel()
    box.addView(vol)

    box.addView(head(T("info_screens.when_pressing_a_key")), gap(18))
    lateinit var buzzRow: Button
    val buzzLabel = { buzzRow.setToggle(prefs.getBoolean("buzz", true), T("about.vibrate")) }
    buzzRow = row("") {
        prefs.edit().putBoolean("buzz", !prefs.getBoolean("buzz", true)).apply()
        buzzLabel()
        buzz()
    }
    buzzLabel()
    box.addView(buzzRow)

    box.addView(head(T("info_screens.tv_keyboard")), gap(18))
    lateinit var kbdRow: Button
    val kbdLabel = { kbdRow.setToggle(prefs.getBoolean("kbdAuto", true), T("about.tv_keyboard_auto")) }
    kbdRow = row("") {
        prefs.edit().putBoolean("kbdAuto", !prefs.getBoolean("kbdAuto", true)).apply()
        kbdLabel()
    }
    kbdLabel()
    box.addView(kbdRow)

    box.addView(head(T("info_screens.updates")), gap(18))
    lateinit var auto: Button
    val autoLabel = { auto.setToggle(prefs.getBoolean("autoUpdate", true), T("info_screens.check_for_updates_automatically")) }
    auto = row("") {
        prefs.edit().putBoolean("autoUpdate", !prefs.getBoolean("autoUpdate", true)).apply()
        autoLabel()
    }
    autoLabel()
    box.addView(auto)
    box.addView(row("@refresh|" + T("info_screens.check_now")) { checkUpdate(true) })
    updateNote = note("")
    box.addView(updateNote)

    box.addView(head(T("info_screens.privacy")), gap(18))        // the anonymous census (core/Census.kt): the same switch as the page's Settings
    lateinit var censusRow: Button
    val censusLabel = { censusRow.setToggle(dev.tvremote.core.Census.enabled, T("about.census")) }
    censusRow = row("") {
        dev.tvremote.core.Census.enabled = !dev.tvremote.core.Census.enabled
        censusLabel()
    }
    censusLabel()
    box.addView(censusRow)
    box.addView(note(T("about.census_note")))

    box.addView(head(T("info_screens.about")), gap(18))
    box.addView(row("@info|" + T("about.about")) { about.visibility = View.VISIBLE })

    val sv = ScrollView(this)
    sv.setBackgroundColor(BG)
    sv.isClickable = true                                         // do not let touches fall through to the screen below
    sv.addView(box)
    sv.visibility = View.GONE
    return sv
}

// ---------------------------------------------------------------- about screen
internal fun MainActivity.web(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {                                      // no browser on this device
        message(url, false)
    }
}

internal fun MainActivity.buildAbout(): View {
    val box = col()
    box.setPadding(dp(16), dp(20), dp(16), dp(24))
    val title = label(T("about.about"), 24, TEXT)
    title.typeface = Typeface.DEFAULT_BOLD
    val top = LinearLayout(this)
    top.gravity = Gravity.CENTER_VERTICAL
    top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
    val done = icon("@check", T("drawing.done"), ACCENT)
    done.setOnClickListener { about.visibility = View.GONE }
    top.addView(done, LinearLayout.LayoutParams(dp(52), dp(44)))
    box.addView(top)

    val logo = ImageView(this)
    logo.setImageResource(R.drawable.icon)
    val lp = LinearLayout.LayoutParams(dp(84), dp(84))
    lp.gravity = Gravity.CENTER_HORIZONTAL
    lp.topMargin = dp(22)
    box.addView(logo, lp)
    val name = label("TV Remote " + versionLine(), 20, TEXT)
    name.typeface = Typeface.DEFAULT_BOLD
    name.gravity = Gravity.CENTER
    box.addView(name, gap(10))
    // The app's own download link as a code: someone next to you scans it and has the app
    val appLink = "https://tvremote.pages.dev/dl/tv-remote.apk"         // the site sends it on to the latest release (docs/_worker.js): a smaller QR
    dev.tvremote.core.web.Qr.bitmap(appLink, 6)?.let { bmp ->
        val qr = ImageView(this)
        qr.setImageBitmap(bmp)
        qr.contentDescription = T("info_screens.qr_code_to_download")
        val qp = LinearLayout.LayoutParams(dp(170), dp(170))
        qp.gravity = Gravity.CENTER_HORIZONTAL
        qp.topMargin = dp(14)
        box.addView(qr, qp)
        val share = button(T("info_screens.share_the_download_link"), BTN)
        share.setOnClickListener { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, appLink), null)) }
        box.addView(share, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
    }

    box.addView(head(T("info_screens.author")), gap(22))
    box.addView(row("@user|Hoang Tran\n@HoangTran0410") { web("https://github.com/HoangTran0410") })

    // the site, not GitHub: a short address to read and tap, and where it leads can change without a new app
    box.addView(head(T("info_screens.website")), gap(18))
    box.addView(row("@globe|tvremote.pages.dev") { web("https://tvremote.pages.dev") })
    box.addView(row("@info|" + T("info_screens.guide_link")) { web("https://tvremote.pages.dev/guide") })
    box.addView(row("@package|" + T("about.releases")) { web("https://tvremote.pages.dev/#install") })
    // what the TVs said and the errors shown (WireLog): what a real TV did is what tells a bug apart. Copied, then the
    // site's page where it is pasted and sent on (a GitHub issue or a message), as on the page (js/core.js sendWireLog)
    box.addView(row("@clipboard|" + T("info_screens.send_the_log")) {
        val log = dev.tvremote.core.WireLog.all.report(basic?.takeIf { it.kind != dev.tvremote.core.remote.Remote.Kind.ANDROID })
        (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("TV Remote", log))
        Toast.makeText(this, T("core.log_copied"), Toast.LENGTH_LONG).show()
        web("https://tvremote.pages.dev/log")
    })

    // let support look at this app for 30 min (PhoneSupport): the same code and Stop as the Manage screen's About
    lateinit var sup: android.widget.Button
    fun supMark(o: org.json.JSONObject) = sup.setLabel("@eye|" + if (o.optBoolean("open")) T("support.stop_sub", "code" to o.optString("code"), "min" to o.optLong("min"))
        else T("support.app_open", "min" to dev.tvremote.core.SupportRelay.MINUTES))
    fun supShow(o: org.json.JSONObject) {
        supMark(o)
        when (o.optString("failed")) {
            "" -> if (o.optBoolean("open")) Popup.Builder(this).setTitle(T("support.code_title") + ": " + o.optString("code").chunked(4).joinToString(" "))
                .setMessage(T("support.read_out_stop", "min" to o.optLong("min")))
                .setPositiveButton(T("common.close"), null)
                .setNegativeButton(T("support.stop")) { _, _ -> Thread { val s = PhoneSupport.act(this, "stop"); ui.post { supMark(s); Toast.makeText(this, T("support.stopped"), Toast.LENGTH_SHORT).show() } }.start() }
                .show()
            "none_left" -> Toast.makeText(this, T("support.none_left"), Toast.LENGTH_LONG).show()
            else -> Toast.makeText(this, T("support.no_net_here"), Toast.LENGTH_LONG).show()
        }
    }
    sup = row("") {
        sup.isEnabled = false
        Thread { val s = PhoneSupport.act(this, "state").takeIf { it.optBoolean("open") } ?: PhoneSupport.act(this, "start"); ui.post { sup.isEnabled = true; supShow(s) } }.start()
    }
    box.addView(sup)
    box.addView(note(T("support.app_sub")))
    supMark(PhoneSupport.relay(this).state())

    box.addView(head(T("info_screens.credits")), gap(18))
    box.addView(row("scrcpy · Apache-2.0") { web("https://github.com/Genymobile/scrcpy") })

    val sv = ScrollView(this)
    sv.setBackgroundColor(BG)
    sv.isClickable = true
    sv.addView(box)
    sv.visibility = View.GONE
    return sv
}
