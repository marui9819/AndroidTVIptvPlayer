package com.tvplayer.app.data.db

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.data.model.SubscriptionEndpoint

@Database(
    entities = [Playlist::class, Channel::class, SubscriptionEndpoint::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun subscriptionEndpointDao(): SubscriptionEndpointDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "iptv_player_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // Migrations for future schema changes
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE playlists ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE channels ADD COLUMN normalizedName TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE channels ADD COLUMN responseTimeMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE channels ADD COLUMN videoWidth INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE channels ADD COLUMN videoHeight INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE channels ADD COLUMN lastCheckedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE channels SET normalizedName = name WHERE normalizedName = ''")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subscription_endpoints (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        name TEXT NOT NULL,
                        url TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        lastTestAt INTEGER NOT NULL DEFAULT 0,
                        lastSuccessAt INTEGER NOT NULL DEFAULT 0,
                        lastError TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE playlists ADD COLUMN autoRefreshEnabled INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_playlist_id ON channels(playlistId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_playlist_order ON channels(playlistId, `order`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_normalized_name ON channels(normalizedName)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_group_name ON channels(`group`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_last_checked ON channels(lastCheckedAt)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_favorite_available ON channels(isFavorite, isAvailable)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channels_url ON channels(url)"
                )

                // v3 rows may have been normalized with the old HD-before-FHD
                // order. Recompute the value from the original channel name so
                // existing FHD/UHD sources can merge correctly after upgrade.
                database.query("SELECT id, name FROM channels").use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) {
                        val values = ContentValues().apply {
                            put("normalizedName", Channel.normalizeName(cursor.getString(nameIndex)))
                        }
                        database.update(
                            "channels",
                            SQLiteDatabase.CONFLICT_ABORT,
                            values,
                            "id = ?",
                            arrayOf(cursor.getLong(idIndex).toString())
                        )
                    }
                }
            }
        }
    }
}
