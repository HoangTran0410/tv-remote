package dev.tvremote.phone.picker

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.ir.irAvailable
import dev.tvremote.phone.ir.irOpen
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.manage.askUpdate
import dev.tvremote.phone.manage.version
import dev.tvremote.phone.manage.versionLine
import dev.tvremote.phone.remote.kind
import dev.tvremote.phone.remote.kindOf
import dev.tvremote.phone.ui.ACCENT
import dev.tvremote.phone.ui.ACCENT_TEXT
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.CARD
import dev.tvremote.phone.ui.ERR_BG
import dev.tvremote.phone.ui.ERR_TEXT
import dev.tvremote.phone.ui.GREEN
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.OK_TEXT
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.SEL
import dev.tvremote.phone.ui.SOFT
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.col
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.gap
import dev.tvremote.phone.ui.head
import dev.tvremote.phone.ui.hideKeyboard
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.input
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.logo
import dev.tvremote.phone.ui.note
import dev.tvremote.phone.ui.row
import dev.tvremote.phone.ui.setBusy
import dev.tvremote.phone.ui.setLabel

import dev.tvremote.core.T
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import dev.tvremote.core.remote.Remote
import dev.tvremote.core.remote.Ssdp
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import dev.tvremote.core.SameTv

// The first screen: saved TVs (with whether each answers now), waking one that is off, scanning the Wi-Fi, typing an IP.

/** The box at the top of the picker: an error (red) or a progress note. */
internal fun MainActivity.message(text: String?, error: Boolean) {
    pickerMsg.text = text ?: ""
    pickerMsg.setTextColor(if (error) ERR_TEXT else TEXT)
    pickerMsg.background = box(if (error) ERR_BG else CARD, 12)
    pickerMsg.visibility = if (text == null) View.GONE else View.VISIBLE
}

// ---------------------------------------------------------------- waking a TV that is off
/**
 * Standby with the network still on: the TV answers, so connect and press "wake". Network off too: only a
 * Wake-on-LAN packet helps, and that reaches the TV from its own Wi-Fi only (never through Tailscale).
 */
internal fun MainActivity.wake(ip: String) {
    if (waking) return
    waking = true
    val mac = pref("mac:$ip")
    val lan = pref("lan:$ip")
    message(T("picker.waking", "name" to title(ip)), false)
    val port = upPort(ip)
    Thread {
        var up = open(ip, port, 1500)
        var sent = 0
        var i = 0
        val hopeless = mac.isEmpty() || !Wol.sameNetwork(lan)     // no packet can reach the TV from here: say so at once instead of waiting
        while (i < 22 && !up && !hopeless) {
            if (i % 6 == 0 && mac.isNotEmpty()) sent += Wol.send(mac, lan)
            try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            up = open(ip, port, 600) || lan.isNotEmpty() && lan != ip && Wol.sameNetwork(lan) && open(lan, port, 400)
            i++
        }
        val ok = up
        val packets = sent > 0
        val home = Wol.sameNetwork(lan)
        ui.post {
            waking = false
            if (isFinishing || host != null) return@post
            if (ok) {
                wakeNext = true
                connect(ip)
                return@post
            }
            val why = if (mac.isEmpty()) T("picker.the_app_does_not")
            else if (!packets || !home) T("picker.the_tv_s_network")
            else T("picker.wake_packets_were_sent")
            message(T("picker.could_not_wake_the", "why" to why), true)
        }
    }.start()
}

/** The port that answers while the TV is on: ADB's on an Android TV, the remote link's on an LG or Samsung TV. */
internal fun MainActivity.upPort(ip: String): Int = kindOf(ip).let { if (it == Remote.Kind.ANDROID) 5555 else Remote.Kind.ports(it).first() }

// ---------------------------------------------------------------- saved TVs
internal fun MainActivity.name(ip: String): String = pref("name:$ip")

internal fun MainActivity.savedIps(): MutableList<String> = pref("ips").split(",").filter { it.isNotEmpty() }.toMutableList()

private val PER_TV = listOf("name", "mac", "lan", "ts", "tvs", "kind") // the prefs kept per saved TV, as "<field>:<ip>" (and atv:)

internal fun MainActivity.savedTv(ip: String) = SameTv.Tv(ip, pref("lan:$ip"), pref("ts:$ip"), pref("mac:$ip"))

/** The saved TV that [ip] is another address of (its home IP, its Tailscale IP), if any. */
internal fun MainActivity.savedOwner(ip: String): String? = savedIps().firstOrNull { it != ip && ip in savedTv(it).addresses }

/** Rows that are one TV (picked once on the Wi-Fi, once over Tailscale) become one, keeping what each knew. [first]: kept. */
internal fun MainActivity.foldSaved(first: String? = null) {
    val ips = savedIps().let { l -> if (first != null && first in l) listOf(first) + (l - first) else l }
    val groups = SameTv.groups(ips) { savedTv(it) }
    if (groups.all { it.second.isEmpty() }) return
    val e = prefs.edit()
    for ((k, dups) in groups) for (d in dups) {
        for (f in listOf("name", "mac", "tvs")) if (pref("$f:$k").isEmpty() && pref("$f:$d").isNotEmpty()) e.putString("$f:$k", pref("$f:$d"))
        val addr = (savedTv(d).addresses - k)
        if (pref("lan:$k").isEmpty()) addr.firstOrNull { !it.startsWith("100.") }?.let { e.putString("lan:$k", it) }
        if (pref("ts:$k").isEmpty()) addr.firstOrNull { it.startsWith("100.") }?.let { e.putString("ts:$k", it) }
        if (prefs.getBoolean("atv:$d", false)) e.putBoolean("atv:$k", true)
        for (f in PER_TV) e.remove("$f:$d")
        e.remove("atv:$d")
    }
    e.putString("ips", TextUtils.join(",", groups.map { it.first })).apply()
}

internal fun MainActivity.save(ip: String, model: String) {
    val ips = savedIps()
    ips.remove(ip)
    ips.add(0, ip)
    val e = prefs.edit().putString("ips", TextUtils.join(",", ips))
    if (model.isNotEmpty()) e.putString("name:$ip", model)
    e.apply()
}

/** Asks each saved TV, every 8 s while the picker shows, whether it answers, and through which of its addresses. */
internal fun MainActivity.probeSaved() {
    ui.removeCallbacks(probe)
    if (host == null && !waking) for (ip in savedIps()) {
        if (reach[ip] == false && trying.add(ip)) showReach(ip, false)   // says it is trying again, not only that it failed
        Thread {
            val t0 = System.currentTimeMillis()
            // the saved address first, then the TV's other one: its Tailscale IP when away, its home IP when at home
            val via = listOf(ip, pref("ts:$ip"), pref("lan:$ip")).filter { it.isNotEmpty() }.distinct()
                .firstOrNull { h -> kindOf(ip).let { k -> if (k != Remote.Kind.ANDROID) Remote.Kind.ports(k).any { open(h, it, 1500) } else open(h, 5555, 2500) || prefs.getBoolean("atv:$ip", false) && open(h, 6466, 1500) } }
            val up = via != null
            if (!up) try { Thread.sleep((1000 - (System.currentTimeMillis() - t0)).coerceAtLeast(0)) } catch (e: InterruptedException) { }   // a refused port answers at once: "trying again" must still be seen
            val lan = pref("lan:$ip")
            val away = lan.isNotEmpty() && !Wol.sameNetwork(lan)   // the phone is not on the TV's own Wi-Fi
            ui.post { trying.remove(ip); if (via != null) route[ip] = via else route.remove(ip); reach[ip] = up; awayFrom[ip] = away; showReach(ip, up) }
        }.start()
    }
    ui.postDelayed(probe, 8000)
    if (host == null && !waking) statusStart()                // TvStatus.kt: on / standby / app under each name, its own 10 s round
}

/** Asks the saved TVs again as soon as the Wi-Fi comes back, a VPN goes up or down, or the phone moves to the mobile data and back,
 *  instead of after the next 8 s round (or never, when the old answer is what the user keeps reading). */
internal fun MainActivity.watchNetworks(on: Boolean) {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    netWatch?.let { try { cm.unregisterNetworkCallback(it) } catch (e: Exception) { } }
    netWatch = null
    if (!on) return
    val again = Runnable { if (host == null) probe.run() }
    val cb = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: android.net.Network) { ui.removeCallbacks(again); ui.postDelayed(again, 1500) }   // a burst of changes: one round
        override fun onLost(n: android.net.Network) { ui.removeCallbacks(again); ui.postDelayed(again, 1500) }
    }
    try { cm.registerNetworkCallback(android.net.NetworkRequest.Builder().build(), cb); netWatch = cb } catch (e: Exception) { }
}

/** The plain line under a saved TV: still asking, answering (and through which address), or not. */
internal fun MainActivity.reachWords(ip: String, up: Boolean?): String = when (up) {
    null -> T("picker.checking")
    false -> if (ip in trying) T("picker.trying_again") else if (awayFrom[ip] == true) T("picker.no_answer_you_are") else T("picker.no_answer_tv_off")
    true -> route[ip].let { r -> if (r == null || r == ip) T("picker.reachable")
        else if (r.startsWith("100.")) T("picker.reachable_through_tailscale") else T("picker.reachable_at_home") }
}

internal fun MainActivity.showReach(ip: String, up: Boolean?) {
    val status = reachWords(ip, up)
    TvStatus.cards[ip]?.let { showCard(ip, it, up, status); return }   // "Show screens": a picture card (TvStatus.kt)
    val (b, wk) = savedRows[ip] ?: return
    val more = if (up == false) null else statusLines(ip)       // a TV that is on or in standby says so, with its model and the app (TvStatus.kt)
    val shown = more?.first ?: status
    val facts = more?.second.orEmpty()
    val text = android.text.SpannableString((if (name(ip).isEmpty()) ip else name(ip) + "\n" + ip) + "\n" + shown + (if (facts.isNotEmpty()) "\n" + facts else ""))
    val from = text.length - shown.length - (if (facts.isNotEmpty()) facts.length + 1 else 0)
    text.setSpan(android.text.style.ForegroundColorSpan(if (up == true) OK_TEXT else MUTED), from, from + shown.length, 0)
    text.setSpan(android.text.style.RelativeSizeSpan(0.82f), from, text.length, 0)
    if (facts.isNotEmpty()) text.setSpan(android.text.style.ForegroundColorSpan(MUTED), from + shown.length, text.length, 0)
    b.text = text
    wk.visibility = if (up == false) View.VISIBLE else View.GONE
}

internal fun MainActivity.askRemove(ip: String) {
    Popup.Builder(this).setMessage(T("picker.remove_this_tv_from") + "\n" + title(ip) + (if (name(ip).isEmpty()) "" else " · $ip") + "\n\n" +
            T("picker.nothing_changes_on_the"))
        .setPositiveButton(T("picker.remove")) { _, _ ->
            val ips = savedIps()
            ips.remove(ip)
            prefs.edit().putString("ips", TextUtils.join(",", ips)).also { e -> PER_TV.forEach { e.remove("$it:$ip") } }.remove("atv:$ip").apply()
            refreshSaved()
        }.setNegativeButton(T("common.cancel"), null).show()
}

internal fun MainActivity.refreshSaved() {
    foldSaved()
    savedList.removeAllViews()
    savedRows.clear()
    TvStatus.thumbs.clear()
    TvStatus.cards.clear()
    if (TvStatus.shots && savedIps().isNotEmpty()) screenCards(savedIps())   // TvStatus.kt: a grid of pictures, two a row
    else for (ip in savedIps()) {
        val b = row("") { connectSaved(ip) }
        val wk = icon("@power", T("remote.wake"), BTN)        // only offered while the TV does not answer
        wk.setOnClickListener { wake(ip) }
        wk.visibility = View.GONE
        savedRows[ip] = Pair(b, wk)
        showReach(ip, reach[ip])
        val line = LinearLayout(this)
        line.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        val wp = LinearLayout.LayoutParams(dp(60), -1)
        wp.leftMargin = dp(6)
        line.addView(wk, wp)
        line.layoutParams = gap(6)
        val ask = { askRemove(ip) }
        val del = icon("@close", T("picker.remove_this_tv_from_the"), BTN)
        del.setOnClickListener { ask() }
        val dp2 = LinearLayout.LayoutParams(dp(48), -1)
        dp2.leftMargin = dp(6)
        line.addView(del, dp2)
        b.setOnLongClickListener { ask(); true }
        savedList.addView(line)
    }
    fleetBtn.visibility = if (savedIps().size >= 2) View.VISIBLE else View.GONE
    screensBtn.visibility = if (savedIps().isNotEmpty()) View.VISIBLE else View.GONE
    showThumbs()
    if (savedList.childCount == 0) savedList.addView(note(T("picker.no_tvs_yet_scan")))
}

// ---------------------------------------------------------------- finding TVs on the Wi-Fi
private fun MainActivity.foundRow(ip: String, name: String, adb: Boolean) = row((if (name.isNotEmpty()) name + "\n" else "") + ip + " · " +
    (if (adb) T("setup.adb_on") else T("picker.adb_off_keys_only"))) { prefs.edit().remove("kind:$ip").apply(); connect(ip) }

/** An LG or Samsung TV the SSDP search found: its own remote link, no ADB. */
private fun MainActivity.foundRow(f: Ssdp.Found) = row((if (f.name.isNotEmpty()) f.name + "\n" else "") + f.ip + " · TV " + Remote.Kind.name(f.kind) + T(if (f.kind == Remote.Kind.VIDAA) "picker.beta_vidaa" else "picker.beta")) {
    prefs.edit().putString("kind:${f.ip}", f.kind).apply(); connect(f.ip)
}

private val MOBILE = Regex("^(rmnet|ccmni|pdp|v4-|clat|seth|wwan|tun|ppp|ipsec)")   // mobile data and VPNs; Wi-Fi, Ethernet and the hotspot stay

internal fun MainActivity.scan() {
    scanBtn.isEnabled = false
    scanBtn.setBusy(T("picker.scanning"))
    foundList.removeAllViews()
    probe.run()                                                   // the saved TVs are asked again too: the button is also "refresh"
    val saved = savedIps().toSet()
    Thread {
        val nets = ArrayList<String>()
        val found = Collections.synchronizedList(ArrayList<String>())
        try {
            val add = { a: java.net.InetAddress? ->
                val ip = a?.hostAddress
                if (ip != null && a is Inet4Address && a.isSiteLocalAddress) ip.substring(0, ip.lastIndexOf('.') + 1).let { if (it !in nets) nets.add(it) }
            }
            try {
                for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp || ni.isLoopback || MOBILE.containsMatchIn(ni.name)) continue   // 4G's 10.x is the carrier's, no TV there
                    for (a in Collections.list(ni.inetAddresses)) add(a)
                }
            } catch (ignored: Exception) {
            }
            try {                                                 // a second source: with a VPN (Tailscale) on, the list above can miss the Wi-Fi
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                @Suppress("DEPRECATION")
                for (n in cm.allNetworks) if (cm.getNetworkCapabilities(n)?.let { c -> c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) } == true) cm.getLinkProperties(n)?.linkAddresses?.forEach { add(it.address) }
            } catch (ignored: Exception) {
            }
            val lan = lanNetwork()
            val seen = { ip: String ->
                if (synchronized(found) { if (ip in found) false else found.add(ip) }) {
                    val adb = open(ip, 5555, 900, lan)
                    if (ip in saved && adb && host == null) ui.post { route[ip] = ip; reach[ip] = true; showReach(ip, true) }   // found here: not "no answer" above
                    val n = name(ip).ifEmpty { dev.tvremote.core.remote.Ssdp.castName(ip) }
                    ui.post { foundList.addView(foundRow(ip, n, adb)) }   // shown as soon as it answers, not after the whole network
                }
            }
            val stopMdns = announced { ip -> Thread { seen(ip) }.start() }   // TVs also announce themselves; this finds the slow ones
            val ssdp = Thread { Ssdp.find(3000, { so -> if (android.os.Build.VERSION.SDK_INT >= 22) lan?.bindSocket(so) }) { f -> if (synchronized(found) { if (f.ip in found) false else found.add(f.ip) }) ui.post { foundList.addView(foundRow(f)) } } }.apply { start() }   // LG and Samsung TVs answer this
            val pool = Executors.newFixedThreadPool(96)
            for (net in nets) for (i in 1 until 255) {
                val ip = net + i
                pool.execute { if (open(ip, 6466, 900, lan) || open(ip, 5555, 900, lan)) seen(ip) }
            }
            pool.shutdown()
            pool.awaitTermination(25, TimeUnit.SECONDS)
            ssdp.join(5000)
            stopMdns()
        } catch (ignored: Exception) {
        }
        ui.post {
            scanBtn.isEnabled = true
            scanBtn.setLabel("@search|" + T("picker.scan_the_wi_fi"))
            if (foundList.childCount == 0) foundList.addView(note(
                if (nets.isEmpty()) T("picker.the_phone_is_not")
                else T("picker.scanned", "nets" to nets.joinToString(", ") { it + "x" })))
        }
    }.start()
}

// ---------------------------------------------------------------- picker screen
internal fun MainActivity.buildPicker() {
    picker = col()
    picker.setPadding(dp(16), dp(20), dp(16), dp(24))
    val title = label("TV Remote", 24, TEXT)
    title.typeface = Typeface.DEFAULT_BOLD
    val top = LinearLayout(this)
    top.gravity = Gravity.CENTER_VERTICAL
    val names = col()
    names.addView(title)
    names.addView(label(T("picker.version", "v" to versionLine()), 12, MUTED))
    top.addView(logo(40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
    top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
    val set = icon("@settings", T("page.settings"), BTN)
    set.setOnClickListener {
        hideKeyboard()
        settings.visibility = View.VISIBLE
    }
    top.addView(set, LinearLayout.LayoutParams(dp(52), dp(44)))
    picker.addView(top)
    pickerMsg = label("", 14, ERR_TEXT)
    pickerMsg.background = box(ERR_BG, 12)
    pickerMsg.setPadding(dp(12), dp(10), dp(12), dp(10))
    pickerMsg.visibility = View.GONE
    picker.addView(pickerMsg, gap(12))
    updateBtn = button("", GREEN)
    updateBtn.setOnClickListener { askUpdate() }
    updateBtn.visibility = View.GONE
    val up = LinearLayout.LayoutParams(-1, dp(48))
    up.topMargin = dp(12)
    picker.addView(updateBtn, up)

    val savedHead = LinearLayout(this)                                // the heading, with "Several TVs" at its end once there are two
    savedHead.gravity = android.view.Gravity.CENTER_VERTICAL
    savedHead.addView(head(T("picker.your_tvs")), LinearLayout.LayoutParams(0, -2, 1f))
    fleetBtn = fleetButton()
    screensBtn = screensButton()
    savedHead.addView(screensBtn, LinearLayout.LayoutParams(dp(42), dp(34)).apply { rightMargin = dp(6) })
    savedHead.addView(fleetBtn, LinearLayout.LayoutParams(-2, dp(34)))
    picker.addView(savedHead, gap(14))
    savedList = col()
    picker.addView(savedList)
    if (irAvailable()) {                                              // no network, no TV picked: the phone's IR window (IrRemote.kt)
        val ir = button("@pulse|" + T("ir.title"), BTN)
        ir.setOnClickListener { irOpen() }
        picker.addView(ir, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
    }

    picker.addView(head(T("picker.find_on_the_wi")), gap(18))
    scanBtn = button("@search|" + T("picker.scan_the_wi_fi"), ACCENT)
    scanBtn.setOnClickListener { scan() }
    val sp = LinearLayout.LayoutParams(-1, dp(48))
    sp.topMargin = dp(6)
    picker.addView(scanBtn, sp)
    foundList = col()
    picker.addView(foundList)

    picker.addView(head(T("picker.type_the_tv_s")), gap(18))
    val line = LinearLayout(this)
    ipBox = input(T("picker.e_g_192_168"))
    ipBox.inputType = InputType.TYPE_CLASS_PHONE
    ipBox.imeOptions = EditorInfo.IME_ACTION_GO
    ipBox.setOnEditorActionListener { _, _, _ ->
        connect(ipBox.text.toString())
        true
    }
    val go = icon("@right", T("page.connect"), ACCENT)
    go.setOnClickListener { connect(ipBox.text.toString()) }
    line.addView(ipBox, LinearLayout.LayoutParams(0, dp(48), 1f))
    val lp = LinearLayout.LayoutParams(-2, dp(48))
    lp.leftMargin = dp(8)
    lp.width = dp(60)
    line.addView(go, lp)
    picker.addView(line, gap(6))
    val help = button(T("picker.how_to_connect"), 0)
    help.setTextColor(ACCENT_TEXT)
    help.setOnClickListener {
        Popup.Builder(this).setTitle(T("picker.how_to_connect")).setView(howSteps(T("picker.1_turn_on_adb"), T("setup.tcl_hidden_adb_short")))
            .setPositiveButton(T("setup_banner.got_it"), null).show()
    }
    picker.addView(help, LinearLayout.LayoutParams(-1, dp(44)))
}

/**
 * How to connect, as numbered steps: the words are blocks split by a blank line, a block of several lines opens a step (its
 * first line the title, "1. ..." giving the badge), a one-line block goes on in the step before. [firstNote] sits in step 1,
 * set apart (TCL China's hidden switch).
 */
private fun MainActivity.howSteps(words: String, firstNote: String): View {
    val list = col()
    list.setPadding(dp(16), dp(6), dp(16), dp(2))
    var body: LinearLayout? = null
    for (block in words.split("\n\n")) {
        val lines = block.trim().split("\n")
        val b = body
        if (lines.size == 1 && b != null) { b.addView(label(lines[0], 14, MUTED).apply { setLineSpacing(dp(2).toFloat(), 1f) }, gap(6)); continue }
        val m = Regex("^(\\w{1,2})\\.\\s+(.*)").find(lines[0])
        val step = LinearLayout(this)
        step.background = box(BTN, 14)
        step.setPadding(dp(12), dp(12), dp(14), dp(12))
        val badge = label(m?.groupValues?.get(1) ?: "💡", 14, ACCENT_TEXT).apply {
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; background = box(SOFT, 15)
        }
        step.addView(badge, LinearLayout.LayoutParams(dp(30), dp(30)))
        val c = col()
        c.addView(label(m?.groupValues?.get(2) ?: lines[0], 15, TEXT).apply { typeface = Typeface.DEFAULT_BOLD })
        if (lines.size > 1) c.addView(label(lines.drop(1).joinToString("\n"), 14, MUTED).apply { setLineSpacing(dp(2).toFloat(), 1f) }, gap(4))
        if (list.childCount == 0) c.addView(label(firstNote, 13, TEXT).apply {
            background = box(SEL, 10); setPadding(dp(10), dp(8), dp(10), dp(8))
        }, gap(8))
        step.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        list.addView(step, if (list.childCount == 0) LinearLayout.LayoutParams(-1, -2) else gap(8))
        body = c
    }
    return list
}
