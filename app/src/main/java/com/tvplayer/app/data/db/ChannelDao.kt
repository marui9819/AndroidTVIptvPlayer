package com.tvplayer.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.tvplayer.app.data.model.Channel

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels WHERE id = :channelId")
    suspend fun getChannelById(channelId: Long): Channel?

    @Query("SELECT * FROM channels WHERE is_favorite = 1 ORDER BY name ASC")
    fun getFavoriteChannels(): LiveData<List<Channel>>

    @Query("SELECT * FROM channels WHERE playlist_id = :playlistId ORDER BY `order` ASC, name ASC")
    fun getChannelsByPlaylist(playlistId: Long): LiveData<List<Channel>>

    @Query("SELECT * FROM channels WHERE playlist_id = :playlistId AND is_available = 1 ORDER BY `order` ASC, name ASC")
    suspend fun getAvailableChannelsByPlaylist(playlistId: Long): List<Channel>

    @Query("SELECT * FROM channels WHERE name LIKE '%' || :searchQuery || '%' OR group_name LIKE '%' || :searchQuery || '%' ORDER BY name ASC")
    fun searchChannels(searchQuery: String): LiveData<List<Channel>>

    @Query("SELECT * FROM channels WHERE playlist_id = :playlistId AND name LIKE '%' || :searchQuery || '%' ORDER BY name ASC")
    fun searchChannelsInPlaylist(playlistId: Long, searchQuery: String): LiveData<List<Channel>>

    @Query("SELECT * FROM channels ORDER BY last_played_time DESC LIMIT 10")
    fun getRecentlyPlayedChannels(): LiveData<List<Channel>>

    @Query("SELECT * FROM channels WHERE is_favorite = 1 ORDER BY last_played_time DESC LIMIT 10")
    fun getRecentlyPlayedFavorites(): LiveData<List<Channel>>

    @Query("SELECT COUNT(*) FROM channels WHERE playlist_id = :playlistId")
    suspend fun getChannelCountByPlaylist(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM channels WHERE is_available = 1 AND playlist_id = :playlistId")
    suspend fun getAvailableChannelCountByPlaylist(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannel(channel: Channel): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<Channel>)

    @Update
    suspend fun updateChannel(channel: Channel): Int

    @Query("UPDATE channels SET is_favorite = :isFavorite WHERE id = :channelId")
    suspend fun updateFavoriteStatus(channelId: Long, isFavorite: Boolean)

    @Query("UPDATE channels SET last_played_position = :position, last_played_time = :timestamp WHERE id = :channelId")
    suspend fun updatePlaybackPosition(channelId: Long, position: Long, timestamp: Long)

    @Query("UPDATE channels SET play_count = play_count + 1 WHERE id = :channelId")
    suspend fun incrementPlayCount(channelId: Long)

    @Query("UPDATE channels SET is_available = :isAvailable, load_error = :error WHERE id = :channelId")
    suspend fun updateChannelAvailability(channelId: Long, isAvailable: Boolean, error: String?)

    @Delete
    suspend fun deleteChannel(channel: Channel): Int

    @Query("DELETE FROM channels WHERE playlist_id = :playlistId")
    suspend fun deleteChannelsByPlaylist(playlistId: Long): Int

    @Query("DELETE FROM channels WHERE is_available = 0 AND last_played_time < :cutoffTime")
    suspend fun deleteOldUnavailableChannels(cutoffTime: Long): Int

    @Query("UPDATE channels SET is_favorite = 0")
    suspend fun clearAllFavorites(): Int

    @Query("SELECT * FROM channels WHERE stream_url LIKE '%.m3u8%' ORDER BY name ASC")
    fun getHlsChannels(): LiveData<List<Channel>>

    @Query("SELECT * FROM channels WHERE stream_url LIKE '%.mp4%' ORDER BY name ASC")
    fun getMp4Channels(): LiveData<List<Channel>>
}