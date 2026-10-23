package com.tvplayer.app.util

/** elapsedRealtime deadlines are unaffected by channel changes or wall-clock adjustments. */
internal data class SleepTimerState(
    val token: String = "",
    val startedAtMs: Long = 0L,
    val deadlineMs: Long = 0L,
    val bootId: String = ""
) {
    fun isValid(nowMs: Long, currentBootId: String): Boolean =
        token.isNotEmpty() && deadlineMs > startedAtMs && nowMs >= startedAtMs &&
            (bootId.isEmpty() || currentBootId.isEmpty() || bootId == currentBootId)

    fun isDue(nowMs: Long) = token.isNotEmpty() && deadlineMs > 0L && nowMs >= deadlineMs
    fun remainingMs(nowMs: Long) = (deadlineMs - nowMs).coerceAtLeast(0L)

    companion object {
        fun durationMs(hours: Int, minutes: Int): Long? {
            if (hours !in 0..23 || minutes !in 0..59) return null
            return (hours * 60L + minutes).takeIf { it > 0L }?.times(60_000L)
        }
    }
}
