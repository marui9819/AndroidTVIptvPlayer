package com.tvplayer.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.Locale

@Entity(
    tableName = "channels",
    indices = [
        Index(name = "index_channels_playlist_id", value = ["playlistId"]),
        Index(name = "index_channels_playlist_order", value = ["playlistId", "order"]),
        Index(name = "index_channels_normalized_name", value = ["normalizedName"]),
        Index(name = "index_channels_group_name", value = ["group"]),
        Index(name = "index_channels_last_checked", value = ["lastCheckedAt"]),
        Index(name = "index_channels_favorite_available", value = ["isFavorite", "isAvailable"]),
        Index(name = "index_channels_url", value = ["url"])
    ]
)
@Serializable
data class Channel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val isFavorite: Boolean = false,
    val playlistId: Long = 0,
    val lastPlayed: Long = 0,
    val playCount: Int = 0,
    val order: Int = 0,
    val isAvailable: Boolean = true,
    val availabilityError: String? = null,
    val normalizedName: String = normalizeName(name),
    val responseTimeMs: Long = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val lastCheckedAt: Long = 0
) {
    enum class StreamType {
        HLS,
        DASH,
        RTMP,
        FILE,
        UNKNOWN
    }

    fun getDisplayUrl(): String = url

    fun getStreamType(): StreamType = when {
        url.contains(".m3u8", ignoreCase = true) -> StreamType.HLS
        url.contains(".mpd", ignoreCase = true) -> StreamType.DASH
        url.startsWith("rtmp", ignoreCase = true) -> StreamType.RTMP
        url.startsWith("http", ignoreCase = true) -> StreamType.FILE
        else -> StreamType.UNKNOWN
    }

    companion object {
        fun create(
            name: String,
            url: String,
            playlistId: Long,
            logo: String? = null,
            group: String? = null,
            order: Int = 0
        ): Channel {
            return Channel(
                name = name,
                url = url,
                playlistId = playlistId,
                logo = logo,
                group = group,
                order = order,
                normalizedName = normalizeName(name)
            )
        }

        fun normalizeName(name: String): String {
            return name
                .uppercase(Locale.ROOT)
                .replace("（", "(")
                .replace("）", ")")
                .replace("高清", "")
                .replace("超清", "")
                .replace("标清", "")
                .replace("频道", "")
                .replace("FHD", "")
                .replace("UHD", "")
                .replace("HD", "")
                .replace("4K", "4K")
                .replace(Regex("[\\s\\-_/()\\[\\]【】]+"), "")
                .trim()
        }
    }
}
