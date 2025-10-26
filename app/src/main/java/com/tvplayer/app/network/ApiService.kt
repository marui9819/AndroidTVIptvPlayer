package com.tvplayer.app.network

import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import java.util.*

interface ApiService {

    @GET
    suspend fun getPlaylist(@Url url: String): Response<ResponseBody>

    @Streaming
    @GET
    suspend fun getStream(@Url url: String): Response<ResponseBody>

    @HEAD
    suspend fun checkUrlAvailability(@Url url: String): Response<ResponseBody>

    @GET
    suspend fun testProxyUrl(
        @Url url: String,
        @Header("User-Agent") userAgent: String = Constants.USER_AGENT
    ): Response<ResponseBody>
}

object PlaylistParser {

    suspend fun parseM3U(content: String, playlistId: Long): List<Channel> = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        var currentName: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var order = 0

        content.split("\n").forEach { line ->
            val trimmedLine = line.trim()

            when {
                trimmedLine.startsWith("#EXTINF:") -> {
                    val info = trimmedLine.substring(8).split(",")
                    currentName = if (info.size > 1) info.last().trim() else info.firstOrNull()?.trim()

                    // Extract tvg-logo if present
                    val logoMatch = Regex("tvg-logo=\"([^\"]+)\"").find(trimmedLine)
                    currentLogo = logoMatch?.groups?.get(1)?.value

                    // Extract group-title if present
                    val groupMatch = Regex("group-title=\"([^\"]+)\"").find(trimmedLine)
                    currentGroup = groupMatch?.groups?.get(1)?.value
                }

                trimmedLine.startsWith("http://") || trimmedLine.startsWith("https://") || trimmedLine.startsWith("rtmp://") -> {
                    if (currentName != null) {
                        channels.add(Channel.create(currentName!!, trimmedLine, playlistId).copy(
                            logo = currentLogo,
                            group = currentGroup,
                            order = order++
                        ))
                    }
                    currentName = null
                    currentLogo = null
                    currentGroup = null
                }

                trimmedLine.isBlank() || trimmedLine.startsWith("#EXTM3U") -> {
                    // Skip empty lines and playlist header
                }
            }
        }

        return@withContext channels
    }

    suspend fun parseJson(content: String, playlistId: Long): List<Channel> = withContext(Dispatchers.IO) {
        return@withContext emptyList() // JSON parsing implementation would go here
    }

    suspend fun getStreamHeaders(url: String): Map<String, String> = withContext(Dispatchers.IO) {
        return@withContext mapOf(
            "User-Agent" to Constants.USER_AGENT,
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "Range" to "bytes=0-",
            "Referer" to when {
                url.contains("migu") -> "http://miguvideo.com"
                url.contains("cctv") -> "http://cctv.com"
                else -> url.substringBeforeLast("/")
            }
        )
    }
}

class NetworkRepository {
    private val apiService = RetrofitClient.retrofit.create(ApiService::class)

    suspend fun fetchPlaylist(playlist: Playlist): Result<String> = try {
        val response = apiService.getPlaylist(playlist.url!!)
        if (response.isSuccessful) {
            val content = response.body()?.string() ?: ""
            Result.success(content)
        } else {
            Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun checkStreamAvailability(channel: Channel): Boolean = try {
        val response = apiService.checkUrlAvailability(channel.url)
        response.isSuccessful
    } catch (e: Exception) {
        false
    }

    suspend fun getStreamHeaders(channel: Channel): Map<String, String> {
        return PlaylistParser.getStreamHeaders(channel.url)
    }
}