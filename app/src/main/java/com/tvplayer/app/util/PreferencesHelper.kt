package com.tvplayer.app.util

import android.content.Context
import android.content.SharedPreferences
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvplayer.app.data.model.Channel
import java.util.UUID

class PreferencesHelper(private val context: Context) {

    data class ChannelPlaybackSettings(
        val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        val decoderMode: String = CHANNEL_DECODER_DEFAULT,
        val huyaScheme: String = HUYA_SCHEME_HOT_SWAP,
        val douyuScheme: String = DOUYU_SCHEME_SMART
    )

    companion object {
        const val CHANNEL_DECODER_DEFAULT = "default"
        const val CHANNEL_DECODER_COMPATIBILITY = "compatibility"
        const val HUYA_SCHEME_HOT_SWAP = "huya_hot_swap"
        const val HUYA_SCHEME_SINGLE_FLV = "huya_single_flv"
        const val HUYA_SCHEME_SINGLE_HLS = "huya_single_hls"
        const val DOUYU_SCHEME_SMART = "douyu_smart"
        const val DOUYU_SCHEME_OFFICIAL = "douyu_official"
        const val DOUYU_SCHEME_PREVIEW = "douyu_preview"
        const val DOUYU_SCHEME_ENTRY = "douyu_entry"

        private val IMPORTABLE_KEYS = setOf(
            "auto_load_enabled", "auto_play_enabled", "boot_start_enabled", "auto_failover_enabled", "hw_acceleration",
            "enable_subtitles", "video_quality", "playback_speed", "show_epg_on_low_end_tv",
            "epg_schedule_count", "epg_schedule_count_slider", "channel_drawer_group_width",
            "channel_drawer_list_width",
            "av_precheck_enabled", "missing_audio_recovery_enabled",
            "ui_theme", "show_channel_numbers", "show_channel_logos", "show_channel_group_name",
            "show_channel_source_count", "show_preferred_source_name", "show_channel_probe_status",
            "show_current_channel_name",
            "show_unavailable_channels", "show_empty_channel_groups",
            "use_proxy", "proxy_url", "connection_timeout",
            "default_playlist_url", "huya_playback_scheme", "douyu_playback_scheme",
            "min_cache_size", "max_cache_size", "epg_enabled", "epg_source_url",
            "epg_source_urls", "epg_refresh_interval", "epg_refresh_time_of_day",
            "analytics_enabled", "error_reporting_enabled", "diagnostics_enabled",
            "diagnostics_server_url", "enable_auto_refresh", "refresh_interval",
            "refresh_time_of_day", "enable_channel_probe_refresh",
            "channel_probe_interval", "channel_probe_thread_count",
            "channel_probe_delay_ms", "channel_probe_timeout_ms"
        )
        private val IMPORTABLE_INT_KEYS = setOf(
            "connection_timeout", "channel_probe_thread_count", "epg_schedule_count_slider",
            "channel_drawer_group_width", "channel_drawer_list_width"
        )
        private val IMPORTABLE_LONG_KEYS = setOf(
            "min_cache_size", "max_cache_size", "epg_refresh_interval",
            "refresh_interval", "channel_probe_interval", "channel_probe_delay_ms",
            "channel_probe_timeout_ms"
        )
        private val IMPORTABLE_FLOAT_KEYS = setOf("playback_speed")

        private fun isImportableKey(key: String): Boolean =
            key in IMPORTABLE_KEYS ||
                key.startsWith("channel_resize_mode_") ||
                key.startsWith("channel_decoder_mode_") ||
                key.startsWith("channel_huya_scheme_") ||
                key.startsWith("channel_douyu_scheme_")
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Older releases stored this setting as a String under epg_schedule_count.
        // Use a new integer key so SeekBarPreference never reads a String as an Int.
        if (!prefs.contains("epg_schedule_count_slider")) {
            val legacyValue = runCatching {
                prefs.getString("epg_schedule_count", null)?.toIntOrNull()
            }.getOrNull()
            prefs.edit().putInt("epg_schedule_count_slider", (legacyValue ?: 5).coerceIn(5, 20)).apply()
        }
    }

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

    var bootStartEnabled: Boolean
        get() = prefs.getBoolean("boot_start_enabled", false)
        set(value) = prefs.edit().putBoolean("boot_start_enabled", value).apply()

    /**
     * Whether a failed live source may be replaced automatically.
     *
     * This is intentionally off by default.  A transient HLS segment/anti-hotlink
     * failure is not proof that the channel itself is unavailable, especially on
     * older TV firmware where the decoder can briefly stop presenting frames.
     */
    var autoFailoverEnabled: Boolean
        get() = prefs.getBoolean("auto_failover_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_failover_enabled", value).apply()

    // Player Settings
    var useHardwareAcceleration: Boolean
        get() = prefs.getBoolean("hw_acceleration", Constants.DefaultSettings.ENABLE_HARDWARE_ACCELERATION)
        set(value) = prefs.edit().putBoolean("hw_acceleration", value).apply()

    var enableSubtitles: Boolean
        get() = prefs.getBoolean("enable_subtitles", Constants.DefaultSettings.ENABLE_SUBTITLES)
        set(value) = prefs.edit().putBoolean("enable_subtitles", value).apply()

    var videoQuality: String
        get() = prefs.getString("video_quality", Constants.DefaultSettings.DEFAULT_VIDEO_QUALITY) ?: Constants.DefaultSettings.DEFAULT_VIDEO_QUALITY
        set(value) = prefs.edit().putString("video_quality", value).apply()

    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", Constants.DefaultSettings.DEFAULT_PLAYBACK_SPEED)
        set(value) = prefs.edit().putFloat("playback_speed", value).apply()

    var epgScheduleCount: Int
        get() = prefs.getInt("epg_schedule_count_slider", 5).coerceIn(5, 20)
        set(value) = prefs.edit().putInt("epg_schedule_count_slider", value.coerceIn(5, 20)).apply()

    var channelDrawerGroupWidthDp: Int
        get() = prefs.getInt("channel_drawer_group_width", 250).coerceIn(150, 400)
        set(value) = prefs.edit().putInt("channel_drawer_group_width", value.coerceIn(150, 400)).apply()

    var channelDrawerListWidthDp: Int
        get() = prefs.getInt("channel_drawer_list_width", 350).coerceIn(250, 600)
        set(value) = prefs.edit().putInt("channel_drawer_list_width", value.coerceIn(250, 600)).apply()

    var defaultPlaylistId: Long
        get() = prefs.getLong("default_playlist_id", -1L)
        set(value) = prefs.edit().putLong("default_playlist_id", value).apply()

    var lastPlayedChannelId: Long
        get() = prefs.getLong("last_played_channel_id", -1L)
        set(value) = prefs.edit().putLong("last_played_channel_id", value).apply()

    var lastPlayedChannelUrl: String?
        get() = prefs.getString("last_played_channel_url", null)
        set(value) = prefs.edit().putString("last_played_channel_url", value).apply()

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
        get() = prefs.getBoolean("show_channel_logos", false)
        set(value) = prefs.edit().putBoolean("show_channel_logos", value).apply()

    var showChannelGroupName: Boolean
        get() = prefs.getBoolean("show_channel_group_name", false)
        set(value) = prefs.edit().putBoolean("show_channel_group_name", value).apply()

    var showChannelSourceCount: Boolean
        get() = prefs.getBoolean("show_channel_source_count", true)
        set(value) = prefs.edit().putBoolean("show_channel_source_count", value).apply()

    var showPreferredSourceName: Boolean
        get() = prefs.getBoolean("show_preferred_source_name", false)
        set(value) = prefs.edit().putBoolean("show_preferred_source_name", value).apply()

    var showChannelProbeStatus: Boolean
        get() = prefs.getBoolean("show_channel_probe_status", true)
        set(value) = prefs.edit().putBoolean("show_channel_probe_status", value).apply()

    var showCurrentChannelName: Boolean
        get() = prefs.getBoolean("show_current_channel_name", true)
        set(value) = prefs.edit().putBoolean("show_current_channel_name", value).apply()

    var showUnavailableChannels: Boolean
        get() = prefs.getBoolean("show_unavailable_channels", false)
        set(value) = prefs.edit().putBoolean("show_unavailable_channels", value).apply()

    var showEmptyChannelGroups: Boolean
        get() = prefs.getBoolean("show_empty_channel_groups", false)
        set(value) = prefs.edit().putBoolean("show_empty_channel_groups", value).apply()

    /** Opt in to parsing and showing cached EPG data on devices using the low-end TV profile. */
    var showEpgOnLowEndTv: Boolean
        get() = prefs.getBoolean("show_epg_on_low_end_tv", false)
        set(value) = prefs.edit().putBoolean("show_epg_on_low_end_tv", value).apply()

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

    var defaultPlaylistUrl: String
        get() = prefs.getString("default_playlist_url", Constants.DefaultPlaylists.DEFAULT_URL)
            ?: Constants.DefaultPlaylists.DEFAULT_URL
        set(value) = prefs.edit().putString("default_playlist_url", value).apply()

    var huyaPlaybackScheme: String
        get() = prefs.getString("huya_playback_scheme", HUYA_SCHEME_HOT_SWAP) ?: HUYA_SCHEME_HOT_SWAP
        set(value) = prefs.edit().putString("huya_playback_scheme", value).apply()

    var douyuPlaybackScheme: String
        get() = prefs.getString("douyu_playback_scheme", DOUYU_SCHEME_SMART) ?: DOUYU_SCHEME_SMART
        set(value) = prefs.edit().putString("douyu_playback_scheme", value).apply()

    // Cache Settings
    var minCacheSize: Long
        get() = prefs.getLong("min_cache_size", Constants.DefaultSettings.MIN_CACHE_SIZE)
        set(value) = prefs.edit().putLong("min_cache_size", value).apply()

    var maxCacheSize: Long
        get() = prefs.getLong("max_cache_size", Constants.DefaultSettings.MAX_CACHE_SIZE)
        set(value) = prefs.edit().putLong("max_cache_size", value).apply()

    // EPG Settings
    var epgEnabled: Boolean
        get() = prefs.getBoolean("epg_enabled", true)
        set(value) = prefs.edit().putBoolean("epg_enabled", value).apply()

    var epgSourceUrl: String?
        get() = prefs.getString("epg_source_url", null)
        set(value) = prefs.edit().putString("epg_source_url", value).apply()

    var epgSourceUrls: String
        get() = prefs.getString(
            "epg_source_urls",
            Constants.DefaultEpgSources.ALL.joinToString(separator = "\n")
        ) ?: Constants.DefaultEpgSources.ALL.joinToString(separator = "\n")
        set(value) = prefs.edit().putString("epg_source_urls", value).apply()

    var epgRefreshInterval: Long
        get() = prefs.getLong("epg_refresh_interval", Constants.DEFAULT_EPG_REFRESH_INTERVAL)
        set(value) = prefs.edit().putLong("epg_refresh_interval", value).apply()

    var epgRefreshTimeOfDay: String
        get() = prefs.getString("epg_refresh_time_of_day", Constants.DEFAULT_EPG_REFRESH_TIME)
            ?: Constants.DEFAULT_EPG_REFRESH_TIME
        set(value) = prefs.edit().putString("epg_refresh_time_of_day", value).apply()

    var epgLastUpdated: Long
        get() = prefs.getLong("epg_last_updated", 0L)
        set(value) = prefs.edit().putLong("epg_last_updated", value).apply()

    var epgLastSuccessfulUrl: String?
        get() = prefs.getString("epg_last_successful_url", null)
        set(value) = prefs.edit().putString("epg_last_successful_url", value).apply()

    // Analytics & Monitoring
    var analyticsEnabled: Boolean
        get() = prefs.getBoolean("analytics_enabled", Constants.ANALYTICS_ENABLED)
        set(value) = prefs.edit().putBoolean("analytics_enabled", value).apply()

    var errorReportingEnabled: Boolean
        get() = prefs.getBoolean("error_reporting_enabled", Constants.ERROR_REPORTING_ENABLED)
        set(value) = prefs.edit().putBoolean("error_reporting_enabled", value).apply()

    var diagnosticsEnabled: Boolean
        get() = prefs.getBoolean("diagnostics_enabled", false)
        set(value) = prefs.edit().putBoolean("diagnostics_enabled", value).apply()

    var diagnosticsServerUrl: String
        get() = prefs.getString(
            "diagnostics_server_url",
            Constants.DefaultSettings.DEFAULT_DIAGNOSTICS_SERVER_URL
        ) ?: Constants.DefaultSettings.DEFAULT_DIAGNOSTICS_SERVER_URL
        set(value) = prefs.edit().putString("diagnostics_server_url", value.trim()).apply()

    var remoteSourceServerUrl: String
        get() = prefs.getString(
            "remote_source_server_url",
            Constants.DefaultSettings.DEFAULT_REMOTE_SOURCE_SERVER_URL
        ) ?: Constants.DefaultSettings.DEFAULT_REMOTE_SOURCE_SERVER_URL
        set(value) = prefs.edit().putString("remote_source_server_url", value.trim().trimEnd('/')).apply()

    val remoteSourceDeviceId: String
        get() = prefs.getString("remote_source_device_id", null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("remote_source_device_id", it).apply()
            }

    val remoteSourceAccessToken: String
        get() = prefs.getString("remote_source_access_token", null)
            ?: UUID.randomUUID().toString().replace("-", "").also {
                prefs.edit().putString("remote_source_access_token", it).apply()
            }

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

    var enableChannelProbeRefresh: Boolean
        get() = prefs.getBoolean("enable_channel_probe_refresh", true)
        set(value) = prefs.edit().putBoolean("enable_channel_probe_refresh", value).apply()

    var channelProbeInterval: Long
        get() = prefs.getLong("channel_probe_interval", Constants.DEFAULT_CHANNEL_PROBE_INTERVAL)
        set(value) = prefs.edit().putLong("channel_probe_interval", value).apply()

    var channelProbeThreadCount: Int
        get() = prefs.getInt("channel_probe_thread_count", Constants.DEFAULT_CHANNEL_PROBE_THREAD_COUNT)
        set(value) = prefs.edit().putInt("channel_probe_thread_count", value.coerceIn(1, 8)).apply()

    var channelProbeDelayMs: Long
        get() = prefs.getLong("channel_probe_delay_ms", Constants.DEFAULT_CHANNEL_PROBE_DELAY_MS)
        set(value) = prefs.edit().putLong("channel_probe_delay_ms", value.coerceAtLeast(0L)).apply()

    var channelProbeTimeoutMs: Long
        get() = prefs.getLong("channel_probe_timeout_ms", Constants.DEFAULT_CHANNEL_PROBE_TIMEOUT_MS)
        set(value) = prefs.edit().putLong("channel_probe_timeout_ms", value.coerceAtLeast(1000L)).apply()

    var channelProbeLastCompletedAt: Long
        get() = prefs.getLong("channel_probe_last_completed_at", 0L)
        set(value) = prefs.edit().putLong("channel_probe_last_completed_at", value).apply()

    fun getEpgSourceUrlList(): List<String> {
        return epgSourceUrls
            .split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

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

        // Keep only the 100 most recently watched channels. The previous
        // implementation sorted by channel ID, which could delete a newly
        // watched channel merely because its numeric ID was small.
        val historyEntries = prefs.all.keys
            .filter { it.startsWith("watch_history_") && it.endsWith("_timestamp") }
            .mapNotNull { timestampKey ->
                val channelId = timestampKey
                    .removePrefix("watch_history_")
                    .removeSuffix("_timestamp")
                    .toLongOrNull()
                    ?: return@mapNotNull null
                val timestamp = prefs.all[timestampKey] as? Long ?: return@mapNotNull null
                channelId to timestamp
            }
            .sortedByDescending { it.second }
            .drop(100)

        historyEntries.forEach { (channelId, _) ->
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

    fun getChannelPlaybackSettings(channelKey: String, defaultResizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT): ChannelPlaybackSettings {
        val key = sanitizeChannelKey(channelKey)
        val resizeMode = prefs.getInt("channel_resize_mode_$key", defaultResizeMode)
        val decoderMode = prefs.getString("channel_decoder_mode_$key", CHANNEL_DECODER_DEFAULT)
            ?: CHANNEL_DECODER_DEFAULT
        val huyaScheme = prefs.getString("channel_huya_scheme_$key", huyaPlaybackScheme)
            ?: huyaPlaybackScheme
        val douyuScheme = prefs.getString("channel_douyu_scheme_$key", douyuPlaybackScheme)
            ?: douyuPlaybackScheme
        return ChannelPlaybackSettings(
            resizeMode = resizeMode,
            decoderMode = decoderMode,
            huyaScheme = huyaScheme,
            douyuScheme = douyuScheme
        )
    }

    fun setChannelResizeMode(channelKey: String, resizeMode: Int) {
        prefs.edit().putInt("channel_resize_mode_${sanitizeChannelKey(channelKey)}", resizeMode).apply()
    }

    fun setChannelDecoderMode(channelKey: String, decoderMode: String) {
        prefs.edit().putString("channel_decoder_mode_${sanitizeChannelKey(channelKey)}", decoderMode).apply()
    }

    fun setChannelHuyaScheme(channelKey: String, scheme: String) {
        prefs.edit().putString("channel_huya_scheme_${sanitizeChannelKey(channelKey)}", scheme).apply()
    }

    fun setChannelDouyuScheme(channelKey: String, scheme: String) {
        prefs.edit().putString("channel_douyu_scheme_${sanitizeChannelKey(channelKey)}", scheme).apply()
    }

    fun clearChannelPlaybackOverrides(channelKey: String) {
        val key = sanitizeChannelKey(channelKey)
        prefs.edit()
            .remove("channel_resize_mode_$key")
            .remove("channel_decoder_mode_$key")
            .remove("channel_huya_scheme_$key")
            .remove("channel_douyu_scheme_$key")
            .apply()
    }

    private fun sanitizeChannelKey(channelKey: String): String {
        return channelKey.replace(Regex("[^A-Za-z0-9_]"), "_")
    }

    // Import/Export Settings
    fun exportSettings(): String {
        val allSettings = mutableMapOf<String, Any?>()
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
                if (!isImportableKey(key)) return@forEach
                if (key == "epg_schedule_count" || key == "epg_schedule_count_slider") {
                    val count = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else -> null
                    } ?: return@forEach
                    editor.putInt("epg_schedule_count_slider", count.coerceIn(5, 20))
                    return@forEach
                }
                when {
                    value is Boolean -> editor.putBoolean(key, value)
                    value is String -> editor.putString(key, value)
                    value is Number && key in IMPORTABLE_FLOAT_KEYS ->
                        editor.putFloat(key, value.toFloat())
                    value is Number && (
                        key in IMPORTABLE_INT_KEYS || key.startsWith("channel_resize_mode_")
                        ) -> editor.putInt(key, value.toInt())
                    value is Number && key in IMPORTABLE_LONG_KEYS ->
                        editor.putLong(key, value.toLong())
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
            autoFailoverEnabled = false
            useHardwareAcceleration = Constants.DefaultSettings.ENABLE_HARDWARE_ACCELERATION
            enableSubtitles = Constants.DefaultSettings.ENABLE_SUBTITLES
            videoQuality = Constants.DefaultSettings.DEFAULT_VIDEO_QUALITY
            playbackSpeed = Constants.DefaultSettings.DEFAULT_PLAYBACK_SPEED
            uiTheme = "dark"
            showChannelNumbers = true
            showChannelLogos = false
            showChannelGroupName = false
            showChannelSourceCount = true
            showPreferredSourceName = false
            showChannelProbeStatus = true
            channelDrawerGroupWidthDp = 250
            channelDrawerListWidthDp = 350
            showUnavailableChannels = false
            showEmptyChannelGroups = false
            enableAutoRefresh = true
            refreshInterval = Constants.DEFAULT_REFRESH_INTERVAL
            defaultPlaylistUrl = Constants.DefaultPlaylists.DEFAULT_URL
            huyaPlaybackScheme = HUYA_SCHEME_HOT_SWAP
            douyuPlaybackScheme = DOUYU_SCHEME_SMART
            epgEnabled = true
            epgSourceUrls = Constants.DefaultEpgSources.ALL.joinToString(separator = "\n")
            epgRefreshInterval = Constants.DEFAULT_EPG_REFRESH_INTERVAL
            epgRefreshTimeOfDay = Constants.DEFAULT_EPG_REFRESH_TIME
            enableChannelProbeRefresh = true
            channelProbeInterval = Constants.DEFAULT_CHANNEL_PROBE_INTERVAL
            channelProbeThreadCount = Constants.DEFAULT_CHANNEL_PROBE_THREAD_COUNT
            channelProbeDelayMs = Constants.DEFAULT_CHANNEL_PROBE_DELAY_MS
            channelProbeTimeoutMs = Constants.DEFAULT_CHANNEL_PROBE_TIMEOUT_MS
            analyticsEnabled = Constants.ANALYTICS_ENABLED
            errorReportingEnabled = Constants.ERROR_REPORTING_ENABLED
            minCacheSize = Constants.DefaultSettings.MIN_CACHE_SIZE
            maxCacheSize = Constants.DefaultSettings.MAX_CACHE_SIZE

            isFirstLaunch = false
        }

        // This profile is deliberately applied once on older Android TV / MStar
        // devices.  Their MediaCodec and storage stacks are easily saturated by
        // a large EPG parse, logo downloads and an automatic playback launch at
        // the same time.  Users can turn individual options back on later.
        if (DeviceProfile.isLegacyTv(context) && !prefs.getBoolean("legacy_tv_profile_v210", false)) {
            autoPlayEnabled = false
            showChannelLogos = false
            enableAutoRefresh = false
            epgEnabled = false
            enableChannelProbeRefresh = false
            huyaPlaybackScheme = HUYA_SCHEME_SINGLE_HLS
            prefs.edit().putBoolean("legacy_tv_profile_v210", true).apply()
        }

        // v2.6.2 replaces the old always-off legacy probe setting with a safe
        // user-driven category check.  Keep the full background scan disabled
        // while playback is active, but let the player refresh a selected
        // category one channel at a time with a strict five-second timeout.
        if (DeviceProfile.isLegacyTv(context) && !prefs.getBoolean("legacy_tv_probe_v262", false)) {
            enableChannelProbeRefresh = true
            channelProbeInterval = Constants.DEFAULT_CHANNEL_PROBE_INTERVAL
            channelProbeThreadCount = 1
            channelProbeDelayMs = Constants.DEFAULT_CHANNEL_PROBE_DELAY_MS
            channelProbeTimeoutMs = Constants.DEFAULT_CHANNEL_PROBE_TIMEOUT_MS
            prefs.edit().putBoolean("legacy_tv_probe_v262", true).apply()
        }

        if (refreshInterval == Constants.REFRESH_INTERVAL_6_HOURS || refreshInterval == Constants.REFRESH_INTERVAL_5_MINUTES) {
            refreshInterval = Constants.DEFAULT_REFRESH_INTERVAL
        }

        if (defaultPlaylistUrl.isBlank()) {
            defaultPlaylistUrl = Constants.DefaultPlaylists.DEFAULT_URL
        }

        if (huyaPlaybackScheme.isBlank()) {
            huyaPlaybackScheme = HUYA_SCHEME_HOT_SWAP
        }

        if (douyuPlaybackScheme.isBlank()) {
            douyuPlaybackScheme = DOUYU_SCHEME_SMART
        }

        if (epgSourceUrls.isBlank()) {
            epgSourceUrls = Constants.DefaultEpgSources.ALL.joinToString(separator = "\n")
        }

        if (!epgSourceUrl.isNullOrBlank() && epgSourceUrls.isBlank()) {
            epgSourceUrls = epgSourceUrl!!
        }

        if (epgRefreshInterval <= 0L) {
            epgRefreshInterval = Constants.DEFAULT_EPG_REFRESH_INTERVAL
        }

        if (epgRefreshTimeOfDay.isBlank()) {
            epgRefreshTimeOfDay = Constants.DEFAULT_EPG_REFRESH_TIME
        }

        if (channelProbeInterval <= 0L) {
            channelProbeInterval = Constants.DEFAULT_CHANNEL_PROBE_INTERVAL
        }

        if (channelProbeThreadCount <= 0) {
            channelProbeThreadCount = Constants.DEFAULT_CHANNEL_PROBE_THREAD_COUNT
        }

        if (channelProbeDelayMs < 0L) {
            channelProbeDelayMs = Constants.DEFAULT_CHANNEL_PROBE_DELAY_MS
        }

        if (channelProbeTimeoutMs <= 0L) {
            channelProbeTimeoutMs = Constants.DEFAULT_CHANNEL_PROBE_TIMEOUT_MS
        }
    }
}
