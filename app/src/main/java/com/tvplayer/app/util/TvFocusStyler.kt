package com.tvplayer.app.util

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.os.Build
import androidx.core.content.ContextCompat
import com.tvplayer.app.R

/** Applies one high-contrast D-pad focus language to classic View screens. */
object TvFocusStyler {

    fun apply(root: View) {
        style(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                apply(root.getChildAt(index))
            }
        }
    }

    private fun style(view: View) {
        when (view) {
            is EditText -> {
                // Preserve the dedicated input background (Search uses a
                // rounded cyan focus ring; Material inputs own their outline)
                // instead of replacing it with a generic list-item surface.
                attachFocusScale(view, 1.02f)
            }
            is CompoundButton -> {
                view.setTextColor(ContextCompat.getColor(view.context, android.R.color.white))
                attachFocusAnimation(view, R.drawable.tv_toggle_background, R.drawable.tv_toggle_background)
            }
            is ImageButton -> {
                clearFrameworkBackgroundTint(view)
                view.background = ContextCompat.getDrawable(view.context, R.drawable.tv_icon_button_normal)
                attachFocusAnimation(view, R.drawable.tv_icon_button_normal, R.drawable.tv_icon_button_focused)
            }
            is Button -> {
                clearFrameworkBackgroundTint(view)
                view.background = ContextCompat.getDrawable(view.context, R.drawable.tv_action_button_normal)
                view.setTextColor(ContextCompat.getColorStateList(view.context, R.color.tv_button_text))
                attachFocusAnimation(view, R.drawable.tv_action_button_normal, R.drawable.tv_action_button_focused)
            }
            else -> if (view.isClickable && view.isFocusable) {
                view.background = ContextCompat.getDrawable(view.context, R.drawable.tv_list_item_background)
                attachFocusAnimation(view, R.drawable.tv_list_item_background, R.drawable.tv_list_item_background)
            }
        }
    }

    private fun clearFrameworkBackgroundTint(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.backgroundTintList = null
        }
    }

    private fun attachFocusAnimation(view: View, normalBackground: Int, focusedBackground: Int) {
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            // Some vendor Android 6 builds do not redraw state_focused reliably
            // on Button backgrounds. Mirror it into state_selected and add a
            // small scale change so the active remote target remains obvious.
            focusedView.isSelected = hasFocus
            focusedView.setBackgroundResource(if (hasFocus) focusedBackground else normalBackground)
            focusedView.animate()
                .scaleX(if (hasFocus) 1.045f else 1f)
                .scaleY(if (hasFocus) 1.045f else 1f)
                .setDuration(110L)
                .start()
        }
    }

    private fun attachFocusScale(view: View, focusedScale: Float) {
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            focusedView.isSelected = hasFocus
            focusedView.animate()
                .scaleX(if (hasFocus) focusedScale else 1f)
                .scaleY(if (hasFocus) focusedScale else 1f)
                .setDuration(110L)
                .start()
        }
    }
}
