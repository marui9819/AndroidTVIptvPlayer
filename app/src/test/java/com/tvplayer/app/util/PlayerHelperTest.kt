package com.tvplayer.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PlayerHelperTest {

    @Test
    fun closingDuringAReconnectOpenInvalidatesTheRetiredDataSourceSession() {
        val lifecycle = LegacyHuyaFlvSessionLifecycle()
        val session = lifecycle.beginSession()
        val reopenStarted = CountDownLatch(1)
        val reopenCanFinish = CountDownLatch(1)
        val lateConnectionMustClose = AtomicBoolean(false)
        val reopenThread = Thread {
            reopenStarted.countDown()
            if (reopenCanFinish.await(2, TimeUnit.SECONDS)) {
                // LegacyHuyaFlvContinuousDataSource performs this check after
                // upstream.open() returns, before accepting the new response.
                lateConnectionMustClose.set(!lifecycle.isCurrent(session))
            }
        }

        reopenThread.start()
        try {
            assertTrue(reopenStarted.await(2, TimeUnit.SECONDS))
            lifecycle.endCurrentSession()
        } finally {
            reopenCanFinish.countDown()
        }
        reopenThread.join(2_000L)

        assertFalse(reopenThread.isAlive)
        assertTrue(lateConnectionMustClose.get())
        val nextSession = lifecycle.beginSession()
        assertTrue(nextSession > session)
        assertTrue(lifecycle.isCurrent(nextSession))
    }

    @Test
    fun queuedHuyaReconnectWaitsForCutoverAndCanBeCancelled() {
        val gate = LegacyHuyaFlvReconnectGate(initiallyEnabled = false)
        val waiting = CountDownLatch(1)
        val resumed = AtomicBoolean(false)
        val worker = Thread {
            waiting.countDown()
            resumed.set(gate.awaitPermission { true })
        }

        worker.start()
        assertTrue(waiting.await(2, TimeUnit.SECONDS))
        Thread.sleep(150L)
        assertFalse(resumed.get())
        gate.enable()
        worker.join(2_000L)
        assertFalse(worker.isAlive)
        assertTrue(resumed.get())

        val cancelledGate = LegacyHuyaFlvReconnectGate(initiallyEnabled = false)
        assertFalse(cancelledGate.awaitPermission { false })
        cancelledGate.cancel()
        assertFalse(cancelledGate.awaitPermission { true })
    }

    @Test
    fun legacyPlaybackBuffersAllowNetworkRecoveryWithoutUnboundedMemoryGrowth() {
        val profile = playbackBufferProfile(isLegacyTv = true)

        assertTrue(profile.minBufferMs >= profile.bufferForPlaybackMs)
        assertTrue(profile.minBufferMs >= profile.bufferForPlaybackAfterRebufferMs)
        assertEquals(3_000, profile.minBufferMs)
        assertEquals(12_000, profile.maxBufferMs)
        assertEquals(1_000, profile.bufferForPlaybackMs)
        assertEquals(2_000, profile.bufferForPlaybackAfterRebufferMs)

        val standardProfile = playbackBufferProfile(isLegacyTv = false)
        assertEquals(5_000, standardProfile.minBufferMs)
        assertEquals(30_000, standardProfile.maxBufferMs)
    }

    @Test
    fun legacyHuyaFlvReadTimeoutPrecedesTheFrameStallWatchdog() {
        assertEquals(
            MediaHttpTimeoutProfile(connectTimeoutMs = 8_000, readTimeoutMs = 8_000),
            mediaHttpTimeoutProfile(isLegacyHuyaFlv = true)
        )
        assertEquals(
            MediaHttpTimeoutProfile(connectTimeoutMs = 15_000, readTimeoutMs = 20_000),
            mediaHttpTimeoutProfile(isLegacyHuyaFlv = false)
        )
    }

    @Test
    fun channelUpDownUsesTheVisibleCategoryOrderAndSkipsUnavailableRows() {
        val categoryRows = listOf("纪晓岚", "小木子先生", "星爷", "电影频道")
        val available = listOf(true, false, true, true)

        assertEquals(
            2,
            adjacentAvailableChannelIndex(categoryRows, available, "纪晓岚", direction = 1)
        )
        assertEquals(
            0,
            adjacentAvailableChannelIndex(categoryRows, available, "星爷", direction = -1)
        )
        assertEquals(
            null,
            adjacentAvailableChannelIndex(categoryRows, available, "纪晓岚", direction = -1)
        )
        assertEquals(
            null,
            adjacentAvailableChannelIndex(categoryRows, available, "电影频道", direction = 1)
        )
    }

    @Test
    fun legacyTvKeepsExplicitHuyaTransportButUsesFlvForSmartDefaultAfterHls403() {
        assertEquals(
            PreferencesHelper.HUYA_SCHEME_SINGLE_HLS,
            effectiveHuyaScheme(true, PreferencesHelper.HUYA_SCHEME_SINGLE_HLS)
        )
        assertEquals(
            PreferencesHelper.HUYA_SCHEME_SINGLE_FLV,
            effectiveHuyaScheme(true, PreferencesHelper.HUYA_SCHEME_SINGLE_FLV)
        )
        assertEquals(
            PreferencesHelper.HUYA_SCHEME_SINGLE_FLV,
            effectiveHuyaScheme(true, PreferencesHelper.HUYA_SCHEME_HOT_SWAP)
        )
        assertEquals(
            PreferencesHelper.HUYA_SCHEME_HOT_SWAP,
            effectiveHuyaScheme(false, PreferencesHelper.HUYA_SCHEME_HOT_SWAP)
        )
    }

    @Test
    fun huyaRecoveryUsesTheLatestExplicitTransportWhenRecoveryFlagsConflict() {
        assertEquals(
            PreferencesHelper.HUYA_SCHEME_SINGLE_HLS,
            huyaRecoverySchemeOverride(forceHls = true, forceFlv = false)
        )
        assertEquals(
            PreferencesHelper.HUYA_SCHEME_SINGLE_HLS,
            huyaRecoverySchemeOverride(forceHls = true, forceFlv = false)
        )
        assertEquals(
            PreferencesHelper.HUYA_SCHEME_SINGLE_FLV,
            huyaRecoverySchemeOverride(forceHls = true, forceFlv = true)
        )
        assertEquals(null, huyaRecoverySchemeOverride(forceHls = false, forceFlv = false))
    }

    @Test
    fun repeatedLegacyHuyaFlv403ReconnectsSwitchToSameChannelHlsAtBoundedThreshold() {
        assertFalse(
            shouldFallbackLegacyHuyaFlvToHlsAfterRejectedReconnect(
                isLegacyMstar = true,
                statusCode = 403,
                rejectedReconnectCount = 2,
                hlsFallbackAlreadyAttempted = false
            )
        )
        assertTrue(
            shouldFallbackLegacyHuyaFlvToHlsAfterRejectedReconnect(
                isLegacyMstar = true,
                statusCode = 403,
                rejectedReconnectCount = 3,
                hlsFallbackAlreadyAttempted = false
            )
        )
        assertFalse(
            shouldFallbackLegacyHuyaFlvToHlsAfterRejectedReconnect(
                isLegacyMstar = false,
                statusCode = 403,
                rejectedReconnectCount = 3,
                hlsFallbackAlreadyAttempted = false
            )
        )
        assertFalse(
            shouldFallbackLegacyHuyaFlvToHlsAfterRejectedReconnect(
                isLegacyMstar = true,
                statusCode = 503,
                rejectedReconnectCount = 3,
                hlsFallbackAlreadyAttempted = false
            )
        )
        assertFalse(
            shouldFallbackLegacyHuyaFlvToHlsAfterRejectedReconnect(
                isLegacyMstar = true,
                statusCode = 403,
                rejectedReconnectCount = 3,
                hlsFallbackAlreadyAttempted = true
            )
        )
    }

    @Test
    fun rejectedHuyaHlsHttpStatusesTriggerSameChannelFlvFallback() {
        assertTrue(shouldFallbackHuyaHlsToFlv(true, 403))
        assertTrue(shouldFallbackHuyaHlsToFlv(true, 503))
        assertFalse(shouldFallbackHuyaHlsToFlv(true, null))
        assertFalse(shouldFallbackHuyaHlsToFlv(false, 403))
    }

    @Test
    fun rejectedHuyaFlvSignaturesAreNotRetriedOnTheSameUrl() {
        assertFalse(shouldRetryLegacyHuyaFlvHttpStatus(401))
        assertFalse(shouldRetryLegacyHuyaFlvHttpStatus(403))
        assertFalse(shouldRetryLegacyHuyaFlvHttpStatus(404))
        assertTrue(shouldRetryLegacyHuyaFlvHttpStatus(408))
        assertTrue(shouldRetryLegacyHuyaFlvHttpStatus(429))
        assertTrue(shouldRetryLegacyHuyaFlvHttpStatus(503))

        assertTrue(shouldRenewLegacyHuyaFlvHttpStatus(401))
        assertTrue(shouldRenewLegacyHuyaFlvHttpStatus(403))
        assertTrue(shouldRenewLegacyHuyaFlvHttpStatus(404))
        assertFalse(shouldRenewLegacyHuyaFlvHttpStatus(408))
        assertFalse(shouldRenewLegacyHuyaFlvHttpStatus(429))
        assertFalse(shouldRenewLegacyHuyaFlvHttpStatus(503))
    }

    @Test
    fun activeHuyaFlvReadFailuresReconnectButRetiredOrInterruptedReadsDoNot() {
        assertTrue(shouldReconnectLegacyHuyaFlvReadFailure(sourceSessionIsCurrent = true, threadInterrupted = false))
        assertFalse(shouldReconnectLegacyHuyaFlvReadFailure(sourceSessionIsCurrent = false, threadInterrupted = false))
        assertFalse(shouldReconnectLegacyHuyaFlvReadFailure(sourceSessionIsCurrent = true, threadInterrupted = true))
    }

    @Test
    fun legacyHuyaInitialOpenRetriesTransientIoButRenewsRejectedUrlsAndStopsWhenRetired() {
        assertTrue(
            shouldRetryLegacyHuyaFlvOpen(
                isIoFailure = true,
                httpStatusCode = null,
                sourceSessionIsCurrent = true,
                threadInterrupted = false,
                attempt = 1,
                maxAttempts = 3
            )
        )
        assertTrue(
            shouldRetryLegacyHuyaFlvOpen(
                isIoFailure = true,
                httpStatusCode = 503,
                sourceSessionIsCurrent = true,
                threadInterrupted = false,
                attempt = 2,
                maxAttempts = 3
            )
        )
        assertFalse(
            shouldRetryLegacyHuyaFlvOpen(
                isIoFailure = true,
                httpStatusCode = 403,
                sourceSessionIsCurrent = true,
                threadInterrupted = false,
                attempt = 1,
                maxAttempts = 3
            )
        )
        assertFalse(
            shouldRetryLegacyHuyaFlvOpen(
                isIoFailure = true,
                httpStatusCode = null,
                sourceSessionIsCurrent = false,
                threadInterrupted = false,
                attempt = 1,
                maxAttempts = 3
            )
        )
        assertFalse(
            shouldRetryLegacyHuyaFlvOpen(
                isIoFailure = true,
                httpStatusCode = null,
                sourceSessionIsCurrent = true,
                threadInterrupted = true,
                attempt = 1,
                maxAttempts = 3
            )
        )
        assertFalse(
            shouldRetryLegacyHuyaFlvOpen(
                isIoFailure = true,
                httpStatusCode = null,
                sourceSessionIsCurrent = true,
                threadInterrupted = false,
                attempt = 3,
                maxAttempts = 3
            )
        )
        assertEquals(250L, legacyHuyaFlvOpenRetryDelayMs(1))
        assertEquals(600L, legacyHuyaFlvOpenRetryDelayMs(2))
    }

    @Test
    fun activeHuyaRecoveryOwnsPlayerErrorsAndPreventsParallelPersistentRetry() {
        assertTrue(shouldIgnoreHuyaPlaybackErrorDuringRecovery(isHuyaStream = true, recoveryInProgress = true))
        assertFalse(shouldIgnoreHuyaPlaybackErrorDuringRecovery(isHuyaStream = true, recoveryInProgress = false))
        assertFalse(shouldIgnoreHuyaPlaybackErrorDuringRecovery(isHuyaStream = false, recoveryInProgress = true))
    }

    @Test
    fun pooledHttpTransportIsRestrictedToLegacyHuyaFlv() {
        assertTrue(shouldUseOkHttpForLegacyHuyaMedia(isLegacyTv = true, isHuyaStream = true, isFlv = true))
        assertFalse(shouldUseOkHttpForLegacyHuyaMedia(isLegacyTv = false, isHuyaStream = true, isFlv = true))
        assertFalse(shouldUseOkHttpForLegacyHuyaMedia(isLegacyTv = true, isHuyaStream = false, isFlv = true))
        assertFalse(shouldUseOkHttpForLegacyHuyaMedia(isLegacyTv = true, isHuyaStream = true, isFlv = false))
    }

    @Test
    fun consumedPreconnectedHuyaSourceCanReopenWithItsFreshSignedUri() {
        val original = "https://edge.example/live.flv?signature=old"
        val renewed = "https://edge.example/live.flv?signature=new"

        assertFalse(shouldRejectPreconnectedUri(original, original, preconnectedResponseConsumed = false))
        assertTrue(shouldRejectPreconnectedUri(renewed, original, preconnectedResponseConsumed = false))
        assertFalse(shouldRejectPreconnectedUri(renewed, original, preconnectedResponseConsumed = true))
    }

    @Test
    fun preconnectedHttpFreshnessStartsAtResponseOpenAndRejectsExpiredOrInvalidTimes() {
        val openedAtMs = 1_000L
        val maximumAgeMs = 20_000L

        assertTrue(isFreshPreconnectedHttpResponse(openedAtMs, 21_000L, maximumAgeMs))
        assertFalse(isFreshPreconnectedHttpResponse(openedAtMs, 21_001L, maximumAgeMs))
        assertFalse(isFreshPreconnectedHttpResponse(0L, 10_000L, maximumAgeMs))
        assertFalse(isFreshPreconnectedHttpResponse(2_000L, 1_999L, maximumAgeMs))
        assertFalse(isFreshPreconnectedHttpResponse(openedAtMs, 1_001L, -1L))
    }

    @Test
    fun legacyHuyaFlvRenewsRejectedSignedUrlWithoutReplacingTheMediaItem() {
        assertTrue(shouldUseInlineHuyaRenewal(isLegacyHuyaFlv = true))
        assertFalse(shouldUseInlineHuyaRenewal(isLegacyHuyaFlv = false))
    }

    @Test
    fun bufferingWatchdogDefersOnlyDuringBoundedLegacyHuyaTransportReconnect() {
        assertEquals(30_000L, LEGACY_HUYA_TRANSPORT_RECOVERY_GRACE_MS)
        assertTrue(
            shouldDeferLegacyHuyaBufferingRecovery(
                isLegacyHuyaFlv = true,
                reconnectStartedAtMs = 10_000L,
                nowMs = 39_999L,
                maximumGraceMs = LEGACY_HUYA_TRANSPORT_RECOVERY_GRACE_MS
            )
        )
        assertEquals(
            1L,
            remainingLegacyHuyaReconnectGraceMs(
                10_000L,
                39_999L,
                LEGACY_HUYA_TRANSPORT_RECOVERY_GRACE_MS
            )
        )
        assertFalse(
            shouldDeferLegacyHuyaBufferingRecovery(
                isLegacyHuyaFlv = true,
                reconnectStartedAtMs = 10_000L,
                nowMs = 40_000L,
                maximumGraceMs = LEGACY_HUYA_TRANSPORT_RECOVERY_GRACE_MS
            )
        )
        assertFalse(
            shouldDeferLegacyHuyaBufferingRecovery(
                isLegacyHuyaFlv = false,
                reconnectStartedAtMs = 10_000L,
                nowMs = 20_000L,
                maximumGraceMs = 30_000L
            )
        )
    }

    @Test
    fun successfulHttpOpenKeepsTheOriginalFrameRecoveryDeadline() {
        val startedAt = legacyHuyaReconnectGraceStartedAtMs(
            currentStartedAtMs = 0L,
            nowMs = 10_000L,
            recoveryInProgress = true
        )
        assertEquals(10_000L, startedAt)
        assertEquals(
            startedAt,
            legacyHuyaReconnectGraceStartedAtMs(
                currentStartedAtMs = startedAt,
                nowMs = 20_000L,
                recoveryInProgress = true
            )
        )
        // DataSource HTTP success/failure events must not clear or extend the
        // deadline while ExoPlayer is still waiting for a rendered frame.
        assertEquals(
            startedAt,
            legacyHuyaReconnectGraceStartedAtMs(
                currentStartedAtMs = startedAt,
                nowMs = 25_000L,
                recoveryInProgress = false
            )
        )
    }

    @Test
    fun inlineHuyaRenewalHasTwoBoundedAttemptsOnlyForSignatureRejections() {
        assertTrue(shouldAttemptLegacyHuyaInlineRenewal(401, providerAvailable = true, renewalAttemptsForThisRecovery = 0, maxRenewalAttemptsPerRecovery = 2))
        assertTrue(shouldAttemptLegacyHuyaInlineRenewal(403, providerAvailable = true, renewalAttemptsForThisRecovery = 0, maxRenewalAttemptsPerRecovery = 2))
        assertTrue(shouldAttemptLegacyHuyaInlineRenewal(403, providerAvailable = true, renewalAttemptsForThisRecovery = 1, maxRenewalAttemptsPerRecovery = 2))
        assertTrue(shouldAttemptLegacyHuyaInlineRenewal(404, providerAvailable = true, renewalAttemptsForThisRecovery = 0, maxRenewalAttemptsPerRecovery = 2))
        assertFalse(shouldAttemptLegacyHuyaInlineRenewal(403, providerAvailable = false, renewalAttemptsForThisRecovery = 0, maxRenewalAttemptsPerRecovery = 2))
        assertFalse(shouldAttemptLegacyHuyaInlineRenewal(403, providerAvailable = true, renewalAttemptsForThisRecovery = 2, maxRenewalAttemptsPerRecovery = 2))
        assertFalse(shouldAttemptLegacyHuyaInlineRenewal(408, providerAvailable = true, renewalAttemptsForThisRecovery = 0, maxRenewalAttemptsPerRecovery = 2))
        assertFalse(shouldAttemptLegacyHuyaInlineRenewal(429, providerAvailable = true, renewalAttemptsForThisRecovery = 0, maxRenewalAttemptsPerRecovery = 2))
        assertFalse(shouldAttemptLegacyHuyaInlineRenewal(503, providerAvailable = true, renewalAttemptsForThisRecovery = 0, maxRenewalAttemptsPerRecovery = 2))
    }

    @Test
    fun reconnectCandidateRenewalIsBoundedAndOnlyFollowsRejectedContentOrSignatureStatus() {
        assertTrue(
            shouldAttemptLegacyHuyaReconnectCandidateRenewal(
                httpStatusCode = null,
                reconnectPayloadRejected = true,
                providerAvailable = true,
                renewalAttemptsForThisRecovery = 0,
                maxRenewalAttemptsPerRecovery = 2
            )
        )
        assertTrue(
            shouldAttemptLegacyHuyaReconnectCandidateRenewal(
                httpStatusCode = 403,
                reconnectPayloadRejected = false,
                providerAvailable = true,
                renewalAttemptsForThisRecovery = 1,
                maxRenewalAttemptsPerRecovery = 2
            )
        )
        assertFalse(
            shouldAttemptLegacyHuyaReconnectCandidateRenewal(
                httpStatusCode = 503,
                reconnectPayloadRejected = false,
                providerAvailable = true,
                renewalAttemptsForThisRecovery = 0,
                maxRenewalAttemptsPerRecovery = 2
            )
        )
        assertFalse(
            shouldAttemptLegacyHuyaReconnectCandidateRenewal(
                httpStatusCode = null,
                reconnectPayloadRejected = false,
                providerAvailable = true,
                renewalAttemptsForThisRecovery = 0,
                maxRenewalAttemptsPerRecovery = 2
            )
        )
        assertFalse(
            shouldAttemptLegacyHuyaReconnectCandidateRenewal(
                httpStatusCode = null,
                reconnectPayloadRejected = true,
                providerAvailable = false,
                renewalAttemptsForThisRecovery = 0,
                maxRenewalAttemptsPerRecovery = 2
            )
        )
        assertFalse(
            shouldAttemptLegacyHuyaReconnectCandidateRenewal(
                httpStatusCode = 403,
                reconnectPayloadRejected = false,
                providerAvailable = true,
                renewalAttemptsForThisRecovery = 2,
                maxRenewalAttemptsPerRecovery = 2
            )
        )
    }

    @Test
    fun legacyMstarHuyaRecoveryKeepsTheExistingSurfaceAndPlayer() {
        assertTrue(shouldKeepLegacyHuyaSurface(true, "huya:TX:0:video/x-flv"))
        assertTrue(shouldKeepLegacyHuyaSurface(true, "huya:mobile:hls"))
        assertFalse(shouldKeepLegacyHuyaSurface(false, "huya:TX:0:video/x-flv"))
        assertFalse(shouldKeepLegacyHuyaSurface(true, "direct"))
    }

    @Test
    fun sameUrlEofReconnectIsLimitedToLegacyFlvAndStopsAfterRouteValidationFails() {
        assertTrue(shouldTryHuyaSameUrlReconnect(true, true, blockedForCurrentUrl = false))
        assertFalse(shouldTryHuyaSameUrlReconnect(true, true, blockedForCurrentUrl = true))
        assertFalse(shouldTryHuyaSameUrlReconnect(false, true, blockedForCurrentUrl = false))
        assertFalse(shouldTryHuyaSameUrlReconnect(true, false, blockedForCurrentUrl = false))
    }

    @Test
    fun sameUrlHuyaReconnectHasPriorityAfterConfirmedEof() {
        assertTrue(shouldReconnectLegacyHuyaAtEof(true, true, true, false, false))
        assertFalse(shouldReconnectLegacyHuyaAtEof(true, true, false, false, false))
        assertFalse(shouldReconnectLegacyHuyaAtEof(false, true, true, false, false))
        assertFalse(shouldReconnectLegacyHuyaAtEof(true, false, true, false, false))
        assertFalse(shouldReconnectLegacyHuyaAtEof(true, true, true, true, false))
        assertFalse(shouldReconnectLegacyHuyaAtEof(true, true, true, false, true))
    }

    @Test
    fun repeatedHuyaHttpRejectionsUseCappedSameChannelBackoff() {
        assertEquals(350L, legacyHuyaHttpFailureBackoffMs(0))
        assertEquals(350L, legacyHuyaHttpFailureBackoffMs(1))
        assertEquals(1_500L, legacyHuyaHttpFailureBackoffMs(2))
        assertEquals(3_000L, legacyHuyaHttpFailureBackoffMs(3))
        assertEquals(6_000L, legacyHuyaHttpFailureBackoffMs(4))
        assertEquals(12_000L, legacyHuyaHttpFailureBackoffMs(5))
        assertEquals(12_000L, legacyHuyaHttpFailureBackoffMs(50))
    }

    @Test
    fun silentHuyaRetriesWaitForAudioWarmupAndUseCappedBackoff() {
        assertEquals(1_500L, huyaMissingAudioRetryDelayMs(retryCount = 0, supportedAudioTrackCount = 0))
        assertEquals(8_000L, huyaMissingAudioRetryDelayMs(retryCount = 0, supportedAudioTrackCount = 1))
        assertEquals(8_000L, huyaMissingAudioRetryDelayMs(retryCount = 1, supportedAudioTrackCount = 2))
        assertEquals(6_000L, huyaMissingAudioRetryDelayMs(retryCount = 2, supportedAudioTrackCount = 0))
        assertEquals(20_000L, huyaMissingAudioRetryDelayMs(retryCount = 4, supportedAudioTrackCount = 0))
        assertEquals(20_000L, huyaMissingAudioRetryDelayMs(retryCount = 99, supportedAudioTrackCount = 0))
    }

    @Test
    fun legacyHuyaAudioNeedsTwoSpacedPcmObservationsToIgnoreStaleTransitionCallbacks() {
        val evidence = AudiblePcmEvidence()
        assertFalse(evidence.observe(1_000L))
        assertFalse(evidence.observe(1_100L))
        assertTrue(evidence.observe(1_750L))

        evidence.reset()
        assertFalse(evidence.observe(3_000L))
        assertFalse(evidence.observe(7_000L))
        assertTrue(evidence.observe(7_750L))
    }

    @Test
    fun legacyHuyaRecoveryCanContinueWhileModernRecoveryKeepsItsFiniteBudget() {
        assertTrue(isHuyaRecoveryAttemptAllowed(attemptCount = 20, maxAttempts = null))
        assertFalse(isHuyaRecoveryAttemptAllowed(attemptCount = 5, maxAttempts = 5))
        assertEquals(21, nextHuyaRecoveryAttempt(attemptCount = 20, maxAttempts = null))
        assertEquals(5, nextHuyaRecoveryAttempt(attemptCount = 4, maxAttempts = 5))
        assertEquals(Int.MAX_VALUE, nextHuyaRecoveryAttempt(attemptCount = Int.MAX_VALUE, maxAttempts = null))
    }

    @Test
    fun legacyFrameWatchdogCanUseAConfirmedVideoSizeWhenTracksSnapshotIsEmpty() {
        assertEquals(1, monitoredVideoTrackCount(0, videoSizeObserved = true, isLegacyTv = true))
        assertEquals(
            1,
            monitoredVideoTrackCount(
                0,
                videoSizeObserved = false,
                isLegacyTv = true,
                expectedVideoTrack = true
            )
        )
        assertEquals(0, monitoredVideoTrackCount(0, videoSizeObserved = false, isLegacyTv = true))
        assertEquals(0, monitoredVideoTrackCount(0, videoSizeObserved = true, isLegacyTv = false))
        assertEquals(2, monitoredVideoTrackCount(2, videoSizeObserved = false, isLegacyTv = true))
    }

    @Test
    fun deferredPlaybackErrorWaitsForControlsAndCooldownThenResumesOnSameChannel() {
        assertFalse(shouldDispatchDeferredPlaybackError(true, true, true, true))
        assertFalse(shouldDispatchDeferredPlaybackError(true, true, false, false))
        assertFalse(shouldDispatchDeferredPlaybackError(true, false, false, true))
        assertTrue(shouldDispatchDeferredPlaybackError(true, true, false, true))
        assertFalse(shouldDispatchDeferredPlaybackError(false, true, false, true))
    }

    @Test
    fun behindLiveWindowRecoveryOnlyRunsForHlsAfterCooldown() {
        assertTrue(shouldRecoverBehindLiveWindow(1002, 1002, isHlsSource = true, cooldownExpired = true))
        assertFalse(shouldRecoverBehindLiveWindow(1002, 1002, isHlsSource = false, cooldownExpired = true))
        assertFalse(shouldRecoverBehindLiveWindow(1002, 1002, isHlsSource = true, cooldownExpired = false))
        assertFalse(shouldRecoverBehindLiveWindow(1003, 1002, isHlsSource = true, cooldownExpired = true))
    }

    @Test
    fun drawerRefreshRestoresTheSelectedChannelByStableKey() {
        assertEquals(
            2,
            restoredSelectionIndex("channel-c", listOf("channel-a", "channel-b", "channel-c"), true, 0)
        )
        assertEquals(
            1,
            restoredSelectionIndex("channel-c", listOf("channel-a", "channel-b"), true, 1)
        )
        assertEquals(
            0,
            restoredSelectionIndex("channel-c", listOf("channel-a", "channel-c"), false, 0)
        )
    }

    @Test
    fun catalogRefreshOnlyScrollsWhenTheStableDpadSelectionChanges() {
        assertFalse(shouldScrollToStableSelection("group-a", "group-a"))
        assertTrue(shouldScrollToStableSelection("group-a", "group-b"))
        assertFalse(shouldScrollToStableSelection("channel-a", null))
        assertTrue(shouldScrollToStableSelection(null, "channel-a"))
    }

    @Test
    fun sameGroupRebuildKeepsRemoteBrowseCursorInsteadOfFollowingPlayback() {
        assertEquals(
            2,
            restoredSelectionIndexForGroupRefresh(
                previousGroupName = "电影",
                nextGroupName = "电影",
                previousSelectedKey = "channel-c",
                newChannelKeys = listOf("channel-a", "channel-b", "channel-c"),
                fallbackIndex = 0
            )
        )
        assertEquals(
            0,
            restoredSelectionIndexForGroupRefresh(
                previousGroupName = "电影",
                nextGroupName = "新闻",
                previousSelectedKey = "channel-c",
                newChannelKeys = listOf("news-a", "news-b"),
                fallbackIndex = 0
            )
        )
    }

    @Test
    fun drawerRefreshKeepsTheBrowsedGroupByNameInsteadOfFollowingPlayback() {
        assertEquals(2, restoredGroupIndex("电影", listOf("收藏", "新闻", "电影"), 0))
        assertEquals(1, restoredGroupIndex("已删除分类", listOf("收藏", "电影"), 1))
        assertEquals(0, restoredGroupIndex(null, emptyList(), 0))
    }

    @Test
    fun listRefreshRestoresTheFirstVisibleRemoteBrowseAnchorByStableKey() {
        assertEquals(1, restoredScrollAnchorIndex("channel-e", listOf("channel-a", "channel-e")))
        assertEquals(2, restoredScrollAnchorIndex("电影", listOf("收藏", "新闻", "电影")))
        assertEquals(null, restoredScrollAnchorIndex("removed", listOf("channel-a", "channel-b")))
        assertEquals(null, restoredScrollAnchorIndex(null, listOf("channel-a")))
        assertEquals(4, restoredScrollAnchorIndex("removed", listOf("a", "b", "c", "d", "e"), 4))
        assertEquals(4, restoredScrollAnchorIndex("removed", listOf("a", "b", "c", "d", "e"), 99))
        assertEquals(null, restoredScrollAnchorIndex("removed", emptyList(), 4))
        // If a transient empty snapshot made RecyclerView report NO_POSITION,
        // keep the D-pad's selected item in view instead of jumping to zero.
        assertEquals(3, restoredScrollAnchorIndex(null, listOf("a", "b", "c", "d"), 3))
    }

    @Test
    fun pcmFromThePreviousMediaItemCannotConfirmNewItemAudio() {
        assertFalse(isPcmActivityAfterActiveAudioStart(99L, 100L))
        assertTrue(isPcmActivityAfterActiveAudioStart(100L, 100L))
        assertTrue(isPcmActivityAfterActiveAudioStart(101L, 100L))
        assertFalse(isPcmActivityAfterActiveAudioStart(101L, 0L))
    }

    @Test
    fun staleSurfaceRepairCallbacksCannotMutateANewChannelOrSource() {
        assertTrue(isCurrentSurfaceRepairRequest(7L, 7L, 14L, 14L))
        assertFalse(isCurrentSurfaceRepairRequest(6L, 7L, 14L, 14L))
        assertFalse(isCurrentSurfaceRepairRequest(7L, 7L, 14L, 15L))
    }

    @Test
    fun surfaceLayoutRepairComparesBufferWithMeasuredSurfaceNotSourceAspect() {
        assertFalse(surfaceFrameGeometryNeedsRepair(1920, 1080, 1920, 1080))
        assertFalse(surfaceFrameGeometryNeedsRepair(1919, 1081, 1920, 1080))
        assertTrue(surfaceFrameGeometryNeedsRepair(960, 1080, 1920, 1080))
        assertTrue(surfaceFrameGeometryNeedsRepair(960, 540, 1920, 1080))
        assertTrue(surfaceFrameGeometryNeedsRepair(1280, 720, 1920, 1080))
        assertFalse(surfaceFrameGeometryNeedsRepair(0, 0, 1920, 1080))
        assertFalse(surfaceFrameGeometryNeedsRepair(960, 1080, 0, 0))
    }

    @Test
    fun surfaceRepairWaitsUntilTheNewSurfaceHasRealFrameDimensions() {
        assertFalse(hasSurfaceFrameGeometry(0, 0))
        assertFalse(hasSurfaceFrameGeometry(1920, 0))
        assertTrue(hasSurfaceFrameGeometry(1920, 1080))
    }

    @Test
    fun flvHeaderAudioFlagCanRejectSilentRenewalCandidatesBeforeCutover() {
        assertEquals(true, hasAudioTrackInFlvHeader(byteArrayOf(70, 76, 86, 1, 5, 0, 0, 0, 9)))
        assertEquals(false, hasAudioTrackInFlvHeader(byteArrayOf(70, 76, 86, 1, 1, 0, 0, 0, 9)))
        assertEquals(null, hasAudioTrackInFlvHeader(byteArrayOf(70, 76, 86)))
        assertEquals(null, hasAudioTrackInFlvHeader(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun flvRenewalPrebufferIsBoundedAndSkipsMediaForExplicitlySilentRoutes() {
        assertEquals(9, flvPreconnectReadAheadBytes(false))
        assertEquals(FLV_PRECONNECT_MAX_BUFFER_BYTES, flvPreconnectReadAheadBytes(true))
        assertEquals(FLV_PRECONNECT_MAX_BUFFER_BYTES, flvPreconnectReadAheadBytes(null))
        assertEquals(1024 * 1024, FLV_PRECONNECT_MAX_BUFFER_BYTES)
    }

    @Test
    fun legacyHuyaRenewalQueuesOnlyAPlayableAudioAndVideoBearingFlv() {
        assertTrue(shouldQueueLegacyHuyaRenewal(true, true, true, true, true))
        assertTrue(shouldQueueLegacyHuyaRenewal(true, true, null, true, true))
        assertFalse(shouldQueueLegacyHuyaRenewal(true, true, false, true, true))
        assertFalse(shouldQueueLegacyHuyaRenewal(true, true, true, false, true))
        assertFalse(shouldQueueLegacyHuyaRenewal(true, true, true, true, false))
        assertFalse(shouldQueueLegacyHuyaRenewal(false, true, true, true, true))
        assertFalse(shouldQueueLegacyHuyaRenewal(true, false, true, true, true))
    }

    @Test
    fun queuedHuyaRenewalCutsOverOnlyAfterItsSourceAgeDeadline() {
        assertFalse(shouldCutOverQueuedHuyaRenewal(107_999L, 108_000L, true))
        assertTrue(shouldCutOverQueuedHuyaRenewal(108_000L, 108_000L, true))
        assertFalse(shouldCutOverQueuedHuyaRenewal(130_000L, 108_000L, false))
        assertFalse(shouldCutOverQueuedHuyaRenewal(1_000L, -1L, true))
    }

    @Test
    fun signedUrlRenewalDoesNotInvalidateQueuedHandoffForTheSameMediaItem() {
        assertTrue(
            isSameQueuedHuyaPlaybackSession(
                requestedChannelId = 3857L,
                activeChannelId = 3857L,
                requestedMediaId = "3857:2",
                activeMediaId = "3857:2"
            )
        )
        assertFalse(
            isSameQueuedHuyaPlaybackSession(
                requestedChannelId = 3857L,
                activeChannelId = 3857L,
                requestedMediaId = "3857:2",
                activeMediaId = "3857:3"
            )
        )
        assertFalse(
            isSameQueuedHuyaPlaybackSession(
                requestedChannelId = 3857L,
                activeChannelId = 3858L,
                requestedMediaId = "3857:2",
                activeMediaId = "3857:2"
            )
        )
    }

    @Test
    fun legacyVideoRendererGetsOnlyABoundedJoiningGrace() {
        assertEquals(250L, videoJoiningTimeMs(isLegacyTv = true))
        assertEquals(5_000L, videoJoiningTimeMs(isLegacyTv = false))
    }

    @Test
    fun flvPreconnectStopsOnlyAfterACompleteDecodableVideoKeyframe() {
        val avcKeyframe = flvVideoTag(frameAndCodec = 0x17, packetType = 1)
        val avcSequenceHeader = flvVideoTag(frameAndCodec = 0x17, packetType = 0)
        val interFrame = flvVideoTag(frameAndCodec = 0x27, packetType = 1)

        assertTrue(hasFlvVideoKeyframe(avcKeyframe))
        assertFalse(hasFlvVideoKeyframe(avcSequenceHeader))
        assertFalse(hasFlvVideoKeyframe(interFrame))
        assertFalse(hasFlvVideoKeyframe(avcKeyframe, validLength = 33))
        assertFalse(hasFlvVideoKeyframe(byteArrayOf(1, 2, 3)))
        assertFalse(hasFlvVideoKeyframe(flvVideoTag(frameAndCodec = 0x1e, packetType = 1)))
        assertFalse(hasFlvVideoKeyframe(flvVideoTag(frameAndCodec = 0x12, packetType = 1)))
        assertFalse(
            hasFlvVideoKeyframe(
                byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5,
                    0x7f, -1, -1, -1, 0, 0, 0, 0)
            )
        )
    }

    @Test
    fun flvPreconnectRequiresAacSamplesRatherThanOnlyTheSequenceHeader() {
        val aacSequenceHeader = flvAudioTag(packetType = 0)
        val aacRawSamples = flvAudioTag(packetType = 1)

        assertFalse(hasFlvAudioPayload(aacSequenceHeader))
        assertTrue(hasFlvAudioPayload(aacRawSamples))
        assertEquals(10, flvDecodableAudioPayloadFormat(aacRawSamples))
        assertFalse(hasFlvAudioPayload(aacRawSamples, validLength = aacRawSamples.size - 2))
    }

    @Test
    fun sameMediaReconnectRequiresValidHeaderButNotImmediateAudioOrVideoSamples() {
        val headerOnly = byteArrayOf(
            'F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5,
            0, 0, 0, 9,
            0, 0, 0, 0
        )
        assertEquals(13, legacyHuyaReconnectHeaderBytesToSkip(headerOnly))
        assertEquals(null, legacyHuyaReconnectHeaderBytesToSkip(headerOnly, validLength = 12))
        assertEquals(
            null,
            legacyHuyaReconnectHeaderBytesToSkip(headerOnly.copyOf().also { it[3] = 2 })
        )
        assertEquals(
            null,
            legacyHuyaReconnectHeaderBytesToSkip(headerOnly.copyOf().also { it[12] = 1 })
        )

        val video = flvVideoTag(frameAndCodec = 0x17, packetType = 1)
        val audio = flvAudioTag(packetType = 1)
        assertTrue(hasFlvVideoKeyframe(video))
        assertTrue(hasFlvAudioPayload(audio))
        assertFalse(hasFlvVideoKeyframe(headerOnly))
        assertFalse(hasFlvAudioPayload(headerOnly))
    }

    @Test
    fun flvPreconnectRejectsAudioCodecsNotSupportedByTheBundledExtractor() {
        listOf(2, 7, 8).forEach { soundFormat ->
            val tag = flvAudioTag(packetType = 1, soundFormat = soundFormat)
            assertTrue("FLV sound format $soundFormat should be supported", hasFlvAudioPayload(tag))
            assertEquals(soundFormat, flvDecodableAudioPayloadFormat(tag))
        }

        val unsupported = flvAudioTag(packetType = 1, soundFormat = 11)
        assertFalse(hasFlvAudioPayload(unsupported))
        assertEquals(null, flvDecodableAudioPayloadFormat(unsupported))
    }

    @Test
    fun unsupportedFlvVideoFormatIsExtractedFromTheCauseChain() {
        val wrapped = java.io.IOException(
            "container parser failed",
            UnsupportedFormatException("Video format not supported: 14")
        )
        assertEquals(14, unsupportedFlvVideoFormatId(wrapped))
        assertEquals(null, unsupportedFlvVideoFormatId(java.io.IOException("HTTP 403")))
        assertEquals(
            null,
            unsupportedFlvVideoFormatId(UnsupportedFormatException("Audio format not supported: 14"))
        )
    }

    private class UnsupportedFormatException(message: String) : java.io.IOException(message)

    private fun flvVideoTag(frameAndCodec: Int, packetType: Int): ByteArray {
        val payload = byteArrayOf(
            frameAndCodec.toByte(), packetType.toByte(),
            0, 0, 0, // AVC composition time
            0, 0, 0, 1, 0x65 // one-byte IDR NALU
        )
        val previousTagSize = 11 + payload.size
        return byteArrayOf(
            'F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5,
            0, 0, 0, 9, // FLV header, data offset 9
            0, 0, 0, 0, // PreviousTagSize0
            9, 0, 0, payload.size.toByte(), // video tag
            0, 0, 0, 0, 0, 0, 0 // timestamp and stream id
        ) + payload + byteArrayOf(
            0, 0, 0, previousTagSize.toByte() // trailing PreviousTagSize
        )
    }

    @Test
    fun lateAnalyticsEventsFromThePreviousMediaItemAreIgnored() {
        assertTrue(isCurrentMediaItemEvent("channel-14-source-2", "channel-14-source-2"))
        assertFalse(isCurrentMediaItemEvent("channel-14-source-1", "channel-14-source-2"))
        assertFalse(isCurrentMediaItemEvent(null, "channel-14-source-2"))
        assertFalse(isCurrentMediaItemEvent("", "channel-14-source-2"))
        assertTrue(isCurrentMediaItemEvent(null, null))
    }

    @Test
    fun huyaPrewarmUsesTheRemainingAgeOfTheCurrentSource() {
        assertEquals(75_000L, remainingSourceAgeDelayMs(1_000L, 11_000L, 85_000L))
        assertEquals(15_000L, remainingSourceAgeDelayMs(1_000L, 71_000L, 85_000L))
        assertEquals(0L, remainingSourceAgeDelayMs(1_000L, 91_000L, 85_000L))
        assertEquals(85_000L, remainingSourceAgeDelayMs(0L, 91_000L, 85_000L))
    }

    @Test
    fun videoFrameWatchdogDetectsFrozenVideoEvenWhenPlaybackClockMayContinue() {
        assertTrue(
            shouldRecoverForVideoFrameStall(
                hasVideoTrack = true,
                playbackReady = true,
                playWhenReady = true,
                lastRenderedFrameAtMs = 10_000L,
                monitoringStartedAtMs = 1_000L,
                nowMs = 25_000L,
                timeoutMs = 15_000L
            )
        )
        assertFalse(
            shouldRecoverForVideoFrameStall(
                hasVideoTrack = true,
                playbackReady = true,
                playWhenReady = true,
                lastRenderedFrameAtMs = 20_000L,
                monitoringStartedAtMs = 1_000L,
                nowMs = 25_000L,
                timeoutMs = 15_000L
            )
        )
        assertFalse(
            shouldRecoverForVideoFrameStall(
                hasVideoTrack = false,
                playbackReady = true,
                playWhenReady = true,
                lastRenderedFrameAtMs = 1_000L,
                monitoringStartedAtMs = 1_000L,
                nowMs = 25_000L,
                timeoutMs = 15_000L
            )
        )
        assertFalse(
            shouldRecoverForVideoFrameStall(
                hasVideoTrack = true,
                playbackReady = true,
                playWhenReady = false,
                lastRenderedFrameAtMs = 1_000L,
                monitoringStartedAtMs = 1_000L,
                nowMs = 25_000L,
                timeoutMs = 15_000L
            )
        )
    }

    @Test
    fun videoFrameWatchdogDoesNotRenewWhileDecoderOutputIsAdvancing() {
        assertFalse(
            shouldRecoverForVideoFrameStall(
                hasVideoTrack = true,
                playbackReady = true,
                playWhenReady = true,
                lastRenderedFrameAtMs = 20_000L,
                lastDecoderOutputAtMs = 39_000L,
                monitoringStartedAtMs = 1_000L,
                nowMs = 40_000L,
                timeoutMs = 15_000L
            )
        )
        assertTrue(
            shouldRecoverForVideoFrameStall(
                hasVideoTrack = true,
                playbackReady = true,
                playWhenReady = true,
                lastRenderedFrameAtMs = 20_000L,
                lastDecoderOutputAtMs = 21_000L,
                monitoringStartedAtMs = 1_000L,
                nowMs = 40_000L,
                timeoutMs = 15_000L
            )
        )
    }

    @Test
    fun legacyHuyaOnlyShortensCooldownAfterAConfirmedSourceFailure() {
        assertEquals(
            350L,
            legacyHuyaRetryDelayMs(true, true, normalDelayMs = 1_500L, rejectedSourceDelayMs = 350L)
        )
        assertEquals(
            1_500L,
            legacyHuyaRetryDelayMs(true, false, normalDelayMs = 1_500L, rejectedSourceDelayMs = 350L)
        )
        assertEquals(
            1_500L,
            legacyHuyaRetryDelayMs(false, true, normalDelayMs = 1_500L, rejectedSourceDelayMs = 350L)
        )
    }

    @Test
    fun reconnectFlvHeaderAndPreviousTagSizeAreRemovedFromTheSameExtractorStream() {
        val standardHeader = byteArrayOf(
            'F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5,
            0, 0, 0, 9
        )
        assertEquals(13, repeatedFlvHeaderBytesToSkip(standardHeader))

        val extendedHeader = standardHeader.copyOf().also {
            it[8] = 13
        }
        assertEquals(17, repeatedFlvHeaderBytesToSkip(extendedHeader))
        assertEquals(null, repeatedFlvHeaderBytesToSkip("not-flv!!".toByteArray()))
    }

    private fun flvAudioTag(packetType: Int, soundFormat: Int = 10): ByteArray {
        val soundHeader = ((soundFormat shl 4) or 0x0f).toByte()
        val payload = byteArrayOf(soundHeader, packetType.toByte(), 0x12, 0x10)
        val previousTagSize = 11 + payload.size
        return byteArrayOf(
            'F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5,
            0, 0, 0, 9,
            0, 0, 0, 0,
            8, 0, 0, payload.size.toByte(),
            0, 0, 0, 0, 0, 0, 0
        ) + payload + byteArrayOf(
            0, 0, 0, previousTagSize.toByte()
        )
    }
}
