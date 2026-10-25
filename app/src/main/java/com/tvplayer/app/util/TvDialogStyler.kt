package com.tvplayer.app.util

import android.content.DialogInterface
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.tvplayer.app.R

/** Makes AlertDialog actions readable and focusable with a TV remote. */
object TvDialogStyler {

    fun apply(
        dialog: AlertDialog,
        initialButton: Int = DialogInterface.BUTTON_NEGATIVE,
        styleFocusableContent: Boolean = true,
        focusInitialButton: Boolean = true
    ) {
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.color.background_secondary)
            val alertTitleId = dialog.context.resources.getIdentifier(
                "alertTitle",
                "id",
                "android"
            )
            dialog.findViewById<TextView>(alertTitleId)?.setTextColor(
                ContextCompat.getColor(dialog.context, R.color.text_primary)
            )
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(
                ContextCompat.getColor(dialog.context, R.color.text_primary)
            )
            val root = dialog.window?.decorView ?: return@setOnShowListener
            if (styleFocusableContent) TvFocusStyler.apply(root)
            listOf(
                dialog.getButton(DialogInterface.BUTTON_POSITIVE),
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE),
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
            ).forEach { button ->
                button?.apply {
                    minHeight = 52
                    textSize = 16f
                    // Choice dialogs opt out of recursive styling so their
                    // checked rows remain untouched; style action buttons alone.
                    if (!styleFocusableContent) TvFocusStyler.apply(this)
                }
            }
            if (focusInitialButton) {
                dialog.getButton(initialButton)?.let { button ->
                    if (button.isShown && button.isEnabled) {
                        button.requestFocus()
                    }
                }
            }
        }
    }
}
