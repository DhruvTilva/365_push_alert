package com.dhruvtilva.pushalert.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope

/** A tab's content. [start] attaches flow collectors to the activity-scoped coroutine scope. */
interface Screen {
    val view: View
    fun start(scope: CoroutineScope)
}

class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF111418.toInt() else 0xFFF3F5F8.toInt()
    val surface = if (dark) 0xFF1B2026.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFE8EAED.toInt() else 0xFF1B1F23.toInt()
    val subtext = if (dark) 0xFF9AA4AF.toInt() else 0xFF59636E.toInt()
    val primary = if (dark) 0xFF8AB4F8.toInt() else 0xFF1F5FBF.toInt()
    val onPrimary = if (dark) 0xFF0B1B33.toInt() else 0xFFFFFFFF.toInt()
    val danger = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt()
    val warn = if (dark) 0xFFFFD27A.toInt() else 0xFF8A5A00.toInt()
    val ok = if (dark) 0xFF8FD9A8.toInt() else 0xFF1E7B3A.toInt()
    val divider = if (dark) 0xFF2C333B.toInt() else 0xFFDDE1E6.toInt()
    val chip = if (dark) 0xFF26303A.toInt() else 0xFFE8EDF3.toInt()

    companion object {
        fun of(context: Context): Palette {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return Palette(night == Configuration.UI_MODE_NIGHT_YES)
        }
    }
}

class Ui(val context: Context, val p: Palette) {
    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    fun text(s: CharSequence = "", sizeSp: Float = 15f, color: Int = p.text, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun sub(s: CharSequence = ""): TextView = text(s, 13f, p.subtext)

    fun title(s: CharSequence): TextView = text(s, 18f, p.text, true).apply { setPadding(0, dp(16), 0, dp(6)) }

    fun button(label: String, filled: Boolean = false, onClick: (View) -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextColor(if (filled) p.onPrimary else p.primary)
            background = rounded(if (filled) p.primary else p.chip, 10)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener(onClick)
        }

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.surface, 12, p.divider)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    fun input(hint: String, multiline: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint
        setTextColor(p.text)
        setHintTextColor(p.subtext)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minHeight = dp(48)
        if (multiline) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
        } else {
            setSingleLine()
        }
    }

    fun check(label: String, checked: Boolean, onClick: (Boolean) -> Unit): CheckBox = CheckBox(context).apply {
        text = label
        isChecked = checked
        setTextColor(p.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minHeight = dp(48)
        setOnClickListener { onClick(isChecked) }
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(p.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    fun vbox(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }

    fun formatTime(ms: Long): String = DateFormat.format("MMM d, HH:mm", ms).toString()

    @Suppress("unused") fun transparent() = Color.TRANSPARENT
}
