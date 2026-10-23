package com.tvplayer.app.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvplayer.app.data.model.Channel

internal data class RecentPlayback(
    val url: String, val name: String, val logicalKey: String,
    val playedAt: Long, val channelId: Long = 0L
)

internal object ChannelUserPolicy {
    const val DEFAULT_HISTORY_LIMIT = 20
    fun historyLimit(value: Int) = value.coerceIn(1, 100)
    fun opacity(transparency: Int) = 1f - transparency.coerceIn(0, 100) / 100f
    fun remainingMinutes(deadlineMs: Long, nowMs: Long): Long =
        ((deadlineMs - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L

    fun record(existing: List<RecentPlayback>, entry: RecentPlayback, limit: Int): List<RecentPlayback> {
        if (entry.url.isBlank() || entry.playedAt <= 0L) return existing.take(historyLimit(limit))
        val normalized = entry.copy(url = entry.url.trim())
        return (listOf(normalized) + existing.filterNot {
            it.url.trim() == normalized.url ||
                (normalized.logicalKey.isNotBlank() && it.logicalKey == normalized.logicalKey)
        }).take(historyLimit(limit))
    }

    fun successfulPlayback(
        playing: Boolean, videoExpected: Boolean, firstFrame: Boolean,
        audioTracks: Int, audioAdvanced: Boolean, huyaVideo: Boolean
    ): Boolean = playing &&
        (if (videoExpected) firstFrame else audioTracks > 0 && audioAdvanced) &&
        (!huyaVideo || audioTracks > 0 && audioAdvanced) &&
        (audioTracks == 0 || audioAdvanced)

    fun resolveHistory(history: List<RecentPlayback>, channels: List<Channel>): List<Channel> {
        val byUrl = channels.groupBy { it.url.trim() }
        return history.mapNotNull { record ->
            byUrl[record.url.trim()]?.firstOrNull()?.copy(lastPlayed = record.playedAt)
        }
    }

    fun requestedSource(channels: List<Channel>, id: Long, expectedUrl: String?): Channel? {
        val url = expectedUrl?.trim().orEmpty()
        // An ID is only a hint after a source refresh. Never substitute a
        // different URL just because a recycled database ID happens to match.
        return if (url.isNotEmpty()) {
            channels.firstOrNull { it.id == id && it.url.trim() == url }
                ?: channels.firstOrNull { it.url.trim() == url }
        } else channels.firstOrNull { it.id == id }
    }
}

/** Only the main process writes this file. Playback accesses it through PlaybackControlsProvider. */
internal class ChannelUserState private constructor(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("channel_user_state", Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private var cachedAliasJson: String? = null
    private var cachedAliases: Map<String, String> = emptyMap()

    val historyLimit get() = ChannelUserPolicy.historyLimit(prefs.getInt("recent_history_limit", 20))
    val transparency get() = prefs.getInt("channel_name_transparency", 50).coerceIn(0, 100)
    val showName get() = prefs.getBoolean("show_current_channel_name", legacy.getBoolean("show_current_channel_name", true))
    val showClock get() = prefs.getBoolean("show_current_time", true)
    val showVersion get() = prefs.getBoolean("show_version_badge", true)
    val resizeMode get() = prefs.getString("default_resize_mode", "0")?.toIntOrNull()?.takeIf { it in setOf(0, 3, 4) } ?: 0

    fun history(): List<RecentPlayback> = runCatching {
        gson.fromJson<List<RecentPlayback>>(prefs.getString("recent_playback_v2", "[]"),
            object : TypeToken<List<RecentPlayback>>() {}.type)
            .filter { it.url.isNotBlank() && it.playedAt > 0L }.take(historyLimit)
    }.getOrDefault(emptyList())

    @Synchronized fun record(entry: RecentPlayback) {
        prefs.edit().putString("recent_playback_v2", gson.toJson(ChannelUserPolicy.record(history(), entry, historyLimit))).apply()
    }

    @Synchronized fun setHistoryLimit(limit: Int) {
        val count = ChannelUserPolicy.historyLimit(limit)
        val records = history().take(count)
        prefs.edit().putInt("recent_history_limit", count).putString("recent_playback_v2", gson.toJson(records)).apply()
    }

    @Synchronized fun aliases(): Map<String, String> {
        val json = prefs.getString("channel_aliases", "{}").orEmpty()
        if (json != cachedAliasJson) {
            cachedAliases = runCatching {
                gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
                    .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            }.getOrDefault(emptyMap())
            cachedAliasJson = json
        }
        return cachedAliases
    }

    fun displayName(channel: Channel): String = aliases()[channel.url.trim()] ?: channel.name

    @Synchronized fun setAlias(url: String, name: String): Boolean {
        val key = url.trim()
        if (key.isBlank()) return false
        val entries = aliases().toMutableMap()
        val alias = name.trim().take(60)
        if (alias.isEmpty()) entries.remove(key) else entries[key] = alias
        val json = gson.toJson(entries)
        if (entries.size > 500 || json.toByteArray(Charsets.UTF_8).size > 256 * 1024) return false
        prefs.edit().putString("channel_aliases", json).apply()
        return true
    }

    companion object {
        @Volatile private var instance: ChannelUserState? = null
        fun get(context: Context): ChannelUserState = instance ?: synchronized(this) {
            instance ?: ChannelUserState(context.applicationContext).also { instance = it }
        }
    }
}
