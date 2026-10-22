package com.medreminder.alarmtest

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// Small helpers for building screens in code: the test app has no layout resources.

internal fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

internal fun Context.column(paddingDp: Int = 16): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
}

internal fun LinearLayout.text(
    value: CharSequence,
    sizeSp: Float = 16f,
    bold: Boolean = false,
    color: Int = Color.rgb(0x1F, 0x1F, 0x1F),
    center: Boolean = false,
    topMarginDp: Int = 0,
): TextView {
    val view = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (center) gravity = Gravity.CENTER_HORIZONTAL
    }
    addView(view, matchWidth(topMarginDp))
    return view
}

internal fun LinearLayout.button(
    label: CharSequence,
    background: Int? = null,
    textColor: Int? = null,
    sizeSp: Float = 16f,
    minHeightDp: Int = 48,
    topMarginDp: Int = 8,
    onClick: () -> Unit,
): Button {
    val view = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        minHeight = context.dp(minHeightDp)
        minimumHeight = context.dp(minHeightDp)
        background?.let { setBackground(rounded(it)) }
        textColor?.let { setTextColor(it) }
        setOnClickListener { onClick() }
    }
    addView(view, matchWidth(topMarginDp))
    return view
}

internal fun LinearLayout.heading(value: String) = text(value, sizeSp = 18f, bold = true, topMarginDp = 20)

internal fun LinearLayout.divider() {
    addView(
        View(context).apply { setBackgroundColor(Color.rgb(0xDD, 0xDD, 0xDD)) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1)).apply { topMargin = context.dp(8) },
    )
}

internal fun View.rounded(color: Int, radiusDp: Int = 16): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = context.dp(radiusDp).toFloat()
}

private fun LinearLayout.matchWidth(topMarginDp: Int) =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(topMarginDp)
    }

/** Apps targeting Android 15+ draw behind the status and navigation bars; pad the content clear of them. */
internal fun View.padForSystemBars() {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(
                base[0] + insets.systemWindowInsetLeft,
                base[1] + insets.systemWindowInsetTop,
                base[2] + insets.systemWindowInsetRight,
                base[3] + insets.systemWindowInsetBottom,
            )
        }
        insets
    }
}

internal object Palette {
    val GREEN = Color.rgb(0x1B, 0x7F, 0x3B)
    val GREY = Color.rgb(0xE6, 0xE6, 0xE6)
    val RED = Color.rgb(0xB3, 0x26, 0x1E)
    val AMBER = Color.rgb(0x9A, 0x6B, 0x00)
    val INK = Color.rgb(0x1F, 0x1F, 0x1F)
    val PAPER = Color.rgb(0xFB, 0xFB, 0xF8)
}
