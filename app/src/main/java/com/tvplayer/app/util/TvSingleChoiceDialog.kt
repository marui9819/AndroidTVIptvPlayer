package com.tvplayer.app.util

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** A TV-friendly single-choice dialog with distinct checked and D-pad focus states. */
object TvSingleChoiceDialog {

    fun show(
        context: android.content.Context,
        title: CharSequence,
        entries: Array<out CharSequence>,
        selectedIndex: Int,
        onDismiss: (() -> Unit)? = null,
        onSelected: (Int) -> Boolean
    ) {
        if (entries.isEmpty()) return
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val currentIndex = selectedIndex.takeIf { it in entries.indices } ?: 0
        var dialogRef: AlertDialog? = null
        val rows = ArrayList<TextView>(entries.size)
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }

        fun styleRow(row: TextView, selected: Boolean, focused: Boolean) {
            val fill = when {
                focused -> Color.rgb(14, 111, 151)
                selected -> Color.rgb(20, 58, 83)
                else -> Color.rgb(31, 42, 52)
            }
            val outline = when {
                focused -> Color.rgb(255, 211, 92)
                selected -> Color.rgb(72, 206, 246)
                else -> Color.rgb(74, 91, 105)
            }
            row.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(fill)
                setStroke(dp(if (focused) 3 else if (selected) 2 else 1), outline)
            }
            row.setTextColor(
                if (focused) Color.WHITE
                else if (selected) Color.rgb(147, 235, 255)
                else Color.rgb(238, 244, 249)
            )
            row.scaleX = if (focused) 1.025f else 1f
            row.scaleY = if (focused) 1.025f else 1f
        }

        entries.forEachIndexed { index, entry ->
            val selected = index == currentIndex
            val row = TextView(context).apply {
                text = (if (selected) "✓   " else "○   ") + entry
                textSize = if (DeviceProfile.isTv(context)) 19f else 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                minHeight = dp(58)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                isSelected = selected
                contentDescription = entry.toString() + if (selected) "，当前已选" else ""
                setOnFocusChangeListener { _, focused -> styleRow(this, isSelected, focused) }
                setOnClickListener {
                    if (onSelected(index)) dialogRef?.dismiss()
                }
            }
            styleRow(row, selected, false)
            rows += row
            options.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply {
                bottomMargin = dp(8)
            })
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(options, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll, dp(8), dp(4), dp(8), 0)
            .setNegativeButton("取消", null)
            .create()
        dialogRef = dialog
        TvDialogStyler.apply(dialog, styleFocusableContent = false, focusInitialButton = false)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        dialog.show()
        rows[currentIndex].post { rows[currentIndex].requestFocus() }
    }
}
