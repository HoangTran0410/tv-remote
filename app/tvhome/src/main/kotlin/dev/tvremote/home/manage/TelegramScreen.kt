package dev.tvremote.home.manage

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tvremote.core.web.Qr
import dev.tvremote.home.Font
import dev.tvremote.home.HomeActivity
import dev.tvremote.home.HomeConfig
import dev.tvremote.home.R
import dev.tvremote.update.Release
import dev.tvremote.tvui.TvGlass
import org.json.JSONObject
import dev.tvremote.tvui.TvScroll

/**
 * "Control from Telegram" (Customize, top level): what TV Setup's Telegram bot needs next, by its state. TV Home has no bot and
 * no token; it asks TV Setup's HomeInfo provider ("telegram", arg "" to read or "code" for a fresh pairing code) and shows
 * `ready` = the QR of the t.me link + steps + the code's countdown, `none` / `token_missing` = how to make a bot and a QR to TV Setup's
 * page on this TV (where the token is pasted), no answer = TV Setup missing or too old (the Downloader code, no silent download).
 * Same layout as [PhotoScreen]: QR on the left, words on the right, Close pinned under the scroll (DECISIONS "A dialog taller than the screen").
 */
object TelegramScreen {
    private val SOFT = 0xFFB8C7E6.toInt()
    private val BAD = 0xFFFFB4A9.toInt()
    private val DIM = 0xA6FFFFFF.toInt()
    private val INK = 0xFF111318.toInt()

    /** What TV Setup said: [state] is "missing" (no answer at all) or the provider's own word. */
    class Answer(val state: String, val code: String = "", val left: Int = 0, val link: String = "") {
        companion object {
            fun parse(b: android.os.Bundle?): Answer {
                val j = try { JSONObject(b?.getString("json") ?: return Answer("missing")) } catch (e: Exception) { return Answer("missing") }
                val s = j.optString("state")
                if (s != "ready" && s != "none" && s != "token_missing") return Answer("missing")   // an error, or a state a newer TV Setup invented
                return Answer(s, j.optString("code"), j.optInt("code_left"), j.optString("link"))
            }
        }
    }

    /** One blocking call to the provider (a thread of the caller's): null Bundle = no provider, an old TV Setup (unknown method) or a refusal. */
    fun ask(a: Activity, arg: String): Answer = try {
        Answer.parse(a.contentResolver.call(Uri.parse(HomeConfig.USAGE), "telegram", arg, null))
    } catch (e: Exception) { Answer("missing") }

    /** The TV Setup this TV has, under the number needed: "not installed", "this TV has 0.21.0", or new enough but not answering. */
    private fun haveLine(a: Activity): String {
        val have = try { a.packageManager.getPackageInfo("dev.tvremote.setup", 0).versionName ?: "" } catch (e: Exception) { null }
        return when {
            have == null -> a.getString(R.string.tg_have_none)
            have.isNotEmpty() && Release.compare(have, HomeConfig.TELEGRAM_SETUP) >= 0 -> a.getString(R.string.tg_have_mute, have)
            else -> a.getString(R.string.tg_have, have)
        }
    }

    fun show(a: Activity): Dialog {
        val d = a.resources.displayMetrics.density
        fun dp(v: Number) = (v.toFloat() * d).toInt()
        fun label(t: String, size: Float, color: Int) = Font.on(TextView(a)).apply { text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER }
        val main = Handler(Looper.getMainLooper())
        val dialog = Dialog(a, R.style.Menu)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(32), dp(20), dp(32), dp(20)); clipToPadding = false; clipChildren = false }
        col.addView(label(a.getString(R.string.tg_title), 22f, Color.WHITE).apply { typeface = Font.medium })
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; clipToPadding = false }
        col.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        fun pill(text: String, go: () -> Unit) = Font.on(TextView(a)).apply {
            this.text = text; textSize = 16f; setTextColor(INK); gravity = Gravity.CENTER; typeface = Font.medium
            isFocusable = true; isClickable = true; setPadding(dp(28), dp(11), dp(28), dp(11))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.WHITE) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(SOFT) })
            }
            setOnClickListener { go() }
        }
        val close = pill(a.getString(R.string.pq_close)) { dialog.dismiss() }

        // the countdown: one tick a second while the code is live
        var left = 0
        var timer: TextView? = null
        var again: View? = null
        val tick = object : Runnable {
            override fun run() {
                val t = timer ?: return
                if (left > 0) left--
                t.text = if (left > 0) a.getString(R.string.tg_code_left, left / 60, left % 60) else a.getString(R.string.tg_code_expired)
                t.setTextColor(if (left > 0) SOFT else BAD)
                if (left == 0) again?.requestFocus() else main.postDelayed(this, 1000)
            }
        }

        fun qrView(link: String): ImageView? {
            val bmp = Qr.bitmap(link, 8) ?: return null
            // as big as the height leaves (scanned from the sofa); the whole screen scrolls if it still does not fit
            val side = maxOf(dp(160), minOf(dp(320), a.resources.displayMetrics.heightPixels - dp(250)))
            return ImageView(a).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(BitmapDrawable(a.resources, bmp).apply { isFilterBitmap = false })   // sharp squares
                layoutParams = LinearLayout.LayoutParams(side, side)
            }
        }

        /** QR on the left, [words] on the right. */
        fun twoColumns(qr: View?, words: List<View>): View {
            val row = LinearLayout(a).apply { clipChildren = false; clipToPadding = false; gravity = Gravity.CENTER_VERTICAL }
            if (qr != null) row.addView(qr)
            val right = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; gravity = Gravity.CENTER_VERTICAL }
            for (w in words) right.addView(w, LinearLayout.LayoutParams(if (w is TextView && w.isFocusable) -2 else -1, -2).apply { if (right.childCount > 0) topMargin = dp(12) })
            row.addView(right, LinearLayout.LayoutParams(if (qr != null) 0 else -1, -2, 1f).apply { if (qr != null) leftMargin = dp(28) })
            return row
        }
        fun steps(res: Int) = label(a.getString(res), 16f, SOFT).apply { gravity = Gravity.START }

        fun render(r: Answer) {
            if (!dialog.isShowing) return
            main.removeCallbacks(tick); timer = null; again = null
            body.removeAllViews()
            var focus: View = close
            when (r.state) {
                "ready" -> {
                    val qr = if (r.link.isNotEmpty()) qrView(r.link) else null
                    val words = mutableListOf<View>(steps(R.string.tg_ready_steps))
                    left = r.left + 1
                    val t = label("", 15f, SOFT).apply { gravity = Gravity.START }
                    timer = t; words.add(t)
                    val b = pill(a.getString(R.string.tg_new_code)) {
                        Thread({ val n = ask(a, "code"); main.post { render(n) } }, "home-telegram").apply { isDaemon = true }.start()
                    }
                    again = b; words.add(b)
                    body.addView(twoColumns(qr, words))
                    tick.run()                               // paints the first value, then once a second
                    focus = b
                }
                "none", "token_missing" -> {
                    val none = r.state == "none"
                    val ip = ManageServer.lan(a)
                    val link = if (ip != null) "http://$ip:8765" else null    // TV Setup's web page on this TV: asks for its 6-digit code, then the Telegram card
                    val words = mutableListOf<View>()
                    if (none) words.add(label(a.getString(R.string.tg_none_title), 18f, Color.WHITE).apply { gravity = Gravity.START; typeface = Font.medium })
                    words.add(steps(if (none) R.string.tg_none_steps else R.string.tg_token_steps))
                    if (link != null) words.add(label(link.removePrefix("http://"), 14f, DIM).apply { gravity = Gravity.START })
                    else words.add(label(a.getString(R.string.tg_no_net), 15f, BAD).apply { gravity = Gravity.START })
                    body.addView(twoColumns(link?.let { qrView(it) }, words))
                }
                else -> {   // TV Setup absent, too old, or not answering: the same way to get it as the power button's
                    val dl = a.packageManager.getLaunchIntentForPackage("com.esaba.downloader")
                    val words = mutableListOf<View>(
                        label(a.getString(R.string.tg_need, HomeConfig.TELEGRAM_SETUP), 18f, Color.WHITE).apply { gravity = Gravity.START; typeface = Font.medium },
                        label(haveLine(a), 15f, BAD).apply { gravity = Gravity.START },
                        label(a.getString(R.string.tg_need_code, HomeActivity.SETUP_CODE), 16f, SOFT).apply { gravity = Gravity.START },
                        label(a.getString(R.string.tg_need_phone), 14f, DIM).apply { gravity = Gravity.START })
                    if (dl != null) {
                        val b = pill(a.getString(R.string.tg_open_dl)) { try { a.startActivity(dl); dialog.dismiss() } catch (e: Exception) { } }
                        words.add(b); focus = b
                    }
                    body.addView(twoColumns(null, words))
                }
            }
            focus.requestFocus()
        }

        body.addView(label(a.getString(R.string.tg_loading), 16f, SOFT))
        // Close under the scroll, not in it (a 540 dp tall TV squashed the last row otherwise)
        val scroll = TvScroll(a).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(col) }
        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = TvGlass.panelBg(a); setPadding(0, 0, 0, dp(20))
            addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
            addView(close, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }
        val w = minOf(a.resources.displayMetrics.widthPixels - dp(64), dp(820))
        dialog.setContentView(panel, ViewGroup.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT); setDimAmount(0.6f) }
        dialog.setOnDismissListener { main.removeCallbacks(tick) }
        dialog.setOnShowListener { close.requestFocus() }
        dialog.show()
        Thread({
            var r = ask(a, "")
            if (r.state == "ready" && r.link.isEmpty()) r = ask(a, "code")      // listening but no code live: make one
            main.post { render(r) }
        }, "home-telegram").apply { isDaemon = true }.start()
        return dialog
    }
}
