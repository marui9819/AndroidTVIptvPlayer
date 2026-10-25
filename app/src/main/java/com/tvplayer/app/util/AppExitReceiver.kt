package com.tvplayer.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tvplayer.app.App

/** Receives the playback process's request to stop the launcher process too. */
class AppExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != App.ACTION_EXIT_MAIN_PROCESS) return
        (context.applicationContext as? App)?.onRemoteExitRequested()
    }
}
