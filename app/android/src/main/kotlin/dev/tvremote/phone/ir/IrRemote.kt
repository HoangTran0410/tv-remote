package dev.tvremote.phone.ir

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.R
import dev.tvremote.phone.media.askText
import dev.tvremote.phone.media.say
import dev.tvremote.phone.picker.name
import dev.tvremote.phone.picker.open
import dev.tvremote.phone.picker.save
import dev.tvremote.phone.picker.savedIps
import dev.tvremote.phone.remote.keys
import dev.tvremote.phone.remote.kind
import dev.tvremote.phone.remote.kindOf
import dev.tvremote.phone.ui.ACCENT
import dev.tvremote.phone.ui.ACCENT_TEXT
import dev.tvremote.phone.ui.BG
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.CARD
import dev.tvremote.phone.ui.GREEN
import dev.tvremote.phone.ui.HELD
import dev.tvremote.phone.ui.MUTED
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.RED
import dev.tvremote.phone.ui.SOFT
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.cell
import dev.tvremote.phone.ui.col
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.gap
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.input
import dev.tvremote.phone.ui.label
import dev.tvremote.phone.ui.menu
import dev.tvremote.phone.ui.note
import dev.tvremote.phone.ui.oval
import dev.tvremote.phone.ui.say
import dev.tvremote.phone.ui.setInk
import dev.tvremote.phone.ui.setOn

import dev.tvremote.core.T
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.hardware.ConsumerIrManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.concurrent.Executors

// The infrared remote: the phone's IR LED (ConsumerIrManager) sends a TV's or box's own remote codes (Ir.kt, IrCodes.kt, assets/ir/codes.json).
// It needs no network and no picked TV, so it is reached from the list of TVs: a TV that lost its Wi-Fi still has its IR window.
// Phone app only (the Computer app and the TV web page have no IR LED). A debug build without an LED logs the pattern ("TvrIr") instead.

private const val TAG = "TvrIr"
private const val PROFILE = "ir:profile"                           // the chosen profile's id; "ir:profile:<ip>" for a saved TV
private const val TARGET = "ir:tv"                                  // the saved TV the codes are for, "" for none
private const val CUSTOM = "ir:custom"                              // the sets learned from a box: [{"id", "name", "keys"}]
private const val SAVED = "ir:saved"                                // the remotes kept by name: [{"name", "id"}], for the list (irList)
private val ONCE = setOf("POWER")                                   // held, every other key goes on sending, as a remote does (Ir.held)

private object IrState {
    var catalog: List<IrProfile>? = null
    var toggle = false                                              // RC5 / RC6: the bit that flips on every new press
    var onFind = false                                              // which of the two screens the back button leaves
    var page = ""                                                   // "auto" while Dò tự động is on screen: its steps redraw it
    var slot = -1                                                   // the saved remote on screen (SAVED), -1 none, ADDING while one is being added
    @Volatile var stop = false
    @Volatile var link: dev.tvremote.core.adb.AdbLink? = null
    var auto = ""                                                   // the TV Dò tự động listens on
    var custom: List<IrProfile>? = null                             // the sets learned from a box (IrLearn.kt), kept in CUSTOM
    @Volatile var skip = false                                      // Học phím: leave this key out
    @Volatile var finish = false                                    // Học phím: enough keys, build the set
    val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ir").apply { isDaemon = true } }
}

/** The learned sets first, so Dò tự động tries the box's own remote before the thousands of others. */
internal fun MainActivity.irCatalog(): List<IrProfile> = irCustom() + (IrState.catalog ?: try {
    IrCodes.merge(IrCodes.parse(assets.open("ir/codes.json").bufferedReader().use { it.readText() }))
} catch (e: Exception) { emptyList() }.also { IrState.catalog = it })

private fun MainActivity.irCustom(): List<IrProfile> = IrState.custom ?: try {
    val a = org.json.JSONArray(pref(CUSTOM).ifEmpty { "[]" })
    (0 until a.length()).map { a.getJSONObject(it).let { o ->
        val k = o.getJSONObject("keys")
        IrProfile(o.getString("id"), o.getString("name"), o.getString("name"), k.keys().asSequence().associateWith { n -> k.getString(n) })
    } }
} catch (e: Exception) { emptyList() }.also { IrState.custom = it }

/** Keeps a set learned from a box under [id] (one per TV: learning again replaces it). */
private fun MainActivity.irCustomKeep(id: String, name: String, keys: Map<String, String>): IrProfile {
    val all = irCustom().filter { it.id != id } + IrProfile(id, name, name, keys)
    val a = org.json.JSONArray()
    for (p in all) a.put(org.json.JSONObject().put("id", p.id).put("name", p.brand).put("keys", org.json.JSONObject(p.keys)))
    prefs.edit().putString(CUSTOM, a.toString()).apply()
    IrState.custom = all
    return all.last()
}

private fun MainActivity.irManager(): ConsumerIrManager? = try { getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager } catch (e: Exception) { null }

/** The entry is shown only on a phone with an IR LED; a debug build shows it anyway, to be looked at on an emulator. */
internal fun MainActivity.irAvailable(): Boolean =
    irManager()?.hasIrEmitter() == true || applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/** The carrier the LED can send that is nearest to [hz]: some drivers refuse a frequency outside their ranges. */
private fun nearest(m: ConsumerIrManager, hz: Int): Int {
    val r = try { m.carrierFrequencies } catch (e: Exception) { null } ?: return hz
    return r.minByOrNull { if (hz in it.minFrequency..it.maxFrequency) 0 else minOf(Math.abs(hz - it.minFrequency), Math.abs(hz - it.maxFrequency)) }
        ?.let { hz.coerceIn(it.minFrequency, it.maxFrequency) } ?: hz
}

private fun MainActivity.irSend(code: String, repeat: Boolean) {
    if (!repeat) IrState.toggle = !IrState.toggle
    val s = (if (repeat) Ir.held(code, IrState.toggle) else Ir.encode(code, IrState.toggle)) ?: return
    val m = irManager()
    if (m?.hasIrEmitter() != true) { Log.i(TAG, "send $code hz=${s.hz} us=${s.micros} pattern=${s.pattern.joinToString(",")}"); return }
    IrState.io.execute {
        try { m.transmit(nearest(m, s.hz), s.pattern) } catch (e: Exception) {
            Log.w(TAG, "transmit failed", e)
            ui.post { if (!isFinishing) Toast.makeText(this, T("ir.failed"), Toast.LENGTH_SHORT).show() }
        }
    }
}

/** The saved TV the codes are for: each keeps its own remote, and one ADB reaches can find its code by itself (IrAuto.kt). */
private fun MainActivity.irTarget(): String = pref(TARGET).takeIf { it in savedIps() } ?: ""
private fun MainActivity.irProfileKey(): String = irTarget().let { if (it.isEmpty()) PROFILE else "$PROFILE:$it" }
private const val ADDING = -2
private fun MainActivity.irProfile(): IrProfile? {
    val id = when {
        irTarget().isNotEmpty() -> pref(irProfileKey()).ifEmpty { pref(PROFILE) }
        IrState.slot >= 0 -> irSaved().getOrNull(IrState.slot)?.second ?: ""
        else -> ""
    }
    return irCatalog().firstOrNull { id in it.ids }
}

/** A TV's own code goes with that TV; otherwise into the list: the remote on screen changes, or one being added is kept under a name. */
private fun MainActivity.irSave(p: IrProfile) {
    if (irTarget().isNotEmpty()) { prefs.edit().putString(irProfileKey(), p.id).apply(); return }
    val all = irSaved().toMutableList()
    val at = IrState.slot
    if (at in all.indices) {
        val (name, old) = all[at]
        val was = irCatalog().firstOrNull { old in it.ids }?.title       // a name never typed follows the code
        all[at] = Pair(if (name == was) p.title else name, p.id)
        irKeep(all)
        return
    }
    all.add(Pair(p.title, p.id))
    irKeep(all)
    IrState.slot = all.size - 1
    ui.post { if (!isFinishing) irRename(IrState.slot) }                // the screen first, then its name asked over it
}

/** The saved remotes, name and profile id. The single code of before the list becomes its first entry. */
private fun MainActivity.irSaved(): List<Pair<String, String>> {
    val text = pref(SAVED)
    if (text.isEmpty()) {
        val old = pref(PROFILE).takeIf { it.isNotEmpty() }?.let { id -> irCatalog().firstOrNull { id in it.ids } } ?: return emptyList()
        return listOf(Pair(old.title, old.id)).also { irKeep(it) }
    }
    return try {
        val a = org.json.JSONArray(text)
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Pair(o.optString("name"), o.optString("id")) } }
    } catch (e: Exception) { emptyList() }
}

private fun MainActivity.irKeep(all: List<Pair<String, String>>) {
    val a = org.json.JSONArray()
    for ((n, id) in all) a.put(org.json.JSONObject().put("name", n).put("id", id))
    prefs.edit().putString(SAVED, a.toString()).apply()
}

private fun MainActivity.irRename(at: Int) {
    val all = irSaved().toMutableList()
    if (at !in all.indices) return
    askText(T("ir.name_title"), T("ir.name_hint"), T("ir.save"), all[at].first) { n ->
        all[at] = Pair(n, all[at].second); irKeep(all)
        if (IrState.page == "list") irList() else if (IrState.page == "" && !IrState.onFind) irRemote()
    }
}
/** "Samsung", or "TCL · Kiểu 2" when the brand has more than one remote. */
internal val IrProfile.title: String get() = if (kind == 0) brand else brand + " · " + T("ir.kind", "n" to kind)
/** The models it stands for, and how many keys it knows. */
private fun IrProfile.about(): String =
    T("ir.models", "list" to (models.take(2).joinToString(", ") + (if (models.size > 2) " +" + (models.size - 2) else ""))) + " · " + T("ir.n_keys", "n" to keys.size)

private fun MainActivity.irTvName(ip: String) = pref("name:$ip").ifEmpty { ip }

// ---------------------------------------------------------------- the screen
internal fun MainActivity.buildIr(): FrameLayout {
    val host = FrameLayout(this)
    host.visibility = View.GONE
    return host
}

/** [tv]: the saved TV it is opened for (from that TV's remote: its own code), null from the list of TVs (the saved remotes first). */
internal fun MainActivity.irOpen(tv: String? = null) {
    prefs.edit().putString(TARGET, tv ?: "").apply()
    IrState.slot = -1
    if (tv == null) irList() else irRemote()
    irHost.visibility = View.VISIBLE
}

/** The back button: from the code finder to the remote, from a saved remote to the list, then to the list of TVs. True when it was ours. */
internal fun MainActivity.irBack(): Boolean {
    if (irHost.visibility != View.VISIBLE) return false
    if (IrState.onFind) { irStop(); irRemote() } else irUp()
    return true
}

private fun MainActivity.irUp() { if (irTarget().isEmpty() && IrState.page != "list") irList() else irHost.visibility = View.GONE }

/** The remotes kept by name (a TV in the bedroom, the operator's box): one tap opens it, no finding again. Add opens the brands / Find code. */
private fun MainActivity.irList() {
    IrState.onFind = false
    IrState.page = "list"
    IrState.slot = -1
    val all = irSaved()
    irPage(T("ir.title"), { irHost.visibility = View.GONE }) {
        if (all.isEmpty()) addView(note(T("ir.saved_none")), gap(14))
        all.forEachIndexed { i, (name, id) ->
            val p = irCatalog().firstOrNull { id in it.ids }
            val line = LinearLayout(this@irList)
            line.gravity = Gravity.CENTER_VERTICAL
            line.background = box(CARD, 14)
            line.setPadding(dp(14), dp(10), dp(6), dp(10))
            line.isClickable = true
            line.setOnClickListener { IrState.slot = i; irRemote() }
            val texts = col()
            val head = label(name, 16, TEXT)
            head.typeface = Typeface.DEFAULT_BOLD
            head.maxLines = 1; head.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(head)
            if (p != null && p.title != name) texts.addView(label(p.title, 13, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val edit = icon("@pen", T("ir.rename"), CARD)
            edit.setOnClickListener { irRename(i) }
            line.addView(edit, LinearLayout.LayoutParams(dp(44), dp(44)))
            val drop = icon("@trash", T("setup.delete"), CARD)
            drop.setOnClickListener {
                Popup.Builder(this@irList).setTitle(T("ir.delete_ask", "name" to name))
                    .setPositiveButton(T("setup.delete")) { _, _ -> irKeep(irSaved().filterIndexed { k, _ -> k != i }); irList() }
                    .setNegativeButton(T("common.cancel"), null).show()
            }
            line.addView(drop, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(line, gap(if (i == 0) 14 else 8))
        }
        val add = button("@plus|" + T("ir.add"), ACCENT)
        add.setOnClickListener { IrState.slot = ADDING; irRemote() }
        addView(add, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        val files = LinearLayout(this@irList)                           // to another phone, or kept before a reset
        val save = button("@download|" + T("ir.export"), SOFT)
        save.isEnabled = all.isNotEmpty(); save.alpha = if (all.isEmpty()) 0.4f else 1f
        save.setOnClickListener {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "tv-remote-ir.json")
            try { @Suppress("DEPRECATION") startActivityForResult(i, PICK_IR_SAVE) } catch (e: Exception) { say(T("media.this_phone_has_no"), true) }
        }
        files.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f))
        val open = button("@upload|" + T("ir.import"), SOFT)
        open.setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
            try { @Suppress("DEPRECATION") startActivityForResult(i, PICK_IR_OPEN) } catch (e: Exception) { say(T("media.this_phone_has_no"), true) }
        }
        files.addView(open, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(10) })
        addView(files, gap(10))
    }
}

/** The saved remotes as a file: names, codes' ids and brands (for a person reading it). */
internal fun MainActivity.irExported(uri: android.net.Uri) {
    val a = org.json.JSONArray()
    for ((n, id) in irSaved()) a.put(org.json.JSONObject().put("name", n).put("id", id).put("brand", irCatalog().firstOrNull { id in it.ids }?.brand ?: ""))
    val text = org.json.JSONObject().put("app", "tv-remote").put("ir", a).toString(1)
    val ok = try { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }; true } catch (e: Exception) { false }
    Toast.makeText(this, if (ok) T("ir.exported", "n" to a.length()) else T("ir.import_none"), Toast.LENGTH_LONG).show()
}

/** A file from Xuất file (or a bare list): its remotes added after the ones here; the same name and code twice, or a code this
 *  app does not know (a newer list of codes), is left out. */
private const val IR_FILE_MAX = 1 shl 20

internal fun MainActivity.irImported(uri: android.net.Uri) {
    val got = try {
        val text = contentResolver.openInputStream(uri)!!.use { s ->                     // at most 1 MB: a big file picked by mistake (a video) ran out of memory (census 3b92aa84)
            val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
            while (out.size() <= IR_FILE_MAX) { val n = s.read(b); if (n < 0) break; out.write(b, 0, n) }
            if (out.size() > IR_FILE_MAX) "" else out.toString("UTF-8")
        }.trim()
        val a = if (text.startsWith("[")) org.json.JSONArray(text) else org.json.JSONObject(text).getJSONArray("ir")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Pair(o.optString("name"), o.optString("id")) } }
    } catch (e: Exception) { emptyList() }
    val known = got.filter { (_, id) -> irCatalog().any { id in it.ids } }
    val all = irMerge(irSaved(), known)
    val added = all.size - irSaved().size
    if (added > 0) irKeep(all)
    Toast.makeText(this, if (known.isEmpty()) T("ir.import_none") else T("ir.imported", "n" to added), Toast.LENGTH_LONG).show()
    if (irHost.visibility == View.VISIBLE && IrState.page == "list") irList()
}

/** [add] after [have], without one already there (same code and name); a nameless one is named by its code's id. */
internal fun irMerge(have: List<Pair<String, String>>, add: List<Pair<String, String>>): List<Pair<String, String>> {
    val out = have.toMutableList()
    for ((n, id) in add) {
        if (id.isEmpty()) continue
        val p = Pair(n.trim().ifEmpty { id }, id)
        if (p !in out) out.add(p)
    }
    return out
}

private fun MainActivity.irPage(title: String, back: () -> Unit, body: LinearLayout.() -> Unit) {
    val box = col()
    box.setPadding(dp(16), dp(20), dp(16), dp(24))
    val head = label(title, 24, TEXT)
    head.typeface = Typeface.DEFAULT_BOLD
    val top = LinearLayout(this)
    top.gravity = Gravity.CENTER_VERTICAL
    top.addView(head, LinearLayout.LayoutParams(0, -2, 1f))
    val done = icon("@check", T("drawing.done"), ACCENT)
    done.setOnClickListener { back() }
    top.addView(done, LinearLayout.LayoutParams(dp(52), dp(44)))
    box.addView(top)
    box.body()
    val sv = ScrollView(this)
    sv.setBackgroundColor(BG)
    sv.isClickable = true                                           // touches do not fall through to the list of TVs below
    sv.addView(box)
    irHost.removeAllViews()
    irHost.addView(sv, FrameLayout.LayoutParams(-1, -1))
}

internal fun MainActivity.irRemote() {
    IrState.onFind = false
    IrState.page = ""
    val p = irProfile()
    val saved = irSaved().getOrNull(IrState.slot)?.first
    irPage(saved ?: T("ir.title"), { irUp() }) {
        val line = LinearLayout(this@irRemote)
        val pick = button("@tv|" + (p?.title ?: T("ir.choose_brand")), BTN)
        pick.maxLines = 1; pick.ellipsize = android.text.TextUtils.TruncateAt.END
        pick.setOnClickListener { irBrands() }
        line.addView(pick, LinearLayout.LayoutParams(0, dp(48), 1f))
        val find = button("@search|" + T("ir.find"), SOFT)
        find.setInk(ACCENT_TEXT)
        find.setOnClickListener { irFindStart() }
        line.addView(find, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(8) })
        addView(line, gap(14))
        addView(note(if (p == null) T("ir.pick_first") else T("ir.aim")))
        if (p != null) irKeys(this, p)
    }
}

/** Find code: by itself on a TV ADB reaches (the one this remote is for, else the one picked), by hand on any other. */
private fun MainActivity.irFindStart() {
    val android = { ip: String -> kindOf(ip) == dev.tvremote.core.remote.Remote.Kind.ANDROID }
    val t = irTarget()
    if (t.isNotEmpty()) { if (android(t)) { IrState.auto = t; irAuto() } else irFind(); return }
    val tvs = savedIps().filter(android)
    if (tvs.isEmpty()) { irFind(); return }
    menu(T("ir.find_on"), -1, tvs.map { ip -> Triple("@tv|" + irTvName(ip), T("ir.tv_sub")) { IrState.auto = ip; irAuto() } } +
        Triple("@search|" + T("ir.other_tv"), T("ir.other_tv_sub")) { irFind() })
}

private fun MainActivity.irBrands() {
    val all = irCatalog()
    val now = irProfile()?.id
    val items = all.map { it.brand }.distinct().map { b ->
        val of = all.filter { it.brand == b }
        val sub = if (of.size == 1) T("ir.one_kind", "n" to of[0].keys.size) else T("ir.n_kinds", "n" to of.size)
        Triple("@tv|" + b + (if (of.any { it.id == now }) "  ✓" else ""), sub) { if (of.size == 1) { irSave(of[0]); irRemote() } else irProfiles(b, of) }
    }
    menu(T("ir.choose_brand"), -1, items)
}

private fun MainActivity.irProfiles(brand: String, of: List<IrProfile>) {
    val now = irProfile()?.id
    menu(brand, -1, of.map { p ->
        Triple("@tv|" + T("ir.kind", "n" to p.kind) + (if (p.kind == 1) " · " + T("ir.common") else "") + (if (p.id == now) "  ✓" else ""), p.about()) { irSave(p); irRemote() }
    })
}

/** [bind]: a key of [p] that is there answers the finger (held: it repeats); one that is not is greyed, as the page greys a key the TV lacks. */
private fun MainActivity.irBind(b: Button, p: IrProfile, key: String, held: android.graphics.drawable.Drawable) {
    val code = p.keys[key]
    if (code == null) { b.alpha = 0.3f; b.isEnabled = false; return }
    val normal = b.background
    val every = Ir.period(code).toLong()
    val again = object : Runnable { override fun run() { irSend(code, true); ui.postDelayed(this, every) } }
    b.setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.background = held
                buzz()
                irSend(code, false)
                if (key !in ONCE) ui.postDelayed(again, every)          // no pause first: a gap reads as let go, the TV waits its own long-press time
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { ui.removeCallbacks(again); v.background = normal }
        }
        true
    }
}

private fun MainActivity.irButton(p: IrProfile, key: String, symbol: String, say: String): Button {
    val b = icon(symbol, say, BTN)
    irBind(b, p, key, box(HELD, 16))
    return b
}

private fun MainActivity.irRow(p: IrProfile, h: Int, vararg keys: Triple<String, String, String>): LinearLayout {
    val row = LinearLayout(this)
    keys.forEachIndexed { i, (k, sym, say) -> row.addView(irButton(p, k, sym, say), cell(h, i > 0)) }
    return row
}

private fun MainActivity.irKeys(box: LinearLayout, p: IrProfile) {
    fun k(key: String, sym: String, say: String) = Triple(key, sym, say)
    box.addView(irRow(p, 52, k("POWER", "@power", T("remote.power")), k("INPUT", "@input", T("rkey.input")), k("MUTE", "@mute", T("rkey.mute"))), gap(16))
    box.addView(irDpad(p), gap(14))
    box.addView(irRow(p, 52, k("BACK", "@back", T("rkey.back")), k("HOME", "@home", T("rkey.home")), k("MENU", "@menu", T("rkey.menu"))), gap(14))
    val pills = LinearLayout(this)
    pills.addView(irPill(p, T("remote.ch_2"), Triple("CH_UP", "@up", T("remote.ch")), Triple("CH_DOWN", "@down", T("remote.ch_3"))), LinearLayout.LayoutParams(0, dp(124), 1f).apply { rightMargin = dp(8) })
    pills.addView(irPill(p, "VOL", Triple("VOL_UP", "@plus", T("remote.volume_up")), Triple("VOL_DOWN", "@minus", T("remote.volume_down"))), LinearLayout.LayoutParams(0, dp(124), 1f).apply { leftMargin = dp(8) })
    box.addView(pills, gap(14))
    if (p.keys.keys.any { it in "0".."9" }) {
        for (r in listOf("123", "456", "789", " 0 ")) {
            val row = LinearLayout(this)
            r.forEachIndexed { i, c ->
                val b = if (c == ' ') View(this) else button(c.toString(), BTN).also { it.textSize = 18f; irBind(it, p, c.toString(), box(HELD, 14)) }
                row.addView(b, cell(48, i > 0))
            }
            box.addView(row, gap(if (r == "123") 14 else 6))
        }
    }
    val media = listOf(k("REWIND", "@rewind", T("rkey.rewind")), k("PLAY_PAUSE", "@play_pause", T("rkey.playpause")), k("FFWD", "@ffwd", T("rkey.forward")), k("STOP", "@stop", T("rkey.stop")))
    val more = listOf(k("SETTINGS", "@gear", T("rkey.settings")), k("INFO", "@info", T("rkey.info")), k("GUIDE", "@guide", T("rkey.guide")), k("SUBTITLE", "@subtitle", T("rkey.subtitles")))
    for (keys in listOf(media, more)) if (keys.any { it.first in p.keys }) box.addView(irRow(p, 48, *keys.toTypedArray()), gap(14))
    // only the keys this remote has (most have none of them): the four colours in their own colour, then the apps' buttons by name
    val colours = listOf("RED" to 0xffe5484d.toInt(), "GREEN" to 0xff30a46c.toInt(), "YELLOW" to 0xfff5d90a.toInt(), "BLUE" to 0xff3e63dd.toInt()).filter { it.first in p.keys }
    if (colours.isNotEmpty()) {
        val row = LinearLayout(this)
        colours.forEachIndexed { i, (key, c) ->
            val b = button(" ", c).also { it.contentDescription = T("rkey." + key.lowercase()) }
            irBind(b, p, key, box(HELD, 16))
            row.addView(b, cell(34, i > 0))
        }
        box.addView(row, gap(14))
    }
    val apps = listOf("NETFLIX" to T("rkey.netflix"), "YOUTUBE" to T("rkey.youtube"), "PRIME" to T("rkey.prime")).filter { it.first in p.keys }
    if (apps.isNotEmpty()) {
        val row = LinearLayout(this)
        apps.forEachIndexed { i, (key, name) ->
            val b = button(name, BTN).also { it.textSize = 14f; it.setPadding(dp(4), 0, dp(4), 0) }
            irBind(b, p, key, box(HELD, 14))
            row.addView(b, cell(44, i > 0))
        }
        box.addView(row, gap(14))
    }
}

private fun MainActivity.irPill(p: IrProfile, name: String, up: Triple<String, String, String>, down: Triple<String, String, String>): LinearLayout {
    val pill = col()
    pill.background = box(BTN, 26)
    val a = icon(up.second, up.third, 0)
    irBind(a, p, up.first, box(HELD, 26))
    val b = icon(down.second, down.third, 0)
    irBind(b, p, down.first, box(HELD, 26))
    val l = label(name, 13, MUTED)
    l.gravity = Gravity.CENTER
    l.typeface = Typeface.DEFAULT_BOLD
    pill.addView(a, LinearLayout.LayoutParams(-1, 0, 1f))
    pill.addView(l, LinearLayout.LayoutParams(-1, -2))
    pill.addView(b, LinearLayout.LayoutParams(-1, 0, 1f))
    return pill
}

/** The round pad of the native remote (RemoteScreen.kt dpad), sending IR codes. */
private fun MainActivity.irDpad(p: IrProfile): View {
    val pad = FrameLayout(this)
    pad.background = oval(CARD)
    val arrows = listOf(Triple("UP", "@up", Gravity.TOP or Gravity.CENTER_HORIZONTAL), Triple("DOWN", "@down", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
        Triple("LEFT", "@left", Gravity.START or Gravity.CENTER_VERTICAL), Triple("RIGHT", "@right", Gravity.END or Gravity.CENTER_VERTICAL))
    val says = mapOf("UP" to T("remote.up"), "DOWN" to T("remote.down"), "LEFT" to T("remote.left"), "RIGHT" to T("remote.right"))
    for ((key, sym, gravity) in arrows) {
        val b = icon(sym, says.getValue(key), 0)
        b.setIcon(sym, MUTED)
        b.size = 26f
        b.background = oval(0)
        irBind(b, p, key, oval(HELD))
        pad.addView(b, FrameLayout.LayoutParams(dp(72), dp(72), gravity).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }
    val ok = button("OK", ACCENT)
    ok.typeface = Typeface.DEFAULT_BOLD
    ok.textSize = 18f
    ok.setPadding(0, 0, 0, 0)
    ok.background = oval(ACCENT)
    ok.elevation = dp(6).toFloat()
    irBind(ok, p, "OK", oval(HELD))
    pad.addView(ok, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
    val wrap = LinearLayout(this)
    wrap.gravity = Gravity.CENTER
    wrap.addView(pad, LinearLayout.LayoutParams(dp(216), dp(216)))
    return wrap
}

// ---------------------------------------------------------------- Find code: a device that is in no list
/**
 * Sends one key's code of every different remote in turn, one tap at a time; the one the device answers to is kept. Remotes that share
 * that code count as one step (a hundred Samsung models send the same power code).
 */
private fun MainActivity.irFind(key0: String = "POWER", at0: Int = -1) {
    IrState.onFind = true
    IrState.page = ""
    var key = key0
    var idx = at0
    val steps = IrCodes.distinct(irCatalog(), key)
    irPage(T("ir.find"), { irRemote() }) {
        val chips = LinearLayout(this@irFind)
        chips.isBaselineAligned = false                               // a two-line chip pulled itself down
        val names = listOf("POWER" to T("remote.power"), "HOME" to T("rkey.home"), "VOL_UP" to T("ir.vol"), "MUTE" to T("rkey.mute"))
        names.forEachIndexed { i, (k, words) ->
            val c = button(words, BTN)
            c.textSize = 13f
            c.maxLines = 1; c.ellipsize = android.text.TextUtils.TruncateAt.END   // four in a row: "Tắt tiếng" went to two lines
            c.setPadding(dp(4), 0, dp(4), 0)
            c.setOn(k == key)
            c.setOnClickListener { if (k != key) irFind(k, -1) }
            chips.addView(c, cell(40, i > 0))
        }
        addView(note(T("ir.find_key")), gap(10))
        addView(chips, gap(6))
        addView(note(T("ir.find_hint")), gap(8))
        val card = col()
        card.background = box(CARD, 14)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val step = label(if (idx < 0) T("ir.ready") else T("ir.step", "n" to idx + 1, "total" to steps.size), 20, TEXT)
        step.typeface = Typeface.DEFAULT_BOLD
        card.addView(step)
        if (idx in steps.indices) {
            val (p, same) = steps[idx]
            card.addView(label(p.title + (if (same > 1) "  " + T("ir.same", "n" to same - 1) else ""), 14, MUTED), gap(4))
        }
        addView(card, gap(14))
        val row = LinearLayout(this@irFind)
        val again = button(T("ir.again"), BTN)
        val next = button(if (idx < 0) T("ir.send") else T("ir.next"), BTN)
        again.isEnabled = idx in steps.indices; again.alpha = if (again.isEnabled) 1f else 0.4f
        again.setOnClickListener { steps.getOrNull(idx)?.let { s -> buzz(); irSend(s.first.keys.getValue(key), false) } }
        next.setOnClickListener {
            buzz()
            if (idx + 1 >= steps.size) { Toast.makeText(this@irFind, T("ir.end"), Toast.LENGTH_LONG).show(); return@setOnClickListener }
            irFind(key, idx + 1)
            steps[idx + 1].let { s -> irSend(s.first.keys.getValue(key), false) }
        }
        row.addView(again, cell(52, false))
        row.addView(next, cell(52, true))
        addView(row, gap(14))
        val ok = button(T("ir.reacted"), ACCENT)
        ok.isEnabled = idx in steps.indices; ok.alpha = if (ok.isEnabled) 1f else 0.4f
        ok.setOnClickListener {
            val s = steps[idx].first
            irSave(s)
            Toast.makeText(this@irFind, T("ir.found", "name" to s.title), Toast.LENGTH_LONG).show()
            irRemote()
        }
        addView(ok, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
    }
}

// ---------------------------------------------------------------- Dò tự động: the TV says over ADB when a code arrives (IrAuto.kt)
private const val AUTO_KEY = "VOL_UP"                                // heard by every TV, harmless, undone with VOL_DOWN; never POWER (ADB would go)

private fun MainActivity.irStop() {
    IrState.stop = true
    IrState.link?.let { l -> Thread { l.close() }.start() }       // a TV still asking "Allow USB debugging?" lets go too
}

/** The page: [say] what is going on, [sub] the line under it, [found] the match, [running] while the codes go out. */
private fun MainActivity.irAuto(say: String = "", sub: String = "", found: IrProfile? = null, running: Boolean = false) {
    IrState.onFind = true
    IrState.page = "auto"
    val name = irTvName(IrState.auto)
    irPage(T("ir.find"), { irStop(); irRemote() }) {
        addView(note(T("ir.auto_hint", "name" to name)), gap(10))
        val card = col()
        card.background = box(CARD, 14)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val big = label(if (found != null) T("ir.auto_found", "name" to found.title) else say.ifEmpty { T("ir.ready") }, 20, TEXT)
        big.typeface = Typeface.DEFAULT_BOLD
        card.addView(big)
        if (sub.isNotEmpty()) card.addView(label(sub, 14, MUTED), gap(4))
        if (running) card.addView(android.widget.ProgressBar(this@irAuto, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; progressTintList = android.content.res.ColorStateList.valueOf(ACCENT) }, gap(8))
        addView(card, gap(14))
        val main = when {
            found != null -> button(T("ir.use"), ACCENT).apply { setOnClickListener {
                irSave(found); prefs.edit().putString("$PROFILE:${IrState.auto}", found.id).apply(); Toast.makeText(this@irAuto, T("ir.found", "name" to found.title), Toast.LENGTH_LONG).show(); irRemote() } }
            running -> button(T("ir.stop"), BTN).apply { setOnClickListener { irStop(); irAuto() } }
            else -> button(if (say.isEmpty()) T("ir.auto_start") else T("ir.retry"), ACCENT).apply { setOnClickListener { irAutoRun() } }
        }
        addView(main, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(4) })
        val hand = button("@search|" + T("ir.by_hand"), SOFT)
        hand.setInk(ACCENT_TEXT)
        hand.setOnClickListener { irStop(); irFind() }
        addView(hand, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        if (!running) {
            val learn = button("@gamepad|" + T("ir.learn"), SOFT)
            learn.setInk(ACCENT_TEXT)
            learn.setOnClickListener { irLearnRun() }
            addView(learn, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
            addView(note(T("ir.learn_sub")), gap(6))
        }
    }
}

private fun MainActivity.irAutoRun() {
    val ip = IrState.auto
    val name = irTvName(ip)
    val steps = IrCodes.distinct(irCatalog(), AUTO_KEY)
    IrState.stop = false
    irAuto(T("ir.auto_connecting", "name" to name), running = true)
    // each step redraws the page, only while it is still the one shown
    val show: (() -> Unit) -> Unit = { f -> ui.post { if (!isFinishing && IrState.page == "auto" && irHost.visibility == View.VISIBLE) f() } }
    Thread({
        var hear: IrHear? = null
        try {
            var adb: dev.tvremote.core.adb.Adb? = null
            for (h in listOf(ip, pref("ts:$ip"), pref("lan:$ip")).filter { it.isNotEmpty() }.distinct()) {
                if (IrState.stop) return@Thread
                val l = dev.tvremote.core.adb.AdbLink(filesDir, h)
                IrState.link = l
                adb = try { l.get() } catch (e: Exception) { l.close(); null }
                if (adb != null) break
            }
            if (IrState.stop) return@Thread
            if (adb == null) { show { irAuto(T("ir.auto_noadb", "name" to name), T("ir.auto_noadb_sub")) }; return@Thread }
            val ear = IrHear(adb).also { hear = it }
            Thread.sleep(600)                                        // getevent lists the devices first
            var sent = ""
            val hit = irScan(steps, { it.first.keys.getValue(AUTO_KEY) }, { sent = it; irSend(it, false) }, { ear.heard(sent) }, { IrState.stop },
                { i -> show { irAuto(T("ir.auto_trying", "n" to i + 1, "total" to steps.size), steps[i].first.title, running = true) } })
            if (IrState.stop) return@Thread
            if (hit == null) { show { irAuto(T("ir.auto_none"), T("ir.auto_none_sub")) }; return@Thread }
            hit.first.keys["VOL_DOWN"]?.let { down -> repeat(2) { Thread.sleep(250); irSend(down, false) } }   // the two VOL_UP it heard
            show { irAuto(found = hit.first) }
        } catch (e: Exception) {
            Log.w(TAG, "auto find", e)
            if (!IrState.stop) show { irAuto(T("ir.auto_noadb", "name" to name), T("ir.auto_noadb_sub")) }
        } finally {
            hear?.close()
            IrState.link?.close(); IrState.link = null
        }
    }, "ir-auto").start()
}

// ---------------------------------------------------------------- Học phím: the box's own remote, read key by key through getevent (IrLearn.kt)

/** [key] is the one asked for now (-1: none yet), [got] how many are in; [fail] a line when the last try gave nothing usable. */
private fun MainActivity.irLearn(say: String, sub: String = "", got: Int = 0, busy: Boolean = true) {
    IrState.onFind = true
    IrState.page = "auto"
    irPage(T("ir.find"), { irStop(); irAuto() }) {
        val card = col()
        card.background = box(CARD, 14)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        val big = label(say, 20, TEXT)
        big.typeface = Typeface.DEFAULT_BOLD
        card.addView(big)
        if (sub.isNotEmpty()) card.addView(label(sub, 14, MUTED), gap(4))
        if (busy) card.addView(android.widget.ProgressBar(this@irLearn, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; progressTintList = android.content.res.ColorStateList.valueOf(ACCENT) }, gap(8))
        addView(card, gap(14))
        if (busy) {
            addView(button(T("ir.learn_skip"), BTN).apply { setOnClickListener { IrState.skip = true } }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(4) })
            if (got >= IrLearn.MIN) addView(button(T("ir.learn_done", "n" to got), ACCENT).apply { setOnClickListener { IrState.finish = true } },
                LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        } else addView(button(T("ir.retry"), ACCENT).apply { setOnClickListener { irLearnRun() } }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(4) })
    }
}

private fun irKeyName(key: String) = when (key) {
    "POWER" -> T("ir.key_power")
    "HOME" -> T("ir.key_home")
    "BACK" -> T("ir.key_back")
    "UP" -> T("ir.key_up")
    "DOWN" -> T("ir.key_down")
    "LEFT" -> T("ir.key_left")
    "RIGHT" -> T("ir.key_right")
    "OK" -> T("ir.key_ok")
    "MENU" -> T("ir.key_menu")
    "VOL_UP" -> T("ir.key_vol_up")
    "VOL_DOWN" -> T("ir.key_vol_down")
    "MUTE" -> T("ir.key_mute")
    else -> key
}

private fun MainActivity.irLearnRun() {
    val ip = IrState.auto
    val name = irTvName(ip)
    IrState.stop = false; IrState.skip = false; IrState.finish = false
    irLearn(T("ir.auto_connecting", "name" to name))
    val show: (() -> Unit) -> Unit = { f -> ui.post { if (!isFinishing && IrState.page == "auto" && irHost.visibility == View.VISIBLE) f() } }
    Thread({
        var hear: IrHear? = null
        try {
            var adb: dev.tvremote.core.adb.Adb? = null
            for (h in listOf(ip, pref("ts:$ip"), pref("lan:$ip")).filter { it.isNotEmpty() }.distinct()) {
                if (IrState.stop) return@Thread
                val l = dev.tvremote.core.adb.AdbLink(filesDir, h)
                IrState.link = l
                adb = try { l.get() } catch (e: Exception) { l.close(); null }
                if (adb != null) break
            }
            if (IrState.stop) return@Thread
            if (adb == null) { show { irLearn(T("ir.auto_noadb", "name" to name), T("ir.auto_noadb_sub"), busy = false) }; return@Thread }
            val model = try { adb.shell("getprop ro.product.model").trim() } catch (e: Exception) { "" }.ifEmpty { name }
            val ear = IrHear(adb).also { hear = it }
            Thread.sleep(600)
            val scans = LinkedHashMap<String, Long>()
            for ((i, key) in IrLearn.KEYS.withIndex()) {
                if (IrState.finish || IrState.stop) break
                IrState.skip = false
                show { irLearn(T("ir.learn_press", "key" to irKeyName(key)), T("ir.learn_step", "n" to i + 1, "total" to IrLearn.KEYS.size) + (if (key == "POWER") " · " + T("ir.learn_power") else ""), scans.size) }
                val t = irNow()
                while (!IrState.skip && !IrState.finish && !IrState.stop && ear.lastScan <= t) Thread.sleep(40)
                if (ear.lastScan > t && ear.scanValue >= 0) { scans[key] = ear.scanValue; Thread.sleep(500) }   // a held key repeats: let it go
            }
            if (IrState.stop) return@Thread
            Log.i(TAG, "learned scancodes " + scans.entries.joinToString { it.key + "=" + java.lang.Long.toHexString(it.value) })
            val keys = IrLearn.build(scans)
            if (keys == null) { show { irLearn(T("ir.learn_bad"), T("ir.learn_bad_sub", "n" to IrLearn.MIN), busy = false) }; return@Thread }
            val p = irCustomKeep("custom:$ip", model, keys)
            irSave(p); prefs.edit().putString("$PROFILE:$ip", p.id).apply()
            show { Toast.makeText(this, T("ir.found", "name" to p.title), Toast.LENGTH_LONG).show(); irRemote() }
        } catch (e: Exception) {
            Log.w(TAG, "learn", e)
            if (!IrState.stop) show { irLearn(T("ir.auto_noadb", "name" to name), T("ir.auto_noadb_sub"), busy = false) }
        } finally {
            hear?.close()
            IrState.link?.close(); IrState.link = null
        }
    }, "ir-learn").start()
}
