package com.tvplayer.app.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.audio.AudioCapabilities
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.Extractor
import com.google.android.exoplayer2.extractor.ExtractorOutput
import com.google.android.exoplayer2.extractor.ExtractorsFactory
import com.google.android.exoplayer2.extractor.PositionHolder
import com.google.android.exoplayer2.extractor.SeekMap
import com.google.android.exoplayer2.extractor.TrackOutput
import com.google.android.exoplayer2.extractor.flv.FlvExtractor
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.util.MimeTypes
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import com.tvplayer.app.App
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.data.repository.LogoRepository
import com.tvplayer.app.data.repository.SubscriptionRepository
import com.tvplayer.app.network.DouyuStreamResolver
import com.tvplayer.app.network.HuyaStreamResolver
import com.tvplayer.app.network.PlaylistParser
import com.tvplayer.app.network.PlaybackStreamInfo
import com.tvplayer.app.ui.player.PlayerActivity
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun effectiveHuyaScheme(isLegacyTv: Boolean, requestedScheme: String): String =
    if (isLegacyTv && requestedScheme == PreferencesHelper.HUYA_SCHEME_HOT_SWAP) {
        PreferencesHelper.HUYA_SCHEME_SINGLE_FLV
    } else {
        requestedScheme
    }

/** Preserve the latest explicit recovery decision over the device's default scheme. */
internal fun huyaRecoverySchemeOverride(forceHls: Boolean, forceFlv: Boolean): String? = when {
    forceFlv -> PreferencesHelper.HUYA_SCHEME_SINGLE_FLV
    forceHls -> PreferencesHelper.HUYA_SCHEME_SINGLE_HLS
    else -> null
}

internal const val LEGACY_HUYA_FLV_HLS_FALLBACK_403_THRESHOLD = 3

internal fun shouldFallbackLegacyHuyaFlvToHlsAfterRejectedReconnect(
    isLegacyMstar: Boolean,
    statusCode: Int,
    rejectedReconnectCount: Int,
    hlsFallbackAlreadyAttempted: Boolean
): Boolean = isLegacyMstar && statusCode == 403 &&
    rejectedReconnectCount >= LEGACY_HUYA_FLV_HLS_FALLBACK_403_THRESHOLD &&
    !hlsFallbackAlreadyAttempted

internal fun shouldFallbackHuyaHlsToFlv(isHuyaHls: Boolean, httpStatusCode: Int?): Boolean =
    isHuyaHls && httpStatusCode != null && httpStatusCode !in 200..299

/** Retry transient FLV transport failures, but re-sign instead of hammering a rejected URL. */
internal fun shouldRetryLegacyHuyaFlvHttpStatus(statusCode: Int): Boolean =
    statusCode == 408 || statusCode == 429 || statusCode in 500..599

internal fun shouldRenewLegacyHuyaFlvHttpStatus(statusCode: Int): Boolean =
    statusCode in 400..499 && statusCode != 408 && statusCode != 429

/** Retry a broken streamed response only while its owning DataSource is still active. */
internal fun shouldReconnectLegacyHuyaFlvReadFailure(
    sourceSessionIsCurrent: Boolean,
    threadInterrupted: Boolean
): Boolean = sourceSessionIsCurrent && !threadInterrupted

/** Retry a transient first-request failure, but never replay a rejected signed URL unchanged. */
internal fun shouldRetryLegacyHuyaFlvOpen(
    isIoFailure: Boolean,
    httpStatusCode: Int?,
    sourceSessionIsCurrent: Boolean,
    threadInterrupted: Boolean,
    attempt: Int,
    maxAttempts: Int
): Boolean {
    if (!isIoFailure || !sourceSessionIsCurrent || threadInterrupted || attempt >= maxAttempts) {
        return false
    }
    return httpStatusCode == null || shouldRetryLegacyHuyaFlvHttpStatus(httpStatusCode)
}

internal fun legacyHuyaFlvOpenRetryDelayMs(retryAttempt: Int): Long = when {
    retryAttempt <= 1 -> 250L
    retryAttempt == 2 -> 600L
    else -> 1_200L
}

internal fun shouldAttemptLegacyHuyaInlineRenewal(
    statusCode: Int,
    providerAvailable: Boolean,
    renewalAttemptsForThisRecovery: Int,
    maxRenewalAttemptsPerRecovery: Int = 1
): Boolean = providerAvailable && renewalAttemptsForThisRecovery < maxRenewalAttemptsPerRecovery &&
    shouldRenewLegacyHuyaFlvHttpStatus(statusCode)

internal fun shouldAttemptLegacyHuyaReconnectCandidateRenewal(
    httpStatusCode: Int?,
    reconnectPayloadRejected: Boolean,
    providerAvailable: Boolean,
    renewalAttemptsForThisRecovery: Int,
    maxRenewalAttemptsPerRecovery: Int
): Boolean = providerAvailable && renewalAttemptsForThisRecovery < maxRenewalAttemptsPerRecovery &&
    (reconnectPayloadRejected || httpStatusCode?.let(::shouldRenewLegacyHuyaFlvHttpStatus) == true)

/** Pace repeated signed-URL rejections without abandoning the selected live channel. */
internal fun legacyHuyaHttpFailureBackoffMs(recoveryAttempt: Int): Long = when {
    recoveryAttempt <= 1 -> 350L
    recoveryAttempt == 2 -> 1_500L
    recoveryAttempt == 3 -> 3_000L
    recoveryAttempt == 4 -> 6_000L
    else -> 12_000L
}

/** MStar Android 6 can crash in Stagefright when a Huya retry detaches its Surface during codec flush. */
internal fun shouldKeepLegacyHuyaSurface(isLegacyTv: Boolean, resolvedBy: String): Boolean =
    isLegacyTv && resolvedBy.startsWith("huya:")

internal fun shouldDispatchDeferredPlaybackError(
    hasPendingError: Boolean,
    sameChannel: Boolean,
    userInterfaceOpen: Boolean,
    interactionCooldownExpired: Boolean
): Boolean = hasPendingError && sameChannel && !userInterfaceOpen && interactionCooldownExpired

internal fun shouldRecoverBehindLiveWindow(
    errorCode: Int,
    behindLiveWindowErrorCode: Int,
    isHlsSource: Boolean,
    cooldownExpired: Boolean
): Boolean = errorCode == behindLiveWindowErrorCode && isHlsSource && cooldownExpired

internal fun restoredSelectionIndex(
    previousKey: String?,
    newKeys: List<String>,
    groupUnchanged: Boolean,
    fallbackIndex: Int
): Int {
    if (!groupUnchanged || previousKey.isNullOrBlank()) return fallbackIndex
    return newKeys.indexOf(previousKey).takeIf { it >= 0 } ?: fallbackIndex
}

/** Resolve a RecyclerView's first-visible anchor by stable item key after a refresh/reorder. */
internal fun restoredScrollAnchorIndex(
    anchorKey: String?,
    newKeys: List<String>,
    fallbackIndex: Int? = null
): Int? {
    if (newKeys.isEmpty()) return null
    val keyedIndex = anchorKey?.takeIf { it.isNotBlank() }
        ?.let(newKeys::indexOf)
        ?.takeIf { it >= 0 }
    return keyedIndex ?: fallbackIndex?.coerceIn(0, newKeys.lastIndex)
}

/** Refresh/reorder must not scroll the TV cursor when its stable item is unchanged. */
internal fun shouldScrollToStableSelection(previousKey: String?, currentKey: String?): Boolean =
    !currentKey.isNullOrBlank() && currentKey != previousKey

internal fun isPcmActivityAfterActiveAudioStart(
    observedAtNanos: Long,
    activeAudioAdvancingAtNanos: Long
): Boolean = activeAudioAdvancingAtNanos > 0L && observedAtNanos >= activeAudioAdvancingAtNanos

internal fun restoredSelectionIndexForGroupRefresh(
    previousGroupName: String,
    nextGroupName: String,
    previousSelectedKey: String?,
    newChannelKeys: List<String>,
    fallbackIndex: Int
): Int = restoredSelectionIndex(
    previousKey = previousSelectedKey,
    newKeys = newChannelKeys,
    groupUnchanged = previousGroupName == nextGroupName,
    fallbackIndex = fallbackIndex
)

/** Find the next/previous playable row in the same visible category. */
internal fun adjacentAvailableChannelIndex(
    channelKeys: List<String>,
    availability: List<Boolean>,
    currentKey: String?,
    direction: Int
): Int? {
    if (channelKeys.isEmpty() || availability.size != channelKeys.size || direction == 0) return null
    val currentIndex = channelKeys.indexOf(currentKey)
    if (currentIndex < 0) return null
    val step = if (direction > 0) 1 else -1
    var index = currentIndex + step
    while (index in channelKeys.indices) {
        if (availability[index]) return index
        index += step
    }
    return null
}

internal fun restoredGroupIndex(previousName: String?, newNames: List<String>, fallbackIndex: Int): Int {
    if (previousName.isNullOrBlank()) return fallbackIndex.coerceIn(0, (newNames.lastIndex).coerceAtLeast(0))
    return newNames.indexOf(previousName).takeIf { it >= 0 }
        ?: fallbackIndex.coerceIn(0, (newNames.lastIndex).coerceAtLeast(0))
}

internal fun isCurrentSurfaceRepairRequest(
    requestGeneration: Long,
    activeGeneration: Long,
    requestChannelId: Long,
    activeChannelId: Long
): Boolean = requestGeneration == activeGeneration && requestChannelId == activeChannelId

internal fun surfaceFrameGeometryNeedsRepair(
    frameWidth: Int,
    frameHeight: Int,
    surfaceWidth: Int,
    surfaceHeight: Int,
    tolerance: Double = 0.02
): Boolean {
    if (frameWidth <= 0 || frameHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return false
    if (!tolerance.isFinite() || tolerance < 0.0) return false

    // Compare SurfaceHolder's buffer with the measured SurfaceView bounds, not
    // source video dimensions. A 4:3 stream may validly fill a 16:9 Surface;
    // source-aspect comparisons caused layout churn on every source switch.
    val widthDelta = kotlin.math.abs(frameWidth - surfaceWidth).toDouble() / surfaceWidth
    val heightDelta = kotlin.math.abs(frameHeight - surfaceHeight).toDouble() / surfaceHeight
    return widthDelta > tolerance || heightDelta > tolerance
}

internal fun hasSurfaceFrameGeometry(frameWidth: Int, frameHeight: Int): Boolean =
    frameWidth > 0 && frameHeight > 0

internal fun hasAudioTrackInFlvHeader(prefix: ByteArray): Boolean? {
    if (prefix.size < 5 ||
        prefix[0] != 'F'.code.toByte() ||
        prefix[1] != 'L'.code.toByte() ||
        prefix[2] != 'V'.code.toByte()
    ) return null
    return (prefix[4].toInt() and 0x04) != 0
}

internal const val FLV_PRECONNECT_MAX_BUFFER_BYTES = 1024 * 1024
internal const val FLV_PRECONNECT_READ_CHUNK_BYTES = 16 * 1024
internal const val FLV_PRECONNECT_CONNECT_TIMEOUT_MS = 3_000
internal const val FLV_PRECONNECT_READ_TIMEOUT_MS = 3_000
internal const val FLV_PRECONNECT_TOTAL_TIMEOUT_MS = 7_000L

internal fun flvPreconnectReadAheadBytes(audioFlag: Boolean?): Int =
    if (audioFlag == false) 9 else FLV_PRECONNECT_MAX_BUFFER_BYTES

internal fun shouldQueueLegacyHuyaRenewal(
    isLegacyTv: Boolean,
    isFlv: Boolean,
    audioFlag: Boolean?,
    hasAudioPayload: Boolean,
    hasVideoKeyframe: Boolean
): Boolean = isLegacyTv && isFlv && audioFlag != false && hasAudioPayload && hasVideoKeyframe

internal fun shouldTryHuyaSameUrlReconnect(
    isLegacyTv: Boolean,
    isFlv: Boolean,
    blockedForCurrentUrl: Boolean
): Boolean = isLegacyTv && isFlv && !blockedForCurrentUrl

internal fun shouldReconnectLegacyHuyaAtEof(
    isLegacyTv: Boolean,
    isFlv: Boolean,
    playbackEnded: Boolean,
    blockedForCurrentUrl: Boolean,
    reconnectInProgress: Boolean
): Boolean = isLegacyTv && isFlv && playbackEnded && !blockedForCurrentUrl &&
    !reconnectInProgress

internal fun shouldCutOverQueuedHuyaRenewal(
    sourceAgeMs: Long,
    targetSourceAgeMs: Long,
    hasQueuedCandidate: Boolean
): Boolean = hasQueuedCandidate && targetSourceAgeMs >= 0L && sourceAgeMs >= targetSourceAgeMs

/** Signed URL renewal must not invalidate a queued handoff while the MediaItem stays active. */
internal fun isSameQueuedHuyaPlaybackSession(
    requestedChannelId: Long,
    activeChannelId: Long,
    requestedMediaId: String?,
    activeMediaId: String?
): Boolean = requestedChannelId > 0L && requestedChannelId == activeChannelId &&
    !requestedMediaId.isNullOrBlank() && requestedMediaId == activeMediaId

internal const val LEGACY_VIDEO_JOINING_TIME_MS = 250L

internal fun videoJoiningTimeMs(isLegacyTv: Boolean): Long =
    if (isLegacyTv) LEGACY_VIDEO_JOINING_TIME_MS
    else DefaultRenderersFactory.DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS

/**
 * Detect a complete decodable FLV video keyframe in the bounded prefix. The
 * preconnect can then stop reading as soon as ExoPlayer has enough data to
 * render promptly, instead of cutting over in the middle of a GOP and waiting
 * for the next keyframe on the network.
 */
internal fun hasFlvVideoKeyframe(prefix: ByteArray, validLength: Int = prefix.size): Boolean {
    val length = validLength.coerceIn(0, prefix.size)
    if (length < 13 || prefix[0] != 'F'.code.toByte() ||
        prefix[1] != 'L'.code.toByte() || prefix[2] != 'V'.code.toByte()
    ) return false

    val dataOffset = ((prefix[5].toLong() and 0xff) shl 24) or
        ((prefix[6].toLong() and 0xff) shl 16) or
        ((prefix[7].toLong() and 0xff) shl 8) or
        (prefix[8].toLong() and 0xff)
    if (dataOffset < 9 || dataOffset > Int.MAX_VALUE - 4 || dataOffset + 4 > length) return false
    var tagOffset = dataOffset.toInt() + 4 // FLV header plus PreviousTagSize0

    while (tagOffset >= 0 && tagOffset.toLong() + 11L <= length) {
        val tagType = prefix[tagOffset].toInt() and 0xff
        val dataSize = ((prefix[tagOffset + 1].toInt() and 0xff) shl 16) or
            ((prefix[tagOffset + 2].toInt() and 0xff) shl 8) or
            (prefix[tagOffset + 3].toInt() and 0xff)
        val payloadOffset = tagOffset + 11
        val tagEnd = payloadOffset.toLong() + dataSize
        if (tagEnd > length) return false

        if (tagType == 9 && dataSize > 0) {
            val frameAndCodec = prefix[payloadOffset].toInt() and 0xff
            val frameType = frameAndCodec ushr 4
            val codecId = frameAndCodec and 0x0f
            // ExoPlayer 2.18.7's FLV extractor only understands AVC (codec 7).
            // Do not let Enhanced-FLV/unknown IDs reach the extractor as a
            // supposedly decodable keyframe (e.g. "format not supported: 14").
            // AVC packet type 1 carries NALU data; type 0 is only the
            // sequence header and cannot produce the first picture.
            if (frameType == 1 && codecId == 7 && dataSize >= 7 &&
                (prefix[payloadOffset + 1].toInt() and 0xff) == 1
            ) {
                return true
            }
        }

        val nextTagOffset = tagEnd + 4 // trailing PreviousTagSize
        if (nextTagOffset > length || nextTagOffset > Int.MAX_VALUE) return false
        tagOffset = nextTagOffset.toInt()
    }
    return false
}

/**
 * Detect actual decodable audio data, not just the FLV header's audio flag.
 * AAC sequence headers configure the decoder but contain no samples, so AAC
 * only counts once packet type 1 (raw AAC data) is present.
 */
internal fun flvDecodableAudioPayloadFormat(prefix: ByteArray, validLength: Int = prefix.size): Int? {
    val length = validLength.coerceIn(0, prefix.size)
    if (length < 13 || prefix[0] != 'F'.code.toByte() ||
        prefix[1] != 'L'.code.toByte() || prefix[2] != 'V'.code.toByte()
    ) return null

    val dataOffset = ((prefix[5].toLong() and 0xff) shl 24) or
        ((prefix[6].toLong() and 0xff) shl 16) or
        ((prefix[7].toLong() and 0xff) shl 8) or
        (prefix[8].toLong() and 0xff)
    if (dataOffset < 9 || dataOffset > Int.MAX_VALUE - 4 || dataOffset + 4 > length) return null
    var tagOffset = dataOffset.toInt() + 4 // FLV header plus PreviousTagSize0

    while (tagOffset >= 0 && tagOffset.toLong() + 11L <= length) {
        val tagType = prefix[tagOffset].toInt() and 0xff
        val dataSize = ((prefix[tagOffset + 1].toInt() and 0xff) shl 16) or
            ((prefix[tagOffset + 2].toInt() and 0xff) shl 8) or
            (prefix[tagOffset + 3].toInt() and 0xff)
        val payloadOffset = tagOffset + 11
        val tagEnd = payloadOffset.toLong() + dataSize
        if (tagEnd > length) return null
        val nextTagOffset = tagEnd + 4 // trailing PreviousTagSize
        if (nextTagOffset > length || nextTagOffset > Int.MAX_VALUE) return null

        if (tagType == 8 && dataSize > 1) {
            val soundFormat = (prefix[payloadOffset].toInt() and 0xff) ushr 4
            // ExoPlayer 2.18.7's FLV extractor only supports MP3 (2),
            // G.711 A-law (7), G.711 mu-law (8), and AAC (10). Do not call
            // Speex/Opus/unknown FLV tags playable audio: those candidates can
            // still be queued but later produce an unsupported-format error
            // or a silent renewal on the legacy TV.
            val hasDecodableSamples = when (soundFormat) {
                2, 7, 8 -> dataSize > 1
                10 -> dataSize > 2 && (prefix[payloadOffset + 1].toInt() and 0xff) == 1
                else -> false
            }
            if (hasDecodableSamples) return soundFormat
        }

        tagOffset = nextTagOffset.toInt()
    }
    return null
}

internal fun hasFlvAudioPayload(prefix: ByteArray, validLength: Int = prefix.size): Boolean =
    flvDecodableAudioPayloadFormat(prefix, validLength) != null

/** Extract the unsupported FLV video codec ID without retaining a signed URL or media payload. */
internal fun unsupportedFlvVideoFormatId(error: Throwable?): Int? {
    var current = error
    repeat(16) {
        val cause = current ?: return null
        if (cause.javaClass.simpleName == "UnsupportedFormatException") {
            val message = cause.message ?: return null
            val marker = "Video format not supported:"
            if (!message.startsWith(marker)) return null
            return message.removePrefix(marker).trim().toIntOrNull()
        }
        val next = cause.cause
        if (next === cause) return null
        current = next
    }
    return null
}

internal fun huyaMissingAudioRetryDelayMs(
    retryCount: Int,
    supportedAudioTrackCount: Int
): Long {
    val exponent = retryCount.coerceIn(0, 4)
    val exponentialBackoffMs = 1_500L shl exponent
    val audioRendererWarmupMs = if (supportedAudioTrackCount > 0) 8_000L else 1_500L
    return maxOf(exponentialBackoffMs, audioRendererWarmupMs).coerceAtMost(20_000L)
}

/**
 * Rejects a single delayed PCM callback as evidence that a newly-renewed FLV
 * has sound. The ExoPlayer audio processor has no MediaItem identity, so a
 * callback already queued on the playback thread can arrive just after a
 * source transition. Two spaced observations confirm actual activity in the
 * new source without delaying playback.
 */
internal class AudiblePcmEvidence(
    private val requiredSamples: Int = 2,
    private val minimumSpacingMs: Long = 500L,
    private val maximumGapMs: Long = 3_000L
) {
    private var sampleCount = 0
    private var lastObservationAtMs = 0L

    val observations: Int
        get() = sampleCount

    fun reset() {
        sampleCount = 0
        lastObservationAtMs = 0L
    }

    fun observe(nowMs: Long): Boolean {
        if (nowMs <= 0L) return false
        if (lastObservationAtMs > 0L) {
            if (nowMs <= lastObservationAtMs) return false
            val gapMs = nowMs - lastObservationAtMs
            if (gapMs < minimumSpacingMs) return false
            if (gapMs > maximumGapMs) sampleCount = 0
        }
        sampleCount++
        lastObservationAtMs = nowMs
        return sampleCount >= requiredSamples.coerceAtLeast(1)
    }
}

internal fun isHuyaRecoveryAttemptAllowed(attemptCount: Int, maxAttempts: Int?): Boolean =
    maxAttempts == null || attemptCount < maxAttempts

/** Prevent a second player-error path from racing an already-owned Huya recovery. */
internal fun shouldIgnoreHuyaPlaybackErrorDuringRecovery(
    isHuyaStream: Boolean,
    recoveryInProgress: Boolean
): Boolean = isHuyaStream && recoveryInProgress

internal fun shouldUseOkHttpForLegacyHuyaMedia(
    isLegacyTv: Boolean,
    isHuyaStream: Boolean,
    isFlv: Boolean
): Boolean = isLegacyTv && isHuyaStream && isFlv

internal fun shouldRejectPreconnectedUri(
    requestedUri: String,
    openedUri: String,
    preconnectedResponseConsumed: Boolean
): Boolean = !preconnectedResponseConsumed && requestedUri != openedUri

internal fun isFreshPreconnectedHttpResponse(
    openedAtElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
    maximumAgeMs: Long
): Boolean = openedAtElapsedRealtimeMs > 0L &&
    nowElapsedRealtimeMs >= openedAtElapsedRealtimeMs &&
    maximumAgeMs >= 0L &&
    nowElapsedRealtimeMs - openedAtElapsedRealtimeMs <= maximumAgeMs

internal fun shouldUseInlineHuyaRenewal(isLegacyHuyaFlv: Boolean): Boolean =
    isLegacyHuyaFlv

// The in-place FLV transport recovery can perform three bounded opens, each
// with an 8s HTTP timeout. Continuations no longer wait for a full keyframe
// and audio prefix before being returned to the active extractor.
// Keep the Activity watchdog from replacing the MediaSource before that path
// has had a chance to finish; still bound the wait if the endpoint is dead.
internal const val LEGACY_HUYA_TRANSPORT_RECOVERY_GRACE_MS = 30_000L

internal fun shouldDeferLegacyHuyaBufferingRecovery(
    isLegacyHuyaFlv: Boolean,
    reconnectStartedAtMs: Long,
    nowMs: Long,
    maximumGraceMs: Long
): Boolean {
    if (!isLegacyHuyaFlv || reconnectStartedAtMs <= 0L || nowMs < reconnectStartedAtMs) return false
    return nowMs - reconnectStartedAtMs < maximumGraceMs.coerceAtLeast(0L)
}

/** Preserve the first retry deadline until decoded playback actually resumes. */
internal fun legacyHuyaReconnectGraceStartedAtMs(
    currentStartedAtMs: Long,
    nowMs: Long,
    recoveryInProgress: Boolean
): Long {
    if (!recoveryInProgress || currentStartedAtMs > 0L || nowMs <= 0L) return currentStartedAtMs
    return nowMs
}

internal fun remainingLegacyHuyaReconnectGraceMs(
    reconnectStartedAtMs: Long,
    nowMs: Long,
    maximumGraceMs: Long
): Long {
    if (reconnectStartedAtMs <= 0L || nowMs < reconnectStartedAtMs) return 0L
    return (maximumGraceMs.coerceAtLeast(0L) - (nowMs - reconnectStartedAtMs)).coerceAtLeast(0L)
}

internal fun nextHuyaRecoveryAttempt(attemptCount: Int, maxAttempts: Int?): Int {
    val nextAttempt = if (attemptCount == Int.MAX_VALUE) Int.MAX_VALUE else attemptCount + 1
    return maxAttempts?.let { nextAttempt.coerceAtMost(it) } ?: nextAttempt
}

internal fun monitoredVideoTrackCount(
    supportedTrackCount: Int,
    videoSizeObserved: Boolean,
    isLegacyTv: Boolean,
    expectedVideoTrack: Boolean = false
): Int = supportedTrackCount.takeIf { it > 0 }
    ?: if (isLegacyTv && (videoSizeObserved || expectedVideoTrack)) 1 else 0

internal fun isCurrentMediaItemEvent(eventMediaId: String?, activeMediaId: String?): Boolean {
    if (activeMediaId.isNullOrBlank()) return true
    // Unknown timeline events are not proof that the active item produced
    // them. In particular, an old FLV audio callback can arrive just after a
    // media-source replacement and must not suppress the new item's silent-
    // audio recovery or video-frame stall detection.
    return !eventMediaId.isNullOrBlank() && eventMediaId == activeMediaId
}

internal fun shouldRecoverForVideoFrameStall(
    hasVideoTrack: Boolean,
    playbackReady: Boolean,
    playWhenReady: Boolean,
    lastRenderedFrameAtMs: Long,
    lastDecoderOutputAtMs: Long = 0L,
    monitoringStartedAtMs: Long,
    nowMs: Long,
    timeoutMs: Long
): Boolean {
    if (!hasVideoTrack || !playbackReady || !playWhenReady || timeoutMs <= 0L) return false
    // Vendor renderers may omit onVideoFrameProcessingOffset callbacks while
    // their video decoder continues producing output buffers.
    val lastProgressAtMs = maxOf(lastRenderedFrameAtMs, lastDecoderOutputAtMs).takeIf { it > 0L }
        ?: monitoringStartedAtMs
    return lastProgressAtMs > 0L && nowMs >= lastProgressAtMs &&
        nowMs - lastProgressAtMs >= timeoutMs
}

internal fun legacyHuyaRetryDelayMs(
    isLegacyMstar: Boolean,
    sourceRequestFailed: Boolean,
    normalDelayMs: Long,
    rejectedSourceDelayMs: Long
): Long = if (isLegacyMstar && sourceRequestFailed) {
    rejectedSourceDelayMs.coerceAtLeast(0L)
} else {
    normalDelayMs.coerceAtLeast(0L)
}

internal fun remainingSourceAgeDelayMs(
    sourceStartedAtMs: Long,
    nowMs: Long,
    targetSourceAgeMs: Long
): Long {
    if (sourceStartedAtMs <= 0L) return targetSourceAgeMs.coerceAtLeast(0L)
    val sourceAgeMs = (nowMs - sourceStartedAtMs).coerceAtLeast(0L)
    return (targetSourceAgeMs - sourceAgeMs).coerceAtLeast(0L)
}

/**
 * An HTTP response opened shortly before a live FLV renewal. Ownership is
 * transferred to ExoPlayer at the cutover so the TV can reuse the established
 * connection instead of paying DNS/TCP/TLS and first-response latency twice.
 */
internal class PreconnectedHttpDataSource(
    private val delegate: DataSource,
    private val openedUri: Uri,
    private val openedLength: Long,
    private val bufferedPrefix: ByteArray,
    val hasAudioTrackInFlvHeader: Boolean?,
    val containsVideoKeyframe: Boolean,
    val containsAudioPayload: Boolean,
    val audioPayloadFormat: Int?,
    /** Monotonic instant the HTTP response became available, before prefix read-ahead. */
    val openedAtElapsedRealtimeMs: Long
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var preconnectedResponseConsumed = false
    private var bufferedPrefixOffset = 0
    private var activeDataSpec: DataSpec? = null
    private var transferStarted = false
    private var transferEnded = false
    private var closed = false

    val bufferedBytes: Int
        get() = bufferedPrefix.size

    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListener in transferListeners) return
        transferListeners += transferListener
        // The HTTP delegate was opened and read ahead before ExoPlayer received
        // this wrapper. Registering a listener on that already-open delegate
        // can emit onTransferEnd without a matching onTransferStart and crash
        // DefaultBandwidthMeter on older ExoPlayer versions.
        activeDataSpec?.takeIf { transferStarted && !transferEnded }?.let { dataSpec ->
            transferListener.onTransferInitializing(this, dataSpec, true)
            transferListener.onTransferStart(this, dataSpec, true)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        if (shouldRejectPreconnectedUri(
                requestedUri = dataSpec.uri.toString(),
                openedUri = openedUri.toString(),
                preconnectedResponseConsumed = preconnectedResponseConsumed
            )
        ) {
            throw IOException("Preconnected source does not match the requested media item")
        }
        if (!preconnectedResponseConsumed && !closed && dataSpec.position == 0L) {
            // First handoff reuses the already-open HTTP response and its
            // read-ahead bytes, avoiding another DNS/TCP/TLS round trip.
            preconnectedResponseConsumed = true
            beginTransfer(dataSpec)
            return openedLength
        }

        // ProgressiveMediaPeriod may reopen a DataSource after an interrupted
        // load or seek. The preconnected response is single-use, but the source
        // itself must remain reusable: reopen the HTTP delegate at ExoPlayer's
        // requested byte position and do not replay the stale buffered prefix.
        if (!closed) close()
        bufferedPrefixOffset = bufferedPrefix.size
        val length = delegate.open(dataSpec)
        closed = false
        beginTransfer(dataSpec)
        return length
    }

    private fun beginTransfer(dataSpec: DataSpec) {
        activeDataSpec = dataSpec
        transferStarted = true
        transferEnded = false
        transferListeners.toList().forEach { listener ->
            listener.onTransferInitializing(this, dataSpec, true)
            listener.onTransferStart(this, dataSpec, true)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val remainingPrefix = bufferedPrefix.size - bufferedPrefixOffset
        if (remainingPrefix > 0) {
            val copied = minOf(length, remainingPrefix)
            System.arraycopy(bufferedPrefix, bufferedPrefixOffset, buffer, offset, copied)
            bufferedPrefixOffset += copied
            notifyBytesTransferred(copied)
            return copied
        }
        val read = delegate.read(buffer, offset, length)
        if (read > 0) notifyBytesTransferred(read)
        return read
    }

    private fun notifyBytesTransferred(bytes: Int) {
        val dataSpec = activeDataSpec ?: return
        if (!transferStarted || transferEnded || bytes <= 0) return
        transferListeners.toList().forEach { listener ->
            listener.onBytesTransferred(this, dataSpec, true, bytes)
        }
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        if (closed) return
        closed = true
        try {
            delegate.close()
        } finally {
            val dataSpec = activeDataSpec
            if (transferStarted && !transferEnded && dataSpec != null) {
                transferEnded = true
                transferListeners.toList().forEach { listener ->
                    listener.onTransferEnd(this, dataSpec, true)
                }
            }
        }
    }
}

/**
 * FFmpeg's streamed-HTTP reconnect behavior, adapted to ExoPlayer's progressive
 * FLV pipeline for the legacy MStar TV. A short HTTP FLV response, EOF, or
 * recoverable read IOException is not the end of the live program: reopen the
 * same signed URL (re-signing only when the server rejects it) and feed the
 * next response to the same extractor/player instead of finishing a MediaItem
 * and flushing the vendor decoder.
 */
internal class LegacyHuyaFlvContinuousDataSource(
    private val upstream: DataSource,
    private val onReconnect: (LegacyHuyaFlvReconnectEvent) -> Unit = {},
    private val reconnectGate: LegacyHuyaFlvReconnectGate = LegacyHuyaFlvReconnectGate(),
    private val renewRejectedUrl: (suspend (Int, String) -> PlaybackStreamInfo?)? = null,
    initialResolvedBy: String? = null
) : DataSource {
    private val lifecycle = LegacyHuyaFlvSessionLifecycle()
    private var initialDataSpec: DataSpec? = null
    @Volatile private var sessionGeneration: Long = 0L
    private var responseBytes = 0L
    private var emptyContinuationResponses = 0
    private var totalReconnects = 0
    private var sourceSessionId = 0L
    @Volatile private var activeResolvedBy: String? = initialResolvedBy
    @Volatile private var transportRecoveryInProgress = false
    private var pendingReconnectPayload: ByteArray? = null
    private var pendingReconnectPayloadOffset = 0
    private val flvFrames = FlvTagFrameReader()
    private val responseProgress = FlvResponseProgress()
    private val sharedTimeline = FlvSharedTimeline()
    private var responseOpenedAtMs = 0L
    private var fileHeaderDelivered = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val generation = lifecycle.beginSession()
        sessionGeneration = generation
        var activeDataSpec = dataSpec
        initialDataSpec = activeDataSpec
        responseBytes = 0L
        emptyContinuationResponses = 0
        totalReconnects = 0
        transportRecoveryInProgress = false
        pendingReconnectPayload = null
        pendingReconnectPayloadOffset = 0
        fileHeaderDelivered = false
        sharedTimeline.reset()
        responseProgress.reset()
        sourceSessionId = NEXT_SOURCE_SESSION_ID.incrementAndGet()
        var openAttempts = 0
        var inlineRenewalAttempts = 0
        var lastError: Exception? = null
        var lastHttpStatus: Int? = null
        var signedUrlRenewed = false

        while (openAttempts < MAX_INITIAL_OPEN_ATTEMPTS) {
            if (!lifecycle.isCurrent(generation)) {
                runCatching { upstream.close() }
                throw InterruptedIOException("Huya FLV source closed while opening")
            }
            if (openAttempts > 0) {
                SystemClock.sleep(legacyHuyaFlvOpenRetryDelayMs(openAttempts))
            }
            if (!lifecycle.isCurrent(generation) || Thread.currentThread().isInterrupted) {
                lifecycle.endSession(generation)
                runCatching { upstream.close() }
                throw InterruptedIOException("Huya FLV source closed before initial-open retry")
            }
            openAttempts++
            try {
                upstream.open(activeDataSpec)
                if (!lifecycle.isCurrent(generation)) {
                    runCatching { upstream.close() }
                    throw InterruptedIOException("Huya FLV source closed while opening")
                }
                initialDataSpec = activeDataSpec
                flvFrames.beginResponse(emitHeader = true)
                responseProgress.beginResponse()
                responseOpenedAtMs = SystemClock.elapsedRealtime()
                transportRecoveryInProgress = false
                // Mirror APTV's distinct first-open recovery path: transient
                // connect failures retry this request; a 401/403/404 receives
                // a fresh signature instead of hammering the rejected URL.
                if (openAttempts > 1 || signedUrlRenewed) {
                    reportReconnect(
                        LegacyHuyaFlvReconnectEvent(
                            successful = true,
                            reconnectCount = totalReconnects,
                            responseBytes = 0L,
                            headerBytesSkipped = 0,
                            errorType = null,
                            sourceSessionId = sourceSessionId,
                            httpStatusCode = lastHttpStatus,
                            resolvedBy = activeResolvedBy,
                            signedUrlRenewed = signedUrlRenewed,
                            recoveryCause = "initial_open",
                            triggerErrorType = lastError?.javaClass?.simpleName,
                            openAttempt = openAttempts
                        )
                    )
                }
                // A known Content-Length describes this response only, not
                // the duration of a live FLV item.
                return C.LENGTH_UNSET.toLong()
            } catch (error: Exception) {
                lastError = error
                lastHttpStatus = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                runCatching { upstream.close() }
                if (!lifecycle.isCurrent(generation) || Thread.currentThread().isInterrupted) {
                    lifecycle.endSession(generation)
                    throw error
                }

                // A later ExoPlayer load can fail while opening the same live
                // MediaItem (not only while reopening after an explicit EOF).
                // Publish this bounded in-place recovery too, so the Activity's
                // buffering watchdog cannot replace the source mid-retry.
                transportRecoveryInProgress = true
                reportReconnect(
                    LegacyHuyaFlvReconnectEvent(
                        successful = false,
                        reconnectCount = totalReconnects,
                        responseBytes = 0L,
                        headerBytesSkipped = 0,
                        errorType = error.javaClass.simpleName,
                        sourceSessionId = sourceSessionId,
                        httpStatusCode = lastHttpStatus,
                        resolvedBy = activeResolvedBy,
                        recoveryCause = "initial_open_retry_started",
                        triggerErrorType = error.javaClass.simpleName,
                        openAttempt = openAttempts,
                        recoveryInProgress = true
                    )
                )

                if (error is HttpDataSource.InvalidResponseCodeException &&
                    shouldAttemptLegacyHuyaInlineRenewal(
                        statusCode = error.responseCode,
                        providerAvailable = renewRejectedUrl != null,
                        renewalAttemptsForThisRecovery = inlineRenewalAttempts,
                        maxRenewalAttemptsPerRecovery = MAX_INLINE_RENEWAL_ATTEMPTS_PER_RECOVERY
                    )
                ) {
                    inlineRenewalAttempts++
                    SystemClock.sleep(legacyHuyaHttpFailureBackoffMs(inlineRenewalAttempts))
                    val renewedInfo = try {
                        runBlocking(Dispatchers.IO) {
                            renewRejectedUrl?.invoke(error.responseCode, activeDataSpec.uri.toString())
                        }
                    } catch (renewalError: Exception) {
                        lastError = renewalError
                        null
                    }
                    if (renewedInfo != null && renewedInfo.url.isNotBlank() &&
                        renewedInfo.mimeType == "video/x-flv" &&
                        renewedInfo.url != activeDataSpec.uri.toString() &&
                        lifecycle.isCurrent(generation)
                    ) {
                        activeDataSpec = activeDataSpec.buildUpon()
                            .setUri(Uri.parse(renewedInfo.url))
                            .setPosition(0L)
                            .setLength(C.LENGTH_UNSET.toLong())
                            .build()
                        initialDataSpec = activeDataSpec
                        activeResolvedBy = renewedInfo.resolvedBy
                        signedUrlRenewed = true
                        continue
                    }
                }

                if (!shouldRetryLegacyHuyaFlvOpen(
                        isIoFailure = error is IOException,
                        httpStatusCode = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode,
                        sourceSessionIsCurrent = lifecycle.isCurrent(generation),
                        threadInterrupted = Thread.currentThread().isInterrupted,
                        attempt = openAttempts,
                        maxAttempts = MAX_INITIAL_OPEN_ATTEMPTS
                    )
                ) break
            }
        }

        lifecycle.endSession(generation)
        transportRecoveryInProgress = false
        val failure = lastError ?: IOException("Huya FLV initial connection failed")
        reportReconnect(
            LegacyHuyaFlvReconnectEvent(
                successful = false,
                reconnectCount = totalReconnects,
                responseBytes = 0L,
                headerBytesSkipped = 0,
                errorType = failure.javaClass.simpleName,
                sourceSessionId = sourceSessionId,
                httpStatusCode = (failure as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    ?: lastHttpStatus,
                resolvedBy = activeResolvedBy,
                signedUrlRenewed = signedUrlRenewed,
                recoveryCause = "initial_open",
                triggerErrorType = failure.javaClass.simpleName,
                openAttempt = openAttempts
            )
        )
        throw if (failure is IOException) failure else IOException("Huya FLV initial connection failed", failure)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val generation = sessionGeneration
        while (true) {
            if (!lifecycle.isCurrent(generation)) return C.RESULT_END_OF_INPUT
            val pending = pendingReconnectPayload
            if (pending != null && pendingReconnectPayloadOffset < pending.size) {
                val count = minOf(length, pending.size - pendingReconnectPayloadOffset)
                System.arraycopy(pending, pendingReconnectPayloadOffset, buffer, offset, count)
                pendingReconnectPayloadOffset += count
                if (pendingReconnectPayloadOffset >= pending.size) {
                    if (pending.size >= 3 && pending[0] == 'F'.code.toByte() &&
                        pending[1] == 'L'.code.toByte() && pending[2] == 'V'.code.toByte()
                    ) fileHeaderDelivered = true
                    pendingReconnectPayload = null
                    pendingReconnectPayloadOffset = 0
                }
                return count
            }
            pendingReconnectPayload = null
            pendingReconnectPayloadOffset = 0
            val chunk = try {
                // Never expose a half tag to the extractor. A later HTTP
                // response starts a new tag and cannot finish the old payload.
                flvFrames.readChunk { target, targetOffset, targetLength ->
                    if (!lifecycle.isCurrent(generation)) throw InterruptedIOException("Huya FLV source retired")
                    upstream.read(target, targetOffset, targetLength)
                }
            } catch (error: IOException) {
                if (!lifecycle.isCurrent(generation)) return C.RESULT_END_OF_INPUT
                if (!shouldReconnectLegacyHuyaFlvReadFailure(
                        sourceSessionIsCurrent = lifecycle.isCurrent(generation),
                        threadInterrupted = Thread.currentThread().isInterrupted
                    )
                ) {
                    throw error
                }
                // FFmpeg-backed players treat a broken streamed HTTP response
                // as a reconnectable transport gap. ExoPlayer otherwise turns
                // this read IOException into a terminal source error, even
                // though the live FLV endpoint may recover at its current edge.
                reopenAfterTransportGap(generation, readFailure = error)
                if (!lifecycle.isCurrent(generation) || reconnectGate.isCancelled() ||
                    Thread.currentThread().isInterrupted
                ) {
                    return C.RESULT_END_OF_INPUT
                }
                continue
            }
            // close() can race with a blocking upstream read. Never deliver
            // bytes from a retired player/source after that read wakes up.
            if (!lifecycle.isCurrent(generation)) return C.RESULT_END_OF_INPUT
            if (chunk != null) {
                responseBytes += chunk.size
                if (!responseProgress.observe(chunk)) continue
                sharedTimeline.adjust(chunk)
                pendingReconnectPayload = chunk
                continue
            }
            reopenAfterTransportGap(generation)
            if (!lifecycle.isCurrent(generation) || reconnectGate.isCancelled() ||
                Thread.currentThread().isInterrupted
            ) {
                return C.RESULT_END_OF_INPUT
            }
        }
    }

    private fun reopenAfterTransportGap(generation: Long, readFailure: IOException? = null) {
        if (!lifecycle.isCurrent(generation)) return
        val originalSpec = initialDataSpec ?: run {
            if (!lifecycle.isCurrent(generation)) return
            throw IOException("Huya FLV reconnect has no initial DataSpec")
        }
        val endedResponseBytes = responseBytes
        val endedMedia = responseProgress.snapshot()
        val endedTimeline = sharedTimeline.snapshot()
        // This budget covers both a normal EOF and a truncated/failed tag.
        // Metadata/AAC configuration alone must not reset media progress.
        if (endedMedia.newVideoPackets == 0 && endedMedia.newAudioPackets == 0) {
            emptyContinuationResponses++
        } else emptyContinuationResponses = 0
        if (emptyContinuationResponses > MAX_EMPTY_CONTINUATION_RESPONSES) {
            transportRecoveryInProgress = false
            reportReconnect(LegacyHuyaFlvReconnectEvent(
                successful = false, reconnectCount = totalReconnects,
                responseBytes = endedResponseBytes, headerBytesSkipped = 0,
                errorType = "EOFException", sourceSessionId = sourceSessionId,
                resolvedBy = activeResolvedBy, responseMedia = endedMedia,
                recoveryCause = "no_media_progress", timeline = endedTimeline
            ))
            throw EOFException("Huya FLV responses made no media progress")
        }
        // A short live response can be downloaded far faster than its media
        // duration. Reopening immediately redownloads the same rolling GOP and
        // burns CPU/bandwidth; pace by media already delivered, on this loader
        // thread only, while the player consumes its existing buffer.
        val mediaPaceMs = if (endedMedia.newVideoPackets == 0 && endedMedia.newAudioPackets == 0) {
            (250L shl (emptyContinuationResponses - 1).coerceIn(0, 3)).coerceAtMost(2_000L)
        } else endedMedia.newMediaDurationMs
        val nextOpenAtMs = responseOpenedAtMs + mediaPaceMs.coerceAtMost(10_000L)
        while (lifecycle.isCurrent(generation) && !Thread.currentThread().isInterrupted &&
            SystemClock.elapsedRealtime() < nextOpenAtMs
        ) {
            SystemClock.sleep(minOf(100L, nextOpenAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
        }
        if (!lifecycle.isCurrent(generation) || Thread.currentThread().isInterrupted) return
        transportRecoveryInProgress = true
        reportReconnect(
            LegacyHuyaFlvReconnectEvent(
                successful = false,
                reconnectCount = totalReconnects,
                responseBytes = endedResponseBytes,
                headerBytesSkipped = 0,
                errorType = null,
                sourceSessionId = sourceSessionId,
                resolvedBy = activeResolvedBy,
                responseMedia = endedMedia,
                timeline = endedTimeline,
                recoveryCause = "transport_reconnect_started",
                recoveryInProgress = true
            )
        )
        var lastError: Exception? = null
        var continuationSpec = originalSpec
        var inlineRenewalAttempts = 0
        var signedUrlRenewed = false
        var rejectedHttpStatus: Int? = null
        for (attempt in 1..MAX_REOPEN_ATTEMPTS) {
            if (!lifecycle.isCurrent(generation)) return
            runCatching { upstream.close() }
            if (!lifecycle.isCurrent(generation)) return
            // A queued candidate is validated and buffered while the current
            // item plays. Pause it at EOF instead of downloading a second live
            // stream; the owner opens this gate only when cutting over.
            if (!reconnectGate.awaitPermission { lifecycle.isCurrent(generation) }) return
            if (attempt > 1) SystemClock.sleep(REOPEN_BACKOFF_MS * (1L shl (attempt - 2)))
            if (!lifecycle.isCurrent(generation)) return
            try {
                upstream.open(continuationSpec)
                val responseOpenedAt = SystemClock.elapsedRealtime()
                // ExoPlayer may release a MediaSource while open() is blocked.
                // A successful HTTP open after that release must not revive
                // the old stream; close it immediately and let read() return EOF.
                if (!lifecycle.isCurrent(generation)) {
                    runCatching { upstream.close() }
                    return
                }
                val validatedPrefix = readValidatedReconnectPrefix(generation)
                if (!lifecycle.isCurrent(generation)) {
                    runCatching { upstream.close() }
                    return
                }
                totalReconnects++
                responseBytes = validatedPrefix.totalBytes.toLong()
                responseProgress.beginResponse()
                sharedTimeline.beginResponse()
                val firstTagIsNew = responseProgress.observe(validatedPrefix.payload)
                if (firstTagIsNew) sharedTimeline.adjust(validatedPrefix.payload)
                responseOpenedAtMs = responseOpenedAt
                pendingReconnectPayload = validatedPrefix.payload.takeIf { firstTagIsNew }
                pendingReconnectPayloadOffset = 0
                transportRecoveryInProgress = false
                reportReconnect(
                    LegacyHuyaFlvReconnectEvent(
                        successful = true,
                        reconnectCount = totalReconnects,
                        responseBytes = endedResponseBytes,
                        headerBytesSkipped = validatedPrefix.headerBytesSkipped,
                        errorType = null,
                        sourceSessionId = sourceSessionId,
                        httpStatusCode = rejectedHttpStatus,
                        resolvedBy = activeResolvedBy,
                        signedUrlRenewed = signedUrlRenewed,
                        recoveryCause = if (readFailure != null) "read_io_error" else "http_eof",
                        triggerErrorType = readFailure?.javaClass?.simpleName,
                        prefixHasAudioPayload = validatedPrefix.hasAudioPayload,
                        prefixHasVideoKeyframe = validatedPrefix.hasVideoKeyframe,
                        prefixBytes = validatedPrefix.totalBytes
                    )
                )
                return
            } catch (error: Exception) {
                if (!lifecycle.isCurrent(generation)) {
                    runCatching { upstream.close() }
                    return
                }
                lastError = error
                runCatching { upstream.close() }
                val responseCode = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val rejectedPayload = error as? HuyaFlvReconnectPayloadRejectedException
                val rejectedPrefix = rejectedPayload != null
                reportReconnect(
                    LegacyHuyaFlvReconnectEvent(
                        successful = false,
                        reconnectCount = totalReconnects,
                        responseBytes = endedResponseBytes,
                        headerBytesSkipped = 0,
                        errorType = error.javaClass.simpleName,
                        sourceSessionId = sourceSessionId,
                        httpStatusCode = responseCode,
                        resolvedBy = activeResolvedBy,
                        recoveryCause = "reconnect_candidate_rejected",
                        triggerErrorType = readFailure?.javaClass?.simpleName,
                        recoveryInProgress = true,
                        prefixHasAudioPayload = rejectedPayload?.hasAudioPayload,
                        prefixHasVideoKeyframe = rejectedPayload?.hasVideoKeyframe,
                        prefixBytes = rejectedPayload?.prefixBytes
                    )
                )
                val shouldRenewCandidate = shouldAttemptLegacyHuyaReconnectCandidateRenewal(
                    httpStatusCode = responseCode,
                    reconnectPayloadRejected = rejectedPrefix,
                    providerAvailable = renewRejectedUrl != null,
                    renewalAttemptsForThisRecovery = inlineRenewalAttempts,
                    maxRenewalAttemptsPerRecovery = MAX_INLINE_RENEWAL_ATTEMPTS_PER_RECOVERY
                )
                if (shouldRenewCandidate) {
                    inlineRenewalAttempts++
                    rejectedHttpStatus = responseCode
                    SystemClock.sleep(legacyHuyaHttpFailureBackoffMs(inlineRenewalAttempts))
                    val renewedInfo = try {
                        runBlocking(Dispatchers.IO) {
                            renewRejectedUrl?.invoke(responseCode ?: 0, continuationSpec.uri.toString())
                        }
                    } catch (renewalError: Exception) {
                        lastError = renewalError
                        null
                    }
                    if (renewedInfo != null && renewedInfo.url.isNotBlank() &&
                        renewedInfo.mimeType == "video/x-flv" &&
                        renewedInfo.url != continuationSpec.uri.toString() &&
                        lifecycle.isCurrent(generation)
                    ) {
                        continuationSpec = continuationSpec.buildUpon()
                            .setUri(Uri.parse(renewedInfo.url))
                            .setPosition(0L)
                            .setLength(C.LENGTH_UNSET.toLong())
                            .build()
                        initialDataSpec = continuationSpec
                        activeResolvedBy = renewedInfo.resolvedBy
                        signedUrlRenewed = true
                        continue
                    }
                    // Do not hammer the same rejected URL or repeatedly resolve
                    // signatures inside a single transport recovery window.
                    break
                }
                if (rejectedPrefix) break
                if (error is HttpDataSource.InvalidResponseCodeException &&
                    !shouldRetryLegacyHuyaFlvHttpStatus(error.responseCode)
                ) {
                    // A signed Huya 401/403/404 is not a transient disconnect. Stop
                    // issuing the same rejected URL; let PlayerActivity resolve
                    // a fresh signature/CDN route instead.
                    break
                }
            }
        }
        if (!lifecycle.isCurrent(generation)) return
        val failure = lastError ?: IOException("Huya FLV transport reconnect failed")
        transportRecoveryInProgress = false
        reportReconnect(
            LegacyHuyaFlvReconnectEvent(
                successful = false,
                reconnectCount = totalReconnects,
                responseBytes = responseBytes,
                headerBytesSkipped = 0,
                errorType = failure.javaClass.simpleName,
                sourceSessionId = sourceSessionId,
                httpStatusCode = (failure as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    ?: rejectedHttpStatus,
                resolvedBy = activeResolvedBy,
                signedUrlRenewed = signedUrlRenewed,
                recoveryCause = if (readFailure != null) "read_io_error" else "http_eof",
                triggerErrorType = readFailure?.javaClass?.simpleName,
                prefixHasAudioPayload = (failure as? HuyaFlvReconnectPayloadRejectedException)?.hasAudioPayload,
                prefixHasVideoKeyframe = (failure as? HuyaFlvReconnectPayloadRejectedException)?.hasVideoKeyframe,
                prefixBytes = (failure as? HuyaFlvReconnectPayloadRejectedException)?.prefixBytes
            )
        )
        throw if (failure is IOException) failure else IOException("Huya FLV transport reconnect failed", failure)
    }

    /** Validate a new response and keep its first complete tag, never a partial packet. */
    private fun readValidatedReconnectPrefix(generation: Long): ValidatedHuyaFlvReconnectPrefix {
        val deadlineMs = SystemClock.elapsedRealtime() + RECONNECT_PREFIX_VALIDATION_TIMEOUT_MS
        flvFrames.beginResponse(emitHeader = !fileHeaderDelivered)
        val firstTag = try { flvFrames.readChunk { target, offset, length ->
                if (!lifecycle.isCurrent(generation) || Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("Huya FLV reconnect cancelled during prefix validation")
                }
                if (SystemClock.elapsedRealtime() >= deadlineMs) {
                    throw SocketTimeoutException("Huya FLV reconnect prefix validation timed out")
                }
                upstream.read(target, offset, length)
        } } catch (_: MalformedFlvException) {
            throw HuyaFlvReconnectPayloadRejectedException(false, false, 0)
        } ?: throw EOFException("Empty Huya FLV reconnect response")
        val videoKeyframe = firstTag.size > 20 && firstTag[0].toInt() == 9 &&
            (firstTag[11].toInt() and 0xff) == 0x17 && firstTag[12].toInt() == 1
        val audioPayload = firstTag.size > 17 && firstTag[0].toInt() == 8 &&
            (firstTag[11].toInt() and 0xf0) == 0xa0 && firstTag[12].toInt() == 1
        return ValidatedHuyaFlvReconnectPrefix(
            headerBytesSkipped = if (fileHeaderDelivered) flvFrames.lastHeaderBytes else 0,
            payload = firstTag,
            hasAudioPayload = audioPayload,
            hasVideoKeyframe = videoKeyframe,
            totalBytes = (if (fileHeaderDelivered) flvFrames.lastHeaderBytes else 0) + firstTag.size
        )
    }

    private fun reportReconnect(event: LegacyHuyaFlvReconnectEvent) {
        runCatching { onReconnect(event) }.onFailure { error ->
            // Never log a signed URL through the callback's exception message.
            Log.w("PlayerHelper", "Huya reconnect callback failed: ${error.javaClass.simpleName}")
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        lifecycle.endCurrentSession()
        if (transportRecoveryInProgress) {
            transportRecoveryInProgress = false
            reportReconnect(
                LegacyHuyaFlvReconnectEvent(
                    successful = false,
                    reconnectCount = totalReconnects,
                    responseBytes = responseBytes,
                    headerBytesSkipped = 0,
                    errorType = null,
                    sourceSessionId = sourceSessionId,
                    resolvedBy = activeResolvedBy,
                    recoveryCause = "transport_reconnect_cancelled"
                )
            )
        }
        sessionGeneration = 0L
        initialDataSpec = null
        pendingReconnectPayload = null
        pendingReconnectPayloadOffset = 0
        // Always close upstream. A concurrent open/reopen may have completed
        // after the prior close began; the generation checks above close that
        // late connection again before returning to ExoPlayer.
        upstream.close()
    }

    companion object {
        private val NEXT_SOURCE_SESSION_ID = AtomicLong(0L)
        private const val MAX_INITIAL_OPEN_ATTEMPTS = 3
        private const val MAX_REOPEN_ATTEMPTS = 3
        private const val MAX_INLINE_RENEWAL_ATTEMPTS_PER_RECOVERY = 2
        private const val MAX_EMPTY_CONTINUATION_RESPONSES = 8
        private const val REOPEN_BACKOFF_MS = 120L
        private const val FLV_HEADER_BYTES = 9
        private const val FLV_PREVIOUS_TAG_SIZE_BYTES = 4
        private const val MAX_FLV_HEADER_BYTES = 64 * 1024
        private const val RECONNECT_PREFIX_VALIDATION_TIMEOUT_MS = 5_000L
    }
}

private data class ValidatedHuyaFlvReconnectPrefix(
    val headerBytesSkipped: Int,
    val payload: ByteArray,
    val hasAudioPayload: Boolean,
    val hasVideoKeyframe: Boolean,
    val totalBytes: Int
)

private class HuyaFlvReconnectPayloadRejectedException(
    val hasAudioPayload: Boolean,
    val hasVideoKeyframe: Boolean,
    val prefixBytes: Int
) : IOException("Huya FLV reconnect response has an invalid container prefix")

/** Generation guard for close-vs-open races in ExoPlayer's loader thread. */
internal class LegacyHuyaFlvSessionLifecycle {
    private val generation = AtomicLong(0L)

    fun beginSession(): Long = generation.incrementAndGet()

    fun endSession(session: Long) {
        if (session != 0L) generation.compareAndSet(session, session + 1L)
    }

    fun endCurrentSession() {
        generation.incrementAndGet()
    }

    fun isCurrent(session: Long): Boolean = session != 0L && generation.get() == session
}

internal data class PlaybackBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
)

internal data class MediaHttpTimeoutProfile(
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int
)

internal fun mediaHttpTimeoutProfile(isLegacyHuyaFlv: Boolean): MediaHttpTimeoutProfile =
    if (isLegacyHuyaFlv) {
        // Surface/source watchdogs on MStar fire at 15 seconds. A longer
        // socket read timeout lets a stalled streamed FLV preempt same-source
        // reconnect and forces a disruptive PlayerActivity renewal first.
        MediaHttpTimeoutProfile(connectTimeoutMs = 8_000, readTimeoutMs = 8_000)
    } else {
        MediaHttpTimeoutProfile(connectTimeoutMs = 15_000, readTimeoutMs = 20_000)
    }

internal fun playbackBufferProfile(isLegacyTv: Boolean): PlaybackBufferProfile =
    if (isLegacyTv) {
        // Maintain enough decoded/read-ahead headroom for the short HTTP FLV
        // responses observed on MStar, while keeping the memory target below
        // 8 MiB in createPlayer(). A 250 ms rebuffer threshold caused repeated
        // AudioTrack underruns during otherwise recoverable EOF reconnects.
        PlaybackBufferProfile(
            minBufferMs = 3_000,
            maxBufferMs = 12_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 2_000
        )
    } else {
        PlaybackBufferProfile(
            minBufferMs = 5_000,
            maxBufferMs = 30_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000
        )
    }

internal data class LegacyHuyaFlvReconnectEvent(
    val successful: Boolean,
    val reconnectCount: Int,
    val responseBytes: Long,
    val headerBytesSkipped: Int,
    val errorType: String?,
    val sourceSessionId: Long = 0L,
    val httpStatusCode: Int? = null,
    val resolvedBy: String? = null,
    val signedUrlRenewed: Boolean = false,
    val recoveryCause: String? = null,
    val triggerErrorType: String? = null,
    val openAttempt: Int? = null,
    val recoveryInProgress: Boolean = false,
    val prefixHasAudioPayload: Boolean? = null,
    val prefixHasVideoKeyframe: Boolean? = null,
    val prefixBytes: Int? = null,
    val responseMedia: FlvResponseProgressSnapshot? = null,
    val timeline: FlvSharedTimelineSnapshot? = null
)

internal fun repeatedFlvHeaderBytesToSkip(header: ByteArray): Int? {
    if (header.size < 9 || header[0] != 'F'.code.toByte() ||
        header[1] != 'L'.code.toByte() || header[2] != 'V'.code.toByte()
    ) return null
    val dataOffset = ((header[5].toInt() and 0xff) shl 24) or
        ((header[6].toInt() and 0xff) shl 16) or
        ((header[7].toInt() and 0xff) shl 8) or
        (header[8].toInt() and 0xff)
    if (dataOffset !in 9..(64 * 1024)) return null
    return dataOffset + 4
}

/** Return the byte count for a complete, structurally valid FLV header. */
internal fun legacyHuyaReconnectHeaderBytesToSkip(
    prefix: ByteArray,
    validLength: Int = prefix.size
): Int? {
    val length = validLength.coerceIn(0, prefix.size)
    if (length < 13 || prefix[3].toInt() != 1) return null
    val bytesToSkip = repeatedFlvHeaderBytesToSkip(prefix) ?: return null
    if (length < bytesToSkip) return null
    // PreviousTagSize0 is required to be zero; reject arbitrary bodies that
    // merely happen to begin with the FLV signature.
    for (index in bytesToSkip - 4 until bytesToSkip) {
        if (prefix[index].toInt() != 0) return null
    }
    return bytesToSkip
}

/** FLV timing is preserved; only finite response metadata is hidden for live playback. */
private class LegacyHuyaFlvLiveExtractor : Extractor {
    private val delegate = FlvExtractor()

    override fun sniff(input: com.google.android.exoplayer2.extractor.ExtractorInput): Boolean =
        delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)

            override fun endTracks() = output.endTracks()

            override fun seekMap(seekMap: SeekMap) {
                // Huya responses are live chunks. Do not expose the first
                // response's short metadata duration as the live item's end.
                output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
            }
        })
    }

    override fun read(
        input: com.google.android.exoplayer2.extractor.ExtractorInput,
        seekPosition: PositionHolder
    ): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()
}

/** Holds a queued live FLV at EOF until that media item becomes the active route. */
internal class LegacyHuyaFlvReconnectGate(initiallyEnabled: Boolean = true) {
    @Volatile private var enabled = initiallyEnabled
    @Volatile private var cancelled = false

    fun enable() {
        if (!cancelled) enabled = true
    }

    fun cancel() {
        cancelled = true
    }

    fun isCancelled(): Boolean = cancelled

    fun awaitPermission(isSessionActive: () -> Boolean): Boolean {
        while (!enabled && !cancelled && isSessionActive()) {
            try {
                Thread.sleep(GATE_POLL_INTERVAL_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return enabled && !cancelled && isSessionActive()
    }

    private companion object {
        const val GATE_POLL_INTERVAL_MS = 100L
    }
}

private class LegacyHuyaFlvReconnectDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val onReconnect: (LegacyHuyaFlvReconnectEvent) -> Unit,
    private val reconnectGate: LegacyHuyaFlvReconnectGate,
    private val renewRejectedUrl: (suspend (Int, String) -> PlaybackStreamInfo?)? = null,
    private val initialResolvedBy: String? = null
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        LegacyHuyaFlvContinuousDataSource(
            upstreamFactory.createDataSource(),
            onReconnect,
            reconnectGate,
            renewRejectedUrl,
            initialResolvedBy
        )
}

private class PreconnectedFirstDataSourceFactory(
    private val first: DataSource,
    private val fallback: DataSource.Factory
) : DataSource.Factory {
    private var supplied = false

    @Synchronized
    override fun createDataSource(): DataSource {
        if (!supplied) {
            supplied = true
            return first
        }
        return fallback.createDataSource()
    }
}

object PlayerHelper {

    private const val TAG = "PlayerHelper"
    private const val FLV_MIME_TYPE = "video/x-flv"
    private const val TS_MIME_TYPE = "video/mp2t"
    private val loadMutex = Mutex()
    private val legacyHuyaMediaClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_1_1))
            // Keep retry ownership in the same-channel recovery state machine;
            // hidden client retries can multiply signed-URL requests on MStar.
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun createPlayer(
        context: Context,
        useHardwareAcceleration: Boolean,
        decoderMode: String = PreferencesHelper.CHANNEL_DECODER_DEFAULT,
        onAudiblePcm: (Long) -> Unit = {}
    ): ExoPlayer {
        val isLegacyTv = DeviceProfile.isLegacyTv(context)
        val preferSoftwareDecoding = !useHardwareAcceleration ||
            decoderMode == PreferencesHelper.CHANNEL_DECODER_COMPATIBILITY
        val baseRenderersFactory = if (isLegacyTv) {
            LegacyPcmMonitoringRenderersFactory(context, onAudiblePcm)
        } else {
            DefaultRenderersFactory(context)
        }
        val renderersFactory = baseRenderersFactory
            // Falling back from a failing hardware decoder to software is useful
            // on phones, but on MStar TV it can exhaust the CPU and native video
            // buffers while the hardware codec is still being torn down.
            .setEnableDecoderFallback(!isLegacyTv)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            // This is a maximum video-renderer join grace, not a timer. A
            // long grace can leave the previous Surface frame displayed while
            // a renewed FLV waits for its first keyframe. Keep a shorter
            // 750ms bridge on MStar; the prebuffered keyframe should make the
            // transition prompt, while the frame watchdog handles real stalls.
            .setAllowedVideoJoiningTimeMs(
                videoJoiningTimeMs(isLegacyTv)
            )
            .setMediaCodecSelector(
                MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )
                    if (!preferSoftwareDecoding) {
                        infos
                    } else {
                        infos.sortedBy { if (it.softwareOnly) 0 else 1 }
                    }
                }
            )
            .apply {
                // Some Android 7 TV vendor codecs are unstable with the
                // asynchronous MediaCodec queue used by newer ExoPlayer builds.
                if (isLegacyTv) {
                    forceDisableMediaCodecAsynchronousQueueing()
                }
            }
        val bufferProfile = playbackBufferProfile(isLegacyTv)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferProfile.minBufferMs,
                bufferProfile.maxBufferMs,
                bufferProfile.bufferForPlaybackMs,
                bufferProfile.bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(
                if (isLegacyTv) 8 * 1024 * 1024 else com.google.android.exoplayer2.C.LENGTH_UNSET
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()
        val trackSelector = DefaultTrackSelector(context).apply {
            if (isLegacyTv) {
                // Source lists often expose a 4K/60 track first. MStar-era
                // decoders can advertise support but then exhaust codec
                // buffers. Prefer a stable 1080p/30 stereo track and only
                // exceed it when the source has no compatible alternative.
                setParameters(
                    buildUponParameters()
                        .setMaxVideoSize(1920, 1080)
                        .setMaxVideoFrameRate(30)
                        .setMaxVideoBitrate(5_000_000)
                        // Do not cap legacy TV audio at stereo. A number of
                        // IPTV entries advertise AAC 5.1 only; the previous
                        // limit made ExoPlayer select no audio renderer at
                        // all. MStar firmware can downmix the six-channel
                        // output to the TV's actual speaker layout.
                        .setMaxAudioChannelCount(6)
                        .setExceedVideoConstraintsIfNecessary(true)
                        .build()
                )
            }
        }
        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                setHandleAudioBecomingNoisy(true)
            }
    }

    suspend fun createMediaSource(context: Context, channel: Channel): MediaSource {
        val playbackInfo = resolvePlaybackInfo(channel)
        return createMediaSource(context, playbackInfo, channel.id.toString())
    }

    internal suspend fun preconnectHttpStream(
        playbackInfo: PlaybackStreamInfo,
        connectTimeoutMs: Int = FLV_PRECONNECT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = FLV_PRECONNECT_READ_TIMEOUT_MS,
        totalTimeoutMs: Long = FLV_PRECONNECT_TOTAL_TIMEOUT_MS
    ): PreconnectedHttpDataSource? {
        var openedDataSource: HttpDataSource? = null
        try {
            return withContext(Dispatchers.IO) {
            if (playbackInfo.mimeType != FLV_MIME_TYPE) return@withContext null
            val dataSource = createHttpDataSourceFactory(
                playbackInfo,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                useOkHttpForLegacyHuya = shouldUseOkHttpForLegacyHuyaMedia(
                    isLegacyTv = DeviceProfile.isLegacyTv(App.instance),
                    isHuyaStream = playbackInfo.resolvedBy.startsWith("huya:"),
                    isFlv = playbackInfo.mimeType == FLV_MIME_TYPE
                )
            ).createDataSource().also { openedDataSource = it }
            val uri = Uri.parse(playbackInfo.url)
            val coroutineContext = currentCoroutineContext()
            val deadlineNanos = System.nanoTime() + totalTimeoutMs.coerceAtLeast(1L) * 1_000_000L
            fun checkPreconnectDeadline() {
                coroutineContext.ensureActive()
                if (System.nanoTime() >= deadlineNanos) {
                    throw SocketTimeoutException("FLV preconnect exceeded ${totalTimeoutMs}ms")
                }
            }
            val length = dataSource.open(DataSpec(uri))
            val openedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
            checkPreconnectDeadline()
            // Read ahead while the old stream is still playing. Stop once the
            // prefix contains a complete video keyframe, or at the strict
            // 1 MiB cap. This avoids cutting over mid-GOP and then visibly
            // waiting for the next keyframe. Preserve every byte so ExoPlayer
            // still receives the FLV stream from byte zero. If the FLV header
            // explicitly says there is no audio, stop after the header; the
            // caller will reject that route without downloading media.
            val prefix = ByteArray(FLV_PRECONNECT_MAX_BUFFER_BYTES)
            var prefixLength = 0
            var hasAudioTrack: Boolean? = null
            while (prefixLength < 9) {
                checkPreconnectDeadline()
                val read = dataSource.read(
                    prefix,
                    prefixLength,
                    minOf(9 - prefixLength, FLV_PRECONNECT_MAX_BUFFER_BYTES - prefixLength)
                )
                if (read <= 0) break
                prefixLength += read
            }
            if (prefixLength >= 9) hasAudioTrack = hasAudioTrackInFlvHeader(prefix)
            val maxReadAheadBytes = flvPreconnectReadAheadBytes(hasAudioTrack)
            val hasVideoInHeader = prefixLength >= 5 && (prefix[4].toInt() and 0x01) != 0
            while (hasAudioTrack != false && prefixLength < maxReadAheadBytes && hasVideoInHeader &&
                (!hasFlvVideoKeyframe(prefix, prefixLength) || !hasFlvAudioPayload(prefix, prefixLength))
            ) {
                checkPreconnectDeadline()
                val read = dataSource.read(
                    prefix,
                    prefixLength,
                    minOf(FLV_PRECONNECT_READ_CHUNK_BYTES, maxReadAheadBytes - prefixLength)
                )
                if (read <= 0) break
                prefixLength += read
            }
            val bufferedPrefix = prefix.copyOf(prefixLength)
            checkPreconnectDeadline()
            PreconnectedHttpDataSource(
                dataSource,
                uri,
                length,
                bufferedPrefix,
                hasAudioTrack,
                hasFlvVideoKeyframe(bufferedPrefix),
                hasFlvAudioPayload(bufferedPrefix),
                flvDecodableAudioPayloadFormat(bufferedPrefix),
                openedAtElapsedRealtimeMs
            )
            }
        } catch (error: Throwable) {
            // withContext may cancel AFTER the IO block returned its value.
            // Keep ownership outside that boundary so its opened socket cannot leak.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching { openedDataSource?.close() }
            }
            throw error
        }
    }

    fun startChannelPlayback(context: Context, channel: Channel) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(Constants.EXTRA_CHANNEL_ID, channel.id)
            putExtra(Constants.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(Constants.EXTRA_CHANNEL_URL, channel.url)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        Log.d(TAG, "Starting PlayerActivity for channel=${channel.name}, id=${channel.id}")
        context.startActivity(intent)
    }

    suspend fun resolvePlaybackInfo(
        channel: Channel,
        attemptIndex: Int = 0,
        douyuOfficialOnly: Boolean = false,
        huyaSchemeOverride: String? = null,
        validateHuyaRedirectHost: Boolean = false
    ): PlaybackStreamInfo {
        val channelSettings = App.instance.preferences.getChannelPlaybackSettings(
            channel.normalizedName.ifBlank { Channel.normalizeName(channel.name) }
        )
        if (HuyaStreamResolver.supports(channel.getDisplayUrl())) {
            val isLegacyTv = DeviceProfile.isLegacyTv(App.instance)
            val requestedScheme = huyaSchemeOverride ?: channelSettings.huyaScheme
            // Prefer Huya's separately signed mobile HLS route for the legacy-TV
            // smart default. Reopening the same FLV URL on MStar repeatedly
            // succeeds for a few responses, then receives HTTP 403. HLS keeps
            // segment continuity in ExoPlayer; explicit FLV remains available
            // and HLS failures still fall back to a freshly signed FLV route.
            val huyaScheme = effectiveHuyaScheme(isLegacyTv, requestedScheme)
            if (isLegacyTv) {
                Log.i(
                    TAG,
                    "Legacy TV Huya scheme channel=${channel.name} " +
                        "requested=$requestedScheme effective=$huyaScheme"
                )
            }
            val resolved = HuyaStreamResolver.resolveOrThrow(
                url = channel.getDisplayUrl(),
                attemptIndex = attemptIndex,
                schemeOverride = huyaScheme,
                validateCdnRedirectHost = validateHuyaRedirectHost
            )
            Log.d(TAG, "Resolved playback url for channel=${channel.name} via=${resolved.resolvedBy} -> ${resolved.url}")
            return resolved
        }
        if (DouyuStreamResolver.supports(channel.getDisplayUrl())) {
            val resolved = DouyuStreamResolver.resolve(
                url = channel.getDisplayUrl(),
                officialOnly = douyuOfficialOnly,
                schemeOverride = channelSettings.douyuScheme
            )
            Log.d(TAG, "Resolved playback url for channel=${channel.name} via=${resolved.resolvedBy} -> ${resolved.url}")
            return resolved
        }
        Log.d(TAG, "Using direct playback url for channel=${channel.name} -> ${channel.getDisplayUrl()}")
        val directUrl = channel.getDisplayUrl()
        return PlaybackStreamInfo(
            url = directUrl,
            headers = PlaylistParser.getStreamHeaders(channel.url),
            mimeType = MimeTypes.APPLICATION_M3U8.takeIf {
                directUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            }
        )
    }

    suspend fun createMediaSourceForUrl(url: String): MediaSource {
        val playbackInfo = HuyaStreamResolver.resolve(url)
            ?: DouyuStreamResolver.takeIf { DouyuStreamResolver.supports(url) }?.resolve(url)
            ?: PlaybackStreamInfo(url = url, headers = PlaylistParser.getStreamHeaders(url))
        return createMediaSource(App.instance, playbackInfo)
    }

    internal fun createMediaSource(
        context: Context,
        playbackInfo: PlaybackStreamInfo,
        mediaId: String? = null,
        preconnectedDataSource: PreconnectedHttpDataSource? = null,
        transferListener: TransferListener? = null,
        onHttpEofReconnect: ((LegacyHuyaFlvReconnectEvent) -> Unit)? = null,
        huyaReconnectGate: LegacyHuyaFlvReconnectGate = LegacyHuyaFlvReconnectGate(),
        renewRejectedHuyaUrl: (suspend (Int, String) -> PlaybackStreamInfo?)? = null
    ): MediaSource {
        val isLegacyHuyaFlv = shouldUseOkHttpForLegacyHuyaMedia(
            isLegacyTv = DeviceProfile.isLegacyTv(context),
            isHuyaStream = playbackInfo.resolvedBy.startsWith("huya:"),
            isFlv = playbackInfo.mimeType == FLV_MIME_TYPE
        )
        val timeoutProfile = mediaHttpTimeoutProfile(isLegacyHuyaFlv)
        val httpDataSourceFactory = createHttpDataSourceFactory(
            playbackInfo,
            connectTimeoutMs = timeoutProfile.connectTimeoutMs,
            readTimeoutMs = timeoutProfile.readTimeoutMs,
            useOkHttpForLegacyHuya = isLegacyHuyaFlv
        )
        if (transferListener != null) {
            // The first response was opened before this factory existed. Its
            // wrapper publishes balanced events without attaching the listener
            // midway through the underlying HTTP transfer.
            preconnectedDataSource?.addTransferListener(transferListener)
            when (httpDataSourceFactory) {
                is DefaultHttpDataSource.Factory -> httpDataSourceFactory.setTransferListener(transferListener)
                is OkHttpDataSource.Factory -> httpDataSourceFactory.setTransferListener(transferListener)
            }
        }
        val defaultDataSourceFactory = DefaultDataSource.Factory(
            context,
            httpDataSourceFactory
        )
        val baseDataSourceFactory: DataSource.Factory = preconnectedDataSource?.let {
            PreconnectedFirstDataSourceFactory(it, defaultDataSourceFactory)
        } ?: defaultDataSourceFactory
        // On MStar, renew a rejected signed FLV URL inside this DataSource.
        // This keeps the active extractor/player alive across a Huya response
        // boundary and avoids a decoder flush on every CDN signature change.
        val inlineHuyaRenewal = renewRejectedHuyaUrl.takeIf {
            shouldUseInlineHuyaRenewal(isLegacyHuyaFlv)
        }
        val dataSourceFactory: DataSource.Factory = if (isLegacyHuyaFlv) {
            LegacyHuyaFlvReconnectDataSourceFactory(
                baseDataSourceFactory,
                onHttpEofReconnect ?: {},
                huyaReconnectGate,
                inlineHuyaRenewal,
                playbackInfo.resolvedBy
            )
        } else {
            baseDataSourceFactory
        }
        val mediaItem = MediaItem.Builder()
            .setUri(playbackInfo.url)
            .apply {
                mediaId?.let { setMediaId(it) }
                playbackInfo.mimeType?.let { setMimeType(it) }
            }
            .build()
        return when (playbackInfo.mimeType) {
            MimeTypes.APPLICATION_M3U8 -> HlsMediaSource.Factory(dataSourceFactory)
                // A dead live segment should be retried briefly, then return
                // control to PlayerActivity's bounded source failover.  The
                // default policy can keep one bad HLS request alive long
                // enough to look like a permanent TV freeze.
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
                .createMediaSource(mediaItem)
            FLV_MIME_TYPE -> ProgressiveMediaSource.Factory(
                dataSourceFactory,
                if (isLegacyHuyaFlv) {
                    ExtractorsFactory { arrayOf(LegacyHuyaFlvLiveExtractor()) }
                } else {
                    DefaultExtractorsFactory()
                }
            ).apply {
                if (isLegacyHuyaFlv) {
                    // One retry is enough for transient transport errors. A
                    // rejected signed URL should return promptly to the same-
                    // channel resolver, not be replayed repeatedly by ExoPlayer.
                    setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
                }
            }.createMediaSource(mediaItem)
            TS_MIME_TYPE -> ProgressiveMediaSource.Factory(
                dataSourceFactory,
                DefaultExtractorsFactory()
            ).createMediaSource(mediaItem)
            else -> DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }
    }

    private fun createHttpDataSourceFactory(
        playbackInfo: PlaybackStreamInfo,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 20_000,
        useOkHttpForLegacyHuya: Boolean = false
    ): HttpDataSource.Factory {
        val userAgent = playbackInfo.headers["User-Agent"] ?: Constants.USER_AGENT
        if (useOkHttpForLegacyHuya) {
            val client = legacyHuyaMediaClient.newBuilder()
                .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            Log.i(TAG, "Using pooled OkHttp media transport for legacy Huya FLV")
            return OkHttpDataSource.Factory(client)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(playbackInfo.headers)
        }
        return DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(connectTimeoutMs)
            .setReadTimeoutMs(readTimeoutMs)
            .setDefaultRequestProperties(playbackInfo.headers)
    }

    suspend fun loadInitialPlaylist() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
        try {
            val database = App.instance.database
            val playlistDao = database.playlistDao()
            SubscriptionRepository(App.instance, database).ensureDefaults()
            val logoRepository = LogoRepository(App.instance)

            playlistDao.getDefaultPlaylistItem()?.let { playlist ->
                App.instance.preferences.defaultPlaylistId = playlist.id
            }
            // No live playlist is created on first launch. Users explicitly
            // import sources from Subscription Management. Logo resolution is
            // limited to channels they have already added.
            logoRepository.applyLogosToDatabase(forceRefresh = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load initial playlist", e)
        }
        }
    }

}

private class LegacyPcmMonitoringRenderersFactory(
    context: Context,
    private val onAudiblePcm: (Long) -> Unit
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
        enableOffload: Boolean
    ): AudioSink = DefaultAudioSink.Builder()
        .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
        .setAudioProcessors(arrayOf(PcmActivityAudioProcessor(onAudiblePcm)))
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setOffloadMode(
            if (enableOffload) DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED
            else DefaultAudioSink.OFFLOAD_MODE_DISABLED
        )
        .build()
}
