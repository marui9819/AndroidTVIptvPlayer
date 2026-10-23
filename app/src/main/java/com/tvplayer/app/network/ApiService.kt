package com.tvplayer.app.network

import android.net.Uri
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.PreferencesHelper
import com.google.android.exoplayer2.util.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import java.util.*
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okio.BufferedSource

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

    private val ignoredChannelNamePrefixes = listOf(
        "更新日期",
        "更新时间",
        "版本",
        "说明"
    )
    private val logoAttributeRegex = Regex("tvg-logo\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
    private val groupAttributeRegex = Regex("group-title\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
    private val extGroupPrefix = "#EXTGRP:"

    suspend fun parseM3U(content: String, playlistId: Long): List<Channel> = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        var currentName: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var pendingExtGroup: String? = null
        var order = 0

        content.lineSequence().forEach { line ->
            val trimmedLine = line.trim()

            when {
                trimmedLine.startsWith("#EXTINF:") -> {
                    currentName = trimmedLine.substring(8).substringAfterLast(",").trim()

                    // Extract tvg-logo if present
                    val logoMatch = logoAttributeRegex.find(trimmedLine)
                    currentLogo = logoMatch?.groups?.get(1)?.value

                    // Extract group-title if present
                    val groupMatch = groupAttributeRegex.find(trimmedLine)
                    currentGroup = groupMatch?.groups?.get(1)?.value?.cleanGroupName() ?: pendingExtGroup
                    pendingExtGroup = null
                }

                // Many IPTV lists put the category on its own line between
                // #EXTINF and the stream URL.  The old parser ignored it,
                // which made an otherwise well-organised source appear as
                // one large "未分组" list on TV.
                trimmedLine.startsWith(extGroupPrefix, ignoreCase = true) -> {
                    val extGroup = trimmedLine.substringAfter(":").cleanGroupName() ?: return@forEach
                    if (currentName == null) {
                        // Some generators put #EXTGRP immediately before
                        // #EXTINF; hold it until the channel record begins.
                        pendingExtGroup = extGroup
                    } else {
                        currentGroup = extGroup
                    }
                }

                trimmedLine.startsWith("http://") || trimmedLine.startsWith("https://") || trimmedLine.startsWith("rtmp://") -> {
                    val name = currentName
                    if (!name.isNullOrBlank() && shouldIncludeChannel(name)) {
                        channels.add(Channel.create(name, trimmedLine, playlistId).copy(
                            logo = currentLogo,
                            // A category must always exist for D-pad browsing.
                            // Prefer source metadata, then a safe channel-name
                            // inference, and finally a deterministic label.
                            group = currentGroup ?: inferGroupFromChannelName(name) ?: "分类1",
                            order = order++
                        ))
                    }
                    currentName = null
                    currentLogo = null
                    currentGroup = null
                    pendingExtGroup = null
                }

                trimmedLine.isBlank() || trimmedLine.startsWith("#EXTM3U") -> {
                    // Skip empty lines and playlist header
                }
            }
        }

        return@withContext channels
    }

    private fun String.cleanGroupName(): String? = trim()
        .replace(Regex("\\s+"), " ")
        .takeIf { it.isNotBlank() }

    /**
     * Useful fallback for the large number of public M3U lists that omit both
     * group-title and #EXTGRP.  It intentionally stays conservative: unknown
     * channels go to "分类1" rather than being incorrectly assigned.
     */
    private fun inferGroupFromChannelName(name: String): String? {
        val normalized = name.uppercase(Locale.ROOT)
        return when {
            normalized.contains("CCTV") || normalized.contains("央视频") -> "央视频道"
            normalized.contains("卫视") -> "卫视频道"
            normalized.contains("香港") || normalized.contains("澳门") ||
                normalized.contains("台湾") || normalized.contains("凤凰") -> "港澳台"
            normalized.contains("体育") || normalized.contains("足球") ||
                normalized.contains("篮球") || normalized.contains("NBA") ||
                normalized.contains("CSPN") -> "体育频道"
            normalized.contains("电影") || normalized.contains("影院") || normalized.contains("MOVIE") -> "电影"
            normalized.contains("剧") || normalized.contains("电视剧") || normalized.contains("连续剧") -> "剧集"
            normalized.contains("少儿") || normalized.contains("卡通") || normalized.contains("动漫") -> "少儿"
            normalized.contains("新闻") || normalized.contains("财经") || normalized.contains("资讯") -> "新闻资讯"
            normalized.contains("音乐") || normalized.contains("MTV") -> "音乐"
            normalized.contains("广播") || normalized.contains("RADIO") || normalized.contains("FM") -> "广播"
            else -> null
        }
    }

    private fun shouldIncludeChannel(name: String): Boolean {
        val normalizedName = name.trim()
        return normalizedName.isNotBlank() &&
            ignoredChannelNamePrefixes.none { normalizedName.startsWith(it) }
    }

    suspend fun parseJson(content: String, playlistId: Long): List<Channel> = withContext(Dispatchers.IO) {
        return@withContext emptyList() // JSON parsing implementation would go here
    }

    fun looksLikeM3u(content: String): Boolean {
        val normalized = content.trim()
        if (normalized.isBlank()) return false
        return normalized.startsWith("#EXTM3U", ignoreCase = true) ||
            normalized.contains("#EXTINF:", ignoreCase = true) ||
            normalized.lineSequence().any { line ->
                val trimmed = line.trim()
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) ||
                    trimmed.startsWith("rtmp://", ignoreCase = true)
            }
    }

    fun shouldParseAsM3u(url: String?, content: String): Boolean {
        val normalizedUrl = url?.substringBefore("?")?.lowercase(Locale.ROOT).orEmpty()
        return normalizedUrl.endsWith(".m3u") ||
            normalizedUrl.endsWith(".m3u8") ||
            normalizedUrl.endsWith(".txt") ||
            looksLikeM3u(content)
    }

    suspend fun getStreamHeaders(url: String): Map<String, String> = withContext(Dispatchers.IO) {
        if (HuyaStreamResolver.supports(url)) {
            return@withContext HuyaStreamResolver.huyaHeaders()
        }
        if (DouyuStreamResolver.supports(url)) {
            return@withContext DouyuStreamResolver.douyuHeaders()
        }
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
    private val apiService = RetrofitClient.retrofit.create(ApiService::class.java)
    private val probeClients = ConcurrentHashMap<Long, OkHttpClient>()

    data class ProbeResult(
        val isAvailable: Boolean,
        val responseTimeMs: Long,
        val videoWidth: Int,
        val videoHeight: Int,
        val error: String? = null
    )

    suspend fun fetchPlaylist(playlist: Playlist): Result<String> = try {
        fetchUrlContent(playlist.url!!)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun parsePlaylistContent(playlist: Playlist, content: String): List<Channel> {
        return if (PlaylistParser.shouldParseAsM3u(playlist.url, content)) {
            PlaylistParser.parseM3U(content, playlist.id)
        } else {
            PlaylistParser.parseJson(content, playlist.id)
        }
    }

    suspend fun fetchUrlContent(url: String): Result<String> = try {
        val response = apiService.getPlaylist(url)
        if (response.isSuccessful) {
            Result.success(response.body()?.string() ?: "")
        } else {
            Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun checkStreamAvailability(channel: Channel): Boolean = try {
        val playbackInfo = HuyaStreamResolver.resolve(channel.url)
            ?: DouyuStreamResolver.takeIf { DouyuStreamResolver.supports(channel.url) }?.resolve(channel.url)
        val response = apiService.checkUrlAvailability(playbackInfo?.url ?: channel.url)
        response.isSuccessful
    } catch (e: Exception) {
        false
    }

    suspend fun getStreamHeaders(channel: Channel): Map<String, String> {
        return PlaylistParser.getStreamHeaders(channel.url)
    }

    suspend fun probeStream(url: String, timeoutMs: Long): ProbeResult = withContext(Dispatchers.IO) {
        val playbackInfo = HuyaStreamResolver.resolve(url)
            ?: DouyuStreamResolver.takeIf { DouyuStreamResolver.supports(url) }?.resolve(url)
            ?: PlaybackStreamInfo(url = url, headers = PlaylistParser.getStreamHeaders(url))
        val primaryResult = probePlaybackInfo(playbackInfo, timeoutMs)
        if (primaryResult.isAvailable || !HuyaStreamResolver.supports(url)) {
            return@withContext primaryResult
        }

        // A Huya room can expose a valid FLV while its HLS manifest is
        // rejected by the CDN for this client/network.  Treat that as a
        // format failure, not a dead channel: retry the same room with a
        // freshly signed HTTPS FLV before marking it unavailable.
        val flvPlaybackInfo = HuyaStreamResolver.resolve(
            url = url,
            schemeOverride = PreferencesHelper.HUYA_SCHEME_SINGLE_FLV
        )
        if (flvPlaybackInfo != null && flvPlaybackInfo.url != playbackInfo.url) {
            val flvResult = probePlaybackInfo(flvPlaybackInfo, timeoutMs)
            if (flvResult.isAvailable) return@withContext flvResult
            val combinedError = listOfNotNull(primaryResult.error, "FLV回退：${flvResult.error ?: "检测失败"}")
                .joinToString("；")
            return@withContext primaryResult.copy(error = combinedError)
        }
        primaryResult
    }

    private fun probePlaybackInfo(playbackInfo: PlaybackStreamInfo, timeoutMs: Long): ProbeResult {
        val scheme = Uri.parse(playbackInfo.url).scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return ProbeResult(
                isAvailable = false,
                responseTimeMs = 0L,
                videoWidth = 0,
                videoHeight = 0,
                error = "不支持探测的协议: ${scheme ?: "unknown"}"
            )
        }
        val client = probeClient(timeoutMs)

        val request = Request.Builder()
            .url(playbackInfo.url)
            .get()
            .build()
            .newBuilder()
            .apply {
                playbackInfo.headers.forEach { (key, value) -> header(key, value) }
            }
            .build()

        val start = System.currentTimeMillis()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val responseTimeMs = System.currentTimeMillis() - start
                if (!response.isSuccessful) {
                    ProbeResult(
                        isAvailable = false,
                        responseTimeMs = responseTimeMs,
                        videoWidth = 0,
                        videoHeight = 0,
                        error = "HTTP ${response.code}"
                    )
                } else {
                    val sample = readProbeSample(response.body?.source(), playbackInfo.mimeType)
                    val resolution = if (playbackInfo.mimeType == MimeTypes.APPLICATION_M3U8) {
                        extractResolution(sample)
                    } else {
                        0 to 0
                    }
                    ProbeResult(
                        isAvailable = true,
                        responseTimeMs = responseTimeMs,
                        videoWidth = resolution.first,
                        videoHeight = resolution.second
                    )
                }
            }
        }.getOrElse {
            ProbeResult(
                isAvailable = false,
                responseTimeMs = System.currentTimeMillis() - start,
                videoWidth = 0,
                videoHeight = 0,
                error = when (it) {
                    is SocketTimeoutException -> "超时"
                    else -> it.message ?: "请求失败"
                }
            )
        }
    }

    private fun probeClient(timeoutMs: Long): OkHttpClient {
        // Reusing a client keeps connection pools and dispatcher threads
        // bounded while a category is checked one channel at a time.
        val safeTimeoutMs = timeoutMs.coerceIn(1_000L, 15_000L)
        return probeClients.getOrPut(safeTimeoutMs) {
            OkHttpClient.Builder()
                .connectTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(safeTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(safeTimeoutMs + 500L, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    private fun readProbeSample(source: BufferedSource?, mimeType: String?): String {
        if (source == null) return ""
        val byteCount = if (mimeType == MimeTypes.APPLICATION_M3U8) 32L * 1024L else 512L
        source.request(byteCount)
        val buffer = source.buffer.clone()
        val sampleSize = minOf(buffer.size, byteCount)
        return if (sampleSize > 0) {
            buffer.readString(sampleSize, Charsets.UTF_8)
        } else {
            ""
        }
    }

    private fun extractResolution(content: String): Pair<Int, Int> {
        if (content.isBlank()) return 0 to 0
        val match = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(content)
        return if (match != null) {
            val width = match.groupValues[1].toIntOrNull() ?: 0
            val height = match.groupValues[2].toIntOrNull() ?: 0
            width to height
        } else {
            0 to 0
        }
    }
}
