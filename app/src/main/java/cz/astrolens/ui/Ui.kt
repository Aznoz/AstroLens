package cz.astrolens.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Small toolkit that builds the whole interface from code (no XML layouts). */
class Ui(val ctx: Context) {
    companion object {
        val PANEL = Color.argb(238, 12, 16, 28)
        val CARD = Color.argb(255, 26, 33, 52)
        val CARD_ON = Color.argb(255, 38, 98, 170)
        val ACCENT = Color.rgb(90, 170, 255)
        val TEXT = Color.rgb(236, 240, 248)
        val DIM = Color.rgb(150, 160, 180)
        val WARN = Color.rgb(255, 170, 80)
        val RED = Color.rgb(230, 70, 70)
    }

    val d: Float = ctx.resources.displayMetrics.density

    fun dp(v: Float): Int = (v * d + 0.5f).toInt()
    fun dp(v: Int): Int = dp(v.toFloat())

    fun rounded(color: Int, radiusDp: Float = 12f, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(radiusDp).toFloat()
        g.setColor(color)
        if (strokeDp > 0f) g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    fun text(s: String, sizeSp: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return t
    }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.weight = weight
        return p
    }

    fun vbox(): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun hbox(): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun pad(v: View, l: Int, t: Int, r: Int, b: Int) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b))
    }

    fun margin(v: View, l: Int, t: Int, r: Int, b: Int) {
        val p = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        v.layoutParams = p
    }

    fun section(title: String): TextView {
        val t = text(title.uppercase(), 11.5f, ACCENT, true)
        t.letterSpacing = 0.08f
        t.layoutParams = lp(-1, -2)
        pad(t, 2, 14, 2, 4)
        return t
    }

    fun hint(s: String): TextView {
        val t = text(s, 12f, DIM)
        t.layoutParams = lp(-1, -2)
        pad(t, 2, 2, 2, 4)
        return t
    }

    fun button(label: String, onClick: () -> Unit): TextView {
        val t = text(label, 14f, TEXT, true)
        t.gravity = Gravity.CENTER
        t.background = rounded(CARD, 10f)
        pad(t, 14, 10, 14, 10)
        t.setOnClickListener { onClick() }
        return t
    }

    fun accentButton(label: String, onClick: () -> Unit): TextView {
        val t = button(label, onClick)
        t.background = rounded(CARD_ON, 10f)
        return t
    }

    /** Row of exclusive choices; returns the container and a function to change the selection. */
    class Choice(val view: View, val select: (Int) -> Unit)

    fun choice(labels: List<String>, selected: Int, onPick: (Int) -> Unit): Choice {
        val row = hbox()
        val views = ArrayList<TextView>()
        val sel = intArrayOf(selected)
        fun paint() {
            for ((i, v) in views.withIndex()) {
                v.background = rounded(if (i == sel[0]) CARD_ON else CARD, 9f)
                v.setTextColor(if (i == sel[0]) Color.WHITE else DIM)
            }
        }
        for ((i, s) in labels.withIndex()) {
            val t = text(s, 13f, DIM, true)
            t.gravity = Gravity.CENTER
            pad(t, 10, 9, 10, 9)
            val p = lp(0, -2, 1f)
            p.setMargins(dp(2), 0, dp(2), 0)
            t.layoutParams = p
            t.setOnClickListener {
                sel[0] = i
                paint()
                onPick(i)
            }
            views.add(t)
            row.addView(t)
        }
        paint()
        val scroller: View = if (labels.size > 5) {
            val h = HorizontalScrollView(ctx)
            h.isHorizontalScrollBarEnabled = false
            for (t in views) t.layoutParams = lp(-2, -2).also { it.setMargins(dp(2), 0, dp(2), 0) }
            h.addView(row)
            h
        } else row
        return Choice(scroller) { i -> sel[0] = i; paint() }
    }

    class Toggle(val view: View, val set: (Boolean) -> Unit)

    fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Toggle {
        val row = hbox()
        row.layoutParams = lp(-1, -2)
        pad(row, 2, 7, 2, 7)
        val t = text(label, 14.5f)
        t.layoutParams = lp(0, -2, 1f)
        val sw = android.widget.Switch(ctx)
        sw.isChecked = checked
        var silent = false
        sw.setOnCheckedChangeListener { _, v -> if (!silent) onChange(v) }
        row.addView(t)
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return Toggle(row) { v -> silent = true; sw.isChecked = v; silent = false }
    }

    class Slider(val view: View, val seek: SeekBar, val setValueText: (String) -> Unit, val setProgress: (Int) -> Unit)

    /** Labelled slider with a value readout; [onChange] fires while dragging (fromUser only). */
    fun slider(label: String, max: Int, progress: Int, valueText: String, onChange: (Int, Boolean) -> Unit): Slider {
        val box = vbox()
        box.layoutParams = lp(-1, -2)
        pad(box, 2, 4, 2, 2)
        val head = hbox()
        val l = text(label, 14f)
        l.layoutParams = lp(0, -2, 1f)
        val v = text(valueText, 14f, ACCENT, true)
        head.addView(l)
        head.addView(v)
        val sb = SeekBar(ctx)
        sb.max = max
        sb.progress = progress
        sb.layoutParams = lp(-1, dp(38))
        var silent = false
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!silent) onChange(p, fromUser)
            }

            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        box.addView(head)
        box.addView(sb)
        return Slider(box, sb, { s -> v.text = s }, { p -> silent = true; sb.progress = p; silent = false })
    }

    fun edit(hintText: String, value: String, numeric: Boolean): EditText {
        val e = EditText(ctx)
        e.hint = hintText
        e.setText(value)
        e.setTextColor(TEXT)
        e.setHintTextColor(DIM)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        e.background = rounded(CARD, 10f)
        pad(e, 12, 10, 12, 10)
        e.setSingleLine(true)
        if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        return e
    }

    fun spacer(hDp: Int): View {
        val v = View(ctx)
        v.layoutParams = lp(-1, dp(hDp))
        return v
    }
}
