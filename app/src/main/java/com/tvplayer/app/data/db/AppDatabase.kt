package com.tvplayer.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist

/**
 * Application database configuration
 * Handles channel and playlist data persistence
 */
@Database(
    entities = [Channel::class, Playlist::class],
    version = 1,
    exportSchema = false   // ✅ 关键修复行
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun playlistDao(): PlaylistDao
}