package com.tvplayer.app.data.db

import androidx.room.*
import com.tvplayer.app.data.model.Channel
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY name ASC")
    fun getAllChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels ORDER BY playlistId ASC, `order` ASC, name ASC")
    suspend fun getAllChannelsList(): List<Channel>

    @Query("SELECT COUNT(*) FROM channels WHERE lastCheckedAt = 0")
    suspend fun getUncheckedChannelCount(): Int

    @Query("""
        SELECT COUNT(*)
        FROM channels AS c
        INNER JOIN playlists AS p ON c.playlistId = p.id
        WHERE p.isActive = 1 AND c.lastCheckedAt = 0
    """)
    suspend fun getActiveUncheckedChannelCount(): Int

    @Query("""
        SELECT c.*
        FROM channels AS c
        INNER JOIN playlists AS p ON c.playlistId = p.id
        WHERE p.isActive = 1
        ORDER BY CASE WHEN c.lastCheckedAt = 0 THEN 0 ELSE 1 END,
            c.lastCheckedAt ASC, c.playlistId ASC, c.`order` ASC, c.name ASC
    """)
    suspend fun getActiveChannelsForProbe(): List<Channel>

    @Query("""
        SELECT c.*
        FROM channels AS c
        INNER JOIN playlists AS p ON c.playlistId = p.id
        WHERE p.isActive = 1
          AND COALESCE(c.`group`, '分类1') = :groupName
        ORDER BY CASE WHEN c.lastCheckedAt = 0 THEN 0 ELSE 1 END,
            c.lastCheckedAt ASC, c.playlistId ASC, c.`order` ASC, c.name ASC
    """)
    suspend fun getActiveChannelsByGroupForProbe(groupName: String): List<Channel>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND isAvailable = 1 ORDER BY `order` ASC, name ASC")
    fun getChannelsByPlaylist(playlistId: Long): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND isAvailable = 1 ORDER BY name ASC")
    fun getFavoriteChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE isAvailable = 1 AND lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT :limit")
    fun getRecentlyPlayedChannels(limit: Int = 20): Flow<List<Channel>>

    @Query("SELECT c.* FROM channels c INNER JOIN playlists p ON c.playlistId = p.id WHERE p.isActive = 1 AND c.url IN (:urls) ORDER BY c.playlistId, c.`order`")
    fun observeActiveChannelsByUrls(urls: List<String>): Flow<List<Channel>>

    @Query("SELECT c.* FROM channels c INNER JOIN playlists p ON c.playlistId = p.id WHERE p.isActive = 1 AND c.url IN (:urls) ORDER BY c.playlistId, c.`order`")
    suspend fun getActiveChannelsByUrls(urls: List<String>): List<Channel>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND isAvailable = 1 ORDER BY name ASC")
    suspend fun getFavoriteChannelsList(): List<Channel>

    @Query("SELECT * FROM channels WHERE isAvailable = 1 AND lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT :limit")
    suspend fun getRecentlyPlayedChannelsList(limit: Int = 20): List<Channel>

    @Query("SELECT * FROM channels WHERE isAvailable = 1 AND (name LIKE '%' || :query || '%' OR `group` LIKE '%' || :query || '%') ORDER BY name ASC")
    fun searchChannels(query: String): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE isAvailable = 1 AND (name LIKE '%' || :query || '%' OR `group` LIKE '%' || :query || '%') ORDER BY name ASC")
    suspend fun searchChannelsList(query: String): List<Channel>

    @Query("""
        SELECT c.*
        FROM channels AS c
        INNER JOIN playlists AS p ON c.playlistId = p.id
        WHERE p.isActive = 1
          AND c.isAvailable = 1
          AND (c.name LIKE '%' || :query || '%' OR c.`group` LIKE '%' || :query || '%')
        ORDER BY c.name ASC
    """)
    suspend fun searchActiveChannelsList(query: String): List<Channel>

    @Query("SELECT * FROM channels WHERE `group` = :groupName AND isAvailable = 1 ORDER BY name ASC")
    suspend fun getChannelsByGroup(groupName: String): List<Channel>

    @Query("""
        SELECT c.*
        FROM channels AS c
        INNER JOIN playlists AS p ON c.playlistId = p.id
        WHERE p.isActive = 1
          AND c.isAvailable = 1
          AND COALESCE(c.`group`, '分类1') = :groupName
        ORDER BY c.name ASC
    """)
    suspend fun getActiveChannelsByGroup(groupName: String): List<Channel>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND isAvailable = 1 AND (name LIKE '%' || :query || '%' OR `group` LIKE '%' || :query || '%') ORDER BY name ASC")
    suspend fun searchFavoriteChannels(query: String): List<Channel>

    @Query("SELECT * FROM channels WHERE isAvailable = 1 AND (name LIKE '%' || :query || '%' OR `group` LIKE '%' || :query || '%') ORDER BY name ASC")
    suspend fun searchAvailableChannels(query: String): List<Channel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<Channel>)

    @Update
    suspend fun update(channel: Channel)

    @Update
    suspend fun updateAll(channels: List<Channel>)

    @Delete
    suspend fun delete(channel: Channel)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannelsByPlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<Channel>)

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun getChannelCountByPlaylist(playlistId: Long): Int

    @Query("SELECT * FROM channels WHERE id = :channelId")
    suspend fun getChannelById(channelId: Long): Channel?

    @Query("SELECT * FROM channels WHERE normalizedName = :normalizedName ORDER BY playlistId ASC, `order` ASC, name ASC")
    suspend fun getChannelsByNormalizedName(normalizedName: String): List<Channel>

    @Query("SELECT * FROM channels WHERE url = :url AND isAvailable = 1 ORDER BY lastPlayed DESC, `order` ASC LIMIT 1")
    suspend fun getAvailableChannelByUrl(url: String): Channel?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND isAvailable = 1 ORDER BY `order` ASC, name ASC")
    suspend fun getAvailableChannelsByPlaylist(playlistId: Long): List<Channel>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY `order` ASC, name ASC")
    suspend fun getChannelsByPlaylistList(playlistId: Long): List<Channel>

    @Query("SELECT * FROM channels WHERE playlistId IN (:playlistIds) ORDER BY playlistId ASC, `order` ASC, name ASC")
    suspend fun getChannelsByPlaylistIds(playlistIds: List<Long>): List<Channel>

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE id = :channelId")
    suspend fun updateFavoriteStatus(channelId: Long, isFavorite: Boolean)

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE id IN (:channelIds)")
    suspend fun updateFavoriteStatusForChannels(channelIds: List<Long>, isFavorite: Boolean)

    @Query("UPDATE channels SET logo = :logoUrl WHERE id = :channelId")
    suspend fun updateChannelLogo(channelId: Long, logoUrl: String?)

    @Query("UPDATE channels SET lastPlayed = :timestamp, playCount = playCount + 1 WHERE id = :channelId")
    suspend fun updatePlaybackPosition(channelId: Long, timestamp: Long)

    @Query("UPDATE channels SET isAvailable = :isAvailable, availabilityError = :error WHERE id = :channelId")
    suspend fun updateChannelAvailability(channelId: Long, isAvailable: Boolean, error: String?)

    @Query("""
        UPDATE channels
        SET isAvailable = :isAvailable,
            availabilityError = :error,
            responseTimeMs = :responseTimeMs,
            videoWidth = :videoWidth,
            videoHeight = :videoHeight,
            lastCheckedAt = :checkedAt
        WHERE id = :channelId
    """)
    suspend fun updateProbeResult(
        channelId: Long,
        isAvailable: Boolean,
        error: String?,
        responseTimeMs: Long,
        videoWidth: Int,
        videoHeight: Int,
        checkedAt: Long
    )

    @Query("UPDATE channels SET playCount = playCount + 1 WHERE id = :channelId")
    suspend fun incrementPlayCount(channelId: Long)
}
