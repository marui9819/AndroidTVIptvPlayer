package com.tvplayer.app.repository

import android.util.Log
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.network.NetworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

class PlaylistRepository {

    companion object {
        private const val TAG = "PlaylistRepository"
    }

    suspend fun refreshPlaylist(playlist: Playlist): Result<Unit> {
        return try {
            when (playlist.type) {
                Playlist.PlaylistType.REMOTE_M3U -> {
                    refreshRemoteM3UPlaylist(playlist)
                }
                Playlist.PlaylistType.LOCAL_M3U -> {
                    refreshLocalM3UPlaylist(playlist)
                }
                Playlist.PlaylistType.JSON -> {
                    refreshJsonPlaylist(playlist)
                }
                Playlist.PlaylistType.TEXT_URLS -> {
                    refreshTextUrlsPlaylist(playlist)
                }
                else -> {
                    Result.failure(Exception("Unsupported playlist type: ${playlist.type}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing playlist: ${playlist.name}", e)
            Result.failure(e)
        }
    }

    private suspend fun refreshRemoteM3UPlaylist(playlist: Playlist): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val networkRepository = NetworkRepository(null) // TODO: Pass preferences
                val content = networkRepository.fetchRemotePlaylist(playlist.url)

                if (content.isSuccess) {
                    val channels = parseM3UContent(content.getOrNull() ?: "")
                    updatePlaylistChannels(playlist.id, channels)
                    Log.d(TAG, "Refreshed ${channels.size} channels for playlist: ${playlist.name}")
                    Result.success(Unit)
                } else {
                    Result.failure(content.exceptionOrNull() ?: Exception("Unknown error"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing remote M3U playlist", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun refreshLocalM3UPlaylist(playlist: Playlist): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // For local playlists, we assume the content is already stored in the playlist URL field
                val content = playlist.url
                if (content.isNotEmpty()) {
                    val channels = parseM3UContent(content)
                    updatePlaylistChannels(playlist.id, channels)
                    Log.d(TAG, "Refreshed ${channels.size} channels for local playlist: ${playlist.name}")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Local playlist content is empty"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing local M3U playlist", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun refreshJsonPlaylist(playlist: Playlist): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val networkRepository = NetworkRepository(null) // TODO: Pass preferences
                val content = networkRepository.fetchRemotePlaylist(playlist.url)

                if (content.isSuccess) {
                    val channels = parseJsonContent(content.getOrNull() ?: "")
                    updatePlaylistChannels(playlist.id, channels)
                    Log.d(TAG, "Refreshed ${channels.size} channels for JSON playlist: ${playlist.name}")
                    Result.success(Unit)
                } else {
                    Result.failure(content.exceptionOrNull() ?: Exception("Unknown error"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing JSON playlist", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun refreshTextUrlsPlaylist(playlist: Playlist): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val content = if (playlist.type == Playlist.PlaylistType.TEXT_URLS && playlist.url.startsWith("http")) {
                    val networkRepository = NetworkRepository(null) // TODO: Pass preferences
                    val result = networkRepository.fetchRemotePlaylist(playlist.url)
                    result.getOrNull() ?: ""
                } else {
                    playlist.url
                }

                if (content.isNotEmpty()) {
                    val channels = parseTextUrlsContent(content)
                    updatePlaylistChannels(playlist.id, channels)
                    Log.d(TAG, "Refreshed ${channels.size} channels for text URLs playlist: ${playlist.name}")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Text URLs content is empty"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing text URLs playlist", e)
                Result.failure(e)
            }
        }
    }

    private fun parseM3UContent(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.split("\n")
        var currentChannel: Channel? = null
        var order = 0

        for (line in lines) {
            val trimmedLine = line.trim()

            if (trimmedLine.startsWith("#EXTINF:")) {
                // Parse channel info
                currentChannel = parseExtInfLine(trimmedLine, order)
                order++
            } else if (trimmedLine.startsWith("http") && currentChannel != null) {
                // Parse URL
                currentChannel = currentChannel.copy(url = trimmedLine)
                channels.add(currentChannel)
                currentChannel = null
            }
        }

        return channels
    }

    private fun parseExtInfLine(extInfLine: String, order: Int): Channel {
        val namePattern = Pattern.compile("#EXTINF:.*?,(.+)")
        val nameMatcher = namePattern.matcher(extInfLine)

        val name = if (nameMatcher.find()) {
            nameMatcher.group(1).trim()
        } else {
            "Unknown Channel"
        }

        // Extract other metadata
        var tvgName = ""
        var tvgLogo = ""
        var groupTitle = ""
        var tvgId = ""

        val tvgNamePattern = Pattern.compile("tvg-name=\"([^\"]*)\"")
        val tvgLogoPattern = Pattern.compile("tvg-logo=\"([^\"]*)\"")
        val groupTitlePattern = Pattern.compile("group-title=\"([^\"]*)\"")
        val tvgIdPattern = Pattern.compile("tvg-id=\"([^\"]*)\"")

        val tvgNameMatcher = tvgNamePattern.matcher(extInfLine)
        val tvgLogoMatcher = tvgLogoPattern.matcher(extInfLine)
        val groupTitleMatcher = groupTitlePattern.matcher(extInfLine)
        val tvgIdMatcher = tvgIdPattern.matcher(extInfLine)

        if (tvgNameMatcher.find()) tvgName = tvgNameMatcher.group(1)
        if (tvgLogoMatcher.find()) tvgLogo = tvgLogoMatcher.group(1)
        if (groupTitleMatcher.find()) groupTitle = groupTitleMatcher.group(1)
        if (tvgIdMatcher.find()) tvgId = tvgIdMatcher.group(1)

        return Channel.create(
            name = tvgName.ifEmpty { name },
            url = "", // Will be set in the next iteration
            playlistId = 0, // Will be set later
            logo = tvgLogo.ifEmpty { null },
            group = groupTitle.ifEmpty { null },
            tvgId = tvgId.ifEmpty { null },
            tvgName = tvgName.ifEmpty { name },
            order = order
        )
    }

    private fun parseJsonContent(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val order = 0

        try {
            // Simple JSON parsing - in a real implementation, use a proper JSON library
            val lines = content.split("\n")
            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.contains("\"name\":") && trimmedLine.contains("\"url\":")) {
                    val name = extractJsonValue(trimmedLine, "name")
                    val url = extractJsonValue(trimmedLine, "url")
                    val logo = extractJsonValue(trimmedLine, "logo")
                    val group = extractJsonValue(trimmedLine, "group")

                    if (name.isNotEmpty() && url.isNotEmpty()) {
                        channels.add(
                            Channel.create(
                                name = name,
                                url = url,
                                playlistId = 0,
                                logo = logo.ifEmpty { null },
                                group = group.ifEmpty { null },
                                order = channels.size
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON content", e)
        }

        return channels
    }

    private fun parseTextUrlsContent(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.split("\n")
        val urlPattern = Pattern.compile("(https?://\\S+)")

        for (line in lines) {
            val trimmedLine = line.trim()
            val urlMatcher = urlPattern.matcher(trimmedLine)

            if (urlMatcher.find()) {
                val url = urlMatcher.group(1)
                val name = extractNameFromLine(trimmedLine, url) ?: "Channel ${channels.size + 1}"

                channels.add(
                    Channel.create(
                        name = name,
                        url = url,
                        playlistId = 0,
                        order = channels.size
                    )
                )
            }
        }

        return channels
    }

    private fun extractJsonValue(line: String, key: String): String {
        val pattern = Pattern.compile("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        val matcher = pattern.matcher(line)
        return if (matcher.find()) matcher.group(1) else ""
    }

    private fun extractNameFromLine(line: String, url: String): String? {
        // Try to extract a name before the URL
        val urlIndex = line.indexOf(url)
        return if (urlIndex > 0) {
            line.substring(0, urlIndex).trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }

    private suspend fun updatePlaylistChannels(playlistId: Long, channels: List<Channel>) {
        // This would be implemented to update the database with new channels
        // For now, this is a placeholder implementation
        Log.d(TAG, "Would update ${channels.size} channels for playlist ID: $playlistId")
    }

    fun shouldRefreshPlaylist(playlist: Playlist): Boolean {
        return when {
            !playlist.autoRefresh -> false
            playlist.lastRefreshTime == 0L -> true
            System.currentTimeMillis() - playlist.lastRefreshTime > playlist.refreshInterval -> true
            else -> false
        }
    }

    fun getRefreshIntervalInMinutes(playlist: Playlist): Long {
        return playlist.refreshInterval / (1000 * 60)
    }

    fun formatRefreshTime(timestamp: Long): String {
        return if (timestamp > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        } else {
            "从未刷新"
        }
    }
}