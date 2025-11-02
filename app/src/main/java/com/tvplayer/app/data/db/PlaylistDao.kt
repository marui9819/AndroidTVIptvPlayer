package com.tvplayer.app.data.db

import androidx.room.*
import com.tvplayer.app.data.model.Playlist
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE isDefault = 1")
    fun getDefaultPlaylist(): Flow<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(playlists: List<Playlist>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Delete
    suspend fun delete(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deleteById(playlistId: Long)

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: Long): Playlist?

    @Query("UPDATE playlists SET isDefault = 0 WHERE id != :playlistId")
    suspend fun setAsDefault(playlistId: Long)

    @Query("UPDATE playlists SET isDefault = 1 WHERE id = :playlistId")
    suspend fun updateDefaultStatus(playlistId: Long)

    @Query("SELECT COUNT(*) FROM playlists")
    fun getPlaylistCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Query("UPDATE playlists SET lastUpdated = :timestamp WHERE id = :playlistId")
    suspend fun markPlaylistAsUpdated(playlistId: Long, timestamp: Long)

    @Query("UPDATE playlists SET channelCount = :count WHERE id = :playlistId")
    suspend fun updateChannelCount(playlistId: Long, count: Int)

    @Query("UPDATE playlists SET lastError = :error, lastErrorTime = :timestamp WHERE id = :playlistId")
    suspend fun markPlaylistWithError(playlistId: Long, error: String, timestamp: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long)
}