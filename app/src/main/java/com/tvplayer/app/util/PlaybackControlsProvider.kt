package com.tvplayer.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.util.UUID

/** Main-process authority: do not use cached multi-process SharedPreferences in :playback. */
class PlaybackControlsProvider : ContentProvider() {
    private val appContext get() = requireNotNull(context)
    private val timerPrefs by lazy { appContext.getSharedPreferences("sleep_timer_runtime", Context.MODE_PRIVATE) }
    private val bootId by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) {
                "count:${Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)}"
            } else {
                File("/proc/sys/kernel/random/boot_id").readText().trim()
            }
        }.getOrDefault("")
    }

    override fun onCreate() = true

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        var state = readState()
        val now = SystemClock.elapsedRealtime()
        if (state.token.isNotEmpty() && !state.isValid(now, bootId)) {
            clearTimer()
            state = SleepTimerState()
        }
        when (method) {
            "start" -> {
                val duration = extras?.getLong("durationMs") ?: 0L
                require(duration in 60_000L..86_340_000L) { "Invalid sleep duration" }
                state = SleepTimerState(UUID.randomUUID().toString(), now, now + duration, bootId)
                timerPrefs.edit().putString("token", state.token).putLong("started", now)
                    .putLong("deadline", state.deadlineMs).putString("boot", bootId)
                    .putString("expired", "").putLong("lastDuration", duration).commit()
                scheduleAlarm(state)
                android.util.Log.i("AppSleepTimer", "started durationMs=$duration deadlineElapsedMs=${state.deadlineMs}")
                broadcast(ACTION_UPDATED)
            }
            "cancel" -> {
                clearTimer(); state = SleepTimerState()
                android.util.Log.i("AppSleepTimer", "cancelled")
                broadcast(ACTION_UPDATED)
            }
            "expire" -> if (arg == state.token && state.isDue(now)) {
                val expiredToken = state.token
                clearTimer(expiredToken)
                state = SleepTimerState()
                android.util.Log.i("AppSleepTimer", "expired elapsedMs=$now")
                broadcast(ACTION_EXPIRED, expiredToken)
            }
            "snapshot" -> Unit
            "set_alias" -> {
                val ok = ChannelUserState.get(appContext).setAlias(arg.orEmpty(), extras?.getString("name").orEmpty())
                if (!ok) throw IllegalArgumentException("Alias storage limit reached")
            }
            "record_recent" -> {
                require(!arg.isNullOrBlank())
                ChannelUserState.get(appContext).record(RecentPlayback(
                    arg, extras?.getString("name").orEmpty(), extras?.getString("logicalKey").orEmpty(),
                    System.currentTimeMillis(), extras?.getLong("channelId") ?: 0L
                ))
            }
            else -> throw IllegalArgumentException("Unknown controls operation")
        }
        val prefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val user = ChannelUserState.get(appContext)
        return Bundle().apply {
            putBoolean("showName", user.showName)
            putInt("nameTransparency", user.transparency)
            putBoolean("showClock", user.showClock)
            putBoolean("showVersion", user.showVersion)
            putInt("defaultResizeMode", user.resizeMode)
            putBundle("aliases", Bundle().apply { user.aliases().forEach { (url, name) -> putString(url, name) } })
            putBoolean("precheck", prefs.getBoolean("av_precheck_enabled", true))
            putBoolean("audioRecovery", prefs.getBoolean("missing_audio_recovery_enabled", true))
            putString("token", state.token)
            putLong("deadline", state.deadlineMs)
            putString("expired", timerPrefs.getString("expired", ""))
            putLong("lastDuration", timerPrefs.getLong("lastDuration", 3_600_000L))
        }
    }

    private fun readState() = SleepTimerState(
        timerPrefs.getString("token", "").orEmpty(), timerPrefs.getLong("started", 0L),
        timerPrefs.getLong("deadline", 0L), timerPrefs.getString("boot", "").orEmpty()
    )

    private fun clearTimer(expiredToken: String = "") {
        timerPrefs.edit().remove("token").remove("started").remove("deadline").remove("boot")
            .putString("expired", expiredToken).commit()
        (appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent())
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext, 121, Intent(appContext, SleepTimerReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleAlarm(state: SleepTimerState) {
        val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Foreground handlers in BOTH processes give an on-time watching cutoff.
        // Background fallback needs no new exact-alarm or battery exemption permission.
        if (Build.VERSION.SDK_INT >= 23) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, state.deadlineMs, alarmIntent())
        } else {
            alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, state.deadlineMs, alarmIntent())
        }
    }

    private fun broadcast(action: String, token: String = "") {
        appContext.sendBroadcast(Intent(action).setPackage(appContext.packageName).putExtra("token", token))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val ACTION_UPDATED = "com.tvplayer.app.SLEEP_TIMER_UPDATED"
        const val ACTION_EXPIRED = "com.tvplayer.app.SLEEP_TIMER_EXPIRED"
    }
}

internal object PlaybackControls {
    private fun call(context: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle? =
        runCatching {
            context.contentResolver.call(Uri.parse("content://${context.packageName}.playback-controls"), method, arg, extras)
        }.getOrNull()

    fun snapshot(context: Context) = call(context, "snapshot")
    fun startTimer(context: Context, hours: Int, minutes: Int): Boolean {
        val duration = SleepTimerState.durationMs(hours, minutes) ?: return false
        return call(context, "start", extras = Bundle().apply { putLong("durationMs", duration) }) != null
    }
    fun cancelTimer(context: Context) = call(context, "cancel") != null
    fun expire(context: Context, token: String) { call(context, "expire", token) }
    fun setAlias(context: Context, url: String, name: String) =
        call(context, "set_alias", url, Bundle().apply { putString("name", name) })
    fun recordRecent(context: Context, channel: com.tvplayer.app.data.model.Channel): Boolean =
        call(context, "record_recent", channel.url.trim(), Bundle().apply {
            putString("name", channel.name); putString("logicalKey", channel.normalizedName)
            putLong("channelId", channel.id)
        }) != null
}

class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = PlaybackControls.snapshot(context) ?: return
        PlaybackControls.expire(context, state.getString("token").orEmpty())
    }
}
