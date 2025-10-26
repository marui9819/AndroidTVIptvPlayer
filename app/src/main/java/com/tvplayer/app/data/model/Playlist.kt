package com.tvplayer.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val url: String? = null,
    val description: String? = null,
    val sourceType: PlaylistSourceType = PlaylistSourceType.REMOTE,
    val isActive: Boolean = true,
    val isDefault: Boolean = false,
    val lastUpdated: Long = 0,
    val refreshInterval: Long = 6 * 60 * 60 * 1000L, // 6 hours
    val autoRefresh: Boolean = true,
    val channelCount: Int = 0,
    val loadError: String? = null,
    val createdTime: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
) {
    fun isLocal(): Boolean {
        return sourceType == PlaylistSourceType.LOCAL
    }

    fun isRemote(): Boolean {
        return sourceType == PlaylistSourceType.REMOTE
    }

    fun shouldRefresh(): Boolean {
        if (!autoRefresh || url == null) return false
        val now = System.currentTimeMillis()
        return (now - lastUpdated) >= refreshInterval
    }

    fun getRefreshTimeString(): String {
        val now = System.currentTimeMillis()
        val diff = now - lastUpdated
        val hours = diff / (1000 * 60 * 60)
        val minutes = (diff % (1000 * 60 * 60)) / (1000 * 60)

        return when {
            hours >= 24 -> "${(hours / 24)}天前"
            hours > 0 -> "${hours}小时${minutes}分钟前"
            minutes > 0 -> "${minutes}分钟前"
            else -> "刚刚"
        }
    }

    fun getLastUpdatedDate(): Date {
        return Date(lastUpdated)
    }

    fun markAsUpdated() = this.copy(
        lastUpdated = System.currentTimeMillis(),
        loadError = null
    )

    fun markAsError(error: String) = this.copy(
        loadError = error,
        lastUpdated = System.currentTimeMillis()
    )

    companion object {
        fun createLocal(name: String, description: String? = null): Playlist {
            return Playlist(
                name = name,
                description = description,
                sourceType = PlaylistSourceType.LOCAL,
                url = null,
                autoRefresh = false
            )
        }

        fun createRemote(name: String, url: String, description: String? = null): Playlist {
            return Playlist(
                name = name,
                url = url,
                description = description,
                sourceType = PlaylistSourceType.REMOTE
            )
        }
    }
}

enum class PlaylistSourceType {
    LOCAL, REMOTE, FILE
}