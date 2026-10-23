package com.tvplayer.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.SubscriptionEndpoint
import com.tvplayer.app.network.NetworkRepository
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.DeviceProfile
import com.tvplayer.app.util.PreferencesHelper
import java.io.File
import java.util.Locale

class LogoRepository(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context),
    private val subscriptionRepository: SubscriptionRepository = SubscriptionRepository(context),
    private val networkRepository: NetworkRepository = NetworkRepository()
) {
    private val preferences = PreferencesHelper(context)
    private val gson = Gson()
    private val cacheFile: File by lazy {
        File(context.filesDir, Constants.LOGO_CACHE_FILE_NAME)
    }
    @Volatile
    private var memoryLogoMap: Map<String, String>? = null

    private val unreliableLogoHosts = setOf(
        "live.cooltv.top",
        "epg.112114.eu.org",
        "epg.51zmt.top",
        "q1.fengshows.cn"
    )

    private val shouldResolveLogos: Boolean
        get() = preferences.showChannelLogos && !DeviceProfile.isLegacyTv(context)

    suspend fun applyLogos(channels: List<Channel>, forceRefresh: Boolean = false): List<Channel> {
        if (!shouldResolveLogos) return channels
        val logoMap = loadLogoMap(forceRefresh)
        if (logoMap.isEmpty()) return channels
        return channels.map { channel ->
            val resolved = resolveLogoUrl(channel, logoMap)
            if (resolved.isNullOrBlank() || resolved == channel.logo) channel else channel.copy(logo = resolved)
        }
    }

    suspend fun applyLogosToDatabase(forceRefresh: Boolean = false): Int {
        if (!shouldResolveLogos) return 0
        val logoMap = loadLogoMap(forceRefresh)
        if (logoMap.isEmpty()) return 0

        val updates = database.channelDao().getAllChannelsList().mapNotNull { channel ->
            val resolved = resolveLogoUrl(channel, logoMap)
            if (resolved.isNullOrBlank() || resolved == channel.logo) null
            else channel.copy(logo = resolved)
        }
        if (updates.isNotEmpty()) {
            // Room wraps a list @Update in one transaction, avoiding one
            // transaction and fsync per channel on a low-end TV.
            database.channelDao().updateAll(updates)
        }
        return updates.size
    }

    suspend fun loadLogoMap(forceRefresh: Boolean = false): Map<String, String> {
        if (!forceRefresh) {
            memoryLogoMap?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        if (!forceRefresh) {
            loadCache()?.takeIf { it.isNotEmpty() }?.let {
                memoryLogoMap = it
                return it
            }
        }

        val endpoints = subscriptionRepository.getEnabledEndpoints(SubscriptionEndpoint.TYPE_LOGO)
        if (endpoints.isEmpty()) return emptyMap()

        val merged = linkedMapOf<String, String>()
        endpoints.forEach { endpoint ->
            val content = networkRepository.fetchUrlContent(endpoint.url).getOrNull().orEmpty()
            parseLogoList(content).forEach { (name, logoUrl) ->
                buildLookupKeys(name).forEach { key ->
                    if (!merged.containsKey(key)) {
                        merged[key] = logoUrl
                    }
                }
            }
        }

        if (merged.isNotEmpty()) {
            cacheFile.writeText(gson.toJson(merged))
        }
        memoryLogoMap = merged
        return merged
    }

    fun parseLogoList(content: String): Map<String, String> {
        if (content.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val httpIndex = line.indexOf("http")
                if (httpIndex <= 0) return@forEach
                val name = line.substring(0, httpIndex).trim().trimEnd(',', '|', ';', '\t')
                val url = line.substring(httpIndex).trim()
                if (name.isNotBlank() && url.startsWith("http")) {
                    if (!result.containsKey(name)) {
                        result[name] = url
                    }
                }
            }
        return result
    }

    private fun loadCache(): Map<String, String>? {
        if (!cacheFile.exists()) return null
        return runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(cacheFile.readText(), type)
        }.getOrNull()
    }

    private fun resolveLogoUrl(channel: Channel, logoMap: Map<String, String>): String? {
        val sourceLogo = channel.logo?.trim()?.takeIf(::looksLikeUrl)
        val libraryLogo = findLibraryLogo(channel, logoMap)

        return when {
            libraryLogo.isNullOrBlank() -> sourceLogo
            sourceLogo.isNullOrBlank() -> libraryLogo
            shouldPreferLibraryLogo(sourceLogo, libraryLogo) -> libraryLogo
            else -> sourceLogo
        }
    }

    private fun findLibraryLogo(channel: Channel, logoMap: Map<String, String>): String? {
        return buildLookupKeys(channel.name)
            .asSequence()
            .mapNotNull { key -> logoMap[key] }
            .firstOrNull()
    }

    private fun shouldPreferLibraryLogo(currentLogo: String, libraryLogo: String): Boolean {
        val currentHost = extractHost(currentLogo)
        return currentHost in unreliableLogoHosts ||
            (currentLogo.startsWith("http://", ignoreCase = true) &&
                libraryLogo.startsWith("https://", ignoreCase = true))
    }

    private fun extractHost(url: String): String {
        return runCatching {
            val withoutScheme = url.substringAfter("://", "")
            withoutScheme.substringBefore("/").substringBefore(":").lowercase(Locale.ROOT)
        }.getOrDefault("")
    }

    private fun looksLikeUrl(value: String): Boolean {
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
    }

    private fun buildLookupKeys(name: String): List<String> {
        val normalized = Channel.normalizeName(name)
        if (normalized.isBlank()) return emptyList()

        val keys = linkedSetOf<String>()
        keys += name.trim()
        keys += normalized

        val suffixVariants = listOf(
            "综合", "新闻", "电影", "体育", "体育赛事", "少儿", "电视剧", "财经",
            "科教", "社会与法", "纪录", "戏曲", "音乐", "奥林匹克", "农业农村",
            "国防军事", "中文国际", "国际", "生活", "都市", "影视", "娱乐", "资讯",
            "民生休闲", "教科影视", "经济生活", "钱江都市", "新闻综合", "文化影视",
            "融媒", "融媒体", "电视台", "广播", "伴音"
        )

        var trimmed = normalized
        suffixVariants.forEach { suffix ->
            if (trimmed.endsWith(suffix)) {
                trimmed = trimmed.removeSuffix(suffix)
                if (trimmed.isNotBlank()) {
                    keys += trimmed
                }
            }
        }

        Regex("^CCTV(\\d{1,2})(PLUS|\\+)?").find(normalized)?.let { match ->
            val number = match.groupValues[1]
            val plus = match.groupValues.getOrNull(2).orEmpty()
            keys += "CCTV$number"
            keys += "CCTV-$number"
            if (plus.isNotBlank()) {
                keys += "CCTV${number}+"
                keys += "CCTV-${number}+"
            }
        }

        return keys.filter { it.isNotBlank() }
    }
}
