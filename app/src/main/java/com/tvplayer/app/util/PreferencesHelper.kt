package com.tvplayer.app.util

import android.content.Context
import android.content.SharedPreferences
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import java.util.*

class PreferencesHelper(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    // App Settings
    var isFirstLaunch: Boolean
        get() = prefs.getBoolean("first_launch", true)
        set(value) = prefs.edit().putBoolean("first_launch", value).apply()

    var isAutoLoadEnabled: Boolean
        get() = prefs.getBoolean("auto_load_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_load_enabled", value).apply()

    var autoPlayEnabled: Boolean
        get() = prefs.getBoolean("auto_play_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_play_enabled", value).apply()

    // Player Settings
    var useHardwareAcceleration: Boolean
        get() = prefs.getBoolean("hw_acceleration", Constants.DefaultSettings.ENABLE_HARDWARE_ACCELERATION)
        set(value) = prefs.edit().putBoolean("hw_acceleration", value).apply()

    var enableSubtitles: Boolean
        get() = prefs.getBoolean("enable_subtitles", Constants.DefaultSettings.ENABLE_SUBTITLES)
        set(value) = prefs.edit().putBoolean("enable_subtitles", value).apply()

    var videoQuality: String
        get() = prefs.getString("video_quality", Constants.DefaultSettings.DEFAULT_PLAYBACK_SPEED) ?: Constants.DefaultSettings.DEFAULT_PLAYBACK_SPEED
        set(value) = prefs.edit().putString("video_quality", value).apply()

    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", Constants.DefaultSettings.DEFAULT_PLAYBACK_SPEED)
        set(value) = prefs.edit().putFloat("playback_speed", value).apply()

    var defaultPlaylistId: Long
        get() = prefs.getLong("default_playlist_id", -1L)
        set(value) = prefs.edit().putLong("default_playlist_id", value).apply()

    var lastPlayedChannelId: Long
        get() = prefs.getLong("last_played_channel_id", -1L)
        set(value) = prefs.edit().putLong("last_played_channel_id", value).apply()

    var lastPlayedPosition: Long
        get() = prefs.getLong("last_played_position", 0L)
        set(value) = prefs.edit().putLong("last_played_position", value).apply()

    // UI Settings
    var uiTheme: String
        get() = prefs.getString("ui_theme", "dark") ?: "dark"
        set(value) = prefs.edit().putString("ui_theme", value).apply()

    var showChannelNumbers: Boolean
        get() = prefs.getBoolean("show_channel_numbers", true)
        set(value) = prefs.edit().putBoolean("show_channel_numbers", value).apply()

    var showChannelLogos: Boolean
        get() = prefs.getBoolean("show_channel_logos", true)
        set(value) = prefs.edit().putBoolean("show_channel_logos", value).apply()

    // Network Settings
    var useProxy: Boolean
        get() = prefs.getBoolean("use_proxy", false)
        set(value) = prefs.edit().putBoolean("use_proxy", value).apply()

    var proxyUrl: String?
        get() = prefs.getString("proxy_url", null)
        set(value) = prefs.edit().putString("proxy_url", value).apply()

    var connectionTimeout: Int
        get() = prefs.getInt("connection_timeout", (Constants.CONNECT_TIMEOUT / 1000).toInt())
        set(value) = prefs.edit().putInt("connection_timeout", value).apply()

    // Cache Settings
    var minCacheSize: Long
        get() = prefs.getLong("min_cache_size", Constants.DefaultSettings.MIN_CACHE_SIZE)
        set(value) = prefs.edit().putLong("min_cache_size", value).apply()

    var maxCacheSize: Long
        get() = prefs.getLong("max_cache_size", Constants.DefaultSettings.MAX_CACHE_SIZE)
        set(value) = prefs.edit().putLong("max_cache_size", value).apply()

    // EPG Settings
    var epgEnabled: Boolean
        get() = prefs.getBoolean("epg_enabled", false)
        set(value) = prefs.edit().putBoolean("epg_enabled", value).apply()

    var epgSourceUrl: String?
        get() = prefs.getString("epg_source_url", null)
        set(value) = prefs.edit().putString("epg_source_url", value).apply()

    // Analytics & Monitoring
    var analyticsEnabled: Boolean
        get() = prefs.getBoolean("analytics_enabled", Constants.ANALYTICS_ENABLED)
        set(value) = prefs.edit().putBoolean("analytics_enabled", value).apply()

    var errorReportingEnabled: Boolean
        get() = prefs.getBoolean("error_reporting_enabled", Constants.ERROR_REPORTING_ENABLED)
        set(value) = prefs.edit().putBoolean("error_reporting_enabled", value).apply()

    // Work Manager Settings
    var enableAutoRefresh: Boolean
        get() = prefs.getBoolean("enable_auto_refresh", true)
        set(value) = prefs.edit().putBoolean("enable_auto_refresh", value).apply()

    var refreshInterval: Long
        get() = prefs.getLong("refresh_interval", Constants.DEFAULT_REFRESH_INTERVAL)
        set(value) = prefs.edit().putLong("refresh_interval", value).apply()

    var refreshTimeOfDay: String?
        get() = prefs.getString("refresh_time_of_day", null) // Format: "HH:mm"
        set(value) = prefs.edit().putString("refresh_time_of_day", value).apply()

    // Channel Specific Settings
    fun getChannelFavorite(channelId: Long): Boolean {
        return prefs.getBoolean("channel_favorite_$channelId", false)
    }

    fun setChannelFavorite(channelId: Long, isFavorite: Boolean) {
        prefs.edit().putBoolean("channel_favorite_$channelId", isFavorite).apply()
    }

    fun getChannelCustomName(channelId: Long): String? {
        return prefs.getString("channel_name_$channelId", null)
    }

    fun setChannelCustomName(channelId: Long, customName: String?) {
        if (customName == null) {
            prefs.edit().remove("channel_name_$channelId").apply()
        } else {
            prefs.edit().putString("channel_name_$channelId", customName).apply()
        }
    }

    // Playback History
    fun addToWatchHistory(channel: Channel, playbackDuration: Long) {
        val timestamp = System.currentTimeMillis()
        prefs.edit()
            .putLong("watch_history_${channel.id}_timestamp", timestamp)
            .putLong("watch_history_${channel.id}_duration", playbackDuration)
            .apply()

        // Keep only last 100 watched channels
        val historyKeys = prefs.all.keys
            .filter { it.startsWith("watch_history_") && it.endsWith("_timestamp") }
            .map { it.removeSuffix("_timestamp").split("_").last().toLong() }
            .sortedDescending()
            .drop(100)

        historyKeys.forEach { channelId ->
            prefs.edit()
                .remove("watch_history_${channelId}_timestamp")
                .remove("watch_history_${channelId}_duration")
                .apply()
        }
    }

    fun getWatchHistory(): List<Pair<Channel, Long>> {
        // This would need to be implemented with proper Channel retrieval
        return emptyList()
    }

    // Import/Export Settings
    fun exportSettings(): String {
        val allSettings = mutableMapOf<String, Any>()
        prefs.all.forEach { (key, value) ->
            allSettings[key] = value
        }
        return Gson().toJson(allSettings)
    }

    fun importSettings(settingsJson: String): Boolean {
        return try {
            val gson = Gson()
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val settings: Map<String, Any> = gson.fromJson(settingsJson, type)

            val editor = prefs.edit()
            settings.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun initializeDefaults() {
        if (isFirstLaunch) {
            // Set default values on first launch
            isAutoLoadEnabled = true
            autoPlayEnabled = true
            useHardwareAcceleration = Constants.DefaultSettings.ENABLE_HARDWARE_ACCELERATION
            enableSubtitles = Constants.DefaultSettings.ENABLE_SUBTITLES
            videoQuality = Constants.DefaultSettings.DEFAULT_PLAYBACK_SPEED
            playbackSpeed = Constants.DefaultSettings.DEFAULT_PLAYBACK_SPEED
            uiTheme = "dark"
            showChannelNumbers = true
            showChannelLogos = true
            enableAutoRefresh = true
            refreshInterval = Constants.DEFAULT_REFRESH_INTERVAL
            analyticsEnabled = Constants.ANALYTICS_ENABLED
            errorReportingEnabled = Constants.ERROR_REPORTING_ENABLED
            minCacheSize = Constants.DefaultSettings.MIN_CACHE_SIZE
            maxCacheSize = Constants.DefaultSettings.MAX_CACHE_SIZE

            isFirstLaunch = false
        }
    }
}