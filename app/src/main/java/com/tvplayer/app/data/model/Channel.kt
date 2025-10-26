package com.tvplayer.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @SerializedName("name")
    val name: String,

    @SerializedName("url")
    val url: String,

    @SerializedName("logo")
    val logo: String? = null,

    @SerializedName("group")
    val group: String? = null,

    @SerializedName("epg")
    val epg: String? = null,

    val playlistId: Long,
    val order: Int = 0,
    val isFavorite: Boolean = false,
    val lastPlayedPosition: Long = 0,
    val isAvailable: Boolean = true,
    val loadError: String? = null,
    val lastPlayedTime: Long = 0,
    val playCount: Int = 0
) {
    fun getDisplayUrl(): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "http://$url"
        }
    }

    fun getStreamType(): StreamType {
        return when {
            url.contains(".m3u8", ignoreCase = true) -> StreamType.HLS
            url.contains(".mp4", ignoreCase = true) -> StreamType.MP4
            url.contains(".ts", ignoreCase = true) -> StreamType.TS
            url.contains(".flv", ignoreCase = true) -> StreamType.FLV
            url.startsWith("rtmp://") -> StreamType.RTMP
            else -> StreamType.UNKNOWN
        }
    }

    fun hasValidUrl(): Boolean {
        return url.isNotBlank() &&
               (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("rtmp://"))
    }

    fun increasePlayCount() = this.copy(playCount = playCount + 1)
    fun markAsFavorite() = this.copy(isFavorite = true)
    fun unmarkAsFavorite() = this.copy(isFavorite = false)
    fun updatePosition(position: Long) = this.copy(lastPlayedPosition = position)
    fun markAsUnAvailable(error: String?) = this.copy(isAvailable = false, loadError = error)
    fun markAsAvailable() = this.copy(isAvailable = true, loadError = null)

    companion object {
        fun create(name: String, url: String, playlistId: Long): Channel {
            return Channel(
                name = name.takeIf { it.isNotBlank() } ?: "Unknown Channel",
                url = url,
                playlistId = playlistId
            )
        }
    }
}

enum class StreamType {
    HLS, MP4, TS, FLV, RTMP, UNKNOWN
}