package com.tvplayer.app.data.repository

import android.content.Context
import android.util.Xml
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.SubscriptionEndpoint
import com.tvplayer.app.network.NetworkRepository
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.PreferencesHelper
import java.io.File
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

class EpgRepository(
    context: Context,
    private val networkRepository: NetworkRepository = NetworkRepository(),
    private val preferences: PreferencesHelper = PreferencesHelper(context),
    private val subscriptionRepository: SubscriptionRepository = SubscriptionRepository(context)
) {

    data class EpgProgram(
        val title: String,
        val startMillis: Long,
        val endMillis: Long
    )

    data class ChannelEpgInfo(
        val current: EpgProgram?,
        val next: EpgProgram?
    )

    private data class EpgIndex(
        val lastModified: Long,
        val normalizedNameToIds: Map<String, Set<String>>,
        val programsByChannelId: Map<String, List<EpgProgram>>
    )

    private val cacheDir = File(context.filesDir, "epg").apply { mkdirs() }
    private val cacheFile = File(cacheDir, "epg.xml")

    @Volatile
    private var cachedIndex: EpgIndex? = null

    suspend fun refreshEpgSources(urls: List<String>? = null): Result<String> {
        val targetUrls = urls ?: getConfiguredUrls()
        if (targetUrls.isEmpty()) {
            return Result.failure(IllegalArgumentException("未配置 EPG 地址"))
        }

        val failures = mutableListOf<String>()
        targetUrls.forEach { url ->
            val result = networkRepository.fetchUrlContent(url)
            val content = result.getOrNull()
            if (content.isNullOrBlank()) {
                failures += "$url: 空响应"
                return@forEach
            }
            if (!looksLikeEpgXml(content)) {
                failures += "$url: 内容不是有效 EPG XML"
                return@forEach
            }

            cacheFile.writeText(content)
            preferences.epgLastUpdated = System.currentTimeMillis()
            preferences.epgLastSuccessfulUrl = url
            cachedIndex = null
            return Result.success(url)
        }

        return Result.failure(IllegalStateException(failures.joinToString(separator = "\n")))
    }

    suspend fun testEpgSources(urls: List<String>): Result<String> {
        val failures = mutableListOf<String>()
        urls.forEach { url ->
            val result = networkRepository.fetchUrlContent(url)
            val content = result.getOrNull()
            if (!content.isNullOrBlank() && looksLikeEpgXml(content)) {
                return Result.success(url)
            }
            failures += "$url: ${result.exceptionOrNull()?.message ?: "不是有效 EPG XML"}"
        }
        return Result.failure(IllegalStateException(failures.joinToString(separator = "\n")))
    }

    suspend fun getConfiguredUrls(): List<String> {
        val configured = subscriptionRepository.getEnabledEndpoints(SubscriptionEndpoint.TYPE_EPG)
            .map { it.url.trim() }
            .filter { it.isNotBlank() }
        return if (configured.isNotEmpty()) configured else preferences.getEpgSourceUrlList()
    }

    suspend fun getCurrentProgramInfo(
        channel: Channel,
        nowMillis: Long = System.currentTimeMillis()
    ): ChannelEpgInfo {
        val schedule = getProgramSchedule(channel, futureProgramCount = 1, nowMillis = nowMillis)
        val current = schedule.firstOrNull { nowMillis in it.startMillis until it.endMillis }
        val next = if (current != null) {
            schedule.firstOrNull { it.startMillis >= current.endMillis }
        } else {
            schedule.firstOrNull { it.startMillis > nowMillis }
        }
        return ChannelEpgInfo(current = current, next = next)
    }

    /** Returns the active programme followed by up to [futureProgramCount] future entries. */
    suspend fun getProgramSchedule(
        channel: Channel,
        futureProgramCount: Int = 5,
        nowMillis: Long = System.currentTimeMillis()
    ): List<EpgProgram> = withContext(Dispatchers.IO) {
        val index = getOrBuildIndex() ?: return@withContext emptyList()
        val matchedIds = matchChannelIds(index, channel.name)
        if (matchedIds.isEmpty()) {
            return@withContext emptyList()
        }

        val candidates = matchedIds.mapNotNull { channelId ->
            val programs = index.programsByChannelId[channelId].orEmpty()
            if (programs.isEmpty()) return@mapNotNull null
            val currentIndex = programs.indexOfFirst { nowMillis in it.startMillis until it.endMillis }
            val startIndex = if (currentIndex >= 0) currentIndex else {
                programs.indexOfFirst { it.startMillis > nowMillis }
            }
            if (startIndex < 0) emptyList() else programs.drop(startIndex)
        }

        // Never combine schedule rows from different fuzzy-matched channel IDs.
        // Prefer the ID with a current programme, otherwise the first upcoming one.
        val sequence = candidates.firstOrNull { programs ->
            programs.firstOrNull()?.let { nowMillis in it.startMillis until it.endMillis } == true
        } ?: candidates.firstOrNull().orEmpty()
        val count = futureProgramCount.coerceIn(1, 20) +
            if (sequence.firstOrNull()?.let { nowMillis in it.startMillis until it.endMillis } == true) 1 else 0
        sequence.take(count)
    }

    fun shouldRefresh(
        nowMillis: Long = System.currentTimeMillis(),
        allowWhenGloballyDisabled: Boolean = false
    ): Boolean {
        if (!preferences.epgEnabled && !allowWhenGloballyDisabled) return false

        val lastUpdated = preferences.epgLastUpdated
        if (lastUpdated <= 0L) return true

        val interval = preferences.epgRefreshInterval.coerceAtLeast(Constants.REFRESH_INTERVAL_5_MINUTES)
        val scheduledSlot = latestScheduledSlot(nowMillis, interval)
        return lastUpdated < scheduledSlot
    }

    /**
     * TV playback normally skips background EPG work. If the user explicitly
     * enables EPG on a low-end TV, fetch only when missing/stale and honor the
     * configured refresh interval even when the legacy profile disabled the
     * global background EPG flag.
     *
     * @return the refresh result, or null when the cached data is still fresh.
     */
    suspend fun refreshIfNeededForPlayback(
        allowWhenGloballyDisabled: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<String>? {
        if (!shouldRefresh(nowMillis, allowWhenGloballyDisabled)) return null
        return refreshEpgSources()
    }

    private fun latestScheduledSlot(nowMillis: Long, intervalMillis: Long): Long {
        val zoneId = ZoneId.systemDefault()
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        val preferredTime = parseRefreshTime(preferences.epgRefreshTimeOfDay)
        var anchor = now.withHour(preferredTime.hour).withMinute(preferredTime.minute)
            .withSecond(0)
            .withNano(0)

        while (anchor.toInstant().toEpochMilli() > nowMillis) {
            anchor = anchor.minusDays(1)
        }

        var slot = anchor.toInstant().toEpochMilli()
        while (slot + intervalMillis <= nowMillis) {
            slot += intervalMillis
        }
        return slot
    }

    private fun parseRefreshTime(value: String): LocalTime {
        return runCatching { LocalTime.parse(value) }
            .getOrElse { LocalTime.parse(Constants.DEFAULT_EPG_REFRESH_TIME) }
    }

    private fun looksLikeEpgXml(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith("<?xml", ignoreCase = true) &&
            (trimmed.contains("<tv") || trimmed.contains("<programme") || trimmed.contains("<channel"))
    }

    private fun getOrBuildIndex(): EpgIndex? {
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            return null
        }

        val lastModified = cacheFile.lastModified()
        cachedIndex?.takeIf { it.lastModified == lastModified }?.let { return it }

        val parser = Xml.newPullParser()
        parser.setInput(StringReader(cacheFile.readText()))

        val normalizedNameToIds = mutableMapOf<String, MutableSet<String>>()
        val programsByChannelId = mutableMapOf<String, MutableList<EpgProgram>>()

        var eventType = parser.eventType
        var currentChannelId: String? = null
        var collectingChannelName = false
        var currentProgrammeChannel: String? = null
        var currentProgrammeStart: Long? = null
        var currentProgrammeEnd: Long? = null
        var currentProgrammeTitle: String? = null
        var collectingProgrammeTitle = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> currentChannelId = parser.getAttributeValue(null, "id")
                    "display-name" -> collectingChannelName = currentChannelId != null
                    "programme" -> {
                        currentProgrammeChannel = parser.getAttributeValue(null, "channel")
                        currentProgrammeStart = parseProgrammeTime(parser.getAttributeValue(null, "start"))
                        currentProgrammeEnd = parseProgrammeTime(parser.getAttributeValue(null, "stop"))
                        currentProgrammeTitle = null
                    }
                    "title" -> collectingProgrammeTitle = currentProgrammeChannel != null
                }

                XmlPullParser.TEXT -> {
                    if (collectingChannelName) {
                        val displayName = parser.text.orEmpty().trim()
                        if (displayName.isNotBlank() && currentChannelId != null) {
                            val normalized = normalizeChannelName(displayName)
                            if (normalized.isNotBlank()) {
                                normalizedNameToIds.getOrPut(normalized) { mutableSetOf() }.add(currentChannelId!!)
                            }
                        }
                    }

                    if (collectingProgrammeTitle) {
                        val title = parser.text.orEmpty().trim()
                        if (title.isNotBlank()) {
                            currentProgrammeTitle = title
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name" -> collectingChannelName = false
                    "channel" -> currentChannelId = null
                    "title" -> collectingProgrammeTitle = false
                    "programme" -> {
                        val channelId = currentProgrammeChannel
                        val start = currentProgrammeStart
                        val end = currentProgrammeEnd
                        val title = currentProgrammeTitle
                        if (!channelId.isNullOrBlank() && start != null && end != null &&
                            !title.isNullOrBlank() && !isServiceAttributionTitle(title)
                        ) {
                            programsByChannelId.getOrPut(channelId) { mutableListOf() }
                                .add(EpgProgram(title = title, startMillis = start, endMillis = end))
                        }
                        currentProgrammeChannel = null
                        currentProgrammeStart = null
                        currentProgrammeEnd = null
                        currentProgrammeTitle = null
                    }
                }
            }

            eventType = parser.next()
        }

        return EpgIndex(
            lastModified = lastModified,
            normalizedNameToIds = normalizedNameToIds,
            programsByChannelId = programsByChannelId.mapValues { (_, programs) ->
                programs.sortedBy { it.startMillis }
            }
        ).also { cachedIndex = it }
    }

    private fun matchChannelIds(index: EpgIndex, channelName: String): Set<String> {
        val normalized = normalizeChannelName(channelName)
        if (normalized.isBlank()) return emptySet()

        index.normalizedNameToIds[normalized]?.let { return it }

        val variants = buildSet {
            add(normalized)
            val simplified = normalized.replace(
                Regex("(综合|新闻|电影|体育|少儿|电视剧|财经|科教|社会与法|纪录|戏曲|音乐)$"),
                ""
            )
            if (simplified.isNotBlank()) add(simplified)
        }

        // Only accept complete-name prefixes. Substring matching turned the
        // numeric part of CCTV2 into a match for CCTV12 and unrelated channels,
        // which could supply the wrong next-program title.
        val bestMatch = index.normalizedNameToIds.entries
            .mapNotNull { (indexedName, ids) ->
                val distance = variants.mapNotNull { variant ->
                    if (namesAreEquivalent(variant, indexedName)) {
                        kotlin.math.abs(variant.length - indexedName.length)
                    } else {
                        null
                    }
                }.minOrNull()
                distance?.let { it to ids }
            }
            .minByOrNull { it.first }
        return bestMatch?.second.orEmpty()
    }

    private fun namesAreEquivalent(first: String, second: String): Boolean {
        if (first == second) return true
        val shorter = if (first.length < second.length) first else second
        val longer = if (first.length < second.length) second else first
        if (!longer.startsWith(shorter)) return false

        // CCTV2 must not match CCTV20 or CCTV12 by a shared numeric prefix.
        val cctvNumberedName = shorter.contains(Regex("cctv\\d+"))
        val nextCharacter = longer.getOrNull(shorter.length)
        return !cctvNumberedName || nextCharacter?.isDigit() != true
    }

    private fun isServiceAttributionTitle(title: String): Boolean {
        val normalized = title.trim().lowercase(Locale.ROOT)
        val hasWebAddress = normalized.contains("http://") || normalized.contains("https://") ||
            normalized.contains("www.")
        val describesEpgService = normalized.contains("节目单") || normalized.contains("epg")
        return (hasWebAddress && describesEpgService) ||
            normalized.contains("提供节目单服务") ||
            normalized.contains("节目单服务由")
    }

    private fun normalizeChannelName(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\-_:：·•.,，。()/（）\\[\\]]"), "")
            .replace("频道", "")
            .replace("高清", "")
            .replace("超清", "")
    }

    private fun parseProgrammeTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        val zonedPatterns = listOf(
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z", Locale.US),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm Z", Locale.US)
        )
        zonedPatterns.forEach { formatter ->
            runCatching {
                return OffsetDateTime.parse(normalized, formatter).toInstant().toEpochMilli()
            }
        }

        val localPatterns = listOf(
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US),
            DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.US)
        )
        localPatterns.forEach { formatter ->
            runCatching {
                return ZonedDateTime.of(
                    LocalDateTime.parse(normalized, formatter),
                    ZoneId.systemDefault()
                ).toInstant().toEpochMilli()
            }
        }

        return runCatching { Instant.parse(normalized).toEpochMilli() }.getOrNull()
    }
}
