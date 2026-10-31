package dev.tvremote.phone.ui

import dev.tvremote.phone.R
import dev.tvremote.phone.ir.title
import dev.tvremote.phone.picker.message
import dev.tvremote.phone.remote.keys

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The app's own dialog, in its colours: a CARD box with the app's rounded buttons (the main one blue), instead of the system's
 * AlertDialog, whose frame and capital teal words change with every Android and every maker. Built the same way
 * (`Popup.Builder(this).setTitle(..).setMessage(..).setPositiveButton(..) { _, _ -> }.show()`), so the screens read as before.
 * A button closes the box after its listener; one given a click listener of its own later (getButton) keeps it open.
 */
internal class Popup private constructor(c: Context) : Dialog(c) {
    private val buttons = HashMap<Int, Button>()
    private lateinit var title: TextView
    private lateinit var message: TextView

    /** [which]: [BUTTON_POSITIVE], [BUTTON_NEGATIVE] or [BUTTON_NEUTRAL], one the box was given. */
    fun getButton(which: Int): Button = buttons[which] ?: throw IllegalArgumentException("no button $which")

    fun setMessage(s: CharSequence?) { message.text = s; message.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }

    override fun setTitle(s: CharSequence?) {
        title.text = s; title.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
        window?.setTitle(s)                                            // what TalkBack says as the box opens
    }

    override fun onStart() { super.onStart(); open.add(this) }
    override fun onStop() { open.remove(this); super.onStop() }

    class Builder(private val c: Context) {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var view: View? = null
        private val buttons = LinkedHashMap<Int, Pair<CharSequence, DialogInterface.OnClickListener?>>()
        private var cancelable = true
        private var onCancel: DialogInterface.OnCancelListener? = null
        private var onDismiss: DialogInterface.OnDismissListener? = null
        private var choices: Triple<Array<out CharSequence>, BooleanArray, DialogInterface.OnMultiChoiceClickListener?>? = null

        fun setTitle(s: CharSequence?) = apply { title = s }
        fun setMessage(s: CharSequence?) = apply { message = s }
        fun setView(v: View?) = apply { view = v }
        fun setPositiveButton(s: CharSequence, l: DialogInterface.OnClickListener?) = apply { buttons[BUTTON_POSITIVE] = s to l }
        fun setNegativeButton(s: CharSequence, l: DialogInterface.OnClickListener?) = apply { buttons[BUTTON_NEGATIVE] = s to l }
        fun setNeutralButton(s: CharSequence, l: DialogInterface.OnClickListener?) = apply { buttons[BUTTON_NEUTRAL] = s to l }
        fun setCancelable(on: Boolean) = apply { cancelable = on }
        fun setOnCancelListener(l: DialogInterface.OnCancelListener?) = apply { onCancel = l }
        fun setOnDismissListener(l: DialogInterface.OnDismissListener?) = apply { onDismiss = l }
        fun setMultiChoiceItems(items: Array<out CharSequence>, on: BooleanArray, l: DialogInterface.OnMultiChoiceClickListener?) =
            apply { choices = Triple(items, on, l) }

        fun show(): Popup = create().also { it.show() }

        fun create(): Popup {
            val d = Popup(c)
            d.requestWindowFeature(Window.FEATURE_NO_TITLE)
            d.setCancelable(cancelable)
            onCancel?.let { d.setOnCancelListener(it) }
            onDismiss?.let { d.setOnDismissListener(it) }

            val root = c.col()
            root.background = c.box(CARD, 22)
            root.setPadding(0, c.dp(22), 0, c.dp(16))
            d.title = c.label("", 19, TEXT).apply {
                typeface = Typeface.DEFAULT_BOLD
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                setPadding(c.dp(24), 0, c.dp(24), c.dp(4))
            }
            d.setTitle(title)
            // Close and Back sit in the head, as ✕ / ‹ Back on the page's dialogs: at the top, the same place in every box
            val closeWords = dev.tvremote.core.T("common.close"); val backWords = dev.tvremote.core.T("common.back")
            val inHead = buttons.filter { (_, b) -> b.first == closeWords || b.first == backWords }
            val head = LinearLayout(c)
            head.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            head.setPadding(0, 0, c.dp(14), 0)
            head.addView(d.title, LinearLayout.LayoutParams(0, -2, 1f))
            for ((which, b) in inHead.entries.sortedBy { it.value.first != backWords }) {   // Back first, ✕ at the edge
                val (s, l) = b
                val btn: Button = if (s == closeWords) c.icon("@close", s.toString(), BTN) else PopupButton(c, s.toString(), BTN).apply { minHeight = c.dp(40); minimumHeight = c.dp(40) }
                btn.setOnClickListener { l?.onClick(d, which); d.dismiss() }
                d.buttons[which] = btn
                head.addView(btn, LinearLayout.LayoutParams(if (s == closeWords) c.dp(40) else -2, c.dp(40)).apply { leftMargin = c.dp(8) })
            }
            inHead.keys.forEach { buttons.remove(it) }
            if (inHead.isNotEmpty()) root.setPadding(0, c.dp(16), 0, c.dp(16))
            root.addView(head)

            // the words, the screen's own view and the ticks scroll together; the buttons stay in sight below them
            val body = c.col()
            d.message = c.label("", 15, TEXT).apply {
                id = android.R.id.message                                // screens find it to keep its height (AdbHelp)
                setLineSpacing(c.dp(3).toFloat(), 1f)
                setPadding(c.dp(24), c.dp(8), c.dp(24), c.dp(4))
            }
            d.setMessage(message)
            body.addView(d.message)
            choices?.let { (items, on, l) ->
                val list = c.col()
                list.setPadding(c.dp(14), c.dp(4), c.dp(24), 0)
                items.forEachIndexed { i, s ->
                    val box = CheckBox(c)
                    box.text = s
                    box.textSize = 16f
                    box.setTextColor(TEXT)
                    box.buttonTintList = ColorStateList.valueOf(ACCENT)
                    box.isChecked = on[i]
                    box.minHeight = c.dp(48)
                    box.setOnCheckedChangeListener { _, checked -> on[i] = checked; l?.onClick(d, i, checked) }
                    list.addView(box)
                }
                body.addView(list)
            }
            val v = view
            val scroller: View = if (v is ScrollView) {                  // it scrolls itself: two scrollers inside each other fight
                if (message != null || choices != null) root.addView(body)
                v
            } else {
                v?.let { body.addView(it) }
                ScrollView(c).apply { addView(body); isVerticalFadingEdgeEnabled = true }
            }
            (scroller.parent as? ViewGroup)?.removeView(scroller)
            root.addView(scroller, LinearLayout.LayoutParams(-1, -2, 1f))   // shrinks, when the box is taller than the screen, before the buttons do

            if (buttons.isNotEmpty()) {
                val row = ButtonRow(c)
                row.setPadding(c.dp(20), c.dp(14), c.dp(20), 0)
                for (which in listOf(BUTTON_NEUTRAL, BUTTON_NEGATIVE, BUTTON_POSITIVE)) {   // the main one last: right, or at the bottom
                    val (s, l) = buttons[which] ?: continue
                    val b = PopupButton(c, s.toString(), if (which == BUTTON_POSITIVE) ACCENT else BTN)
                    b.setOnClickListener { l?.onClick(d, which); d.dismiss() }
                    d.buttons[which] = b
                    row.addView(b)
                }
                root.addView(row)
            }

            // the window spans the screen and the box is capped inside it: right again after a rotation (the activity is not recreated)
            val frame = Capped(c, c.dp(520))
            frame.setPadding(c.dp(16), 0, c.dp(16), 0)
            frame.addView(root, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            root.isClickable = true                                    // a tap on the box is not a tap beside it
            frame.setOnClickListener { if (cancelable) d.cancel() }
            d.setContentView(frame)
            d.window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return d
        }
    }

    /** One of the app's buttons, greyed while it is off (AlertDialog greys its words; this has a fill to grey). */
    private class PopupButton(c: Context, s: String, color: Int) : Button(c) {
        init {
            setAllCaps(false)
            textSize = 15f
            setTextColor(if (onFill(color)) Color.WHITE else TEXT)
            setLabel(s)
            background = c.box(color, 14)
            stateListAnimator = null
            setPadding(c.dp(16), 0, c.dp(16), 0)
            minWidth = c.dp(64); minimumWidth = c.dp(64)
            minHeight = c.dp(44); minimumHeight = c.dp(44)
            maxLines = 2                                               // a long label wraps rather than being cut
        }

        override fun setEnabled(on: Boolean) { super.setEnabled(on); alpha = if (on) 1f else 0.4f }
    }

    /** The buttons side by side at the right when they fit on one line; one under the other, full width, when they do not, so a
     *  long label is never cut (the main one at the bottom, nearest the thumb). */
    private class ButtonRow(c: Context) : LinearLayout(c) {
        private val gap = c.dp(8)

        override fun onMeasure(w: Int, h: Int) {
            val room = MeasureSpec.getSize(w) - paddingLeft - paddingRight
            var need = 0
            var shown = 0
            for (i in 0 until childCount) {
                val b = getChildAt(i)
                if (b.visibility == GONE) continue
                b.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                need += b.measuredWidth + if (shown++ > 0) gap else 0
            }
            val across = need <= room
            orientation = if (across) HORIZONTAL else VERTICAL
            gravity = if (across) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            var first = true
            for (i in 0 until childCount) {
                val b = getChildAt(i)
                val lp = b.layoutParams as LayoutParams
                lp.width = if (across) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
                lp.leftMargin = if (across && !first) gap else 0
                lp.topMargin = if (!across && !first) gap else 0
                if (b.visibility != GONE) first = false
            }
            super.onMeasure(w, h)
        }
    }

    /** Holds the box at [max] wide at most, centred, however wide the window is. */
    private class Capped(c: Context, private val max: Int) : android.widget.FrameLayout(c) {
        override fun onMeasure(w: Int, h: Int) {
            val room = MeasureSpec.getSize(w) - paddingLeft - paddingRight
            val child = getChildAt(0)
            (child?.layoutParams as? LayoutParams)?.width = minOf(room, max)
            super.onMeasure(w, h)
        }
    }

    companion object {
        private val open = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Popup, Boolean>())

        /** The boxes of an activity going away. */
        fun closeAll(owner: Context) { for (d in open.toList()) if (d.context.let { it === owner || (it as? android.content.ContextWrapper)?.baseContext === owner }) try { d.dismiss() } catch (e: Exception) { } }

        const val BUTTON_POSITIVE = DialogInterface.BUTTON_POSITIVE
        const val BUTTON_NEGATIVE = DialogInterface.BUTTON_NEGATIVE
        const val BUTTON_NEUTRAL = DialogInterface.BUTTON_NEUTRAL
    }
}
