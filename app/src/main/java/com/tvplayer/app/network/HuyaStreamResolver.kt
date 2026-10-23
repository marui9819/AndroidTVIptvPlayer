package com.tvplayer.app.network

import android.text.Html
import android.util.Base64
import android.os.Build
import android.util.Log
import com.google.android.exoplayer2.util.MimeTypes
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tvplayer.app.App
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.DeviceProfile
import com.tvplayer.app.util.PreferencesHelper
import android.net.Uri
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okio.ByteString.Companion.decodeBase64

data class PlaybackStreamInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val resolvedBy: String = "direct",
    val expiresAtMillis: Long? = null
)

internal data class HuyaAntiCodeParameters(val encodedPairs: List<Pair<String, String>>) {
    val encodedValues = encodedPairs.associate { (key, value) ->
        URLDecoder.decode(key, Charsets.UTF_8.name()) to value
    }
    // Decode from the original query exactly once. In particular, a decoded
    // Base64 '+' must never pass through URLDecoder again and become a space.
    val fm = decodeValue("fm")
    val wsTime = decodeValue("wsTime")
    val ctype = decodeValue("ctype")
    val t = decodeValue("t")

    private fun decodeValue(key: String): String? = encodedValues[key]?.let {
        URLDecoder.decode(it, Charsets.UTF_8.name())
    }
}

internal fun parseHuyaAntiCode(antiCode: String): HuyaAntiCodeParameters =
    HuyaAntiCodeParameters(antiCode.split('&').mapNotNull { token ->
        val separator = token.indexOf('=')
        if (separator <= 0) null else token.substring(0, separator) to token.substring(separator + 1)
    })

internal data class HuyaStreamSignature(
    val wsSecret: String,
    val wsTime: String,
    val ctype: String,
    val t: String,
    val seqId: Long,
    val convertedUid: Long
)

internal fun signHuyaAntiCode(
    antiCode: HuyaAntiCodeParameters,
    streamName: String,
    defaultCtype: String,
    uid: Long,
    timestampMillis: Long
): HuyaStreamSignature {
    val fm = requireNotNull(antiCode.fm) { "Missing Huya fm" }
    val wsTime = requireNotNull(antiCode.wsTime) { "Missing Huya wsTime" }
    require(fm.isNotBlank() && wsTime.isNotBlank()) { "Empty Huya signing parameters" }
    // Okio is already supplied by OkHttp and works on API 23 as well as the
    // local JVM. This is Base64 decoding only, with no second URL decoding.
    val prefix = requireNotNull(fm.decodeBase64()) { "Invalid Huya fm Base64" }
        .utf8().substringBefore('_')
    val convertedUid = ((uid shl 8) or (uid ushr 24)) and 0xffffffffL
    val seqId = uid + timestampMillis
    val ctype = antiCode.ctype ?: defaultCtype
    val t = antiCode.t ?: "100"
    val secretHash = huyaMd5("$seqId|$ctype|$t")
    return HuyaStreamSignature(
        wsSecret = huyaMd5("${prefix}_${convertedUid}_${streamName}_${secretHash}_${wsTime}"),
        wsTime = wsTime,
        ctype = ctype,
        t = t,
        seqId = seqId,
        convertedUid = convertedUid
    )
}

private fun huyaMd5(value: String): String = MessageDigest.getInstance("MD5")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(Locale.US, it) }

/** Pure URL construction; callers supply the existing UID/time policy. */
internal fun buildHuyaResolvedStreamUrl(
    baseUrl: String,
    streamName: String,
    suffix: String,
    antiCode: String,
    bitrate: Int,
    forceHttps: Boolean,
    uid: Long,
    timestampMillis: Long
): String {
    val resolvedBaseUrl = if (forceHttps) baseUrl.replace("http://", "https://") else baseUrl
    val parsed = parseHuyaAntiCode(antiCode)
    val signature = signHuyaAntiCode(parsed, streamName, "huya_live", uid, timestampMillis)
    val params = linkedMapOf(
        "wsSecret" to signature.wsSecret,
        "wsTime" to URLEncoder.encode(signature.wsTime, Charsets.UTF_8.name()),
        "ctype" to URLEncoder.encode(signature.ctype, Charsets.UTF_8.name()),
        "fs" to parsed.encodedValues["fs"].orEmpty(),
        "seqid" to signature.seqId.toString(),
        "u" to signature.convertedUid.toString(),
        "sdk_sid" to timestampMillis.toString(),
        "ratio" to bitrate.toString(),
        "t" to URLEncoder.encode(signature.t, Charsets.UTF_8.name()),
        "ver" to "1",
        "sv" to "2401090219",
        "codec" to "264"
    )
    parsed.encodedPairs.forEach { (key, value) ->
        if (key in preservedHuyaAntiCodeKeys && key !in params.keys) {
            params[key] = value
        }
    }
    return "$resolvedBaseUrl/$streamName.$suffix?" +
        params.entries.joinToString("&") { (key, value) -> "$key=$value" }
}

/**
 * Keep the existing non-signature parameter policy. In particular, do not copy
 * txyp from the page response; this change only repairs decoding and isolation.
 */
private val preservedHuyaAntiCodeKeys = setOf("dMod", "sdkPcdn")

/** Pure mobile signing path, using the same once-decoded inputs as desktop. */
internal fun buildHuyaMobileStreamUrl(rawUrl: String, uid: Long, timestampMillis: Long): String? {
    val normalizedUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
    val queryStart = normalizedUrl.indexOf('?')
    if (queryStart <= 0) return null
    val path = normalizedUrl.substring(0, queryStart)
    val params = parseHuyaAntiCode(normalizedUrl.substring(queryStart + 1))
    val streamName = path.substringAfterLast('/').substringBeforeLast('.')
    if (streamName.isBlank()) return null
    val signature = runCatching {
        signHuyaAntiCode(params, streamName, "tars_mobile", uid, timestampMillis)
    }.getOrNull() ?: return null
    val fs = URLEncoder.encode(
        URLDecoder.decode(params.encodedValues["fs"].orEmpty(), Charsets.UTF_8.name()),
        Charsets.UTF_8.name()
    )
    val ctype = URLEncoder.encode(signature.ctype, Charsets.UTF_8.name())
    val t = URLEncoder.encode(signature.t, Charsets.UTF_8.name())
    val wsTime = URLEncoder.encode(signature.wsTime, Charsets.UTF_8.name())
    if (!path.endsWith(".m3u8", ignoreCase = true)) return null
    return "$path?wsSecret=${signature.wsSecret}&wsTime=$wsTime" +
        "&u=${signature.convertedUid}&seqid=${signature.seqId}&ctype=$ctype&ver=1&fs=$fs" +
        "&sdk_sid=$timestampMillis&t=$t&sv=2401090219&codec=264"
}

internal data class HuyaStreamCandidate(
    val url: String,
    val bitrate: Int,
    val cdnType: String,
    val priority: Int,
    val mimeType: String
)

/** Isolate each CDN/bitrate/protocol before applying the existing ranking. */
internal fun buildHuyaStreamCandidates(
    streamInfos: List<JsonObject>,
    bitrates: List<Int>,
    buildUrl: (String, String, String, String, Int) -> String,
    onCandidateFailure: (String, String, Exception) -> Unit = { _, _, _ -> }
): List<HuyaStreamCandidate> = buildList<HuyaStreamCandidate> {
    streamInfos.forEach { streamInfo ->
        bitrates.forEach { bitrate ->
            fun addProtocol(urlKey: String, suffixKey: String, antiCodeKey: String, mimeType: String) {
                var cdnType = "unknown"
                try {
                    cdnType = streamInfo.getAsStringOrNull("sCdnType").orEmpty().ifBlank { "unknown" }
                    val baseUrl = streamInfo.getAsStringOrNull(urlKey)
                    val suffix = streamInfo.getAsStringOrNull(suffixKey)
                    val antiCode = streamInfo.getAsStringOrNull(antiCodeKey)
                    if (baseUrl.isNullOrBlank() || suffix.isNullOrBlank() || antiCode.isNullOrBlank()) return
                    val streamName = streamInfo.getAsStringOrNull("sStreamName")
                    if (streamName.isNullOrBlank()) return
                    val priority = streamInfo.get("iMobilePriorityRate")?.asInt
                        ?: streamInfo.get("iWebPriorityRate")?.asInt
                        ?: streamInfo.get("iPCPriorityRate")?.asInt
                        ?: 0
                    add(HuyaStreamCandidate(
                        url = buildUrl(baseUrl, streamName, suffix, antiCode, bitrate),
                        bitrate = bitrate,
                        cdnType = cdnType,
                        priority = priority,
                        mimeType = mimeType
                    ))
                } catch (error: Exception) {
                    onCandidateFailure(cdnType, mimeType, error)
                }
            }

            addProtocol("sFlvUrl", "sFlvUrlSuffix", "sFlvAntiCode", "video/x-flv")
            // HLS must use its own anti-code and survive a malformed FLV candidate.
            addProtocol("sHlsUrl", "sHlsUrlSuffix", "sHlsAntiCode", MimeTypes.APPLICATION_M3U8)
        }
    }
}.distinctBy { "${it.mimeType}|${it.url}" }

private fun JsonObject.getAsStringOrNull(key: String): String? =
    takeIf { has(key) && !get(key).isJsonNull }?.get(key)?.asString

internal fun isValidHuyaCdnHost(host: String): Boolean {
    val normalizedHost = host.removePrefix("[").removeSuffix("]").removeSuffix(".")
    if (normalizedHost.isBlank() || normalizedHost.length > 253) return false
    if (':' in normalizedHost) {
        return runCatching { InetAddress.getByName(normalizedHost) is Inet6Address }
            .getOrDefault(false)
    }
    val asciiHost = runCatching {
        IDN.toASCII(normalizedHost, IDN.USE_STD3_ASCII_RULES)
    }.getOrNull() ?: return false
    if (asciiHost.length > 253) return false
    return asciiHost.split('.').all { label ->
        label.length in 1..63 &&
            label.first().isLetterOrDigit() &&
            label.last().isLetterOrDigit() &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}

internal fun isInvalidHuyaCdnHostError(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }
        .take(12)
        .any { cause ->
            cause is IllegalArgumentException &&
                cause.message.orEmpty().contains("toASCII", ignoreCase = true)
        }

object HuyaStreamResolver {

    private const val TAG = "HuyaStreamResolver"
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; SM-G900P) AppleWebKit/537.36 " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    // APTV 1.5.12's shipped player binary contains this macOS Chrome UA and
    // uses AVURLAsset HTTP header support. Keep this scoped to Huya media
    // requests; room-page resolution continues to use the Android mobile UA.
    internal const val HUYA_MEDIA_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val PRIMARY_ROOM_CALL_TIMEOUT_MS = 10_000L
    private const val LEGACY_HTTP1_CALL_TIMEOUT_MS = 6_000L
    private val roomIdRegex = Regex("""(?:/live/huya/|huya\.com/)(\d+)""", RegexOption.IGNORE_CASE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // Bound the whole response body read as well as each individual read;
        // the default read timeout alone permits a slow-drip room page to keep
        // a same-channel renewal suspended indefinitely.
        .callTimeout(PRIMARY_ROOM_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val http11Client = client.newBuilder()
        .connectTimeout(LEGACY_HTTP1_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(LEGACY_HTTP1_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(LEGACY_HTTP1_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val redirectProbeClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun supports(url: String): Boolean = roomIdRegex.containsMatchIn(url)

    suspend fun resolveOrThrow(
        url: String,
        attemptIndex: Int = 0,
        schemeOverride: String? = null,
        validateCdnRedirectHost: Boolean = false
    ): PlaybackStreamInfo = withContext(Dispatchers.IO) {
        val scheme = schemeOverride ?: currentScheme()
        val roomId = roomIdRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("未识别到虎牙房间号")
        Log.d(TAG, "Attempting to resolve Huya roomId=$roomId for url=$url attempt=$attemptIndex")

        val html = runCatching { fetchRoomHtml(roomId) }
            .getOrElse { error ->
                throw IllegalStateException(
                    "虎牙房间页请求失败，可能与 DNS / IPv6 / TLS 兼容有关：${error.message ?: "未知错误"}",
                    error
                )
            }

        val streamData = parseStreamJson(html)
            ?: throw IllegalStateException("虎牙房间页已返回，但未解析到直播流数据，可能是页面结构变化或返回了风控页")

        val data = streamData.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
            ?: throw IllegalStateException("虎牙返回的直播流数据结构不完整")
        val streamInfos = data.getAsJsonArray("gameStreamInfoList")
            ?.mapNotNull { it?.asJsonObject }
            .orEmpty()
        if (streamInfos.isEmpty()) {
            throw IllegalStateException("虎牙房间当前未返回可用流列表")
        }

        val bitrates = streamData.getAsJsonArray("vMultiStreamInfo")
            ?.mapNotNull { item ->
                item?.asJsonObject?.get("iBitRate")?.asInt
            }
            ?.distinct()
            ?.sortedWith(compareBy<Int> { if (it == 0) -1 else it })
            .orEmpty()
            .ifEmpty { listOf(0) }

        // On Android 6/7 TVs the mobile liveLineUrl is the more reliable HLS
        // path. Some desktop HLS URLs pass parsing but are rejected by the
        // CDN with 403/404 after the first refresh, which would otherwise make
        // every recovery attempt repeat the same invalid desktop signature.
        if (scheme == PreferencesHelper.HUYA_SCHEME_SINGLE_HLS) {
            resolveMobileHls(roomId)?.let { mobileStream ->
                if (!validateCdnRedirectHost || isCdnRedirectHostSafe(mobileStream.url) != false) {
                    Log.d(TAG, "Using Huya mobile HLS stream for roomId=$roomId")
                    return@withContext mobileStream
                }
                Log.w(TAG, "Skipping Huya mobile HLS route with an invalid redirect host")
            }
        }
        val candidates = buildCandidates(streamInfos, bitrates, scheme)
        if (candidates.isEmpty()) {
            val schemeHint = when (scheme) {
                PreferencesHelper.HUYA_SCHEME_SINGLE_HLS -> "当前频道设置为 HLS 方案，但房间未返回可用 HLS 流"
                PreferencesHelper.HUYA_SCHEME_SINGLE_FLV -> "当前频道设置为 FLV 方案，但房间未返回可用 FLV 流"
                else -> "未筛选到可用虎牙候选流"
            }
            throw IllegalStateException("$schemeHint，建议切换到“智能热切换”或“单流 FLV”")
        }
        // One room exposes multiple bitrates for each CDN. A retry must move
        // to another CDN, not merely to another bitrate on the same failing
        // endpoint. The TV diagnostics contain AL -> TX 404/403 sequences
        // within seconds, so endpoint diversity is more valuable than quality
        // switching here.
        val rotatedCandidates = rotateCandidatesByCdn(candidates, attemptIndex)
        val selectedCandidate = if (validateCdnRedirectHost) {
            rotatedCandidates
                .distinctBy { it.cdnType.uppercase(Locale.US) }
                .take(4)
                .firstOrNull { candidate ->
                    val redirectHostSafe = isCdnRedirectHostSafe(candidate.url)
                    if (redirectHostSafe == false) {
                        Log.w(TAG, "Skipping Huya CDN ${candidate.cdnType} with an invalid redirect host")
                    }
                    redirectHostSafe != false
                }
        } else {
            rotatedCandidates.firstOrNull()
        } ?: throw IllegalStateException("虎牙候选线路均不可用或跳转主机名无效")

        PlaybackStreamInfo(
            url = selectedCandidate.url,
            headers = huyaHeaders(),
            mimeType = selectedCandidate.mimeType,
            resolvedBy = "huya:${selectedCandidate.cdnType}:${selectedCandidate.bitrate}:${selectedCandidate.mimeType}",
            expiresAtMillis = parseExpiryMillis(selectedCandidate.url)
        ).also {
            Log.d(
                TAG,
                "Resolved Huya stream roomId=$roomId -> ${selectedCandidate.url} " +
                    "cdn=${selectedCandidate.cdnType} bitrate=${selectedCandidate.bitrate} mime=${selectedCandidate.mimeType}"
            )
        }
    }

    suspend fun resolve(
        url: String,
        attemptIndex: Int = 0,
        schemeOverride: String? = null,
        validateCdnRedirectHost: Boolean = false
    ): PlaybackStreamInfo? = withContext(Dispatchers.IO) {
        runCatching {
            resolveOrThrow(url, attemptIndex, schemeOverride, validateCdnRedirectHost)
        }.onFailure {
            Log.w(TAG, "Failed to resolve Huya stream for url=$url", it)
        }.getOrNull()
    }

    /**
     * Check only the redirect authority before a scheduled renewal. A malformed
     * IPv6-derived hostname can make Android's TLS SNI setup throw before the
     * player gets a chance to try another CDN. Network failures are treated as
     * unknown and keep the candidate; only an explicitly invalid redirect is
     * rejected.
     */
    private fun isCdnRedirectHostSafe(url: String): Boolean? {
        val request = runCatching {
            Request.Builder()
                .url(url)
                .headers(okhttp3.Headers.headersOf(
                    *huyaHeaders().flatMap { listOf(it.key, it.value) }.toTypedArray()
                ))
                .header("Range", "bytes=0-0")
                .get()
                .build()
        }.getOrNull() ?: return false

        return runCatching {
            redirectProbeClient.newCall(request).execute().use { response ->
                if (response.code !in 300..399) return@use true
                val location = response.header("Location") ?: return@use false
                val target = response.request.url.resolve(location) ?: return@use false
                isValidHuyaCdnHost(target.host)
            }
        }.getOrNull()
    }

    private fun buildCandidates(
        streamInfos: List<JsonObject>,
        bitrates: List<Int>,
        scheme: String
    ): List<HuyaStreamCandidate> {
        val discoveredCandidates = buildHuyaStreamCandidates(
            streamInfos,
            bitrates,
            buildUrl = { baseUrl, streamName, suffix, antiCode, bitrate ->
                buildResolvedStreamUrl(baseUrl, streamName, suffix, antiCode, bitrate, forceHttps = true)
            },
            onCandidateFailure = { cdnType, mimeType, error ->
                Log.w(TAG, "Skipping malformed Huya candidate cdn=$cdnType mime=$mimeType", error)
            }
        )
        val validCandidates = discoveredCandidates.filter { candidate ->
            Uri.parse(candidate.url).host?.let(::isValidHuyaCdnHost) == true
        }
        if (validCandidates.size != discoveredCandidates.size) {
            Log.w(
                TAG,
                "Skipped ${discoveredCandidates.size - validCandidates.size} Huya candidate(s) " +
                    "with invalid TLS host names"
            )
        }
        val sortedCandidates = validCandidates.sortedWith(
            compareBy<HuyaStreamCandidate> { candidateRank(it) }
                // HLS lets ExoPlayer request the next live segment
                // independently. Prefer it for smart mode as the other
                // 5xingTV implementation does; FLV remains available as
                // a fallback for rooms without HLS metadata.
                .thenBy {
                    if (scheme != PreferencesHelper.HUYA_SCHEME_SINGLE_FLV &&
                        it.mimeType == MimeTypes.APPLICATION_M3U8
                    ) 0 else 1
                }
                .thenByDescending { it.priority }
                .thenBy { if (it.bitrate == 0) -1 else it.bitrate }
                .thenBy { it.cdnType }
        )
        // HLS is significantly less demanding than progressive FLV on older
        // MStar firmware.  Prefer it when selected, but retain FLV as a
        // fallback for rooms that do not publish an HLS variant.
        return when (scheme) {
            PreferencesHelper.HUYA_SCHEME_SINGLE_HLS ->
                sortedCandidates.filter { it.mimeType == MimeTypes.APPLICATION_M3U8 }
                    .ifEmpty { sortedCandidates }
            PreferencesHelper.HUYA_SCHEME_SINGLE_FLV ->
                sortedCandidates.filter { it.mimeType == "video/x-flv" }
                    .ifEmpty { sortedCandidates }
            else -> sortedCandidates
        }
    }

    private fun candidateRank(candidate: HuyaStreamCandidate): Int {
        // The reference implementation used by the other 5xingTV project
        // prioritizes TX/HW before AL.  Recent TV diagnostics show repeated
        // AL HLS 403 responses, so use the same CDN order here.
        return when (candidate.cdnType.uppercase(Locale.US)) {
            "TX" -> 0
            "HW" -> 1
            "AL" -> 2
            "HS" -> 3
            "WS" -> 4
            "HY" -> 5
            "BD" -> 6
            else -> 7
        }
    }

    private fun currentScheme(): String {
        return runCatching { App.instance.preferences.huyaPlaybackScheme }
            .getOrDefault(PreferencesHelper.HUYA_SCHEME_HOT_SWAP)
    }

    private fun rotateCandidatesByCdn(
        candidates: List<HuyaStreamCandidate>,
        attemptIndex: Int
    ): List<HuyaStreamCandidate> {
        if (candidates.isEmpty() || attemptIndex <= 0) return candidates
        val grouped = candidates.groupBy { it.cdnType.uppercase(Locale.US) }.values.toList()
        if (grouped.size <= 1) return candidates
        val pivot = attemptIndex.mod(grouped.size)
        return (grouped.drop(pivot) + grouped.take(pivot)).flatten()
    }

    private fun parseExpiryMillis(url: String): Long? {
        val wsTime = Uri.parse(url).getQueryParameter("wsTime") ?: return null
        return wsTime.toLongOrNull(16)?.times(1000)
    }

    private fun fetchRoomHtml(roomId: String): String {
        val requestHeaders = okhttp3.Headers.headersOf(
            *huyaHeaders().flatMap { listOf(it.key, it.value) }.toTypedArray()
        )
        val desktopUrl = "https://www.huya.com/$roomId"
        val mobileUrl = "https://m.huya.com/$roomId"
        // Older MStar Android builds have less reliable HTTP/2 negotiation.
        // Try bounded HTTP/1.1 first there so a failed protocol handshake does
        // not consume most of the signed-stream renewal window.
        val candidates = if (DeviceProfile.isLegacyTv(App.instance)) {
            listOf(
                http11Client to desktopUrl,
                http11Client to mobileUrl,
                client to desktopUrl
            )
        } else {
            listOf(
                client to desktopUrl,
                http11Client to desktopUrl,
                http11Client to mobileUrl
            )
        }
        var lastError: Throwable? = null
        candidates.forEach { (httpClient, requestUrl) ->
            runCatching {
                val request = Request.Builder()
                    .url(requestUrl)
                    .get()
                    .headers(requestHeaders)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Huya room request failed: HTTP ${response.code}" }
                    return response.body?.string().orEmpty()
                }
            }.onFailure { error ->
                lastError = error
                Log.w(TAG, "Failed to fetch Huya room html from $requestUrl", error)
            }
        }
        throw IllegalStateException("Huya room request failed for roomId=$roomId", lastError)
    }

    /**
     * Mobile Huya pages publish a base64 liveLineUrl even when the desktop
     * page only exposes FLV metadata.  This fallback mirrors the working
     * resolver in 5xingTV and is especially important for Android 6/7 TVs:
     * a signed FLV connection can be closed by the CDN after a few chunks,
     * while the m3u8 endpoint lets ExoPlayer continue requesting segments.
     */
    private fun resolveMobileHls(roomId: String): PlaybackStreamInfo? {
        return runCatching {
            val request = Request.Builder()
                .url("https://m.huya.com/$roomId")
                .get()
                .headers(
                    okhttp3.Headers.headersOf(
                        "User-Agent", MOBILE_USER_AGENT,
                        "Referer", "https://m.huya.com/",
                        "Accept", "text/html,application/xhtml+xml"
                    )
                )
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Huya mobile room request failed: HTTP ${response.code}" }
                val html = response.body?.string().orEmpty()
                val encoded = Regex("""\"liveLineUrl\":\"([^\"]+)\"""")
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return@use null
                val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
                if (decoded.contains("replay", ignoreCase = true)) return@use null
                signMobileStream(decoded)
            }
        }.onFailure { error ->
            Log.w(TAG, "Huya mobile HLS fallback failed for roomId=$roomId", error)
        }.getOrNull()
    }

    private fun signMobileStream(rawUrl: String): PlaybackStreamInfo? {
        val signedUrl = buildHuyaMobileStreamUrl(
            rawUrl,
            uid = (12_340_000..12_349_999).random().toLong(),
            timestampMillis = System.currentTimeMillis()
        ) ?: return null

        return PlaybackStreamInfo(
            url = signedUrl,
            headers = mobileHuyaMediaHeaders(),
            mimeType = MimeTypes.APPLICATION_M3U8,
            resolvedBy = "huya:mobile:hls",
            expiresAtMillis = parseExpiryMillis(signedUrl)
        )
    }

    private fun parseStreamJson(html: String): JsonObject? {
        val searchWindow = html.substring((html.indexOf("hyPlayerConfig")).coerceAtLeast(0))
        extractJsonObject(searchWindow, "stream")?.let { json ->
            return JsonParser.parseString(json).asJsonObject
        }

        val base64Match = Regex("""(?:"stream"|stream)\s*:\s*"([^"]+)"""").find(searchWindow)
        if (base64Match != null) {
            val decoded = String(Base64.decode(base64Match.groupValues[1], Base64.DEFAULT))
            return JsonParser.parseString(decoded).asJsonObject
        }
        return null
    }

    private fun extractJsonObject(html: String, key: String): String? {
        val candidates = listOf("\"$key\"", "$key:")
        val keyIndex = candidates
            .map { html.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return null
        val start = html.indexOf('{', keyIndex)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until html.length) {
            val char = html[index]
            if (escaped) {
                escaped = false
                continue
            }
            when (char) {
                '\\' -> if (inString) escaped = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth++
                '}' -> if (!inString) {
                    depth--
                    if (depth == 0) {
                        return html.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun buildResolvedStreamUrl(
        baseUrl: String,
        streamName: String,
        suffix: String,
        antiCode: String,
        bitrate: Int,
        forceHttps: Boolean
    ): String {
        return buildHuyaResolvedStreamUrl(
            baseUrl, streamName, suffix, htmlUnescape(antiCode), bitrate, forceHttps,
            uid = (12340000..12349999).random().toLong(),
            timestampMillis = System.currentTimeMillis()
        )
    }

    @Suppress("DEPRECATION")
    private fun htmlUnescape(value: String): String {
        // Html.fromHtml(String, flags) exists only from Android 7 (API 24).
        // The MStar TV reports Android 6 / API 23, so the previous call throws
        // NoSuchMethodError during every Huya/YQK resolution and terminates the
        // process before normal playback error handling can run.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            Html.fromHtml(value).toString()
        }
    }

    fun huyaHeaders(): Map<String, String> = mapOf(
        "User-Agent" to HUYA_MEDIA_USER_AGENT,
        "Referer" to "https://www.huya.com/",
        "Origin" to "https://www.huya.com",
        "Accept" to "*/*"
    )

    internal fun mobileHuyaMediaHeaders(): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Referer" to "https://m.huya.com/",
        "Origin" to "https://m.huya.com",
        "Accept" to "*/*"
    )

    private fun <T> Iterable<T>.firstOrNull(): T? {
        val iterator = iterator()
        return if (iterator.hasNext()) iterator.next() else null
    }

}
