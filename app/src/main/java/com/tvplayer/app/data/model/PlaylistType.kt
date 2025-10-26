package com.tvplayer.app.data.model

/**
 * Enumeration of different playlist types supported by the IPTV player.
 * This enum defines the various formats and sources for playlist files.
 */
enum class PlaylistType {
    /** M3U playlist format - standard IPTV playlist format */
    M3U,

    /** JSON playlist format - structured data format */
    JSON,

    /** Text file with URL lines - simple list of streaming URLs */
    TEXT_URLS,

    /** Remote M3U playlist - M3U files from remote servers */
    REMOTE_M3U,

    /** Local M3U playlist - M3U files stored locally */
    LOCAL_M3U
}