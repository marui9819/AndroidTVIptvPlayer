package com.tvplayer.app.network

import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.util.MimeTypes
import com.google.gson.JsonParser
import com.tvplayer.app.App
import com.tvplayer.app.util.PreferencesHelper
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.Context
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable

object DouyuStreamResolver {

    /**
     * The web gateway can reject a signature request even while the room is
     * still live. This is not evidence that the current channel is dead and
     * must not be fed into the normal channel-failover path.
     */
    class ProtectionException(message: String) : IOException(message)

    private const val TAG = "DouyuStreamResolver"
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    private const val DOUYU_WEB_BASE_URL = "https://www.douyu.com/"
    private const val HOME_H5_ENC_URL = "https://www.douyu.com/swf_api/homeH5Enc"
    // The current web endpoint accepts the signed parameters in the query
    // string.  The older playweb host still exists but now commonly returns
    // error=-1 (参数错误) for the same payload.
    private const val GET_H5_PLAY_URL = "https://www.douyu.com/lapi/live/getH5Play/"
    private const val DID_API_URL = "https://passport.douyu.com/lapi/did/api/get"
    private const val DOUYU_VERSION = "Douyu_219052705"
    private const val DEFAULT_DID = "10000000000000000000000000001501"
    private const val MAX_SIGNING_SCRIPT_LENGTH = 512 * 1024
    private val allowedRhinoClasses = setOf(
        "java.security.MessageDigest",
        // Rhino wraps byte[] results from MessageDigest through Object while
        // evaluating the signed H5 function.  Object exposes no filesystem,
        // process, reflection, or network API and is safe to allow here.
        "java.lang.Object",
        "java.lang.String",
        "java.lang.Integer"
    )
    private const val DOUYU_MOBILE_BASE_URL = "https://m.douyu.com"
    private const val DOUYU_RATE_STREAM_API = "https://m.douyu.com/api/room/ratestream"
    private const val DOUYU_H5_PREVIEW_API = "https://playweb.douyucdn.cn/lapi/live/hlsH5Preview/"
    private val roomIdRegex = Regex("""(?:goodiptv\.club/douyu/|112114\.[^/]+/douyu/|m\.douyu\.com/|douyu\.com/)(\d+)""", RegexOption.IGNORE_CASE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Some TV firmware images have a stale wall clock.  Douyu rejects an H5
    // signature made with that clock, so keep a short-lived offset learned
    // from the Date header of the room page.
    @Volatile
    private var serverTimeOffsetMillis: Long = 0L

    fun supports(url: String): Boolean = roomIdRegex.containsMatchIn(url)

    suspend fun resolve(
        url: String,
        officialOnly: Boolean = false,
        schemeOverride: String? = null
    ): PlaybackStreamInfo = withContext(Dispatchers.IO) {
        val scheme = schemeOverride ?: currentScheme()
        val roomId = extractRoomId(url)
        if (roomId == null) {
            return@withContext PlaybackStreamInfo(
                url = url,
                headers = douyuPlaybackHeaders(),
                resolvedBy = "douyu-unsupported"
            )
        }

        var officialFailure: Throwable? = null
        fun tryOfficial(): PlaybackStreamInfo? {
            val stream = resolveOfficialStream(
                roomId = roomId,
                scheme = scheme,
                allowLegacyFallback = !officialOnly
            ).onFailure {
                officialFailure = it
                val nextStep = if (officialOnly) {
                    "continuity retry will keep the same room"
                } else {
                    "compatibility fallback may be tried"
                }
                Log.w(TAG, "Official Douyu resolve failed for roomId=$roomId; $nextStep", it)
            }.getOrNull() ?: return null
            return PlaybackStreamInfo(
                    url = stream.url,
                    headers = douyuH5PlaybackHeaders(roomId),
                    mimeType = stream.mimeType,
                    resolvedBy = "douyu-official",
                    expiresAtMillis = stream.expiresAtMillis
                ).also {
                    Log.d(TAG, "Resolved Douyu roomId=$roomId via H5/official -> ${stream.url}")
                }
        }

        fun tryPreview(): PlaybackStreamInfo? {
            val previewStream = resolvePreviewStream(roomId).onFailure {
                Log.w(TAG, "Douyu preview resolve failed for roomId=$roomId, fallback to source url=$url", it)
            }.getOrNull() ?: return null
            return PlaybackStreamInfo(
                    url = previewStream.url,
                    headers = douyuPlaybackHeaders(roomId),
                    mimeType = previewStream.mimeType,
                    resolvedBy = previewStream.resolvedBy,
                    expiresAtMillis = parseExpiryMillis(previewStream.url)
                ).also {
                    Log.d(TAG, "Resolved Douyu preview roomId=$roomId -> ${previewStream.url}")
                }
        }

        fun tryEntry(): PlaybackStreamInfo {
            val headers = douyuEntryHeaders(url)
            val fallbackResolvedBy = if (shouldAllowEntryFallback(officialFailure)) {
                "douyu-entry-compat"
            } else {
                "douyu-entry-fallback"
            }
            return runCatching {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .headers(headersOf(headers))
                    .build()
                client.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    val mimeType = when {
                        finalUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                        else -> null
                    }
                    PlaybackStreamInfo(
                        url = finalUrl,
                        headers = headers,
                        mimeType = mimeType,
                        resolvedBy = fallbackResolvedBy
                    )
                }
            }.getOrElse {
                PlaybackStreamInfo(
                    url = url,
                    headers = headers,
                    resolvedBy = fallbackResolvedBy
                )
            }
        }

        val orderedAttempts = when {
            officialOnly -> listOf("official")
            scheme == PreferencesHelper.DOUYU_SCHEME_OFFICIAL -> listOf("official", "preview", "entry")
            scheme == PreferencesHelper.DOUYU_SCHEME_PREVIEW -> listOf("preview", "official", "entry")
            scheme == PreferencesHelper.DOUYU_SCHEME_ENTRY -> listOf("entry", "official", "preview")
            else -> listOf("official", "preview", "entry")
        }

        orderedAttempts.forEach { attempt ->
            when (attempt) {
                "official" -> tryOfficial()?.let { return@withContext it }
                "preview" -> tryPreview()?.let { return@withContext it }
                "entry" -> {
                    if (!officialOnly || shouldAllowEntryFallback(officialFailure)) {
                        return@withContext tryEntry()
                    }
                }
            }
        }

        throw IllegalStateException(
            "Douyu resolve failed for roomId=$roomId",
            officialFailure
        )
    }

    private fun shouldAllowEntryFallback(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        return message.contains("code=7", ignoreCase = true)
    }

    private data class PreviewStream(
        val url: String,
        val mimeType: String?,
        val resolvedBy: String
    )

    private data class OfficialStream(
        val url: String,
        val mimeType: String?,
        val expiresAtMillis: Long?
    )

    private fun resolvePreviewStream(roomId: String): Result<PreviewStream> = runCatching {
        val did = "10000000000000000000000000001501"
        val t13 = System.currentTimeMillis().toString()
        val auth = md5(roomId + t13)
        val request = Request.Builder()
            .url("$DOUYU_H5_PREVIEW_API$roomId")
            .post(
                FormBody.Builder()
                    .add("rid", roomId)
                    .add("did", did)
                    .build()
            )
            .headers(
                headersOf(
                    mapOf(
                        "rid" to roomId,
                        "time" to t13,
                        "auth" to auth,
                        "User-Agent" to MOBILE_USER_AGENT,
                        "Referer" to "$DOUYU_MOBILE_BASE_URL/$roomId",
                        "Origin" to "https://playweb.douyucdn.cn",
                        "Accept" to "application/json, text/plain, */*"
                    )
                )
            )
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Douyu preview failed: HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            val json = JsonParser.parseString(body).asJsonObject
            val error = json.get("error")?.asInt ?: -1
            check(error == 0) { "Douyu preview error code=$error" }
            val data = json.getAsJsonObject("data")
            val rtmpUrl = data?.get("rtmp_url")?.asString.orEmpty().trimEnd('/')
            val rtmpLive = data?.get("rtmp_live")?.asString.orEmpty()
            check(rtmpLive.isNotBlank()) { "Douyu preview returned empty stream" }

            if (rtmpUrl.isNotBlank()) {
                val directPreviewUrl = "$rtmpUrl/$rtmpLive"
                val directPreviewMimeType = when {
                    directPreviewUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                    directPreviewUrl.contains(".flv", ignoreCase = true) -> "video/x-flv"
                    else -> null
                }
                if (directPreviewMimeType != null) {
                    return@use PreviewStream(
                        url = directPreviewUrl,
                        mimeType = directPreviewMimeType,
                        resolvedBy = "douyu-preview-direct"
                    )
                }
            }

            val key = Regex("""(\d{1,8}[0-9A-Za-z]+)_?\d{0,4}(/playlist|\.m3u8)""")
                .find(rtmpLive)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
            check(key.isNotBlank()) { "Douyu preview key parse failed" }

            val fallbackCandidates = listOf(
                PreviewStream(
                    url = "http://dyscdnali1.douyucdn.cn/live/$key.flv?uuid=",
                    mimeType = "video/x-flv",
                    resolvedBy = "douyu-preview-ali1"
                ),
                PreviewStream(
                    url = "http://dyscdnali3.douyucdn.cn/live/$key.flv?uuid=",
                    mimeType = "video/x-flv",
                    resolvedBy = "douyu-preview-ali3"
                ),
                PreviewStream(
                    url = "http://vplay1a.douyucdn.cn/live/$key.flv?uuid=",
                    mimeType = "video/x-flv",
                    resolvedBy = "douyu-preview-vplay"
                )
            )
            fallbackCandidates.first()
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun douyuHeaders(url: String? = null): Map<String, String> {
        return if (url != null && url.contains("goodiptv.club", ignoreCase = true)) {
            douyuEntryHeaders(url)
        } else {
            douyuPlaybackHeaders()
        }
    }

    private fun extractRoomId(url: String): String? = roomIdRegex.find(url)?.groupValues?.getOrNull(1)

    /**
     * The old mobile ratestream endpoint is increasingly rejected with code 6
     * and the returned entry URL is commonly cut when Douyu rotates a live
     * segment.  Use the browser H5 signing flow first so a later refresh gets
     * a genuinely new signed stream for the same room.  Keep the legacy flow
     * as a compatibility fallback for older rooms/firmware networks.
     */
    private fun resolveOfficialStream(
        roomId: String,
        scheme: String,
        allowLegacyFallback: Boolean
    ): Result<OfficialStream> = runCatching {
        runCatching { resolveH5PlayStream(roomId, scheme) }.getOrElse { h5Error ->
            if (!allowLegacyFallback) throw h5Error
            Log.w(TAG, "Douyu H5 resolve failed for roomId=$roomId; trying legacy ratestream", h5Error)
            val legacyUrl = resolveLegacyOfficialStream(roomId).getOrThrow()
            OfficialStream(
                url = legacyUrl,
                mimeType = mimeTypeForUrl(legacyUrl),
                expiresAtMillis = parseExpiryMillis(legacyUrl)
            )
        }
    }

    private fun resolveH5PlayStream(roomId: String, scheme: String): OfficialStream {
        val roomPage = fetchWebRoomPage(roomId)
        val realRoomId = extractRealRoomId(roomPage.html).ifBlank { roomId }
        val did = fetchH5Did()
        val timestamp = networkNowMillis() / 1000L
        val encryptedJs = fetchEncryptedJs(realRoomId)
        val signedQuery = executeH5SigningScript(encryptedJs, realRoomId, did, timestamp)
        check(signedQuery.isNotBlank()) { "Douyu H5 signature is empty" }
        Log.d(TAG, "Generated Douyu H5 signature roomId=$realRoomId length=${signedQuery.length}")
        val data = requestH5Play(realRoomId, signedQuery)
        val rtmpUrl = data.get("rtmp_url")?.asString.orEmpty().trimEnd('/')
        val rtmpLive = data.get("rtmp_live")?.asString.orEmpty().trimStart('/')
        check(rtmpUrl.isNotBlank() && rtmpLive.isNotBlank()) {
            "Douyu H5 returned an empty stream address"
        }

        var streamUrl = "$rtmpUrl/$rtmpLive"
        // The low-end TV profile uses ExoPlayer's FLV path for live streams.
        // getH5Play may return a playlist URL even when the corresponding FLV
        // endpoint is available; renewing this URL later keeps the room ID and
        // signing context unchanged while avoiding a stale third-party entry.
        if (scheme != PreferencesHelper.DOUYU_SCHEME_PREVIEW &&
            streamUrl.contains("/playlist.m3u8?", ignoreCase = true)
        ) {
            streamUrl = streamUrl.replace("/playlist.m3u8?", ".flv?")
        }
        return OfficialStream(
            url = streamUrl,
            mimeType = mimeTypeForUrl(streamUrl),
            expiresAtMillis = parseExpiryMillis(streamUrl)
                ?: (System.currentTimeMillis() + 5 * 60 * 1000L)
        )
    }

    private data class WebRoomPage(
        val html: String,
        val serverTimeMillis: Long?
    )

    private fun fetchWebRoomPage(roomId: String): WebRoomPage {
        val request = Request.Builder()
            .url("$DOUYU_WEB_BASE_URL$roomId")
            .get()
            .headers(headersOf(douyuWebPageHeaders(roomId)))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 429) {
                throw ProtectionException(
                    "Douyu web room request temporarily rejected: HTTP ${response.code}"
                )
            }
            check(response.isSuccessful) { "Douyu web room request failed: HTTP ${response.code}" }
            val serverTimeMillis = response.header("Date")?.let(::parseHttpDateMillis)
            if (serverTimeMillis != null) {
                serverTimeOffsetMillis = serverTimeMillis - System.currentTimeMillis()
                Log.d(TAG, "Using Douyu server clock offset=${serverTimeOffsetMillis}ms")
            }
            return WebRoomPage(
                html = response.body?.string().orEmpty(),
                serverTimeMillis = serverTimeMillis
            )
        }
    }

    private fun parseHttpDateMillis(value: String): Long? {
        return runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(value)?.time
        }.getOrNull()
    }

    private fun networkNowMillis(): Long {
        return System.currentTimeMillis() + serverTimeOffsetMillis
    }

    private fun extractRealRoomId(html: String): String {
        val dollarRoom = Regex.escape("\$ROOM")
        val patterns = listOf(
            Regex("$dollarRoom\\.room_id\\s*=\\s*[\\\"']?(\\d+)"),
            Regex("\\\"room_id\\\"\\s*:\\s*\\\"?(\\d+)"),
            Regex("\\\"roomId\\\"\\s*:\\s*\\\"?(\\d+)"),
            Regex("room_id\\s*=\\s*\\\"?(\\d+)")
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }.orEmpty()
    }

    private fun fetchH5Did(): String {
        // The dynamic DID endpoint returns a value that is tied to browser
        // cookies.  This lightweight TV client does not keep that browser
        // cookie jar, and repeated requests are then rejected with HTTP 403.
        // Douyu's documented web fallback DID is accepted by getH5Play and is
        // stable across same-room renewals, which is exactly what continuity
        // recovery needs on Android 6/7.
        return DEFAULT_DID
    }

    private fun fetchEncryptedJs(roomId: String): String {
        val request = Request.Builder()
            .url("$HOME_H5_ENC_URL?rids=$roomId")
            .get()
            .headers(headersOf(mapOf(
                "User-Agent" to MOBILE_USER_AGENT,
                "Referer" to DOUYU_WEB_BASE_URL,
                "Accept" to "application/json, text/plain, */*"
            )))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 429) {
                throw ProtectionException(
                    "Douyu homeH5Enc temporarily rejected: HTTP ${response.code}"
                )
            }
            check(response.isSuccessful) { "Douyu homeH5Enc failed: HTTP ${response.code}" }
            val json = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            val script = json.getAsJsonObject("data")?.get("room$roomId")?.asString.orEmpty()
            check(script.isNotBlank()) { "Douyu homeH5Enc returned empty script" }
            return script
        }
    }

    private fun executeH5SigningScript(
        script: String,
        roomId: String,
        did: String,
        timestamp: Long
    ): String {
        require(script.length <= MAX_SIGNING_SCRIPT_LENGTH) {
            "Douyu signing script is too large"
        }
        val context = Context.enter()
        return try {
            context.optimizationLevel = -1
            context.languageVersion = Context.VERSION_ES6
            context.setClassShutter(ClassShutter { className -> className in allowedRhinoClasses })
            val scope: Scriptable = context.initStandardObjects()
            val bootstrap = """
                var window = this;
                var document = {};
                var navigator = { userAgent: "$MOBILE_USER_AGENT" };
                var location = { href: "$DOUYU_WEB_BASE_URL$roomId" };
                function __md5hex__(value) {
                    var MessageDigest = Packages.java.security.MessageDigest;
                    var JavaString = Packages.java.lang.String;
                    var IntegerCls = Packages.java.lang.Integer;
                    var bytes = new JavaString(String(value)).getBytes("UTF-8");
                    var digest = MessageDigest.getInstance("MD5").digest(bytes);
                    var out = "";
                    for (var i = 0; i < digest.length; i++) {
                        var b = (digest[i] + 256) % 256;
                        var hex = IntegerCls.toHexString(b);
                        out += (hex.length === 1 ? "0" + hex : hex);
                    }
                    return out;
                }
                var CryptoJS = {
                    MD5: function(value) {
                        return {
                            toString: function() {
                                return __md5hex__(value);
                            }
                        };
                    }
                };
            """.trimIndent()
            context.evaluateString(scope, bootstrap, "douyu-h5-bootstrap", 1, null)
            context.evaluateString(scope, script, "douyu-h5-sign", 1, null)
            val fn = scope.get("ub98484234", scope)
            require(fn is Function) { "Douyu H5 signing function missing" }
            val result = fn.call(context, scope, scope, arrayOf(roomId, did, timestamp.toString()))
            Context.toString(result)
        } finally {
            Context.exit()
        }
    }

    private fun requestH5Play(roomId: String, signedQuery: String): com.google.gson.JsonObject {
        // Current Douyu web playback expects the signature and stream options
        // in the URL query.  Keep the request POST-shaped with an empty body
        // (the endpoint rejects a missing Content-Length with HTTP 411).
        val query = signedQuery + "&cdn=&rate=0&iar=0&ive=0&hevc=0&fa=0"
        val requestBody = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url("$GET_H5_PLAY_URL$roomId?$query")
            .post(requestBody)
            .headers(headersOf(mapOf(
                "User-Agent" to MOBILE_USER_AGENT,
                "Referer" to "$DOUYU_WEB_BASE_URL$roomId",
                "Origin" to "https://www.douyu.com",
                "Accept" to "application/json, text/plain, */*",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 429) {
                val detail = response.body?.string()?.take(160).orEmpty()
                throw ProtectionException(
                    "Douyu getH5Play temporarily rejected: HTTP ${response.code}" +
                        if (detail.isBlank()) "" else " body=$detail"
                )
            }
            check(response.isSuccessful) { "Douyu getH5Play failed: HTTP ${response.code}" }
            val json = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            val error = json.get("error")?.asInt ?: -1
            check(error == 0) {
                "Douyu getH5Play error=$error msg=${json.get("msg")?.asString.orEmpty()}"
            }
            return json.getAsJsonObject("data")
                ?: error("Douyu getH5Play returned no data")
        }
    }

    private fun mimeTypeForUrl(url: String): String? = when {
        url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
        url.contains(".flv", ignoreCase = true) -> "video/x-flv"
        else -> null
    }

    private fun resolveLegacyOfficialStream(roomId: String): Result<String> = runCatching {
        val roomPageHtml = fetchRoomHtml(roomId)
        val signingScript = extractSigningScript(roomPageHtml)
        val did = generateDid(roomId)
        val timestamp = Instant.now().epochSecond.toString()
        val signedQuery = executeSigningScript(signingScript, roomId, did, timestamp)
        Log.d(TAG, "Generated Douyu signature roomId=$roomId query=$signedQuery")
        requestOfficialStream(roomId, signedQuery)
    }

    private fun fetchRoomHtml(roomId: String): String {
        val request = Request.Builder()
            .url("$DOUYU_MOBILE_BASE_URL/$roomId")
            .get()
            .headers(headersOf(douyuPageHeaders(roomId)))
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Douyu mobile room request failed: HTTP ${response.code}" }
            return response.body?.string().orEmpty()
        }
    }

    private fun extractSigningScript(html: String): String {
        Regex("""<script id="crp-stript">(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        throw IllegalStateException("Douyu signing script not found")
    }

    private fun executeSigningScript(script: String, roomId: String, did: String, timestamp: String): String {
        require(script.length <= MAX_SIGNING_SCRIPT_LENGTH) {
            "Douyu signing script is too large"
        }
        return try {
            val rhinoContext = Context.enter().apply {
                optimizationLevel = -1
                languageVersion = Context.VERSION_ES6
            }
            rhinoContext.setClassShutter(ClassShutter { className -> className in allowedRhinoClasses })
            val scope = rhinoContext.initStandardObjects()
            val bootstrap = """
                var window = this;
                var document = {};
                var navigator = { userAgent: "$MOBILE_USER_AGENT" };
                var location = { href: "$DOUYU_MOBILE_BASE_URL/$roomId" };
                function __md5hex__(value) {
                    var MessageDigest = Packages.java.security.MessageDigest;
                    var JavaString = Packages.java.lang.String;
                    var IntegerCls = Packages.java.lang.Integer;
                    var bytes = new JavaString(String(value)).getBytes("UTF-8");
                    var digest = MessageDigest.getInstance("MD5").digest(bytes);
                    var out = "";
                    for (var i = 0; i < digest.length; i++) {
                        var b = (digest[i] + 256) % 256;
                        var hex = IntegerCls.toHexString(b);
                        out += (hex.length === 1 ? "0" + hex : hex);
                    }
                    return out;
                }
                var CryptoJS = {
                    MD5: function(value) {
                        return {
                            toString: function() {
                                return __md5hex__(value);
                            }
                        };
                    }
                };
            """.trimIndent()
            rhinoContext.evaluateString(scope, bootstrap, "douyu-bootstrap", 1, null)
            rhinoContext.evaluateString(scope, script, "douyu-sign", 1, null)
            val fn = scope.get("ub98484234", scope)
            require(fn is Function) { "Douyu signing function missing" }
            val result = fn.call(rhinoContext, scope, scope, arrayOf(roomId, did, timestamp))
            Context.toString(result)
        } finally {
            Context.exit()
        }
    }

    private fun requestOfficialStream(roomId: String, signedQuery: String): String {
        val params = parseSignedQuery(signedQuery)
        val formBodyBuilder = FormBody.Builder()
        params.forEach { (key, value) -> formBodyBuilder.add(key, value) }
        formBodyBuilder.add("rid", roomId)
        formBodyBuilder.add("rate", "0")
        val request = Request.Builder()
            .url(DOUYU_RATE_STREAM_API)
            .post(formBodyBuilder.build())
            .headers(headersOf(douyuApiHeaders(roomId)))
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Douyu ratestream failed: HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            val json = JsonParser.parseString(body).asJsonObject
            val code = json.get("code")?.asInt ?: -1
            check(code == 0) { "Douyu ratestream error code=$code" }
            val streamUrl = json.getAsJsonObject("data")?.get("url")?.asString.orEmpty()
            check(streamUrl.isNotBlank()) { "Douyu ratestream returned empty url" }
            return streamUrl
        }
    }

    private fun parseSignedQuery(query: String): Map<String, String> {
        return query.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0 || index == part.lastIndex) {
                    null
                } else {
                    part.substring(0, index) to part.substring(index + 1)
                }
            }
            .toMap()
    }

    private fun headersOf(map: Map<String, String>): Headers {
        return Headers.headersOf(*map.flatMap { listOf(it.key, it.value) }.toTypedArray())
    }

    private fun generateDid(roomId: String): String {
        val seed = roomId + UUID.randomUUID().toString().replace("-", "")
        val digits = seed.filter { it.isDigit() }
        val padded = (digits + "10000000000000000000000000000000").take(32)
        return if (padded.any { !it.isDigit() }) {
            padded.map { if (it.isDigit()) it else '0' }.joinToString("")
        } else {
            padded
        }
    }

    private fun douyuPageHeaders(roomId: String): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Referer" to "$DOUYU_MOBILE_BASE_URL/$roomId",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    private fun douyuWebPageHeaders(roomId: String): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Referer" to "$DOUYU_WEB_BASE_URL$roomId",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    private fun douyuApiHeaders(roomId: String): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Referer" to "$DOUYU_MOBILE_BASE_URL/$roomId",
        "Origin" to DOUYU_MOBILE_BASE_URL,
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    private fun douyuPlaybackHeaders(roomId: String? = null): Map<String, String> {
        val referer = roomId?.let { "$DOUYU_MOBILE_BASE_URL/$it" } ?: DOUYU_MOBILE_BASE_URL
        return mapOf(
            "User-Agent" to MOBILE_USER_AGENT,
            "Referer" to referer,
            "Origin" to DOUYU_MOBILE_BASE_URL,
            "Accept" to "*/*"
        )
    }

    private fun douyuH5PlaybackHeaders(roomId: String): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_USER_AGENT,
        "Referer" to "$DOUYU_WEB_BASE_URL$roomId",
        "Origin" to "https://www.douyu.com",
        "Accept" to "*/*"
    )

    private fun douyuEntryHeaders(url: String? = null): Map<String, String> {
        return if (url != null && url.contains("goodiptv.club", ignoreCase = true)) {
            mapOf(
                "User-Agent" to MOBILE_USER_AGENT,
                "Referer" to "https://github.com/Kimentanm/aptv",
                "Accept" to "*/*",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
        } else {
            douyuPlaybackHeaders()
        }
    }

    private fun parseExpiryMillis(url: String): Long? {
        val txTimeHex = runCatching { Uri.parse(url).getQueryParameter("txTime") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return txTimeHex.toLongOrNull(16)?.times(1000L)
    }

    private fun currentScheme(): String {
        return runCatching { App.instance.preferences.douyuPlaybackScheme }
            .getOrDefault(PreferencesHelper.DOUYU_SCHEME_SMART)
    }

}
