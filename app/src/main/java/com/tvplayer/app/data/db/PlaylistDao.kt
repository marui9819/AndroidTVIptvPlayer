package com.tvplayer.app.data.db

import androidx.room.*
import com.tvplayer.app.data.model.Playlist
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllPlaylistsList(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE isDefault = 1")
    fun getDefaultPlaylist(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultPlaylistItem(): Playlist?

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun getActivePlaylistsList(): List<Playlist>

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

    @Query("SELECT * FROM playlists WHERE url = :url LIMIT 1")
    suspend fun getPlaylistByUrl(url: String): Playlist?

    @Query("UPDATE playlists SET isDefault = CASE WHEN id = :playlistId THEN 1 ELSE 0 END")
    suspend fun setAsDefault(playlistId: Long)

    @Query("UPDATE playlists SET isDefault = 1 WHERE id = :playlistId")
    suspend fun updateDefaultStatus(playlistId: Long)

    @Query("SELECT COUNT(*) FROM playlists")
    fun getPlaylistCount(): Int

    @Query("SELECT COUNT(*) FROM playlists WHERE isActive = 1")
    suspend fun getActivePlaylistCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Query("UPDATE playlists SET lastUpdated = :timestamp WHERE id = :playlistId")
    suspend fun markPlaylistAsUpdated(playlistId: Long, timestamp: Long)

    @Query("UPDATE playlists SET channelCount = :count WHERE id = :playlistId")
    suspend fun updateChannelCount(playlistId: Long, count: Int)

    @Query("UPDATE playlists SET lastError = :error, lastErrorTime = :timestamp WHERE id = :playlistId")
    suspend fun markPlaylistWithError(playlistId: Long, error: String, timestamp: Long)

    @Query("UPDATE playlists SET isDefault = 0")
    suspend fun clearDefaultPlaylist()

    suspend fun setAsDefaultPlaylist(playlistId: Long) {
        setAsDefault(playlistId)
    }

    @Query("SELECT * FROM playlists WHERE isActive = 1 AND autoRefreshEnabled = 1 AND (lastUpdated = 0 OR lastUpdated < :threshold)")
    suspend fun getPlaylistsNeedingRefresh(threshold: Long): List<Playlist>

    @Query("UPDATE playlists SET lastUpdated = :timestamp, lastError = NULL WHERE id = :playlistId")
    suspend fun updateLastRefreshTime(playlistId: Long, timestamp: Long)

    @Query("UPDATE playlists SET lastError = :error, lastErrorTime = :timestamp WHERE id = :playlistId")
    suspend fun updatePlaylistError(playlistId: Long, error: String?, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long)
}
