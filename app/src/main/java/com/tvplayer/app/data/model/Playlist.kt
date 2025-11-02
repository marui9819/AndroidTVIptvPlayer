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
    val type: String = "m3u", // m3u, json, text
    val lastUpdated: Long = 0,
    val isDefault: Boolean = false,
    val channelCount: Int = 0,
    val lastError: String? = null,
    val lastErrorTime: Long = 0
) {
    companion object {
        fun createRemote(name: String, url: String, description: String? = null): Playlist {
            return Playlist(
                name = name,
                url = url,
                type = "m3u"
            )
        }

        fun createLocal(name: String, description: String? = null): Playlist {
            return Playlist(
                name = name,
                url = null,
                type = "local"
            )
        }
    }
}