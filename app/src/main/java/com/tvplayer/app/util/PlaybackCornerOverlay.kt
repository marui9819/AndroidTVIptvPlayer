package com.tvplayer.app.util

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minute-aligned updates only; no per-second polling on legacy TV. */
internal class PlaybackCornerOverlay(
    private val container: View, private val clock: TextView, private val version: TextView,
    private val countdown: TextView? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var showClock = true
    private var showVersion = true
    private var deadlineMs = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            render()
            val clockDelay = 60_000L - System.currentTimeMillis() % 60_000L
            val timerDelay = if (deadlineMs > SystemClock.elapsedRealtime()) {
                val remainder = (deadlineMs - SystemClock.elapsedRealtime()) % 60_000L
                if (remainder == 0L) 60_000L else remainder
            } else 60_000L
            handler.postDelayed(this, minOf(clockDelay, timerDelay).coerceAtLeast(100L) + 30L)
        }
    }

    fun start(clockVisible: Boolean, versionVisible: Boolean, deadline: Long = 0L) {
        showClock = clockVisible; showVersion = versionVisible; deadlineMs = deadline
        active = true
        handler.removeCallbacks(tick)
        tick.run()
    }

    fun render() {
        clock.visibility = if (showClock) View.VISIBLE else View.GONE
        version.visibility = if (showVersion) View.VISIBLE else View.GONE
        container.visibility = if (showClock || showVersion) View.VISIBLE else View.GONE
        if (showClock) clock.text = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date())
        countdown?.apply {
            val remaining = ChannelUserPolicy.remainingMinutes(deadlineMs, SystemClock.elapsedRealtime())
            visibility = if (deadlineMs > 0L && remaining > 0L) View.VISIBLE else View.GONE
            text = "${remaining}分后退出"
        }
    }

    fun stop() { active = false; handler.removeCallbacks(tick) }
}
