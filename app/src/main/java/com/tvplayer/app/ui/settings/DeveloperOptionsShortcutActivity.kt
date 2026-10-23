package com.tvplayer.app.ui.settings

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.tvplayer.app.util.SystemSettingsLauncher

/** Launcher icon that acts as a direct developer-options shortcut. */
class DeveloperOptionsShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = SystemSettingsLauncher.openDeveloperOptions(this)
        if (!result.success) {
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
