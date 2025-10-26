package com.tvplayer.app.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.util.Constants

@Database(
    entities = [Channel::class, Playlist::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    Constants.DATABASE_NAME
                )
                .fallbackToDestructiveMigration()
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Initialize default playlists if needed
                        initializeDefaultPlaylists()
                    }
                })
                .build()
                INSTANCE = instance
                instance
            }
        }

        private fun initializeDefaultPlaylists() {
            // This would be called when the database is first created
            // You can add default playlist initialization logic here
        }
    }
}