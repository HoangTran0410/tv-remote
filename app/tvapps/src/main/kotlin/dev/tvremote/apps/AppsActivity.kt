package dev.tvremote.apps

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tvremote.tvapps.AppList
import dev.tvremote.tvui.TvState
import dev.tvremote.tvui.TvUi
import dev.tvremote.tvui.dp
import dev.tvremote.tvui.pill
import dev.tvremote.update.Release
import dev.tvremote.update.SelfUpdate
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import dev.tvremote.tvui.TvScroll
import dev.tvremote.tvui.TvHScroll

/** The app, only to start the error guard and the anonymous error reports. */
class TvAppsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        dev.tvremote.guard.Guard.install(this, { c -> dev.tvremote.guard.GuardWords(c.getString(R.string.err_title), c.getString(R.string.err_last_time),
            c.getString(R.string.err_copy), c.getString(R.string.err_copied), c.getString(R.string.err_share), c.getString(R.string.err_close)) { n ->
            c.getString(R.string.err_repeat, n) } })
        dev.tvremote.report.OwnReport.start(this, "tvapps")              // anonymous error reports (TV Setup's switch, else on)
    }
}

/**
 * TV Apps' one screen: the signed list (AppList, from the site), a pill per category, a row per app. OK installs an app that is not
 * on the TV and opens one that is; hold OK / Menu: Details, Install again, Open. An install goes through TV Setup's ADB link when it
 * has one (HomeInfo "tvapps": no question on the TV), else through Android's installer, which asks. Either way the APK is the
 * author's own release, checked against the author's certificate before anything installs it.
 */
class AppsActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val work = Executors.newFixedThreadPool(3)
    private val lang by lazy { resources.configuration.let { if (Build.VERSION.SDK_INT >= 24) it.locales[0] else @Suppress("DEPRECATION") it.locale }.language }
    private val abis: List<String> = Build.SUPPORTED_ABIS.toList()
    private var list: AppList.List_? = null
    private var cat = ""                                              // "" = All
    private val icons = HashMap<String, Bitmap>()
    private var pending: String? = null                               // an app waiting for "install unknown apps" to be allowed
    private lateinit var state: TvState
    private lateinit var pills: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var foot: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val mx = resources.getDimensionPixelSize(R.dimen.tv_margin_x); val my = resources.getDimensionPixelSize(R.dimen.tv_margin_y)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(mx, my, mx, 0) }
        page.addView(header())
        pills = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        page.addView(TvHScroll(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(pills) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, my) }
        foot = TvUi.text(this, "", TvUi.SP_COUNT, TvUi.DIM).apply { setPadding(dp(12), dp(12), dp(12), 0) }
        scroll = TvScroll(this).apply {
            isVerticalScrollBarEnabled = false; clipToPadding = false
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(rows); addView(foot) })
        }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        state = TvState(this).apply { add(TvUi.button(context, getString(R.string.retry)) { load(true) }) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(TvUi.BG)
            addView(page, FrameLayout.LayoutParams(-1, -1)); addView(state, FrameLayout.LayoutParams(-1, -1))
        })
        load(false)
    }

    private val upd by lazy { SelfUpdate(this, "tvapps", "tv-apps.apk") }

    override fun onResume() {
        super.onResume()
        upd.check({ askUpdate(it) }, { updating(it) })
        if (list != null) render()                                    // back from an app or Android's installer: what is on the TV now
        val p = pending
        if (p != null && canInstall()) { pending = null; list?.get(p)?.let { install(it) } }
        registerReceiver(added, IntentFilter().apply {                // Android's installer closes before the app lands
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        })
    }

    override fun onPause() { super.onPause(); try { unregisterReceiver(added) } catch (e: Exception) { } }   // onResume may have thrown above its registerReceiver

    private val added = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (list != null) render() }
    }

    override fun onDestroy() { super.onDestroy(); work.shutdownNow() }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), dp(12))
        val head = resources.getDimensionPixelSize(R.dimen.tv_head_icon)
        addView(ImageView(context).apply { setImageResource(R.drawable.icon); rounded(this, resources.getDimension(R.dimen.tv_head_radius)) },
            LinearLayout.LayoutParams(head, head).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.tv_head_gap) })
        addView(TvUi.text(context, getString(R.string.app_name), 24f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(TvUi.text(context, getString(R.string.menu_hint), TvUi.SP_COUNT, TvUi.DIM))
    }

    private fun rounded(v: View, r: Float) {
        v.outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
        v.clipToOutline = true
    }

    /** The list, then the screen; [fresh] asks the site again (Try again). No list and no copy kept: No internet · Try again. */
    private fun load(fresh: Boolean) {
        state.visibility = View.VISIBLE; state.loading(getString(R.string.loading))
        work.execute {
            val l = try { AppList.fetch(filesDir, fresh) } catch (e: Exception) { null }
            ui.post {
                if (isDestroyed) return@post
                if (l == null) { state.message(getString(R.string.offline)); return@post }
                list = l; state.visibility = View.GONE
                pillsFor(l); render()
                rows.getChildAt(0)?.requestFocus()
            }
        }
    }

    private fun pillsFor(l: AppList.List_) {
        pills.removeAllViews()
        val all = listOf("" to getString(R.string.all)) + l.cats.filter { c -> l.apps.any { it.cat == c.id } }.map { it.id to it.name(lang) }
        for ((id, name) in all) pills.addView(pill(name, focused = { v -> v.parent?.requestChildFocus(v, v) }) {
            cat = id; for (i in 0 until pills.childCount) pills.getChildAt(i).isSelected = pills.getChildAt(i).tag == id
            render(); scroll.scrollTo(0, 0)
        }.apply { tag = id; isSelected = id == cat; this.id = View.generateViewId() })
    }

    private fun installed(pkg: String) = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
    private fun installed(a: AppList.App) = a.pkgs.any { installed(it) }

    /** One row per app of the chosen category, in the list's order; the focus stays on the same app when the rows are drawn again. */
    private fun render() {
        val l = list ?: return
        val had = (currentFocus?.tag as? String)?.takeIf { currentFocus?.parent === rows }
        rows.removeAllViews()
        for (a in l.apps.filter { cat.isEmpty() || it.cat == cat }) rows.addView(appRow(l, a))
        val on = (0 until pills.childCount).map { pills.getChildAt(it) }.firstOrNull { it.isSelected }
        rows.getChildAt(0)?.nextFocusUpId = on?.id ?: View.NO_ID                // Up from the list: the chosen pill, not the nearest
        foot.text = getString(R.string.updated, l.updated)
        had?.let { id -> (0 until rows.childCount).map { rows.getChildAt(it) }.firstOrNull { it.tag == id }?.requestFocus() }
    }

    private fun why(a: AppList.App) = when (AppList.why(a, Build.VERSION.SDK_INT, abis)) {
        "sdk" -> getString(R.string.why_sdk, sdkName(a.minSdk)); "abi" -> getString(R.string.why_abi); else -> ""
    }

    private fun sdkName(sdk: Int) = mapOf(21 to "5", 22 to "5.1", 23 to "6", 24 to "7", 25 to "7.1", 26 to "8", 27 to "8.1", 28 to "9", 29 to "10",
        30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15")[sdk] ?: "API $sdk"

    private fun appRow(l: AppList.List_, a: AppList.App): View = LinearLayout(this).apply {
        tag = a.id
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(16), dp(10))
        background = TvUi.states(context, Color.TRANSPARENT, TvUi.dp(context, TvUi.R_ROW), edge = false)
        isFocusable = true; isClickable = true; isLongClickable = true
        val on = installed(a); val why = why(a)
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; rounded(this, TvUi.dp(context, TvUi.R_TILE)) }
        addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(16) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TvUi.text(context, a.name, 18f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(TvUi.text(context, a.desc(lang), TvUi.SP_ROW, TvUi.DIM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TvUi.text(context, when { on -> "✓ " + getString(R.string.on_tv); why.isNotEmpty() -> why; else -> getString(R.string.install) },
            TvUi.SP_COUNT + 1, if (on) TvUi.ACCENT else TvUi.DIM).apply { setPadding(dp(16), 0, 0, 0) })
        setOnClickListener { if (installed(a)) open(a) else if (why.isEmpty()) install(a) else details(l, a) }
        setOnLongClickListener { menu(l, a); true }
        icons[a.id]?.let { icon.setImageBitmap(it) } ?: work.execute {
            val bmp = try { AppList.icon(l, a, filesDir).let { BitmapFactory.decodeByteArray(it, 0, it.size) } } catch (e: Exception) { null }
            if (bmp != null) ui.post { icons[a.id] = bmp; icon.setImageBitmap(bmp) }
        }
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_MENU) {
            val id = currentFocus?.takeIf { it.parent === rows }?.tag as? String
            val l = list
            if (id != null && l != null) { l[id]?.let { menu(l, it) }; return true }
        }
        return super.onKeyDown(code, e)
    }

    private fun open(a: AppList.App) {
        if (a.setup.isNotEmpty()) return toSetup(a)
        val i = packageManager.getLeanbackLaunchIntentForPackage(a.pkg) ?: packageManager.getLaunchIntentForPackage(a.pkg) ?: return
        try { startActivity(i) } catch (e: Exception) { }
    }

    /** Hold OK / Menu: Open, Install again, Details. */
    private fun menu(l: AppList.List_, a: AppList.App) {
        val on = installed(a)
        val items = ArrayList<TvUi.Item>()
        if (on) items += TvUi.Item(R.drawable.ic_play, getString(R.string.open)) { open(a) }
        if (why(a).isEmpty() && !(on && a.setup.isNotEmpty())) items += TvUi.Item(R.drawable.ic_download, getString(if (on) R.string.reinstall else R.string.install)) { install(a) }
        items += TvUi.Item(R.drawable.ic_info, getString(R.string.details)) { details(l, a) }
        TvUi.menu(this, R.style.Dialog, a.name, items, R.drawable.ic_check)
    }

    /** What the app is, its notes, where its source is and its licence; the button that fits. */
    private fun details(l: AppList.List_, a: AppList.App) {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 520)
        TvUi.sheetTitle(box, a.name)
        TvUi.sheetNote(box, a.desc(lang))
        a.notes(lang).takeIf { it.isNotEmpty() }?.let { TvUi.sheetNote(box, it) }
        why(a).takeIf { it.isNotEmpty() }?.let { TvUi.sheetNote(box, it).setTextColor(TvUi.BAD) }
        TvUi.sheetNote(box, getString(R.string.source, a.source.removePrefix("https://")) + "\n" + getString(R.string.license, a.license)).textSize = TvUi.SP_COUNT
        val on = installed(a)
        val first = box.childCount
        if (on) box.addView(TvUi.row(this, R.drawable.ic_play, getString(R.string.open)) { dlg.dismiss(); open(a) })
        else if (why(a).isEmpty()) box.addView(TvUi.row(this, R.drawable.ic_download, getString(R.string.install)) { dlg.dismiss(); install(a) })
        box.addView(TvUi.row(this, R.drawable.ic_check, getString(R.string.ok)) { dlg.dismiss() })
        show(dlg, box, first)
    }

    /** A sheet shown with the focus on its row [at]: the sheet's own first-focus came a press late after a menu closed. */
    private fun show(dlg: Dialog, box: LinearLayout, at: Int) { dlg.show(); box.post { box.getChildAt(at)?.requestFocus() } }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    // ---- installing: TV Setup's ADB link first (no question), else Android's installer ----

    /** A sheet with one line that says how far it is and Cancel (Back too); [stop] runs on either. */
    private class Progress(val dlg: Dialog, val line: TextView)

    private fun progress(a: AppList.App, stop: () -> Unit): Progress {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 460)
        TvUi.sheetTitle(box, a.name)
        val line = TvUi.sheetNote(box, getString(R.string.downloading, 0))
        box.addView(TvUi.row(this, R.drawable.ic_close, getString(R.string.cancel)) { dlg.dismiss() })
        dlg.setOnDismissListener { stop() }
        show(dlg, box, box.childCount - 1)
        return Progress(dlg, line)
    }

    private fun done(p: Progress) { p.dlg.setOnDismissListener(null); p.dlg.dismiss() }

    private fun notice(title: String, msg: String) {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 460)
        TvUi.sheetTitle(box, title); TvUi.sheetNote(box, msg)
        box.addView(TvUi.row(this, R.drawable.ic_check, getString(R.string.ok)) { dlg.dismiss() })
        show(dlg, box, box.childCount - 1)
    }

    private fun words(m: String) = when (m) {
        AppList.BAD_CERT -> getString(R.string.cert_bad); AppList.NO_APK -> getString(R.string.no_apk)
        AppList.NO_NET -> getString(R.string.offline); else -> getString(R.string.failed, m)
    }

    private fun setup(op: String, id: String): JSONObject? = try {
        val info = packageManager.resolveContentProvider(SETUP_AUTH, 0)
        if (info?.packageName != SETUP_PKG) null
        else contentResolver.call(Uri.parse("content://$SETUP_AUTH"), "tvapps", JSONObject().put("op", op).put("id", id).toString(), null)
            ?.getString("json")?.let { JSONObject(it) }
    } catch (e: Exception) { null }                                    // missing, older than TV Apps, another key: Android's installer

    /**
     * An app TV Setup installs on a screen of its own ("setup": TV Setup Voice, by its rule, which also takes it off again): that
     * screen, handed back by TV Setup and started from here (we are in front; Android 14 drops TV Setup's own start from behind).
     * Without TV Setup it cannot work: said, with TV Setup to install first. A TV Setup too old to answer: its own first screen.
     */
    private fun toSetup(a: AppList.App) {
        if (!installed(SETUP_PKG)) {
            val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 520)
            TvUi.sheetTitle(box, a.name); TvUi.sheetNote(box, getString(R.string.needs_setup, a.name))
            val first = box.childCount
            list?.apps?.firstOrNull { it.pkg == SETUP_PKG }?.let { s ->
                box.addView(TvUi.row(this, R.drawable.ic_download, getString(R.string.install_setup)) { dlg.dismiss(); install(s) }) }
            box.addView(TvUi.row(this, R.drawable.ic_close, getString(R.string.cancel)) { dlg.dismiss() })
            return show(dlg, box, first)
        }
        work.execute {
            @Suppress("DEPRECATION") val go = try {
                if (packageManager.resolveContentProvider(SETUP_AUTH, 0)?.packageName != SETUP_PKG) null
                else contentResolver.call(Uri.parse("content://$SETUP_AUTH"), "tvapps", JSONObject().put("op", "open").put("screen", a.setup).toString(), null)
                    ?.getParcelable<android.app.PendingIntent>("go")
            } catch (e: Exception) { null }
            ui.post {
                try {
                    if (go == null) startActivity(packageManager.getLeanbackLaunchIntentForPackage(SETUP_PKG) ?: packageManager.getLaunchIntentForPackage(SETUP_PKG) ?: return@post)
                    else if (Build.VERSION.SDK_INT < 23) go.send()
                    else go.send(this, 0, null, null, null, null, android.app.ActivityOptions.makeBasic().apply {
                        if (Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED) }.toBundle())
                } catch (e: Exception) { }
            }
        }
    }

    private fun install(a: AppList.App) {
        if (a.setup.isNotEmpty()) return toSetup(a)
        val stop = AtomicBoolean(false)
        val job = AtomicReference<String?>(null)
        val p = progress(a) { stop.set(true); job.get()?.let { j -> work.execute { setup("cancel", j) } } }
        work.execute {
            val started = setup("install", a.id)
            if (started != null && started.optString("status") != "error" && started.optString("id").isNotEmpty()) {
                val id = started.getString("id")
                job.set(id)
                if (stop.get()) setup("cancel", id)                    // Cancel came before the call returned: it saw no job to cancel
                viaSetup(a, p, id) { stop.get() }
            } else ui.post { if (!stop.get()) { done(p); viaAndroid(a) } }
        }
    }

    /** TV Setup's job, asked every second: download % then installing, until done / error / cancelled. Off the main thread. */
    private fun viaSetup(a: AppList.App, p: Progress, id: String, stopped: () -> Boolean) {
        while (!stopped()) {
            val j = setup("job", id) ?: JSONObject().put("status", "error").put("msg", "TV Setup")
            val status = j.optString("status")
            ui.post {
                if (stopped() || isDestroyed) return@post
                when (status) {
                    "running" -> p.line.text = if (j.optString("step") == "install") getString(R.string.installing) else getString(R.string.downloading, j.optInt("pct"))
                    "done" -> { done(p); render(); notice(a.name, getString(R.string.done, a.name)) }
                    "cancelled" -> done(p)
                    else -> { done(p); notice(a.name, words(j.optString("msg"))) }
                }
            }
            if (status != "running") return
            try { Thread.sleep(1000) } catch (e: InterruptedException) { return }   // the screen closed (onDestroy: shutdownNow); TV Setup goes on installing
        }
    }

    /** Without TV Setup's link: "install unknown apps" first if Android wants it, then download here and hand it to Android's installer. */
    private fun viaAndroid(a: AppList.App) {
        if (!canInstall()) {                                           // say why Android's switch comes up, then open it
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            if (i.resolveActivity(packageManager) != null) {
                val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 460)
                TvUi.sheetTitle(box, a.name); TvUi.sheetNote(box, getString(R.string.allow_note))
                box.addView(TvUi.row(this, R.drawable.ic_check, getString(R.string.ok)) {
                    dlg.dismiss()
                    try { startActivity(i); pending = a.id } catch (e: Exception) { }   // onResume goes on once it is allowed
                })
                box.addView(TvUi.row(this, R.drawable.ic_close, getString(R.string.cancel)) { dlg.dismiss() })
                show(dlg, box, box.childCount - 2)
                return
            }                                                         // no such screen: Android's installer asks its own way
        }
        val stop = AtomicBoolean(false)
        val p = progress(a) { stop.set(true) }
        SelfUpdate.Installed.failed = { m -> ui.post { notice(a.name, getString(R.string.failed, m)) } }
        work.execute {
            var last = -1
            try {
                val f = AppList.download(a, abis, cacheDir, { pct -> if (pct != last) { last = pct; ui.post { if (!stop.get()) p.line.text = getString(R.string.downloading, pct) } } }, { stop.get() })
                try {
                    ui.post { p.line.text = getString(R.string.installing) }
                    SelfUpdate.commit(this, f, Intent(this, SelfUpdate.Installed::class.java), false, false)
                } finally { f.delete() }                              // the session holds its own copy
                ui.post { done(p) }                                   // Android's installer shows its question; onResume shows the result
            } catch (e: Exception) {
                ui.post { done(p); if (!stop.get()) notice(a.name, words(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    // ---- TV Apps' own update (SelfUpdate), as TV Channels does ----

    private fun askUpdate(r: Release) {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 420)
        TvUi.sheetTitle(box, getString(R.string.upd_title, r.version))
        TvUi.sheetNote(box, getString(R.string.upd_note, upd.version()))
        box.addView(TvUi.row(this, R.drawable.ic_download, getString(R.string.upd_now)) { dlg.dismiss(); updating(r) })
        box.addView(TvUi.row(this, R.drawable.ic_clock, getString(R.string.upd_later)) { upd.later(); dlg.dismiss() })
        box.addView(TvUi.row(this, R.drawable.ic_eye_off, getString(R.string.upd_skip)) { upd.skip(r); dlg.dismiss() })
        dlg.show()
        box.post { box.getChildAt(2)?.requestFocus() }
    }

    private fun updating(r: Release) {
        val (dlg, box) = TvUi.sheet(this, R.style.Dialog, 420)
        TvUi.sheetTitle(box, getString(R.string.upd_title, r.version))
        val line = TvUi.sheetNote(box, getString(R.string.upd_loading, 0))
        box.addView(TvUi.row(this, R.drawable.ic_close, getString(R.string.upd_cancel)) { dlg.dismiss() })
        dlg.setOnDismissListener { upd.cancel() }
        dlg.show()
        box.post { box.getChildAt(2)?.requestFocus() }
        val started = upd.install(r, { pct ->
            line.text = getString(R.string.upd_loading, pct)
            if (pct >= 100) { dlg.setOnDismissListener(null); dlg.dismiss() }
        }, { m ->
            dlg.setOnDismissListener(null); dlg.dismiss()
            notice(getString(R.string.upd_title, r.version), getString(R.string.upd_failed, m))
        })
        if (!started) { dlg.setOnDismissListener(null); dlg.dismiss() }
    }

    companion object {
        private const val SETUP_PKG = "dev.tvremote.setup"
        private const val SETUP_AUTH = "dev.tvremote.setup.home"       // TV Setup's HomeInfo
    }
}
