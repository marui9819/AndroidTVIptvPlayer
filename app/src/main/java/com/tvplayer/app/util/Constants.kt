package com.tvplayer.app.util

object Constants {
    // Database
    const val DATABASE_NAME = "tvplayer_database.db"

    // Shared Preferences
    const val PREFS_NAME = "tvplayer_preferences"

    // Network
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; SM-G950F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
    const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val CONNECT_TIMEOUT = 30000L // 30 seconds
    const val READ_TIMEOUT = 30000L // 30 seconds
    const val WRITE_TIMEOUT = 30000L // 30 seconds

    // Refresh Intervals
    const val REFRESH_INTERVAL_5_MINUTES = 5 * 60 * 1000L
    const val REFRESH_INTERVAL_1_HOUR = 60 * 60 * 1000L
    const val REFRESH_INTERVAL_3_HOURS = 3 * 60 * 60 * 1000L
    const val REFRESH_INTERVAL_6_HOURS = 6 * 60 * 60 * 1000L
    const val REFRESH_INTERVAL_12_HOURS = 12 * 60 * 60 * 1000L
    const val REFRESH_INTERVAL_24_HOURS = 24 * 60 * 60 * 1000L
    const val DEFAULT_REFRESH_INTERVAL = REFRESH_INTERVAL_1_HOUR

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
    const val DEFAULT_EPG_REFRESH_INTERVAL = REFRESH_INTERVAL_6_HOURS
    const val DEFAULT_EPG_REFRESH_TIME = "00:00"
    // Full-library checks run on a long cadence.  A shorter, selected-category
    // check is performed on demand in the player UI.
    const val DEFAULT_CHANNEL_PROBE_INTERVAL = 60 * 60 * 1000L
    const val DEFAULT_CHANNEL_PROBE_THREAD_COUNT = 3
    const val DEFAULT_CHANNEL_PROBE_DELAY_MS = 500L
    const val DEFAULT_CHANNEL_PROBE_TIMEOUT_MS = 5_000L
    const val LOGO_CACHE_FILE_NAME = "logo-library-cache.json"

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
        const val DEFAULT_DIAGNOSTICS_SERVER_URL = ""
        const val DEFAULT_REMOTE_SOURCE_SERVER_URL = ""
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

    // Live channel lists are intentionally not bundled. Users add sources they
    // are authorized to access from Subscription Management.
    object DefaultPlaylists {
        const val DEFAULT_URL = ""
    }

    object DefaultEpgSources {
        const val SOURCE_PRIMARY = "https://epg.136605.xyz/3days.xml"
        const val SOURCE_SECONDARY = "https://epg.catvod.com/epg.xml"
        val ALL = listOf(SOURCE_PRIMARY, SOURCE_SECONDARY)
    }

    object DefaultLogoSources {
        const val PRIMARY = "https://gh-proxy.org/https://github.com/iptv-pro/iptv-pro.github.io/blob/main/list.txt"
        const val SECONDARY = "https://gh-proxy.org/https://raw.githubusercontent.com/iptv-pro/iptv-pro.github.io/main/list.txt"
        val ALL = listOf(PRIMARY, SECONDARY)
    }
}
