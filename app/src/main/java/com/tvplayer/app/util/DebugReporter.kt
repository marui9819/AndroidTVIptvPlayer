package com.tvplayer.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.network.PlaybackStreamInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device diagnostics for playback and process failures.
 *
 * Reports are always written to the app-private diagnostics directory. They
 * are uploaded only when the user enables diagnostics and configures an HTTP
 * endpoint in Settings. A playback marker is intentionally kept until the
 * player Activity stops, so a native MediaCodec crash can be inferred on the
 * next app launch even when Java receives no exception callback.
 */
object DebugReporter {

    private const val TAG = "DebugReporter"
    private const val PREFS_NAME = "tvplayer_preferences"
    private const val INFLIGHT_KEY = "diagnostics_inflight_playback"
    private const val MAX_LOG_FILES = 80
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val READ_TIMEOUT_MS = 2_500
    private val gson = Gson()
    private val initialized = AtomicBoolean(false)
    private val reportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostics-worker").apply { isDaemon = true }
    }
    private val liveEventIds = AtomicLong(0L)
    private val liveEvents = BoundedPlaybackLogBuffer(maxEntries = 1_000, maxBytes = 1024 * 1024)
    private lateinit var appContext: Context
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    private val liveDetailKeys = setOf(
        "fullscreen", "active", "playerIdentity", "positionMs",
        "avPrecheck", "audioRecovery", "protectionAttempt", "protectionLimit", "candidateMustBePreconnected",
        "state", "playWhenReady", "isPlaying", "playbackPositionMs", "decoderMode",
        "requestedHuyaScheme",
        "resolvedBy", "errorCode", "errorName", "decoderName",
        "uriScheme", "uriHost", "bytesLoaded", "loadDurationMs", "dataSpecLength",
        "contentLengthBytes", "contentTypeHeader", "transferEncodingHeader", "contentRangeHeader",
        "dataSpecPosition",
        "responseVideoPackets", "responseAudioPackets", "overlappingVideoPackets",
        "overlappingAudioPackets", "newVideoPackets", "newAudioPackets", "newMediaDurationMs",
        "lastAudiblePcmAgeMs",
        "timelinePolicy", "timelineOffsetMs", "timelineCorrectionCount", "outputAudioVideoDtsDeltaMs",
        "videoPresentationTimeMs", "videoPresentationAgeMs", "videoVsPlaybackClockDeltaMs", "videoScheduledAheadMs",
        "firstVideoTimestampMs", "lastVideoTimestampMs", "firstAudioTimestampMs", "lastAudioTimestampMs",
        "firstKeyframeHash", "responseMediaDurationMs", "reconnectPrefixPolicy", "recoveryInProgress",
        "loadDataType", "trackType", "mediaStartTimeMs", "mediaEndTimeMs", "durationMs",
        "audioTrackCount", "audioPcmObserved", "renderedOutputBufferCount", "wasCanceled",
        "httpStatusCode", "httpErrorType", "recoveryAction",
        "decoderInitMs", "mimeType", "codecs", "sampleRate", "channelCount",
        "bitrate", "trackCount", "audioTracks", "underrunBufferSize",
        "underrunBufferMs", "elapsedSinceLastFeedMs", "truncated",
        "supportedTrackCount", "attempt", "recoveryAttempt", "recoveryAttemptLimit", "reason", "width", "height",
        "supportedAudioTrackCount", "supportedVideoTrackCount",
        "pixelWidthHeightRatio", "surfaceWidth", "surfaceHeight", "legacyTv",
        "surfaceFrameWidth", "surfaceFrameHeight", "playerViewWidth", "playerViewHeight",
        "playoutStartSystemTimeMs", "delayMs", "candidateFresh", "jobActive",
        "videoSizeFallbackActive",
        "audioPositionAdvanced", "prewarmedCandidateAvailable", "preconnectedCandidateAvailable",
        "playbackState", "videoTrackCount", "lastFrameAgeMs", "lastDecoderOutputAgeMs",
        "videoDecoderRenderedOutputBufferCount", "positionAdvancedThisCheck", "lastPositionProgressAgeMs",
        "refreshInProgress", "selectorVisible", "selectedGroupIndex", "selectedGroupName",
        "selectedChannelIndex", "selectedChannelName", "surfaceType",
        "currentResolvedBy", "previousResolvedBy", "recoveryDelayMs", "resolveElapsedMs", "renderTimeMs",
        "renewalFirstFrameLatencyMs", "sourceAgeMs",
        "stage", "renewalElapsedMs", "lastFrameToRenewalStartMs", "lastFrameToStageMs",
        "resolveToStageMs", "sourceApplyToStageMs",
        "mediaDurationMs", "sourceHttpResponseComplete", "parallelSameUrlRequest",
        "sameUrlReconnectBlocked", "reconnectBoundary", "reconnectMode", "preconnectWhilePlaying",
        "wasPrewarmed", "usedPreconnectedDataSource", "candidateAgeMs",
        "flvAudioFlag", "audioPayloadFound", "videoKeyframeFound", "prebufferBytes", "prebufferKeyframe", "preconnectElapsedMs",
        "frameCount", "processingOffsetUs",
        "successful", "reconnectCount", "responseBytes", "headerBytesSkipped",
        "dataSourceSessionId", "mediaId", "recoveryCause", "triggerErrorType", "recoveryElapsedMs",
        "pass",
        "errorType"
    )

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            reportUncaughtException(thread, throwable)
            previousUncaughtHandler?.uncaughtException(thread, throwable)
        }
        recoverPreviousPlaybackCrash()
    }

    fun beginPlayback(channel: Channel, decoderMode: String) {
        if (!initialized.get()) return
        recordLivePlaybackEvent(
            "playback_start",
            channel,
            mapOf("decoderMode" to decoderMode)
        )
        val marker = mapOf(
            "startedAt" to System.currentTimeMillis(),
            "state" to "preparing",
            "channelId" to channel.id,
            "channelName" to channel.name,
            "channelUrl" to channel.url,
            "group" to channel.group,
            "decoderMode" to decoderMode,
            "sdk" to Build.VERSION.SDK_INT
        )
        prefs().edit().putString(INFLIGHT_KEY, gson.toJson(marker)).apply()
    }

    fun updatePlaybackInfo(playbackInfo: PlaybackStreamInfo?, decoderMode: String) {
        val existing = readInflight() ?: return
        val updated = existing.toMutableMap()
        updated["resolvedUrl"] = playbackInfo?.url
        updated["resolvedBy"] = playbackInfo?.resolvedBy
        updated["mimeType"] = playbackInfo?.mimeType
        updated["decoderMode"] = decoderMode
        prefs().edit().putString(INFLIGHT_KEY, gson.toJson(updated)).apply()
    }

    fun markPlaybackReady() {
        val existing = readInflight() ?: return
        val updated = existing.toMutableMap()
        updated["state"] = "ready"
        updated["readyAt"] = System.currentTimeMillis()
        prefs().edit().putString(INFLIGHT_KEY, gson.toJson(updated)).apply()
    }

    /** Records a small, sanitized event for the bounded LAN live-log endpoint. */
    @Synchronized
    fun recordLivePlaybackEvent(
        eventType: String,
        channel: Channel?,
        details: Map<String, Any?> = emptyMap()
    ) {
        if (!initialized.get()) return
        val safeDetails = sanitizeLivePlaybackDetails(details)
        val event = linkedMapOf<String, Any?>(
            "id" to liveEventIds.incrementAndGet(),
            "timestamp" to System.currentTimeMillis(),
            "eventType" to eventType.take(64),
            "channelId" to channel?.id,
            "channelName" to channel?.name?.take(120),
            "details" to safeDetails
        )
        var json = gson.toJson(event)
        if (json.toByteArray(Charsets.UTF_8).size > MAX_LIVE_EVENT_BYTES) {
            event["details"] = mapOf("truncated" to true)
            json = gson.toJson(event)
        }
        liveEvents.append((event["id"] as Number).toLong(), json)
    }

    internal fun sanitizeLivePlaybackDetails(details: Map<String, Any?>): Map<String, Any?> =
        details.entries
            .filter { it.key in liveDetailKeys }
            .associate { it.key to sanitizeLiveValue(it.value) }

    internal fun readLivePlaybackEvents(afterId: Long, limit: Int): List<LivePlaybackLogEntry> =
        liveEvents.readAfter(afterId, limit)

    internal fun latestLivePlaybackEventId(): Long = liveEventIds.get()

    private fun sanitizeLiveValue(value: Any?, depth: Int = 0): Any? {
        if (depth > 2) return null
        return when (value) {
            null, is Number, is Boolean -> value
            is String -> value.take(160)
            is Map<*, *> -> value.entries
                .filter { (key, _) -> key is String && key !in SENSITIVE_LIVE_KEYS }
                .take(12)
                .associate { (key, item) -> key.toString().take(40) to sanitizeLiveValue(item, depth + 1) }
            is Iterable<*> -> value.take(8).map { sanitizeLiveValue(it, depth + 1) }
            else -> value.toString().take(160)
        }
    }

    fun endPlaybackSession() {
        if (!initialized.get()) return
        prefs().edit().remove(INFLIGHT_KEY).apply()
    }

    fun reportPlaybackError(
        phase: String,
        channel: Channel?,
        message: String,
        error: Throwable? = null,
        playbackInfo: PlaybackStreamInfo? = null,
        decoderMode: String? = null
    ) {
        reportAsync(
            eventType = "playback_error",
            details = mapOf(
                "phase" to phase,
                "channelId" to channel?.id,
                "channelName" to channel?.name,
                "channelUrl" to channel?.url,
                "group" to channel?.group,
                "message" to message,
                "resolvedUrl" to playbackInfo?.url,
                "resolvedBy" to playbackInfo?.resolvedBy,
                "mimeType" to playbackInfo?.mimeType,
                "decoderMode" to decoderMode,
                "errorClass" to error?.javaClass?.name,
                "stackTrace" to error?.let { Log.getStackTraceString(it) }
            )
        )
    }

    fun reportPlaybackIssue(
        phase: String,
        channel: Channel?,
        message: String,
        playbackInfo: PlaybackStreamInfo? = null,
        decoderMode: String? = null
    ) {
        reportPlaybackError(phase, channel, message, null, playbackInfo, decoderMode)
    }

    fun diagnosticsDirectory(): File {
        check(initialized.get()) { "DebugReporter has not been initialized" }
        return File(appContext.filesDir, "diagnostics").apply { mkdirs() }
    }

    fun clearDiagnostics(): Boolean {
        return runCatching {
            diagnosticsDirectory().listFiles()?.forEach { it.delete() }
            true
        }.getOrDefault(false)
    }

    private fun reportUncaughtException(thread: Thread, throwable: Throwable) {
        val event = buildEvent(
            eventType = "uncaught_exception",
            details = mapOf(
                "thread" to thread.name,
                "errorClass" to throwable.javaClass.name,
                "stackTrace" to Log.getStackTraceString(throwable),
                "inflightPlayback" to readInflight()
            )
        )
        writeEvent(event)
        postSyncIfConfigured(gson.toJson(event))
        // A Java exception has already been captured; avoid reporting it again
        // as a suspected native crash on the next launch.
        endPlaybackSession()
    }

    private fun recoverPreviousPlaybackCrash() {
        val marker = readInflight() ?: return
        val startedAt = (marker["startedAt"] as? Number)?.toLong() ?: 0L
        if (startedAt > 0L && System.currentTimeMillis() - startedAt > 24 * 60 * 60 * 1_000L) {
            endPlaybackSession()
            return
        }
        reportAsync(
            eventType = "suspected_process_crash",
            details = mapOf(
                "message" to "上一次播放会话未正常结束，疑似 native MediaCodec/OMX 或进程级崩溃",
                "inflightPlayback" to marker
            )
        )
        endPlaybackSession()
    }

    private fun reportAsync(eventType: String, details: Map<String, Any?>) {
        if (!initialized.get()) return
        val event = buildEvent(eventType, details)
        val payload = gson.toJson(event)
        // Playback callbacks arrive on the UI / ExoPlayer thread. Keep both
        // file retention and LAN upload off that path, and serialize reports
        // so repeated stream failures cannot create a burst of threads.
        reportExecutor.execute {
            writeEvent(event)
            postSyncIfConfigured(payload)
        }
    }

    private fun buildEvent(eventType: String, details: Map<String, Any?>): Map<String, Any?> {
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong()
        }
        return linkedMapOf(
            "eventType" to eventType,
            "timestamp" to System.currentTimeMillis(),
            "appVersion" to packageInfo?.versionName,
            "versionCode" to versionCode,
            "packageName" to appContext.packageName,
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "sdkInt" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "details" to details
        )
    }

    private fun writeEvent(event: Map<String, Any?>) {
        runCatching {
            val directory = diagnosticsDirectory()
            val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            val type = (event["eventType"] as? String).orEmpty().replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(directory, "${formatter.format(Date())}_$type.json")
            file.writeText(gson.toJson(event), Charsets.UTF_8)
            directory.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_LOG_FILES)
                ?.forEach { it.delete() }
        }.onFailure {
            Log.e(TAG, "Failed to write diagnostics event", it)
        }
    }

    private fun postSyncIfConfigured(payload: String) {
        val prefs = prefs()
        if (!prefs.getBoolean("diagnostics_enabled", false)) return
        val endpoint = prefs.getString(
            "diagnostics_server_url",
            Constants.DefaultSettings.DEFAULT_DIAGNOSTICS_SERVER_URL
        )?.trim().orEmpty()
        if (endpoint.isBlank()) return
        runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", Constants.USER_AGENT)
            }
            connection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
            }
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
        }.onFailure {
            Log.w(TAG, "Diagnostics upload failed: $endpoint", it)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readInflight(): MutableMap<String, Any?>? {
        val raw = prefs().getString(INFLIGHT_KEY, null) ?: return null
        return runCatching {
            gson.fromJson(raw, Map::class.java).toMutableMap() as MutableMap<String, Any?>
        }.getOrNull()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val MAX_LIVE_EVENT_BYTES = 2 * 1024
    private val SENSITIVE_LIVE_KEYS = setOf(
        "url", "uri", "resolvedUrl", "sourceUrl", "cookie", "cookies", "token",
        "authorization", "headers", "request", "requestUrl", "signedUrl"
    )
}
