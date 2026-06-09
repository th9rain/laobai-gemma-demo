package com.th9rain.laobai.gemmademo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

fun Context.demoRoot(): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(246, 248, 245))
        setPadding(dp(16), dp(18), dp(16), dp(16))
    }
}

fun Context.demoTitle(text: String, id: Int? = null): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(31, 58, 49))
        id?.let { this.id = it }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).withBottomMargin(dp(10))
    }
}

fun Context.demoDescription(text: String): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = 15f
        setLineSpacing(0f, 1.15f)
        setTextColor(Color.rgb(74, 84, 78))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).withBottomMargin(dp(14))
    }
}

fun Context.statusBox(text: String, id: Int? = null): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(43, 69, 58))
        setBackgroundColor(Color.rgb(230, 242, 235))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        id?.let { this.id = it }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).withBottomMargin(dp(12))
    }
}

fun Context.demoInput(label: String, id: Int): EditText {
    return EditText(this).apply {
        this.id = id
        hint = label
        contentDescription = label
        textSize = 17f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        setSelectAllOnFocus(false)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).withBottomMargin(dp(8))
    }
}

fun Context.primaryButton(text: String, id: Int? = null): Button {
    return Button(this).apply {
        this.text = text
        textSize = 17f
        isAllCaps = false
        id?.let { this.id = it }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).withBottomMargin(dp(8))
    }
}

fun Context.dangerButton(text: String, id: Int): Button {
    return primaryButton(text, id).apply {
        setTextColor(Color.rgb(135, 38, 22))
        isEnabled = false
        alpha = 0.82f
    }
}

fun Context.sectionLabel(text: String): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START
        setTextColor(Color.rgb(37, 49, 44))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).withBottomMargin(dp(6))
    }
}

fun LinearLayout.LayoutParams.withBottomMargin(value: Int): LinearLayout.LayoutParams {
    bottomMargin = value
    return this
}

fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
