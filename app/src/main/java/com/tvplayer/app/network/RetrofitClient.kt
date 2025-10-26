package com.tvplayer.app.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.tvplayer.app.util.Constants
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.HEADERS
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", Constants.USER_AGENT)
                .addHeader("Accept", "*/*")
                .build()
            chain.proceed(request)
        }
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("http://dummy.base.url/") // Using dummy base URL for @Url support
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    fun <T> createService(serviceClass: Class<T>): T {
        return retrofit.create(serviceClass)
    }
}

class PlaylistRepository(
    private val networkRepository: NetworkRepository = NetworkRepository(),
    private val database: AppDatabase
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
            val channels = if (playlist.url!!.endsWith(".m3u", true) || playlist.url!!.endsWith(".m3u8", true)) {
                PlaylistParser.parseM3U(content, playlist.id)
            } else {
                PlaylistParser.parseJson(content, playlist.id)
            }

            // Update database
            database.channelDao().deleteChannelsByPlaylist(playlist.id)
            database.channelDao().insertChannels(channels)

            // Update playlist metadata
            database.playlistDao().markPlaylistAsUpdated(playlist.id, System.currentTimeMillis())
            database.playlistDao().updateChannelCount(playlist.id, channels.size)

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
        database.channelDao().updatePlaybackPosition(channelId, position, System.currentTimeMillis())
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

    fun getChannelsByPlaylistLiveData(playlistId: Long) =
        database.channelDao().getChannelsByPlaylist(playlistId)

    fun getFavoriteChannelsLiveData() =
        database.channelDao().getFavoriteChannels()

    fun getRecentlyPlayedChannelsLiveData() =
        database.channelDao().getRecentlyPlayedChannels()

    fun searchChannels(query: String) =
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
        database.channelDao().deleteChannelsByPlaylist(playlistId)
        database.playlistDao().deletePlaylistById(playlistId)
    }
}