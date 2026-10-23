package com.tvplayer.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "playlists")
@Serializable
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String? = null,
    val description: String? = null,
    val type: String = "m3u", // m3u, json, text
    val lastUpdated: Long = 0,
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
    val autoRefreshEnabled: Boolean = true,
    val sortOrder: Int = 0,
    val channelCount: Int = 0,
    val lastError: String? = null,
    val lastErrorTime: Long = 0
) {
    fun isLocal(): Boolean = type == "local"

    fun shouldRefresh(now: Long = System.currentTimeMillis(), intervalMs: Long = 0L): Boolean {
        if (!isActive || isLocal() || !autoRefreshEnabled) return false
        return intervalMs <= 0L || lastUpdated <= 0L || now - lastUpdated >= intervalMs
    }

    fun getRefreshTimeString(): String {
        if (lastUpdated <= 0L) return "未刷新"
        val diff = System.currentTimeMillis() - lastUpdated
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            else -> "${days}天前"
        }
    }

    companion object {
        fun createRemote(name: String, url: String, description: String? = null): Playlist {
            return Playlist(
                name = name,
                url = url,
                description = description,
                type = "m3u"
            )
        }

        fun createLocal(name: String, description: String? = null): Playlist {
            return Playlist(
                name = name,
                description = description,
                url = null,
                type = "local"
            )
        }

        fun create(name: String, url: String?, type: String, description: String? = null): Playlist {
            return Playlist(
                name = name,
                url = url,
                type = type,
                description = description
            )
        }
    }
}
