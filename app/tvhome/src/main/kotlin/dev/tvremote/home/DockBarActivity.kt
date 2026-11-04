package dev.tvremote.home

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.tvremote.home.apps.HomeLock
import dev.tvremote.home.apps.shrink
import dev.tvremote.tvui.TvGlass
import dev.tvremote.tvui.TvHScroll
import org.json.JSONObject

/**
 * The Home bar over the app in front (option "homebar", default off): TV Setup's key filter starts this for a Home press while an
 * app is on screen, as it starts [ControlCenterActivity]. A see-through activity, so the app beneath stays visible and running.
 * OK opens an app (the Home item first, then the dock's apps, then the apps opened last: TV Setup's HomeInfo "recent", in event
 * order), Back closes the bar and leaves the app as it was, Home again (the filter sees TV Home in front) goes to the home screen.
 * Apps behind a PIN are left out (the bar would be a way round the lock). Nothing here runs while the option is off.
 */
class DockBarActivity : Activity() {
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var row: LinearLayout

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Lang.wrap(base))
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); clipChildren = false; clipToPadding = false }
        val scroll = TvHScroll(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(row) }
        val bar = FrameLayout(this).apply { background = TvGlass.panelBg(this@DockBarActivity); addView(scroll) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(TvGlass.DIM_LIGHT)
            addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36); leftMargin = dp(48); rightMargin = dp(48) })
        }
        setContentView(root)
        val dock = Store.list(this, "dock")
        fill(dock, emptyList())                                        // the dock now; the recent apps join when TV Setup answers
        Thread({
            val recent = try { contentResolver.call(Uri.parse(HomeConfig.USAGE), "recent", null, null)?.getString("json")?.let { JSONObject(it).optJSONArray("apps") } } catch (e: Exception) { null }
            val list = List(recent?.length() ?: 0) { recent!!.optString(it) }
            ui.post { if (!isFinishing && !isDestroyed) fill(dock, list.filter { it !in dock }.take(RECENT_MAX)) }
        }, "home-bar").apply { isDaemon = true }.start()
    }

    /** The Home item, then the dock's apps, then the recent ones; the focus stays where it is, else lands on the first app. */
    private fun fill(dock: List<String>, recent: List<String>) {
        val kept = (0 until row.childCount).firstOrNull { row.getChildAt(it).hasFocus() }
        row.removeAllViews()
        add(getString(R.string.bar_home), resources.getDrawable(R.drawable.ic_home, theme), true) { go(Intent(this, HomeActivity::class.java)) }
        for (p in (dock.filter { Target.kind(it) == Target.Kind.APP }.take(DOCK_MAX) + recent).distinct()) {
            if (HomeLock.isLocked(this, p)) continue
            val intent = packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p) ?: continue
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { continue }
            add(label, shrink(this, try { packageManager.getApplicationIcon(p) } catch (e: Exception) { null }, 128), false) { go(intent) }
        }
        row.getChildAt(kept?.coerceAtMost(row.childCount - 1) ?: if (row.childCount > 1) 1 else 0).requestFocus()
    }

    private fun add(label: String, icon: Drawable?, glyph: Boolean, press: () -> Unit) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; isClickable = true
            setPadding(dp(8), dp(8), dp(8), dp(6)); background = TvGlass.rowBg(this@DockBarActivity, 14)
            contentDescription = label
            addView(ImageView(context).apply { setImageDrawable(icon); if (glyph) { setColorFilter(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) } }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(TextView(context).apply { text = label; textSize = 11f; setTextColor(Color.WHITE); maxLines = 1; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(84), -2).apply { topMargin = dp(4) })
            setOnClickListener { press() }
        }
        row.addView(item, LinearLayout.LayoutParams(-2, -2).apply { if (row.childCount > 0) leftMargin = dp(8) })
    }

    private fun go(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        finish()
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean =
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { finish(); true } else super.onKeyDown(code, e)

    override fun onStop() { super.onStop(); if (!isFinishing) finish() }   // the home screen or another app came up: the bar is done

    companion object {
        const val DOCK_MAX = 6                                         // the dock's apps; the recent ones follow, the bar scrolls sideways past the screen
        const val RECENT_MAX = 5
    }
}
