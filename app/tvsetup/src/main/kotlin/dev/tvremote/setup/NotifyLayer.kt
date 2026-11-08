package dev.tvremote.setup

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.tvremote.core.ontv.Notify
import org.json.JSONObject

/**
 * A message from a phone or computer, as a small card over whatever the TV is showing (Notify.kt in the shared code says what
 * it carries). A window that takes no keys and no touches, so the person watching keeps using the TV under it; it goes away
 * by itself, and a newer message takes the place of the one on screen.
 */
internal object NotifyLayer {
    private val ui = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var view: View? = null
    private val hide = Runnable { remove() }

    /** Shows [msg] (already through [Notify.clean]); false when the TV does not let TV Setup draw over other apps. */
    fun show(c: Context, msg: JSONObject): Boolean {
        val ctx = c.applicationContext
        app = ctx
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return false
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {   // from Guard's thread (a reminder): a window is made on the main one
            android.os.Handler(android.os.Looper.getMainLooper()).post { show(ctx, msg) }; return true
        }
        remove()
        val corner = msg.optString("corner", Notify.CORNER)
        val card = card(ctx, msg.optString("title"), msg.optString("text"))
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // Android 12+ fades a window that lets touches through to 0.8, the card's words then mixed with what is under it; a
        // TV has no touch to let through, so only a touchscreen keeps it (a small corner, for a few seconds: no Air Mouse in the way)
        val pass = if (ctx.packageManager.hasSystemFeature("android.hardware.touchscreen")) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val lp = WindowManager.LayoutParams(-2, -2, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or pass or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        lp.gravity = (if (corner.startsWith("top")) Gravity.TOP else Gravity.BOTTOM) or (if (corner.endsWith("left")) Gravity.START else Gravity.END)
        val margin = dp(ctx, 32f)
        lp.x = margin; lp.y = margin
        return try {
            ctx.getSystemService(WindowManager::class.java).addView(card, lp)
            view = card
            ui.postDelayed(hide, Notify.seconds(msg.opt("seconds")) * 1000L)
            true
        } catch (e: Exception) {
            Log.w("NotifyLayer", "no window over other apps: $e")
            false
        }
    }

    private fun remove() {
        ui.removeCallbacks(hide)
        val v = view ?: return
        view = null
        try { (app?.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)?.removeView(v) } catch (e: Exception) { }
    }

    private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    /** Dark, rounded, big enough to read from the sofa: the app's icon, then the title over the text. */
    private fun card(c: Context, title: String, text: String): View {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(c, 24f), dp(c, 20f), dp(c, 28f), dp(c, 20f))
        row.background = GradientDrawable().apply { setColor(0xF2202124.toInt()); cornerRadius = dp(c, 20f).toFloat(); setStroke(dp(c, 1f), 0x33FFFFFF) }
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO     // a message, not something to focus on
        val icon = ImageView(c)
        try { icon.setImageDrawable(c.packageManager.getApplicationIcon(c.packageName)) } catch (e: Exception) { }
        row.addView(icon, LinearLayout.LayoutParams(dp(c, 56f), dp(c, 56f)).also { it.rightMargin = dp(c, 20f) })
        val words = LinearLayout(c)
        words.orientation = LinearLayout.VERTICAL
        if (title.isNotEmpty()) words.addView(TextView(c).apply {
            this.text = title; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Typeface.DEFAULT_BOLD; maxLines = 2
        })
        if (text.isNotEmpty()) words.addView(TextView(c).apply {
            this.text = text; setTextColor(if (title.isEmpty()) Color.WHITE else 0xFFD0D3D8.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); maxLines = 6
            if (title.isNotEmpty()) setPadding(0, dp(c, 4f), 0, 0)
        })
        val max = (c.resources.displayMetrics.widthPixels * 0.42f).toInt()      // a card in a corner, not a banner across the picture
        row.addView(words, LinearLayout.LayoutParams(-2, -2))
        words.measure(View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        words.layoutParams = LinearLayout.LayoutParams(minOf(words.measuredWidth, max), -2)
        return row
    }
}

/**
 * The shell's way to [NotifyLayer] (Notify.kt): only the shell may send it (the manifest). Extra "n": the message as base64 JSON.
 * With "speak" the answer waits for the TV's voice engine ([NotifySpeak]) to say whether it can read it: {"ok":true} when it is
 * reading, plus {"speak":"noengine"} or {"speak":"nolang","lang":"vi"} when the card showed but nothing could read it.
 */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val reply = { answer: JSONObject, pending: PendingResult? ->
            val data = Base64.encodeToString(answer.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            if (pending == null) { resultCode = Activity.RESULT_OK; resultData = data }
            else { pending.resultCode = Activity.RESULT_OK; pending.resultData = data; pending.finish() }
        }
        val msg = try { Notify.clean(JSONObject(String(Base64.decode(i.getStringExtra("n") ?: "", Base64.DEFAULT), Charsets.UTF_8))) }
        catch (e: Exception) { return reply(JSONObject().put("error", e.message ?: e.javaClass.simpleName), null) }
        if (msg.optInt("delay") > 0) {                                  // "remind me in 10 min": kept by NotifyLater, shown by Guard's check
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return reply(JSONObject().put("error", "overlay"), null)
            dev.tvremote.setup.rules.NotifyLater.add(c, msg)
            return reply(JSONObject().put("ok", true).put("later", msg.optInt("delay")), null)
        }
        if (!NotifyLayer.show(c, msg)) return reply(JSONObject().put("error", "overlay"), null)
        if (!msg.optBoolean("speak")) return reply(JSONObject().put("ok", true), null)
        val pending = goAsync()
        try {
            NotifySpeak.speak(c, msg.optString("title"), msg.optString("text"), msg.optString("lang")) { why, lang ->
                val a = JSONObject().put("ok", true)
                if (why != null) a.put("speak", why).put("lang", lang)
                reply(a, pending)
            }
        } catch (e: Exception) { reply(JSONObject().put("ok", true).put("speak", "noengine"), pending) }
    }
}
