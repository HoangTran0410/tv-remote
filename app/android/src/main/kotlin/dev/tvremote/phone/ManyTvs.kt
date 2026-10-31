package dev.tvremote.phone

import dev.tvremote.core.T
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.tvremote.core.adb.AdbLink
import dev.tvremote.core.Errors
import dev.tvremote.core.Fleet
import dev.tvremote.core.manage.Manage
import dev.tvremote.core.ontv.RemoteLabels
import dev.tvremote.core.ontv.Checkout
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

// 📦 Several TVs at once, from the list of saved TVs (homestay, hotel, a house of TVs): one APK installed on each TV ticked,
// one app removed, or a restart, with a line per TV saying how it goes. The computer's page has the same on its own list of
// TVs (app/desktop Fleet.kt); TV Setup has no list, it manages only the TV it runs on.

private const val PARALLEL = 3                                        // an APK goes over the home Wi-Fi to each TV: a few at a time

/** One TV in a run: what its line shows, read by the ticker on the UI thread. */
private class Part(val ip: String, val line: TextView) {
    @Volatile var step = ""
    @Volatile var raw = ""                                            // what the work answered (the check-out's result token)
    @Volatile var state = 0                                           // 0 working, 1 done, 2 failed, 3 stopped by the person
    val cancel = dev.tvremote.core.web.Cancel()                           // the Stop button of the run reaches every TV through this
    @Volatile var link: AdbLink? = null
    val job = JSONObject()                                            // Manage.install puts the copy's progress here
}

/** The TV could not be reached at all: said as is, not through Errors (whose "network is slow" would mislead here). */
private class Unreachable(msg: String) : Exception(msg)

private fun MainActivity.toast(text: String) = Toast.makeText(this, dev.tvremote.tvui.Marks.plain(text), Toast.LENGTH_SHORT).show()   // a toast has no icons: no marks

/** Beside the heading of the saved TVs once there are two of them: small, in the accent colour, so it is seen without a whole row. */
internal fun MainActivity.fleetButton(): android.widget.Button {
    val b = button("@tv|" + T("many_tvs.several_tvs"), SOFT)
    b.setInk(ACCENT_TEXT)
    b.textSize = 14f
    b.typeface = android.graphics.Typeface.DEFAULT_BOLD
    b.setPadding(dp(12), 0, dp(12), 0)
    b.contentDescription = T("many_tvs.several_tvs_at_once")
    b.setOnClickListener { fleetMenu() }
    return b
}

private fun MainActivity.fleetMenu() = menu(T("many_tvs.several_tvs_at_once_2"), 0, listOf(
    Triple("@package|" + T("many_tvs.install_an_apk_on"),
        "") {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream")), PICK_FLEET)
        } catch (e: Exception) { toast(T("media.this_phone_has_no")) }
    },
    Triple("@home|" + T("fleet.menu_welcome"), "") {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*")), PICK_FLEET_WELCOME)
        } catch (e: Exception) { toast(T("media.this_phone_has_no")) }
    },
    Triple("@close|" + T("fleet.menu_welcome_off"), "") {
        fleetPick(T("fleet.ask_welcome_off")) { ips ->
            fleetRun(T("fleet.menu_welcome_off"), ips) { link, _ ->
                Manage(link, null, cacheDir).welcomeBulk(null, 0, "", JSONObject().put("on", false), null, null); T("fleet.step_welcome_off")
            }
        }
    },
    Triple("@close|" + T("many_tvs.remove_apps"), "") {
        fleetPick(T("many_tvs.remove_apps_on_which")) { fleetUninstall(it) }
    },
    Triple("@tv|" + T("fleet.menu_launcher"), "") { fleetLauncher() },
    Triple("@power|" + T("fleet.menu_restart"), "") {
        fleetPick(T("many_tvs.restart_which_tvs")) { ips ->
            fleetRun(T("fleet.menu_restart"), ips) { link, _ -> Fleet.reboot(link.get()); T("many_tvs.restarting") }
        }
    },
    Triple("@sparkle|" + T("fleet.menu_checkout"), T("fleet.menu_checkout_sub")) {
        fleetPick(T("fleet.menu_checkout")) { fleetCheckout(it) }
    }))

/** The result of a check-out on one TV as the TV's line: the same pieces, in the same words, as js/checkout.js (a TV Setup too old is said). */
private fun checkoutLine(r: Checkout.Result): String = listOfNotNull(
    if (r.cleared > 0 || r.failed.isEmpty()) T("fleet.co_cleared", "n" to r.cleared) else null,
    if (r.failed.isNotEmpty()) T("fleet.co_failed", "n" to r.failed.size) else null,
    T("fleet.co_vol", "v" to r.volume),
    when (r.web) {
        "ok" -> if (r.forgot > 0) T("fleet.co_web_ok", "n" to r.forgot) else T("fleet.co_web_0")
        "old" -> T("fleet.co_web_old")
        "missing" -> T("fleet.co_web_missing")
        else -> T("fleet.co_web_failed")
    },
    if (r.home) T("fleet.co_home") else null).joinToString(" · ")

/** A step under way ("checkout clear 2/5", Checkout.run's [say]) in words. */
private fun checkoutStep(msg: String): String {
    val m = Regex("checkout (clear|volume|web|home)(?: (\\d+)/(\\d+))?").matchEntire(msg) ?: return msg
    return when (m.groupValues[1]) {
        "clear" -> T("fleet.co_step_clear", "i" to (m.groupValues[2].toIntOrNull() ?: 0), "n" to (m.groupValues[3].toIntOrNull() ?: 0))
        "volume" -> T("fleet.co_step_volume")
        "web" -> T("fleet.co_step_web")
        else -> T("fleet.co_step_home")
    }
}

/**
 * Check-out (Trả phòng, Checkout.kt) on the TVs ticked: asks each for its apps, then which to clear (what was chosen last time, else the
 * suggestions that are installed, come ticked), the volume, and one last question: it signs the guest out and cannot be undone.
 */
private fun MainActivity.fleetCheckout(ips: List<String>) {
    val wait = Popup.Builder(this).setMessage(T("fleet.asking_each")).setCancelable(false).show()
    val apps = HashMap<String, Int>()                                 // package -> how many TVs have it
    val names = HashMap<String, String>()
    val answered = HashSet<String>()
    val pool = Executors.newFixedThreadPool(PARALLEL)
    for (ip in ips) pool.execute {
        val link = AdbLink(filesDir, route[ip] ?: ip)
        try {
            val adb = link.get()
            val info = Fleet.info(adb)
            val mine = (info.apps + Checkout.here { adb.shell(it) }).distinct()           // a system YouTube counts too
            val rl = RemoteLabels(link, { assets.open("tvr-helper.jar").use { it.readBytes() } }, File(cacheDir, "meta/" + ip.replace(Regex("[^\\w.-]"), "_")))
            val label = try { rl.names() } catch (e: Exception) { emptyMap() }
            synchronized(apps) { mine.forEach { apps[it] = (apps[it] ?: 0) + 1 }; names.putAll(label); answered.add(ip) }
        } catch (ignored: Exception) {                                // a TV that does not answer is told so on its own line when the run starts
        } finally { link.close() }
    }
    pool.shutdown()
    Thread {
        pool.awaitTermination(3, java.util.concurrent.TimeUnit.MINUTES)
        ui.post {
            wait.dismiss()
            if (isFinishing) return@post
            checkoutNames = HashMap(names)
            val saved = try { prefs.getString("checkout", null)?.let { JSONObject(it) } } catch (e: Exception) { null }
            val cfg = Checkout.Config.of(saved)
            val want = (cfg.apps ?: Checkout.SUGGEST.keys.toList()).toSet()
            val list = apps.keys.filter { Checkout.blocked(it) == null }
                .sortedWith(compareBy({ it !in want }, { -(apps[it] ?: 0) }, { (names[it] ?: it).lowercase() }))
            val labels = list.map { (names[it] ?: it.substringAfterLast('.')) + "  ·  " + T("fleet.on_tvs", "n" to (apps[it] ?: 0), "total" to answered.size) }.toTypedArray()
            val on = BooleanArray(list.size) { list[it] in want }
            val pickVolume = { chosen: List<String> ->
                val levels = listOf(10, 25, 40, 60)
                menu(T("fleet.co_volume"), levels.indexOf(cfg.volume), levels.map { v ->   // the filled one is the level kept from last time, as the page's chosen chip
                    Triple("@vol_up|$v%", "") { fleetCheckoutAsk(ips, Checkout.Config(chosen, v)) }
                })
            }
            val box = Popup.Builder(this).setTitle(T("fleet.co_apps"))
            if (list.isEmpty()) box.setMessage(T("fleet.co_none")) else box.setMultiChoiceItems(labels, on) { _, i, c -> on[i] = c }
            box.setPositiveButton(T("draw.next")) { _, _ -> pickVolume(list.filterIndexed { i, _ -> on[i] }) }
                .setNegativeButton(T("common.cancel"), null).show()
        }
    }.start()
}

private fun MainActivity.fleetCheckoutAsk(ips: List<String>, cfg: Checkout.Config) {
    Popup.Builder(this).setTitle(T("fleet.menu_checkout"))
        .setMessage(T("fleet.co_ask", "n" to ips.size, "apps" to (cfg.apps?.size ?: 0)) + "\n\n" + ips.joinToString("\n") { "• " + title(it) })
        .setPositiveButton(T("fleet.co_go", "n" to ips.size)) { _, _ ->
            prefs.edit().putString("checkout", cfg.json().toString()).apply()   // remembered for the next check-out
            fleetRun(T("fleet.menu_checkout"), ips, checkout = true) { link, p ->
                val adb = link.get()
                Checkout.run({ adb.shell(it) }, cfg) { p.step = checkoutStep(it) }.token()   // the result card reads it; the line is made from it
            }
        }.setNegativeButton(T("common.cancel"), null).show()
}

/** A home screen from the gallery on several TVs: one download (kept in the cache), each TV checked and put back on its own if its home does not hold. */
private fun MainActivity.fleetLauncher() {
    dev.tvremote.core.ontv.Launchers.useTestFile(getExternalFilesDir(null))   // a test list, when one was put there
    dev.tvremote.core.ontv.Launchers.dir = java.io.File(cacheDir, "launchers")   // the gallery's list and pictures kept: it opens without waiting on the network
    val wait = Popup.Builder(this).setMessage(T("launcher.loading")).setCancelable(false).show()
    Thread {
        var why = ""
        val g = try { dev.tvremote.core.ontv.Launchers.fetch() } catch (e: Exception) { why = dev.tvremote.core.Errors.say(e, dev.tvremote.core.Words.lang); null }
        ui.post {
            wait.dismiss()
            if (isFinishing) return@post
            val list = g?.entries.orEmpty()   // a Play one too: used on the TVs that already have it, the others are told to install it there
            if (g == null || list.isEmpty()) { toast(if (why.isNotEmpty()) why else T("launcher.none")); return@post }
            launcherCards(g) { e ->                                   // the gallery as on the page: picture, words, Source
                fleetPick(T(if (e.kind == "play") "fleet.launcher_ask_play" else "fleet.launcher_ask", "name" to e.name)) { ips ->
                    fleetRun(e.name, ips, stoppable = true) { link, p ->
                        dev.tvremote.core.ontv.LauncherInstall(link.get(), cacheDir, g).run(e, 0, { step, pct ->
                            p.step = when (step) { "download" -> T("fleet.step_ldownload") + " $pct%"; "install" -> T("fleet.step_linstall"); else -> T("fleet.step_lswap") }
                        }, { p.cancel.stopped })
                        T("fleet.step_lkept").removePrefix("✓ ")
                    }
                }
            }
        }
    }.start()
}

/** The gallery's launchers as cards for several TVs at once, as the page's (launchers.js launchRow): picture, name, words, a CH Play
 *  tag or the version, the notes, a link to the source or the Play page, and one button; no fit, which is about one TV. */
private fun MainActivity.launcherCards(g: dev.tvremote.core.ontv.Launchers.Gallery, pick: (dev.tvremote.core.ontv.Launchers.Entry) -> Unit) {
    val lang = dev.tvremote.core.Words.lang
    val list = col()
    list.setPadding(dp(16), dp(2), dp(16), dp(2))
    lateinit var box: Popup
    val pics = ArrayList<Pair<dev.tvremote.core.ontv.Launchers.Entry, android.widget.ImageView>>()
    for (e in g.entries) {
        val card = col()
        card.background = box(BTN, 14)
        card.setPadding(dp(12), dp(12), dp(12), dp(12))
        val top = LinearLayout(this)
        val pic = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = box(BG, 8); clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        top.addView(pic, LinearLayout.LayoutParams(dp(112), dp(63)))
        val words = col()
        words.addView(label(e.name, 16, TEXT).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
        words.addView(label("", 12, ACCENT_TEXT).apply { setMarked(if (e.kind == "play") "▶ " + T("launcher.badge_play") else e.version) }, gap(2))
        top.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        card.addView(top)
        card.addView(label(e.desc(lang), 14, MUTED).apply { setLineSpacing(dp(2).toFloat(), 1f) }, gap(8))
        e.notes(lang).takeIf { it.isNotEmpty() }?.let { card.addView(label(it, 13, MUTED), gap(4)) }
        val foot = LinearLayout(this)
        foot.gravity = Gravity.CENTER_VERTICAL
        val link = label(T(if (e.kind == "play") "launcher.source_play" else "launcher.source") + " ↗", 14, ACCENT_TEXT)
        link.setPadding(0, dp(12), dp(12), dp(12))                   // a finger's height, not the words'
        link.setOnClickListener { web(e.source) }
        foot.addView(link, LinearLayout.LayoutParams(0, -2, 1f))
        val use = button(T(if (e.kind == "play") "launcher.use" else "fleet.launcher_use"), ACCENT)
        use.setPadding(dp(18), 0, dp(18), 0)
        use.setOnClickListener { box.dismiss(); pick(e) }
        foot.addView(use, LinearLayout.LayoutParams(-2, dp(44)))
        card.addView(foot, gap(4))
        list.addView(card, if (list.childCount == 0) LinearLayout.LayoutParams(-1, -2) else gap(10))
        pics.add(e to pic)
    }
    // Four at a time (one thread each was a burst of requests; one by one was ~4 s for 13), checked against the signed list's hash,
    // decoded at the card's size (the 960x540 picture was 2 MB of memory for a 112 dp card)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    for ((e, pic) in pics) pool.execute {
        val b = try { dev.tvremote.core.ontv.Launchers.thumb(g, e) } catch (x: Exception) { return@execute }
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var n = 1
        while (o.outWidth / (n * 2) >= dp(112)) n *= 2
        val bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = n }) ?: return@execute
        ui.post { pic.setImageBitmap(bm) }
    }
    pool.shutdown()
    box = Popup.Builder(this).setTitle(T("fleet.which_launcher")).setMessage(T("fleet.launcher_note")).setView(list)
        .setNegativeButton(T("common.cancel"), null).show()
}

/** The saved TVs to tick; the ones that answered (or not checked yet) come ticked. */
private fun MainActivity.fleetPick(title: String, then: (List<String>) -> Unit) {
    val ips = savedIps()
    val names = ips.map { title(it) + if (reach[it] == false) T("many_tvs.no_answer") else "" }.toTypedArray()
    val on = BooleanArray(ips.size) { reach[ips[it]] != false }
    Popup.Builder(this).setTitle(title)
        .setMultiChoiceItems(names, on) { _, i, c -> on[i] = c }
        .setPositiveButton(T("draw.next")) { _, _ ->
            val chosen = ips.filterIndexed { i, _ -> on[i] }
            if (chosen.isEmpty()) toast(T("fleet.pick_one")) else then(chosen)
        }.setNegativeButton(T("common.cancel"), null).show()
}

/** The APK chosen in the file picker: copied here once, since it goes to several TVs, then the TVs to put it on. */
internal fun MainActivity.fleetApk(uri: Uri) {
    var name = "app.apk"
    try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).let { if (it >= 0) c.getString(it)?.let { n -> name = n } }
        }
    } catch (ignored: Exception) { }
    val apk = File(cacheDir, "fleet.apk")
    Thread {
        val ok = try { contentResolver.openInputStream(uri)!!.use { i -> apk.outputStream().use { i.copyTo(it) } }; true } catch (e: Exception) { false }
        ui.post {
            if (isFinishing) return@post
            if (!ok) { toast(T("many_tvs.could_not_read_this")); return@post }
            fleetPick(T("many_tvs.install_on_which_tvs", "name" to name)) { ips ->
                fleetRun(T("many_tvs.install", "name" to name), ips, stoppable = true) { link, p ->
                    FileInputStream(apk).use { Manage(link, null, cacheDir).install(it, apk.length(), p.job, p.cancel) }
                    T("many_tvs.installed")
                }
            }
        }
    }.start()
}

/**
 * The welcome screen's video or picture chosen in the file picker: copied here once, since it goes to several TVs, then the TVs and
 * when it shows. A TV Setup without the welcome screen fails on its own line; the others go on.
 */
internal fun MainActivity.fleetWelcome(uri: Uri) {
    var name = "welcome.mp4"
    try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).let { if (it >= 0) c.getString(it)?.let { n -> name = n } }
        }
    } catch (ignored: Exception) { }
    if (dev.tvremote.core.ontv.Welcome.kind(name) == null) { toast(T("core.fr_welcome_kind")); return }
    val file = File(cacheDir, "fleet-welcome." + dev.tvremote.core.ontv.Welcome.ext(name))
    Thread {
        val ok = try { contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } }; true } catch (e: Exception) { false }
        ui.post {
            if (isFinishing) return@post
            if (!ok) { toast(T("many_tvs.could_not_read_this")); return@post }
            if (file.length() > dev.tvremote.core.ontv.Welcome.MAX_BYTES) { file.delete(); toast(T("core.fr_welcome_toobig", "max" to "200 MB")); return@post }
            fleetPick(T("welcome.title")) { ips ->
                menu(T("welcome.when"), 0, listOf(
                    Triple("@power|" + T("welcome.every"), "") { fleetWelcomeRun(name, file, ips, "every") },
                    Triple("@refresh|" + T("welcome.boot"), "") { fleetWelcomeRun(name, file, ips, "boot") }))
            }
        }
    }.start()
}

private fun MainActivity.fleetWelcomeRun(name: String, file: File, ips: List<String>, whenOf: String) {
    val cfg = JSONObject().put("on", true).put("when", whenOf).put("seconds", dev.tvremote.core.ontv.Welcome.SECONDS)
    fleetRun(T("welcome.title"), ips, stoppable = true) { link, p ->
        FileInputStream(file).use { Manage(link, null, cacheDir).welcomeBulk(it, file.length(), name, cfg, p.job, p.cancel) }
        T("fleet.step_welcome_set")
    }
}

/** One app of the removal list: its package, the name the TV gave, and the TVs that have it. */
private class AppRow(val pkg: String, val name: String, val on: List<String>, val named: Boolean) {
    var picked = false
    lateinit var view: View
    lateinit var tick: android.widget.CheckBox
    lateinit var title: TextView
    lateinit var letter: TextView
    lateinit var image: android.widget.ImageView
    val key = (name + " " + pkg).lowercase()
}

/**
 * Asks each TV picked for its apps, then which to remove from the TVs that have them: one row per app (its picture, its name, "on
 * 3/5 TVs"), a tap anywhere on a row ticks it (the .on tint, as the page), a search once the list is long, Select all, and the main
 * button "Remove (n)". The pictures come from the first TV that has the app, one at a time after the box is open.
 */
private fun MainActivity.fleetUninstall(ips: List<String>) {
    val wait = Popup.Builder(this).setMessage(T("fleet.asking_each")).setCancelable(false).show()
    val apps = HashMap<String, MutableList<String>>()                 // package -> the TVs that have it
    val names = HashMap<String, String>()
    val labels = HashMap<String, RemoteLabels>()                      // per TV, kept until the box closes: the pictures are read through its link
    val links = ArrayList<AdbLink>()
    val answered = HashSet<String>()
    val pool = Executors.newFixedThreadPool(PARALLEL)
    for (ip in ips) pool.execute {
        val link = AdbLink(filesDir, route[ip] ?: ip)
        try {
            val info = Fleet.info(link.get())
            val rl = RemoteLabels(link, { assets.open("tvr-helper.jar").use { it.readBytes() } }, File(cacheDir, "meta/" + ip.replace(Regex("[^\\w.-]"), "_")))
            val label = try { rl.names() } catch (e: Exception) { emptyMap() }
            synchronized(apps) { info.apps.forEach { apps.getOrPut(it) { ArrayList() }.add(ip) }; names.putAll(label); labels[ip] = rl; links.add(link); answered.add(ip) }
        } catch (ignored: Exception) {                                // a TV that does not answer has nothing to remove
            link.close()
        }
    }
    pool.shutdown()
    Thread {
        pool.awaitTermination(3, java.util.concurrent.TimeUnit.MINUTES)
        ui.post {
            val closeLinks = { links.forEach { it.close() } }
            if (isFinishing) { closeLinks(); return@post }
            wait.dismiss()
            val list = apps.filterKeys { Fleet.canUninstall(it) == null && it != "com.tailscale.ipn" }
                .map { (pkg, on) -> AppRow(pkg, names[pkg] ?: pkg.substringAfterLast('.'), on, names[pkg] != null) }
                .sortedWith(compareBy({ -it.on.size }, { it.name.lowercase() }))
            if (list.isEmpty()) { closeLinks(); toast(T("fleet.no_apps")); return@post }
            lateinit var box: Popup
            val rows = col()
            val none = label(T("fleet.no_match"), 14, MUTED)
            none.visibility = View.GONE
            none.setPadding(dp(4), dp(8), 0, 0)
            val toggle = button(T("fleet.pick_all"), BTN)
            toggle.textSize = 13f
            val shown = { list.filter { it.view.visibility == View.VISIBLE } }
            val sync = {
                val n = list.count { it.picked }
                box.getButton(Popup.BUTTON_POSITIVE).let { it.isEnabled = n > 0; it.text = T("fleet.remove") + if (n > 0) " ($n)" else "" }
                val vis = shown()
                toggle.isEnabled = vis.isNotEmpty()
                toggle.text = if (vis.isNotEmpty() && vis.all { it.picked }) T("fleet.pick_none") else T("fleet.pick_all")
                none.visibility = if (vis.isEmpty()) View.VISIBLE else View.GONE
            }
            val setPicked = { r: AppRow, on: Boolean ->
                r.picked = on
                r.tick.isChecked = on
                r.view.background = onBox(on, 12)
                r.title.setTextColor(if (on) ACCENT_TEXT else TEXT)
            }
            list.forEach { r ->
                val hue = r.pkg.fold(7) { h, c -> (h * 31 + c.code) % 360 }.toFloat()   // the page's placeholder: a colour from the package
                val tile = android.widget.FrameLayout(this)
                r.letter = label((r.name.firstOrNull { it.isLetterOrDigit() } ?: '?').uppercaseChar().toString(), 18, android.graphics.Color.WHITE)
                r.letter.typeface = android.graphics.Typeface.DEFAULT_BOLD
                r.letter.gravity = Gravity.CENTER
                r.letter.background = box(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.62f, 0.46f)), 10)
                r.image = android.widget.ImageView(this)
                r.image.visibility = View.GONE
                r.image.background = box(BTN, 10)
                r.image.clipToOutline = true
                r.image.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                r.image.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                tile.addView(r.letter, android.widget.FrameLayout.LayoutParams(-1, -1))
                tile.addView(r.image, android.widget.FrameLayout.LayoutParams(-1, -1))
                val text = col()
                r.title = label(r.name, 16, TEXT)
                r.title.typeface = android.graphics.Typeface.DEFAULT_BOLD
                r.title.maxLines = 1
                r.title.ellipsize = android.text.TextUtils.TruncateAt.END
                text.addView(r.title)
                text.addView(label(T("fleet.on_tvs", "n" to r.on.size, "total" to answered.size), 12, MUTED))
                if (!r.named) text.addView(label(r.pkg, 11, MUTED).also { it.maxLines = 1; it.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })   // no name from the TV: the package, so two are not alike
                r.tick = android.widget.CheckBox(this)
                r.tick.buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                r.tick.isClickable = false                            // the row takes the tap
                val row = LinearLayout(this)
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(10), dp(8), dp(6), dp(8))
                row.addView(tile, LinearLayout.LayoutParams(dp(40), dp(40)))
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(12) })
                row.addView(r.tick)
                row.background = onBox(false, 12)
                row.setOnClickListener { setPicked(r, !r.picked); sync() }
                r.view = row
                rows.addView(row, gap(4))
            }
            rows.addView(none)
            rows.setPadding(dp(16), dp(8), dp(16), 0)
            val head = col()
            head.setPadding(dp(20), dp(4), dp(20), dp(4))
            val skipped = ips.size - answered.size
            head.addView(label(T("fleet.remove_from", "tvs" to ips.joinToString(" · ") { title(it) }) + if (skipped > 0) " " + T("fleet.remove_skipped", "n" to skipped) else "", 13, MUTED))
            val tools = LinearLayout(this)
            tools.gravity = Gravity.CENTER_VERTICAL
            if (list.size > 6) {
                val q = input(T("common.search_by_app_name"))
                q.background = box(BTN, 12)                               // input()'s CARD is the dialog's own: no edge would show
                q.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(e: android.text.Editable?) {
                        val k = e.toString().trim().lowercase()
                        list.forEach { it.view.visibility = if (k.isEmpty() || it.key.contains(k)) View.VISIBLE else View.GONE }
                        sync()
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                tools.addView(q, LinearLayout.LayoutParams(0, dp(44), 1f))
            } else tools.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            tools.addView(toggle, LinearLayout.LayoutParams(-2, dp(44)).also { it.leftMargin = dp(8) })
            head.addView(tools, gap(8))
            toggle.setOnClickListener {
                val vis = shown()
                val every = vis.all { it.picked }
                vis.forEach { setPicked(it, !every) }
                sync()
            }
            val body = col()
            body.addView(head)
            body.addView(rows)
            box = Popup.Builder(this).setTitle(T("many_tvs.remove_which_apps")).setView(body)
                .setPositiveButton(T("fleet.remove")) { _, _ ->
                    val chosen = list.filter { it.picked }
                    val pkgs = chosen.map { it.pkg }
                    val on = chosen.flatMap { it.on }.distinct()
                    val what = if (pkgs.size == 1) chosen[0].name else T("many_tvs.apps", "n" to pkgs.size)
                    Popup.Builder(this).setMessage(T("many_tvs.remove_from_these_tvs", "what" to what) +
                        (if (pkgs.size > 1) "\n" + chosen.joinToString("\n") { "• " + it.name } else "") + "\n\n" + T("many_tvs.tvs") + "\n" + on.joinToString("\n") { "• " + title(it) })
                        .setPositiveButton(T("fleet.remove")) { _, _ ->
                            fleetRun(T("many_tvs.remove", "what" to what), on) { link, _ -> val n = Fleet.uninstall(link.get(), pkgs); T("many_tvs.removed_apps", "n" to n) }
                        }.setNegativeButton(T("common.cancel"), null).show()
                }.setNegativeButton(T("common.cancel"), null).setOnDismissListener { closeLinks() }.show()
            sync()
            // the pictures, four at a time (the TV answers each on its own stream: 16 took 0.49 s one by one, 0.18 s four at a time), each from the first TV that has the app (a TV that gives none leaves the letter)
            val pics = Executors.newFixedThreadPool(4)
            for (r in list) pics.execute {
                if (!box.isShowing) return@execute
                val png = r.on.firstNotNullOfOrNull { ip -> try { labels[ip]?.icon(r.pkg) } catch (e: Exception) { null } } ?: return@execute
                val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size) ?: return@execute
                ui.post { if (box.isShowing) { r.image.setImageBitmap(bmp); r.image.visibility = View.VISIBLE; r.letter.visibility = View.GONE } }
            }
            pics.shutdown()
        }
    }.start()
}

/** One line per TV under its name, until every TV is done; the work goes on if the box is closed. */
private fun MainActivity.fleetRun(title: String, ips: List<String>, stoppable: Boolean = false, checkout: Boolean = false, work: (AdbLink, Part) -> String) {
    val list = col()
    list.setPadding(dp(20), dp(8), dp(20), dp(8))
    val parts = ips.map { ip ->
        val name = label(title(ip), 16, TEXT)
        name.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val line = label("", 14, MUTED)
        list.addView(name, gap(10))
        list.addView(line)
        Part(ip, line).also { it.step = T("fleet.step_waiting") }
    }
    val sv = ScrollView(this)
    sv.addView(list)
    val box = Popup.Builder(this).setTitle(title).setView(sv).setPositiveButton(if (checkout) T("fleet.co_r_done") else T("common.close"), null)
        .setNeutralButton(T("page.stop"), null).show()
    if (checkout) box.getButton(Popup.BUTTON_POSITIVE).isEnabled = false       // Done once every TV has answered (its result card)
    val stopAll = box.getButton(Popup.BUTTON_NEUTRAL)            // set after show(): the dialog must stay open while the TVs wind down
    if (!stoppable) stopAll.visibility = android.view.View.GONE   // a removal or a restart has no half way to stop at
    stopAll.setOnClickListener { parts.forEach { it.cancel.stop() }; stopAll.isEnabled = false }
    val pool = Executors.newFixedThreadPool(PARALLEL)
    for (p in parts) pool.execute {
        if (p.cancel.stopped) { p.step = "■ " + T("files.stopped"); p.state = 3; return@execute }   // never started
        p.step = T("fleet.step_connecting")
        val link = AdbLink(filesDir, route[p.ip] ?: p.ip)
        p.link = link
        try {
            try { link.get() } catch (e: Exception) { throw Unreachable(T("many_tvs.tv_off_on_another")) }
            p.step = T("many_tvs.working")
            p.raw = work(link, p)
            p.step = "✓ " + (if (checkout) Checkout.Result.parse(p.raw)?.let { checkoutLine(it) } ?: p.raw else p.raw)
            p.state = 1
        } catch (e: Exception) {
            if (p.cancel.stopped) { p.step = "■ " + T("files.stopped"); p.state = 3 }
            else { p.step = "✗ " + (if (e is Unreachable) e.message else Errors.say(e, dev.tvremote.core.Words.lang)); p.state = 2 }
        } finally { link.close() }
    }
    pool.shutdown()
    val tick = object : Runnable {
        override fun run() {
            for (p in parts) {
                val pct = p.job.optInt("pct", 0)
                p.line.setTextColor(when (p.state) { 1 -> OK_TEXT; 2 -> ERR_TEXT; else -> MUTED })
                p.line.setMarked(when {
                    p.state != 0 -> p.step
                    p.link?.waitingForUser() == true -> T("many_tvs.the_tv_asks_allow")
                    pct in 1..89 -> T("many_tvs.copying_to_the_tv", "pct" to pct)
                    pct >= 90 -> T("many_tvs.installing")
                    else -> p.step
                })
            }
            if (parts.all { it.state != 0 }) {
                val bad = parts.count { it.state == 2 }
                stopAll.visibility = android.view.View.GONE
                box.setTitle(if (parts.any { it.state == 3 }) T("files.stopped") else if (checkout) T("fleet.co_title")   // the report's own head says how it went, as on the page
                    else if (bad == 0) T("many_tvs.done_on_all_tvs", "n" to parts.size)
                    else T("many_tvs.done_tvs_tvs", "ok" to parts.size - bad, "bad" to bad))
                if (checkout && parts.none { it.state == 3 }) { checkoutReport(box, list, parts); box.getButton(Popup.BUTTON_POSITIVE).isEnabled = true }
            } else if (!isFinishing) ui.postDelayed(this, 500)
        }
    }
    ui.post(tick)
}

/** The package names the check-out was asked about, as the TVs named them (set when the apps were listed), for the result card. */
private var checkoutNames: Map<String, String> = emptyMap()

private class CoRow(val state: Int, val icon: String, val text: String, val fix: String? = null)    // state 0 ok, 1 warn, 2 bad

/** One TV's result as rows, one per step: the same rows, in the same words, as js/checkout.js checkoutReport (kept in step by hand). */
private fun checkoutRows(r: Checkout.Result): List<CoRow> {
    val name = { p: String -> checkoutNames[p] ?: p.substringAfterLast('.') }
    val rows = ArrayList<CoRow>()
    if (r.apps.isNotEmpty()) rows += CoRow(0, "eraser", T("fleet.co_r_clear", "apps" to r.apps.joinToString(", ", transform = name)))
    else if (r.cleared > 0) rows += CoRow(0, "eraser", T("fleet.co_cleared", "n" to r.cleared).replaceFirstChar { it.uppercase() })
    else if (r.failed.isEmpty()) rows += CoRow(0, "eraser", T("fleet.co_r_clear_none"))
    if (r.failed.isNotEmpty()) rows += CoRow(2, "eraser", if (r.failed.none { it == "?" }) T("fleet.co_r_failed", "apps" to r.failed.joinToString(", ", transform = name))
        else T("fleet.co_failed", "n" to r.failed.size).replaceFirstChar { it.uppercase() })
    rows += CoRow(0, "vol_up", T("fleet.co_r_vol", "v" to r.volume))
    rows += when (r.web) {
        "ok" -> CoRow(0, "globe", if (r.forgot > 0) T("fleet.co_r_web_ok", "n" to r.forgot) else T("fleet.co_r_web_0"))
        "old" -> CoRow(1, "globe", T("fleet.co_r_web_old"), "update")
        "missing" -> CoRow(1, "globe", T("fleet.co_r_web_missing"), "install")
        else -> CoRow(1, "globe", T("fleet.co_r_web_failed"))
    }
    if (r.home) rows += CoRow(0, "home", T("fleet.co_r_home"))
    return rows
}

/**
 * The end of a check-out, in the box the run was in: a header (all well / some TV needs a look), a card per TV with a row per step,
 * and the box's Done. Built from Ui.kt's helpers and the box's colours; a TV Setup too old gets its Update button, which opens that TV's
 * Manage screen on the update, as [offerSetup] does.
 */
private fun MainActivity.checkoutReport(dlg: Popup, list: LinearLayout, parts: List<Part>) {
    val tvs = parts.map { p ->
        val r = if (p.state == 1) Checkout.Result.parse(p.raw) else null
        Triple(p, r, if (r != null) checkoutRows(r) else listOf(CoRow(2, "close", p.step.removePrefix("✗ "))))
    }
    val bad = tvs.count { it.first.state == 2 }
    val odd = tvs.any { t -> t.third.any { it.state != 0 } }
    fun tint(state: Int) = when (state) { 0 -> OK_TEXT; 1 -> HELD; else -> ERR_TEXT }
    fun glyph(name: String, color: Int, size: Int) = android.widget.ImageView(this).apply {
        val id = resources.getIdentifier("ic_$name", "drawable", packageName)
        setImageResource(id); imageTintList = android.content.res.ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    list.removeAllViews()
    val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    val badge = FrameLayout(this).apply { background = oval(SEL) }
    badge.addView(glyph(if (odd) "alert" else "check", tint(if (odd) 1 else 0), 24), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
    head.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
    val words = col().apply { setPadding(dp(12), 0, 0, 0) }
    words.addView(label(T("fleet.co_r_title", "ok" to tvs.size - bad, "n" to tvs.size), 17, TEXT).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
    words.addView(label(if (odd) T("fleet.co_r_sub_bad", "bad" to tvs.count { t -> t.third.any { it.state != 0 } }) else T("fleet.co_r_sub_ok"), 13, MUTED))
    head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
    list.addView(head)
    for ((p, _, rows) in tvs) {
        val card = col().apply { background = box(BTN, 14); setPadding(dp(14), dp(12), dp(14), dp(10)) }
        card.addView(label(title(p.ip), 16, TEXT).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD })
        for (r in rows) {
            val line = LinearLayout(this).apply { gravity = Gravity.TOP }
            line.addView(glyph(r.icon, MUTED, 20).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(2) })
            val text = label(r.text, 14, if (r.state == 2) ERR_TEXT else TEXT).apply { setPadding(dp(10), 0, dp(8), 0) }
            line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(glyph(when (r.state) { 0 -> "check"; 1 -> "alert"; else -> "close" }, tint(r.state), 20).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(2) })
            card.addView(line, gap(8))
            if (r.fix != null) card.addView(button(T(if (r.fix == "update") "fleet.co_r_fix_update" else "fleet.co_r_fix_install"), CARD).apply {
                setOnClickListener { dlg.dismiss(); managing.open(p.ip, then = if (r.fix == "update") "updateSetup()" else "installSetupApk()") }
            }, gap(8).apply { height = dp(40) })
        }
        list.addView(card, gap(12))
    }
}
