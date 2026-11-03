package dev.tvremote.home.manage

import dev.tvremote.tvui.Motion
import dev.tvremote.tvui.TvGlass
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.tvremote.core.web.Qr
import org.json.JSONObject
import dev.tvremote.home.Font
import dev.tvremote.home.DIM
import dev.tvremote.home.Store
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.R
import dev.tvremote.tvui.TvScroll

/**
 * The QR screen of "Manage from phone" (and of the web-page background): the code, the short address for a phone that cannot scan, and what to do. The server
 * ([ManageServer]) lives as long as this dialog, or after it while a phone that came in is still on the page (at most 10 minutes idle: [ManageServer.release]); [onSet] runs when this screen closes after a photo became the background (the settings panel behind it closes too, to show it).
 */
object PhotoScreen {
    private val SOFT = 0xFFB8C7E6.toInt()
    private val GOOD = 0xFF7BE0A1.toInt()
    private val BAD = 0xFFFFB4A9.toInt()
    private val DIM = 0xA6FFFFFF.toInt()
    private val FAINT = 0x8CFFFFFF.toInt()
    private val INK = 0xFF111318.toInt()

    /** [web] true: the same screen for "Web page as the background" (the phone page pastes a link; the D-pad picks how it updates). */
    fun show(a: Activity, web: Boolean = false, onSet: () -> Unit): Dialog {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        fun label(t: String, size: Float, color: Int) = Font.on(TextView(a)).apply { text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER }
        val dialog = Dialog(a, R.style.Menu)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(32), dp(20), dp(32), dp(20)); clipToPadding = false; clipChildren = false }   // a focused chip grows past the edge
        col.addView(label(a.getString(if (web) R.string.pq_web_title else R.string.mg_title), 22f, Color.WHITE).apply { typeface = Font.medium })
        val status = label(" ", 15f, SOFT)                        // a line kept free: the result must not push Close down
        val addr = label("", 18f, Color.WHITE).apply { typeface = Font.medium }
        val qr = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        var changed = false

        // two columns: the QR alone on the left, as big as the height lets it be (scanned from the sofa), the words, the typed
        // link and how the page updates on the right
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val right = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; gravity = Gravity.CENTER_VERTICAL }
        col.addView(LinearLayout(a).apply {
            clipChildren = false; clipToPadding = false; gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(-2, -2))
            addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(28) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        // typed on the TV itself: faster than the QR when a phone's remote app can type into the TV's field
        val field = if (web) typeUrl(a, right, status, { changed = true }, ::dp) else null
        var firstChip: View? = null
        val s = ManageServer.start(a)
        ManageServer.listener = object : ManageServer.Listener {
            override fun connected(phones: Int) { status.text = a.getString(R.string.mg_connected); status.setTextColor(GOOD) }
            override fun changed() { changed = true; status.text = a.getString(R.string.mg_changed); status.setTextColor(GOOD) }
            override fun stopped() { dialog.dismiss() }
        }
        if (s == null) {
            right.addView(label(a.getString(R.string.pq_none), 16f, BAD), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        } else {
            val side = dp(400)                                         // at most: shrunk below to what fits
            // drawn without smoothing: a QR stretched to fill its column keeps sharp squares, a blurred edge scans worse
            qr.setImageDrawable(android.graphics.drawable.BitmapDrawable(a.resources, Qr.bitmap(s.url, 8)).apply { isFilterBitmap = false })
            left.addView(qr, LinearLayout.LayoutParams(side, side))
            right.addView(label(a.getString(if (web) R.string.pq_web_step else R.string.mg_step), if (web) 14f else 16f, SOFT).apply { gravity = Gravity.START },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            if (web) right.addView(label(a.getString(R.string.pq_web_cost), 12f, FAINT).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            left.addView(label(a.getString(R.string.pq_addr), 12f, DIM), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addr.text = s.url; addr.textSize = 14f
            left.addView(addr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            if (web) firstChip = options(a, right, { changed = true; status.text = a.getString(R.string.pq_done); status.setTextColor(GOOD) }, ::dp)
            else right.addView(label(a.getString(R.string.pq_time), 13f, FAINT).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            right.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val close = Font.on(TextView(a)).apply {
            text = a.getString(R.string.pq_close); textSize = 16f; setTextColor(INK); gravity = Gravity.CENTER; typeface = Font.medium
            isFocusable = true; isClickable = true; setPadding(dp(32), dp(11), dp(32), dp(11))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.WHITE) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(SOFT) })
            }
            setOnClickListener { dialog.dismiss() }
        }
        // Close under the scroll, not in it: on a 540 dp tall TV the web page's screen did not fit, the window stopped at the
        // screen's edge and the last row, Close, was squashed until its word vanished
        val scroll = TvScroll(a).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(col) }
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = TvGlass.panelBg(a); setPadding(0, 0, 0, dp(20))
            addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))                   // weighted: it gives up what Close needs
            addView(close, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
        val w = minOf(a.resources.displayMetrics.widthPixels - dp(64), dp(if (web) 900 else 760))
        // the panel keeps 24dp of screen above and below: the QR gives up what is missing (its column too, so the words under it
        // wrap to its width, which changes their height: twice). On the left it is also at most 45% of the width
        if (qr.parent != null) repeat(2) {
            val lp = qr.layoutParams
            lp.width = minOf(lp.width, w * 45 / 100); lp.height = lp.width; left.layoutParams = left.layoutParams.apply { width = lp.width }
            panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val over = panel.measuredHeight - (a.resources.displayMetrics.heightPixels - dp(48))   // what still does not fit then scrolls
            if (over > 0) { lp.width = maxOf(dp(160), lp.width - over); lp.height = lp.width; qr.layoutParams = lp }
        }
        dialog.setContentView(panel, ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT); setDimAmount(0.6f) }
        // closing this screen (OK, Back, or the activity going away) ends the QR; a phone already on the page keeps it ([ManageServer.release])
        dialog.setOnDismissListener { ManageServer.listener = null; ManageServer.release(); if (changed) onSet() }
        dialog.setOnShowListener { (field ?: firstChip ?: close).requestFocus() }
        // the field takes the focus but the keyboard waits for OK: a phone's remote types into it without one
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        dialog.show()
        return dialog
    }

    private fun glass(r: Int, fill: Int, stroke: Int, d: (Number) -> Int) = GradientDrawable().apply { cornerRadius = d(r).toFloat(); setColor(fill); setStroke(d(1), stroke) }

    /** The link typed on the TV (a phone's remote types into it; OK raises the TV's keyboard), [Use] beside it or the keyboard's Done. No scheme: https://. The same rules as the phone page ([HomeConfig.wurl]). */
    private fun typeUrl(a: Activity, col: LinearLayout, status: TextView, set: () -> Unit, dp: (Number) -> Int): android.widget.EditText {
        val box = dev.tvremote.tvui.TvField(a).apply {
            Font.on(this); textSize = 16f; setTextColor(Color.WHITE); setHintTextColor(FAINT); hint = a.getString(R.string.pq_web_hint)
            isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(Store.get(a).optString("wurl")); setSelection(text.length)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), glass(14, TvGlass.ROW_FOCUS, Color.WHITE, dp))
                addState(intArrayOf(), glass(14, TvGlass.ROW_FILL, TvGlass.ROW_EDGE, dp))
            }
        }
        fun use() {
            val t = box.text.toString().trim()
            val url = HomeConfig.wurl(if (t.isEmpty() || Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(t)) t else "https://$t")
            if (url.isEmpty()) { status.text = a.getString(R.string.pq_web_bad); status.setTextColor(BAD); return }
            Store.change(a, JSONObject().put("wall", HomeConfig.WEB).put("wurl", url)); set()
            box.setText(url); box.setSelection(url.length)
            status.text = a.getString(R.string.pq_done); status.setTextColor(GOOD)
        }
        val go = Font.on(TextView(a)).apply {
            text = a.getString(R.string.pq_web_use); textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Font.medium
            isFocusable = true; isClickable = true; setPadding(dp(20), dp(10), dp(20), dp(10))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), glass(14, TvGlass.ROW_FOCUS, Color.WHITE, dp))
                addState(intArrayOf(), glass(14, TvGlass.ROW_FILL, TvGlass.ROW_EDGE, dp))
            }
            setOnClickListener { use() }
        }
        box.after = { go }; box.done = ::use                         // Done closes the keyboard and steps out to Use
        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(go, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        col.addView(Font.on(TextView(a)).apply { text = a.getString(R.string.pq_web_or); textSize = 13f; setTextColor(DIM); gravity = Gravity.START },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        return box
    }

    /**
     * How the page updates, as three chips the D-pad steps through (the same Store keys the Manage page writes): live or snapshot; with
     * snapshots, how often and whether the page stays open between them. Plus "use the saved page" when one is kept and not on. Returns the first.
     */
    private fun options(a: Activity, col: LinearLayout, used: () -> Unit, dp: (Number) -> Int): View {
        // the chosen way looks chosen when the focus is elsewhere (a light edge and a ✓): one lone chip read as a label
        fun look(on: Boolean) = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), glass(14, TvGlass.ROW_FOCUS, Color.WHITE, dp))
            addState(intArrayOf(), if (on) glass(14, TvGlass.ROW_FOCUS, SOFT, dp) else glass(14, TvGlass.ROW_FILL, TvGlass.ROW_EDGE, dp))
        }
        fun chip(onClick: (TextView) -> Unit) = Font.on(TextView(a)).apply {
            textSize = 14f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE); isFocusable = true; isClickable = true; setPadding(dp(10), dp(11), dp(10), dp(11))
            background = look(false)
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> Motion.pop(v, f) }
            setOnClickListener { onClick(this) }
        }
        val first = ArrayList<View>()
        val saved = Store.get(a).optString("wurl")
        if (saved.isNotEmpty() && Store.get(a).optString("wall") != HomeConfig.WEB) {
            val use = chip { Store.change(a, JSONObject().put("wall", HomeConfig.WEB).put("wurl", saved)); used() }
            use.text = a.getString(R.string.pq_web_saved, saved.removePrefix("https://").removePrefix("http://"))
            col.addView(use, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }); first.add(use)
        }
        val note = Font.on(TextView(a)).apply { textSize = 12f; setTextColor(FAINT); gravity = Gravity.CENTER }
        lateinit var live: TextView; lateinit var shot: TextView; lateinit var every: TextView; lateinit var keep: TextView
        fun tick(t: TextView, on: Boolean) {                         // the ✓ of the chosen chip, a line icon in the words' colour
            val d = if (on) a.getDrawable(R.drawable.ic_check)!!.mutate().also { it.setTint(Color.WHITE); it.setBounds(0, 0, dp(16), dp(16)) } else null
            t.setCompoundDrawablesRelative(d, null, null, null); t.compoundDrawablePadding = dp(6)
        }
        fun paint() {
            val c = Store.get(a)
            val snap = c.optString("wmode") == "snap"
            live.text = a.getString(R.string.pq_mode_live); tick(live, !snap); live.background = look(!snap)
            shot.text = a.getString(R.string.pq_mode_snap); tick(shot, snap); shot.background = look(snap)
            every.text = a.getString(R.string.pq_every, c.optInt("wsnap", HomeConfig.WSNAP_DEFAULT))
            keep.text = a.getString(if (c.optBoolean("wkeep")) R.string.pq_keep_on else R.string.pq_keep_off)
            every.visibility = if (snap) View.VISIBLE else View.INVISIBLE; keep.visibility = every.visibility   // kept in place: the panel does not grow under Close
            note.text = a.getString(if (!snap) R.string.pq_note_live else if (c.optBoolean("wkeep")) R.string.pq_note_keep else R.string.pq_note_snap)
        }
        live = chip { Store.change(a, JSONObject().put("wmode", "live")); paint() }
        shot = chip { Store.change(a, JSONObject().put("wmode", "snap")); paint() }
        every = chip { val i = HomeConfig.WSNAPS.indexOf(Store.get(a).optInt("wsnap")); Store.change(a, JSONObject().put("wsnap", HomeConfig.WSNAPS[(i + 1) % HomeConfig.WSNAPS.size])); paint() }
        keep = chip { Store.change(a, JSONObject().put("wkeep", !Store.get(a).optBoolean("wkeep"))); paint() }
        // "How it updates:" then the two ways side by side, one chosen; a snapshot's own settings on a row of their own
        col.addView(Font.on(TextView(a)).apply { text = a.getString(R.string.pq_mode_head); textSize = 13f; setTextColor(DIM); gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (first.isEmpty()) 14 else 10) })
        val row = LinearLayout(a).apply { clipChildren = false; clipToPadding = false }
        row.addView(live, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(shot, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val more = LinearLayout(a).apply { clipChildren = false; clipToPadding = false }
        more.addView(every, LinearLayout.LayoutParams(0, -2, 1f))
        more.addView(keep, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        col.addView(more, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        paint()
        return first.firstOrNull() ?: live
    }
}
