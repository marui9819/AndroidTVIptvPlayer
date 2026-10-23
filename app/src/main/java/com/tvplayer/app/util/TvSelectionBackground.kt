package com.tvplayer.app.util

import android.graphics.drawable.GradientDrawable
import android.view.View

/** Small, allocation-light background helper for model-driven TV selectors. */
object TvSelectionBackground {

    fun apply(
        view: View,
        fillColor: Int,
        strokeColor: Int,
        strokeWidthDp: Int = 1
    ) {
        val density = view.resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(fillColor)
            setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }
}
