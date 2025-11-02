package com.tvplayer.app.data.db

import androidx.room.*
import com.tvplayer.app.data.model.Channel
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY name ASC")
    fun getAllChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY name ASC")
    fun getChannelsByPlaylist(playlistId: Long): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels ORDER BY lastPlayed DESC LIMIT 10")
    fun getRecentlyPlayedChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' OR group LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchChannels(query: String): Flow<List<Channel>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<Channel>)

    @Update
    suspend fun update(channel: Channel)

    @Delete
    suspend fun delete(channel: Channel)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannelsByPlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<Channel>)

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    fun getChannelCountByPlaylist(playlistId: Long): Int

    @Query("SELECT * FROM channels WHERE id = :channelId")
    suspend fun getChannelById(channelId: Long): Channel?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY `order` ASC, name ASC")
    fun getAvailableChannelsByPlaylist(playlistId: Long): List<Channel>

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE id = :channelId")
    suspend fun updateFavoriteStatus(channelId: Long, isFavorite: Boolean)

    @Query("UPDATE channels SET lastPlayed = :timestamp, playCount = playCount + 1 WHERE id = :channelId")
    suspend fun updatePlaybackPosition(channelId: Long, timestamp: Long)

    @Query("UPDATE channels SET isAvailable = :isAvailable, availabilityError = :error WHERE id = :channelId")
    suspend fun updateChannelAvailability(channelId: Long, isAvailable: Boolean, error: String?)

    @Query("UPDATE channels SET playCount = playCount + 1 WHERE id = :channelId")
    suspend fun incrementPlayCount(channelId: Long)
}