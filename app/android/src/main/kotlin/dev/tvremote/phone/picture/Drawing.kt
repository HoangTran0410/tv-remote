package dev.tvremote.phone.picture

import dev.tvremote.phone.MainActivity
import dev.tvremote.phone.manage.progress
import dev.tvremote.phone.media.askText
import dev.tvremote.phone.media.say
import dev.tvremote.phone.picker.message
import dev.tvremote.phone.picker.offerSetup
import dev.tvremote.phone.picker.withSetup
import dev.tvremote.phone.ui.ACCENT
import dev.tvremote.phone.ui.BTN
import dev.tvremote.phone.ui.Popup
import dev.tvremote.phone.ui.TEXT
import dev.tvremote.phone.ui.box
import dev.tvremote.phone.ui.button
import dev.tvremote.phone.ui.dp
import dev.tvremote.phone.ui.icon
import dev.tvremote.phone.ui.say
import dev.tvremote.phone.ui.setOn

import dev.tvremote.core.T
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import dev.tvremote.core.Errors
import org.json.JSONArray
import org.json.JSONObject

// ✏️ Drawing on the TV's screen, as on the web page (draw.js): while it is on, the finger on the picture draws instead of
// touching the TV, and TV Setup draws the same strokes over whatever the TV shows, for the person watching (DrawLayer.kt there,
// Draw.kt for the way). The strokes stay on the TV until cleared, or 10 minutes; here they fade once the finger lifts, since
// the TV's own picture shows them by then.

internal val DRAW_COLORS = intArrayOf(0xffff3b30.toInt(), 0xffff9f0a.toInt(), 0xffffd60a.toInt(), 0xff32d74b.toInt(), 0xff40c8ff.toInt(),
    0xff0a5cff.toInt(), 0xffbf5af2.toInt(), 0xffff6ac1.toInt(), Color.WHITE, Color.BLACK)

internal fun MainActivity.startDrawing() {
    if (basic != null || tv == null) {
        Toast.makeText(this, T("drawing.drawing_on_the_tv"), Toast.LENGTH_LONG).show()
        return
    }
    if (!video) toggleVideo()                                         // the strokes go where the picture shows
    drawing = true
    drawBar.visibility = View.VISIBLE
    drawLit()
    drawIo.execute {                                                  // opens the way now: an old or missing TV Setup is told before anything is drawn
        try { drawer.send(JSONArray()) } catch (e: Exception) { ui.post { if (drawing) stopDrawing(); drawFail(e) } }
    }
}

/** Why drawing cannot go on; an old or missing TV Setup comes as offerSetup's question (update or install it on the TV). */
internal fun MainActivity.drawFail(e: Exception) {
    if (isFinishing || offerSetup(e)) return
    val m = e.message ?: ""
    Toast.makeText(this, if ("draw:nooverlay" in m) T("draw.no_overlay")
        else T("drawing.could_not_draw_on", "error" to Errors.say(e, dev.tvremote.core.Words.lang)), Toast.LENGTH_LONG).show()
}

internal fun MainActivity.stopDrawing() {
    drawing = false
    drawBar.visibility = View.GONE
    drawLit()
    picEnd()
    drawView.clear()
    synchronized(drawQ) { drawQ.clear() }
    drawIo.execute { drawer.close() }
}

/** ✏️ among the keys shows whether drawing is on. */
internal fun MainActivity.drawLit() {
    val b = drawBtn ?: return
    b.setOn(drawing, 16)
}

/** The colour (and size), picture, undo, clear and done: a row under the picture, hidden until drawing starts; while a
 *  picture is being placed, cancel and ✓ instead. */
internal fun MainActivity.drawTools(): LinearLayout {
    val tools = LinearLayout(this)
    tools.orientation = LinearLayout.VERTICAL
    tools.visibility = View.GONE
    val bar = LinearLayout(this)
    bar.tag = "pen"
    bar.gravity = Gravity.CENTER_VERTICAL
    bar.setPadding(dp(8), dp(8), dp(8), dp(8))
    // one swatch for the colour in use (its ring as thick as the line): a tap opens the colours and the size
    val pen = View(this)
    val paint = { pen.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(drawColor); setStroke(dp(1) + drawWidth / 30, TEXT) } }
    paint()
    pen.contentDescription = T("remote.colour")
    pen.setOnClickListener { drawPen { paint() } }
    bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))       // all on the right, the swatch with the rest
    bar.addView(pen, LinearLayout.LayoutParams(dp(30), dp(30)).also { it.rightMargin = dp(6) })
    val pic = icon("@image", T("drawing.put_a_picture_on"), BTN)
    pic.setOnClickListener { withSetup("pic") { pickDrawPic() } }    // an older TV Setup draws lines but no pictures
    val say = icon("@type", T("drawing.type_a_message_on"), BTN)
    say.setOnClickListener { withSetup("pic") { askText(T("draw.message_for_tv"), T("drawing.e_g_dinner_s"), T("draw.next")) { placeMessage(it) } } }
    val undo = icon("@undo", T("remote.take_back_the_last"), BTN)
    undo.setOnClickListener { drawSend(JSONObject().put("o", "u")) }
    val clear = icon("@trash", T("drawing.clear_all"), BTN)
    clear.setOnClickListener { drawView.clear(); drawSend(JSONObject().put("o", "x")) }
    val done = icon("@check", T("drawing.done"), ACCENT)
    done.setOnClickListener { stopDrawing() }
    for (b in listOf(pic, say, undo, clear, done)) bar.addView(b, LinearLayout.LayoutParams(dp(40), dp(40)).also { it.leftMargin = dp(4) })
    tools.addView(bar, LinearLayout.LayoutParams(-1, -2))

    val place = LinearLayout(this)
    place.tag = "pic"
    place.gravity = Gravity.CENTER_VERTICAL
    place.setPadding(dp(12), dp(8), dp(8), dp(8))
    place.visibility = View.GONE
    // − and +: a tap is one step, holding keeps going (faster the longer), like a slider that springs back; 1x = the size it came at
    val scale = android.widget.TextView(this).apply { setTextColor(TEXT); textSize = 14f; gravity = Gravity.CENTER }
    drawView.onSize = { scale.text = String.format(java.util.Locale.US, "%.1fx", drawView.placing?.let { it.w.toFloat() / it.base } ?: 1f) }
    for ((sign, k) in listOf("−" to 1 / 1.06f, "+" to 1.06f)) {
        val b = button(sign, BTN)
        b.textSize = 20f
        b.contentDescription = if (k > 1) T("remote.bigger") else T("remote.smaller")
        var n = 0
        val step = object : Runnable { override fun run() {
            drawView.resize(k)
            ui.postDelayed(this, if (n++ == 0) 350L else maxOf(30L, 120L - n * 6))
        } }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { n = 0; ui.removeCallbacks(step); step.run() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(step)
            }
            true
        }
        if (k > 1) place.addView(scale, LinearLayout.LayoutParams(dp(48), dp(40)))
        b.setPadding(0, 0, 0, 0)
        place.addView(b, LinearLayout.LayoutParams(dp(44), dp(40)))
    }
    place.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    val cancel = button(T("common.cancel"), BTN)
    cancel.setOnClickListener { picEnd() }
    val ok = button("@check|" + T("drawing.put_on_tv"), ACCENT)
    ok.setOnClickListener { picSend() }
    for (b in listOf(cancel, ok)) { b.setPadding(dp(12), 0, dp(12), 0); place.addView(b, LinearLayout.LayoutParams(-2, dp(40)).also { it.leftMargin = dp(6) }) }
    tools.addView(place, LinearLayout.LayoutParams(-1, -2))
    return tools
}

/** The colours (5 a row) and the line's size, in a small dialog; [changed] repaints the swatch on the bar. */
private fun MainActivity.drawPen(changed: () -> Unit) {
    val wrap = LinearLayout(this)
    wrap.orientation = LinearLayout.VERTICAL
    wrap.setPadding(dp(20), dp(16), dp(20), dp(4))
    var dlg: Popup? = null
    for (row in DRAW_COLORS.toList().chunked(5)) {
        val line = LinearLayout(this)
        line.gravity = Gravity.CENTER
        for (c in row) {
            val s = View(this)
            s.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(if (c == drawColor) 4 else 1), if (c == drawColor) ACCENT else 0x66ffffff) }
            s.contentDescription = T("remote.colour")
            s.setOnClickListener { drawColor = c; changed(); dlg?.dismiss() }
            line.addView(s, LinearLayout.LayoutParams(dp(40), dp(40)).also { it.setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
        wrap.addView(line, LinearLayout.LayoutParams(-1, -2))
    }
    // the dot beside the slider: the line's size and colour, so the slider says what it does
    val dot = View(this)
    dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(drawColor); setStroke(1, 0x59ffffff) }
    val dotBox = android.widget.FrameLayout(this)
    dotBox.addView(dot)
    val dotSize = { val d = dp(maxOf(4, drawWidth / 5)); dot.layoutParams = android.widget.FrameLayout.LayoutParams(d, d, Gravity.CENTER) }
    dotSize()
    val size = android.widget.SeekBar(this)                         // 20..250, as on the web page
    size.max = 23
    size.progress = (drawWidth - 20) / 10
    size.contentDescription = T("remote.line_size")
    size.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(b: android.widget.SeekBar, p: Int, user: Boolean) { drawWidth = 20 + p * 10; changed(); dotSize() }
        override fun onStartTrackingTouch(b: android.widget.SeekBar) {}
        override fun onStopTrackingTouch(b: android.widget.SeekBar) {}
    })
    val sizeRow = LinearLayout(this)
    sizeRow.gravity = Gravity.CENTER_VERTICAL
    sizeRow.addView(size, LinearLayout.LayoutParams(0, dp(48), 1f))
    sizeRow.addView(dotBox, LinearLayout.LayoutParams(dp(56), dp(56)))
    wrap.addView(sizeRow, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
    dlg = Popup.Builder(this).setView(wrap).show()
}

// 🖼️ A picture from the phone, as on the web page: placed here (one finger moves it, two fingers size it), sent
// to the TV once on ✓, where it stays like a stroke. At most 1280 pixels: plenty over a TV's picture, and a photo stays a few
// hundred KB; PNGs (a QR code, a screenshot) stay PNG, so their edges stay sharp.
@Suppress("DEPRECATION")
internal fun MainActivity.pickDrawPic() {
    val sheet = Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType("image/*")
    if (Build.VERSION.SDK_INT >= 33 || sheet.resolveActivity(packageManager) != null) {
        try { startActivityForResult(sheet, PICK_DRAW); return } catch (ignored: Exception) { }
    }
    val gms = Intent("androidx.activity.result.contract.action.PICK_IMAGES").setType("image/*")
    if (gms.resolveActivity(packageManager) != null) {
        try { startActivityForResult(gms, PICK_DRAW); return } catch (ignored: Exception) { }
    }
    try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_DRAW) }
    catch (e: Exception) { Toast.makeText(this, T("drawing.this_phone_has_no"), Toast.LENGTH_LONG).show() }
}

internal fun MainActivity.drawPicked(uri: Uri) {
    drawIo.execute {
        val p = try {
            val type = contentResolver.getType(uri) ?: ""
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
            var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IllegalArgumentException()
            bmp = dev.tvremote.core.Upright.apply(bmp, contentResolver.openInputStream(uri)?.use { dev.tvremote.core.Upright.read(it) } ?: 1)   // a photo stored lying down
            val k = 1280f / maxOf(bmp.width, bmp.height)
            if (k < 1) bmp = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * k).toInt()), maxOf(1, (bmp.height * k).toInt()), true)
            val out = ByteArrayOutputStream()
            if (Regex("png|webp|gif").containsMatchIn(type)) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)   // may be see-through
            if (out.size() == 0 || out.size() > 3_000_000) { out.reset(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            DrawView.Pic(bmp, android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
        } catch (e: Exception) { null }
        ui.post {
            if (p == null) { Toast.makeText(this, T("draw.cannot_open_picture"), Toast.LENGTH_LONG).show(); return@post }
            if (!drawing) startDrawing()
            drawView.placing = p
            drawView.onSize()
            drawView.invalidate()
            drawBar.findViewWithTag<View>("pen").visibility = View.GONE
            drawBar.findViewWithTag<View>("pic").visibility = View.VISIBLE
        }
    }
}

/** 💬 A message as big words, drawn into a picture and placed like one, the same look as the web page's (draw.js): the pen's
 *  colour on a dark box (a light one for black), lines kept under 1100 pixels, 72px words as on a 1080p TV. */
internal fun MainActivity.placeMessage(text: String) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 72f; typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    val lines = ArrayList<String>()
    for (para in text.split('\n')) {
        var line = ""
        for (word in para.split(Regex("\\s+"))) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && paint.measureText(next) > 1100) { lines.add(line); line = word } else line = next
        }
        lines.add(line)
    }
    val pad = 40; val lh = 92
    val w = lines.maxOf { paint.measureText(it) }.toInt() + 1 + pad * 2
    val h = lines.size * lh + pad * 2 - 20
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val dark = drawColor == Color.BLACK
    c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 32f, 32f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xe6ffffff.toInt() else 0xd9000000.toInt() })
    paint.color = drawColor
    lines.forEachIndexed { i, l -> c.drawText(l, w / 2f, pad + i * lh - paint.ascent(), paint) }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    if (!drawing) startDrawing()
    drawView.placing = DrawView.Pic(bmp, android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)).also { it.w = minOf(9000, w * 10000 / 1920); it.base = it.w }
    drawView.onSize()
    drawView.invalidate()
    drawBar.findViewWithTag<View>("pen").visibility = View.GONE
    drawBar.findViewWithTag<View>("pic").visibility = View.VISIBLE
}

internal fun MainActivity.picEnd() {
    drawView.placing = null
    drawView.invalidate()
    drawBar.findViewWithTag<View>("pic")?.visibility = View.GONE
    drawBar.findViewWithTag<View>("pen")?.visibility = View.VISIBLE
}

internal fun MainActivity.picSend() {
    val p = drawView.placing ?: return
    drawSend(JSONObject().put("o", "i").put("id", ++drawId).put("b", p.data).put("x", p.x).put("y", p.y).put("w", p.w))
    drawView.landed(p)
    picEnd()
}

/** The finger on the picture, in 0..10000 of the TV's screen (the picture is the TV's screen, letterboxed by its frame). */
internal fun MainActivity.drawTouch(e: MotionEvent, v: View) {
    if (drawView.placing != null) { drawView.place(e, v); return }
    val x = (e.x / v.width * 10000).toInt().coerceIn(0, 10000)
    val y = (e.y / v.height * 10000).toInt().coerceIn(0, 10000)
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val id = ++drawId
            drawView.start(drawColor, drawWidth, x, y)
            drawSend(JSONObject().put("o", "s").put("id", id).put("c", String.format("#%06x", drawColor and 0xffffff)).put("w", drawWidth).put("p", JSONArray().put(x).put(y)))
        }
        MotionEvent.ACTION_MOVE -> if (drawView.more(x, y)) drawSend(JSONObject().put("o", "a").put("id", drawId).put("p", JSONArray().put(x).put(y)))
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drawView.lift()
    }
}

/** One request at a time; what comes meanwhile goes in the next, so a slow link never falls behind the finger. */
internal fun MainActivity.drawSend(op: JSONObject) {
    synchronized(drawQ) {
        val last = drawQ.lastOrNull()
        if (op.optString("o") == "a" && last != null && last.optString("o") in setOf("s", "a") && last.optInt("id") == op.optInt("id")) {
            val p = op.getJSONArray("p")
            for (i in 0 until p.length()) last.getJSONArray("p").put(p.get(i))
        } else drawQ.add(op)
        if (drawBusy) return
        drawBusy = true
    }
    drawIo.execute {
        while (true) {
            val ops = synchronized(drawQ) {
                if (drawQ.isEmpty()) { drawBusy = false; return@execute }
                JSONArray(drawQ).also { drawQ.clear() }
            }
            try { drawer.send(ops) } catch (e: Exception) {
                synchronized(drawQ) { drawQ.clear(); drawBusy = false }
                ui.post { if (drawing) stopDrawing(); drawFail(e) }
                return@execute
            }
        }
    }
}

/** What the finger draws, here on the phone: each stroke fades soon after it is lifted. */
internal class DrawView(c: Context) : View(c) {
    private class Stroke(val color: Int, val width: Int) { val pts = ArrayList<Int>(); var upAt = 0L }
    /** A picture: centre and width in 0..10000 of the screen, and what goes to the TV. */
    class Pic(val bmp: Bitmap, val data: String) { var x = 5000; var y = 5000; var w = 4000; var base = 4000; var upAt = 0L }   // base: 1x
    private val strokes = ArrayList<Stroke>()
    private val landed = ArrayList<Pic>()                             // sent: shown here until the TV's own picture has it
    var placing: Pic? = null
    var onSize: () -> Unit = {}                                       // the 1.0x beside − and +
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    private val frame = Paint().apply { style = Paint.Style.STROKE; color = Color.WHITE; pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f) }
    private val box = RectF()
    private var lastX = 0f; private var lastY = 0f; private var lastD = 0f
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    init { isClickable = false; isFocusable = false }                 // the touches go on to the picture

    fun start(color: Int, width: Int, x: Int, y: Int) { strokes.add(Stroke(color, width).also { it.pts += x; it.pts += y }); invalidate() }

    /** A point more, if it is far enough from the last one to be worth sending. */
    fun more(x: Int, y: Int): Boolean {
        val s = strokes.lastOrNull()?.takeIf { it.upAt == 0L } ?: return false
        if (Math.abs(x - s.pts[s.pts.size - 2]) + Math.abs(y - s.pts[s.pts.size - 1]) < 25) return false
        s.pts += x; s.pts += y
        invalidate()
        return true
    }

    fun lift() { strokes.lastOrNull()?.let { if (it.upAt == 0L) it.upAt = System.currentTimeMillis() }; invalidate() }

    fun clear() { strokes.clear(); landed.clear(); invalidate() }

    fun landed(p: Pic) { p.upAt = System.currentTimeMillis(); landed.add(p); invalidate() }

    /** One finger moves the picture being placed, two fingers size it. */
    fun place(e: MotionEvent, v: View) {
        val p = placing ?: return
        val two = e.pointerCount >= 2
        val d = if (two) Math.hypot((e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble()).toFloat() else 0f
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (two && lastD > 0) { p.w = (p.w * d / lastD).toInt().coerceIn(400, 10000); onSize() }
                else if (!two) {
                    p.x = (p.x + (e.x - lastX) / v.width * 10000).toInt().coerceIn(0, 10000)
                    p.y = (p.y + (e.y - lastY) / v.height * 10000).toInt().coerceIn(0, 10000)
                }
                invalidate()
            }
        }
        lastX = e.x; lastY = e.y; lastD = d                           // after a finger lifts, the next move starts from here
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) { val i = if (e.actionIndex == 0) 1 else 0; lastX = e.getX(i); lastY = e.getY(i); lastD = 0f }
    }

    /** − and + beside the picture: 0.1x (400) up to the screen's width. */
    fun resize(k: Float) { val p = placing ?: return; p.w = (p.w * k).toInt().coerceIn(400, 10000); onSize(); invalidate() }

    private fun drawPic(c: Canvas, p: Pic, alpha: Int): RectF {
        val pw = p.w * width / 10000f
        val ph = pw * p.bmp.height / p.bmp.width
        box.set(p.x * width / 10000f - pw / 2, p.y * height / 10000f - ph / 2, p.x * width / 10000f + pw / 2, p.y * height / 10000f + ph / 2)
        smooth.alpha = alpha
        c.drawBitmap(p.bmp, null, box, smooth)
        return box
    }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        strokes.removeAll { it.upAt > 0 && now - it.upAt > 1600 }
        landed.removeAll { now - it.upAt > 1600 }
        for (p in landed) drawPic(c, p, (255 * (1 - (now - p.upAt) / 1600f)).toInt().coerceIn(0, 255))
        val w = width / 10000f
        val h = height / 10000f
        for (s in strokes) {
            path.reset()
            path.moveTo(s.pts[0] * w, s.pts[1] * h)
            if (s.pts.size == 2) path.lineTo(s.pts[0] * w + 0.1f, s.pts[1] * h)
            var i = 2
            while (i + 1 < s.pts.size) { path.lineTo(s.pts[i] * w, s.pts[i + 1] * h); i += 2 }
            pen.color = s.color
            pen.alpha = if (s.upAt > 0) (255 * (1 - (now - s.upAt) / 1600f)).toInt().coerceIn(0, 255) else 255
            pen.strokeWidth = maxOf(2f, s.width * w)
            c.drawPath(path, pen)
        }
        placing?.let { c.drawRect(drawPic(c, it, 255), frame) }
        if (strokes.any { it.upAt > 0 } || landed.isNotEmpty()) postInvalidateOnAnimation()
    }
}
