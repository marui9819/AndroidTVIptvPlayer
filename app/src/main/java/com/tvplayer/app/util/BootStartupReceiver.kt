package com.tvplayer.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tvplayer.app.ui.main.MainActivity

/** Opens the app after boot only when the user opted in on a TV device. */
class BootStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!DeviceProfile.isTv(context)) return

        val enabled = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("boot_start_enabled", false)
        if (!enabled) return

        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
            Log.i(TAG, "Launching app after TV boot")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to launch app after TV boot", error)
        }
    }

    private companion object {
        const val TAG = "BootStartupReceiver"
    }
}
