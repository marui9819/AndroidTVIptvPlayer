package com.tvplayer.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "channels")
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
    val availabilityError: String? = null
) {
    companion object {
        fun create(name: String, url: String, playlistId: Long): Channel {
            return Channel(
                name = name,
                url = url,
                playlistId = playlistId
            )
        }
    }
}