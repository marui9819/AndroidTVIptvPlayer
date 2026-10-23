package com.tvplayer.app.data.repository

import androidx.room.withTransaction
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.network.NetworkRepository
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(
    private val networkRepository: NetworkRepository = NetworkRepository(),
    private val database: AppDatabase,
    private val logoRepository: LogoRepository? = null
) {

    suspend fun refreshPlaylist(playlist: Playlist): Result<Int> {
        return try {
            // Fetch playlist content from network
            val result = networkRepository.fetchPlaylist(playlist)
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("Network error"))
            }

            val content = result.getOrNull() ?: ""
            if (content.isBlank()) {
                return Result.failure(Exception("Empty playlist content"))
            }

            // Parse playlist content
            val parsedChannels = networkRepository.parsePlaylistContent(playlist, content)
            // Refreshing a source replaces its rows. Keep user state when the
            // same stream URL is still present, and keep favourites by logical
            // name as a fallback for providers that rewrite stream URLs.
            val previousChannels = database.channelDao()
                .getChannelsByPlaylistList(playlist.id)
            val previousByStreamKey = previousChannels
                .associateBy(::streamKey)
            val previousFavoriteKeys = previousChannels
                .asSequence()
                .filter { it.isFavorite }
                .map { it.normalizedName }
                .toSet()
            val categorizedChannels = parsedChannels.map { channel ->
                val previous = previousByStreamKey[streamKey(channel)]
                channel.copy(
                    isFavorite = channel.isFavorite ||
                        previous?.isFavorite == true ||
                        channel.normalizedName in previousFavoriteKeys,
                    lastPlayed = previous?.lastPlayed ?: channel.lastPlayed,
                    playCount = previous?.playCount ?: channel.playCount,
                    isAvailable = previous?.isAvailable ?: channel.isAvailable,
                    availabilityError = previous?.availabilityError ?: channel.availabilityError,
                    responseTimeMs = previous?.responseTimeMs ?: channel.responseTimeMs,
                    videoWidth = previous?.videoWidth ?: channel.videoWidth,
                    videoHeight = previous?.videoHeight ?: channel.videoHeight,
                    lastCheckedAt = previous?.lastCheckedAt ?: channel.lastCheckedAt
                )
            }
            val channels = logoRepository?.applyLogos(categorizedChannels) ?: categorizedChannels

            // Replace rows and metadata atomically. A process kill or storage
            // error between DELETE and INSERT must not leave a source empty.
            database.withTransaction {
                database.channelDao().deleteChannelsByPlaylist(playlist.id)
                database.channelDao().insertChannels(channels)
                database.playlistDao().updateLastRefreshTime(
                    playlist.id,
                    System.currentTimeMillis()
                )
                database.playlistDao().updateChannelCount(playlist.id, channels.size)
            }

            Result.success(channels.size)
        } catch (e: Exception) {
            // Mark playlist as having error
            database.playlistDao().markPlaylistWithError(
                playlist.id,
                e.message ?: "Unknown error",
                System.currentTimeMillis()
            )
            Result.failure(e)
        }
    }

    suspend fun getChannelsForPlaylist(playlistId: Long): List<Channel> {
        return database.channelDao().getAvailableChannelsByPlaylist(playlistId)
    }

    suspend fun updateChannelFavorite(channelId: Long, isFavorite: Boolean) {
        database.channelDao().updateFavoriteStatus(channelId, isFavorite)
    }

    suspend fun updateChannelPlaybackPosition(channelId: Long, position: Long) {
        database.channelDao().updatePlaybackPosition(channelId, position)
        database.channelDao().incrementPlayCount(channelId)
    }

    suspend fun checkChannelAvailability(channel: Channel): Boolean {
        val isAvailable = networkRepository.checkStreamAvailability(channel)
        database.channelDao().updateChannelAvailability(
            channel.id,
            isAvailable,
            if (!isAvailable) "Stream unavailable" else null
        )
        return isAvailable
    }

    fun getChannelsByPlaylistLiveData(playlistId: Long): Flow<List<Channel>> =
        database.channelDao().getChannelsByPlaylist(playlistId)

    fun getFavoriteChannelsLiveData(): Flow<List<Channel>> =
        database.channelDao().getFavoriteChannels()

    fun getRecentlyPlayedChannelsLiveData(): Flow<List<Channel>> =
        database.channelDao().getRecentlyPlayedChannels()

    fun searchChannels(query: String): Flow<List<Channel>> =
        database.channelDao().searchChannels(query)

    suspend fun createPlaylist(name: String, url: String?, description: String? = null): Long {
        val playlist = if (url != null) {
            Playlist.createRemote(name, url, description)
        } else {
            Playlist.createLocal(name, description)
        }
        return database.playlistDao().insertPlaylist(playlist)
    }

    suspend fun deletePlaylist(playlistId: Long) {
        database.withTransaction {
            database.channelDao().deleteChannelsByPlaylist(playlistId)
            database.playlistDao().deletePlaylistById(playlistId)
        }
    }

    private fun streamKey(channel: Channel): String =
        "${channel.normalizedName}\u0000${channel.url.trim()}"
}
