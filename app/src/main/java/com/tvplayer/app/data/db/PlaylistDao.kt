package com.tvplayer.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.tvplayer.app.data.model.Playlist

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY isDefault DESC, sortOrder ASC, name ASC")
    fun getAllPlaylists(): LiveData<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY isDefault DESC, sortOrder ASC, name ASC")
    fun getActivePlaylists(): LiveData<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultPlaylist(): Playlist?

    @Query("SELECT * FROM playlists WHERE sourceType = 'REMOTE' AND autoRefresh = 1")
    fun getAutoRefreshPlaylists(): LiveData<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE shouldRefresh() AND autoRefresh = 1")
    suspend fun getPlaylistsNeedingRefresh(): List<Playlist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<Playlist>)

    @Update
    suspend fun updatePlaylist(playlist: Playlist): Int

    @Query("UPDATE playlists SET name = :name, description = :description, url = :url, refreshInterval = :interval, autoRefresh = :autoRefresh WHERE id = :playlistId")
    suspend fun updatePlaylistDetails(
        playlistId: Long,
        name: String,
        description: String?,
        url: String?,
        interval: Long,
        autoRefresh: Boolean
    ): Int

    @Query("UPDATE playlists SET isActive = :isActive WHERE id = :playlistId")
    suspend fun updatePlaylistActiveStatus(playlistId: Long, isActive: Boolean): Int

    @Query("UPDATE playlists SET isDefault = 0")
    suspend fun clearDefaultPlaylist(): Int

    @Query("UPDATE playlists SET isDefault = 1 WHERE id = :playlistId")
    suspend fun setAsDefaultPlaylist(playlistId: Long): Int

    @Query("UPDATE playlists SET channelCount = :count WHERE id = :playlistId")
    suspend fun updateChannelCount(playlistId: Long, count: Int): Int

    @Query("UPDATE playlists SET lastUpdated = :timestamp, loadError = NULL WHERE id = :playlistId")
    suspend fun markPlaylistAsUpdated(playlistId: Long, timestamp: Long): Int

    @Query("UPDATE playlists SET loadError = :error, lastUpdated = :timestamp WHERE id = :playlistId")
    suspend fun markPlaylistWithError(playlistId: Long, error: String, timestamp: Long): Int

    @Query("UPDATE playlists SET sortOrder = :sortOrder WHERE id = :playlistId")
    suspend fun updatePlaylistSortOrder(playlistId: Long, sortOrder: Int): Int

    @Delete
    suspend fun deletePlaylist(playlist: Playlist): Int

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long): Int

    @Query("DELETE FROM playlists WHERE isActive = 0 AND channelCount = 0")
    suspend fun deleteEmptyInactivePlaylists(): Int

    @Query("SELECT COUNT(*) FROM playlists WHERE isActive = 1")
    suspend fun getActivePlaylistCount(): Int

    @Query("SELECT COUNT(*) FROM playlists WHERE isDefault = 1")
    suspend fun getDefaultPlaylistCount(): Int

    @Query("SELECT COUNT(*) FROM playlists WHERE sourceType = 'REMOTE'")
    suspend fun getRemotePlaylistCount(): Int

    @Query("SELECT * FROM playlists WHERE name LIKE '%' || :searchQuery || '%' OR description LIKE '%' || :searchQuery || '%' ORDER BY name ASC")
    fun searchPlaylists(searchQuery: String): LiveData<List<Playlist>>

    @Query("UPDATE playlists SET sortOrder = sortOrder + 1 WHERE sortOrder >= :startSortOrder AND id != :excludeId")
    suspend fun incrementSortOrder(startSortOrder: Int, excludeId: Long): Int

    @Query("UPDATE playlists SET sortOrder = sortOrder - 1 WHERE sortOrder > :startSortOrder AND id != :excludeId")
    suspend fun decrementSortOrder(startSortOrder: Int, excludeId: Long): Int
}