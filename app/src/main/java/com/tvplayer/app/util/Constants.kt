package com.tvplayer.app.util

object Constants {
    // Database
    const val DATABASE_NAME = "tvplayer_database.db"

    // Shared Preferences
    const val PREFS_NAME = "tvplayer_preferences"

    // Network
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; SM-G950F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
    const val CONNECT_TIMEOUT = 30000L // 30 seconds
    const val READ_TIMEOUT = 30000L // 30 seconds
    const val WRITE_TIMEOUT = 30000L // 30 seconds

    // Player
    const val PLAYER_BUFFER_SIZE = 64 * 1024 * 1024L // 64MB
    const val PLAYER_MIN_BUFFER = 2000L // 2 seconds
    const val PLAYER_MAX_BUFFER = 30000L // 30 seconds
    const val PLAYER_BUFFER_FOR_PLAYBACK = 2500L // 2.5 seconds

    // Refresh Intervals
    const val REFRESH_INTERVAL_1_HOUR = 60 * 60 * 1000L
    const val REFRESH_INTERVAL_3_HOURS = 3 * 60 * 60 * 1000L
    const val REFRESH_INTERVAL_6_HOURS = 6 * 60 * 60 * 1000L
    const val REFRESH_INTERVAL_12_HOURS = 12 * 60 * 60 * 1000L
    const val REFRESH_INTERVAL_24_HOURS = 24 * 60 * 60 * 1000L
    const val DEFAULT_REFRESH_INTERVAL = REFRESH_INTERVAL_6_HOURS

    // WorkManager
    const val UNIQUE_WORK_NAME = "playlist_refresh_work"
    const val WORK_TAG_REFRESH = "playlist_refresh"
    const val WORK_TAG_AVAILABILITY_CHECK = "availability_check"

    // Notifications
    const val CHANNEL_PLAYING_NOTIFICATION_ID = 1001
    const val REFRESH_NOTIFICATION_ID = 1002

    // Intent Actions
    const val ACTION_PLAY_CHANNEL = "com.tvplayer.app.action.PLAY_CHANNEL"
    const val ACTION_REFRESH_PLAYLIST = "com.tvplayer.app.action.REFRESH_PLAYLIST"
    const val ACTION_CHECK_AVAILABILITY = "com.tvplayer.app.action.CHECK_AVAILABILITY"

    // Intent Extras
    const val EXTRA_CHANNEL_ID = "com.tvplayer.app.extra.CHANNEL_ID"
    const val EXTRA_CHANNEL_NAME = "com.tvplayer.app.extra.CHANNEL_NAME"
    const val EXTRA_CHANNEL_URL = "com.tvplayer.app.extra.CHANNEL_URL"
    const val EXTRA_PLAYLIST_ID = "com.tvplayer.app.extra.PLAYLIST_ID"
    const val EXTRA_FORCE_REFRESH = "com.tvplayer.app.extra.FORCE_REFRESH"

    // EPG (Electronic Program Guide)
    const val EPG_FETCH_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
    const val EPG_DAYS_AHEAD = 7 // Show 7 days ahead

    // Analytics & Monitoring
    const val ANALYTICS_ENABLED = false // Set to false for privacy
    const val ERROR_REPORTING_ENABLED = true

    // Media Types
    object MediaTypes {
        const val HLS = "application/vnd.apple.mpegurl"
        const val MP4 = "video/mp4"
        const val TS = "video/MP2T"
        const val FLV = "video/x-flv"
        const val RTMP = "rtmp"
    }

    // Video Qualities
    object VideoQualities {
        const val AUTO = "auto"
        const val P1080 = "1080p"
        const val P720 = "720p"
        const val P480 = "480p"
        const val P360 = "360p"
    }

    // Player States
    object PlayerStates {
        const val IDLE = 0
        const val BUFFERING = 1
        const val READY = 2
        const val ENDED = 3
    }

    // Default Settings
    object DefaultSettings {
        const val ENABLE_HARDWARE_ACCELERATION = true
        const val ENABLE_SUBTITLES = true
        const val ENABLE_AUTO_ROTATION = false
        const val DEFAULT_VIDEO_QUALITY = VideoQualities.AUTO
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
        const val MIN_CACHE_SIZE = 100 * 1024 * 1024L // 100MB
        const val MAX_CACHE_SIZE = 500 * 1024 * 1024L // 500MB
    }

    // Supported formats for import
    object SupportedFormats {
        const val M3U = ".m3u"
        const val M3U8 = ".m3u8"
        const val JSON = ".json"
        const val TXT = ".txt"
    }

    // Default playlists
    object DefaultPlaylists {
        const val MIGU_VIDEO = "https://raw.githubusercontent.com/develop202/migu_video/refs/heads/main/interface.txt"
        const val LOCAL_DEMO = "demo_channels"
    }
}