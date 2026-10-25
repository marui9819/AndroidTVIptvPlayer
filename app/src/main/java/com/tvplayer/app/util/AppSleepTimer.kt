package com.tvplayer.app.util

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.tvplayer.app.App

/** One dormant callback, not a polling service; installed in launcher AND playback processes. */
internal class AppSleepTimer(private val app: Application) : Application.ActivityLifecycleCallbacks {
    private val activities = LinkedHashSet<Activity>()
    private val handler = Handler(Looper.getMainLooper())
    private var expiry: Runnable? = null

    fun initialize() {
        app.registerActivityLifecycleCallbacks(this)
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PlaybackControlsProvider.ACTION_EXPIRED) {
                    val token = intent.getStringExtra("token").orEmpty()
                    val latest = PlaybackControls.snapshot(app) ?: return
                    // A queued old alarm/broadcast cannot close a restarted or cancelled timer.
                    if (token.isNotEmpty() && latest.getString("expired") == token) {
                        expiry?.let(handler::removeCallbacks)
                        activities.toList().forEach { activity ->
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                (app as? App)?.requestExit(activity, sleepTimerExpired = true)
                                    ?: activity.finishAndRemoveTask()
                            }
                        }
                    }
                } else refresh()
            }
        }, IntentFilter().apply {
            addAction(PlaybackControlsProvider.ACTION_UPDATED)
            addAction(PlaybackControlsProvider.ACTION_EXPIRED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    private fun refresh() {
        expiry?.let(handler::removeCallbacks)
        expiry = null
        val state = PlaybackControls.snapshot(app) ?: return
        val token = state.getString("token").orEmpty()
        if (token.isEmpty()) return
        expiry = Runnable { PlaybackControls.expire(app, token) }.also {
            handler.postDelayed(it, (state.getLong("deadline") - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        }
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) { activities.add(activity) }
    override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
    override fun onActivityResumed(activity: Activity) { refresh() }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
