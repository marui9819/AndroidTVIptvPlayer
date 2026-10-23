package com.tvplayer.app.ui.player

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.video.VideoSize
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DataSpec
import com.google.android.exoplayer2.upstream.TransferListener
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.tvplayer.app.R
import com.tvplayer.app.App
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.repository.EpgRepository
import com.tvplayer.app.data.repository.MergedChannel
import com.tvplayer.app.databinding.ActivityPlayerBinding
import com.tvplayer.app.network.DouyuStreamResolver
import com.tvplayer.app.network.HuyaStreamResolver
import com.tvplayer.app.network.PlaybackStreamInfo
import com.tvplayer.app.network.isInvalidHuyaCdnHostError
import com.tvplayer.app.ui.player.adapter.ChannelGroupAdapter
import com.tvplayer.app.ui.player.adapter.ChannelListAdapter
import com.tvplayer.app.ui.settings.SettingsActivity
import com.tvplayer.app.ui.subscription.SubscriptionManagementActivity
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.DebugReporter
import com.tvplayer.app.util.AudiblePcmEvidence
import com.tvplayer.app.util.LivePlaybackLogServer
import com.tvplayer.app.util.DeviceProfile
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.util.PlayerHelper
import com.tvplayer.app.util.LegacyHuyaFlvReconnectGate
import com.tvplayer.app.util.PreconnectedHttpDataSource
import com.tvplayer.app.util.LegacyHuyaFlvReconnectEvent
import com.tvplayer.app.util.isCurrentSurfaceRepairRequest
import com.tvplayer.app.util.isCurrentMediaItemEvent
import com.tvplayer.app.util.isPcmActivityAfterActiveAudioStart
import com.tvplayer.app.util.remainingSourceAgeDelayMs
import com.tvplayer.app.util.isFreshPreconnectedHttpResponse
import com.tvplayer.app.util.shouldRecoverForVideoFrameStall
import com.tvplayer.app.util.shouldFallbackHuyaHlsToFlv
import com.tvplayer.app.util.shouldKeepLegacyHuyaSurface
import com.tvplayer.app.util.shouldDispatchDeferredPlaybackError
import com.tvplayer.app.util.shouldRecoverBehindLiveWindow
import com.tvplayer.app.util.legacyHuyaRetryDelayMs
import com.tvplayer.app.util.legacyHuyaHttpFailureBackoffMs
import com.tvplayer.app.util.huyaMissingAudioRetryDelayMs
import com.tvplayer.app.util.shouldFallbackLegacyHuyaFlvToHlsAfterRejectedReconnect
import com.tvplayer.app.util.isHuyaRecoveryAttemptAllowed
import com.tvplayer.app.util.nextHuyaRecoveryAttempt
import com.tvplayer.app.util.monitoredVideoTrackCount
import com.tvplayer.app.util.shouldQueueLegacyHuyaRenewal
import com.tvplayer.app.util.shouldTryHuyaSameUrlReconnect
import com.tvplayer.app.util.shouldReconnectLegacyHuyaAtEof
import com.tvplayer.app.util.unsupportedFlvVideoFormatId
import com.tvplayer.app.util.huyaRecoverySchemeOverride
import com.tvplayer.app.util.LEGACY_HUYA_TRANSPORT_RECOVERY_GRACE_MS
import com.tvplayer.app.util.shouldCutOverQueuedHuyaRenewal
import com.tvplayer.app.util.isSameQueuedHuyaPlaybackSession
import com.tvplayer.app.util.shouldRenewLegacyHuyaFlvHttpStatus
import com.tvplayer.app.util.shouldIgnoreHuyaPlaybackErrorDuringRecovery
import com.tvplayer.app.util.shouldDeferLegacyHuyaBufferingRecovery
import com.tvplayer.app.util.legacyHuyaReconnectGraceStartedAtMs
import com.tvplayer.app.util.remainingLegacyHuyaReconnectGraceMs
import com.tvplayer.app.util.surfaceFrameGeometryNeedsRepair
import com.tvplayer.app.util.hasSurfaceFrameGeometry
import com.tvplayer.app.util.restoredScrollAnchorIndex
import com.tvplayer.app.util.shouldScrollToStableSelection
import com.tvplayer.app.util.AppVersion
import com.tvplayer.app.util.TvFocusStyler
import com.tvplayer.app.util.TvDialogStyler
import com.tvplayer.app.util.PlaybackControls
import com.tvplayer.app.util.ChannelUserPolicy
import com.tvplayer.app.util.PlaybackCornerOverlay
import com.tvplayer.app.util.PlayerWindowPolicy
import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tvplayer.app.util.PlaybackProtectionPolicy
import com.tvplayer.app.util.TimedAppExitParticipant
import com.tvplayer.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PlayerActivity : AppCompatActivity(), TimedAppExitParticipant {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val UI_HIDE_DELAY = 5000L
        private const val MAX_SAME_SOURCE_RETRY = 2
        private const val MAX_AUTOMATIC_FAILOVER_CHANNELS = 8
        private const val SAME_SOURCE_RETRY_DELAY_MS = 1500L
        // A source that recovered must remain healthy for a while before its
        // retry budget is reset.  Otherwise a ready callback immediately
        // erases the budget and the same dead live URL can loop forever.
        private const val PLAYBACK_RETRY_STABILITY_WINDOW_MS = 30_000L
        private const val BUFFERING_RECOVERY_TIMEOUT_MS = 12000L
        private const val BUFFERING_INDICATOR_DELAY_MS = 1000L
        private const val DOUYU_ENTRY_BUFFERING_RECOVERY_TIMEOUT_MS = 20000L
        private const val DOUYU_PROACTIVE_REFRESH_MS = 105_000L
        private const val DOUYU_EXPIRY_SAFETY_WINDOW_MS = 45_000L
        // A Douyu stream can be cut when its signed live segment/connection is
        // rotated.  These attempts are deliberately kept on the same room;
        // only after all of them fail may the generic source failover run.
        private const val MAX_DOUYU_CONTINUITY_ATTEMPTS = 3
        private const val DOUYU_CONTINUITY_RETRY_DELAY_MS = 180L
        // A 403/429 from the Douyu web gateway is a protection response, not
        // a dead channel. Back off instead of spending all continuity
        // attempts in a tight loop and incorrectly moving to the next room.
        private const val DOUYU_PROTECTION_RETRY_INITIAL_DELAY_MS = 3_000L
        private const val DOUYU_PROTECTION_RETRY_MAX_DELAY_MS = 60_000L
        // The received TV diagnostics show HLS segment failures after 10–40
        // seconds even though wsTime still has several minutes remaining.
        // Pre-resolve a different CDN shortly after playback begins, then
        // swap immediately on an error rather than interrupting every stream
        // on a fixed timer.
        private const val HUYA_HTTP_RESPONSE_LOG_INTERVAL_MS = 15_000L
        private const val HUYA_PREWARM_DELAY_MS = 2_500L
        // Huya anti-code URLs are short-lived even when wsTime appears to be
        // far in the future. Refresh before the CDN has a chance to close the
        // connection, while still leaving enough uninterrupted play time on
        // a low-end TV.
        private const val HUYA_PROACTIVE_REFRESH_MS = 80_000L
        // Legacy Huya FLV sessions commonly end after about two minutes.
        // Resolve the next signed URL once near the end, without opening a
        // second decoder, so the next single-player source can start promptly.
        // Schedule against the source's original start time, not the latest
        // READY callback: brief buffering can otherwise push prewarm past the
        // ~120s FLV expiry boundary.
        private const val LEGACY_HUYA_RENEWAL_PREWARM_SOURCE_AGE_MS = 85_000L
        private const val LEGACY_HUYA_RENEWAL_FALLBACK_SOURCE_AGE_MS = 105_000L
        private const val LEGACY_HUYA_PREWARM_MAX_AGE_MS = 45_000L
        private const val LEGACY_HUYA_CONNECTION_PRECONNECT_SOURCE_AGE_MS = 100_000L
        private const val LEGACY_HUYA_CONNECTION_PRECONNECT_MAX_AGE_MS = 20_000L
        private const val LEGACY_HUYA_CONNECTION_PRECONNECT_WAIT_MS = 8_000L
        private const val LEGACY_HUYA_SOURCE_AGE_GUARD_MS = 112_000L
        private const val LEGACY_HUYA_SOURCE_AGE_GUARD_GRACE_MS = 3_000L
        private const val LEGACY_HUYA_CANDIDATE_VALIDATION_TIMEOUT_MS = 12_000L
        private const val LEGACY_HUYA_EOF_RECONNECT_TIMEOUT_MS = 5_000L
        private const val LEGACY_HUYA_INLINE_RENEWAL_TIMEOUT_MS = 10_000L
        private const val LEGACY_HUYA_EOF_RECONNECT_CONNECT_TIMEOUT_MS = 2_500
        private const val LEGACY_HUYA_EOF_RECONNECT_READ_TIMEOUT_MS = 2_500
        private const val LEGACY_HUYA_QUEUED_TRANSITION_TIMEOUT_MS = 6_000L
        // Some Huya FLV endpoints return a complete, chunked HTTP response
        // containing only a few seconds of media. Detect that from ExoPlayer's
        // completed load duration and queue a validated continuation while the
        // current decoded buffer is still playing.
        private const val LEGACY_HUYA_SHORT_STREAM_MAX_DURATION_MS = 20_000L
        // Keep the next FLV attached to the active player's playlist and
        // switch with a wider margin before the signed ~120s connection can
        // expire. The previous 118s boundary left only a couple of seconds of
        // safety and produced a visible pause when MStar ended the old stream
        // early. This still reuses the existing decoder; no second decoder is
        // created on the low-memory TV.
        private const val LEGACY_HUYA_QUEUED_CUTOVER_SOURCE_AGE_MS = 108_000L
        private const val HUYA_BUFFERING_RECOVERY_TIMEOUT_MS = 6_000L
        private const val HUYA_PLAYBACK_STALL_TIMEOUT_MS = 8_000L
        private const val MAX_HUYA_CDN_RECOVERY_ATTEMPTS = 5
        // MStar Android 6 cannot tolerate rapid stop/prepare cycles. Keep
        // retries paced by the cooldown, but do not permanently stop renewing
        // the user's selected room after a small fixed number of failures.
        private const val LEGACY_HUYA_BUFFERING_RECOVERY_TIMEOUT_MS = 15_000L
        private const val LEGACY_HUYA_PLAYBACK_STALL_TIMEOUT_MS = 15_000L
        // Keep the same short codec-flush grace period as confirmed HTTP/frame
        // failures; longer waits were visible as an avoidable pause after the
        // next signed FLV had already been resolved.
        private const val LEGACY_HUYA_RECOVERY_DELAY_MS = 350L
        private const val LEGACY_SURFACE_REPAIR_RETRY_DELAY_MS = 80L
        // A definitive HTTP rejection means the old source is already dead.
        // Resolve its replacement immediately and keep only a short codec
        // cooldown, instead of stacking 1.5s of idle time before DNS/network.
        private const val LEGACY_HUYA_HTTP_RETRY_DELAY_MS = 350L
        private const val LEGACY_HUYA_RECOVERY_COOLDOWN_MS = 12_000L
        // MStar SurfaceView/MediaCodec can leave a stale, incorrectly sized
        // frame after an HLS playlist becomes stuck.  Give the live playlist
        // one quiet reload, then let the configured failover policy decide.
        private const val LEGACY_HLS_RECOVERY_DELAY_MS = 400L
        private const val LEGACY_HLS_RECOVERY_COOLDOWN_MS = 20_000L
        private const val MAX_LEGACY_HLS_RECOVERY_ATTEMPTS = 1
        private const val LIVE_WINDOW_RECOVERY_COOLDOWN_MS = 15_000L
        // When failover is disabled, retry forever but with a bounded backoff.
        // This avoids a tight codec/network loop while keeping the user's
        // selected channel and source in control.
        private const val PERSISTENT_SOURCE_RETRY_DELAY_MS = 5_000L
        private const val PERSISTENT_SOURCE_RETRY_DELAY_AFTER_EXHAUSTION_MS = 12_000L
        private const val HUYA_STANDBY_PREPARE_DELAY_MS = 108_000L
        private const val HUYA_STANDBY_SWAP_DELAY_MS = 118_000L
        private const val STREAM_REFRESH_WATCHDOG_INTERVAL_MS = 10_000L
        private const val PLAYBACK_PROGRESS_WATCHDOG_INTERVAL_MS = 5_000L
        // Reduce response jitter on legacy MStar TVs without lowering the
        // stable 15-second stall threshold and introducing false retries.
        private const val LEGACY_PLAYBACK_PROGRESS_WATCHDOG_INTERVAL_MS = 1_500L
        private const val PLAYBACK_WATCHDOG_DIAGNOSTIC_INTERVAL_MS = 30_000L
        private const val ENDED_RECOVERY_WATCHDOG_DELAY_MS = 20_000L
        private const val VIDEO_FRAME_DIAGNOSTIC_INTERVAL_MS = 30_000L
        private const val PLAYBACK_STALL_TIMEOUT_MS = 15_000L
        private const val DOUYU_ENTRY_PLAYBACK_STALL_TIMEOUT_MS = 30000L
        private const val PLAYBACK_PROGRESS_EPSILON_MS = 1_000L
        private const val LOW_END_TV_RECOVERY_SUSPEND_MS = 12_000L
        private const val LOW_END_TV_RECOVERY_RESUME_DELAY_MS = 2_500L
        private const val STATUS_HINT_DEFAULT_MS = 4_000L
        private const val STATUS_HINT_LONG_MS = 7_000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerView: PlayerView
    private lateinit var standbyPlayerView: PlayerView
    private val viewModel: PlayerViewModel by viewModels()
    private val epgRepository by lazy { EpgRepository(this) }
    private val preferences by lazy { PreferencesHelper(this) }
    private val database by lazy { AppDatabase.getDatabase(this) }

    private var player: ExoPlayer? = null
    private var standbyPlayer: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var currentChannelFavorite = false
    private var currentMergedChannel: MergedChannel? = null
    private var isUiVisible = true
    private var actionMenuVisible = false
    private var actionMenuShowingMore = false
    private var hideUiRunnable: Runnable? = null
    private var epgJob: Job? = null
    private var playbackJob: Job? = null
    private var retryJob: Job? = null
    private var retryStabilityJob: Job? = null
    private lateinit var channelListAdapter: ChannelListAdapter
    private lateinit var channelGroupAdapter: ChannelGroupAdapter
    private var playbackRetryChannelId: Long = -1L
    private var playbackRetryCount: Int = 0
    private val autoFailedChannelKeys = linkedSetOf<String>()
    private var autoFailoverAttemptCount = 0
    private var autoFailoverInProgress = false
    private val failedPlaybackSourceIds = linkedSetOf<Long>()
    private var currentPlaybackInfo: PlaybackStreamInfo? = null
    private var standbyPlaybackInfo: PlaybackStreamInfo? = null
    private var currentDecoderMode: String = PreferencesHelper.CHANNEL_DECODER_DEFAULT
    private var currentHuyaScheme: String = PreferencesHelper.HUYA_SCHEME_HOT_SWAP
    private var currentDouyuScheme: String = PreferencesHelper.DOUYU_SCHEME_SMART
    private var currentResizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var defaultResizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var channelNameTransparency = 50
    private var showChannelName = true
    private var showCurrentTime = true
    private var showVersionBadge = true
    private var sleepDeadlineMs = 0L
    private var channelAliases: Map<String, String> = emptyMap()
    private lateinit var cornerOverlay: PlaybackCornerOverlay
    private var adaptiveOverlay: AdaptivePlaybackOverlay? = null
    private var historyCommitJob: Job? = null
    private var historyRecordedUrl: String? = null
    private var historyFirstFrameMediaId: String? = null
    private val phone by lazy { !DeviceProfile.isTv(this) }
    private val pipSupported by lazy { PlayerWindowPolicy.supportsPip(!phone, Build.VERSION.SDK_INT,
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) }
    private var mobileUi: MobilePlayerUi? = null
    private var mobileFullscreen = false
    private var pipEntering = false
    private var pipReturning = false
    private var pipReceiverRegistered = false
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "$packageName.PIP_TOGGLE") { togglePlayPause(); updateMobileInfo(); updatePipActions() }
        }
    }
    private var currentChannelSettingsKey: String = ""
    private var lastScrolledGroupKey: String? = null
    private var lastScrolledChannelKey: String? = null
    private var channelListRefreshJob: Job? = null
    private var streamRefreshJob: Job? = null
    private var huyaPrewarmJob: Job? = null
    private var huyaRenewalFallbackJob: Job? = null
    private var huyaConnectionPrewarmJob: Job? = null
    private var huyaShortStreamPrewarmJob: Job? = null
    private var huyaShortStreamPrewarmSourceUrl: String = ""
    private var huyaEofReconnectJob: Job? = null
    private var huyaEofPrewarmWaitJob: Job? = null
    // If reconnecting one signed FLV URL fails validation, do not hammer that
    // same URL on every short segment. Clear this only when the route changes
    // or the user starts a fresh recovery session.
    private var huyaSameUrlReconnectBlockedUrl: String = ""
    private var activeHuyaLoadedDurationMs: Long = C.TIME_UNSET
    private var activeHuyaHttpResponseComplete: Boolean = false
    @Volatile private var legacyHuyaTransportReconnectStartedAtElapsedMs: Long = 0L
    private var legacyHuyaSourceAgeGuardJob: Job? = null
    private var preconnectedHuyaDataSource: PreconnectedHttpDataSource? = null
    private var preconnectedHuyaDataSourceUrl: String = ""
    private var preconnectedHuyaDataSourceAtMs: Long = 0L
    private var queuedHuyaMediaId: String? = null
    private var queuedHuyaPlaybackInfo: PlaybackStreamInfo? = null
    private var queuedHuyaAttempt: Int = -1
    private var queuedHuyaDataSource: PreconnectedHttpDataSource? = null
    private var queuedHuyaReconnectGate: LegacyHuyaFlvReconnectGate? = null
    private var queuedHuyaPreparedAtMs: Long = 0L
    private var queuedHuyaConnectionOpenedAtMs: Long = 0L
    private var queuedHuyaCutoverJob: Job? = null
    private var queuedHuyaTransitionWatchdogJob: Job? = null
    private var huyaQueueSequence: Long = 0L
    private var huyaMissingAudioRetryJob: Job? = null
    private var huyaRecoveryJob: Job? = null
    private var legacyHlsRecoveryJob: Job? = null
    private var persistentSourceRetryJob: Job? = null
    private var huyaStandbyPrepareJob: Job? = null
    private var huyaStandbySwapJob: Job? = null
    private var streamRefreshWatchdogJob: Job? = null
    private var playbackProgressWatchdogJob: Job? = null
    private var endedRecoveryWatchdogJob: Job? = null
    private var douyuContinuityJob: Job? = null
    private var douyuContinuityChannelId: Long = -1L
    private var douyuContinuityAttempt: Int = 0
    private var douyuProtectionRetryJob: Job? = null
    private var douyuProtectionRetryCount: Int = 0
    private var nextStreamRefreshAtMillis: Long? = null
    private var refreshInProgress = false
    private var inlineHuyaRenewalMediaId: String? = null
    private var huyaCdnRecoveryAttempt = 0
    private var huyaCdnRotationIndex = 0
    private var legacyHuyaFlvForbiddenReconnectCount = 0
    /** Probe the same-channel HLS escape route at most once per manual playback session. */
    private var legacyHuyaHlsFallbackAttempted = false
    private var lastHuyaRecoveryAtMs = 0L
    private var pendingHuyaRenewalFirstFrameAtMs = 0L
    private var pendingHuyaRenewalLastFrameAtMs = 0L
    private var pendingHuyaRenewalFirstFrameChannelId = -1L
    private var pendingHuyaRenewalStartedAtElapsedMs = 0L
    private var pendingHuyaRenewalResolvedAtElapsedMs = 0L
    private var pendingHuyaRenewalSourceAppliedAtElapsedMs = 0L
    private var pendingHuyaRenewalCandidateWasPrewarmed = false
    private var pendingHuyaRenewalUsedPreconnectedDataSource = false
    private var pendingHuyaRenewalFirstFrameSeen = false
    private var pendingHuyaRenewalAudioAdvanced = false
    private var legacyHlsRecoveryChannelId: Long = -1L
    private var legacyHlsRecoveryAttempt = 0
    private var lastLegacyHlsRecoveryAtMs = 0L
    /** HLS protection failures fall back to a freshly signed FLV in-session. */
    private var huyaForceFlvForRecovery = false
    /** Unsupported FLV video tags are recovered through Huya's independent HLS route. */
    private var huyaForceHlsForRecovery = false
    private var huyaUnsupportedFlvVideoFormatId: Int? = null
    private var huyaHlsFlvFallbackAttempted = false
    private var huyaRedirectHostValidationRequired = false
    private var prewarmedHuyaPlaybackInfo: PlaybackStreamInfo? = null
    private var prewarmedHuyaAttempt = -1
    private var prewarmedHuyaChannelId = -1L
    private var prewarmedHuyaResolvedAtMs = 0L
    private var huyaPrewarmSourceChannelId = -1L
    private var huyaPrewarmSourceUrl = ""
    private var huyaMissingAudioRetryCount = 0
    private var audioProtectionAttempts = 0
    private var standbyAudioClockAdvanced = false
    private var standbyPreconnectedDataSource: PreconnectedHttpDataSource? = null
    private var avPrecheckEnabled = true
    private var missingAudioRecoveryEnabled = true
    private var timedExitRequested = false
    private var activeAudioTrackCount = 0
    private var activeAudioPlaybackAdvanced = false
    private var activeAudioAdvancingAtNanos: Long = 0L
    private var activeAudioPcmObserved = false
    private var lastAudiblePcmObservedAtNanos = 0L
    private val legacyHuyaPcmEvidence = AudiblePcmEvidence()
    private var lastPlaybackPositionMs: Long = 0L
    private var lastPlaybackProgressRealtimeMs: Long = 0L
    private var activeVideoTrackCount: Int = 0
    private var activeVideoSizeSeenForSource = false
    private var lastRenderedVideoFrameRealtimeMs: Long = 0L
    private var lastVideoDecoderOutputRealtimeMs: Long = 0L
    private var lastVideoDecoderRenderedBufferCount: Int? = null
    private var videoFrameMonitoringStartedAtMs: Long = 0L
    private var lastVideoFrameDiagnosticAtMs: Long = 0L
    // Render-thread observation, sampled by the main-thread watchdog only.
    // This measures scheduled video PTS vs the player's audio-led clock, not
    // actual speaker/display latency. Never use it alone to trigger recovery.
    private data class VideoClockObservation(val ptsUs: Long, val observedAtNs: Long, val releaseAtNs: Long)
    @Volatile private var lastVideoClockObservation: VideoClockObservation? = null
    private var lastPlaybackWatchdogDiagnosticAtMs: Long = 0L
    private var activePlaybackStartedAtMs: Long = 0L
    private var activeMediaSourceStartedAtMs: Long = 0L
    private var mediaSourceGeneration: Long = 0L
    private var standbyPlaybackReadyAtMs: Long = 0L
    private var automaticRecoverySuspendedUntilMs: Long = 0L
    private var deferredPlaybackError: com.google.android.exoplayer2.PlaybackException? = null
    private var deferredPlaybackErrorChannelId: Long = -1L
    private var deferredPlaybackPreparationError: Throwable? = null
    private var deferredPlaybackPreparationChannelId: Long = -1L
    private var deferredPlaybackEndedChannelId: Long = -1L
    private var behindLiveWindowRecoveryChannelId: Long = -1L
    private var lastBehindLiveWindowRecoveryAtMs: Long = 0L
    private var statusHideRunnable: Runnable? = null
    private var deferredRecoveryRunnable: Runnable? = null
    private val activityManager by lazy {
        @Suppress("DEPRECATION")
        getSystemService(ACTIVITY_SERVICE) as? ActivityManager
    }
    private val isLowEndTvDevice by lazy {
        DeviceProfile.isLegacyTv(this) || (
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) && (
                (activityManager?.isLowRamDevice == true) ||
                    ((activityManager?.memoryClass ?: 0) in 1..256) ||
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                )
            )
    }

    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private val longPressTimeout by lazy { ViewConfiguration.getLongPressTimeout().toLong() }
    private val tapTimeout by lazy { ViewConfiguration.getTapTimeout().toLong() }
    private val swipeThreshold by lazy { touchSlop * 6f }
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTimeMs = 0L
    private var swipeHandled = false
    private var longPressTriggered = false
    private var remoteCenterLongPressTriggered = false
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        openActionMenu()
    }
    private val remoteCenterLongPressRunnable = Runnable {
        remoteCenterLongPressTriggered = true
        openActionMenu()
    }
    private val bufferingRecoveryRunnable = Runnable {
        handleBufferingStall()
    }
    private val delayedBufferingIndicatorRunnable = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            binding.progressBar.visibility = if (inMobilePip() || pipEntering) View.GONE else View.VISIBLE
            binding.bufferingText.visibility = if (inMobilePip() || pipEntering) View.GONE else View.VISIBLE
            viewModel.setBuffering(true)
        }
    }
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reloadPlaybackProtectionSettings()
        ensureLivePlaybackLogServer()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.versionBadge.text = AppVersion.label(this)
        binding.versionBadge.alpha = 0.82f
        cornerOverlay = PlaybackCornerOverlay(binding.cornerOverlay, binding.currentTimeBadge,
            binding.versionBadge, binding.actionMenuCountdown)
        if (isLowEndTvDevice) {
            Log.i(TAG, "Low-end TV compatibility mode enabled")
        }

        playerView = binding.playerView
        standbyPlayerView = binding.standbyPlayerView

        if (phone) {
            mobileFullscreen = savedInstanceState?.getBoolean("mobile_fullscreen") ?: false
            mobileUi = MobilePlayerUi(this, binding.playerRoot, pipSupported, ::handleMobileAction)
            setContentView(mobileUi!!.root)
            applyMobileWindowMode()
            if (pipSupported) {
                ContextCompat.registerReceiver(this, pipReceiver, IntentFilter("$packageName.PIP_TOGGLE"), ContextCompat.RECEIVER_NOT_EXPORTED)
                pipReceiverRegistered = true
            }
        }

        adaptiveOverlay = AdaptivePlaybackOverlay(binding.playerRoot, binding.currentChannelNameBadge,
            binding.cornerOverlay, binding.currentTimeBadge, binding.versionBadge,
            { playerView.videoSurfaceView },
            listOfNotNull(binding.playerView.videoSurfaceView, binding.standbyPlayerView.videoSurfaceView))
        setupPlayer()
        setupUi()
        setupObservers()
        loadIntentData()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val channelId = intent.getLongExtra(Constants.EXTRA_CHANNEL_ID, -1L)
        if (channelId > 0L) {
            // PlayerActivity is singleTop so launches from Home/recent/favorites
            // can arrive here without recreating the activity. Resolve the new
            // channel through the already-loaded catalog instead of silently
            // continuing the previously selected stream.
            viewModel.selectChannel(channelId, intent.getStringExtra(Constants.EXTRA_CHANNEL_URL))
            return
        }

        val channelName = intent.getStringExtra(Constants.EXTRA_CHANNEL_NAME)
        val channelUrl = intent.getStringExtra(Constants.EXTRA_CHANNEL_URL)
        if (!channelName.isNullOrBlank() && !channelUrl.isNullOrBlank()) {
            playChannel(Channel.create(channelName, channelUrl, -1L))
        }
    }

    private fun setupPlayer(decoderMode: String = currentDecoderMode) {
        player = createTrackedPlayer(decoderMode)
        standbyPlayer = null
        playerView.player = player
        standbyPlayerView.player = null
        playerView.useController = false
        standbyPlayerView.useController = false
        playerView.setKeepContentOnPlayerReset(true)
        standbyPlayerView.setKeepContentOnPlayerReset(true)
        playerView.alpha = 1f
        standbyPlayerView.alpha = 0f
        playerView.visibility = View.VISIBLE
        standbyPlayerView.visibility = if (DeviceProfile.isLegacyTv(this)) View.GONE else View.VISIBLE
        startPlaybackProgressWatchdog()
        startStreamRefreshWatchdog()
    }

    private fun ensureLivePlaybackLogServer() {
        LivePlaybackLogServer.start(this).onFailure {
            Log.w(TAG, "Could not start live playback log endpoint", it)
        }
    }

    private fun inMobilePip() = phone && Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode

    private fun applyMobileWindowMode() {
        if (!phone || inMobilePip() || pipEntering) return
        val orientation = if (mobileFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != orientation) requestedOrientation = orientation
        WindowCompat.setDecorFitsSystemWindows(window, !mobileFullscreen)
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (mobileFullscreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        mobileUi?.setMode(mobileFullscreen)
    }

    private fun updateMobileInfo() {
        mobileUi?.update(currentChannel?.let(::displayChannelName).orEmpty(),
            listOfNotNull(currentChannel?.group, viewModel.currentSource.value?.playlistName).joinToString(" · "), player?.isPlaying == true)
    }

    private fun handleMobileAction(action: String) {
        when (action) {
            "back" -> finish()
            "fullscreen", "normal" -> {
                mobileFullscreen = action == "fullscreen"
                applyMobileWindowMode()
                Log.i(TAG, "mobile_window_mode fullscreen=$mobileFullscreen playerIdentity=${System.identityHashCode(player)} positionMs=${player?.currentPosition}")
                DebugReporter.recordLivePlaybackEvent("mobile_window_mode", currentChannel,
                    mapOf("fullscreen" to mobileFullscreen, "playerIdentity" to System.identityHashCode(player)))
            }
            "pip" -> enterMobilePip()
            "play" -> { togglePlayPause(); updateMobileInfo() }
            "previous" -> viewModel.playPreviousChannel()
            "next" -> viewModel.playNextChannel()
            "channels" -> showMobileChannelPicker()
            "video" -> showVideoSettingsDialog()
            "favorite" -> toggleCurrentChannelFavorite()
            "remark" -> showChannelRemarkDialog()
            "source" -> if (!viewModel.switchSource(1, currentChannel)) showToast("当前频道没有备用来源")
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            "sources" -> startActivity(Intent(this, SubscriptionManagementActivity::class.java))
        }
    }

    private fun showMobileChannelPicker() {
        val groups = viewModel.channelGroups.value
        if (groups.isEmpty()) { showToast("频道正在加载，请稍后再试"); return }
        AlertDialog.Builder(this).setTitle("选择频道分类")
            .setItems(groups.map { "${it.name} · ${it.channels.size}" }.toTypedArray()) { _, index ->
                val group = groups[index]
                val channels = group.channels
                AlertDialog.Builder(this).setTitle(group.name)
                    .setItems(channels.map { merged ->
                        val source = merged.preferredSource?.channel
                        (source?.let(::displayChannelName) ?: merged.name) + if (merged.isAvailable) "" else " · 重试"
                    }.toTypedArray()) { _, selected -> viewModel.selectMergedChannel(channels[selected].key) }
                    .setNegativeButton("返回", null).show()
            }.setNegativeButton("取消", null).show()
    }

    @androidx.annotation.RequiresApi(26)
    private fun mobilePipParams(): PictureInPictureParams {
        val playing = player?.playWhenReady == true
        val toggle = PendingIntent.getBroadcast(this, 1230,
            Intent("$packageName.PIP_TOGGLE").setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val action = RemoteAction(Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (playing) "暂停" else "播放", if (playing) "暂停播放" else "继续播放", toggle)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setActions(listOf(action))
        val rect = Rect()
        if (playerView.getGlobalVisibleRect(rect) && !rect.isEmpty) builder.setSourceRectHint(rect)
        return builder.build()
    }

    private fun updatePipActions() {
        if (inMobilePip()) runCatching { setPictureInPictureParams(mobilePipParams()) }
    }

    private fun enterMobilePip() {
        if (!pipSupported || Build.VERSION.SDK_INT < 26 || inMobilePip()) return
        if (currentChannel == null) { showToast("请先选择一个频道"); return }
        viewModel.closeSelector()
        binding.actionMenuView.visibility = View.GONE
        actionMenuVisible = false
        pipEntering = true
        mobileUi?.setMode(mobileFullscreen, true)
        hideMobilePipChrome()
        val entered = runCatching { enterPictureInPictureMode(mobilePipParams()) }.getOrDefault(false)
        if (!entered) {
            pipEntering = false; applyMobileWindowMode()
            cornerOverlay.start(showCurrentTime, showVersionBadge, sleepDeadlineMs)
            updatePersistentChannelName()
            AlertDialog.Builder(this).setTitle("小窗暂不可用")
                .setMessage("请在系统设置中允许 5xingTV 使用画中画。部分系统会限制小窗播放。")
                .setPositiveButton("打开画中画设置") { _, _ ->
                    runCatching { startActivity(Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", android.net.Uri.parse("package:$packageName"))) }
                        .onFailure { showToast("请在系统应用设置中手动允许画中画") }
                }.setNegativeButton("返回", null).show()
        }
    }

    private fun hideMobilePipChrome() {
        cornerOverlay.stop()
        listOf(binding.controlPanel, binding.infoPanel, binding.channelListView, binding.actionMenuView,
            binding.cornerOverlay, binding.currentChannelNameBadge, binding.progressBar, binding.bufferingText, binding.statusText)
            .forEach { it.visibility = View.GONE }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!phone) return
        pipEntering = false
        mobileUi?.setMode(mobileFullscreen, isInPictureInPictureMode)
        if (isInPictureInPictureMode) { hideMobilePipChrome(); updatePipActions() }
        else { pipReturning = true; updatePersistentChannelName() }
        DebugReporter.recordLivePlaybackEvent("mobile_pip_mode", currentChannel,
            mapOf("active" to isInPictureInPictureMode, "playerIdentity" to System.identityHashCode(player), "positionMs" to player?.currentPosition))
        Log.i(TAG, "mobile_pip_mode active=$isInPictureInPictureMode playerIdentity=${System.identityHashCode(player)} positionMs=${player?.currentPosition}")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (phone) mobileUi?.setMode(mobileFullscreen, inMobilePip() || pipEntering)
        adaptiveOverlay?.requestUpdate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("mobile_fullscreen", mobileFullscreen)
        super.onSaveInstanceState(outState)
    }

    private fun reloadPlaybackProtectionSettings() {
        val settings = PlaybackControls.snapshot(this) ?: return
        avPrecheckEnabled = settings.getBoolean("precheck", true)
        missingAudioRecoveryEnabled = settings.getBoolean("audioRecovery", true)
        applyUserUiSnapshot(settings)
        if (!missingAudioRecoveryEnabled) {
            huyaMissingAudioRetryJob?.cancel()
            huyaMissingAudioRetryJob = null
        }
        DebugReporter.recordLivePlaybackEvent("playback_protection_settings", currentChannel,
            mapOf("avPrecheck" to avPrecheckEnabled, "audioRecovery" to missingAudioRecoveryEnabled))
    }

    private fun applyUserUiSnapshot(settings: Bundle) {
        showChannelName = settings.getBoolean("showName", true)
        channelNameTransparency = settings.getInt("nameTransparency", 50)
        showCurrentTime = settings.getBoolean("showClock", true)
        showVersionBadge = settings.getBoolean("showVersion", true)
        defaultResizeMode = settings.getInt("defaultResizeMode", AspectRatioFrameLayout.RESIZE_MODE_FIT)
        sleepDeadlineMs = if (!settings.getString("token").isNullOrEmpty()) settings.getLong("deadline") else 0L
        val aliases = settings.getBundle("aliases")
        channelAliases = aliases?.keySet()?.associateWith { aliases.getString(it).orEmpty() }.orEmpty()
        if (::channelListAdapter.isInitialized) channelListAdapter.setAliases(channelAliases)
        if (::binding.isInitialized) {
            binding.channelName.text = currentChannel?.let(::displayChannelName).orEmpty()
            updatePersistentChannelName()
        }
        if (::cornerOverlay.isInitialized) cornerOverlay.start(showCurrentTime, showVersionBadge, sleepDeadlineMs)
        adaptiveOverlay?.requestUpdate()
    }

    private fun displayChannelName(channel: Channel): String = channelAliases[channel.url.trim()] ?: channel.name

    private fun hasSuccessfulPlaybackEvidence(): Boolean {
        val activePlayer = player ?: return false
        val channel = currentChannel ?: return false
        val huyaVideo = HuyaStreamResolver.supports(channel.url)
        val videoExpected = activeVideoTrackCount > 0 || huyaVideo
        return ChannelUserPolicy.successfulPlayback(
            activePlayer.isPlaying, videoExpected,
            historyFirstFrameMediaId != null && historyFirstFrameMediaId == activePlayer.currentMediaItem?.mediaId,
            activeAudioTrackCount, activeAudioPlaybackAdvanced, huyaVideo
        )
    }

    /** One small delayed check per successful session, not a polling loop. */
    private fun maybeRecordSuccessfulPlayback() {
        val channel = currentChannel ?: return
        if (historyRecordedUrl == channel.url || historyCommitJob?.isActive == true || !hasSuccessfulPlaybackEvidence()) return
        val activePlayer = player ?: return
        val mediaId = activePlayer.currentMediaItem?.mediaId
        historyCommitJob = lifecycleScope.launch {
            delay(3_000L)
            if (player === activePlayer && currentChannel?.url == channel.url &&
                activePlayer.currentMediaItem?.mediaId == mediaId && hasSuccessfulPlaybackEvidence()) {
                historyRecordedUrl = channel.url
                viewModel.recordSuccessfulPlayback(channel)
                DebugReporter.recordLivePlaybackEvent("recent_playback_confirmed", channel)
            }
        }
    }

    override fun stopForTimedExit() {
        timedExitRequested = true
        DebugReporter.recordLivePlaybackEvent("sleep_timer_expired", currentChannel)
        releasePlayer()
    }

    private fun createTrackedPlayer(decoderMode: String): ExoPlayer {
        lateinit var trackedPlayer: ExoPlayer
        trackedPlayer = PlayerHelper.createPlayer(
            this,
            preferences.useHardwareAcceleration,
            decoderMode,
            onAudiblePcm = { observedAtNanos ->
                runOnUiThread {
                    if (trackedPlayer === player) handleActiveAudiblePcm(observedAtNanos)
                }
            }
        )
        return trackedPlayer.apply {
            if (phone) setAudioAttributes(com.google.android.exoplayer2.audio.AudioAttributes.DEFAULT, true)
            setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
                if (trackedPlayer === player) {
                    lastVideoClockObservation = VideoClockObservation(
                        presentationTimeUs, System.nanoTime(), releaseTimeNs
                    )
                }
            }
            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (trackedPlayer !== player || mediaItem == null) return
                    lastVideoClockObservation = null
                    handleQueuedHuyaMediaItemTransition(trackedPlayer, mediaItem, reason)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (trackedPlayer === player) {
                        if (playbackState != Player.STATE_BUFFERING) {
                            legacyHuyaTransportReconnectStartedAtElapsedMs = 0L
                        }
                        if (playbackState != Player.STATE_ENDED) {
                            endedRecoveryWatchdogJob?.cancel()
                            endedRecoveryWatchdogJob = null
                        }
                        DebugReporter.recordLivePlaybackEvent(
                            "playback_state",
                            currentChannel,
                            mapOf(
                                "state" to playbackStateName(playbackState),
                                "playWhenReady" to trackedPlayer.playWhenReady,
                                "isPlaying" to trackedPlayer.isPlaying,
                                "playbackPositionMs" to trackedPlayer.currentPosition
                            )
                        )
                        if (playbackState == Player.STATE_READY) {
                            recordHuyaRenewalStage("ready")
                            if (trackedPlayer === player) maybeScheduleShortHuyaContinuation()
                        }
                    }
                    when {
                        trackedPlayer === player -> handleActivePlaybackStateChanged(playbackState)
                        trackedPlayer === standbyPlayer -> handleStandbyPlaybackStateChanged(playbackState)
                    }
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    if (trackedPlayer === player) {
                        legacyHuyaTransportReconnectStartedAtElapsedMs = 0L
                        val httpError = findHttpResponseError(error)
                        val details = linkedMapOf<String, Any?>(
                            "errorCode" to error.errorCode,
                            "errorName" to error.errorCodeName
                        )
                        httpError?.let {
                            details["httpStatusCode"] = it.responseCode
                            details["httpErrorType"] = it.javaClass.simpleName
                        }
                        DebugReporter.recordLivePlaybackEvent(
                            "player_error",
                            currentChannel,
                            details
                        )
                    }
                    when {
                        trackedPlayer === player -> handlePlayerError(error)
                        trackedPlayer === standbyPlayer -> handleStandbyPlayerError(error)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (trackedPlayer !== player) return
                    updateMobileInfo()
                    updatePipActions()
                    if (isPlaying) maybeRecordSuccessfulPlayback() else {
                        historyCommitJob?.cancel(); historyCommitJob = null
                    }
                    Log.d(
                        TAG,
                        "onIsPlayingChanged channel=${currentChannel?.name} " +
                            "isPlaying=$isPlaying state=${player?.playbackState} " +
                            "position=${player?.currentPosition}"
                    )
                    binding.playPauseButton.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    )
                }

            })
            addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadCompleted(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData
                ) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    val playbackInfo = currentPlaybackInfo ?: return
                    if (!playbackInfo.resolvedBy.startsWith("huya:")) return
                    val uri = loadEventInfo.uri
                    val mediaStartTimeMs = mediaLoadData.mediaStartTimeMs
                    val mediaEndTimeMs = mediaLoadData.mediaEndTimeMs
                    activeHuyaHttpResponseComplete = true
                    if (mediaStartTimeMs != C.TIME_UNSET && mediaEndTimeMs != C.TIME_UNSET &&
                        mediaEndTimeMs > mediaStartTimeMs
                    ) {
                        activeHuyaLoadedDurationMs = mediaEndTimeMs - mediaStartTimeMs
                    }
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_media_load_completed",
                        currentChannel,
                        mapOf(
                            "resolvedBy" to playbackInfo.resolvedBy,
                            "mimeType" to playbackInfo.mimeType,
                            "uriScheme" to uri.scheme,
                            "uriHost" to uri.host,
                            "bytesLoaded" to loadEventInfo.bytesLoaded,
                            "loadDurationMs" to loadEventInfo.loadDurationMs,
                            "dataSpecLength" to loadEventInfo.dataSpec.length,
                            "loadDataType" to mediaLoadData.dataType,
                            "trackType" to mediaLoadData.trackType,
                            "mediaStartTimeMs" to mediaLoadData.mediaStartTimeMs,
                            "mediaEndTimeMs" to mediaLoadData.mediaEndTimeMs,
                            "sourceAgeMs" to (System.currentTimeMillis() - activeMediaSourceStartedAtMs)
                                .coerceAtLeast(0L)
                        )
                    )
                    maybeScheduleShortHuyaContinuation()
                }

                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean
                ) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    val playbackInfo = currentPlaybackInfo ?: return
                    if (!playbackInfo.resolvedBy.startsWith("huya:")) return
                    val unsupportedVideoFormatId = unsupportedFlvVideoFormatId(error)
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_media_load_error",
                        currentChannel,
                        mapOf(
                            "resolvedBy" to playbackInfo.resolvedBy,
                            "mimeType" to playbackInfo.mimeType,
                            "uriHost" to loadEventInfo.uri.host,
                            "bytesLoaded" to loadEventInfo.bytesLoaded,
                            "loadDurationMs" to loadEventInfo.loadDurationMs,
                            "loadDataType" to mediaLoadData.dataType,
                            "dataSpecPosition" to (findHttpResponseError(error)?.dataSpec?.position
                                ?: loadEventInfo.dataSpec.position),
                            "dataSpecLength" to (findHttpResponseError(error)?.dataSpec?.length
                                ?: loadEventInfo.dataSpec.length),
                            "errorType" to error.javaClass.simpleName,
                            "errorCategory" to if (unsupportedVideoFormatId != null) {
                                "unsupported_video_format"
                            } else {
                                "transport_or_http"
                            },
                            "videoFormatId" to unsupportedVideoFormatId,
                            "httpStatusCode" to
                                (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode,
                            "wasCanceled" to wasCanceled
                        )
                    )
                }

                override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    val supportedVideoTracks = videoGroups.sumOf { group ->
                        (0 until group.length).count { index -> group.isTrackSupported(index) }
                    }
                    val monitoredVideoTracks = monitoredVideoTrackCount(
                        supportedTrackCount = supportedVideoTracks,
                        videoSizeObserved = activeVideoSizeSeenForSource,
                        isLegacyTv = DeviceProfile.isLegacyTv(this@PlayerActivity),
                        expectedVideoTrack = shouldAssumeLegacyHuyaVideoTrack()
                    )
                    if (monitoredVideoTracks != activeVideoTrackCount) {
                        activeVideoTrackCount = monitoredVideoTracks
                        videoFrameMonitoringStartedAtMs =
                            if (monitoredVideoTracks > 0) System.currentTimeMillis() else 0L
                        lastRenderedVideoFrameRealtimeMs = 0L
                    }
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    val supportedAudioTrackCount = audioGroups.sumOf { group ->
                        (0 until group.length).count { index -> group.isTrackSupported(index) }
                    }
                    activeAudioTrackCount = supportedAudioTrackCount
                    maybeRecordSuccessfulPlayback()
                    if (supportedAudioTrackCount == 0 && trackedPlayer.playbackState == Player.STATE_READY) {
                        scheduleHuyaMissingAudioRecovery()
                    }
                    maybeScheduleShortHuyaContinuation()
                    val audioTracks = audioGroups
                        .flatMap { group ->
                            (0 until group.length).map { index ->
                                val format = group.getTrackFormat(index)
                                mapOf(
                                    "selected" to group.isTrackSelected(index),
                                    "supported" to group.isTrackSupported(index),
                                    "mimeType" to format.sampleMimeType,
                                    "codecs" to format.codecs,
                                    "sampleRate" to format.sampleRate,
                                    "channelCount" to format.channelCount,
                                    "bitrate" to format.bitrate
                                )
                            }
                        }
                    DebugReporter.recordLivePlaybackEvent(
                        "audio_tracks",
                        currentChannel,
                        mapOf(
                            "trackCount" to audioTracks.size,
                            "supportedTrackCount" to supportedAudioTrackCount,
                            "audioTracks" to audioTracks
                        )
                    )
                    DebugReporter.recordLivePlaybackEvent(
                        "video_tracks",
                        currentChannel,
                        mapOf(
                            "trackCount" to videoGroups.sumOf { it.length },
                            "supportedTrackCount" to supportedVideoTracks
                        )
                    )
                }

                override fun onVideoSizeChanged(
                    eventTime: AnalyticsListener.EventTime,
                    videoSize: VideoSize
                ) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    if (videoSize.width <= 0 || videoSize.height <= 0) return
                    if (DeviceProfile.isLegacyTv(this@PlayerActivity)) {
                        noteLegacyVideoTrackObserved()
                    }
                    val surface = playerView.videoSurfaceView
                    val surfaceFrame = (surface as? SurfaceView)?.holder?.surfaceFrame
                    DebugReporter.recordLivePlaybackEvent(
                        "video_size",
                        currentChannel,
                        mapOf(
                            "width" to videoSize.width,
                            "height" to videoSize.height,
                            "pixelWidthHeightRatio" to videoSize.pixelWidthHeightRatio,
                            "surfaceWidth" to (surface?.width ?: 0),
                            "surfaceHeight" to (surface?.height ?: 0),
                            "surfaceFrameWidth" to (surfaceFrame?.width() ?: 0),
                            "surfaceFrameHeight" to (surfaceFrame?.height() ?: 0),
                            "playerViewWidth" to playerView.width,
                            "playerViewHeight" to playerView.height,
                            "legacyTv" to DeviceProfile.isLegacyTv(this@PlayerActivity),
                            "surfaceLayoutRepairRequestedOnVideoSize" to
                                DeviceProfile.isLegacyTv(this@PlayerActivity)
                        )
                    )
                    if (DeviceProfile.isLegacyTv(this@PlayerActivity)) {
                        requestLegacySurfaceLayoutRepair("video_size_changed")
                    }
                }

                override fun onVideoFrameProcessingOffset(
                    eventTime: AnalyticsListener.EventTime,
                    totalProcessingOffsetUs: Long,
                    frameCount: Int
                ) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime) || frameCount <= 0) return
                    val now = System.currentTimeMillis()
                    if (DeviceProfile.isLegacyTv(this@PlayerActivity)) {
                        noteLegacyVideoTrackObserved()
                        playerView.setKeepContentOnPlayerReset(true)
                    }
                    if (videoFrameMonitoringStartedAtMs == 0L) {
                        videoFrameMonitoringStartedAtMs = now
                    }
                    lastRenderedVideoFrameRealtimeMs = now
                    if (now - lastVideoFrameDiagnosticAtMs >= VIDEO_FRAME_DIAGNOSTIC_INTERVAL_MS) {
                        lastVideoFrameDiagnosticAtMs = now
                        DebugReporter.recordLivePlaybackEvent(
                            "video_frame_progress",
                            currentChannel,
                            mapOf(
                                "frameCount" to frameCount,
                                "processingOffsetUs" to totalProcessingOffsetUs,
                                "playbackPositionMs" to trackedPlayer.currentPosition
                            )
                        )
                    }
                }

                override fun onRenderedFirstFrame(
                    eventTime: AnalyticsListener.EventTime,
                    output: Any,
                    renderTimeMs: Long
                ) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    historyFirstFrameMediaId = trackedPlayer.currentMediaItem?.mediaId
                    maybeRecordSuccessfulPlayback()
                    val now = System.currentTimeMillis()
                    if (DeviceProfile.isLegacyTv(this@PlayerActivity)) {
                        noteLegacyVideoTrackObserved()
                        // SurfaceView may retain the previous channel's
                        // buffer geometry even after its first new frame.
                        // Reassert the current layout once the renderer has
                        // actually attached a frame, without detaching the
                        // MStar decoder or Surface.
                        requestLegacySurfaceLayoutRepair("first_frame_rendered")
                    }
                    if (videoFrameMonitoringStartedAtMs == 0L) {
                        videoFrameMonitoringStartedAtMs = now
                    }
                    lastRenderedVideoFrameRealtimeMs = now
                    val renewalFirstFrameLatency = if (
                        pendingHuyaRenewalFirstFrameChannelId == currentChannel?.id &&
                        pendingHuyaRenewalFirstFrameAtMs > 0L
                    ) {
                        now - pendingHuyaRenewalFirstFrameAtMs
                    } else {
                        null
                    }
                    val firstFrameDetails = videoSurfaceGeometryDetails().toMutableMap()
                    firstFrameDetails["renderTimeMs"] = renderTimeMs
                    renewalFirstFrameLatency?.let {
                        firstFrameDetails["renewalFirstFrameLatencyMs"] = it
                    }
                    firstFrameDetails.putAll(huyaRenewalTimingDetails("first_frame"))
                    DebugReporter.recordLivePlaybackEvent(
                        "video_first_frame",
                        currentChannel,
                        firstFrameDetails
                    )
                    if (renewalFirstFrameLatency != null) {
                        pendingHuyaRenewalFirstFrameAtMs = 0L
                        pendingHuyaRenewalFirstFrameSeen = true
                        if (pendingHuyaRenewalAudioAdvanced) resetHuyaRenewalTiming()
                    }
                }

                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long
                ) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    DebugReporter.recordLivePlaybackEvent(
                        "video_decoder_initialized",
                        currentChannel,
                        mapOf("decoderName" to decoderName, "decoderInitMs" to initializationDurationMs) +
                            huyaRenewalTimingDetails("video_decoder_initialized")
                    )
                }

                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long
                ) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    DebugReporter.recordLivePlaybackEvent(
                        "audio_decoder_initialized",
                        currentChannel,
                        mapOf("decoderName" to decoderName, "decoderInitMs" to initializationDurationMs)
                    )
                }

                override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    recordAudioFormat("audio_input_format", format)
                }

                override fun onAudioPositionAdvancing(
                    eventTime: AnalyticsListener.EventTime,
                    playoutStartSystemTimeMs: Long
                ) {
                    if (trackedPlayer === standbyPlayer && isCurrentPlaybackEvent(eventTime, trackedPlayer)) {
                        standbyAudioClockAdvanced = true
                        return
                    }
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    val firstAdvancingCallback = !activeAudioPlaybackAdvanced
                    activeAudioPlaybackAdvanced = true
                    maybeRecordSuccessfulPlayback()
                    if (firstAdvancingCallback) {
                        activeAudioAdvancingAtNanos = System.nanoTime()
                        legacyHuyaPcmEvidence.reset()
                        recordHuyaRenewalStage("audio_advancing")
                        pendingHuyaRenewalAudioAdvanced = true
                        DebugReporter.recordLivePlaybackEvent(
                            "audio_position_advancing",
                            currentChannel,
                            mapOf("playoutStartSystemTimeMs" to playoutStartSystemTimeMs) +
                                huyaRenewalTimingDetails("audio_advancing")
                        )
                        if (pendingHuyaRenewalFirstFrameSeen) resetHuyaRenewalTiming()
                    }
                    // A running audio clock is positive pipeline evidence,
                    // even if the programme is quiet or PCM monitoring is bypassed.
                    if (!activeAudioPcmObserved) scheduleHuyaMissingAudioRecovery()
                }

                override fun onAudioUnderrun(
                    eventTime: AnalyticsListener.EventTime,
                    bufferSize: Int,
                    bufferSizeMs: Long,
                    elapsedSinceLastFeedMs: Long
                ) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    DebugReporter.recordLivePlaybackEvent(
                        "audio_underrun",
                        currentChannel,
                        mapOf(
                            "underrunBufferSize" to bufferSize,
                            "underrunBufferMs" to bufferSizeMs,
                            "elapsedSinceLastFeedMs" to elapsedSinceLastFeedMs
                        )
                    )
                }

                override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                    if (trackedPlayer === player && isCurrentPlaybackEvent(eventTime)) {
                        recordAudioFailure("audio_sink_error", audioSinkError)
                    }
                }

                override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
                    if (trackedPlayer === player && isCurrentPlaybackEvent(eventTime)) {
                        recordAudioFailure("audio_codec_error", audioCodecError)
                    }
                }

                override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, counters: com.google.android.exoplayer2.decoder.DecoderCounters) {
                    if (trackedPlayer !== player || !isCurrentPlaybackEvent(eventTime)) return
                    DebugReporter.recordLivePlaybackEvent("audio_disabled", currentChannel)
                }
            })
        }
    }

    private fun isCurrentPlaybackEvent(eventTime: AnalyticsListener.EventTime, eventPlayer: ExoPlayer? = player): Boolean {
        val activeMediaId = eventPlayer?.currentMediaItem?.mediaId ?: return true
        val eventMediaId = runCatching {
            val timeline = eventTime.timeline
            if (timeline.isEmpty || eventTime.windowIndex !in 0 until timeline.windowCount) {
                null
            } else {
                timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem.mediaId
            }
        }.getOrNull()
        return isCurrentMediaItemEvent(eventMediaId, activeMediaId)
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown:$state"
    }

    private fun recordAudioFormat(eventType: String, format: Format) {
        DebugReporter.recordLivePlaybackEvent(
            eventType,
            currentChannel,
            mapOf(
                "mimeType" to format.sampleMimeType,
                "codecs" to format.codecs,
                "sampleRate" to format.sampleRate,
                "channelCount" to format.channelCount,
                "bitrate" to format.bitrate
            )
        )
    }

    private fun recordAudioFailure(eventType: String, error: Exception) {
        if (player == null) return
        DebugReporter.recordLivePlaybackEvent(
            eventType,
            currentChannel,
            mapOf(
                "errorName" to error.javaClass.simpleName
            )
        )
    }

    private fun huyaRenewalTimingDetails(stage: String): Map<String, Any?> {
        if (pendingHuyaRenewalFirstFrameChannelId != currentChannel?.id ||
            pendingHuyaRenewalStartedAtElapsedMs <= 0L
        ) return emptyMap()

        val now = SystemClock.elapsedRealtime()
        return mapOf(
            "stage" to stage,
            "renewalElapsedMs" to (now - pendingHuyaRenewalStartedAtElapsedMs).coerceAtLeast(0L),
            "lastFrameToRenewalStartMs" to pendingHuyaRenewalLastFrameAtMs
                .takeIf { it > 0L }
                ?.let { (pendingHuyaRenewalFirstFrameAtMs - it).coerceAtLeast(0L) },
            "lastFrameToStageMs" to pendingHuyaRenewalLastFrameAtMs
                .takeIf { it > 0L }
                ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) },
            "resolveToStageMs" to pendingHuyaRenewalResolvedAtElapsedMs
                .takeIf { it > 0L }
                ?.let { (now - it).coerceAtLeast(0L) },
            "sourceApplyToStageMs" to pendingHuyaRenewalSourceAppliedAtElapsedMs
                .takeIf { it > 0L }
                ?.let { (now - it).coerceAtLeast(0L) },
            "wasPrewarmed" to pendingHuyaRenewalCandidateWasPrewarmed,
            "usedPreconnectedDataSource" to pendingHuyaRenewalUsedPreconnectedDataSource
        )
    }

    private fun recordHuyaRenewalStage(stage: String) {
        val details = huyaRenewalTimingDetails(stage)
        if (details.isEmpty()) return
        DebugReporter.recordLivePlaybackEvent("huya_renewal_stage", currentChannel, details)
    }

    private fun markHuyaRenewalSourceApplied(usedPreconnectedDataSource: Boolean) {
        if (pendingHuyaRenewalFirstFrameChannelId != currentChannel?.id ||
            pendingHuyaRenewalStartedAtElapsedMs <= 0L
        ) return
        pendingHuyaRenewalSourceAppliedAtElapsedMs = SystemClock.elapsedRealtime()
        pendingHuyaRenewalUsedPreconnectedDataSource = usedPreconnectedDataSource
        recordHuyaRenewalStage("source_applied")
    }

    private fun resetHuyaRenewalTiming() {
        pendingHuyaRenewalFirstFrameAtMs = 0L
        pendingHuyaRenewalLastFrameAtMs = 0L
        pendingHuyaRenewalFirstFrameChannelId = -1L
        pendingHuyaRenewalStartedAtElapsedMs = 0L
        pendingHuyaRenewalResolvedAtElapsedMs = 0L
        pendingHuyaRenewalSourceAppliedAtElapsedMs = 0L
        pendingHuyaRenewalCandidateWasPrewarmed = false
        pendingHuyaRenewalUsedPreconnectedDataSource = false
        pendingHuyaRenewalFirstFrameSeen = false
        pendingHuyaRenewalAudioAdvanced = false
    }

    private fun handleActivePlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                showBuffering()
                scheduleBufferingRecovery()
            }
            Player.STATE_READY -> {
                Log.d(TAG, "Playback ready for channel=${currentChannel?.name}")
                refreshActiveTrackCountsFromCurrentPlayer()
                DebugReporter.markPlaybackReady()
                douyuProtectionRetryCount = 0
                douyuProtectionRetryJob?.cancel()
                douyuProtectionRetryJob = null
                activePlaybackStartedAtMs = System.currentTimeMillis()
                notePlaybackProgress(force = true)
                hideBuffering()
                scheduleRetryStabilityReset()
                scheduleHuyaRecoveryPrewarm(currentChannel, currentPlaybackInfo)
                if (!activeAudioPcmObserved) scheduleHuyaMissingAudioRecovery()
                scheduleHuyaStandbyPreparation(currentChannel, currentPlaybackInfo)
            }
            Player.STATE_ENDED -> onPlaybackEnded()
            else -> Unit
        }
    }

    /**
     * Analytics events can arrive with an empty/stale timeline while a live
     * source is being replaced. Take the active ExoPlayer's track snapshot at
     * READY as the authoritative fallback so silent-audio and video-stall
     * recovery do not depend on receiving a perfectly timed analytics event.
     */
    private fun refreshActiveTrackCountsFromCurrentPlayer() {
        val activePlayer = player ?: return
        val tracks = activePlayer.currentTracks
        val videoTrackCount = tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .sumOf { group -> (0 until group.length).count { group.isTrackSupported(it) } }
        val audioTrackCount = tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .sumOf { group -> (0 until group.length).count { group.isTrackSupported(it) } }

        val monitoredVideoTracks = monitoredVideoTrackCount(
            supportedTrackCount = videoTrackCount,
            videoSizeObserved = activeVideoSizeSeenForSource,
            isLegacyTv = DeviceProfile.isLegacyTv(this),
            expectedVideoTrack = shouldAssumeLegacyHuyaVideoTrack()
        )
        if (monitoredVideoTracks != activeVideoTrackCount) {
            activeVideoTrackCount = monitoredVideoTracks
            videoFrameMonitoringStartedAtMs =
                if (monitoredVideoTracks > 0) System.currentTimeMillis() else 0L
            lastRenderedVideoFrameRealtimeMs = 0L
        }
        activeAudioTrackCount = audioTrackCount
        DebugReporter.recordLivePlaybackEvent(
            "ready_track_snapshot",
            currentChannel,
            mapOf(
                "supportedAudioTrackCount" to audioTrackCount,
                "supportedVideoTrackCount" to videoTrackCount,
                "videoSizeFallbackActive" to (videoTrackCount == 0 && activeVideoSizeSeenForSource)
            )
        )
    }

    /**
     * Some vendor builds briefly expose an empty TrackGroup snapshot while
     * SurfaceView already reports a real video size or rendered frame. Keep
     * the frame watchdog armed from those direct renderer signals so a later
     * silent/frozen FLV cannot sit forever with audio-only progress.
     */
    private fun noteLegacyVideoTrackObserved() {
        activeVideoSizeSeenForSource = true
        if (activeVideoTrackCount == 0) {
            activeVideoTrackCount = 1
            videoFrameMonitoringStartedAtMs = System.currentTimeMillis()
            lastRenderedVideoFrameRealtimeMs = 0L
        }
    }

    private fun armLegacyHuyaFrameWatchdog(playbackInfo: PlaybackStreamInfo) {
        if (!DeviceProfile.isLegacyTv(this) ||
            !playbackInfo.resolvedBy.startsWith("huya:") ||
            playbackInfo.mimeType != "video/x-flv"
        ) return

        // Some MStar FLV extractor builds omit/late-deliver track events. Arm
        // frame monitoring from the known stream type so a READY-but-frozen
        // video cannot wait forever for a TrackGroup callback to start it.
        activeVideoTrackCount = maxOf(activeVideoTrackCount, 1)
        videoFrameMonitoringStartedAtMs = System.currentTimeMillis()
        lastRenderedVideoFrameRealtimeMs = 0L
        DebugReporter.recordLivePlaybackEvent(
            "legacy_huya_frame_watchdog_armed",
            currentChannel,
            mapOf(
                "resolvedBy" to playbackInfo.resolvedBy,
                "watchdogTimeoutMs" to LEGACY_HUYA_PLAYBACK_STALL_TIMEOUT_MS,
                "reason" to "known_huya_flv_source"
            )
        )
    }

    private fun handleStandbyPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_READY) return
        val channel = currentChannel ?: return
        val playbackInfo = currentPlaybackInfo ?: return
        if (!playbackInfo.resolvedBy.startsWith("huya:") || standbyPlaybackInfo == null) return
        standbyPlaybackReadyAtMs = System.currentTimeMillis()
        Log.d(TAG, "Standby Huya playback ready for channel=${channel.name}")
        scheduleHuyaStandbySwap(channel)
    }

    private fun setupUi() {
        binding.playerRoot.isFocusable = true
        binding.playerRoot.isFocusableInTouchMode = true

        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.stopButton.setOnClickListener { openActionMenu() }
        binding.previousButton.setOnClickListener { viewModel.playPreviousChannel() }
        binding.nextButton.setOnClickListener { viewModel.playNextChannel() }
        binding.channelInfoButton.setOnClickListener { showPlaybackChrome() }
        binding.qualityButton.setOnClickListener { showQualityOptions() }
        binding.audioButton.setOnClickListener { showToast("音频选项 - 开发中") }
        binding.subtitleButton.setOnClickListener { toggleSubtitles() }
        binding.fullscreenButton.setOnClickListener { if (phone) handleMobileAction(if (mobileFullscreen) "normal" else "fullscreen") }
        binding.previousSourceButton.setOnClickListener { viewModel.switchSource(-1, currentChannel) }
        binding.nextSourceButton.setOnClickListener { viewModel.switchSource(1, currentChannel) }

        channelGroupAdapter = ChannelGroupAdapter { index ->
            viewModel.selectGroup(index)
        }
        binding.groupRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.groupRecyclerView.adapter = channelGroupAdapter
        binding.groupRecyclerView.setHasFixedSize(true)
        binding.groupRecyclerView.itemAnimator = null
        binding.groupRecyclerView.setItemViewCacheSize(if (isLowEndTvDevice) 8 else 24)

        channelListAdapter = ChannelListAdapter { channel ->
            viewModel.selectMergedChannel(channel.key)
            viewModel.closeSelector()
            showPlaybackChrome()
        }
        channelListAdapter.setAliases(channelAliases)
        binding.channelRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.channelRecyclerView.adapter = channelListAdapter
        binding.channelRecyclerView.setHasFixedSize(true)
        binding.channelRecyclerView.itemAnimator = null
        binding.channelRecyclerView.setItemViewCacheSize(if (isLowEndTvDevice) 16 else 48)
        binding.groupRecyclerView.preserveFocusAfterLayout = true
        binding.channelRecyclerView.preserveFocusAfterLayout = true

        binding.channelListView.visibility = View.GONE
        binding.actionMenuView.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.sourceSwitchContainer.visibility = View.GONE
        binding.channelLogo.visibility = if (preferences.showChannelLogos && !isLowEndTvDevice) View.VISIBLE else View.GONE
        showPrimaryQuickActions()
        updateQuickFavoriteButton()
        updatePersistentChannelName()
        if (phone) {
            binding.controlPanel.visibility = View.GONE
            binding.infoPanel.visibility = View.GONE
        }
        if (!phone) TvFocusStyler.apply(binding.root)
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.currentMergedChannel.collectLatest { merged ->
                        currentMergedChannel = merged
                        refreshChannelListSelection()
                        updateSourceUi(merged, viewModel.currentSource.value)
                    }
                }
                launch {
                    viewModel.currentSource.collectLatest { source ->
                        updateSourceUi(currentMergedChannel, source)
                    }
                }
                launch {
                    viewModel.currentChannel.collectLatest { channel ->
                        channel?.let { playChannel(it) }
                    }
                }
                launch {
                    viewModel.playbackError.collectLatest {
                        it?.let { message -> showStatusHint(message, STATUS_HINT_LONG_MS) }
                    }
                }
                launch {
                    viewModel.buffering.collectLatest { if (it) showBuffering() else hideBuffering() }
                }
                launch {
                    viewModel.channelGroups.collectLatest { groups ->
                        val manager = binding.groupRecyclerView.layoutManager as? LinearLayoutManager
                        val firstVisible = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                        val anchorName = channelGroupAdapter.keyAt(firstVisible)
                        val anchorOffset = manager?.findViewByPosition(firstVisible)?.top ?: 0
                        val selectedGroupName = viewModel.channelGroups.value
                            .getOrNull(viewModel.selectedGroupIndex.value)?.name
                        channelGroupAdapter.submitList(groups)
                        channelGroupAdapter.updateSelection(viewModel.selectedGroupIndex.value)
                        val anchorIndex = restoredScrollAnchorIndex(
                            anchorName,
                            groups.map { it.name },
                            fallbackIndex = firstVisible.takeIf { it != RecyclerView.NO_POSITION }
                                ?: viewModel.selectedGroupIndex.value
                        )
                        if (anchorIndex != null && viewModel.selectorVisible.value) {
                            binding.groupRecyclerView.post {
                                val currentGroups = viewModel.channelGroups.value
                                val currentSelectedName = currentGroups
                                    .getOrNull(viewModel.selectedGroupIndex.value)?.name
                                if (viewModel.selectorVisible.value &&
                                    currentGroups === groups &&
                                    currentSelectedName == selectedGroupName
                                ) {
                                    manager?.scrollToPositionWithOffset(anchorIndex, anchorOffset)
                                }
                            }
                        }
                        // A catalog/probe refresh must not scroll the D-pad
                        // cursor. Explicit selectedGroupIndex changes and
                        // opening the drawer already perform the required
                        // scroll; doing it for every list emission steals the
                        // user's browse position during background retries.
                    }
                }
                launch {
                    viewModel.selectedGroupIndex.collectLatest { index ->
                        channelGroupAdapter.updateSelection(index)
                        if (viewModel.selectorVisible.value &&
                            viewModel.selectorPane.value == PlayerViewModel.SelectorPane.GROUPS
                        ) {
                            val groupKey = viewModel.channelGroups.value.getOrNull(index)?.name
                            if (shouldScrollToStableSelection(lastScrolledGroupKey, groupKey)) {
                                binding.groupRecyclerView.post {
                                    val latestIndex = viewModel.selectedGroupIndex.value
                                    val latestKey = viewModel.channelGroups.value
                                        .getOrNull(latestIndex)?.name
                                    if (viewModel.selectorVisible.value &&
                                        viewModel.selectorPane.value == PlayerViewModel.SelectorPane.GROUPS &&
                                        shouldScrollToStableSelection(lastScrolledGroupKey, latestKey)
                                    ) {
                                        binding.groupRecyclerView.scrollToPosition(latestIndex)
                                        lastScrolledGroupKey = latestKey
                                    }
                                }
                            }
                        }
                    }
                }
                launch {
                    viewModel.visibleChannels.collectLatest { channels ->
                        val manager = binding.channelRecyclerView.layoutManager as? LinearLayoutManager
                        val selectedGroupName = viewModel.channelGroups.value
                            .getOrNull(viewModel.selectedGroupIndex.value)?.name
                        val selectedChannelIndex = viewModel.selectedChannelIndex.value
                        val selectedChannelKey = channels.getOrNull(selectedChannelIndex)?.key
                        channelListAdapter.submitList(channels)
                        refreshChannelListSelection(channels)
                        if (selectedChannelKey != null && viewModel.selectorVisible.value) {
                            binding.channelRecyclerView.post {
                                val currentGroups = viewModel.channelGroups.value
                                val currentSelectedGroupName = currentGroups
                                    .getOrNull(viewModel.selectedGroupIndex.value)?.name
                                if (viewModel.selectorVisible.value &&
                                    viewModel.visibleChannels.value === channels &&
                                    currentSelectedGroupName == selectedGroupName &&
                                    viewModel.selectedChannelIndex.value == selectedChannelIndex &&
                                    viewModel.visibleChannels.value
                                        .getOrNull(selectedChannelIndex)?.key == selectedChannelKey
                                ) {
                                    manager?.scrollToPositionWithOffset(selectedChannelIndex, 0)
                                    lastScrolledChannelKey = selectedChannelKey
                                }
                            }
                        }
                        // The selected row is the D-pad cursor. Restoring an
                        // old viewport anchor can leave it off-screen after
                        // opening the drawer or refreshing a category.
                    }
                }
                launch {
                    viewModel.selectedChannelIndex.collectLatest { index ->
                        refreshChannelListSelection()
                        if (index >= 0 && viewModel.selectorVisible.value &&
                            viewModel.selectorPane.value == PlayerViewModel.SelectorPane.CHANNELS
                        ) {
                            val channelKey = viewModel.visibleChannels.value.getOrNull(index)?.key
                            if (shouldScrollToStableSelection(lastScrolledChannelKey, channelKey)) {
                                binding.channelRecyclerView.post {
                                    val latestIndex = viewModel.selectedChannelIndex.value
                                    val latestKey = viewModel.visibleChannels.value
                                        .getOrNull(latestIndex)?.key
                                    if (latestIndex >= 0 && viewModel.selectorVisible.value &&
                                        viewModel.selectorPane.value == PlayerViewModel.SelectorPane.CHANNELS &&
                                        shouldScrollToStableSelection(lastScrolledChannelKey, latestKey)
                                    ) {
                                        binding.channelRecyclerView.scrollToPosition(latestIndex)
                                        lastScrolledChannelKey = latestKey
                                    }
                                }
                            }
                        }
                    }
                }
                launch {
                    viewModel.selectorVisible.collectLatest { visible ->
                        binding.channelListView.visibility = if (visible) View.VISIBLE else View.GONE
                        if (visible) {
                            closeActionMenu()
                            cancelHideUi()
                            cancelBufferingRecovery()
                            binding.groupRecyclerView.post {
                                if (viewModel.selectorVisible.value) {
                                    val index = viewModel.selectedGroupIndex.value
                                    val groupKey = viewModel.channelGroups.value.getOrNull(index)?.name
                                    if (groupKey != null) {
                                        lastScrolledGroupKey = groupKey
                                        (binding.groupRecyclerView.layoutManager as? LinearLayoutManager)
                                            ?.scrollToPositionWithOffset(index, 0)
                                    }
                                }
                            }
                            binding.channelRecyclerView.post {
                                if (viewModel.selectorVisible.value &&
                                    viewModel.selectorPane.value == PlayerViewModel.SelectorPane.CHANNELS
                                ) {
                                    val index = viewModel.selectedChannelIndex.value
                                    lastScrolledChannelKey = viewModel.visibleChannels.value
                                        .getOrNull(index)?.key
                                    binding.channelRecyclerView.scrollToPosition(index)
                                }
                            }
                        } else if (!actionMenuVisible) {
                            resumeDeferredRecoveryChecks()
                            scheduleUiHide()
                        }
                    }
                }
                launch {
                    viewModel.selectorPane.collectLatest { pane ->
                        val groupsFocused = pane == PlayerViewModel.SelectorPane.GROUPS
                        channelGroupAdapter.updatePaneFocus(groupsFocused)
                        channelListAdapter.updatePaneFocus(!groupsFocused)
                        updateSelectorPaneFocus(groupsFocused)
                    }
                }
                if (!isLowEndTvDevice) {
                    launch {
                        while (true) {
                            kotlinx.coroutines.delay(30_000L)
                            if (!viewModel.selectorVisible.value && !actionMenuVisible && player?.playbackState != Player.STATE_BUFFERING) {
                                viewModel.loadChannelList()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun playChannel(
        channel: Channel,
        isRetry: Boolean = false,
        forcePlayerRecreate: Boolean = false,
        isProactiveRefresh: Boolean = false,
        seamlessRetry: Boolean = false,
        huyaSchemeOverride: String? = null,
        resolvedPlaybackInfo: PlaybackStreamInfo? = null
    ) {
        if (timedExitRequested || isFinishing || isDestroyed) return
        Log.d(TAG, "playChannel name=${channel.name}, id=${channel.id}, url=${channel.url}")
        if (!isRetry && !isProactiveRefresh) {
            historyCommitJob?.cancel()
            historyCommitJob = null
            historyRecordedUrl = null
            historyFirstFrameMediaId = null
            audioProtectionAttempts = 0
            discardQueuedHuyaRenewal()
            clearDeferredPlaybackError("manual channel/source selection")
            resetHuyaMissingAudioRetryState()
            pendingHuyaRenewalFirstFrameAtMs = 0L
            pendingHuyaRenewalFirstFrameChannelId = -1L
            resetHuyaRenewalTiming()
        }
        val previousChannelId = currentChannel?.id
        val isAutomaticFailover = autoFailoverInProgress
        autoFailoverInProgress = false
        val sameLogicalChannel = currentMergedChannel?.sources?.any { it.channel.id == channel.id } == true
        val isSourceReplacement = previousChannelId != null && previousChannelId != channel.id
        if (!isRetry && !isProactiveRefresh) {
            // A manual selection starts a fresh retry session, including when
            // the user selects a different source of the same logical channel.
            cancelPersistentSourceRetry()
            resetLegacyHlsRecoveryState()
        }
        if (!isRetry && !isProactiveRefresh && !sameLogicalChannel) {
            failedPlaybackSourceIds.clear()
            huyaForceFlvForRecovery = false
            huyaForceHlsForRecovery = false
            huyaUnsupportedFlvVideoFormatId = null
        }
        if (!isRetry && !isProactiveRefresh && !isAutomaticFailover) {
            // 用户主动换台后，重新允许后续自动故障转移。
            autoFailedChannelKeys.clear()
            autoFailoverAttemptCount = 0
        }
        if (!isRetry && !isProactiveRefresh) {
            // The FLV downgrade is only a same-channel recovery. Manual
            // selection starts again with the configured scheme.
            huyaForceFlvForRecovery = false
            huyaForceHlsForRecovery = false
            huyaUnsupportedFlvVideoFormatId = null
            huyaHlsFlvFallbackAttempted = false
            legacyHuyaFlvForbiddenReconnectCount = 0
            legacyHuyaHlsFallbackAttempted = false
        }
        prepareChannelPlaybackSettings(channel)
        if (!isRetry && !isProactiveRefresh) {
            // A manual re-selection of the same channel is a fresh recovery
            // session too.  Do not carry an old retry budget into it.
            resetPlaybackRetries()
            resetDouyuContinuityState()
            playbackRetryChannelId = channel.id
        }
        if (currentChannel?.id != channel.id) {
            resetHuyaStandbyState()
            resetHuyaRecoveryState()
            resetDouyuContinuityState()
            behindLiveWindowRecoveryChannelId = -1L
            lastBehindLiveWindowRecoveryAtMs = 0L
        }
        currentChannel = channel
        currentChannelFavorite = channel.isFavorite
        updateQuickFavoriteButton()
        DebugReporter.beginPlayback(channel, currentDecoderMode)
        if (isRetry || isProactiveRefresh) {
            val selectedIndex = viewModel.selectedChannelIndex.value
            DebugReporter.recordLivePlaybackEvent(
                "playback_retry_selector_snapshot",
                channel,
                mapOf(
                    "isRetry" to isRetry,
                    "isProactiveRefresh" to isProactiveRefresh,
                    "selectorVisible" to viewModel.selectorVisible.value,
                    "selectedGroupIndex" to viewModel.selectedGroupIndex.value,
                    "selectedGroupName" to viewModel.channelGroups.value
                        .getOrNull(viewModel.selectedGroupIndex.value)?.name,
                    "selectedChannelIndex" to selectedIndex,
                    "selectedChannelKey" to viewModel.visibleChannels.value
                        .getOrNull(selectedIndex)?.key,
                    "visibleChannelCount" to viewModel.visibleChannels.value.size
                )
            )
        }
        viewModel.syncPlaybackContext(channel)
        binding.channelName.text = displayChannelName(channel)
        updatePersistentChannelName()
        binding.channelGroup.text = channel.group ?: "分类1"
        updateEpgInfo(channel)
        if (!isProactiveRefresh && !seamlessRetry && !isRetry) {
            revealChannelInfoOverlay()
            showBuffering()
        }

        if (preferences.showChannelLogos && !isLowEndTvDevice) {
            channel.logo?.let {
                Glide.with(this)
                    .load(it)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .dontAnimate()
                    .into(binding.channelLogo)
            } ?: binding.channelLogo.setImageResource(R.drawable.ic_channel_placeholder)
        }

        channelListAdapter.updateSelection(
            selectedKey = viewModel.visibleChannels.value.getOrNull(viewModel.selectedChannelIndex.value)?.key.orEmpty(),
            currentKey = currentMergedChannel?.key.orEmpty()
        )

        playbackJob?.cancel()
        playbackJob = lifecycleScope.launch {
            var preconnectedHuyaDataSource: PreconnectedHttpDataSource? = null
            try {
                var playbackInfo = resolvedPlaybackInfo ?: PlayerHelper.resolvePlaybackInfo(
                    channel,
                    attemptIndex = if (HuyaStreamResolver.supports(channel.getDisplayUrl())) {
                        huyaCdnRotationIndex
                    } else if (isRetry) {
                        playbackRetryCount
                    } else {
                        0
                    },
                    // A same-channel retry must not silently downgrade from
                    // the signed H5 stream to the preview/entry URL.  If the
                    // official renewal is rejected, the bounded Douyu
                    // continuity budget decides what happens next.
                    douyuOfficialOnly = DouyuStreamResolver.supports(channel.getDisplayUrl()) &&
                        (isRetry || isProactiveRefresh),
                    huyaSchemeOverride = huyaSchemeOverride ?: currentHuyaRecoverySchemeOverride(),
                    validateHuyaRedirectHost = huyaRedirectHostValidationRequired
                )
                if (currentChannel?.id != channel.id) return@launch
                val shouldValidateHuyaOpen =
                    avPrecheckEnabled &&
                        playbackInfo.resolvedBy.startsWith("huya:") &&
                        playbackInfo.mimeType == "video/x-flv"
                if (shouldValidateHuyaOpen) {
                    val firstAttempt = huyaCdnRotationIndex.coerceAtLeast(0)
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_initial_candidate_validation_started",
                        channel,
                        mapOf(
                            "attempt" to firstAttempt,
                            "resolvedBy" to playbackInfo.resolvedBy,
                            "validationMode" to "bounded_audio_and_video_keyframe_preconnect"
                        )
                    )
                    val validatedCandidate = prepareBoundedHuyaCandidate(
                        channel = channel,
                        initialInfo = playbackInfo,
                        initialAttempt = firstAttempt,
                        maximumCandidates = 3
                    )
                    if (currentChannel?.id != channel.id) {
                        validatedCandidate?.third?.let(::closePreconnectedHuyaDataSourceAsync)
                        return@launch
                    }
                    if (validatedCandidate?.third != null) {
                        playbackInfo = validatedCandidate.first
                        preconnectedHuyaDataSource = validatedCandidate.third
                        huyaCdnRotationIndex = maxOf(huyaCdnRotationIndex, validatedCandidate.second)
                        DebugReporter.recordLivePlaybackEvent(
                            "huya_initial_candidate_validated",
                            channel,
                            mapOf(
                                "attempt" to validatedCandidate.second,
                                "resolvedBy" to playbackInfo.resolvedBy,
                                "prebufferBytes" to preconnectedHuyaDataSource?.bufferedBytes,
                                "audioPayloadFound" to preconnectedHuyaDataSource?.containsAudioPayload,
                                "videoKeyframeFound" to preconnectedHuyaDataSource?.containsVideoKeyframe
                            )
                        )
                    } else {
                        DebugReporter.recordLivePlaybackEvent(
                            "huya_initial_candidate_validation_exhausted",
                            channel,
                            mapOf(
                                "attempt" to (validatedCandidate?.second ?: firstAttempt),
                                "resolvedBy" to (validatedCandidate?.first?.resolvedBy ?: playbackInfo.resolvedBy),
                                "recoveryAction" to "continue_with_last_signed_route_after_bounded_scan"
                            )
                        )
                        validatedCandidate?.third?.let(::closePreconnectedHuyaDataSourceAsync)
                    }
                    validatedCandidate?.let { playbackInfo = it.first }
                }
                // On MStar Android 6, Stagefright can SIGSEGV in
                // FrameRenderTracker while ACodec is flushing if a Huya retry
                // detaches/rebinds the active Surface. Keep the same player and
                // Surface for every Huya stream transition on this device; let
                // ExoPlayer replace its media source without an explicit
                // stop/clear/release cycle.
                val keepLegacyHuyaSurface = shouldKeepLegacyHuyaSurface(
                    isLegacyTv = DeviceProfile.isLegacyTv(this@PlayerActivity),
                    resolvedBy = playbackInfo.resolvedBy
                )
                val isLegacyChannelBoundary = DeviceProfile.isLegacyTv(this@PlayerActivity) &&
                    isSourceReplacement && !isRetry && !isProactiveRefresh
                val shouldRecreatePlayer =
                    forcePlayerRecreate ||
                        (isLowEndTvDevice && isSourceReplacement &&
                            (!keepLegacyHuyaSurface || isLegacyChannelBoundary))
                var rotatedLegacySurface = false
                if (shouldRecreatePlayer) {
                    recreatePlayer()
                    if (isLegacyChannelBoundary) {
                        rotatedLegacySurface = rotateLegacySurfaceForChannelSwitch()
                    }
                } else if (keepLegacyHuyaSurface && (forcePlayerRecreate || isSourceReplacement)) {
                    Log.w(
                        TAG,
                        "Keeping MStar Huya player/Surface attached during source replacement " +
                            "(${playbackInfo.resolvedBy})"
                    )
                    DebugReporter.recordLivePlaybackEvent(
                        "mstar_surface_preserved",
                        channel,
                        mapOf(
                            "resolvedBy" to playbackInfo.resolvedBy,
                            "recoveryAction" to "replace_media_source_without_surface_detach",
                            "layoutRefreshRequested" to false
                        )
                    )
                }
                refreshInProgress = false
                activeAudioTrackCount = 0
                activeAudioPlaybackAdvanced = false
                activeAudioPcmObserved = false
                legacyHuyaPcmEvidence.reset()
                activeVideoTrackCount = 0
                activeVideoSizeSeenForSource = false
                beginVideoFrameTrackingForNewSource()
                resetHuyaShortStreamTracking()
                currentPlaybackInfo = playbackInfo
                legacyHuyaTransportReconnectStartedAtElapsedMs = 0L
                if (huyaSameUrlReconnectBlockedUrl.isNotBlank() &&
                    huyaSameUrlReconnectBlockedUrl != playbackInfo.url
                ) {
                    huyaSameUrlReconnectBlockedUrl = ""
                }
                armLegacyHuyaFrameWatchdog(playbackInfo)
                DebugReporter.updatePlaybackInfo(playbackInfo, currentDecoderMode)
                DebugReporter.recordLivePlaybackEvent(
                    "playback_source_selected",
                    channel,
                    mapOf(
                        "resolvedBy" to playbackInfo.resolvedBy,
                        "mimeType" to playbackInfo.mimeType,
                        "requestedHuyaScheme" to currentHuyaScheme,
                        "decoderMode" to currentDecoderMode
                    )
                )
                if (!playbackInfo.resolvedBy.startsWith("huya:")) {
                    resetHuyaStandbyState()
                    resetHuyaRecoveryState()
                }
                scheduleStreamRefresh(channel, playbackInfo)
                val mediaSource = PlayerHelper.createMediaSource(
                    this@PlayerActivity,
                    playbackInfo,
                    "${channel.id}:$mediaSourceGeneration",
                    preconnectedDataSource = preconnectedHuyaDataSource,
                    transferListener = huyaResponseTransferListener(channel, playbackInfo),
                    onHttpEofReconnect = huyaFlvEofReconnectListener(
                        channel,
                        "${channel.id}:$mediaSourceGeneration",
                        playbackInfo
                    ),
                    renewRejectedHuyaUrl = legacyHuyaFlvRenewalProvider(
                        channel,
                        "${channel.id}:$mediaSourceGeneration",
                        playbackInfo
                    )
                )
                // Ownership transfers to the MediaSource once it is created.
                preconnectedHuyaDataSource = null
                playerView.defaultArtwork = null
                if ((seamlessRetry && !shouldRecreatePlayer) || keepLegacyHuyaSurface) {
                    // setMediaSource replaces the current item in place. On
                    // the MStar path, avoid explicit Surface unbind, stop and
                    // clearMediaItems, all of which force vendor codec flushes.
                    player?.setMediaSource(mediaSource, true)
                } else {
                    if (shouldRecreatePlayer) {
                        // recreatePlayer() already released the old codec and
                        // attached a fresh player to this Surface. Detaching
                        // it a second time here can make MStar briefly expose
                        // stale frame geometry (notably after resolution
                        // changes), then poison the next channel's frame size.
                        DebugReporter.recordLivePlaybackEvent(
                            "surface_rebind_skipped_after_player_recreate",
                            channel,
                            mapOf(
                                "recoveryAction" to if (rotatedLegacySurface) {
                                    "fresh_player_and_rotated_surface_view"
                                } else {
                                    "reuse_player_view_surface"
                                },
                                "rotatedLegacySurface" to rotatedLegacySurface
                            )
                        )
                    } else {
                        resetActiveVideoSurfaceForSourceSwitch()
                    }
                    player?.stop()
                    player?.clearMediaItems()
                    player?.setMediaSource(mediaSource)
                }
                activeAudioTrackCount = 0
                activeAudioPlaybackAdvanced = false
                activeAudioPcmObserved = false
                legacyHuyaPcmEvidence.reset()
                player?.prepare()
                player?.playWhenReady = true
                activeMediaSourceStartedAtMs = System.currentTimeMillis()
                markHuyaRenewalSourceApplied(usedPreconnectedDataSource = false)
                scheduleLegacyHuyaSourceAgeGuard(channel, playbackInfo)
            } catch (cancelled: CancellationException) {
                preconnectedHuyaDataSource?.let(::closePreconnectedHuyaDataSourceAsync)
                throw cancelled
            } catch (error: LinkageError) {
                preconnectedHuyaDataSource?.let(::closePreconnectedHuyaDataSourceAsync)
                // Old vendor firmware can miss methods referenced by a stream
                // resolver.  Treat this as a recoverable preparation failure
                // rather than letting a NoSuchMethodError kill the app process.
                Log.e(TAG, "Android compatibility linkage failure for channel=${channel.name}", error)
                refreshInProgress = false
                hideBuffering()
                handlePlaybackPreparationFailure(error)
            } catch (e: Exception) {
                preconnectedHuyaDataSource?.let(::closePreconnectedHuyaDataSourceAsync)
                Log.e(TAG, "Failed to prepare playback for channel=${channel.name}", e)
                refreshInProgress = false
                hideBuffering()
                handlePlaybackPreparationFailure(e)
            }
        }
    }

    private fun updateSourceUi(
        mergedChannel: MergedChannel?,
        currentSource: com.tvplayer.app.data.repository.PlaybackSourceOption?
    ) {
        val sourceCount = mergedChannel?.sources?.size ?: 0
        binding.sourceSwitchContainer.visibility = if (sourceCount > 1) View.VISIBLE else View.GONE
        if (!actionMenuShowingMore) {
            binding.quickSourceButton.isEnabled = sourceCount > 1
            binding.quickSourceButton.alpha = if (sourceCount > 1) 1f else 0.45f
        }
        binding.sourceCountText.text = if (sourceCount > 0) {
            val currentIndex = mergedChannel?.sources?.indexOfFirst { it.channel.id == currentSource?.channel?.id }
                ?.takeIf { it >= 0 } ?: 0
            "${currentIndex + 1}/$sourceCount"
        } else {
            ""
        }
        binding.channelSourceInfo.text = currentSource?.let { source ->
            buildString {
                append("来源：")
                append(source.playlistName)
                if (source.responseTimeMs > 0) {
                    append(" · ${source.responseTimeMs}ms")
                }
            }
        } ?: "来源：未知"
        updateMobileInfo()
    }

    /**
     * Keep the rendered cursor synchronized with the current catalog snapshot,
     * even when a refresh replaces the item at the same numeric index and the
     * selected-index StateFlow therefore emits no new value.
     */
    private fun refreshChannelListSelection(channels: List<MergedChannel> = viewModel.visibleChannels.value) {
        if (!::channelListAdapter.isInitialized) return
        val selectedIndex = viewModel.selectedChannelIndex.value
        channelListAdapter.updateSelection(
            selectedKey = channels.getOrNull(selectedIndex)?.key.orEmpty(),
            currentKey = currentMergedChannel?.key.orEmpty()
        )
    }

    private fun updateEpgInfo(channel: Channel) {
        if (isLowEndTvDevice) {
            epgJob?.cancel()
            binding.epgNowText.text = "正在播：低配电视保护模式已关闭节目单"
            binding.epgNextText.text = "下一档：优先保证播放稳定性"
            return
        }
        binding.epgNowText.text = "正在播：节目单加载中"
        binding.epgNextText.text = "下一档：暂无信息"
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            val epgInfo = epgRepository.getCurrentProgramInfo(channel)
            binding.epgNowText.text = epgInfo.current?.let {
                "正在播：${it.title} ${formatTime(it.startMillis)}-${formatTime(it.endMillis)}"
            } ?: "正在播：暂无节目单"
            binding.epgNextText.text = epgInfo.next?.let {
                "下一档：${it.title} ${formatTime(it.startMillis)}-${formatTime(it.endMillis)}"
            } ?: "下一档：暂无节目单"
        }
    }

    private fun formatTime(epochMillis: Long): String {
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
    }

    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
            cancelHideUi()
            scheduleUiHide()
        }
    }

    private fun toggleSubtitles() {
        viewModel.enableSubtitles = !viewModel.enableSubtitles
        showToast(if (viewModel.enableSubtitles) "字幕已开启" else "字幕已关闭")
    }

    private fun showQualityOptions() {
        val qualities = arrayOf("自动", "1080p", "720p", "480p", "360p")
        AlertDialog.Builder(this)
            .setTitle("选择清晰度")
            .setItems(qualities) { _, which ->
                viewModel.videoQuality = qualities[which].lowercase()
                currentChannel?.let { playChannel(it) }
            }
            .show()
    }

    private fun showBuffering() {
        binding.playerRoot.removeCallbacks(delayedBufferingIndicatorRunnable)
        binding.progressBar.visibility = View.GONE
        binding.bufferingText.visibility = View.GONE
        binding.playerRoot.postDelayed(delayedBufferingIndicatorRunnable, BUFFERING_INDICATOR_DELAY_MS)
    }

    private fun hideBuffering() {
        binding.playerRoot.removeCallbacks(delayedBufferingIndicatorRunnable)
        binding.progressBar.visibility = View.GONE
        binding.bufferingText.visibility = View.GONE
        viewModel.setBuffering(false)
        cancelBufferingRecovery()
    }

    private fun onPlaybackEnded() {
        val channel = currentChannel
        val activePlayer = player
        val playbackInfo = currentPlaybackInfo
        val endedPositionMs = activePlayer?.currentPosition
        Log.w(TAG, "Playback ended for channel=${channel?.name}, position=$endedPositionMs")
        if (channel != null && activePlayer != null && playbackInfo?.resolvedBy?.startsWith("huya:") == true) {
            DebugReporter.recordLivePlaybackEvent(
                "huya_playback_ended_snapshot",
                channel,
                mapOf(
                    "resolvedBy" to playbackInfo.resolvedBy,
                    "mimeType" to playbackInfo.mimeType,
                    "playbackPositionMs" to endedPositionMs,
                    "durationMs" to activePlayer.duration,
                    "sourceAgeMs" to (System.currentTimeMillis() - activeMediaSourceStartedAtMs)
                        .coerceAtLeast(0L),
                    "isPlaying" to activePlayer.isPlaying,
                    "playWhenReady" to activePlayer.playWhenReady,
                    "audioTrackCount" to activeAudioTrackCount,
                    "audioPcmObserved" to activeAudioPcmObserved,
                    "videoTrackCount" to activeVideoTrackCount,
                    "renderedOutputBufferCount" to
                        activePlayer.videoDecoderCounters?.renderedOutputBufferCount
                )
            )
        }
        deferredPlaybackEndedChannelId = -1L
        if (channel != null) {
            if (refreshInProgress) {
                Log.i(TAG, "Playback ended while same-channel renewal is already in progress")
                if (isLegacyMstarHuyaStream()) scheduleEndedRecoveryWatchdog(channel)
                return
            }
            val huyaPlaybackInfo = playbackInfo?.takeIf { it.resolvedBy.startsWith("huya:") }
            if (huyaPlaybackInfo != null && isLegacyMstarHuyaStream() &&
                attemptLegacyHuyaSameUrlReconnectAtEof(channel, huyaPlaybackInfo)
            ) {
                return
            }
            if (swapToStandbyHuyaStream(channel, "直播流结束")) {
                return
            }
            if (huyaPlaybackInfo != null &&
                attemptLegacyHuyaSameUrlReconnectAtEof(channel, huyaPlaybackInfo)
            ) {
                return
            }
            if (refreshHuyaSingleStream(
                    channel,
                    "虎牙直播流结束，正在续签",
                    showHint = !isLegacyMstarHuyaStream()
                )
            ) {
                return
            }
            if (refreshDouyuSingleStream(channel, "斗鱼直播流结束，正在续接同频道")) {
                return
            }
            if (isLegacyDirectHlsStream() &&
                recoverHlsAtLiveEdge("HLS 直播流结束，正在跳到实时位置", requireLiveWindow = true)
            ) {
                return
            }
            if (isLegacyDirectHlsStream() &&
                scheduleLegacyHlsRecovery(channel, "HLS 直播流结束，正在重新加载当前来源")
            ) {
                return
            }
            if (
                shouldAttemptGenericSameSourceRetry() &&
                attemptSameSourceRetry(
                    reason = "直播流结束",
                    forcePlayerRecreate = false,
                    delayMs = 120L,
                    seamlessRetry = true
                )
            ) {
                return
            }
            if (!preferences.autoFailoverEnabled) {
                schedulePersistentSourceRetry("直播流结束")
                return
            }
            if (shouldDeferAutomaticRecovery()) {
                deferredPlaybackEndedChannelId = channel.id
                DebugReporter.recordLivePlaybackEvent(
                    "channel_failover_deferred_while_browsing",
                    channel,
                    mapOf(
                        "phase" to "playback_ended",
                        "playbackPositionMs" to player?.currentPosition,
                        "sameChannelRecoveryAttempted" to true
                    )
                )
                resumeDeferredRecoveryChecks()
                return
            }
            recordFailedPlaybackSource()
            if (viewModel.switchToFallbackSource(channel, failedPlaybackSourceIds)) {
                showStatusHint("直播流已结束，正在切换备用来源", STATUS_HINT_DEFAULT_MS)
                revealChannelInfoOverlay()
                return
            }
            advanceToNextChannelAfterFailure("直播流已结束", null)
            return
        }
        showStatusHint("播放结束", STATUS_HINT_DEFAULT_MS)
    }

    /**
     * Mirror APTV/FFmpeg's EOF reconnect boundary on the legacy MStar path:
     * do not open a second copy of the same signed FLV while the current one
     * is still playing. Once ExoPlayer confirms STATE_ENDED, reopen that URL
     * once, validate audio plus a video keyframe, then feed the preconnected
     * response into the existing player. A rejected URL falls through to the
     * regular same-channel route resolver without changing channels.
     */
    private fun attemptLegacyHuyaSameUrlReconnectAtEof(
        channel: Channel,
        playbackInfo: PlaybackStreamInfo
    ): Boolean {
        val activePlayer = player ?: return false
        val sourceUrl = playbackInfo.url
        if (huyaEofPrewarmWaitJob?.isActive == true || huyaEofReconnectJob?.isActive == true) {
            return true
        }
        val blockedForCurrentUrl = huyaSameUrlReconnectBlockedUrl == sourceUrl
        if (!shouldReconnectLegacyHuyaAtEof(
                isLegacyTv = isLegacyMstarHuyaStream(),
                isFlv = playbackInfo.mimeType == "video/x-flv",
                playbackEnded = activePlayer.playbackState == Player.STATE_ENDED,
                blockedForCurrentUrl = blockedForCurrentUrl,
                reconnectInProgress = huyaEofReconnectJob?.isActive == true || refreshInProgress
            )
        ) return false

        // Follow APTV's open-phase retry shape: after confirmed EOF, first
        // reopen this exact signed URL and validate it. Do not cut over to a
        // parallel prewarm/queued route until that same-URL attempt is rejected.
        val pendingShortPrewarm = huyaShortStreamPrewarmJob?.takeIf { it.isActive }
        if (pendingShortPrewarm != null || queuedHuyaMediaId != null) {
            val hadQueuedCandidate = queuedHuyaMediaId != null
            huyaEofPrewarmWaitJob = lifecycleScope.launch {
                try {
                    pendingShortPrewarm?.cancelAndJoin()
                    if (huyaShortStreamPrewarmJob === pendingShortPrewarm) {
                        huyaShortStreamPrewarmJob = null
                    }
                    val stillSameEndedSource = currentChannel?.id == channel.id &&
                        currentPlaybackInfo?.url == sourceUrl &&
                        player === activePlayer &&
                        activePlayer.playbackState == Player.STATE_ENDED &&
                        !isFinishing && !isDestroyed
                    if (!stillSameEndedSource) return@launch
                    discardQueuedHuyaRenewal()
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_eof_same_url_priority",
                        channel,
                        mapOf(
                            "discardedQueuedCandidate" to hadQueuedCandidate,
                            "cancelledShortPrewarm" to (pendingShortPrewarm != null),
                            "reason" to "confirmed_eof_same_signed_url_first"
                        )
                    )
                    huyaEofPrewarmWaitJob = null
                    attemptLegacyHuyaSameUrlReconnectAtEof(channel, playbackInfo)
                } finally {
                    huyaEofPrewarmWaitJob = null
                }
            }
            return true
        }

        refreshInProgress = true
        DebugReporter.recordLivePlaybackEvent(
            "huya_eof_same_url_reconnect_started",
            channel,
            mapOf(
                "resolvedBy" to playbackInfo.resolvedBy,
                "durationMs" to activePlayer.duration,
                "playbackPositionMs" to activePlayer.currentPosition,
                "reconnectBoundary" to "confirmed_state_ended",
                "parallelSameUrlRequest" to false
            )
        )
        huyaEofReconnectJob = lifecycleScope.launch {
            var connection: PreconnectedHttpDataSource? = null
            try {
                connection = withTimeoutOrNull(LEGACY_HUYA_EOF_RECONNECT_TIMEOUT_MS) {
                    PlayerHelper.preconnectHttpStream(
                        playbackInfo,
                        connectTimeoutMs = LEGACY_HUYA_EOF_RECONNECT_CONNECT_TIMEOUT_MS,
                        readTimeoutMs = LEGACY_HUYA_EOF_RECONNECT_READ_TIMEOUT_MS,
                        totalTimeoutMs = LEGACY_HUYA_EOF_RECONNECT_TIMEOUT_MS
                    )
                }
                val candidateConnection = connection
                val validated = candidateConnection?.let {
                    shouldQueueLegacyHuyaRenewal(
                        isLegacyTv = true,
                        isFlv = true,
                        audioFlag = it.hasAudioTrackInFlvHeader,
                        hasAudioPayload = it.containsAudioPayload,
                        hasVideoKeyframe = it.containsVideoKeyframe
                    )
                } ?: false
                val stillSameEndedSource = currentChannel?.id == channel.id &&
                    currentPlaybackInfo?.url == sourceUrl &&
                    player === activePlayer &&
                    activePlayer.playbackState == Player.STATE_ENDED &&
                    !isFinishing && !isDestroyed
                val rejectionReason = when {
                    candidateConnection == null -> "unavailable_or_timeout"
                    candidateConnection.hasAudioTrackInFlvHeader == false -> "flv_header_without_audio"
                    !candidateConnection.containsAudioPayload -> "no_audio_payload_in_prebuffer"
                    !candidateConnection.containsVideoKeyframe -> "no_video_keyframe_in_prebuffer"
                    !stillSameEndedSource -> "active_source_changed"
                    else -> "queue_validation_rejected"
                }

                if (validated && stillSameEndedSource) {
                    huyaSameUrlReconnectBlockedUrl = ""
                    val acceptedConnection = candidateConnection ?: return@launch
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_eof_same_url_reconnect_ready",
                        channel,
                        mapOf(
                            "resolvedBy" to playbackInfo.resolvedBy,
                            "prebufferBytes" to acceptedConnection.bufferedBytes,
                            "audioPayloadFound" to acceptedConnection.containsAudioPayload,
                            "videoKeyframeFound" to acceptedConnection.containsVideoKeyframe
                        )
                    )
                    connection = null // ownership transfers to ExoPlayer
                    playResolvedStream(
                        channel = channel,
                        playbackInfo = playbackInfo,
                        seamlessRetry = true,
                        forcePlayerRecreate = false,
                        preconnectedDataSource = acceptedConnection
                    )
                    return@launch
                }

                connection?.let(::closePreconnectedHuyaDataSourceAsync)
                connection = null
                if (!stillSameEndedSource) {
                    refreshInProgress = false
                    return@launch
                }

                huyaSameUrlReconnectBlockedUrl = sourceUrl
                DebugReporter.recordLivePlaybackEvent(
                    "huya_eof_same_url_reconnect_rejected",
                    channel,
                    mapOf("reason" to rejectionReason)
                )
                refreshInProgress = false
                if (!refreshHuyaSingleStream(
                        channel,
                        "虎牙直播流自动续接",
                        showHint = false
                    )
                ) {
                    schedulePersistentSourceRetry("虎牙直播流自动续接")
                }
            } catch (cancelled: CancellationException) {
                connection?.let(::closePreconnectedHuyaDataSourceAsync)
                if (currentChannel?.id == channel.id && currentPlaybackInfo?.url == sourceUrl) {
                    refreshInProgress = false
                }
                throw cancelled
            } catch (error: Exception) {
                connection?.let(::closePreconnectedHuyaDataSourceAsync)
                Log.w(TAG, "Legacy Huya same-URL EOF reconnect failed", error)
                if (currentChannel?.id == channel.id && currentPlaybackInfo?.url == sourceUrl &&
                    player === activePlayer && activePlayer.playbackState == Player.STATE_ENDED
                ) {
                    huyaSameUrlReconnectBlockedUrl = sourceUrl
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_eof_same_url_reconnect_failed",
                        channel,
                        mapOf("errorType" to error.javaClass.simpleName)
                    )
                    refreshInProgress = false
                    if (!refreshHuyaSingleStream(
                            channel,
                            "虎牙直播流自动续接",
                            showHint = false
                        )
                    ) {
                        schedulePersistentSourceRetry("虎牙直播流自动续接")
                    }
                }
            } finally {
                huyaEofReconnectJob = null
            }
        }
        return true
    }

    /**
     * A rejected signed FLV can leave ExoPlayer in STATE_ENDED while a renewal
     * coroutine remains marked active. Recheck that state and restart the same
     * channel if the in-flight recovery never installs a replacement source.
     */
    private fun scheduleEndedRecoveryWatchdog(channel: Channel) {
        if (endedRecoveryWatchdogJob?.isActive == true) return
        val endedMediaId = player?.currentMediaItem?.mediaId
        endedRecoveryWatchdogJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(ENDED_RECOVERY_WATCHDOG_DELAY_MS)
            if (currentChannel?.id != channel.id || player?.playbackState != Player.STATE_ENDED) {
                endedRecoveryWatchdogJob = null
                return@launch
            }
            val currentMediaId = player?.currentMediaItem?.mediaId
            if (!endedMediaId.isNullOrBlank() && currentMediaId != endedMediaId) {
                endedRecoveryWatchdogJob = null
                return@launch
            }
            DebugReporter.recordLivePlaybackEvent(
                "ended_recovery_watchdog_fired",
                channel,
                mapOf(
                    "refreshInProgress" to refreshInProgress,
                    "playbackJobActive" to (playbackJob?.isActive == true),
                    "huyaRecoveryJobActive" to (huyaRecoveryJob?.isActive == true),
                    "mediaId" to currentMediaId
                )
            )
            playbackJob?.cancel()
            huyaRecoveryJob?.cancel()
            huyaMissingAudioRetryJob?.cancel()
            huyaMissingAudioRetryJob = null
            refreshInProgress = false
            endedRecoveryWatchdogJob = null
            onPlaybackEnded()
        }
    }

    private fun playResolvedStream(
        channel: Channel,
        playbackInfo: PlaybackStreamInfo,
        seamlessRetry: Boolean,
        forcePlayerRecreate: Boolean,
        preconnectedDataSource: PreconnectedHttpDataSource? = null
    ) {
        discardQueuedHuyaRenewal()
        playbackJob?.cancel()
        playbackJob = lifecycleScope.launch {
            try {
                if (currentChannel?.id != channel.id) return@launch
                val recreateForThisAttempt = forcePlayerRecreate || (isLowEndTvDevice && !seamlessRetry)
                if (recreateForThisAttempt) {
                    recreatePlayer()
                }
                refreshInProgress = false
                activeAudioTrackCount = 0
                activeAudioPlaybackAdvanced = false
                activeAudioPcmObserved = false
                legacyHuyaPcmEvidence.reset()
                activeVideoTrackCount = 0
                activeVideoSizeSeenForSource = false
                beginVideoFrameTrackingForNewSource()
                resetHuyaShortStreamTracking()
                currentPlaybackInfo = playbackInfo
                if (huyaSameUrlReconnectBlockedUrl.isNotBlank() &&
                    huyaSameUrlReconnectBlockedUrl != playbackInfo.url
                ) {
                    huyaSameUrlReconnectBlockedUrl = ""
                }
                armLegacyHuyaFrameWatchdog(playbackInfo)
                scheduleStreamRefresh(channel, playbackInfo)
                val mediaSource = PlayerHelper.createMediaSource(
                    this@PlayerActivity,
                    playbackInfo,
                    "${channel.id}:$mediaSourceGeneration",
                    preconnectedDataSource,
                    huyaResponseTransferListener(channel, playbackInfo),
                    huyaFlvEofReconnectListener(
                        channel,
                        "${channel.id}:$mediaSourceGeneration",
                        playbackInfo
                    ),
                    renewRejectedHuyaUrl = legacyHuyaFlvRenewalProvider(
                        channel,
                        "${channel.id}:$mediaSourceGeneration",
                        playbackInfo
                    )
                )
                playerView.defaultArtwork = null
                if (seamlessRetry && !recreateForThisAttempt) {
                    player?.setMediaSource(mediaSource, true)
                } else {
                    if (recreateForThisAttempt) {
                        DebugReporter.recordLivePlaybackEvent(
                            "surface_rebind_skipped_after_player_recreate",
                            channel,
                            mapOf("recoveryAction" to "reuse_fresh_player_surface")
                        )
                    } else {
                        resetActiveVideoSurfaceForSourceSwitch()
                    }
                    player?.stop()
                    player?.clearMediaItems()
                    player?.setMediaSource(mediaSource)
                }
                activeAudioTrackCount = 0
                activeAudioPlaybackAdvanced = false
                activeAudioPcmObserved = false
                legacyHuyaPcmEvidence.reset()
                player?.prepare()
                player?.playWhenReady = true
                activeMediaSourceStartedAtMs = System.currentTimeMillis()
                markHuyaRenewalSourceApplied(usedPreconnectedDataSource = preconnectedDataSource != null)
                scheduleLegacyHuyaSourceAgeGuard(channel, playbackInfo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LinkageError) {
                Log.e(TAG, "Android compatibility linkage failure for channel=${channel.name}", error)
                handlePlaybackPreparationFailure(error)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply resolved playback for channel=${channel.name}", e)
                handlePlaybackPreparationFailure(e)
            }
        }
    }

    private fun handlePlayerError(error: com.google.android.exoplayer2.PlaybackException) {
        Log.e(TAG, "Playback failed for channel=${currentChannel?.name}", error)
        noteInvalidHuyaRedirectHost(error)
        DebugReporter.reportPlaybackError(
            phase = "player_error",
            channel = currentChannel,
            message = extractPlaybackFailureMessage(error),
            error = error,
            playbackInfo = currentPlaybackInfo,
            decoderMode = currentDecoderMode
        )
        clearDeferredPlaybackError("recovery resumed")
        if (shouldIgnoreHuyaPlaybackErrorDuringRecovery(
                isHuyaStream = currentPlaybackInfo?.resolvedBy?.startsWith("huya:") == true,
                recoveryInProgress = refreshInProgress
            )
        ) {
            DebugReporter.recordLivePlaybackEvent(
                "playback_error_deduplicated_during_huya_recovery",
                currentChannel,
                mapOf(
                    "errorCode" to error.errorCode,
                    "errorName" to error.errorCodeName,
                    "recoveryOwner" to "active_huya_renewal"
                )
            )
            Log.i(TAG, "Ignoring duplicate player error while same-channel Huya recovery is active")
            return
        }
        if (scheduleHuyaHlsFlvFallback(currentChannel, error)) return
        val liveWindowCooldownExpired = currentChannel?.id != behindLiveWindowRecoveryChannelId ||
            lastBehindLiveWindowRecoveryAtMs == 0L ||
            System.currentTimeMillis() - lastBehindLiveWindowRecoveryAtMs >= LIVE_WINDOW_RECOVERY_COOLDOWN_MS
        if (shouldRecoverBehindLiveWindow(
                errorCode = error.errorCode,
                behindLiveWindowErrorCode = com.google.android.exoplayer2.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                isHlsSource = isHlsPlaybackInfo(currentPlaybackInfo),
                cooldownExpired = liveWindowCooldownExpired
            ) &&
            recoverHlsAtLiveEdge("HLS 落后于直播窗口，正在跳到实时位置", requireLiveWindow = false)
        ) {
            return
        }
        if (isLegacyDirectHlsStream() &&
            scheduleLegacyHlsRecovery(currentChannel, "HLS 播放列表卡住，正在重新加载当前来源")
        ) {
            return
        }
        val playbackInfo = currentPlaybackInfo
        val unsupportedVideoFormatId = unsupportedFlvVideoFormatId(error)
            .takeIf {
                playbackInfo?.let {
                    it.resolvedBy.startsWith("huya:") && it.mimeType == "video/x-flv"
                } == true
            }
        if (unsupportedVideoFormatId != null) {
            val useHlsFallback = isLegacyMstarHuyaStream()
            val message = if (useHlsFallback) {
                "当前虎牙 FLV 视频编码 $unsupportedVideoFormatId 不兼容，正在尝试同频道 HLS"
            } else {
                "当前虎牙 FLV 视频编码 $unsupportedVideoFormatId 不兼容，正在尝试同频道其他线路"
            }
            if (useHlsFallback) {
                huyaUnsupportedFlvVideoFormatId = unsupportedVideoFormatId
                huyaForceHlsForRecovery = true
                huyaForceFlvForRecovery = false
                Log.w(TAG, "$message; avoid rotating more FLV routes for channel=${currentChannel?.name}")
            }
            DebugReporter.recordLivePlaybackEvent(
                "huya_unsupported_flv_video_format",
                currentChannel,
                mapOf(
                    "videoFormatId" to unsupportedVideoFormatId,
                    "resolvedBy" to playbackInfo?.resolvedBy,
                    "errorCode" to error.errorCode,
                    "action" to if (useHlsFallback) "same_channel_hls_fallback" else "same_channel_route_rotation"
                )
            )
            if (!useHlsFallback && swapToStandbyHuyaStream(currentChannel, message)) return
            if (refreshHuyaSingleStream(
                    currentChannel,
                    message,
                    recoveryDelayMs = 0L,
                    confirmedSourceFailure = true
                )
            ) {
                return
            }
            if (!preferences.autoFailoverEnabled) {
                schedulePersistentSourceRetry("虎牙 FLV 视频编码不兼容，保持当前频道重试")
                return
            }
        } else {
            if (swapToStandbyHuyaStream(currentChannel, "播放错误")) {
                return
            }
            if (refreshHuyaSingleStream(
                    currentChannel,
                    "虎牙授权失效，正在续签",
                    recoveryDelayMs = huyaRetryDelayForError(error)
                )
            ) {
                return
            }
        }
        if (isDouyuProtectionFailure(error) &&
            scheduleDouyuProtectionRetry(currentChannel, "斗鱼接口暂时拒绝，保留当前频道重试")
        ) {
            return
        }
        if (refreshDouyuSingleStream(currentChannel, "斗鱼流地址失效，正在重新签名续接")) {
            return
        }
        if (shouldAttemptGenericSameSourceRetry() && attemptSameSourceRetry("播放中断", forcePlayerRecreate = true)) {
            return
        }
        if (!preferences.autoFailoverEnabled) {
            schedulePersistentSourceRetry("播放中断")
            return
        }
        if (shouldDeferAutomaticRecovery()) {
            deferredPlaybackError = error
            deferredPlaybackErrorChannelId = currentChannel?.id ?: -1L
            deferredPlaybackEndedChannelId = -1L
            DebugReporter.recordLivePlaybackEvent(
                "channel_failover_deferred_while_browsing",
                currentChannel,
                mapOf(
                    "phase" to "player_error",
                    "errorCode" to error.errorCode,
                    "errorName" to error.errorCodeName,
                    "sameChannelRecoveryAttempted" to true
                )
            )
            resumeDeferredRecoveryChecks()
            return
        }
        if (preferences.autoFailoverEnabled) {
            recordFailedPlaybackSource()
            markCurrentChannelFailure(extractPlaybackFailureMessage(error))
        }
        if (viewModel.switchToFallbackSource(currentChannel, failedPlaybackSourceIds)) {
            showStatusHint(buildFallbackHint("当前来源播放失败", error), STATUS_HINT_LONG_MS)
            revealChannelInfoOverlay()
            return
        }
        advanceToNextChannelAfterFailure("当前频道播放失败", error)
    }

    private fun recoverHlsAtLiveEdge(reason: String, requireLiveWindow: Boolean): Boolean {
        val activePlayer = player ?: return false
        val channel = currentChannel ?: return false
        if (!isHlsPlaybackInfo(currentPlaybackInfo)) return false
        if (requireLiveWindow) {
            val timeline = activePlayer.currentTimeline
            if (timeline.isEmpty) return false
            val window = timeline.getWindow(activePlayer.currentMediaItemIndex, Timeline.Window())
            if (!window.isLive) return false
        }
        val now = System.currentTimeMillis()
        if (behindLiveWindowRecoveryChannelId == channel.id &&
            lastBehindLiveWindowRecoveryAtMs > 0L &&
            now - lastBehindLiveWindowRecoveryAtMs < LIVE_WINDOW_RECOVERY_COOLDOWN_MS
        ) {
            return false
        }
        behindLiveWindowRecoveryChannelId = channel.id
        lastBehindLiveWindowRecoveryAtMs = now
        Log.w(TAG, "$reason; channel=${channel.name}, position=${activePlayer.currentPosition}")
        showStatusHint(reason, STATUS_HINT_LONG_MS)
        DebugReporter.recordLivePlaybackEvent(
            "live_window_recovery",
            channel,
            mapOf(
                "recoveryAction" to "seek_to_default_position_and_prepare",
                "playbackPositionMs" to activePlayer.currentPosition,
                "mediaSource" to currentPlaybackInfo?.resolvedBy
            )
        )
        activePlayer.seekToDefaultPosition()
        activePlayer.prepare()
        activePlayer.playWhenReady = true
        return true
    }

    private fun handlePlaybackPreparationFailure(error: Throwable) {
        noteInvalidHuyaRedirectHost(error)
        DebugReporter.reportPlaybackError(
            phase = "prepare_error",
            channel = currentChannel,
            message = extractPlaybackFailureMessage(error),
            error = error,
            playbackInfo = currentPlaybackInfo,
            decoderMode = currentDecoderMode
        )
        if (scheduleHuyaHlsFlvFallback(currentChannel, error)) return
        if (isLegacyDirectHlsStream() &&
            scheduleLegacyHlsRecovery(currentChannel, "HLS 来源准备卡住，正在重新加载当前来源")
        ) {
            return
        }
        if (swapToStandbyHuyaStream(currentChannel, "解析失败")) {
            return
        }
        if (refreshHuyaSingleStream(
                currentChannel,
                "虎牙地址失效，正在重新获取",
                recoveryDelayMs = huyaRetryDelayForError(error)
            )
        ) {
            return
        }
        if (isDouyuProtectionFailure(error) &&
            scheduleDouyuProtectionRetry(currentChannel, "斗鱼接口暂时拒绝，保留当前频道重试")
        ) {
            return
        }
        if (refreshDouyuSingleStream(currentChannel, "斗鱼地址失效，正在重新签名续接")) {
            return
        }
        if (shouldAttemptGenericSameSourceRetry() && attemptSameSourceRetry("解析失败", forcePlayerRecreate = true)) {
            return
        }
        if (!preferences.autoFailoverEnabled) {
            schedulePersistentSourceRetry("解析失败")
            return
        }
        if (shouldDeferAutomaticRecovery()) {
            deferredPlaybackPreparationError = error
            deferredPlaybackPreparationChannelId = currentChannel?.id ?: -1L
            deferredPlaybackEndedChannelId = -1L
            DebugReporter.recordLivePlaybackEvent(
                "channel_failover_deferred_while_browsing",
                currentChannel,
                mapOf(
                    "phase" to "prepare_error",
                    "sameChannelRecoveryAttempted" to true,
                    "error" to extractPlaybackFailureMessage(error)
                )
            )
            resumeDeferredRecoveryChecks()
            return
        }
        if (preferences.autoFailoverEnabled) {
            recordFailedPlaybackSource()
            markCurrentChannelFailure(extractPlaybackFailureMessage(error))
        }
        if (viewModel.switchToFallbackSource(currentChannel, failedPlaybackSourceIds)) {
            showStatusHint(buildFallbackHint("当前来源准备失败", error), STATUS_HINT_LONG_MS)
            revealChannelInfoOverlay()
            return
        }
        val message = buildDetailedPlaybackErrorMessage(error, isPreparation = true)
        Log.e(TAG, "Playback preparation failed for channel=${currentChannel?.name}: $message", error)
        advanceToNextChannelAfterFailure("当前频道准备失败", error)
    }

    private fun markCurrentChannelFailure(errorText: String?) {
        val channel = currentChannel ?: return
        val message = errorText?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            database.channelDao().updateProbeResult(
                channelId = channel.id,
                isAvailable = false,
                error = message,
                responseTimeMs = 0L,
                videoWidth = 0,
                videoHeight = 0,
                checkedAt = System.currentTimeMillis()
            )
        }
    }

    private fun extractPlaybackFailureMessage(error: Throwable): String {
        return when (val cause = findRootCause(error)) {
            is HttpDataSource.InvalidResponseCodeException -> "HTTP ${cause.responseCode}"
            is SocketTimeoutException -> "超时不可访问"
            is UnknownHostException -> "域名解析失败"
            else -> cause.message ?: error.message ?: "播放失败"
        }
    }

    private fun isDouyuProtectionFailure(error: Throwable?): Boolean {
        if (error == null || !isDouyuStream(currentChannel)) return false
        var current: Throwable? = error
        while (current != null) {
            if (current is DouyuStreamResolver.ProtectionException) return true
            val message = current.message.orEmpty()
            if (
                message.contains("error=-9", ignoreCase = true) ||
                message.contains("时间戳错误", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Keep the current Douyu room when the resolver itself is being rejected.
     * A protection response is different from a playable URL returning a
     * media error: switching rooms here would create a false failover.
     */
    private fun scheduleDouyuProtectionRetry(channel: Channel?, reason: String): Boolean {
        val activeChannel = channel ?: return false
        if (!isDouyuStream(activeChannel)) return false
        if (douyuProtectionRetryJob?.isActive == true) return true

        douyuProtectionRetryCount += 1
        val exponent = (douyuProtectionRetryCount - 1).coerceAtMost(4)
        val delayMs = (DOUYU_PROTECTION_RETRY_INITIAL_DELAY_MS shl exponent)
            .coerceAtMost(DOUYU_PROTECTION_RETRY_MAX_DELAY_MS)
        refreshInProgress = false
        showStatusHint(
            "$reason（${delayMs / 1000}秒后重试）",
            STATUS_HINT_LONG_MS
        )
        DebugReporter.reportPlaybackIssue(
            phase = "douyu_protection_retry",
            channel = activeChannel,
            message = "$reason；不切换频道，等待网关恢复，attempt=$douyuProtectionRetryCount",
            playbackInfo = currentPlaybackInfo,
            decoderMode = currentDecoderMode
        )
        Log.w(
            TAG,
            "$reason, keep same channel=${activeChannel.name}; " +
                "retry in ${delayMs}ms, attempt=$douyuProtectionRetryCount"
        )
        douyuProtectionRetryJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (currentChannel?.id == activeChannel.id && !isFinishing && !isDestroyed) {
                douyuProtectionRetryJob = null
                refreshInProgress = true
                playChannel(
                    channel = activeChannel,
                    isRetry = true,
                    forcePlayerRecreate = false,
                    isProactiveRefresh = true,
                    seamlessRetry = true
                )
            } else {
                douyuProtectionRetryJob = null
                refreshInProgress = false
            }
        }
        return true
    }

    private fun findRootCause(error: Throwable): Throwable {
        var current: Throwable = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun scheduleUiHide() {
        hideUiRunnable?.let { binding.playerRoot.removeCallbacks(it) }
        hideUiRunnable = Runnable { hideUi() }
        binding.playerRoot.postDelayed(hideUiRunnable!!, UI_HIDE_DELAY)
    }

    private fun cancelHideUi() {
        hideUiRunnable?.let {
            binding.playerRoot.removeCallbacks(it)
            hideUiRunnable = null
        }
    }

    private fun scheduleBufferingRecovery() {
        binding.playerRoot.removeCallbacks(bufferingRecoveryRunnable)
        binding.playerRoot.postDelayed(bufferingRecoveryRunnable, currentBufferingRecoveryTimeoutMs())
    }

    private fun cancelBufferingRecovery() {
        binding.playerRoot.removeCallbacks(bufferingRecoveryRunnable)
    }

    private fun handleBufferingStall() {
        if (player?.playbackState == Player.STATE_BUFFERING) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val reconnectStartedAtMs = legacyHuyaTransportReconnectStartedAtElapsedMs
            if (shouldDeferLegacyHuyaBufferingRecovery(
                    isLegacyHuyaFlv = isLegacyMstarHuyaStream(),
                    reconnectStartedAtMs = reconnectStartedAtMs,
                    nowMs = nowElapsedMs,
                    maximumGraceMs = LEGACY_HUYA_TRANSPORT_RECOVERY_GRACE_MS
                )
            ) {
                val remainingGraceMs = remainingLegacyHuyaReconnectGraceMs(
                    reconnectStartedAtMs,
                    nowElapsedMs,
                    LEGACY_HUYA_TRANSPORT_RECOVERY_GRACE_MS
                )
                DebugReporter.recordLivePlaybackEvent(
                    "legacy_huya_buffering_recovery_deferred",
                    currentChannel,
                    mapOf(
                        "reason" to "same_media_item_transport_reconnect_in_progress",
                        "remainingGraceMs" to remainingGraceMs,
                        "recoveryElapsedMs" to (nowElapsedMs - reconnectStartedAtMs).coerceAtLeast(0L),
                        "playbackPositionMs" to (player?.currentPosition ?: 0L)
                    )
                )
                binding.playerRoot.removeCallbacks(bufferingRecoveryRunnable)
                binding.playerRoot.postDelayed(
                    bufferingRecoveryRunnable,
                    minOf(3_000L, remainingGraceMs).coerceAtLeast(1L)
                )
                return
            }
            DebugReporter.reportPlaybackIssue(
                phase = "buffering_timeout",
                channel = currentChannel,
                message = "缓冲超过恢复阈值",
                playbackInfo = currentPlaybackInfo,
                decoderMode = currentDecoderMode
            )
            if (swapToStandbyHuyaStream(currentChannel, "缓冲超时")) {
                return
            }
            if (isHuyaHlsStream()) {
                huyaForceHlsForRecovery = false
                huyaForceFlvForRecovery = true
                Log.w(TAG, "Huya HLS buffering stalled; downgrade same channel to FLV")
            }
            if (refreshHuyaSingleStream(currentChannel, "虎牙缓冲超时，正在续签")) {
                return
            }
            if (refreshDouyuSingleStream(currentChannel, "斗鱼缓冲超时，正在续接同频道")) {
                return
            }
            if (isLegacyDirectHlsStream() &&
                scheduleLegacyHlsRecovery(currentChannel, "HLS 缓冲卡住，正在重新加载当前来源")
            ) {
                return
            }
            if (shouldAttemptGenericSameSourceRetry() && attemptSameSourceRetry("缓冲超时", forcePlayerRecreate = true)) {
                return
            }
            if (!preferences.autoFailoverEnabled) {
                schedulePersistentSourceRetry("缓冲超时")
                return
            }
            if (shouldDeferAutomaticRecovery()) {
                DebugReporter.recordLivePlaybackEvent(
                    "channel_failover_deferred_while_browsing",
                    currentChannel,
                    mapOf(
                        "phase" to "buffering_timeout",
                        "sameChannelRecoveryAttempted" to true
                    )
                )
                scheduleBufferingRecovery()
                return
            }
            if (preferences.autoFailoverEnabled) {
                recordFailedPlaybackSource()
            }
            if (viewModel.switchToFallbackSource(currentChannel, failedPlaybackSourceIds)) {
                showStatusHint("缓冲超时，正在切换备用来源", STATUS_HINT_LONG_MS)
                revealChannelInfoOverlay()
                return
            }
            advanceToNextChannelAfterFailure("缓冲超时", null)
        }
    }

    /**
     * 当前逻辑频道的所有来源和有限次数重试均失败后，自动切换到下一可用频道。
     * 失败频道会加入本次播放会话的排除集合，避免在坏源列表中反复循环。
     */
    private fun advanceToNextChannelAfterFailure(reason: String, error: Throwable?) {
        if (shouldDeferAutomaticRecovery()) return
        if (!preferences.autoFailoverEnabled) {
            schedulePersistentSourceRetry(reason)
            return
        }

        val channel = currentChannel
        val channelKey = currentMergedChannel?.key ?: "channel:${channel?.id ?: -1L}"
        autoFailedChannelKeys += channelKey

        if (autoFailoverAttemptCount >= maxAutomaticFailoverChannels()) {
            hideBuffering()
            player?.stop()
            showStatusHint(
                "已连续跳过 ${maxAutomaticFailoverChannels()} 个无法播放的频道，请检查播放源",
                STATUS_HINT_LONG_MS
            )
            Log.e(TAG, "Automatic channel failover limit reached, reason=$reason", error)
            return
        }

        autoFailoverAttemptCount += 1
        autoFailoverInProgress = true
        val moved = viewModel.playNextChannelExcluding(autoFailedChannelKeys)
        if (moved) {
            hideBuffering()
            showStatusHint(
                "$reason，正在切换下一频道（$autoFailoverAttemptCount/${maxAutomaticFailoverChannels()}）",
                STATUS_HINT_DEFAULT_MS
            )
            Log.w(TAG, "Auto failover from ${channel?.name} to next channel, reason=$reason", error)
        } else {
            autoFailoverInProgress = false
            hideBuffering()
            player?.stop()
            showStatusHint("没有找到可播放的下一频道，请检查播放源", STATUS_HINT_LONG_MS)
            Log.e(TAG, "No available next channel after ${channel?.name}, reason=$reason", error)
        }
    }

    /**
     * On the MStar profile, a direct HLS playlist that stops advancing must
     * get one delayed reload.  Re-preparing immediately from the ExoPlayer
     * error callback is what leaves a compressed old SurfaceView frame and
     * repeatedly flushes the vendor codec.  This method deliberately does not
     * mark the channel unavailable: the channel may still be perfectly valid.
     */
    private fun scheduleLegacyHlsRecovery(channel: Channel?, reason: String): Boolean {
        if (!isLegacyDirectHlsStream() || channel == null) return false
        if (legacyHlsRecoveryChannelId != channel.id) {
            resetLegacyHlsRecoveryState()
            legacyHlsRecoveryChannelId = channel.id
        }
        if (legacyHlsRecoveryJob?.isActive == true) return true
        val now = System.currentTimeMillis()
        if (legacyHlsRecoveryAttempt >= MAX_LEGACY_HLS_RECOVERY_ATTEMPTS ||
            (lastLegacyHlsRecoveryAtMs > 0L &&
                now - lastLegacyHlsRecoveryAtMs < LEGACY_HLS_RECOVERY_COOLDOWN_MS)
        ) {
            return false
        }
        legacyHlsRecoveryAttempt += 1
        lastLegacyHlsRecoveryAtMs = now
        showStatusHint("$reason（第 ${legacyHlsRecoveryAttempt}/${MAX_LEGACY_HLS_RECOVERY_ATTEMPTS} 次）", STATUS_HINT_LONG_MS)
        legacyHlsRecoveryJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(LEGACY_HLS_RECOVERY_DELAY_MS)
            if (currentChannel?.id == channel.id && !isFinishing && !isDestroyed) {
                playChannel(
                    channel = channel,
                    isRetry = true,
                    forcePlayerRecreate = false,
                    seamlessRetry = true
                )
            }
            legacyHlsRecoveryJob = null
        }
        return true
    }

    /**
     * The user can opt out of source/channel failover.  In that mode the
     * selected source remains authoritative and is retried indefinitely, with
     * a longer pause after the normal finite retry budget is exhausted.
     */
    private fun schedulePersistentSourceRetry(reason: String): Boolean {
        val channel = currentChannel ?: return false
        if (persistentSourceRetryJob?.isActive == true) return true

        val exhausted = playbackRetryCount >= maxSameSourceRetries()
        if (exhausted) {
            playbackRetryCount = 0
            failedPlaybackSourceIds.clear()
        }
        val delayMs = if (exhausted) {
            PERSISTENT_SOURCE_RETRY_DELAY_AFTER_EXHAUSTION_MS
        } else {
            PERSISTENT_SOURCE_RETRY_DELAY_MS
        }
        showStatusHint(
            "$reason，保持当前来源持续重试${if (exhausted) "（已退避）" else ""}",
            STATUS_HINT_LONG_MS
        )
        persistentSourceRetryJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (currentChannel?.id == channel.id && !isFinishing && !isDestroyed) {
                persistentSourceRetryJob = null
                if (HuyaStreamResolver.supports(channel.getDisplayUrl())) {
                    huyaCdnRotationIndex = if (huyaCdnRotationIndex == Int.MAX_VALUE) {
                        0
                    } else {
                        huyaCdnRotationIndex + 1
                    }
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_persistent_retry_rotated",
                        channel,
                        mapOf(
                            "attempt" to huyaCdnRotationIndex,
                            "reason" to reason,
                            "previousResolvedBy" to currentPlaybackInfo?.resolvedBy
                        )
                    )
                }
                playChannel(
                    channel = channel,
                    isRetry = true,
                    forcePlayerRecreate = isLowEndTvDevice,
                    seamlessRetry = false
                )
            } else {
                persistentSourceRetryJob = null
            }
        }
        return true
    }

    private fun cancelPersistentSourceRetry() {
        persistentSourceRetryJob?.cancel()
        persistentSourceRetryJob = null
    }

    private fun notePlaybackProgress(force: Boolean = false) {
        val currentPosition = player?.currentPosition ?: 0L
        if (force || currentPosition > lastPlaybackPositionMs + PLAYBACK_PROGRESS_EPSILON_MS) {
            lastPlaybackPositionMs = currentPosition
            lastPlaybackProgressRealtimeMs = System.currentTimeMillis()
        }
    }

    private fun resetVideoFrameWatchdogBaseline(nowMs: Long = System.currentTimeMillis()) {
        lastVideoClockObservation = null
        lastRenderedVideoFrameRealtimeMs = 0L
        lastVideoDecoderOutputRealtimeMs = 0L
        lastVideoDecoderRenderedBufferCount = player?.videoDecoderCounters?.let { counters ->
            counters.ensureUpdated()
            counters.renderedOutputBufferCount
        }
        videoFrameMonitoringStartedAtMs = nowMs.takeIf { activeVideoTrackCount > 0 } ?: 0L
    }

    private fun beginVideoFrameTrackingForNewSource() {
        mediaSourceGeneration = if (mediaSourceGeneration == Long.MAX_VALUE) 0L else mediaSourceGeneration + 1L
        activeAudioAdvancingAtNanos = 0L
        // A retry job is bound to the URL that was active when it was
        // scheduled. If it survives a source handoff, it can occupy the
        // single-job slot, later abort as stale, and leave a silent renewed
        // segment without an audio recovery check until the next expiry.
        huyaMissingAudioRetryJob?.cancel()
        huyaMissingAudioRetryJob = null
        huyaRenewalFallbackJob?.cancel()
        huyaRenewalFallbackJob = null
        resetVideoFrameWatchdogBaseline()
        lastVideoFrameDiagnosticAtMs = 0L
        lastPlaybackWatchdogDiagnosticAtMs = 0L
    }

    private fun startPlaybackProgressWatchdog() {
        if (playbackProgressWatchdogJob?.isActive == true) return
        playbackProgressWatchdogJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(
                    if (DeviceProfile.isLegacyTv(this@PlayerActivity)) {
                        LEGACY_PLAYBACK_PROGRESS_WATCHDOG_INTERVAL_MS
                    } else {
                        PLAYBACK_PROGRESS_WATCHDOG_INTERVAL_MS
                    }
                )
                val activePlayer = player ?: continue
                val channel = currentChannel ?: continue
                if (activePlayer.playbackState != Player.STATE_READY) continue
                if (!activePlayer.playWhenReady) continue
                val now = System.currentTimeMillis()
                val position = activePlayer.currentPosition
                val positionAdvanced = position > lastPlaybackPositionMs + PLAYBACK_PROGRESS_EPSILON_MS
                if (positionAdvanced) {
                    notePlaybackProgress(force = true)
                }
                if (lastPlaybackProgressRealtimeMs == 0L) {
                    notePlaybackProgress(force = true)
                }
                val videoDecoderCounters = activePlayer.videoDecoderCounters
                videoDecoderCounters?.ensureUpdated()
                val renderedVideoOutputBufferCount = videoDecoderCounters?.renderedOutputBufferCount
                if (renderedVideoOutputBufferCount != null) {
                    val previousRenderedCount = lastVideoDecoderRenderedBufferCount
                    when {
                        previousRenderedCount == null -> Unit
                        renderedVideoOutputBufferCount > previousRenderedCount -> {
                            lastVideoDecoderOutputRealtimeMs = now
                        }
                        renderedVideoOutputBufferCount < previousRenderedCount -> {
                            // Decoder recreation resets its counters; rebase
                            // without mistaking the reset for forward progress.
                            lastVideoDecoderOutputRealtimeMs = 0L
                        }
                    }
                    lastVideoDecoderRenderedBufferCount = renderedVideoOutputBufferCount
                }
                if (DeviceProfile.isLegacyTv(this@PlayerActivity) &&
                    now - lastPlaybackWatchdogDiagnosticAtMs >= PLAYBACK_WATCHDOG_DIAGNOSTIC_INTERVAL_MS
                ) {
                    lastPlaybackWatchdogDiagnosticAtMs = now
                    val selectedGroupIndex = viewModel.selectedGroupIndex.value
                    val selectedChannel = viewModel.selectedVisibleChannel()
                    val surface = playerView.videoSurfaceView
                    val videoClock = lastVideoClockObservation
                    val videoClockAgeMs = videoClock?.let {
                        (System.nanoTime() - it.observedAtNs).coerceAtLeast(0L) / 1_000_000L
                    }
                    DebugReporter.recordLivePlaybackEvent(
                        "playback_watchdog_snapshot",
                        channel,
                        videoSurfaceGeometryDetails() + mapOf(
                            "playbackState" to playbackStateName(activePlayer.playbackState),
                            "playWhenReady" to activePlayer.playWhenReady,
                            "isPlaying" to activePlayer.isPlaying,
                            "playbackPositionMs" to position,
                            "videoPresentationTimeMs" to videoClock?.let { it.ptsUs / 1_000L },
                            "videoPresentationAgeMs" to videoClockAgeMs,
                            "videoVsPlaybackClockDeltaMs" to videoClock?.takeIf {
                                videoClockAgeMs != null && videoClockAgeMs < 1_000L
                            }?.let { it.ptsUs / 1_000L - position },
                            "videoScheduledAheadMs" to videoClock?.let {
                                (it.releaseAtNs - it.observedAtNs) / 1_000_000L
                            },
                            "videoTrackCount" to activeVideoTrackCount,
                            "supportedAudioTrackCount" to activeAudioTrackCount,
                            "audioPcmObserved" to activeAudioPcmObserved,
                            "lastAudiblePcmAgeMs" to lastAudiblePcmObservedAtNanos
                                .takeIf { it > 0L }
                                ?.let { (System.nanoTime() - it).coerceAtLeast(0L) / 1_000_000L },
                            "lastFrameAgeMs" to lastRenderedVideoFrameRealtimeMs
                                .takeIf { it > 0L }
                                ?.let { (now - it).coerceAtLeast(0L) },
                            "lastDecoderOutputAgeMs" to lastVideoDecoderOutputRealtimeMs
                                .takeIf { it > 0L }
                                ?.let { (now - it).coerceAtLeast(0L) },
                            "videoDecoderRenderedOutputBufferCount" to renderedVideoOutputBufferCount,
                            "lastPositionProgressAgeMs" to lastPlaybackProgressRealtimeMs
                                .takeIf { it > 0L }
                                ?.let { (now - it).coerceAtLeast(0L) },
                            "refreshInProgress" to refreshInProgress,
                            "automaticRecoveryDeferred" to shouldDeferAutomaticRecovery(),
                            "actionMenuVisible" to actionMenuVisible,
                            "recoverySuspensionRemainingMs" to
                                (automaticRecoverySuspendedUntilMs - now).coerceAtLeast(0L),
                            "selectorVisible" to viewModel.selectorVisible.value,
                            "selectedGroupIndex" to selectedGroupIndex,
                            "selectedGroupName" to viewModel.channelGroups.value
                                .getOrNull(selectedGroupIndex)?.name,
                            "selectedChannelIndex" to viewModel.selectedChannelIndex.value,
                            "selectedChannelName" to selectedChannel?.name,
                            "surfaceType" to when {
                                surface is SurfaceView -> "surface_view"
                                surface != null -> "texture_view"
                                else -> "none"
                            }
                        )
                    )
                }
                val playbackStalled = !positionAdvanced &&
                    now - lastPlaybackProgressRealtimeMs >= currentPlaybackStallTimeoutMs()
                val videoFrameStalled = shouldRecoverForVideoFrameStall(
                    hasVideoTrack = activeVideoTrackCount > 0,
                    playbackReady = activePlayer.playbackState == Player.STATE_READY,
                    playWhenReady = activePlayer.playWhenReady,
                    lastRenderedFrameAtMs = lastRenderedVideoFrameRealtimeMs,
                    lastDecoderOutputAtMs = lastVideoDecoderOutputRealtimeMs,
                    monitoringStartedAtMs = videoFrameMonitoringStartedAtMs,
                    nowMs = now,
                    timeoutMs = currentPlaybackStallTimeoutMs()
                )
                if (!playbackStalled && !videoFrameStalled) continue

                val stallReason = if (videoFrameStalled) "视频画面停滞" else "播放卡住"
                if (videoFrameStalled) {
                    DebugReporter.recordLivePlaybackEvent(
                        "video_frame_stall_confirmed",
                        channel,
                        mapOf(
                            "playbackPositionMs" to position,
                            "positionAdvancedThisCheck" to positionAdvanced,
                            "audioPositionAdvanced" to activeAudioPlaybackAdvanced,
                            "videoDecoderRenderedOutputBufferCount" to renderedVideoOutputBufferCount,
                            "lastFrameAgeMs" to lastRenderedVideoFrameRealtimeMs
                                .takeIf { it > 0L }
                                ?.let { (now - it).coerceAtLeast(0L) },
                            "lastDecoderOutputAgeMs" to lastVideoDecoderOutputRealtimeMs
                                .takeIf { it > 0L }
                                ?.let { (now - it).coerceAtLeast(0L) }
                        )
                    )
                    Log.w(
                        TAG,
                        "Video frames stalled for channel=${channel.name}, position=$position " +
                            "videoTrackCount=$activeVideoTrackCount isPlaying=${activePlayer.isPlaying}"
                    )
                    requestLegacySurfaceLayoutRepair("video_frame_stall")
                } else {
                    Log.w(
                        TAG,
                        "Playback progress stalled for channel=${channel.name}, " +
                            "position=$position isPlaying=${activePlayer.isPlaying} " +
                            "playWhenReady=${activePlayer.playWhenReady}"
                    )
                }
                DebugReporter.reportPlaybackIssue(
                    phase = if (videoFrameStalled) "video_frame_stall" else "playback_stall",
                    channel = channel,
                    message = if (videoFrameStalled) {
                        "视频帧超过恢复阈值未渲染，position=$position，trackCount=$activeVideoTrackCount"
                    } else {
                        "播放进度超过恢复阈值未变化，position=$position"
                    },
                    playbackInfo = currentPlaybackInfo,
                    decoderMode = currentDecoderMode
                )
                if (swapToStandbyHuyaStream(channel, stallReason)) {
                    resetVideoFrameWatchdogBaseline(now)
                    notePlaybackProgress(force = true)
                    continue
                }
                if (isHuyaHlsStream()) {
                    huyaForceHlsForRecovery = false
                    huyaForceFlvForRecovery = true
                    Log.w(TAG, "Huya HLS frames stalled; downgrade same channel to FLV")
                }
                if (refreshHuyaSingleStream(
                        channel,
                        if (videoFrameStalled) "虎牙画面停滞，正在续签" else "虎牙播放卡住，正在续签",
                        recoveryDelayMs = if (videoFrameStalled) {
                            LEGACY_HUYA_HTTP_RETRY_DELAY_MS
                        } else {
                            LEGACY_HUYA_RECOVERY_DELAY_MS
                        }
                    )
                ) {
                    resetVideoFrameWatchdogBaseline(now)
                    notePlaybackProgress(force = true)
                    continue
                }
                if (refreshDouyuSingleStream(channel, stallReason + "，正在续接同频道")) {
                    resetVideoFrameWatchdogBaseline(now)
                    notePlaybackProgress(force = true)
                    continue
                }
                if (isLegacyDirectHlsStream() &&
                    scheduleLegacyHlsRecovery(channel, "HLS 画面停滞，正在重新加载当前来源")
                ) {
                    resetVideoFrameWatchdogBaseline(now)
                    notePlaybackProgress(force = true)
                    continue
                }
                if (preferences.autoFailoverEnabled) {
                    recordFailedPlaybackSource()
                }
                if (shouldAttemptGenericSameSourceRetry() &&
                    attemptSameSourceRetry(
                        stallReason,
                        forcePlayerRecreate = true,
                        delayMs = if (videoFrameStalled) 0L else SAME_SOURCE_RETRY_DELAY_MS
                    )
                ) {
                    resetVideoFrameWatchdogBaseline(now)
                    notePlaybackProgress(force = true)
                    continue
                }
                if (!preferences.autoFailoverEnabled) {
                    schedulePersistentSourceRetry(stallReason)
                    resetVideoFrameWatchdogBaseline(now)
                    notePlaybackProgress(force = true)
                    continue
                }
                if (shouldDeferAutomaticRecovery()) {
                    DebugReporter.recordLivePlaybackEvent(
                        "channel_failover_deferred_while_browsing",
                        channel,
                        mapOf(
                            "reason" to stallReason,
                            "sameChannelRecoveryAttempted" to true,
                            "selectedGroupName" to viewModel.channelGroups.value
                                .getOrNull(viewModel.selectedGroupIndex.value)?.name,
                            "selectedChannelIndex" to viewModel.selectedChannelIndex.value
                        )
                    )
                    resetVideoFrameWatchdogBaseline(now)
                    notePlaybackProgress(force = true)
                    continue
                }
                if (viewModel.switchToFallbackSource(currentChannel, failedPlaybackSourceIds)) {
                    showStatusHint("$stallReason，正在切换备用来源", STATUS_HINT_LONG_MS)
                    revealChannelInfoOverlay()
                } else {
                    advanceToNextChannelAfterFailure(stallReason, null)
                }
                resetVideoFrameWatchdogBaseline(now)
                notePlaybackProgress(force = true)
            }
        }
    }

    private fun attemptSameSourceRetry(
        reason: String,
        forcePlayerRecreate: Boolean = false,
        delayMs: Long = SAME_SOURCE_RETRY_DELAY_MS,
        seamlessRetry: Boolean = false
    ): Boolean {
        val channel = currentChannel ?: return false
        if (playbackRetryChannelId != channel.id) {
            playbackRetryChannelId = channel.id
            playbackRetryCount = 0
        }
        if (playbackRetryCount >= maxSameSourceRetries()) {
            return false
        }
        playbackRetryCount += 1
        retryStabilityJob?.cancel()
        retryStabilityJob = null
        refreshInProgress = true
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            val shouldShowRetryToast = delayMs >= BUFFERING_INDICATOR_DELAY_MS &&
                reason !in setOf("播放卡住", "缓冲超时")
            val shouldShowRetryHint = shouldShowRetryToast || isLowEndTvDevice
            if (shouldShowRetryHint) {
                showStatusHint(buildRetryHint(reason), STATUS_HINT_DEFAULT_MS)
            }
            kotlinx.coroutines.delay(delayMs)
            if (currentChannel?.id == channel.id) {
                // releasePlayer() is also used by a controlled recreation.  Do
                // not leave this coroutine registered as retryJob, otherwise it
                // cancels itself halfway through applying the new media source.
                retryJob = null
                playChannel(
                    channel = channel,
                    isRetry = true,
                    forcePlayerRecreate = !isLowEndTvDevice &&
                        (forcePlayerRecreate || shouldForceRecreateOnRetry(channel)),
                    seamlessRetry = seamlessRetry
                )
            }
        }
        return true
    }

    private fun resetPlaybackRetries() {
        retryStabilityJob?.cancel()
        retryStabilityJob = null
        playbackRetryCount = 0
        refreshInProgress = false
        retryJob?.cancel()
    }

    private fun scheduleRetryStabilityReset() {
        retryStabilityJob?.cancel()
        retryStabilityJob = null
        if (playbackRetryCount <= 0 &&
            douyuContinuityAttempt <= 0 &&
            legacyHlsRecoveryAttempt <= 0 &&
            huyaCdnRecoveryAttempt <= 0
        ) return
        val channelId = currentChannel?.id ?: return
        retryStabilityJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(PLAYBACK_RETRY_STABILITY_WINDOW_MS)
            val videoFramesRecent = activeVideoTrackCount == 0 ||
                (lastRenderedVideoFrameRealtimeMs > 0L &&
                    System.currentTimeMillis() - lastRenderedVideoFrameRealtimeMs < currentPlaybackStallTimeoutMs())
            if (currentChannel?.id == channelId &&
                player?.playbackState == Player.STATE_READY &&
                player?.playWhenReady == true &&
                !refreshInProgress &&
                videoFramesRecent
            ) {
                Log.d(TAG, "Playback remained stable; resetting retry budget for channel=${currentChannel?.name}")
                playbackRetryCount = 0
                douyuContinuityAttempt = 0
                douyuContinuityChannelId = channelId
                huyaCdnRecoveryAttempt = 0
                lastHuyaRecoveryAtMs = 0L
                resetLegacyHlsRecoveryState()
                currentChannel?.id?.let { failedPlaybackSourceIds.remove(it) }
                // A Huya prewarm may have been deferred while this stream's
                // retry budget was exhausted. Re-evaluate it immediately at
                // the source-age deadline instead of waiting for another
                // buffering/READY cycle to restart its timer.
                scheduleHuyaRecoveryPrewarm(currentChannel, currentPlaybackInfo)
            }
            retryStabilityJob = null
        }
    }

    private fun currentBufferingRecoveryTimeoutMs(): Long {
        return if (isLegacyMstarHuyaStream()) {
            LEGACY_HUYA_BUFFERING_RECOVERY_TIMEOUT_MS
        } else if (isSinglePlayerHuyaStream()) {
            HUYA_BUFFERING_RECOVERY_TIMEOUT_MS
        } else if (isDouyuCompatibilityStream()) {
            DOUYU_ENTRY_BUFFERING_RECOVERY_TIMEOUT_MS
        } else {
            BUFFERING_RECOVERY_TIMEOUT_MS
        }
    }

    private fun currentPlaybackStallTimeoutMs(): Long {
        return if (isLegacyMstarHuyaStream()) {
            LEGACY_HUYA_PLAYBACK_STALL_TIMEOUT_MS
        } else if (isSinglePlayerHuyaStream()) {
            HUYA_PLAYBACK_STALL_TIMEOUT_MS
        } else if (isDouyuCompatibilityStream()) {
            DOUYU_ENTRY_PLAYBACK_STALL_TIMEOUT_MS
        } else {
            PLAYBACK_STALL_TIMEOUT_MS
        }
    }

    private fun shouldForceRecreateOnRetry(channel: Channel): Boolean {
        return !isLowEndTvDevice &&
            DouyuStreamResolver.supports(channel.url) &&
            !isDouyuCompatibilityStream()
    }

    private fun maxSameSourceRetries(): Int = when {
        isLowEndTvDevice -> 1
        // Dynamic Huya URLs can renew successfully after a transient 403 or a
        // temporarily empty room response.  Give them three bounded attempts
        // before the generic next-channel failover takes over.
        currentPlaybackInfo?.resolvedBy?.startsWith("huya:") == true -> 3
        else -> MAX_SAME_SOURCE_RETRY
    }

    private fun maxAutomaticFailoverChannels(): Int =
        if (isLowEndTvDevice) 3 else MAX_AUTOMATIC_FAILOVER_CHANNELS

    private fun isDouyuCompatibilityStream(): Boolean {
        val resolvedBy = currentPlaybackInfo?.resolvedBy.orEmpty()
        return resolvedBy.startsWith("douyu-")
    }

    private fun isDouyuStream(channel: Channel?): Boolean {
        return channel != null && (
            DouyuStreamResolver.supports(channel.getDisplayUrl()) ||
                currentPlaybackInfo?.resolvedBy?.startsWith("douyu-") == true
            )
    }

    private fun recordFailedPlaybackSource() {
        currentChannel?.id?.takeIf { it > 0L }?.let { sourceId ->
            if (failedPlaybackSourceIds.add(sourceId)) {
                Log.w(TAG, "Marked playback source as failed for this session: channelId=$sourceId name=${currentChannel?.name}")
            }
        }
    }

    private fun isSinglePlayerHuyaStream(): Boolean =
        currentPlaybackInfo?.resolvedBy?.startsWith("huya:") == true && !isHuyaHotSwapEnabled()

    private fun isLegacyMstarHuyaStream(): Boolean =
        isLowEndTvDevice && currentPlaybackInfo?.resolvedBy?.startsWith("huya:") == true

    private fun usesContinuousLegacyHuyaTransport(info: PlaybackStreamInfo?): Boolean =
        DeviceProfile.isLegacyTv(this) && info?.resolvedBy?.startsWith("huya:") == true &&
            info.mimeType == "video/x-flv"

    private fun shouldAssumeLegacyHuyaVideoTrack(): Boolean =
        isLegacyMstarHuyaStream() && currentPlaybackInfo?.mimeType == "video/x-flv"

    private fun shouldAttemptGenericSameSourceRetry(): Boolean =
        !isDouyuStream(currentChannel) &&
            !isLegacyMstarHuyaStream() &&
            !isLegacyDirectHlsStream()

    private fun isLegacyDirectHlsStream(): Boolean =
        isLowEndTvDevice &&
            isHlsPlaybackInfo(currentPlaybackInfo) &&
            !isHuyaStream(currentChannel) &&
            !isDouyuStream(currentChannel)

    private fun isHlsPlaybackInfo(info: PlaybackStreamInfo?): Boolean {
        if (info == null) return false
        return info.mimeType == com.google.android.exoplayer2.util.MimeTypes.APPLICATION_M3U8 ||
            info.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
    }

    private fun isHuyaStream(channel: Channel?): Boolean {
        return channel != null && (
            HuyaStreamResolver.supports(channel.getDisplayUrl()) ||
                currentPlaybackInfo?.resolvedBy?.startsWith("huya:") == true
            )
    }

    private fun isHuyaHlsStream(): Boolean =
        currentPlaybackInfo?.resolvedBy?.startsWith("huya:") == true &&
            currentPlaybackInfo?.mimeType == com.google.android.exoplayer2.util.MimeTypes.APPLICATION_M3U8

    private fun currentHuyaRecoverySchemeOverride(): String? = huyaRecoverySchemeOverride(
        forceHls = huyaForceHlsForRecovery,
        forceFlv = huyaForceFlvForRecovery
    )

    private fun isHuyaHlsFailure(error: Throwable?): Boolean {
        return shouldFallbackHuyaHlsToFlv(
            isHuyaHls = isHuyaHlsStream(),
            httpStatusCode = findHttpResponseError(error)?.responseCode
        )
    }

    private fun findHttpResponseError(error: Throwable?): HttpDataSource.InvalidResponseCodeException? =
        generateSequence(error) { it.cause }
            .take(12)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()

    private fun huyaRetryDelayForError(error: Throwable): Long {
        val rootCause = findRootCause(error)
        val sourceRequestFailed = findHttpResponseError(error) != null ||
            rootCause is IOException || rootCause is IllegalArgumentException
        return legacyHuyaRetryDelayMs(
            isLegacyMstar = isLegacyMstarHuyaStream(),
            sourceRequestFailed = sourceRequestFailed,
            normalDelayMs = LEGACY_HUYA_RECOVERY_DELAY_MS,
            rejectedSourceDelayMs = LEGACY_HUYA_HTTP_RETRY_DELAY_MS
        )
    }

    /** A rejected Huya HLS request gets one bounded, same-channel FLV attempt. */
    private fun scheduleHuyaHlsFlvFallback(channel: Channel?, error: Throwable): Boolean {
        val activeChannel = channel ?: return false
        val httpError = findHttpResponseError(error) ?: return false
        val isLegacyHlsEscapeProbe = isLegacyMstarHuyaStream() &&
            legacyHuyaHlsFallbackAttempted
        if (!isHuyaHlsFailure(error) ||
            (huyaHlsFlvFallbackAttempted && !isLegacyHlsEscapeProbe)
        ) return false

        if (huyaUnsupportedFlvVideoFormatId != null) {
            DebugReporter.recordLivePlaybackEvent(
                "huya_hls_flv_fallback_suppressed",
                activeChannel,
                mapOf(
                    "httpStatusCode" to httpError.responseCode,
                    "reason" to "flv_codec_incompatible",
                    "videoFormatId" to huyaUnsupportedFlvVideoFormatId,
                    "recoveryAction" to "keep_same_channel_hls_retry"
                )
            )
            return false
        }

        huyaHlsFlvFallbackAttempted = true
        huyaForceHlsForRecovery = false
        huyaForceFlvForRecovery = true
        refreshInProgress = true
        huyaPrewarmJob?.cancel()
        prewarmedHuyaPlaybackInfo = null
        prewarmedHuyaAttempt = -1
        showStatusHint("虎牙 HLS 请求被拒，正在重取 FLV 并保留当前频道", STATUS_HINT_LONG_MS)
        DebugReporter.recordLivePlaybackEvent(
            "huya_hls_http_fallback",
            activeChannel,
            mapOf(
                "httpStatusCode" to httpError.responseCode,
                "httpErrorType" to httpError.javaClass.simpleName,
                "recoveryAction" to "retry_same_channel_with_fresh_flv"
            )
        )
        Log.w(
            TAG,
            "Huya HLS returned HTTP ${httpError.responseCode}; forcing one same-channel FLV retry"
        )

        val retry = {
            if (currentChannel?.id == activeChannel.id && !isFinishing && !isDestroyed) {
                refreshInProgress = false
                playChannel(
                    channel = activeChannel,
                    isRetry = true,
                    forcePlayerRecreate = isLowEndTvDevice,
                    isProactiveRefresh = true
                )
            } else {
                refreshInProgress = false
            }
        }
        if (isLegacyMstarHuyaStream()) {
            huyaRecoveryJob?.cancel()
            huyaRecoveryJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(LEGACY_HUYA_RECOVERY_DELAY_MS)
                retry()
                huyaRecoveryJob = null
            }
        } else {
            retry()
        }
        return true
    }

    private fun scheduleStreamRefresh(channel: Channel, playbackInfo: PlaybackStreamInfo) {
        streamRefreshJob?.cancel()
        // On the MStar profile a proactive source replacement is more likely
        // to trigger an OMX flush storm than to improve continuity. Let the
        // bounded buffering/progress watchdog handle a real stream failure.
        if (isLowEndTvDevice && playbackInfo.resolvedBy.startsWith("huya:")) {
            nextStreamRefreshAtMillis = null
            return
        }
        val delayMs = when {
            playbackInfo.resolvedBy.startsWith("huya:") -> HUYA_PROACTIVE_REFRESH_MS
            playbackInfo.resolvedBy.startsWith("douyu-") && playbackInfo.expiresAtMillis != null -> {
                val untilExpiry = playbackInfo.expiresAtMillis - System.currentTimeMillis()
                (untilExpiry - DOUYU_EXPIRY_SAFETY_WINDOW_MS)
                    .coerceAtLeast(30_000L)
                    .coerceAtMost(DOUYU_PROACTIVE_REFRESH_MS)
            }
            playbackInfo.resolvedBy.startsWith("douyu-") -> DOUYU_PROACTIVE_REFRESH_MS
            else -> return
        }
        nextStreamRefreshAtMillis = System.currentTimeMillis() + delayMs
        val streamType = when {
            playbackInfo.resolvedBy.startsWith("huya:") -> "Huya"
            playbackInfo.resolvedBy.startsWith("douyu-") -> "Douyu"
            else -> "stream"
        }
        Log.d(TAG, "Scheduling $streamType stream refresh in ${delayMs}ms for channel=${channel.name}")
        streamRefreshJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (currentChannel?.id == channel.id && !isFinishing && !isDestroyed && !refreshInProgress) {
                if (playbackInfo.resolvedBy.startsWith("huya:") &&
                    playbackInfo.mimeType == com.google.android.exoplayer2.util.MimeTypes.APPLICATION_M3U8
                ) {
                    // A renewed Huya HLS manifest may still be rejected.
                    // Keep the recovery flags mutually exclusive so a prior
                    // HLS escape probe cannot override this FLV downgrade.
                    huyaForceHlsForRecovery = false
                    huyaForceFlvForRecovery = true
                }
                refreshInProgress = true
                Log.d(TAG, "Refreshing $streamType stream proactively for channel=${channel.name}")
                playChannel(
                    channel = channel,
                    isRetry = true,
                    forcePlayerRecreate = false,
                    isProactiveRefresh = true,
                    // Keep the ExoPlayer/MediaCodec instance alive on MStar;
                    // only the signed media source is replaced.  This is the
                    // same-room continuity path, not a channel failover.
                    seamlessRetry = playbackInfo.resolvedBy.startsWith("douyu-")
                )
            }
        }
    }

    /**
     * Renew a Douyu URL for the current room before declaring the source bad.
     * A segment/FLV connection ending is expected to be recoverable by signing
     * a fresh URL, so this path must run before failedPlaybackSourceIds or the
     * next-channel failover is touched.
     */
    private fun refreshDouyuSingleStream(channel: Channel?, reason: String): Boolean {
        val activeChannel = channel ?: return false
        if (!isDouyuStream(activeChannel) || refreshInProgress) return false
        if (douyuContinuityChannelId != activeChannel.id) {
            douyuContinuityChannelId = activeChannel.id
            douyuContinuityAttempt = 0
        }
        if (douyuContinuityAttempt >= MAX_DOUYU_CONTINUITY_ATTEMPTS) {
            Log.w(
                TAG,
                "Douyu same-channel continuity limit reached for channel=${activeChannel.name}; " +
                    "source failover is now allowed"
            )
            return false
        }

        douyuContinuityAttempt += 1
        retryStabilityJob?.cancel()
        retryStabilityJob = null
        refreshInProgress = true
        showStatusHint(
            "$reason（同频道续接 ${douyuContinuityAttempt}/${MAX_DOUYU_CONTINUITY_ATTEMPTS}）",
            STATUS_HINT_LONG_MS
        )
        DebugReporter.reportPlaybackIssue(
            phase = "douyu_continuity",
            channel = activeChannel,
            message = "$reason；优先重新签名并续播同一房间，attempt=$douyuContinuityAttempt",
            playbackInfo = currentPlaybackInfo,
            decoderMode = currentDecoderMode
        )
        Log.w(
            TAG,
            "$reason, channel=${activeChannel.name}; renewing same-room Douyu stream " +
                "attempt=$douyuContinuityAttempt/$MAX_DOUYU_CONTINUITY_ATTEMPTS"
        )
        douyuContinuityJob?.cancel()
        douyuContinuityJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(DOUYU_CONTINUITY_RETRY_DELAY_MS)
            if (currentChannel?.id == activeChannel.id && !isFinishing && !isDestroyed) {
                douyuContinuityJob = null
                playChannel(
                    channel = activeChannel,
                    isRetry = true,
                    forcePlayerRecreate = false,
                    isProactiveRefresh = true,
                    seamlessRetry = true
                )
            } else {
                refreshInProgress = false
            }
        }
        return true
    }

    private fun resetDouyuContinuityState() {
        douyuContinuityJob?.cancel()
        douyuContinuityJob = null
        douyuProtectionRetryJob?.cancel()
        douyuProtectionRetryJob = null
        douyuContinuityChannelId = -1L
        douyuContinuityAttempt = 0
        douyuProtectionRetryCount = 0
    }

    /**
     * Append a verified next Huya FLV to the current ExoPlayer playlist. The
     * queued source may consume its validated prefix, but its HTTP EOF-reconnect
     * gate stays closed until cutover so it cannot become a second live fetcher.
     */
    private fun queueLegacyHuyaRenewal(
        channel: Channel,
        playbackInfo: PlaybackStreamInfo,
        attempt: Int,
        dataSource: PreconnectedHttpDataSource
    ): Boolean {
        val activePlayer = player ?: return false
        val activeInfo = currentPlaybackInfo ?: return false
        if (currentChannel?.id != channel.id ||
            !isLegacyMstarHuyaStream() ||
            !activeInfo.resolvedBy.startsWith("huya:") ||
            !shouldQueueLegacyHuyaRenewal(
                isLegacyTv = true,
                isFlv = playbackInfo.mimeType == "video/x-flv",
                audioFlag = dataSource.hasAudioTrackInFlvHeader,
                hasAudioPayload = dataSource.containsAudioPayload,
                hasVideoKeyframe = dataSource.containsVideoKeyframe
            ) || activePlayer.currentMediaItem == null
        ) {
            return false
        }

        discardQueuedHuyaRenewal()
        huyaQueueSequence = if (huyaQueueSequence == Long.MAX_VALUE) 0L else huyaQueueSequence + 1L
        val mediaId = "${channel.id}:huya-queued:${huyaQueueSequence}"
        val reconnectGate = LegacyHuyaFlvReconnectGate(initiallyEnabled = false)
        val mediaSource = PlayerHelper.createMediaSource(
            this,
            playbackInfo,
            mediaId,
            dataSource,
            huyaResponseTransferListener(channel, playbackInfo),
            huyaFlvEofReconnectListener(channel, mediaId, playbackInfo),
            huyaReconnectGate = reconnectGate,
            renewRejectedHuyaUrl = legacyHuyaFlvRenewalProvider(channel, mediaId, playbackInfo)
        )
        try {
            activePlayer.addMediaSource(mediaSource)
        } catch (error: Exception) {
            reconnectGate.cancel()
            closePreconnectedHuyaDataSourceAsync(dataSource)
            Log.w(TAG, "Could not queue the next Huya stream for ${channel.name}", error)
            return false
        }

        queuedHuyaMediaId = mediaId
        queuedHuyaPlaybackInfo = playbackInfo
        queuedHuyaAttempt = attempt
        queuedHuyaDataSource = dataSource
        queuedHuyaReconnectGate = reconnectGate
        queuedHuyaPreparedAtMs = System.currentTimeMillis()
        queuedHuyaConnectionOpenedAtMs = dataSource.openedAtElapsedRealtimeMs
        huyaCdnRotationIndex = maxOf(huyaCdnRotationIndex, attempt)
        DebugReporter.recordLivePlaybackEvent(
            "huya_renewal_candidate_queued",
            channel,
            mapOf(
                "mediaId" to mediaId,
                "resolvedBy" to playbackInfo.resolvedBy,
                "attempt" to attempt,
                "currentSourceAgeMs" to
                    (System.currentTimeMillis() - activeMediaSourceStartedAtMs).coerceAtLeast(0L),
                "audioFlag" to dataSource.hasAudioTrackInFlvHeader,
                "audioPayloadFound" to dataSource.containsAudioPayload,
                "audioPayloadFormat" to dataSource.audioPayloadFormat,
                "videoKeyframeFound" to dataSource.containsVideoKeyframe,
                "prebufferBytes" to dataSource.bufferedBytes,
                "connectionAgeAtQueueMs" to
                    (SystemClock.elapsedRealtime() - dataSource.openedAtElapsedRealtimeMs).coerceAtLeast(0L),
                "playlistItemCount" to activePlayer.mediaItemCount
            )
        )
        val activeMediaId = activePlayer.currentMediaItem?.mediaId ?: return true
        scheduleQueuedHuyaCutover(channel, activePlayer, activeMediaId)
        return true
    }

    private fun scheduleQueuedHuyaCutover(
        channel: Channel,
        scheduledPlayer: ExoPlayer,
        activeMediaId: String
    ) {
        queuedHuyaCutoverJob?.cancel()
        val delayMs = remainingSourceAgeDelayMs(
            sourceStartedAtMs = activeMediaSourceStartedAtMs,
            nowMs = System.currentTimeMillis(),
            targetSourceAgeMs = LEGACY_HUYA_QUEUED_CUTOVER_SOURCE_AGE_MS
        )
        queuedHuyaCutoverJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            val nowWallClockMs = System.currentTimeMillis()
            val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
            val sourceAgeMs = (nowWallClockMs - activeMediaSourceStartedAtMs).coerceAtLeast(0L)
            val hasCandidate = queuedHuyaMediaId != null &&
                queuedHuyaPlaybackInfo != null &&
                queuedHuyaDataSource != null &&
                isFreshPreconnectedHttpResponse(
                    queuedHuyaConnectionOpenedAtMs,
                    nowElapsedRealtimeMs,
                    LEGACY_HUYA_CONNECTION_PRECONNECT_MAX_AGE_MS
                )
            val samePlaybackSession = isSameQueuedHuyaPlaybackSession(
                requestedChannelId = channel.id,
                activeChannelId = currentChannel?.id ?: -1L,
                requestedMediaId = activeMediaId,
                activeMediaId = player
                    ?.takeIf { it === scheduledPlayer }
                    ?.currentMediaItem
                    ?.mediaId
            )
            if (!samePlaybackSession ||
                !shouldCutOverQueuedHuyaRenewal(
                    sourceAgeMs,
                    LEGACY_HUYA_QUEUED_CUTOVER_SOURCE_AGE_MS,
                    hasCandidate
                )
            ) {
                if (queuedHuyaMediaId != null && !hasCandidate && samePlaybackSession) {
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_queued_candidate_expired_before_cutover",
                        channel,
                        mapOf(
                            "connectionAgeMs" to
                                (nowElapsedRealtimeMs - queuedHuyaConnectionOpenedAtMs).coerceAtLeast(0L),
                            "queuedAgeMs" to
                                (nowWallClockMs - queuedHuyaPreparedAtMs).coerceAtLeast(0L),
                            "maximumConnectionAgeMs" to LEGACY_HUYA_CONNECTION_PRECONNECT_MAX_AGE_MS,
                            "sourceAgeMs" to sourceAgeMs
                        )
                    )
                    discardQueuedHuyaRenewal()
                    refreshHuyaSingleStream(
                        channel,
                        "虎牙预连接线路已过期，正在提前续签",
                        recoveryDelayMs = 0L,
                        plannedRenewal = true
                    )
                }
                queuedHuyaCutoverJob = null
                return@launch
            }
            cutOverQueuedHuyaStream(channel, "预签线路到达续接窗口")
        }
    }

    private fun cutOverQueuedHuyaStream(channel: Channel?, reason: String): Boolean {
        val activeChannel = channel ?: return false
        val activePlayer = player ?: return false
        val mediaId = queuedHuyaMediaId ?: return false
        val info = queuedHuyaPlaybackInfo ?: return false
        if (currentChannel?.id != activeChannel.id || currentPlaybackInfo?.resolvedBy?.startsWith("huya:") != true) {
            return false
        }
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val candidateAgeMs = nowElapsedRealtimeMs - queuedHuyaConnectionOpenedAtMs
        if (!isFreshPreconnectedHttpResponse(
                queuedHuyaConnectionOpenedAtMs,
                nowElapsedRealtimeMs,
                LEGACY_HUYA_CONNECTION_PRECONNECT_MAX_AGE_MS
            )
        ) return false
        val nextIndex = (0 until activePlayer.mediaItemCount)
            .firstOrNull { index -> activePlayer.getMediaItemAt(index).mediaId == mediaId }
            ?: return false
        val currentIndex = activePlayer.currentMediaItemIndex
        if (nextIndex <= currentIndex) {
            queuedHuyaReconnectGate?.enable()
            return true
        }

        DebugReporter.recordLivePlaybackEvent(
            "huya_queued_cutover_requested",
            activeChannel,
            mapOf(
                "reason" to reason,
                "resolvedBy" to info.resolvedBy,
                "sourceAgeMs" to (System.currentTimeMillis() - activeMediaSourceStartedAtMs).coerceAtLeast(0L),
                "candidateAgeMs" to candidateAgeMs,
                "connectionAgeMs" to candidateAgeMs,
                "currentIndex" to currentIndex,
                "nextIndex" to nextIndex
            )
        )
        queuedHuyaCutoverJob?.cancel()
        queuedHuyaCutoverJob = null
        return runCatching {
            queuedHuyaReconnectGate?.enable()
            activePlayer.seekTo(nextIndex, C.TIME_UNSET)
            activePlayer.playWhenReady = true
            scheduleQueuedHuyaTransitionWatchdog(
                activeChannel,
                mediaId,
                currentPlaybackInfo?.url.orEmpty()
            )
            true
        }.getOrElse { error ->
            queuedHuyaReconnectGate?.cancel()
            Log.w(TAG, "Could not switch to the queued Huya stream", error)
            false
        }
    }

    private fun scheduleQueuedHuyaTransitionWatchdog(
        channel: Channel,
        mediaId: String,
        activeSourceUrl: String
    ) {
        queuedHuyaTransitionWatchdogJob?.cancel()
        queuedHuyaTransitionWatchdogJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(LEGACY_HUYA_QUEUED_TRANSITION_TIMEOUT_MS)
            val activePlayer = player
            val transitionStillPending = currentChannel?.id == channel.id &&
                queuedHuyaMediaId == mediaId &&
                currentPlaybackInfo?.url == activeSourceUrl &&
                activePlayer?.currentMediaItem?.mediaId != mediaId &&
                !refreshInProgress && !isFinishing && !isDestroyed
            if (!transitionStillPending) {
                queuedHuyaTransitionWatchdogJob = null
                return@launch
            }
            DebugReporter.recordLivePlaybackEvent(
                "huya_queued_transition_timeout",
                channel,
                mapOf(
                    "mediaId" to mediaId,
                    "timeoutMs" to LEGACY_HUYA_QUEUED_TRANSITION_TIMEOUT_MS,
                    "playbackState" to activePlayer?.playbackState?.let(::playbackStateName),
                    "playbackPositionMs" to activePlayer?.currentPosition
                )
            )
            queuedHuyaTransitionWatchdogJob = null
            discardQueuedHuyaRenewal()
            refreshHuyaSingleStream(
                channel,
                "虎牙续签线路未能接入，正在重新请求同一频道",
                recoveryDelayMs = 0L,
                plannedRenewal = true
            )
        }
    }

    private fun handleQueuedHuyaMediaItemTransition(
        activePlayer: ExoPlayer,
        mediaItem: MediaItem,
        reason: Int
    ) {
        val mediaId = queuedHuyaMediaId ?: return
        if (mediaItem.mediaId != mediaId || activePlayer !== player) return
        val channel = currentChannel ?: return
        val info = queuedHuyaPlaybackInfo ?: return
        queuedHuyaReconnectGate?.enable()
        val nowWallClock = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val previousFrameAtMs = lastRenderedVideoFrameRealtimeMs
        val previousSourceAgeMs = (nowWallClock - activeMediaSourceStartedAtMs).coerceAtLeast(0L)
        val preparedAtMs = queuedHuyaPreparedAtMs
        val connectionAgeAtTransitionMs =
            (nowElapsed - queuedHuyaConnectionOpenedAtMs).coerceAtLeast(0L)

        resetHuyaShortStreamTracking()
        currentPlaybackInfo = info
        huyaCdnRotationIndex = maxOf(huyaCdnRotationIndex, queuedHuyaAttempt)
        activeMediaSourceStartedAtMs = nowWallClock
        activePlaybackStartedAtMs = nowWallClock
        scheduleLegacyHuyaSourceAgeGuard(channel, info)
        val previousProgressPositionMs = lastPlaybackPositionMs
        // A queued MediaItem handoff can stay in STATE_READY and therefore
        // does not produce another READY callback to reset the progress
        // watchdog. currentPosition also restarts at zero for the new item;
        // rebase here or the watchdog compares it with the old segment's
        // ~118-second position and can misclassify a healthy renewal as a
        // permanent freeze (or fail to arm a useful baseline).
        notePlaybackProgress(force = true)
        activeAudioTrackCount = 0
        activeAudioPlaybackAdvanced = false
        activeAudioAdvancingAtNanos = 0L
        activeAudioPcmObserved = false
        legacyHuyaPcmEvidence.reset()
        activeVideoTrackCount = 0
        activeVideoSizeSeenForSource = false
        beginVideoFrameTrackingForNewSource()
        armLegacyHuyaFrameWatchdog(info)

        pendingHuyaRenewalFirstFrameAtMs = nowWallClock
        pendingHuyaRenewalLastFrameAtMs = previousFrameAtMs
        pendingHuyaRenewalFirstFrameChannelId = channel.id
        pendingHuyaRenewalStartedAtElapsedMs = nowElapsed
        pendingHuyaRenewalResolvedAtElapsedMs = preparedAtMs
            .takeIf { it > 0L }
            ?.let { (nowElapsed - (nowWallClock - it).coerceAtLeast(0L)).coerceAtLeast(0L) }
            ?: nowElapsed
        pendingHuyaRenewalSourceAppliedAtElapsedMs = nowElapsed
        pendingHuyaRenewalCandidateWasPrewarmed = true
        pendingHuyaRenewalUsedPreconnectedDataSource = true
        pendingHuyaRenewalFirstFrameSeen = false
        pendingHuyaRenewalAudioAdvanced = false
        refreshInProgress = false
        recordHuyaRenewalStage("source_applied")
        DebugReporter.recordLivePlaybackEvent(
            "huya_queued_media_item_transition",
            channel,
            mapOf(
                "mediaId" to mediaId,
                "resolvedBy" to info.resolvedBy,
                "reason" to reason,
                "previousSourceAgeMs" to previousSourceAgeMs,
                "connectionAgeMs" to connectionAgeAtTransitionMs,
                "decoderReused" to true,
                "progressWatchdogRebased" to true,
                "previousProgressPositionMs" to previousProgressPositionMs,
                "newProgressPositionMs" to activePlayer.currentPosition
            )
        )

        // Keep at most the active item after a successful transition. Leaving
        // old FLV items in the playlist would grow it on every two-minute
        // renewal on this low-memory device.
        val activeIndex = activePlayer.currentMediaItemIndex
        binding.playerRoot.post {
            if (activePlayer === player && activePlayer.currentMediaItem?.mediaId == mediaId && activeIndex > 0) {
                runCatching { activePlayer.removeMediaItems(0, activeIndex) }
            }
        }
        queuedHuyaCutoverJob?.cancel()
        queuedHuyaCutoverJob = null
        queuedHuyaTransitionWatchdogJob?.cancel()
        queuedHuyaTransitionWatchdogJob = null
        queuedHuyaMediaId = null
        queuedHuyaPlaybackInfo = null
        queuedHuyaAttempt = -1
        queuedHuyaDataSource = null // ownership has transferred to ExoPlayer
        queuedHuyaReconnectGate = null // the active MediaSource keeps its enabled gate
        queuedHuyaPreparedAtMs = 0L
        queuedHuyaConnectionOpenedAtMs = 0L

        // A playlist item can transition while ExoPlayer remains in READY,
        // so relying on another STATE_READY callback leaves the next renewal
        // cycle unarmed. This caused a renewed FLV to play for its full
        // lifetime, then freeze without another request. Re-arm both the next
        // signed-URL prewarm and the silent-audio guard at every handoff.
        refreshActiveTrackCountsFromCurrentPlayer()
        scheduleHuyaRecoveryPrewarm(channel, info)
        if (activePlayer.playbackState == Player.STATE_READY && !activeAudioPcmObserved) {
            scheduleHuyaMissingAudioRecovery()
        }
        DebugReporter.recordLivePlaybackEvent(
            "huya_queued_transition_watchdogs_rearmed",
            channel,
            mapOf(
                "sourceAgeMs" to (System.currentTimeMillis() - activeMediaSourceStartedAtMs).coerceAtLeast(0L),
                "supportedAudioTrackCount" to activeAudioTrackCount,
                "audioPositionAdvanced" to activeAudioPlaybackAdvanced,
                "nonSilentPcmObserved" to activeAudioPcmObserved,
                "nextRenewalPrewarmScheduled" to (huyaPrewarmJob?.isActive == true)
            )
        )
    }

    private fun discardQueuedHuyaRenewal() {
        queuedHuyaCutoverJob?.cancel()
        queuedHuyaCutoverJob = null
        queuedHuyaTransitionWatchdogJob?.cancel()
        queuedHuyaTransitionWatchdogJob = null
        val mediaId = queuedHuyaMediaId
        val activePlayer = player
        val isAlreadyActive = mediaId != null && activePlayer?.currentMediaItem?.mediaId == mediaId
        if (isAlreadyActive) queuedHuyaReconnectGate?.enable()
        else queuedHuyaReconnectGate?.cancel()
        if (!isAlreadyActive && mediaId != null && activePlayer != null) {
            val index = (0 until activePlayer.mediaItemCount)
                .firstOrNull { activePlayer.getMediaItemAt(it).mediaId == mediaId }
            if (index != null && index > activePlayer.currentMediaItemIndex) {
                runCatching { activePlayer.removeMediaItem(index) }
            }
            queuedHuyaDataSource?.let(::closePreconnectedHuyaDataSourceAsync)
        }
        queuedHuyaMediaId = null
        queuedHuyaPlaybackInfo = null
        queuedHuyaAttempt = -1
        queuedHuyaDataSource = null
        queuedHuyaReconnectGate = null
        queuedHuyaPreparedAtMs = 0L
        queuedHuyaConnectionOpenedAtMs = 0L
    }

    private fun refreshHuyaSingleStream(
        channel: Channel?,
        reason: String,
        recoveryDelayMs: Long = LEGACY_HUYA_RECOVERY_DELAY_MS,
        plannedRenewal: Boolean = false,
        showHint: Boolean = true,
        confirmedSourceFailure: Boolean = false
    ): Boolean {
        val activeChannel = channel ?: return false
        val playbackInfo = currentPlaybackInfo ?: return false
        if (!playbackInfo.resolvedBy.startsWith("huya:") || isHuyaHotSwapEnabled() || refreshInProgress) {
            return false
        }
        val now = System.currentTimeMillis()
        var effectiveRecoveryDelayMs = recoveryDelayMs
        val recoveryAttemptLimit = maxHuyaCdnRecoveryAttempts()
        var recoveryAttemptRecorded = false

        // A 403 after an already-playing FLV is a rejected reconnect/open, not
        // evidence that the whole channel is dead. Count it before considering
        // a queued route so repeated rejected candidates cannot evade pacing.
        if (!plannedRenewal && confirmedSourceFailure && isLegacyMstarHuyaStream()) {
            if (!isHuyaRecoveryAttemptAllowed(huyaCdnRecoveryAttempt, recoveryAttemptLimit)) {
                Log.w(TAG, "Huya CDN recovery limit reached for channel=${activeChannel.name}")
                return false
            }
            huyaCdnRecoveryAttempt = nextHuyaRecoveryAttempt(huyaCdnRecoveryAttempt, recoveryAttemptLimit)
            lastHuyaRecoveryAtMs = now
            effectiveRecoveryDelayMs = legacyHuyaHttpFailureBackoffMs(huyaCdnRecoveryAttempt)
            recoveryAttemptRecorded = true
        }
        if (isLegacyMstarHuyaStream() && queuedHuyaMediaId != null) {
            if (cutOverQueuedHuyaStream(activeChannel, reason)) {
                showStatusHint(reason, STATUS_HINT_DEFAULT_MS)
                return true
            }
            discardQueuedHuyaRenewal()
        }
        if (!plannedRenewal && !confirmedSourceFailure && isLegacyMstarHuyaStream() &&
            lastHuyaRecoveryAtMs > 0L &&
            now - lastHuyaRecoveryAtMs < LEGACY_HUYA_RECOVERY_COOLDOWN_MS
        ) {
            Log.w(
                TAG,
                "Ignoring Huya recovery request during MStar cooldown for channel=${activeChannel.name}"
            )
            return false
        }
        if (!plannedRenewal && !isHuyaRecoveryAttemptAllowed(huyaCdnRecoveryAttempt, recoveryAttemptLimit)) {
            Log.w(TAG, "Huya CDN recovery limit reached for channel=${activeChannel.name}")
            return false
        }
        if (!plannedRenewal && !recoveryAttemptRecorded) {
            huyaCdnRecoveryAttempt = nextHuyaRecoveryAttempt(huyaCdnRecoveryAttempt, recoveryAttemptLimit)
            lastHuyaRecoveryAtMs = now
        } else {
            // A healthy scheduled handoff is not a failed-recovery attempt and
            // must not block an immediate retry if the new route itself fails.
            if (plannedRenewal) lastHuyaRecoveryAtMs = 0L
        }
        pendingHuyaRenewalFirstFrameAtMs = now
        pendingHuyaRenewalLastFrameAtMs = lastRenderedVideoFrameRealtimeMs
        pendingHuyaRenewalFirstFrameChannelId = activeChannel.id
        pendingHuyaRenewalStartedAtElapsedMs = SystemClock.elapsedRealtime()
        pendingHuyaRenewalResolvedAtElapsedMs = 0L
        pendingHuyaRenewalSourceAppliedAtElapsedMs = 0L
        pendingHuyaRenewalCandidateWasPrewarmed = false
        pendingHuyaRenewalUsedPreconnectedDataSource = false
        pendingHuyaRenewalFirstFrameSeen = false
        pendingHuyaRenewalAudioAdvanced = false
        refreshInProgress = true
        if (isLegacyMstarHuyaStream() && player?.playbackState == Player.STATE_ENDED) {
            scheduleEndedRecoveryWatchdog(activeChannel)
        }
        val recoveryHint = if (confirmedSourceFailure && isLegacyMstarHuyaStream() &&
            effectiveRecoveryDelayMs > LEGACY_HUYA_HTTP_RETRY_DELAY_MS
        ) {
            val delaySeconds = (effectiveRecoveryDelayMs + 999L) / 1_000L
            "$reason（第${huyaCdnRecoveryAttempt}次同频道重试，${delaySeconds}秒后继续）"
        } else {
            reason
        }
        if (showHint) {
            showStatusHint(
                recoveryHint,
                maxOf(STATUS_HINT_LONG_MS, effectiveRecoveryDelayMs + 1_000L)
            )
        }
        DebugReporter.reportPlaybackIssue(
            phase = "huya_cdn_recovery",
            channel = activeChannel,
            message = "$reason；当前=${playbackInfo.resolvedBy}；失败恢复次数=$huyaCdnRecoveryAttempt，线路轮换=$huyaCdnRotationIndex",
            playbackInfo = playbackInfo,
            decoderMode = currentDecoderMode
        )
        DebugReporter.recordLivePlaybackEvent(
            "huya_renewal_started",
            activeChannel,
            mapOf(
                "reason" to reason,
                "currentResolvedBy" to playbackInfo.resolvedBy,
                "prewarmedCandidateAvailable" to (prewarmedHuyaPlaybackInfo != null),
                "preconnectedCandidateAvailable" to (preconnectedHuyaDataSource != null),
                "recoveryDelayMs" to effectiveRecoveryDelayMs,
                "sourceAgeMs" to (now - activeMediaSourceStartedAtMs).coerceAtLeast(0L),
                "attempt" to huyaCdnRotationIndex,
                "recoveryAttempt" to huyaCdnRecoveryAttempt,
                "recoveryAttemptLimit" to recoveryAttemptLimit
            )
        )
        val cachedInfo = prewarmedHuyaPlaybackInfo?.takeIf {
            prewarmedHuyaChannelId == activeChannel.id &&
                huyaPrewarmSourceChannelId == activeChannel.id &&
                huyaPrewarmSourceUrl == playbackInfo.url &&
                prewarmedHuyaResolvedAtMs > 0L &&
                now - prewarmedHuyaResolvedAtMs <= LEGACY_HUYA_PREWARM_MAX_AGE_MS
        }?.takeUnless {
            (huyaForceFlvForRecovery &&
                it.mimeType == com.google.android.exoplayer2.util.MimeTypes.APPLICATION_M3U8) ||
                (huyaForceHlsForRecovery && it.mimeType == "video/x-flv")
        }
        val cachedAttemptIsNewer = cachedInfo != null && prewarmedHuyaAttempt > huyaCdnRotationIndex
        val cachedPreconnectedDataSource = if (cachedAttemptIsNewer) {
            cachedInfo?.let { takePreconnectedHuyaDataSource(it.url) }
        } else {
            null
        }
        val cachedRequiresPreconnectedFlv = isLegacyMstarHuyaStream() &&
            cachedInfo?.mimeType == "video/x-flv"
        val cachedCandidateIsUsable = cachedAttemptIsNewer &&
            (!cachedRequiresPreconnectedFlv || cachedPreconnectedDataSource != null)
        if (cachedCandidateIsUsable && cachedInfo != null) {
            pendingHuyaRenewalResolvedAtElapsedMs = (
                pendingHuyaRenewalStartedAtElapsedMs -
                    (now - prewarmedHuyaResolvedAtMs).coerceAtLeast(0L)
                ).coerceAtLeast(0L)
            pendingHuyaRenewalCandidateWasPrewarmed = true
            val preconnectedDataSource = cachedPreconnectedDataSource
            DebugReporter.recordLivePlaybackEvent(
                "huya_renewal_candidate_selected",
                activeChannel,
                mapOf(
                    "resolvedBy" to cachedInfo.resolvedBy,
                    "candidateAgeMs" to (now - prewarmedHuyaResolvedAtMs).coerceAtLeast(0L),
                    "usedPreconnectedDataSource" to (preconnectedDataSource != null),
                    "wasPrewarmed" to true
                )
            )
            huyaCdnRotationIndex = prewarmedHuyaAttempt
            prewarmedHuyaPlaybackInfo = null
            prewarmedHuyaAttempt = -1
            prewarmedHuyaChannelId = -1L
            prewarmedHuyaResolvedAtMs = 0L
            Log.w(
                TAG,
                "$reason, channel=${activeChannel.name}; switching immediately to prewarmed ${cachedInfo.resolvedBy}"
            )
            playResolvedStream(
                channel = activeChannel,
                playbackInfo = cachedInfo,
                seamlessRetry = isLegacyMstarHuyaStream(),
                forcePlayerRecreate = false,
                preconnectedDataSource = preconnectedDataSource
            )
        } else {
            if (cachedAttemptIsNewer && cachedRequiresPreconnectedFlv &&
                cachedPreconnectedDataSource == null
            ) {
                DebugReporter.recordLivePlaybackEvent(
                    "huya_renewal_cached_candidate_skipped",
                    activeChannel,
                    mapOf(
                        "reason" to "legacy_flv_candidate_not_preconnected_and_validated",
                        "resolvedBy" to cachedInfo?.resolvedBy,
                        "attempt" to prewarmedHuyaAttempt
                    )
                )
                prewarmedHuyaPlaybackInfo = null
                prewarmedHuyaAttempt = -1
                prewarmedHuyaChannelId = -1L
                prewarmedHuyaResolvedAtMs = 0L
            }
            clearPreconnectedHuyaDataSource()
            huyaCdnRotationIndex = if (huyaCdnRotationIndex == Int.MAX_VALUE) {
                0
            } else {
                huyaCdnRotationIndex + 1
            }
            val rotationAttempt = huyaCdnRotationIndex
            Log.w(
                TAG,
                "$reason, channel=${activeChannel.name}; resolving Huya CDN rotation=$rotationAttempt " +
                    "recovery=$huyaCdnRecoveryAttempt/${recoveryAttemptLimit ?: "unbounded"}"
            )
            val recover = {
                if (currentChannel?.id == activeChannel.id && !isFinishing && !isDestroyed) {
                    playChannel(
                        channel = activeChannel,
                        isRetry = true,
                        forcePlayerRecreate = false,
                        isProactiveRefresh = true,
                        seamlessRetry = isLegacyMstarHuyaStream()
                    )
                } else {
                    refreshInProgress = false
                }
            }
            if (isLegacyMstarHuyaStream()) {
                huyaRecoveryJob?.cancel()
                huyaRecoveryJob = lifecycleScope.launch {
                    val deferResolveUntilBackoff = confirmedSourceFailure &&
                        effectiveRecoveryDelayMs > LEGACY_HUYA_HTTP_RETRY_DELAY_MS
                    var resolveStartedAtMs = System.currentTimeMillis()
                    val candidateJob = if (deferResolveUntilBackoff) null else async {
                        PlayerHelper.resolvePlaybackInfo(
                            activeChannel,
                            attemptIndex = rotationAttempt,
                            huyaSchemeOverride = currentHuyaRecoverySchemeOverride(),
                            validateHuyaRedirectHost = huyaRedirectHostValidationRequired
                        )
                    }
                    try {
                        // APTV's visible retry state distinguishes open-phase
                        // failures and tracks their count. For repeated MStar
                        // 403s, wait before starting another resolver request;
                        // launching it during the backoff would still hammer
                        // the room/signature endpoints even if playback waits.
                        kotlinx.coroutines.delay(effectiveRecoveryDelayMs)
                        val resolvedCandidate = candidateJob?.await() ?: run {
                            resolveStartedAtMs = System.currentTimeMillis()
                            PlayerHelper.resolvePlaybackInfo(
                                activeChannel,
                                attemptIndex = rotationAttempt,
                                huyaSchemeOverride = currentHuyaRecoverySchemeOverride(),
                                validateHuyaRedirectHost = huyaRedirectHostValidationRequired
                            )
                        }
                        var renewedInfo = resolvedCandidate
                        var renewedAttempt = rotationAttempt
                        var renewedDataSource: PreconnectedHttpDataSource? = null
                        if (isLegacyMstarHuyaStream() && renewedInfo.mimeType == "video/x-flv") {
                            val validatedCandidate = try {
                                withTimeoutOrNull(LEGACY_HUYA_CANDIDATE_VALIDATION_TIMEOUT_MS) {
                                    prepareHuyaCandidateConnection(
                                        activeChannel,
                                        renewedInfo,
                                        rotationAttempt
                                    )
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (validationError: Exception) {
                                Log.w(TAG, "Huya renewal candidate validation failed", validationError)
                                null
                            }
                            if (validatedCandidate == null || validatedCandidate.third == null) {
                                val lastAttempt = validatedCandidate?.second ?: rotationAttempt
                                huyaRecoveryJob = null
                                refreshInProgress = false
                                huyaCdnRotationIndex = maxOf(huyaCdnRotationIndex, lastAttempt)
                                retryHuyaRenewalWithNextRoute(
                                    activeChannel,
                                    "新虎牙线路未能验证到音视频数据，继续尝试同一频道",
                                    lastAttempt
                                )
                                return@launch
                            }
                            renewedInfo = validatedCandidate.first
                            renewedAttempt = validatedCandidate.second
                            renewedDataSource = validatedCandidate.third
                            huyaCdnRotationIndex = maxOf(huyaCdnRotationIndex, renewedAttempt)
                        }
                        if (currentChannel?.id == activeChannel.id && !isFinishing && !isDestroyed) {
                            pendingHuyaRenewalResolvedAtElapsedMs = SystemClock.elapsedRealtime()
                            DebugReporter.recordLivePlaybackEvent(
                                "huya_renewal_resolved",
                                activeChannel,
                                mapOf(
                                    "resolvedBy" to renewedInfo.resolvedBy,
                                    "mimeType" to renewedInfo.mimeType,
                                    "attempt" to renewedAttempt,
                                    "validatedAudioPayload" to (renewedDataSource?.containsAudioPayload == true),
                                    "validatedAudioPayloadFormat" to renewedDataSource?.audioPayloadFormat,
                                    "resolveElapsedMs" to (System.currentTimeMillis() - resolveStartedAtMs),
                                    "wasPrewarmed" to false
                                )
                            )
                            huyaRecoveryJob = null
                            if (renewedDataSource != null) {
                                playResolvedStream(
                                    channel = activeChannel,
                                    playbackInfo = renewedInfo,
                                    seamlessRetry = true,
                                    forcePlayerRecreate = false,
                                    preconnectedDataSource = renewedDataSource
                                )
                            } else {
                                playChannel(
                                    channel = activeChannel,
                                    isRetry = true,
                                    forcePlayerRecreate = false,
                                    isProactiveRefresh = true,
                                    seamlessRetry = true,
                                    resolvedPlaybackInfo = renewedInfo
                                )
                            }
                        } else {
                            refreshInProgress = false
                            huyaRecoveryJob = null
                        }
                    } catch (cancelled: CancellationException) {
                        candidateJob?.cancel()
                        if (huyaRecoveryJob?.isActive != true &&
                            currentChannel?.id == activeChannel.id &&
                            currentPlaybackInfo?.url == playbackInfo.url &&
                            !isFinishing && !isDestroyed
                        ) {
                            // A cancelled resolver must not leave the recovery
                            // guard latched. Otherwise every later watchdog
                            // attempt is rejected as "already in progress".
                            refreshInProgress = false
                            DebugReporter.recordLivePlaybackEvent(
                                "huya_renewal_cancelled",
                                activeChannel,
                                mapOf(
                                    "reason" to "resolver_cancelled_before_source_handoff",
                                    "recoveryAction" to "release_refresh_guard"
                                )
                            )
                        }
                        throw cancelled
                    } catch (error: Exception) {
                        huyaRecoveryJob = null
                        refreshInProgress = false
                        handlePlaybackPreparationFailure(error)
                    }
                }
            } else {
                recover()
            }
        }
        return true
    }

    private fun maxHuyaCdnRecoveryAttempts(): Int? =
        if (isLegacyMstarHuyaStream()) null else MAX_HUYA_CDN_RECOVERY_ATTEMPTS

    /**
     * Resolves the next CDN without creating a second decoder. This keeps the
     * MStar Android 6 profile safe while making a 10-second HLS segment error
     * recoverable in one source replacement instead of a fresh network round
     * trip.
     */
    private fun scheduleHuyaRecoveryPrewarm(
        channel: Channel?,
        playbackInfo: PlaybackStreamInfo?
    ) {
        if (channel == null || playbackInfo == null) {
            huyaPrewarmJob?.cancel()
            huyaPrewarmJob = null
            huyaRenewalFallbackJob?.cancel()
            huyaRenewalFallbackJob = null
            clearPreconnectedHuyaDataSource()
            prewarmedHuyaPlaybackInfo = null
            prewarmedHuyaAttempt = -1
            prewarmedHuyaChannelId = -1L
            prewarmedHuyaResolvedAtMs = 0L
            huyaPrewarmSourceChannelId = -1L
            huyaPrewarmSourceUrl = ""
            return
        }
        if (usesContinuousLegacyHuyaTransport(playbackInfo)) {
            // This DataSource owns EOF/re-signing while retaining its packet
            // history and extractor. An age-based queued cutover starts a new
            // history, repeats the CDN's overlapping prefix and flushes codecs.
            huyaPrewarmJob?.cancel()
            huyaPrewarmJob = null
            huyaRenewalFallbackJob?.cancel()
            huyaRenewalFallbackJob = null
            clearPreconnectedHuyaDataSource()
            DebugReporter.recordLivePlaybackEvent(
                "huya_renewal_prewarm_skipped",
                channel,
                mapOf("reason" to "continuous_transport_owns_eof_and_signature_recovery")
            )
            return
        }
        val sameSource = huyaPrewarmSourceChannelId == channel.id &&
            huyaPrewarmSourceUrl == playbackInfo.url
        val preparedCandidateIsFresh = prewarmedHuyaPlaybackInfo != null &&
            prewarmedHuyaChannelId == channel.id &&
            prewarmedHuyaResolvedAtMs > 0L &&
            System.currentTimeMillis() - prewarmedHuyaResolvedAtMs <= LEGACY_HUYA_PREWARM_MAX_AGE_MS
        if (sameSource && (preparedCandidateIsFresh || huyaPrewarmJob?.isActive == true)) {
            DebugReporter.recordLivePlaybackEvent(
                "huya_renewal_prewarm_retained",
                channel,
                mapOf(
                    "jobActive" to (huyaPrewarmJob?.isActive == true),
                    "candidateFresh" to preparedCandidateIsFresh,
                    "resolvedBy" to playbackInfo.resolvedBy
                )
            )
            return
        }
        huyaPrewarmJob?.cancel()
        huyaPrewarmJob = null
        huyaRenewalFallbackJob?.cancel()
        huyaRenewalFallbackJob = null
        clearPreconnectedHuyaDataSource()
        prewarmedHuyaPlaybackInfo = null
        prewarmedHuyaAttempt = -1
        prewarmedHuyaChannelId = -1L
        prewarmedHuyaResolvedAtMs = 0L
        huyaPrewarmSourceChannelId = channel.id
        huyaPrewarmSourceUrl = playbackInfo.url
        if (!isSinglePlayerHuyaStream()) {
            DebugReporter.recordLivePlaybackEvent(
                "huya_renewal_prewarm_skipped",
                channel,
                mapOf("reason" to "not_single_player_huya", "resolvedBy" to playbackInfo.resolvedBy)
            )
            return
        }

        val legacyFlvRenewal = isLegacyMstarHuyaStream() &&
            playbackInfo.mimeType == "video/x-flv"
        if (legacyFlvRenewal) {
            scheduleHuyaRenewalFallback(channel, playbackInfo)
        }
        // Do not parse/sign Huya responses beside active playback on MStar as
        // a general recovery optimization. A single delayed renewal lookup is
        // worthwhile for its approximately two-minute FLV boundary because
        // it removes the 1.5s recovery delay plus the resolver's network RTT.
        if (isLowEndTvDevice && !legacyFlvRenewal) {
            DebugReporter.recordLivePlaybackEvent(
                "huya_renewal_prewarm_skipped",
                channel,
                mapOf("reason" to "legacy_requires_flv", "mimeType" to playbackInfo.mimeType)
            )
            return
        }

        val recoveryAttempt = if (huyaCdnRotationIndex == Int.MAX_VALUE) {
            0
        } else {
            huyaCdnRotationIndex + 1
        }
        val prewarmDelayMs = if (legacyFlvRenewal) {
            remainingSourceAgeDelayMs(
                sourceStartedAtMs = activeMediaSourceStartedAtMs,
                nowMs = System.currentTimeMillis(),
                targetSourceAgeMs = LEGACY_HUYA_RENEWAL_PREWARM_SOURCE_AGE_MS
            )
        } else {
            HUYA_PREWARM_DELAY_MS
        }
        DebugReporter.recordLivePlaybackEvent(
            "huya_renewal_prewarm_scheduled",
            channel,
            mapOf(
                "delayMs" to prewarmDelayMs,
                "attempt" to recoveryAttempt,
                "trigger" to "source_age",
                "resolvedBy" to playbackInfo.resolvedBy
            )
        )
        huyaPrewarmJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(prewarmDelayMs)
            if (currentChannel?.id != channel.id || refreshInProgress || isFinishing || isDestroyed) {
                DebugReporter.recordLivePlaybackEvent(
                    "huya_renewal_prewarm_skipped",
                    channel,
                    mapOf("reason" to "channel_changed_or_refresh_in_progress")
                )
                return@launch
            }
            runCatching {
                PlayerHelper.resolvePlaybackInfo(
                    channel,
                    attemptIndex = recoveryAttempt,
                    huyaSchemeOverride = currentHuyaRecoverySchemeOverride(),
                    validateHuyaRedirectHost = true
                )
            }.onSuccess { info ->
                if (currentChannel?.id == channel.id &&
                    currentPlaybackInfo?.url == playbackInfo.url &&
                    currentPlaybackInfo?.resolvedBy?.startsWith("huya:") == true &&
                    (!legacyFlvRenewal || info.mimeType == "video/x-flv") &&
                    !refreshInProgress
                ) {
                    prewarmedHuyaPlaybackInfo = info
                    prewarmedHuyaAttempt = recoveryAttempt
                    prewarmedHuyaChannelId = channel.id
                    prewarmedHuyaResolvedAtMs = System.currentTimeMillis()
                    Log.d(
                        TAG,
                        "Prewarmed Huya ${if (legacyFlvRenewal) "renewal" else "recovery"} " +
                            "candidate ${info.resolvedBy} for ${channel.name}"
                    )
                    DebugReporter.recordLivePlaybackEvent(
                        if (legacyFlvRenewal) "huya_renewal_prepared" else "huya_recovery_prepared",
                        channel,
                        mapOf(
                            "resolvedBy" to info.resolvedBy,
                            "mimeType" to info.mimeType,
                            "attempt" to recoveryAttempt
                        )
                    )
                    if (legacyFlvRenewal) {
                        scheduleHuyaConnectionPrewarm(
                            channel,
                            info
                        )
                    }
                }
            }.onFailure { error ->
                // A prewarm is an optimization only. The playing stream is
                // left untouched and normal on-error recovery remains active.
                Log.w(TAG, "Could not prewarm Huya recovery candidate for ${channel.name}", error)
                DebugReporter.recordLivePlaybackEvent(
                    "huya_renewal_prewarm_failed",
                    channel,
                    mapOf("errorType" to error.javaClass.simpleName)
                )
            }
        }
    }

    /**
     * Open the already-resolved next FLV connection shortly before the old
     * stream expires. This does not create a second decoder; it only warms the
     * HTTP response and transfers its one-shot DataSource to ExoPlayer at the
     * renewal boundary.
     */
    private fun scheduleHuyaConnectionPrewarm(
        channel: Channel,
        playbackInfo: PlaybackStreamInfo
    ) {
        huyaConnectionPrewarmJob?.cancel()
        clearPreconnectedHuyaDataSource(cancelJob = false)
        if (!isLegacyMstarHuyaStream() || playbackInfo.mimeType != "video/x-flv") return

        val activeSourceUrl = currentPlaybackInfo?.url ?: return
        val initialCandidateAttempt = prewarmedHuyaAttempt
        val delayMs = remainingSourceAgeDelayMs(
            sourceStartedAtMs = activeMediaSourceStartedAtMs,
            nowMs = System.currentTimeMillis(),
            targetSourceAgeMs = LEGACY_HUYA_CONNECTION_PRECONNECT_SOURCE_AGE_MS
        )
        DebugReporter.recordLivePlaybackEvent(
            "huya_renewal_connection_preconnect_scheduled",
            channel,
            mapOf(
                "delayMs" to delayMs,
                "trigger" to "source_age",
                "resolvedBy" to playbackInfo.resolvedBy
            )
        )
        huyaConnectionPrewarmJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (currentChannel?.id != channel.id ||
                currentPlaybackInfo?.url != activeSourceUrl ||
                prewarmedHuyaPlaybackInfo?.url != playbackInfo.url ||
                refreshInProgress || isFinishing || isDestroyed
            ) {
                return@launch
            }

            val preconnectStartedAtElapsedMs = SystemClock.elapsedRealtime()
            runCatching {
                prepareHuyaCandidateConnection(channel, playbackInfo, initialCandidateAttempt)
            }.onSuccess { prepared ->
                    val (connectionInfo, connectionAttempt, connected) = prepared
                    if (connected != null &&
                        currentChannel?.id == channel.id &&
                        currentPlaybackInfo?.url == activeSourceUrl &&
                        currentPlaybackInfo?.resolvedBy?.startsWith("huya:") == true &&
                        !refreshInProgress
                    ) {
                        prewarmedHuyaPlaybackInfo = connectionInfo
                        prewarmedHuyaAttempt = connectionAttempt
                        prewarmedHuyaChannelId = channel.id
                        prewarmedHuyaResolvedAtMs = System.currentTimeMillis()
                        preconnectedHuyaDataSource = connected
                        preconnectedHuyaDataSourceUrl = connectionInfo.url
                        preconnectedHuyaDataSourceAtMs = connected.openedAtElapsedRealtimeMs
                        DebugReporter.recordLivePlaybackEvent(
                            "huya_renewal_connection_preconnected",
                            channel,
                            mapOf(
                                "resolvedBy" to connectionInfo.resolvedBy,
                                "attempt" to connectionAttempt,
                                "flvAudioFlag" to connected.hasAudioTrackInFlvHeader,
                                "audioPayloadFound" to connected.containsAudioPayload,
                                "audioPayloadFormat" to connected.audioPayloadFormat,
                                "prebufferBytes" to connected.bufferedBytes,
                                "prebufferKeyframe" to connected.containsVideoKeyframe,
                                "connectionAgeMs" to
                                    (SystemClock.elapsedRealtime() - connected.openedAtElapsedRealtimeMs).coerceAtLeast(0L),
                                "preconnectElapsedMs" to
                                    (SystemClock.elapsedRealtime() - preconnectStartedAtElapsedMs).coerceAtLeast(0L)
                            )
                        )
                        val queuedDataSource = takePreconnectedHuyaDataSource(connectionInfo.url)
                        val queued = queuedDataSource?.let {
                            queueLegacyHuyaRenewal(channel, connectionInfo, connectionAttempt, it)
                        } == true
                        if (queued) {
                            prewarmedHuyaPlaybackInfo = null
                            prewarmedHuyaAttempt = -1
                            prewarmedHuyaChannelId = -1L
                            prewarmedHuyaResolvedAtMs = 0L
                            DebugReporter.recordLivePlaybackEvent(
                                "huya_proactive_candidate_queued",
                                channel,
                                mapOf(
                                    "sourceAgeMs" to (System.currentTimeMillis() - activeMediaSourceStartedAtMs),
                                    "cutoverTargetSourceAgeMs" to LEGACY_HUYA_QUEUED_CUTOVER_SOURCE_AGE_MS,
                                    "decoderCount" to 1
                                )
                            )
                        } else {
                            if (queuedDataSource != null) {
                                preconnectedHuyaDataSource = queuedDataSource
                                preconnectedHuyaDataSourceUrl = connectionInfo.url
                                preconnectedHuyaDataSourceAtMs = queuedDataSource.openedAtElapsedRealtimeMs
                            }
                            val cutoverStarted = refreshHuyaSingleStream(
                                channel,
                                "虎牙线路已预热，正在续签",
                                recoveryDelayMs = 0L,
                                plannedRenewal = true
                            )
                            if (!cutoverStarted) {
                                DebugReporter.recordLivePlaybackEvent(
                                    "huya_proactive_cutover_skipped",
                                    channel,
                                    mapOf("reason" to "recovery_guard_or_attempt_limit")
                                )
                            }
                        }
                    } else {
                                closePreconnectedHuyaDataSourceAsync(connected)
                        if (connected == null && currentChannel?.id == channel.id &&
                            currentPlaybackInfo?.url == activeSourceUrl && !refreshInProgress
                        ) {
                            retryHuyaRenewalWithNextRoute(
                                channel = channel,
                                reason = "虎牙预连接未建立，改用下一条新线路续签",
                                lastCandidateAttempt = connectionAttempt
                            )
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_renewal_connection_preconnect_failed",
                        channel,
                        mapOf(
                            "errorType" to error.javaClass.simpleName,
                            "preconnectElapsedMs" to
                                (SystemClock.elapsedRealtime() - preconnectStartedAtElapsedMs).coerceAtLeast(0L)
                        )
                    )
                    Log.w(TAG, "Could not preconnect the next Huya stream for ${channel.name}", error)
                    retryHuyaRenewalWithNextRoute(
                        channel = channel,
                        reason = "虎牙预连接失败，改用下一条新线路续签",
                        lastCandidateAttempt = prewarmedHuyaAttempt
                    )
                }
        }
    }

    /**
     * A chunked FLV response can be syntactically successful yet contain only
     * a few seconds of media. Once ExoPlayer reports that the current HTTP
     * response has fully completed, reconnect and queue a validated response
     * while decoded tail-buffer remains available. This avoids concurrent
     * requests for the same URL and gives the player a chance to bridge EOF.
     */
    private fun maybeScheduleShortHuyaContinuation() {
        val channel = currentChannel ?: return
        val playbackInfo = currentPlaybackInfo ?: return
        if (usesContinuousLegacyHuyaTransport(playbackInfo)) return
        val activePlayer = player ?: return
        val shortDurationMs = activeHuyaLoadedDurationMs.takeIf { it > 0L && it != C.TIME_UNSET }
            ?: activePlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET }
                ?.also { activeHuyaLoadedDurationMs = it }
            ?: C.TIME_UNSET
        if (!isLegacyMstarHuyaStream() ||
            playbackInfo.mimeType != "video/x-flv" ||
            shortDurationMs !in 1..LEGACY_HUYA_SHORT_STREAM_MAX_DURATION_MS ||
            !activeHuyaHttpResponseComplete ||
            activePlayer.playbackState == Player.STATE_IDLE ||
            activePlayer.playbackState == Player.STATE_ENDED ||
            !activePlayer.playWhenReady ||
            refreshInProgress ||
            queuedHuyaMediaId != null ||
            huyaShortStreamPrewarmSourceUrl == playbackInfo.url ||
            huyaShortStreamPrewarmJob?.isActive == true ||
            isFinishing || isDestroyed
        ) return

        val activeSourceUrl = playbackInfo.url
        huyaShortStreamPrewarmSourceUrl = activeSourceUrl

        // Replace the long-lifetime-only prewarm for this source. A short
        // completed response is direct evidence that the usual 85-second
        // renewal window is too late for this CDN route.
        huyaPrewarmJob?.cancel()
        huyaPrewarmJob = null
        huyaRenewalFallbackJob?.cancel()
        huyaRenewalFallbackJob = null
        huyaConnectionPrewarmJob?.cancel()
        huyaConnectionPrewarmJob = null
        clearPreconnectedHuyaDataSource()
        prewarmedHuyaPlaybackInfo = null
        prewarmedHuyaAttempt = -1
        prewarmedHuyaChannelId = -1L
        prewarmedHuyaResolvedAtMs = 0L

        huyaShortStreamPrewarmJob = lifecycleScope.launch {
            try {
                val nextAttempt = if (huyaCdnRotationIndex == Int.MAX_VALUE) {
                    0
                } else {
                    huyaCdnRotationIndex + 1
                }
                DebugReporter.recordLivePlaybackEvent(
                    "huya_short_stream_continuation_started",
                    channel,
                    mapOf(
                        "mediaDurationMs" to shortDurationMs,
                        "currentResolvedBy" to playbackInfo.resolvedBy,
                        "attempt" to nextAttempt,
                        "sourceHttpResponseComplete" to activeHuyaHttpResponseComplete,
                        "parallelSameUrlRequest" to false,
                        "sameUrlReconnectBlocked" to (huyaSameUrlReconnectBlockedUrl == activeSourceUrl),
                        "playbackPositionMs" to activePlayer.currentPosition
                    )
                )

                if (shouldTryHuyaSameUrlReconnect(
                        isLegacyTv = isLegacyMstarHuyaStream(),
                        isFlv = playbackInfo.mimeType == "video/x-flv",
                        blockedForCurrentUrl = huyaSameUrlReconnectBlockedUrl == activeSourceUrl
                    )
                ) {
                    val sameUrlDataSource = try {
                        withTimeoutOrNull(LEGACY_HUYA_CANDIDATE_VALIDATION_TIMEOUT_MS) {
                            PlayerHelper.preconnectHttpStream(playbackInfo)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Same-URL Huya EOF reconnect could not be validated", error)
                        null
                    }
                    val sameUrlConnection = sameUrlDataSource
                    val sameUrlValidated = sameUrlConnection != null &&
                        shouldQueueLegacyHuyaRenewal(
                            isLegacyTv = true,
                            isFlv = true,
                            audioFlag = sameUrlConnection.hasAudioTrackInFlvHeader,
                            hasAudioPayload = sameUrlConnection.containsAudioPayload,
                            hasVideoKeyframe = sameUrlConnection.containsVideoKeyframe
                        )
                    val stillSameSourceForReconnect = currentChannel?.id == channel.id &&
                        currentPlaybackInfo?.url == activeSourceUrl &&
                        player === activePlayer &&
                        (activePlayer.playbackState == Player.STATE_READY ||
                            activePlayer.playbackState == Player.STATE_BUFFERING) &&
                        activePlayer.playWhenReady &&
                        !refreshInProgress && queuedHuyaMediaId == null &&
                        !isFinishing && !isDestroyed
                    val sameUrlQueued = sameUrlConnection != null && sameUrlValidated && stillSameSourceForReconnect &&
                        queueLegacyHuyaRenewal(
                            channel,
                            playbackInfo,
                            huyaCdnRotationIndex,
                            sameUrlConnection
                        )
                    if (sameUrlQueued) {
                        val queuedConnection = sameUrlConnection ?: return@launch
                        DebugReporter.recordLivePlaybackEvent(
                            "huya_same_url_reconnect_queued",
                            channel,
                            mapOf(
                                "resolvedBy" to playbackInfo.resolvedBy,
                                "attempt" to huyaCdnRotationIndex,
                                "mediaDurationMs" to shortDurationMs,
                                "prebufferBytes" to queuedConnection.bufferedBytes,
                                "audioPayloadFound" to queuedConnection.containsAudioPayload,
                                "videoKeyframeFound" to queuedConnection.containsVideoKeyframe
                            )
                        )
                        DebugReporter.recordLivePlaybackEvent(
                            "huya_short_stream_continuation_queued",
                            channel,
                            mapOf(
                                "continuationMode" to "same_url_eof_reconnect",
                                "mediaDurationMs" to shortDurationMs,
                                "resolvedBy" to playbackInfo.resolvedBy,
                                "attempt" to huyaCdnRotationIndex,
                                "prebufferBytes" to queuedConnection.bufferedBytes
                            )
                        )
                        return@launch
                    }

                    sameUrlDataSource?.let(::closePreconnectedHuyaDataSourceAsync)
                    if (stillSameSourceForReconnect) {
                        huyaSameUrlReconnectBlockedUrl = activeSourceUrl
                    }
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_same_url_reconnect_skipped",
                        channel,
                        mapOf(
                            "reason" to when {
                                !stillSameSourceForReconnect -> "active_source_changed"
                                sameUrlConnection == null -> "reconnect_unavailable_or_timeout"
                                sameUrlConnection.hasAudioTrackInFlvHeader == false -> "flv_header_without_audio"
                                !sameUrlConnection.containsAudioPayload -> "no_audio_payload_in_prebuffer"
                                !sameUrlConnection.containsVideoKeyframe -> "no_video_keyframe_in_prebuffer"
                                else -> "queue_rejected"
                            },
                            "attempt" to huyaCdnRotationIndex,
                            "prebufferBytes" to sameUrlConnection?.bufferedBytes,
                            "audioPayloadFound" to sameUrlConnection?.containsAudioPayload,
                            "videoKeyframeFound" to sameUrlConnection?.containsVideoKeyframe
                        )
                    )
                    if (!stillSameSourceForReconnect) return@launch
                }

                val candidateInfo = PlayerHelper.resolvePlaybackInfo(
                    channel,
                    attemptIndex = nextAttempt,
                    huyaSchemeOverride = currentHuyaRecoverySchemeOverride(),
                    validateHuyaRedirectHost = huyaRedirectHostValidationRequired
                )
                if (candidateInfo.mimeType != "video/x-flv") {
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_short_stream_continuation_skipped",
                        channel,
                        mapOf(
                            "reason" to "candidate_not_flv",
                            "mimeType" to candidateInfo.mimeType,
                            "resolvedBy" to candidateInfo.resolvedBy
                        )
                    )
                    return@launch
                }

                val prepared = withTimeoutOrNull(LEGACY_HUYA_CANDIDATE_VALIDATION_TIMEOUT_MS) {
                    prepareHuyaCandidateConnection(channel, candidateInfo, nextAttempt)
                }
                val (validatedInfo, validatedAttempt, dataSource) = prepared
                    ?: run {
                        DebugReporter.recordLivePlaybackEvent(
                            "huya_short_stream_continuation_skipped",
                            channel,
                            mapOf("reason" to "candidate_validation_timeout")
                        )
                        return@launch
                    }
                if (currentChannel?.id == channel.id && currentPlaybackInfo?.url == activeSourceUrl) {
                    huyaCdnRotationIndex = maxOf(huyaCdnRotationIndex, validatedAttempt)
                }
                if (dataSource == null) {
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_short_stream_continuation_skipped",
                        channel,
                        mapOf(
                            "reason" to "no_validated_preconnected_source",
                            "resolvedBy" to validatedInfo.resolvedBy,
                            "attempt" to validatedAttempt
                        )
                    )
                    return@launch
                }

                val stillSameActiveSource = currentChannel?.id == channel.id &&
                    currentPlaybackInfo?.url == activeSourceUrl &&
                    player === activePlayer &&
                    (activePlayer.playbackState == Player.STATE_READY ||
                        activePlayer.playbackState == Player.STATE_BUFFERING) &&
                    activePlayer.playWhenReady &&
                    !refreshInProgress && queuedHuyaMediaId == null &&
                    !isFinishing && !isDestroyed
                val queued = stillSameActiveSource && queueLegacyHuyaRenewal(
                    channel,
                    validatedInfo,
                    validatedAttempt,
                    dataSource
                )
                if (queued) {
                    prewarmedHuyaPlaybackInfo = null
                    prewarmedHuyaAttempt = -1
                    prewarmedHuyaChannelId = -1L
                    prewarmedHuyaResolvedAtMs = 0L
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_short_stream_continuation_queued",
                        channel,
                        mapOf(
                            "mediaDurationMs" to shortDurationMs,
                            "resolvedBy" to validatedInfo.resolvedBy,
                            "attempt" to validatedAttempt,
                            "prebufferBytes" to dataSource.bufferedBytes
                        )
                    )
                } else {
                    closePreconnectedHuyaDataSourceAsync(dataSource)
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_short_stream_continuation_skipped",
                        channel,
                        mapOf("reason" to if (stillSameActiveSource) "queue_rejected" else "active_source_changed")
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not prewarm a short Huya continuation for ${channel.name}", error)
                DebugReporter.recordLivePlaybackEvent(
                    "huya_short_stream_continuation_failed",
                    channel,
                    mapOf("errorType" to error.javaClass.simpleName)
                )
            } finally {
                if (huyaShortStreamPrewarmSourceUrl == activeSourceUrl) {
                    huyaShortStreamPrewarmJob = null
                }
            }
        }
    }

    private suspend fun prepareHuyaCandidateConnection(
        channel: Channel,
        initialInfo: PlaybackStreamInfo,
        initialAttempt: Int,
        maximumCandidates: Int = 4
    ): Triple<PlaybackStreamInfo, Int, PreconnectedHttpDataSource?> {
        var candidateInfo = initialInfo
        var candidateAttempt = initialAttempt
        val candidateLimit = maximumCandidates.coerceAtLeast(1)
        repeat(candidateLimit) { candidateIndex ->
            val connection = try {
                PlayerHelper.preconnectHttpStream(candidateInfo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val httpError = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                DebugReporter.recordLivePlaybackEvent(
                    "huya_candidate_open_failed",
                    channel,
                    mapOf(
                        "attempt" to candidateAttempt,
                        "resolvedBy" to candidateInfo.resolvedBy,
                        "errorType" to error.javaClass.simpleName,
                        "httpStatusCode" to httpError?.responseCode,
                        "candidateIndex" to candidateIndex
                    )
                )
                null
            }
            if (connection != null && (!avPrecheckEnabled || PlaybackProtectionPolicy.acceptsCandidate(
                    isFlv = candidateInfo.mimeType == "video/x-flv",
                    audioFlag = connection.hasAudioTrackInFlvHeader,
                    audioPayload = connection.containsAudioPayload,
                    keyframe = connection.containsVideoKeyframe
                ))
            ) {
                return Triple(candidateInfo, candidateAttempt, connection)
            }

            if (connection != null) {
                val rejectReason = when {
                    !connection.containsVideoKeyframe -> "no_video_keyframe_in_prebuffer"
                    connection.hasAudioTrackInFlvHeader == false -> "flv_header_without_audio"
                    else -> "no_decodable_audio_payload_in_prebuffer"
                }
                DebugReporter.recordLivePlaybackEvent(
                    "huya_renewal_silent_candidate_skipped",
                    channel,
                    mapOf(
                        "reason" to rejectReason,
                        "attempt" to candidateAttempt,
                        "resolvedBy" to candidateInfo.resolvedBy,
                        "flvAudioFlag" to connection.hasAudioTrackInFlvHeader,
                        "audioPayloadFound" to connection.containsAudioPayload,
                        "audioPayloadFormat" to connection.audioPayloadFormat,
                        "videoKeyframeFound" to connection.containsVideoKeyframe,
                        "prebufferBytes" to connection.bufferedBytes,
                        "candidateIndex" to candidateIndex
                    )
                )
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { connection.close() }
                }
            }

            if (candidateIndex < candidateLimit - 1) {
                val lastAttempt = maxOf(candidateAttempt, huyaCdnRotationIndex)
                candidateAttempt = if (lastAttempt == Int.MAX_VALUE) 0 else lastAttempt + 1
                prewarmedHuyaAttempt = candidateAttempt
                candidateInfo = PlayerHelper.resolvePlaybackInfo(
                    channel,
                    attemptIndex = candidateAttempt,
                    huyaSchemeOverride = currentHuyaRecoverySchemeOverride(),
                    validateHuyaRedirectHost = true
                )
            }
        }
        return Triple(candidateInfo, candidateAttempt, null)
    }

    private suspend fun prepareBoundedHuyaCandidate(
        channel: Channel,
        initialInfo: PlaybackStreamInfo,
        initialAttempt: Int,
        maximumCandidates: Int = 3
    ): Triple<PlaybackStreamInfo, Int, PreconnectedHttpDataSource?>? {
        var acquired: PreconnectedHttpDataSource? = null
        var delivered = false
        try {
            val result = withTimeoutOrNull(PlaybackProtectionPolicy.PRECHECK_TIMEOUT_MS) {
                prepareHuyaCandidateConnection(channel, initialInfo, initialAttempt, maximumCandidates)
                    .also { acquired = it.third }
            }
            delivered = result != null
            return result
        } finally {
            if (!delivered) acquired?.let(::closePreconnectedHuyaDataSourceAsync)
        }
    }

    private fun retryHuyaRenewalWithNextRoute(
        channel: Channel,
        reason: String,
        lastCandidateAttempt: Int
    ) {
        if (currentChannel?.id != channel.id || isFinishing || isDestroyed || refreshInProgress) return
        if (lastCandidateAttempt > huyaCdnRotationIndex) {
            huyaCdnRotationIndex = lastCandidateAttempt
        }
        prewarmedHuyaPlaybackInfo = null
        prewarmedHuyaAttempt = -1
        prewarmedHuyaChannelId = -1L
        prewarmedHuyaResolvedAtMs = 0L
        clearPreconnectedHuyaDataSource(cancelJob = false)
        huyaConnectionPrewarmJob = null
        val started = refreshHuyaSingleStream(
            channel,
            reason,
            recoveryDelayMs = 0L,
            plannedRenewal = true
        )
        DebugReporter.recordLivePlaybackEvent(
            "huya_renewal_route_fallback",
            channel,
            mapOf(
                "reason" to reason,
                "attempt" to huyaCdnRotationIndex,
                "recoveryAction" to if (started) "resolve_next_route" else "skip_active_recovery"
            )
        )
    }

    /**
     * Last-resort renewal guard for legacy Huya FLV. The normal path queues
     * the next signed stream at 108s; this independent timer ensures a missed
     * media-item transition or a stuck recovery flag cannot leave the TV on a
     * frozen frame indefinitely.
     */
    private fun scheduleLegacyHuyaSourceAgeGuard(channel: Channel, playbackInfo: PlaybackStreamInfo) {
        legacyHuyaSourceAgeGuardJob?.cancel()
        legacyHuyaSourceAgeGuardJob = null
        if (usesContinuousLegacyHuyaTransport(playbackInfo)) return
        val sourceStartedAtMs = activeMediaSourceStartedAtMs
        if (!isLegacyMstarHuyaStream() || playbackInfo.mimeType != "video/x-flv" || sourceStartedAtMs <= 0L) {
            return
        }

        val delayMs = remainingSourceAgeDelayMs(
            sourceStartedAtMs = sourceStartedAtMs,
            nowMs = System.currentTimeMillis(),
            targetSourceAgeMs = LEGACY_HUYA_SOURCE_AGE_GUARD_MS
        )
        legacyHuyaSourceAgeGuardJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!isSameLegacyHuyaSource(channel, playbackInfo, sourceStartedAtMs)) {
                legacyHuyaSourceAgeGuardJob = null
                return@launch
            }

            if (hasActiveHuyaRenewalWork()) {
                // Give a live resolver/codec handoff one short grace window,
                // but do not let an orphaned refreshInProgress flag suppress
                // every future request after the signed FLV has expired.
                kotlinx.coroutines.delay(LEGACY_HUYA_SOURCE_AGE_GUARD_GRACE_MS)
                if (!isSameLegacyHuyaSource(channel, playbackInfo, sourceStartedAtMs)) {
                    legacyHuyaSourceAgeGuardJob = null
                    return@launch
                }
                DebugReporter.recordLivePlaybackEvent(
                    "huya_source_age_guard_recovery_stuck",
                    channel,
                    mapOf(
                        "sourceAgeMs" to (System.currentTimeMillis() - sourceStartedAtMs).coerceAtLeast(0L),
                        "refreshInProgress" to refreshInProgress,
                        "playbackJobActive" to (playbackJob?.isActive == true),
                        "retryJobActive" to (retryJob?.isActive == true),
                        "queuedTransitionWatchdogActive" to (queuedHuyaTransitionWatchdogJob?.isActive == true)
                    )
                )
                cancelStaleHuyaRenewalWork()
            }

            if (!isSameLegacyHuyaSource(channel, playbackInfo, sourceStartedAtMs)) {
                legacyHuyaSourceAgeGuardJob = null
                return@launch
            }
            val lastAttempt = maxOf(prewarmedHuyaAttempt, queuedHuyaAttempt)
            discardQueuedHuyaRenewal()
            clearPreconnectedHuyaDataSource()
            refreshInProgress = false
            DebugReporter.recordLivePlaybackEvent(
                "huya_source_age_guard_triggered",
                channel,
                mapOf(
                    "sourceAgeMs" to (System.currentTimeMillis() - sourceStartedAtMs).coerceAtLeast(0L),
                    "thresholdMs" to LEGACY_HUYA_SOURCE_AGE_GUARD_MS,
                    "lastCandidateAttempt" to lastAttempt,
                    "recoveryAction" to "resolve_next_same_channel_route"
                )
            )
            legacyHuyaSourceAgeGuardJob = null
            retryHuyaRenewalWithNextRoute(
                channel = channel,
                reason = "虎牙视频流超过续签保护时限，正在重新获取同频道线路",
                lastCandidateAttempt = lastAttempt
            )
        }
    }

    private fun isSameLegacyHuyaSource(
        channel: Channel,
        playbackInfo: PlaybackStreamInfo,
        sourceStartedAtMs: Long
    ): Boolean = currentChannel?.id == channel.id &&
        currentPlaybackInfo?.url == playbackInfo.url &&
        activeMediaSourceStartedAtMs == sourceStartedAtMs &&
        !isFinishing && !isDestroyed

    private fun hasActiveHuyaRenewalWork(): Boolean = refreshInProgress ||
        playbackJob?.isActive == true ||
        retryJob?.isActive == true ||
        huyaRecoveryJob?.isActive == true ||
        huyaEofReconnectJob?.isActive == true ||
        huyaEofPrewarmWaitJob?.isActive == true ||
        huyaPrewarmJob?.isActive == true ||
        huyaConnectionPrewarmJob?.isActive == true ||
        huyaShortStreamPrewarmJob?.isActive == true ||
        huyaRenewalFallbackJob?.isActive == true ||
        huyaMissingAudioRetryJob?.isActive == true ||
        persistentSourceRetryJob?.isActive == true ||
        queuedHuyaCutoverJob?.isActive == true ||
        queuedHuyaTransitionWatchdogJob?.isActive == true

    private fun cancelStaleHuyaRenewalWork() {
        playbackJob?.cancel()
        retryJob?.cancel()
        huyaRecoveryJob?.cancel()
        huyaEofReconnectJob?.cancel()
        huyaEofPrewarmWaitJob?.cancel()
        huyaPrewarmJob?.cancel()
        huyaConnectionPrewarmJob?.cancel()
        huyaShortStreamPrewarmJob?.cancel()
        huyaRenewalFallbackJob?.cancel()
        huyaMissingAudioRetryJob?.cancel()
        persistentSourceRetryJob?.cancel()
        queuedHuyaCutoverJob?.cancel()
        queuedHuyaTransitionWatchdogJob?.cancel()
        playbackJob = null
        retryJob = null
        huyaRecoveryJob = null
        huyaEofReconnectJob = null
        huyaEofPrewarmWaitJob = null
        huyaPrewarmJob = null
        huyaConnectionPrewarmJob = null
        huyaShortStreamPrewarmJob = null
        huyaRenewalFallbackJob = null
        huyaMissingAudioRetryJob = null
        persistentSourceRetryJob = null
        queuedHuyaCutoverJob = null
        queuedHuyaTransitionWatchdogJob = null
    }

    /**
     * A failed prewarm must not leave a legacy FLV stream to expire without a
     * renewal attempt. If the candidate lookup did not finish by 105 seconds,
     * obtain a fresh signed route while the current connection is still alive.
     */
    private fun scheduleHuyaRenewalFallback(channel: Channel, sourceInfo: PlaybackStreamInfo) {
        huyaRenewalFallbackJob?.cancel()
        val delayMs = remainingSourceAgeDelayMs(
            sourceStartedAtMs = activeMediaSourceStartedAtMs,
            nowMs = System.currentTimeMillis(),
            targetSourceAgeMs = LEGACY_HUYA_RENEWAL_FALLBACK_SOURCE_AGE_MS
        )
        DebugReporter.recordLivePlaybackEvent(
            "huya_renewal_fallback_scheduled",
            channel,
            mapOf("delayMs" to delayMs, "resolvedBy" to sourceInfo.resolvedBy)
        )
        huyaRenewalFallbackJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (currentChannel?.id != channel.id ||
                currentPlaybackInfo?.url != sourceInfo.url ||
                isFinishing || isDestroyed
            ) {
                huyaRenewalFallbackJob = null
                return@launch
            }

            var waitCount = 0
            while (huyaPrewarmJob?.isActive == true && waitCount < 2) {
                kotlinx.coroutines.delay(750L)
                waitCount += 1
                if (currentChannel?.id != channel.id || currentPlaybackInfo?.url != sourceInfo.url) {
                    huyaRenewalFallbackJob = null
                    return@launch
                }
            }
            // A signed URL alone is not a ready renewal. Wait briefly for the
            // separately scheduled HTTP preconnect to either queue a playable
            // source or fail fast, then decide from the actual connection state.
            val preconnectDeadline = SystemClock.elapsedRealtime() + LEGACY_HUYA_CONNECTION_PRECONNECT_WAIT_MS
            while (huyaConnectionPrewarmJob?.isActive == true &&
                SystemClock.elapsedRealtime() < preconnectDeadline
            ) {
                kotlinx.coroutines.delay(200L)
                if (currentChannel?.id != channel.id || currentPlaybackInfo?.url != sourceInfo.url) {
                    huyaRenewalFallbackJob = null
                    return@launch
                }
            }
            val queuedCandidateReady = queuedHuyaMediaId != null &&
                queuedHuyaPlaybackInfo != null &&
                queuedHuyaDataSource != null &&
                isFreshPreconnectedHttpResponse(
                    queuedHuyaConnectionOpenedAtMs,
                    SystemClock.elapsedRealtime(),
                    LEGACY_HUYA_CONNECTION_PRECONNECT_MAX_AGE_MS
                )
            val connectedCandidateReady = preconnectedHuyaDataSource != null &&
                prewarmedHuyaChannelId == channel.id &&
                preconnectedHuyaDataSourceUrl == prewarmedHuyaPlaybackInfo?.url &&
                isFreshPreconnectedHttpResponse(
                    preconnectedHuyaDataSourceAtMs,
                    SystemClock.elapsedRealtime(),
                    LEGACY_HUYA_CONNECTION_PRECONNECT_MAX_AGE_MS
                )
            if (queuedCandidateReady || connectedCandidateReady || refreshInProgress) {
                huyaRenewalFallbackJob = null
                return@launch
            }

            if (huyaConnectionPrewarmJob?.isActive == true) {
                huyaConnectionPrewarmJob?.cancel()
                huyaConnectionPrewarmJob = null
                clearPreconnectedHuyaDataSource(cancelJob = false)
            }
            if (huyaPrewarmJob?.isActive == true) {
                huyaPrewarmJob?.cancel()
                huyaPrewarmJob = null
            }
            huyaRenewalFallbackJob = null
            DebugReporter.recordLivePlaybackEvent(
                "huya_renewal_fallback_started",
                channel,
                mapOf(
                    "reason" to "connected_candidate_missing",
                    "sourceAgeMs" to (System.currentTimeMillis() - activeMediaSourceStartedAtMs).coerceAtLeast(0L)
                )
            )
            retryHuyaRenewalWithNextRoute(
                channel,
                reason = "虎牙候选流未能及时建立连接，提前切换到下一条同频道线路",
                lastCandidateAttempt = prewarmedHuyaAttempt
            )
        }
    }

    private fun takePreconnectedHuyaDataSource(url: String): PreconnectedHttpDataSource? {
        huyaConnectionPrewarmJob?.cancel()
        huyaConnectionPrewarmJob = null
        val source = preconnectedHuyaDataSource
        val nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val isUsable = source != null &&
            preconnectedHuyaDataSourceUrl == url &&
            isFreshPreconnectedHttpResponse(
                preconnectedHuyaDataSourceAtMs,
                nowElapsedRealtimeMs,
                LEGACY_HUYA_CONNECTION_PRECONNECT_MAX_AGE_MS
            )
        preconnectedHuyaDataSource = null
        preconnectedHuyaDataSourceUrl = ""
        preconnectedHuyaDataSourceAtMs = 0L
        if (!isUsable) {
            closePreconnectedHuyaDataSourceAsync(source)
            return null
        }
        return source
    }

    private fun clearPreconnectedHuyaDataSource(cancelJob: Boolean = true) {
        if (cancelJob) {
            huyaConnectionPrewarmJob?.cancel()
            huyaConnectionPrewarmJob = null
        }
        val source = preconnectedHuyaDataSource
        preconnectedHuyaDataSource = null
        preconnectedHuyaDataSourceUrl = ""
        preconnectedHuyaDataSourceAtMs = 0L
        closePreconnectedHuyaDataSourceAsync(source)
    }

    private fun legacyHuyaFlvRenewalProvider(
        channel: Channel,
        mediaId: String,
        playbackInfo: PlaybackStreamInfo
    ): (suspend (Int, String) -> PlaybackStreamInfo?)? {
        if (!DeviceProfile.isLegacyTv(this) ||
            !playbackInfo.resolvedBy.startsWith("huya:") ||
            playbackInfo.mimeType != "video/x-flv"
        ) return null
        return { statusCode, rejectedUrl ->
            resolveLegacyHuyaFlvInlineRenewal(channel, mediaId, statusCode, rejectedUrl)
        }
    }

    /**
     * Refresh only the signed transport URL. The active MediaItem, FLV
     * extractor, ExoPlayer and vendor decoder remain attached across the EOF.
     */
    private suspend fun resolveLegacyHuyaFlvInlineRenewal(
        channel: Channel,
        mediaId: String,
        statusCode: Int,
        rejectedUrl: String
    ): PlaybackStreamInfo? {
        var forceSameChannelHlsFallback = false
        val attempt = withContext(Dispatchers.Main.immediate) {
            if (currentChannel?.id != channel.id ||
                player?.currentMediaItem?.mediaId != mediaId ||
                currentPlaybackInfo?.url != rejectedUrl ||
                inlineHuyaRenewalMediaId != null ||
                isFinishing || isDestroyed
            ) {
                null
            } else {
                inlineHuyaRenewalMediaId = mediaId
                huyaCdnRotationIndex = if (huyaCdnRotationIndex == Int.MAX_VALUE) {
                    0
                } else {
                    huyaCdnRotationIndex + 1
                }
                if (isLegacyMstarHuyaStream() && statusCode == 403) {
                    legacyHuyaFlvForbiddenReconnectCount++
                    forceSameChannelHlsFallback =
                        shouldFallbackLegacyHuyaFlvToHlsAfterRejectedReconnect(
                            isLegacyMstar = true,
                            statusCode = statusCode,
                            rejectedReconnectCount = legacyHuyaFlvForbiddenReconnectCount,
                            hlsFallbackAlreadyAttempted = legacyHuyaHlsFallbackAttempted
                        )
                    if (forceSameChannelHlsFallback) {
                        // Keep this latched even if HLS is rejected and we return
                        // to FLV. Otherwise every later FLV 403 starts another
                        // FLV -> HLS -> FLV loop on this legacy device.
                        legacyHuyaHlsFallbackAttempted = true
                        huyaForceHlsForRecovery = true
                        huyaForceFlvForRecovery = false
                        DebugReporter.recordLivePlaybackEvent(
                            "huya_flv_repeated_403_hls_fallback_armed",
                            channel,
                            mapOf(
                                "httpStatusCode" to statusCode,
                                "rejectedReconnectCount" to legacyHuyaFlvForbiddenReconnectCount,
                                "recoveryAction" to "same_channel_mobile_hls"
                            )
                        )
                    }
                }
                DebugReporter.recordLivePlaybackEvent(
                    "huya_inline_signed_url_renewal_started",
                    channel,
                    mapOf(
                        "mediaId" to mediaId,
                        "previousResolvedBy" to currentPlaybackInfo?.resolvedBy,
                        "httpStatusCode" to statusCode.takeIf { it > 0 },
                        "triggerKind" to if (statusCode == 0) "reconnect_payload_rejected" else "http_status_rejected",
                        "attempt" to huyaCdnRotationIndex,
                        "recoveryAction" to "renew_transport_url_without_replacing_media_item"
                    )
                )
                huyaCdnRotationIndex
            }
        } ?: return null

        if (forceSameChannelHlsFallback) {
            withContext(Dispatchers.Main.immediate) {
                if (inlineHuyaRenewalMediaId == mediaId) inlineHuyaRenewalMediaId = null
            }
            Log.w(
                TAG,
                "Repeated Huya FLV HTTP 403 reconnects; stop FLV retries and move same channel to HLS"
            )
            return null
        }

        val startedAtMs = System.currentTimeMillis()
        try {
            val renewedInfo = withTimeoutOrNull(LEGACY_HUYA_INLINE_RENEWAL_TIMEOUT_MS) {
                PlayerHelper.resolvePlaybackInfo(
                    channel,
                    attemptIndex = attempt,
                    huyaSchemeOverride = PreferencesHelper.HUYA_SCHEME_SINGLE_FLV,
                    validateHuyaRedirectHost = huyaRedirectHostValidationRequired
                )
            }
            return withContext(Dispatchers.Main.immediate) {
                if (currentChannel?.id != channel.id ||
                    player?.currentMediaItem?.mediaId != mediaId ||
                    inlineHuyaRenewalMediaId != mediaId ||
                    isFinishing || isDestroyed
                ) return@withContext null

                val currentInfo = currentPlaybackInfo
                if (renewedInfo == null ||
                    renewedInfo.mimeType != "video/x-flv" ||
                    renewedInfo.url.isBlank() ||
                    renewedInfo.url == rejectedUrl
                ) {
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_inline_signed_url_renewal_failed",
                        channel,
                        mapOf(
                            "mediaId" to mediaId,
                            "httpStatusCode" to statusCode.takeIf { it > 0 },
                            "attempt" to attempt,
                            "reason" to if (renewedInfo == null) "timeout_or_resolver_failure" else "no_new_flv_url",
                            "resolveElapsedMs" to (System.currentTimeMillis() - startedAtMs)
                        )
                    )
                    null
                } else {
                    currentPlaybackInfo = renewedInfo
                    DebugReporter.updatePlaybackInfo(renewedInfo, currentDecoderMode)
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_inline_signed_url_resolved",
                        channel,
                        mapOf(
                            "mediaId" to mediaId,
                            "previousResolvedBy" to currentInfo?.resolvedBy,
                            "resolvedBy" to renewedInfo.resolvedBy,
                            "httpStatusCode" to statusCode.takeIf { it > 0 },
                            "attempt" to attempt,
                            "resolveElapsedMs" to (System.currentTimeMillis() - startedAtMs),
                            "recoveryAction" to "same_data_source_same_extractor"
                        )
                    )
                    renewedInfo
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            withContext(Dispatchers.Main.immediate) {
                if (inlineHuyaRenewalMediaId == mediaId && currentChannel?.id == channel.id) {
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_inline_signed_url_renewal_failed",
                        channel,
                        mapOf(
                            "mediaId" to mediaId,
                            "httpStatusCode" to statusCode,
                            "attempt" to attempt,
                            "errorType" to error.javaClass.simpleName,
                            "resolveElapsedMs" to (System.currentTimeMillis() - startedAtMs)
                        )
                    )
                }
            }
            return null
        } finally {
            withContext(Dispatchers.Main.immediate) {
                if (inlineHuyaRenewalMediaId == mediaId) inlineHuyaRenewalMediaId = null
            }
        }
    }

    /** Log bounded checkpoints for transport-level EOF reconnects, never the signed URL. */
    private fun huyaFlvEofReconnectListener(
        channel: Channel,
        mediaId: String,
        playbackInfo: PlaybackStreamInfo
    ): (LegacyHuyaFlvReconnectEvent) -> Unit = { event ->
        // The DataSource invokes this on ExoPlayer's loader thread. All player
        // reads, attribution and recovery decisions must run on its main looper;
        // otherwise verifyApplicationThread throws inside reportReconnect's
        // callback guard and silently discards the transport failure.
        runOnUiThread { handleHuyaFlvEofReconnect(channel, mediaId, playbackInfo, event) }
    }

    private fun handleHuyaFlvEofReconnect(
        channel: Channel,
        mediaId: String,
        playbackInfo: PlaybackStreamInfo,
        event: LegacyHuyaFlvReconnectEvent
    ) {
        if (isFinishing || isDestroyed || currentChannel?.id != channel.id ||
            player?.currentMediaItem?.mediaId != mediaId
        ) return
        runOnUiThread {
            if (currentChannel?.id == channel.id &&
                player?.currentMediaItem?.mediaId == mediaId &&
                !isFinishing && !isDestroyed
            ) {
                if (event.recoveryInProgress) {
                    // A successful HTTP open is not proof that decoded FLV
                    // frames resumed. Keep the original deadline while the
                    // player is still buffering, so the high-level watchdog
                    // cannot replace this MediaSource mid-reconnect.
                    legacyHuyaTransportReconnectStartedAtElapsedMs =
                        legacyHuyaReconnectGraceStartedAtMs(
                            currentStartedAtMs = legacyHuyaTransportReconnectStartedAtElapsedMs,
                            nowMs = SystemClock.elapsedRealtime(),
                            recoveryInProgress = true
                        )
                } else if (player?.playbackState != Player.STATE_BUFFERING) {
                    legacyHuyaTransportReconnectStartedAtElapsedMs = 0L
                }
            }
        }
        val activePlaybackInfo = currentPlaybackInfo?.takeIf {
            currentChannel?.id == channel.id &&
                player?.currentMediaItem?.mediaId == mediaId
        }
        // Inline signature renewal changes the route without changing mediaId.
        // Resolve attribution from the active item so later EOF/403 events do
        // not get discarded merely because the original route has changed.
        val resolvedBy = event.resolvedBy ?: activePlaybackInfo?.resolvedBy ?: playbackInfo.resolvedBy
        if (event.recoveryCause == "initial_open") {
            DebugReporter.recordLivePlaybackEvent(
                "huya_flv_initial_open_recovery",
                channel,
                mapOf(
                    "successful" to event.successful,
                    "openAttempt" to event.openAttempt,
                    "dataSourceSessionId" to event.sourceSessionId,
                    "mediaId" to mediaId,
                    "resolvedBy" to resolvedBy,
                    "httpStatusCode" to event.httpStatusCode,
                    "errorType" to event.errorType,
                    "triggerErrorType" to event.triggerErrorType,
                    "signedUrlRenewed" to event.signedUrlRenewed,
                    "recoveryAction" to "bounded_first_open_retry_same_channel"
                )
            )
        } else if (!event.successful || event.signedUrlRenewed || event.recoveryCause == "read_io_error" ||
            event.reconnectCount == 1 || event.reconnectCount % 10 == 0
        ) {
            DebugReporter.recordLivePlaybackEvent(
                "huya_flv_transport_reconnect",
                channel,
                mapOf(
                    "successful" to event.successful,
                    "reconnectCount" to event.reconnectCount,
                    "responseBytes" to event.responseBytes,
                    "headerBytesSkipped" to event.headerBytesSkipped,
                    "reconnectMode" to "same_media_item",
                    "reconnectPrefixPolicy" to "complete_flv_tags_only",
                    "responseVideoPackets" to event.responseMedia?.videoPackets,
                    "responseAudioPackets" to event.responseMedia?.audioPackets,
                    "overlappingVideoPackets" to event.responseMedia?.overlappingVideoPackets,
                    "overlappingAudioPackets" to event.responseMedia?.overlappingAudioPackets,
                    "newVideoPackets" to event.responseMedia?.newVideoPackets,
                    "newAudioPackets" to event.responseMedia?.newAudioPackets,
                    "newMediaDurationMs" to event.responseMedia?.newMediaDurationMs,
                    "firstVideoTimestampMs" to event.responseMedia?.firstVideoTimestampMs,
                    "lastVideoTimestampMs" to event.responseMedia?.lastVideoTimestampMs,
                    "firstAudioTimestampMs" to event.responseMedia?.firstAudioTimestampMs,
                    "lastAudioTimestampMs" to event.responseMedia?.lastAudioTimestampMs,
                    "firstKeyframeHash" to event.responseMedia?.firstKeyframeHash,
                    "responseMediaDurationMs" to event.responseMedia?.mediaDurationMs,
                    "timelinePolicy" to "shared_flv_dts_preserve_gaps",
                    "timelineOffsetMs" to event.timeline?.offsetMs,
                    "timelineCorrectionCount" to event.timeline?.correctionCount,
                    "outputAudioVideoDtsDeltaMs" to event.timeline?.audioVideoDtsDeltaMs,
                    "dataSourceSessionId" to event.sourceSessionId,
                    "mediaId" to mediaId,
                    "resolvedBy" to resolvedBy,
                    "httpStatusCode" to event.httpStatusCode,
                    "errorType" to event.errorType,
                    "signedUrlRenewed" to event.signedUrlRenewed,
                    "recoveryCause" to event.recoveryCause,
                    "triggerErrorType" to event.triggerErrorType,
                    "recoveryInProgress" to event.recoveryInProgress,
                    "audioPayloadFound" to event.prefixHasAudioPayload,
                    "videoKeyframeFound" to event.prefixHasVideoKeyframe,
                    "prebufferBytes" to event.prefixBytes
                )
            )
        }
        if (event.successful && event.signedUrlRenewed) {
            runOnUiThread {
                if (currentChannel?.id == channel.id &&
                    player?.currentMediaItem?.mediaId == mediaId &&
                    !isFinishing && !isDestroyed
                ) {
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_inline_signed_url_opened",
                        channel,
                        mapOf(
                            "mediaId" to mediaId,
                            "resolvedBy" to resolvedBy,
                            "httpStatusCode" to event.httpStatusCode,
                            "reconnectCount" to event.reconnectCount,
                            "recoveryAction" to "continue_existing_flv_session"
                        )
                    )
                }
            }
        }
        if (event.successful && event.reconnectCount == 1 && playbackInfo.mimeType == "video/x-flv") {
            runOnUiThread {
                val sourceAgeMs = (System.currentTimeMillis() - activeMediaSourceStartedAtMs)
                    .coerceAtLeast(0L)
                if (isLegacyMstarHuyaStream() && currentChannel?.id == channel.id &&
                    currentPlaybackInfo?.url == playbackInfo.url &&
                    player?.currentMediaItem?.mediaId == mediaId &&
                    queuedHuyaMediaId == null &&
                    !refreshInProgress && !isFinishing && !isDestroyed
                ) {
                    DebugReporter.recordLivePlaybackEvent(
                        "huya_early_http_eof_observed",
                        channel,
                        mapOf(
                            "resolvedBy" to resolvedBy,
                            "sourceAgeMs" to sourceAgeMs,
                            "responseBytes" to event.responseBytes,
                            "reconnectCount" to event.reconnectCount,
                            "action" to "observe_only; successful_http_eof_is_not_signature_failure"
                        )
                    )
                }
            }
        }
        val rejectedStatus = event.httpStatusCode
        if (!event.successful && !event.recoveryInProgress && event.recoveryCause != "initial_open" && rejectedStatus != null &&
            shouldRenewLegacyHuyaFlvHttpStatus(rejectedStatus)
        ) {
            runOnUiThread {
                if (currentChannel?.id != channel.id ||
                    currentPlaybackInfo?.resolvedBy != resolvedBy ||
                    player?.currentMediaItem?.mediaId != mediaId ||
                    refreshInProgress || isFinishing || isDestroyed
                ) return@runOnUiThread
                Log.w(TAG, "Huya signed FLV reconnect rejected with HTTP $rejectedStatus; pacing same-channel renewal")
                refreshHuyaSingleStream(
                    channel = channel,
                    reason = "虎牙线路返回 HTTP $rejectedStatus，正在重新签名",
                    recoveryDelayMs = 0L,
                    confirmedSourceFailure = true
                )
            }
        }
    }

    /** Capture response framing without ever recording the signed URL or query string. */
    private fun huyaResponseTransferListener(
        channel: Channel,
        playbackInfo: PlaybackStreamInfo
    ): TransferListener? {
        if (!playbackInfo.resolvedBy.startsWith("huya:") || playbackInfo.mimeType != "video/x-flv") {
            return null
        }
        return object : TransferListener {
            private val responseLogLock = Any()
            private var responseStartCount = 0
            private var lastResponseLogAtMs = 0L
            private var lastLoggedHost: String? = null

            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                if (!isNetwork) return
                val host = source.uri?.host
                val responseOrdinal: Int
                val shouldLog: Boolean
                synchronized(responseLogLock) {
                    responseStartCount++
                    responseOrdinal = responseStartCount
                    val now = SystemClock.elapsedRealtime()
                    shouldLog = responseOrdinal == 1 || host != lastLoggedHost ||
                        now - lastResponseLogAtMs >= HUYA_HTTP_RESPONSE_LOG_INTERVAL_MS
                    if (shouldLog) {
                        lastResponseLogAtMs = now
                        lastLoggedHost = host
                    }
                }
                if (!shouldLog) return
                val headers = runCatching { source.responseHeaders }.getOrDefault(emptyMap())
                fun header(name: String): String? = headers.entries
                    .firstOrNull { it.key.equals(name, ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
                val contentLength = header("Content-Length")
                DebugReporter.recordLivePlaybackEvent(
                    "huya_http_response_opened",
                    channel,
                    mapOf(
                        "resolvedBy" to playbackInfo.resolvedBy,
                        "responseStartOrdinal" to responseOrdinal,
                        "mimeType" to playbackInfo.mimeType,
                        "uriScheme" to source.uri?.scheme,
                        "uriHost" to source.uri?.host,
                        "contentLengthBytes" to contentLength?.toLongOrNull(),
                        "contentTypeHeader" to header("Content-Type"),
                        "transferEncodingHeader" to header("Transfer-Encoding"),
                        "contentRangeHeader" to header("Content-Range"),
                        "dataSpecPosition" to dataSpec.position,
                        "dataSpecLength" to dataSpec.length
                    )
                )
            }

            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int
            ) = Unit

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        }
    }

    /** Closing an unused HTTP response can perform blocking socket work; never do that on TV's UI thread. */
    private fun closePreconnectedHuyaDataSourceAsync(source: PreconnectedHttpDataSource?) {
        if (source == null) return
        App.instance.applicationScope.launch(Dispatchers.IO) {
            runCatching { source.close() }
                .onFailure { Log.w(TAG, "Failed to close unused Huya preconnected stream", it) }
        }
    }

    /**
     * Huya may return a valid FLV video URL whose current CDN variant has no
     * audio track. On MStar, try another signed line quickly instead of
     * leaving a silent picture for the full ~120-second FLV lifetime.
     */
    private fun scheduleHuyaMissingAudioRecovery() {
        val channel = currentChannel ?: return
        val playbackInfo = currentPlaybackInfo ?: return
        if (!missingAudioRecoveryEnabled || !playbackInfo.resolvedBy.startsWith("huya:") ||
            playbackInfo.mimeType != "video/x-flv" ||
            audioProtectionAttempts >= PlaybackProtectionPolicy.MAX_AUDIO_RECOVERIES
        ) return
        if (activeAudioPcmObserved || refreshInProgress) return
        if (activeAudioTrackCount > 0 && activeAudioPlaybackAdvanced) return
        if (huyaMissingAudioRetryJob?.isActive == true) return

        val nextRetry = if (huyaMissingAudioRetryCount == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            huyaMissingAudioRetryCount + 1
        }
        val retryDelayMs = huyaMissingAudioRetryDelayMs(
            retryCount = huyaMissingAudioRetryCount,
            supportedAudioTrackCount = activeAudioTrackCount
        ).coerceAtLeast(3_000L)
        val scheduledGeneration = mediaSourceGeneration
        huyaMissingAudioRetryJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(retryDelayMs)
            huyaMissingAudioRetryJob = null
            if (currentChannel?.id != channel.id ||
                currentPlaybackInfo?.url != playbackInfo.url ||
                mediaSourceGeneration != scheduledGeneration ||
                refreshInProgress ||
                isFinishing || isDestroyed
            ) return@launch
            if (!PlaybackProtectionPolicy.shouldRecoverAudio(
                    enabled = missingAudioRecoveryEnabled,
                    isHuyaFlv = playbackInfo.resolvedBy.startsWith("huya:") && playbackInfo.mimeType == "video/x-flv",
                    readyAndPlaying = player?.playbackState == Player.STATE_READY && player?.playWhenReady == true && hasWindowFocus(),
                    interactionDeferred = shouldDeferAutomaticRecovery(),
                    sourceAgeMs = System.currentTimeMillis() - activeMediaSourceStartedAtMs,
                    supportedTracks = activeAudioTrackCount,
                    audioClockAdvanced = activeAudioPlaybackAdvanced,
                    audiblePcmObserved = activeAudioPcmObserved,
                    attempts = audioProtectionAttempts
                )
            ) return@launch
            audioProtectionAttempts++

            if (cutOverQueuedHuyaStream(channel, "当前 FLV 未检测到音频，切换到已预连接线路")) {
                showStatusHint("当前切片无声音，正在接续同频道的已验证线路", STATUS_HINT_DEFAULT_MS)
                return@launch
            }

            huyaMissingAudioRetryCount = if (huyaMissingAudioRetryCount == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                huyaMissingAudioRetryCount + 1
            }
            showStatusHint(
                "当前虎牙线路尚无声音，保持当前方案尝试第 $nextRetry 次",
                STATUS_HINT_LONG_MS
            )
            DebugReporter.recordLivePlaybackEvent(
                "huya_missing_audio_retry",
                channel,
                mapOf(
                    "attempt" to huyaMissingAudioRetryCount,
                    "recoveryAction" to "rotate_same_huya_scheme",
                    "delayMs" to retryDelayMs,
                    "mimeType" to playbackInfo.mimeType,
                    "resolvedBy" to playbackInfo.resolvedBy,
                    "supportedTrackCount" to activeAudioTrackCount,
                    "audioPositionAdvanced" to activeAudioPlaybackAdvanced,
                    "nonSilentPcmObserved" to activeAudioPcmObserved,
                    "candidateMustBePreconnected" to avPrecheckEnabled,
                    "protectionAttempt" to audioProtectionAttempts,
                    "protectionLimit" to PlaybackProtectionPolicy.MAX_AUDIO_RECOVERIES
                )
            )
            val recoveryStarted = if (isHuyaHotSwapEnabled()) {
                // The phone's hot-swap mode is a different recovery owner.
                // Cancel its pending standby before one bounded SAME-channel request.
                resetHuyaStandbyState()
                huyaCdnRotationIndex = if (huyaCdnRotationIndex == Int.MAX_VALUE) 0 else huyaCdnRotationIndex + 1
                refreshInProgress = true
                playChannel(channel, isRetry = true, isProactiveRefresh = true)
                true
            } else refreshHuyaSingleStream(
                channel = channel,
                reason = "虎牙新切片没有声音，正在验证同频道下一条音视频线路",
                recoveryDelayMs = 0L,
                plannedRenewal = true
            )
            if (!recoveryStarted) {
                audioProtectionAttempts = (audioProtectionAttempts - 1).coerceAtLeast(0)
                DebugReporter.recordLivePlaybackEvent(
                    "huya_missing_audio_retry_deferred",
                    channel,
                    mapOf(
                        "refreshInProgress" to refreshInProgress,
                        "sourceAgeMs" to (System.currentTimeMillis() - activeMediaSourceStartedAtMs)
                            .coerceAtLeast(0L)
                    )
                )
            }
        }
    }

    private fun resetHuyaMissingAudioRetryState() {
        huyaMissingAudioRetryJob?.cancel()
        huyaMissingAudioRetryJob = null
        huyaMissingAudioRetryCount = 0
        activeAudioTrackCount = 0
        activeAudioPlaybackAdvanced = false
        activeAudioAdvancingAtNanos = 0L
        activeAudioPcmObserved = false
        lastAudiblePcmObservedAtNanos = 0L
        legacyHuyaPcmEvidence.reset()
    }

    private fun resetHuyaShortStreamTracking() {
        huyaShortStreamPrewarmJob?.cancel()
        huyaShortStreamPrewarmJob = null
        huyaShortStreamPrewarmSourceUrl = ""
        activeHuyaLoadedDurationMs = C.TIME_UNSET
        activeHuyaHttpResponseComplete = false
    }

    private fun handleActiveAudiblePcm(observedAtNanos: Long) {
        if (!isLegacyMstarHuyaStream() ||
            isPcmActivityAfterActiveAudioStart(observedAtNanos, activeAudioAdvancingAtNanos)
        ) lastAudiblePcmObservedAtNanos = observedAtNanos
        if (activeAudioPcmObserved) return
        if (isLegacyMstarHuyaStream()) {
            val advancingAtNanos = activeAudioAdvancingAtNanos
            // AudioProcessor callbacks have no MediaItem identity. A delayed
            // callback from the previous FLV must not cancel the new item's
            // missing-audio retry; require PCM emitted after its audio clock
            // actually started.
            if (!isPcmActivityAfterActiveAudioStart(observedAtNanos, advancingAtNanos)) {
                DebugReporter.recordLivePlaybackEvent(
                    "audio_pcm_ignored_before_active_audio",
                    currentChannel,
                    mapOf(
                        "observedAtNanos" to observedAtNanos,
                        "activeAudioAdvancingAtNanos" to advancingAtNanos,
                        "supportedAudioTrackCount" to activeAudioTrackCount
                    ) + huyaRenewalTimingDetails("stale_pcm_ignored")
                )
                return
            }
        }
        if (isLegacyMstarHuyaStream() &&
            !legacyHuyaPcmEvidence.observe(observedAtNanos / 1_000_000L)
        ) {
            DebugReporter.recordLivePlaybackEvent(
                "audio_pcm_evidence_pending",
                currentChannel,
                mapOf(
                    "observations" to legacyHuyaPcmEvidence.observations,
                    "requiredObservations" to 2,
                    "supportedAudioTrackCount" to activeAudioTrackCount
                ) + huyaRenewalTimingDetails("pcm_evidence_pending")
            )
            return
        }
        activeAudioPcmObserved = true
        maybeScheduleShortHuyaContinuation()
        huyaMissingAudioRetryJob?.cancel()
        huyaMissingAudioRetryJob = null
        huyaMissingAudioRetryCount = 0
        if (isLegacyMstarHuyaStream()) {
            recordHuyaRenewalStage("non_silent_pcm")
            DebugReporter.recordLivePlaybackEvent(
                "audio_pcm_activity_detected",
                currentChannel,
                mapOf(
                    "audioPositionAdvanced" to activeAudioPlaybackAdvanced,
                    "supportedAudioTrackCount" to activeAudioTrackCount
                ) + huyaRenewalTimingDetails("non_silent_pcm")
            )
            if (pendingHuyaRenewalFirstFrameSeen) resetHuyaRenewalTiming()
        }
    }

    private fun resetHuyaRecoveryState() {
        resetHuyaShortStreamTracking()
        huyaEofReconnectJob?.cancel()
        huyaEofReconnectJob = null
        huyaEofPrewarmWaitJob?.cancel()
        huyaEofPrewarmWaitJob = null
        huyaSameUrlReconnectBlockedUrl = ""
        discardQueuedHuyaRenewal()
        legacyHuyaSourceAgeGuardJob?.cancel()
        legacyHuyaSourceAgeGuardJob = null
        huyaPrewarmJob?.cancel()
        huyaPrewarmJob = null
        huyaRenewalFallbackJob?.cancel()
        huyaRenewalFallbackJob = null
        clearPreconnectedHuyaDataSource()
        huyaRecoveryJob?.cancel()
        huyaRecoveryJob = null
        huyaPrewarmSourceChannelId = -1L
        huyaPrewarmSourceUrl = ""
        huyaCdnRecoveryAttempt = 0
        huyaCdnRotationIndex = 0
        legacyHuyaFlvForbiddenReconnectCount = 0
        legacyHuyaHlsFallbackAttempted = false
        huyaRedirectHostValidationRequired = false
        lastHuyaRecoveryAtMs = 0L
        prewarmedHuyaPlaybackInfo = null
        prewarmedHuyaAttempt = -1
        prewarmedHuyaChannelId = -1L
        prewarmedHuyaResolvedAtMs = 0L
        resetHuyaMissingAudioRetryState()
    }

    private fun noteInvalidHuyaRedirectHost(error: Throwable) {
        if (!isLegacyMstarHuyaStream() || !isInvalidHuyaCdnHostError(error) ||
            huyaRedirectHostValidationRequired
        ) return

        huyaRedirectHostValidationRequired = true
        Log.w(TAG, "Huya CDN returned an invalid redirect host; validating redirect targets on renewal")
        DebugReporter.recordLivePlaybackEvent(
            "huya_invalid_redirect_host_detected",
            currentChannel,
            mapOf(
                "resolvedBy" to currentPlaybackInfo?.resolvedBy,
                "validationEnabledForRenewals" to true
            )
        )
    }

    private fun resetLegacyHlsRecoveryState() {
        legacyHlsRecoveryJob?.cancel()
        legacyHlsRecoveryJob = null
        legacyHlsRecoveryChannelId = -1L
        legacyHlsRecoveryAttempt = 0
        lastLegacyHlsRecoveryAtMs = 0L
    }

    private fun scheduleHuyaStandbyPreparation(channel: Channel?, playbackInfo: PlaybackStreamInfo?) {
        huyaStandbyPrepareJob?.cancel()
        huyaStandbySwapJob?.cancel()
        if (!isHuyaHotSwapEnabled()) return
        if (channel == null || playbackInfo == null || !playbackInfo.resolvedBy.startsWith("huya:")) {
            return
        }
        huyaStandbyPrepareJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(HUYA_STANDBY_PREPARE_DELAY_MS)
            if (currentChannel?.id != channel.id || isFinishing || isDestroyed) return@launch
            if (currentPlaybackInfo?.resolvedBy?.startsWith("huya:") != true) return@launch
            var connection: PreconnectedHttpDataSource? = null
            try {
                var info = PlayerHelper.resolvePlaybackInfo(channel, attemptIndex = 0)
                if (avPrecheckEnabled && info.resolvedBy.startsWith("huya:") && info.mimeType == "video/x-flv") {
                    val candidate = prepareBoundedHuyaCandidate(channel, info, 0)
                    if (candidate?.third == null) return@launch // Never replace a healthy stream with an unverified standby.
                    info = candidate.first
                    connection = candidate.third
                }
                if (currentChannel?.id == channel.id && info.resolvedBy.startsWith("huya:") && !isFinishing) {
                    prepareHuyaStandbyStream(channel, info, connection)
                    connection = null // MediaSource now owns the open response/prefix.
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Failed to prepare standby Huya playback info for channel=${channel.name}", error)
            } finally {
                connection?.let(::closePreconnectedHuyaDataSourceAsync)
            }
        }
    }

    private fun prepareHuyaStandbyStream(
        channel: Channel, playbackInfo: PlaybackStreamInfo, connection: PreconnectedHttpDataSource? = null
    ) {
        if (!isHuyaHotSwapEnabled() || currentChannel?.id != channel.id) {
            connection?.let(::closePreconnectedHuyaDataSourceAsync)
            return
        }
        val standby = ensureStandbyPlayer()
        standbyAudioClockAdvanced = false
        standbyPlaybackInfo = playbackInfo
        standbyPlaybackReadyAtMs = 0L
        runCatching {
            PlayerHelper.createMediaSource(this, playbackInfo, "${channel.id}_standby:${System.nanoTime()}", preconnectedDataSource = connection)
        }.onSuccess { mediaSource ->
            standby.stop()
            standby.clearMediaItems()
            standbyPreconnectedDataSource?.let(::closePreconnectedHuyaDataSourceAsync)
            standbyPreconnectedDataSource = connection
            standby.setMediaSource(mediaSource)
            standby.volume = 0f
            standby.prepare()
            standby.playWhenReady = true
            standbyPlayerView.alpha = 0f
            Log.d(TAG, "Prepared standby Huya playback info for channel=${channel.name}")
        }.onFailure {
            connection?.let(::closePreconnectedHuyaDataSourceAsync)
            standbyPlaybackInfo = null
            Log.w(TAG, "Failed to prepare standby Huya playback info for channel=${channel.name}", it)
        }
    }

    private fun scheduleHuyaStandbySwap(channel: Channel) {
        huyaStandbySwapJob?.cancel()
        if (!isHuyaHotSwapEnabled()) return
        val activeStartedAt = activePlaybackStartedAtMs
        if (activeStartedAt == 0L) return
        val delayMs = (activeStartedAt + HUYA_STANDBY_SWAP_DELAY_MS - System.currentTimeMillis())
            .coerceAtLeast(500L)
        huyaStandbySwapJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (currentChannel?.id == channel.id) {
                swapToStandbyHuyaStream(channel, "到期前热切换")
            }
        }
    }

    private fun swapToStandbyHuyaStream(channel: Channel?, reason: String): Boolean {
        val activeChannel = channel ?: return false
        val activePlayer = player ?: return false
        val standby = standbyPlayer ?: return false
        val standbyInfo = standbyPlaybackInfo ?: return false
        if (currentChannel?.id != activeChannel.id) return false
        if (currentPlaybackInfo?.resolvedBy?.startsWith("huya:") != true) return false
        if (standby.playbackState != Player.STATE_READY) return false

        Log.d(TAG, "Hot swapping to standby Huya stream for channel=${activeChannel.name}, reason=$reason")

        val oldActivePlayer = activePlayer
        val oldActiveView = playerView
        val newActivePlayer = standby
        val newActiveView = standbyPlayerView

        newActiveView.alpha = 1f
        oldActiveView.alpha = 0f

        player = newActivePlayer
        standbyPlayer = oldActivePlayer
        playerView = newActiveView
        standbyPlayerView = oldActiveView
        adaptiveOverlay?.requestUpdate()
        player?.volume = 1f

        // Audio evidence belongs to the promoted player, not the retired one.
        // In particular, old audio=1/clock=true must not mask a silent standby.
        huyaMissingAudioRetryJob?.cancel()
        huyaMissingAudioRetryJob = null
        activeAudioTrackCount = newActivePlayer.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .sumOf { group -> (0 until group.length).count { group.isTrackSupported(it) } }
        activeAudioPlaybackAdvanced = standbyAudioClockAdvanced
        activeAudioAdvancingAtNanos = if (standbyAudioClockAdvanced) System.nanoTime() else 0L
        activeAudioPcmObserved = false
        lastAudiblePcmObservedAtNanos = 0L
        legacyHuyaPcmEvidence.reset()
        standbyAudioClockAdvanced = false
        standbyPreconnectedDataSource = null // Promoted MediaSource owns this response until release.
        activeMediaSourceStartedAtMs = System.currentTimeMillis()

        currentPlaybackInfo = standbyInfo
        standbyPlaybackInfo = null
        activePlaybackStartedAtMs = standbyPlaybackReadyAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        standbyPlaybackReadyAtMs = 0L
        notePlaybackProgress(force = true)
        hideBuffering()
        resetPlaybackRetries()

        standbyPlayerView.player = standbyPlayer
        standbyPlayerView.alpha = 0f
        standbyPlayer?.playWhenReady = false
        standbyPlayer?.volume = 0f
        standbyPlayer?.stop()
        standbyPlayer?.clearMediaItems()

        playerView.player = player
        playerView.alpha = 1f
        playerView.resizeMode = currentResizeMode
        standbyPlayerView.resizeMode = currentResizeMode

        scheduleHuyaStandbyPreparation(activeChannel, currentPlaybackInfo)
        scheduleHuyaMissingAudioRecovery()
        return true
    }

    private fun handleStandbyPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
        Log.w(TAG, "Standby playback failed for channel=${currentChannel?.name}", error)
        DebugReporter.reportPlaybackError(
            phase = "standby_player_error",
            channel = currentChannel,
            message = extractPlaybackFailureMessage(error),
            error = error,
            playbackInfo = standbyPlaybackInfo,
            decoderMode = currentDecoderMode
        )
        standbyPlaybackInfo = null
        standbyPlaybackReadyAtMs = 0L
        standbyPlayer?.playWhenReady = false
        standbyPlayer?.stop()
        standbyPlayer?.clearMediaItems()
        standbyPlayerView.alpha = 0f
    }

    private fun resetHuyaStandbyState() {
        huyaStandbyPrepareJob?.cancel()
        huyaStandbySwapJob?.cancel()
        standbyPlaybackInfo = null
        activePlaybackStartedAtMs = 0L
        standbyPlaybackReadyAtMs = 0L
        releaseStandbyPlayer()
        standbyPlayerView.alpha = 0f
    }

    private fun isHuyaHotSwapEnabled(): Boolean {
        // YQK 等节目源实际使用虎牙转流地址。热切换需要同时创建两路
        // ExoPlayer，在 Android 7 的低配电视上可能触发 MediaCodec/native
        // 崩溃，表现为播放后直接退回首页。低配设备统一使用单播放器，
        // 仍保留当前流重试和备用频道切换能力。
        // Once HLS has hit a protection/status failure, the current channel
        // recovery session is deliberately pinned to the single FLV path.
        // Do not let the delayed standby job switch back to the rejected HLS
        // manifest a minute later.
        return !huyaForceFlvForRecovery &&
            currentHuyaScheme == PreferencesHelper.HUYA_SCHEME_HOT_SWAP &&
            !DeviceProfile.isLegacyTv(this)
    }

    private fun startStreamRefreshWatchdog() {
        if (isLowEndTvDevice) return
        if (streamRefreshWatchdogJob?.isActive == true) return
        streamRefreshWatchdogJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(STREAM_REFRESH_WATCHDOG_INTERVAL_MS)
                val channel = currentChannel ?: continue
                val playbackInfo = currentPlaybackInfo ?: continue
                val nextRefreshAt = nextStreamRefreshAtMillis ?: continue
                val isTrackedStream = playbackInfo.resolvedBy.startsWith("douyu-")
                if (!isTrackedStream || refreshInProgress) continue
                if (currentChannel?.id != channel.id || isFinishing || isDestroyed) continue
                if (System.currentTimeMillis() >= nextRefreshAt) {
                    refreshInProgress = true
                    Log.d(TAG, "Watchdog forcing stream refresh for channel=${channel.name}")
                    playChannel(
                        channel,
                        isRetry = true,
                        forcePlayerRecreate = false,
                        isProactiveRefresh = true,
                        seamlessRetry = true
                    )
                }
            }
        }
    }

    private fun hideUi() {
        if (viewModel.selectorVisible.value || actionMenuVisible) return
        binding.controlPanel.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE
        updatePersistentChannelName()
        isUiVisible = false
    }

    private fun showPlaybackChrome() {
        if (phone) { mobileUi?.showControls(); return }
        closeActionMenu()
        viewModel.closeSelector()
        // The full quick dock is exclusively opened by MENU/BACK/long OK.
        // A simple tap or source switch only reveals the lightweight info bar.
        binding.controlPanel.visibility = View.GONE
        binding.infoPanel.visibility = View.VISIBLE
        updatePersistentChannelName()
        isUiVisible = true
        scheduleUiHide()
    }

    private fun openSelectorFromInput() {
        if (phone) { showMobileChannelPicker(); return }
        suspendAutomaticRecoveryForUserInteraction("打开频道列表")
        closeActionMenu()
        binding.controlPanel.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE
        viewModel.openSelector(currentChannel)
        updatePersistentChannelName()
        isUiVisible = false
        cancelHideUi()
        cancelBufferingRecovery()
    }

    private fun openActionMenu() {
        if (phone) { mobileUi?.showControls(); return }
        PlaybackControls.snapshot(this)?.let(::applyUserUiSnapshot)
        suspendAutomaticRecoveryForUserInteraction("打开操作菜单")
        viewModel.closeSelector()
        showPrimaryQuickActions()
        binding.controlPanel.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE
        binding.actionMenuView.visibility = View.VISIBLE
        actionMenuVisible = true
        updatePersistentChannelName()
        binding.quickPlayPauseButton.post { binding.quickPlayPauseButton.requestFocus() }
        cancelHideUi()
        cancelBufferingRecovery()
    }

    private fun closeActionMenu() {
        binding.actionMenuView.visibility = View.GONE
        actionMenuVisible = false
        actionMenuShowingMore = false
        binding.playerRoot.requestFocus()
        updatePersistentChannelName()
        resumeDeferredRecoveryChecks()
    }

    private fun updatePersistentChannelName() {
        updateMobileInfo()
        val channelName = currentChannel?.let(::displayChannelName).orEmpty()
        binding.currentChannelNameBadge.text = channelName
        binding.currentChannelNameBadge.alpha = ChannelUserPolicy.opacity(channelNameTransparency)
        val visible = !inMobilePip() && !pipEntering && showChannelName &&
            channelName.isNotBlank() &&
            binding.infoPanel.visibility != View.VISIBLE &&
            !viewModel.selectorVisible.value &&
            !actionMenuVisible
        binding.currentChannelNameBadge.visibility = if (visible) View.VISIBLE else View.GONE
        adaptiveOverlay?.requestUpdate()
    }

    private fun updateSelectorPaneFocus(groupsFocused: Boolean) {
        binding.groupPane.setBackgroundResource(
            if (groupsFocused) R.drawable.tv_selector_pane_focused else R.drawable.tv_selector_pane_background
        )
        binding.channelPane.setBackgroundResource(
            if (groupsFocused) R.drawable.tv_selector_pane_background else R.drawable.tv_selector_pane_focused
        )
        binding.groupPaneTitle.setTextColor(
            ContextCompat.getColor(this, if (groupsFocused) R.color.tv_focus_color else R.color.text_secondary)
        )
        binding.channelPaneTitle.setTextColor(
            ContextCompat.getColor(this, if (groupsFocused) R.color.text_secondary else R.color.tv_focus_color)
        )
    }

    private fun updateQuickFavoriteButton() {
        if (!::binding.isInitialized || actionMenuShowingMore) return
        binding.quickFavoriteButton.text = if (currentChannelFavorite) "已收藏" else "收藏"
    }

    /**
     * The first row keeps playback controls immediate.  Less frequent actions
     * are deliberately one step deeper so a remote's left/right navigation is
     * predictable and no control is hidden without an obvious route back.
     */
    private fun showPrimaryQuickActions() {
        actionMenuShowingMore = false
        binding.actionMenuTitle.text = "快捷操作"
        binding.quickRemarkButton.visibility = View.VISIBLE
        configureQuickAction(binding.quickRemarkButton, "备注", currentChannel != null) { showChannelRemarkDialog() }
        configureQuickAction(binding.quickPreviousButton, "上一频道") {
            closeActionMenu()
            viewModel.playPreviousChannel()
            revealChannelInfoOverlay()
        }
        configureQuickAction(binding.quickPlayPauseButton, "播放 / 暂停") {
            togglePlayPause()
        }
        configureQuickAction(binding.quickNextButton, "下一频道") {
            closeActionMenu()
            viewModel.playNextChannel()
            revealChannelInfoOverlay()
        }
        val hasAlternativeSource = (currentMergedChannel?.sources?.size ?: 0) > 1
        configureQuickAction(binding.quickSourceButton, "切换来源", hasAlternativeSource) {
            if (viewModel.switchSource(1, currentChannel)) {
                closeActionMenu()
                showStatusHint("已切换到下一来源", STATUS_HINT_DEFAULT_MS)
                revealChannelInfoOverlay()
            } else {
                showStatusHint("当前频道没有可切换的备用来源", STATUS_HINT_DEFAULT_MS)
            }
        }
        configureQuickAction(binding.quickFavoriteButton, if (currentChannelFavorite) "已收藏" else "收藏") {
            toggleCurrentChannelFavorite()
        }
        configureQuickAction(binding.openSettingsMenuButton, "更多") {
            showMoreQuickActions()
        }
    }

    private fun showMoreQuickActions() {
        actionMenuShowingMore = true
        binding.actionMenuTitle.text = "更多操作"
        binding.quickRemarkButton.visibility = View.GONE
        configureQuickAction(binding.quickPreviousButton, "返回快捷操作") {
            showPrimaryQuickActions()
            binding.quickPlayPauseButton.requestFocus()
        }
        configureQuickAction(binding.quickPlayPauseButton, "系统设置") {
            closeActionMenu()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        configureQuickAction(binding.quickNextButton, "订阅源") {
            closeActionMenu()
            startActivity(Intent(this, SubscriptionManagementActivity::class.java))
        }
        configureQuickAction(binding.quickSourceButton, "我的收藏") {
            if (viewModel.openFavoritesSelector()) {
                closeActionMenu()
                // closeActionMenu restores focus to the player root.  The
                // visible selector will then receive remote navigation via
                // PlayerActivity's regular drawer key handling.
                showStatusHint("我的收藏", STATUS_HINT_DEFAULT_MS)
            } else {
                showStatusHint("还没有收藏频道，请在播放菜单中选择“收藏”", STATUS_HINT_DEFAULT_MS)
            }
        }
        configureQuickAction(binding.quickFavoriteButton, "画面设置") {
            closeActionMenu()
            showVideoSettingsDialog()
        }
        configureQuickAction(binding.openSettingsMenuButton, "退出应用") {
            closeActionMenu()
            showExitConfirmDialog()
        }
        binding.quickPlayPauseButton.post { binding.quickPlayPauseButton.requestFocus() }
    }

    private fun configureQuickAction(
        button: Button,
        label: String,
        enabled: Boolean = true,
        action: () -> Unit
    ) {
        button.text = label
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.45f
        button.setOnClickListener { action() }
    }

    private fun showChannelRemarkDialog() {
        val channel = currentChannel ?: return
        val url = channel.url.trim()
        val input = android.widget.EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
            setText(channelAliases[url].orEmpty())
            hint = channel.name
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            selectAll()
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("频道备注")
            .setMessage("原名称：${channel.name}\n备注与当前来源 URL 绑定；留空保存可恢复原名称。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除备注", null)
            .setPositiveButton("保存", null)
            .create()
        TvDialogStyler.apply(dialog)
        dialog.show()
        fun save(value: String) {
            val snapshot = PlaybackControls.setAlias(this, url, value)
            if (snapshot == null) { showToast("保存失败，请重试（备注最多 500 条）"); return }
            applyUserUiSnapshot(snapshot)
            showToast(if (value.isBlank()) "已恢复原频道名称" else "频道备注已保存")
            dialog.dismiss()
        }
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { save(input.text.toString()) }
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { save("") }
        input.isFocusableInTouchMode = true
        // The dialog's focus styling runs from its queued onShow callback.
        // Request input focus afterwards so it cannot be stolen by Cancel.
        input.post { input.requestFocus() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun toggleCurrentChannelFavorite() {
        val channel = currentChannel
        if (channel == null || channel.id <= 0L) {
            showStatusHint("当前频道暂不能收藏", STATUS_HINT_DEFAULT_MS)
            return
        }
        val newValue = !currentChannelFavorite
        lifecycleScope.launch {
            // A merged channel can have several interchangeable playlist
            // sources.  Favourite the logical station as a whole, otherwise
            // it could disappear from "我的收藏" after a source failover.
            val sourceIds = currentMergedChannel?.sources
                ?.map { it.channel.id }
                ?.filter { it > 0L }
                ?.distinct()
                .orEmpty()
                .ifEmpty { listOf(channel.id) }
            database.channelDao().updateFavoriteStatusForChannels(sourceIds, newValue)
            currentChannelFavorite = newValue
            updateQuickFavoriteButton()
            viewModel.loadChannelList()
            showStatusHint(if (newValue) "已加入收藏" else "已取消收藏", STATUS_HINT_DEFAULT_MS)
        }
    }

    private fun showExitConfirmDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("退出应用")
            .setMessage("确定要退出电视直播吗？")
            .setPositiveButton("退出") { _, _ ->
                finishAffinity()
            }
            .setNegativeButton("取消", null)
            .create()
        TvDialogStyler.apply(dialog)
        dialog.show()
    }

    private fun showUnavailableChannelActions(): Boolean {
        if (viewModel.selectorPane.value != PlayerViewModel.SelectorPane.CHANNELS) return false
        val selected = viewModel.selectedVisibleChannel() ?: return false
        if (selected.isAvailable) return false

        val errorText = selected.sources
            .mapNotNull { source -> source.errorText?.takeIf { it.isNotBlank() } }
            .distinct()
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
            ?: "最近一次检测未通过"
        val sourceCount = selected.sources.size
        val dialog = AlertDialog.Builder(this)
            .setTitle("频道不可用")
            .setMessage(
                "${selected.name}\n" +
                    "来源数：$sourceCount\n" +
                    "原因：$errorText\n\n" +
                    "可以重新检测该频道的全部来源。检测成功后，频道会恢复为可播放状态。"
            )
            .setPositiveButton("重新检测") { _, _ ->
                if (viewModel.retrySelectedChannelProbe()) {
                    showStatusHint("正在重新检测 ${selected.name} 的全部来源", STATUS_HINT_LONG_MS)
                } else {
                    showStatusHint("检测任务正在进行，请稍后再试", STATUS_HINT_LONG_MS)
                }
            }
            .setNegativeButton("取消", null)
            .create()
        TvDialogStyler.apply(dialog, initialButton = android.content.DialogInterface.BUTTON_POSITIVE)
        dialog.show()
        return true
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun resolveInstalledVersionName(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return packageInfo.versionName ?: "unknown"
    }

    private fun loadIntentData() {
        val channelId = intent.getLongExtra(Constants.EXTRA_CHANNEL_ID, -1L)
        val channelName = intent.getStringExtra(Constants.EXTRA_CHANNEL_NAME)
        val channelUrl = intent.getStringExtra(Constants.EXTRA_CHANNEL_URL)

        when {
            channelId > 0L -> {
                // MainActivity may hand us a valid URL while the main process
                // is still importing or refreshing its source database.  A
                // low-end TV must not remain on an empty player just because
                // the first catalogue read raced that write transaction.
                val fallback = if (!channelName.isNullOrBlank() && !channelUrl.isNullOrBlank()) {
                    Channel.create(channelName, channelUrl, -1L).copy(id = channelId)
                } else {
                    null
                }
                viewModel.loadStartupChannel(channelId, fallback)
            }
            channelName != null && channelUrl != null -> {
                playChannel(Channel.create(channelName, channelUrl, -1L))
                // A direct external URL is not part of the local catalogue;
                // load the catalogue only for this path.
                viewModel.loadChannelList()
            }
            else -> viewModel.loadStartupChannel()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (phone) return super.dispatchTouchEvent(ev)
        handleTouchTracking(ev)

        if (ev.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE &&
            ev.actionMasked == MotionEvent.ACTION_UP
        ) {
            if (ev.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
                openActionMenu()
                return true
            }
            if (ev.buttonState and MotionEvent.BUTTON_PRIMARY != 0 && handlePrimaryTap(ev.rawX, ev.rawY)) {
                return true
            }
        }

        if (ev.actionMasked == MotionEvent.ACTION_UP &&
            !swipeHandled &&
            !longPressTriggered &&
            isTap(ev)
        ) {
            if (handlePrimaryTap(ev.rawX, ev.rawY)) {
                return true
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE &&
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
        ) {
            val actionButton = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                event.actionButton
            } else {
                event.buttonState
            }
            when (actionButton) {
                MotionEvent.BUTTON_PRIMARY -> {
                    if (handlePrimaryTap(event.rawX, event.rawY)) {
                        return true
                    }
                }
                MotionEvent.BUTTON_SECONDARY -> {
                    openActionMenu()
                    return true
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (phone) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (mobileFullscreen) handleMobileAction("normal") else finish()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
            return super.dispatchKeyEvent(event)
        }
        if (!actionMenuVisible &&
            !viewModel.selectorVisible.value &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            return handleRemoteCenterKey(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        if (actionMenuVisible) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_MENU -> {
                    closeActionMenu()
                    true
                }
                // The dock is horizontal. Consume vertical navigation rather
                // than sending focus to hidden playback chrome underneath it.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> super.dispatchKeyEvent(event)
            }
        }

        if (viewModel.selectorVisible.value) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    viewModel.moveSelectionUp()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    viewModel.moveSelectionDown()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    viewModel.focusGroupsPane()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!showUnavailableChannelActions()) {
                        viewModel.focusChannelsPane()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    viewModel.confirmSelection()
                    true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_MENU -> {
                    viewModel.closeSelector()
                    openActionMenu()
                    true
                }
                else -> super.dispatchKeyEvent(event)
            }
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                viewModel.playPreviousChannel()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                viewModel.playNextChannel()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if ((currentMergedChannel?.sources?.size ?: 0) > 1) {
                    viewModel.switchSource(-1)
                    showPlaybackChrome()
                    true
                } else {
                    super.dispatchKeyEvent(event)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if ((currentMergedChannel?.sources?.size ?: 0) > 1) {
                    viewModel.switchSource(1)
                    showPlaybackChrome()
                    true
                } else {
                    super.dispatchKeyEvent(event)
                }
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_B -> {
                openActionMenu()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                viewModel.playPreviousChannel()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                viewModel.playNextChannel()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun handleRemoteCenterKey(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    remoteCenterLongPressTriggered = false
                    binding.playerRoot.removeCallbacks(remoteCenterLongPressRunnable)
                    binding.playerRoot.postDelayed(remoteCenterLongPressRunnable, longPressTimeout)
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                binding.playerRoot.removeCallbacks(remoteCenterLongPressRunnable)
                if (!remoteCenterLongPressTriggered) {
                    openSelectorFromInput()
                }
                true
            }
            else -> true
        }
    }

    override fun onResume() {
        super.onResume()
        applyMobileWindowMode()
        if (timedExitRequested) { finishAndRemoveTask(); return }
        reloadPlaybackProtectionSettings()
        cornerOverlay.start(showCurrentTime, showVersionBadge, sleepDeadlineMs)
        if (currentChannelSettingsKey.isNotBlank()) {
            applyResizeMode(preferences.getChannelPlaybackSettings(currentChannelSettingsKey, defaultResizeMode).resizeMode)
        }
        ensureLivePlaybackLogServer()
        App.setPlaybackActive(true)
        // Subscription Management runs in the main process while playback is
        // isolated.  Reloading here makes newly added/enabled sources appear
        // in the selector as soon as the user returns to the player.
        viewModel.loadChannelList()
        if (player == null) {
            setupPlayer()
            currentChannel?.let { playChannel(it) }
        }
        if (!pipReturning) player?.playWhenReady = true
        pipReturning = false
        standbyPlayer?.playWhenReady = standbyPlaybackInfo != null
        updatePersistentChannelName()
        scheduleUiHide()
    }

    override fun onPause() {
        mobileUi?.stop()
        if (PlayerWindowPolicy.keepPlayingOnPause(!phone, inMobilePip(), pipEntering, isFinishing)) {
            super.onPause()
            return
        }
        cornerOverlay.stop()
        historyCommitJob?.cancel()
        historyCommitJob = null
        super.onPause()
        App.setPlaybackActive(false)
        viewModel.savePlaybackPosition(player?.currentPosition ?: 0L)
        player?.playWhenReady = false
        standbyPlayer?.playWhenReady = false
        cancelHideUi()
        cancelBufferingRecovery()
        binding.playerRoot.removeCallbacks(longPressRunnable)
        binding.playerRoot.removeCallbacks(remoteCenterLongPressRunnable)
        binding.playerRoot.removeCallbacks(delayedBufferingIndicatorRunnable)
    }

    override fun onStop() {
        super.onStop()
        pipEntering = false
        App.setPlaybackActive(false)
        // Keep the playback marker when the system unexpectedly backgrounds or
        // destroys this activity.  If the process died in native MediaCodec,
        // the next launch can then report a useful suspected-crash event.
        if (isFinishing) {
            DebugReporter.endPlaybackSession()
        }
        releasePlayer()
    }

    override fun onDestroy() {
        adaptiveOverlay?.dispose()
        adaptiveOverlay = null
        mobileUi?.stop()
        if (pipReceiverRegistered) { unregisterReceiver(pipReceiver); pipReceiverRegistered = false }
        super.onDestroy()
        App.setPlaybackActive(false)
        deferredRecoveryRunnable?.let { binding.playerRoot.removeCallbacks(it) }
        deferredRecoveryRunnable = null
        clearDeferredPlaybackError("activity destroyed")
        epgJob?.cancel()
        cleanupResources()
    }

    private fun releasePlayer(cancelPlaybackWork: Boolean = true) {
        historyCommitJob?.cancel()
        historyCommitJob = null
        historyFirstFrameMediaId = null
        huyaMissingAudioRetryJob?.cancel()
        huyaMissingAudioRetryJob = null
        resetHuyaShortStreamTracking()
        if (cancelPlaybackWork) {
            playbackJob?.cancel()
            retryJob?.cancel()
        }
        cancelPersistentSourceRetry()
        streamRefreshJob?.cancel()
        douyuContinuityJob?.cancel()
        douyuContinuityJob = null
        douyuProtectionRetryJob?.cancel()
        douyuProtectionRetryJob = null
        huyaPrewarmJob?.cancel()
        huyaPrewarmJob = null
        huyaRenewalFallbackJob?.cancel()
        huyaRenewalFallbackJob = null
        clearPreconnectedHuyaDataSource()
        discardQueuedHuyaRenewal()
        huyaRecoveryJob?.cancel()
        huyaRecoveryJob = null
        resetLegacyHlsRecoveryState()
        huyaStandbyPrepareJob?.cancel()
        huyaStandbySwapJob?.cancel()
        streamRefreshWatchdogJob?.cancel()
        streamRefreshWatchdogJob = null
        playbackProgressWatchdogJob?.cancel()
        playbackProgressWatchdogJob = null
        retryStabilityJob?.cancel()
        retryStabilityJob = null
        douyuContinuityAttempt = 0
        douyuContinuityChannelId = -1L
        nextStreamRefreshAtMillis = null
        refreshInProgress = false
        standbyPlaybackInfo = null
        activePlaybackStartedAtMs = 0L
        activeMediaSourceStartedAtMs = 0L
        mediaSourceGeneration = if (mediaSourceGeneration == Long.MAX_VALUE) 0L else mediaSourceGeneration + 1L
        activeVideoTrackCount = 0
        activeVideoSizeSeenForSource = false
        lastRenderedVideoFrameRealtimeMs = 0L
        lastVideoDecoderOutputRealtimeMs = 0L
        lastVideoDecoderRenderedBufferCount = null
        videoFrameMonitoringStartedAtMs = 0L
        lastVideoFrameDiagnosticAtMs = 0L
        playerView.player = null
        player?.clearVideoSurface()
        player?.release()
        player = null
        releaseStandbyPlayer()
    }

    private fun cleanupResources() {
        hideUiRunnable?.let { binding.playerRoot.removeCallbacks(it) }
        binding.playerRoot.removeCallbacks(longPressRunnable)
        binding.playerRoot.removeCallbacks(delayedBufferingIndicatorRunnable)
        cancelBufferingRecovery()
        streamRefreshWatchdogJob?.cancel()
        releasePlayer()
    }

    private fun prepareChannelPlaybackSettings(channel: Channel) {
        val channelKey = currentMergedChannel?.key.ifNullOrBlank { Channel.normalizeName(channel.name) }
        currentChannelSettingsKey = channelKey
        val settings = preferences.getChannelPlaybackSettings(channelKey, defaultResizeMode)
        applyResizeMode(settings.resizeMode)
        currentHuyaScheme = settings.huyaScheme
        currentDouyuScheme = settings.douyuScheme
        val effectiveDecoderMode = when {
            settings.decoderMode != PreferencesHelper.CHANNEL_DECODER_DEFAULT -> settings.decoderMode
            shouldPreferCompatibilityDecoder(channel) -> PreferencesHelper.CHANNEL_DECODER_COMPATIBILITY
            else -> PreferencesHelper.CHANNEL_DECODER_DEFAULT
        }
        if (effectiveDecoderMode != currentDecoderMode) {
            Log.d(TAG, "Switching decoder mode for channel=${channel.name} to $effectiveDecoderMode")
            currentDecoderMode = effectiveDecoderMode
            recreatePlayer()
        }
    }

    private fun shouldPreferCompatibilityDecoder(channel: Channel): Boolean {
        return when {
            HuyaStreamResolver.supports(channel.url) -> !DeviceProfile.isLegacyTv(this)
            DouyuStreamResolver.supports(channel.url) -> !DeviceProfile.isLegacyTv(this)
            else -> false
        }
    }

    private fun recreatePlayer() {
        // A playback/retry coroutine may be the caller.  Cancelling it from
        // releasePlayer used to abort the source assignment after codec release.
        releasePlayer(cancelPlaybackWork = false)
        setupPlayer(currentDecoderMode)
        playerView.player = player
        standbyPlayerView.player = standbyPlayer
    }

    /**
     * MStar can retain the prior channel's video-plane geometry even after a
     * decoder recreation. Alternate the two existing PlayerViews on manual
     * channel boundaries; the inactive SurfaceView is GONE so Android
     * destroys its old Surface and creates a fresh one when it becomes active.
     */
    private fun rotateLegacySurfaceForChannelSwitch(): Boolean {
        if (!DeviceProfile.isLegacyTv(this) || !::playerView.isInitialized) return false
        val activePlayer = player ?: return false
        val previousView = playerView
        val freshView = standbyPlayerView
        if (freshView === previousView || freshView.parent !== binding.playerRoot) return false

        previousView.player = null
        previousView.setKeepContentOnPlayerReset(false)
        previousView.alpha = 0f
        previousView.visibility = View.GONE

        freshView.player = null
        freshView.setKeepContentOnPlayerReset(false)
        freshView.resizeMode = currentResizeMode
        freshView.alpha = 1f
        freshView.visibility = View.VISIBLE

        playerView = freshView
        standbyPlayerView = previousView
        adaptiveOverlay?.requestUpdate()
        standbyPlayerView.visibility = View.GONE
        playerView.player = activePlayer
        playerView.requestLayout()
        // Let the new Surface finish creation first. Video-size/first-frame
        // callbacks will repair measured geometry if it is actually wrong.
        DebugReporter.recordLivePlaybackEvent(
            "legacy_channel_surface_rotated",
            currentChannel,
            mapOf(
                "previousPlayerViewId" to System.identityHashCode(previousView),
                "activePlayerViewId" to System.identityHashCode(freshView),
                "oldSurfaceHidden" to true,
                "decoderRecreated" to true
            )
        )
        return true
    }

    private fun applyResizeMode(resizeMode: Int) {
        if (currentResizeMode == resizeMode &&
            playerView.resizeMode == resizeMode &&
            standbyPlayerView.resizeMode == resizeMode
        ) return
        currentResizeMode = resizeMode
        playerView.resizeMode = resizeMode
        standbyPlayerView.resizeMode = resizeMode
        // AspectRatioFrameLayout handles this layout-only change. Detaching
        // the Surface here on every retry creates an unnecessary ACodec flush
        // on MStar and can crash Android 6 Stagefright.
        playerView.requestLayout()
        standbyPlayerView.requestLayout()
        playerView.post {
            if (currentResizeMode == resizeMode) forceVideoSurfaceLayout(playerView)
        }
    }

    private fun resetActiveVideoSurfaceForSourceSwitch() {
        val activePlayer = player ?: return
        playerView.setKeepContentOnPlayerReset(false)
        playerView.player = null
        activePlayer.clearVideoSurface()
        forceVideoSurfaceLayout(playerView)
        playerView.resizeMode = currentResizeMode
        playerView.setKeepContentOnPlayerReset(true)
        playerView.player = activePlayer
        playerView.requestLayout()
        playerView.post { forceVideoSurfaceLayout(playerView) }
    }

    private fun videoSurfaceGeometryDetails(): Map<String, Any?> {
        val surface = playerView.videoSurfaceView as? SurfaceView
        val frame = runCatching { surface?.holder?.surfaceFrame }.getOrNull()
        return mapOf(
            "playerViewWidth" to playerView.width,
            "playerViewHeight" to playerView.height,
            "surfaceWidth" to (surface?.width ?: 0),
            "surfaceHeight" to (surface?.height ?: 0),
            "surfaceFrameWidth" to (frame?.width() ?: 0),
            "surfaceFrameHeight" to (frame?.height() ?: 0)
        )
    }

    /**
     * MStar's video plane can retain stale buffer geometry after a source
     * change. Inspect the measured Surface bounds, but only reassert layout if
     * the actual buffer dimensions differ; a normal first frame needs no work.
     */
    private fun requestLegacySurfaceLayoutRepair(reason: String) {
        if (!DeviceProfile.isLegacyTv(this) || !::playerView.isInitialized) return
        val sourceGeneration = mediaSourceGeneration
        val channelId = currentChannel?.id ?: -1L
        playerView.post {
            applyLegacySurfaceLayoutRepair(reason, "immediate", sourceGeneration, channelId)
        }
    }

    private fun applyLegacySurfaceLayoutRepair(
        reason: String,
        pass: String,
        sourceGeneration: Long,
        channelId: Long
    ) {
        if (isDestroyed || !::playerView.isInitialized) return
        if (!isCurrentSurfaceRepairRequest(
                requestGeneration = sourceGeneration,
                activeGeneration = mediaSourceGeneration,
                requestChannelId = channelId,
                activeChannelId = currentChannel?.id ?: -1L
            )
        ) return
        val surface = playerView.videoSurfaceView as? SurfaceView
        val params = surface?.layoutParams
        val frame = runCatching { surface?.holder?.surfaceFrame }.getOrNull()
        val frameWidth = frame?.width() ?: 0
        val frameHeight = frame?.height() ?: 0
        val videoSize = player?.videoSize
        if (surface != null && !hasSurfaceFrameGeometry(frameWidth, frameHeight)) {
            DebugReporter.recordLivePlaybackEvent(
                "video_surface_layout_repair",
                currentChannel,
                videoSurfaceGeometryDetails() + mapOf(
                    "reason" to reason,
                    "pass" to pass,
                    "repairApplied" to false,
                    "deferred" to true,
                    "skipReason" to "surface_frame_not_ready"
                )
            )
            if (pass == "immediate") {
                playerView.postDelayed({
                    applyLegacySurfaceLayoutRepair(
                        reason,
                        "after_surface_create",
                        sourceGeneration,
                        channelId
                    )
                }, LEGACY_SURFACE_REPAIR_RETRY_DELAY_MS)
            }
            return
        }
        val layoutMismatch = params?.let {
            it.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                it.height != ViewGroup.LayoutParams.MATCH_PARENT
        } == true
        val geometryMismatch = surface != null && surfaceFrameGeometryNeedsRepair(
            frameWidth = frame?.width() ?: 0,
            frameHeight = frame?.height() ?: 0,
            surfaceWidth = surface.width,
            surfaceHeight = surface.height
        )
        if (!layoutMismatch && !geometryMismatch) return
        playerView.requestLayout()
        forceVideoSurfaceLayout(playerView)
        playerView.invalidate()
        DebugReporter.recordLivePlaybackEvent(
            "video_surface_layout_repair",
            currentChannel,
            videoSurfaceGeometryDetails() + mapOf(
                "reason" to reason,
                "pass" to pass,
                "repairApplied" to true,
                "layoutMismatch" to layoutMismatch,
                "geometryMismatch" to geometryMismatch,
                "videoWidth" to videoSize?.width,
                "videoHeight" to videoSize?.height,
                "videoPixelRatio" to videoSize?.pixelWidthHeightRatio
            )
        )
    }

    /**
     * MStar's SurfaceFlinger occasionally keeps the previous buffer size when
     * an IPTV source changes resolution.  ExoPlayer still reports audio and
     * playback state correctly, but the old frame is then rendered in a narrow
     * strip on the left.  Ask the holder to use the current parent layout after
     * every source rebind; this is harmless on non-SurfaceView configurations.
     */
    private fun forceVideoSurfaceLayout(view: PlayerView) {
        val surface = view.videoSurfaceView as? SurfaceView ?: return
        val params = surface.layoutParams ?: return
        if (params.width != ViewGroup.LayoutParams.MATCH_PARENT ||
            params.height != ViewGroup.LayoutParams.MATCH_PARENT
        ) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            surface.layoutParams = params
        }
        surface.holder.setSizeFromLayout()
        surface.requestLayout()
    }

    private fun showVideoSettingsDialog() {
        if (currentChannel == null) {
            showToast("当前没有可设置的频道")
            return
        }
        val channel = currentChannel ?: return
        val options = buildList {
            add("画面比例")
            add("解码模式")
            if (HuyaStreamResolver.supports(channel.url)) {
                add("虎牙视频方案")
            }
            if (DouyuStreamResolver.supports(channel.url)) {
                add("斗鱼视频方案")
            }
            add("恢复当前频道默认")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("当前频道画面设置")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "画面比例" -> showResizeModeDialog()
                    "解码模式" -> showDecoderModeDialog()
                    "虎牙视频方案" -> showHuyaSchemeDialog()
                    "斗鱼视频方案" -> showDouyuSchemeDialog()
                    "恢复当前频道默认" -> resetChannelPlaybackSettings()
                }
            }
            .show()
    }

    private fun showResizeModeDialog() {
        val items = arrayOf("完整显示", "裁切铺满", "拉伸铺满")
        val values = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        val currentIndex = values.indexOf(currentResizeMode).takeIf { it >= 0 } ?: 2
        AlertDialog.Builder(this)
            .setTitle("画面比例")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                preferences.setChannelResizeMode(currentChannelSettingsKey, values[which])
                applyResizeMode(values[which])
                showToast("已保存当前频道画面比例")
                dialog.dismiss()
            }
            .show()
    }

    private fun showDecoderModeDialog() {
        val items = arrayOf("默认解码", "兼容优先")
        val values = arrayOf(
            PreferencesHelper.CHANNEL_DECODER_DEFAULT,
            PreferencesHelper.CHANNEL_DECODER_COMPATIBILITY
        )
        val currentIndex = values.indexOf(currentDecoderMode).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("解码模式")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                preferences.setChannelDecoderMode(currentChannelSettingsKey, values[which])
                currentDecoderMode = values[which]
                currentChannel?.let { playChannel(it) }
                showToast("已保存当前频道解码模式")
                dialog.dismiss()
            }
            .show()
    }

    private fun resetChannelPlaybackSettings() {
        preferences.clearChannelPlaybackOverrides(currentChannelSettingsKey)
        currentDecoderMode = PreferencesHelper.CHANNEL_DECODER_DEFAULT
        currentHuyaScheme = preferences.huyaPlaybackScheme
        currentDouyuScheme = preferences.douyuPlaybackScheme
        applyResizeMode(defaultResizeMode)
        currentChannel?.let { playChannel(it) }
        showToast("已恢复当前频道默认设置")
    }

    private fun showHuyaSchemeDialog() {
        val items = resources.getStringArray(R.array.huya_scheme_entries)
        val values = resources.getStringArray(R.array.huya_scheme_values)
        val currentIndex = values.indexOf(currentHuyaScheme).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("虎牙视频方案")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                currentHuyaScheme = values[which]
                preferences.setChannelHuyaScheme(currentChannelSettingsKey, currentHuyaScheme)
                currentChannel?.let { playChannel(it) }
                showToast("已保存当前频道虎牙方案")
                dialog.dismiss()
            }
            .show()
    }

    private fun showDouyuSchemeDialog() {
        val items = resources.getStringArray(R.array.douyu_scheme_entries)
        val values = resources.getStringArray(R.array.douyu_scheme_values)
        val currentIndex = values.indexOf(currentDouyuScheme).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("斗鱼视频方案")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                currentDouyuScheme = values[which]
                preferences.setChannelDouyuScheme(currentChannelSettingsKey, currentDouyuScheme)
                currentChannel?.let { playChannel(it) }
                showToast("已保存当前频道斗鱼方案")
                dialog.dismiss()
            }
            .show()
    }

    private fun shouldDeferAutomaticRecovery(): Boolean {
        return viewModel.selectorVisible.value ||
            actionMenuVisible ||
            System.currentTimeMillis() < automaticRecoverySuspendedUntilMs
    }

    private fun resumeDeferredRecoveryChecks() {
        deferredRecoveryRunnable?.let { binding.playerRoot.removeCallbacks(it) }
        val remainingDelayMs = automaticRecoverySuspendedUntilMs - System.currentTimeMillis()
        val userInterfaceOpen = viewModel.selectorVisible.value || actionMenuVisible
        if (remainingDelayMs > 0L || userInterfaceOpen) {
            val nextCheckDelayMs = if (remainingDelayMs > 0L) remainingDelayMs else 500L
            deferredRecoveryRunnable = Runnable {
                deferredRecoveryRunnable = null
                resumeDeferredRecoveryChecks()
            }
            binding.playerRoot.postDelayed(deferredRecoveryRunnable!!, nextCheckDelayMs)
            return
        }
        val pendingError = deferredPlaybackError
        scheduleHuyaMissingAudioRecovery()
        val pendingChannelId = deferredPlaybackErrorChannelId
        val dispatchPendingError = shouldDispatchDeferredPlaybackError(
            hasPendingError = pendingError != null,
            sameChannel = pendingChannelId == currentChannel?.id,
            userInterfaceOpen = userInterfaceOpen,
            interactionCooldownExpired = true
        )
        if (dispatchPendingError && pendingError != null) {
            clearDeferredPlaybackError("dispatch after controls closed")
            Log.i(TAG, "Resuming deferred playback recovery for channel=${currentChannel?.name}")
            handlePlayerError(pendingError)
            return
        }
        if (pendingError != null) {
            clearDeferredPlaybackError("pending error no longer matches current channel")
            return
        }
        val pendingPreparationError = deferredPlaybackPreparationError
        val pendingPreparationChannelId = deferredPlaybackPreparationChannelId
        if (pendingPreparationError != null) {
            val dispatchPendingPreparationError = shouldDispatchDeferredPlaybackError(
                hasPendingError = true,
                sameChannel = pendingPreparationChannelId == currentChannel?.id,
                userInterfaceOpen = userInterfaceOpen,
                interactionCooldownExpired = true
            )
            if (dispatchPendingPreparationError) {
                deferredPlaybackPreparationError = null
                deferredPlaybackPreparationChannelId = -1L
                Log.i(TAG, "Resuming deferred preparation recovery for channel=${currentChannel?.name}")
                handlePlaybackPreparationFailure(pendingPreparationError)
                return
            }
            clearDeferredPlaybackError("pending preparation failure no longer matches current channel")
            return
        }
        val pendingEndedChannelId = deferredPlaybackEndedChannelId
        val dispatchPendingEnd = shouldDispatchDeferredPlaybackError(
            hasPendingError = pendingEndedChannelId != -1L,
            sameChannel = pendingEndedChannelId == currentChannel?.id,
            userInterfaceOpen = userInterfaceOpen,
            interactionCooldownExpired = true
        ) && player?.playbackState == Player.STATE_ENDED
        if (dispatchPendingEnd) {
            deferredPlaybackEndedChannelId = -1L
            Log.i(TAG, "Resuming deferred end-of-stream recovery for channel=${currentChannel?.name}")
            onPlaybackEnded()
            return
        }
        if (pendingEndedChannelId != -1L) {
            Log.d(TAG, "Clearing deferred playback-ended recovery for a changed channel/state")
            deferredPlaybackEndedChannelId = -1L
        }
        if (player?.playbackState == Player.STATE_BUFFERING) {
            scheduleBufferingRecovery()
        } else {
            notePlaybackProgress(force = true)
        }
    }

    private fun clearDeferredPlaybackError(reason: String) {
        if (deferredPlaybackError != null || deferredPlaybackPreparationError != null || deferredPlaybackEndedChannelId != -1L) {
            Log.d(TAG, "Clearing deferred playback recovery: $reason")
        }
        deferredPlaybackError = null
        deferredPlaybackErrorChannelId = -1L
        deferredPlaybackPreparationError = null
        deferredPlaybackPreparationChannelId = -1L
        deferredPlaybackEndedChannelId = -1L
    }

    private fun String?.ifNullOrBlank(fallback: () -> String): String {
        return if (this.isNullOrBlank()) fallback() else this
    }

    private fun handleTouchTracking(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downTimeMs = event.eventTime
                swipeHandled = false
                longPressTriggered = false
                binding.playerRoot.removeCallbacks(longPressRunnable)
                if (!isPointInsideVisiblePanel(event.rawX, event.rawY)) {
                    binding.playerRoot.postDelayed(longPressRunnable, longPressTimeout)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
                if (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop) {
                    binding.playerRoot.removeCallbacks(longPressRunnable)
                }

                if (!swipeHandled &&
                    !viewModel.selectorVisible.value &&
                    !actionMenuVisible &&
                    kotlin.math.abs(deltaY) > swipeThreshold &&
                    kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * 1.3f
                ) {
                    swipeHandled = true
                    if (deltaY < 0) {
                        viewModel.playPreviousChannel()
                    } else {
                        viewModel.playNextChannel()
                    }
                    revealChannelInfoOverlay()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                binding.playerRoot.removeCallbacks(longPressRunnable)
            }
        }
    }

    private fun handlePrimaryTap(rawX: Float, rawY: Float): Boolean {
        if (viewModel.selectorVisible.value) {
            return if (isPointInsideView(rawX, rawY, binding.channelListView)) {
                false
            } else {
                viewModel.closeSelector()
                updatePersistentChannelName()
                true
            }
        }

        if (actionMenuVisible) {
            return if (isPointInsideView(rawX, rawY, binding.actionMenuView)) {
                false
            } else {
                closeActionMenu()
                true
            }
        }

        if (isPointInsideView(rawX, rawY, binding.controlPanel) || isPointInsideView(rawX, rawY, binding.infoPanel)) {
            return false
        }

        val width = binding.playerRoot.width.toFloat().coerceAtLeast(1f)
        return when {
            rawX <= width / 3f -> {
                openSelectorFromInput()
                true
            }
            rawX >= width * 0.55f -> {
                openActionMenu()
                true
            }
            else -> {
                showPlaybackChrome()
                true
            }
        }
    }

    private fun isTap(event: MotionEvent): Boolean {
        val deltaX = kotlin.math.abs(event.rawX - downRawX)
        val deltaY = kotlin.math.abs(event.rawY - downRawY)
        val duration = event.eventTime - downTimeMs
        return deltaX <= touchSlop && deltaY <= touchSlop && duration <= longPressTimeout + tapTimeout
    }

    private fun isPointInsideVisiblePanel(rawX: Float, rawY: Float): Boolean {
        return isPointInsideView(rawX, rawY, binding.channelListView) ||
            isPointInsideView(rawX, rawY, binding.actionMenuView) ||
            isPointInsideView(rawX, rawY, binding.controlPanel) ||
            isPointInsideView(rawX, rawY, binding.infoPanel)
    }

    private fun revealChannelInfoOverlay() {
        if (phone) { updateMobileInfo(); return }
        if (viewModel.selectorVisible.value || actionMenuVisible) return
        binding.infoPanel.visibility = View.VISIBLE
        updatePersistentChannelName()
        if (binding.controlPanel.visibility != View.VISIBLE) {
            binding.controlPanel.visibility = View.GONE
        }
        isUiVisible = true
        scheduleUiHide()
    }

    private fun isPointInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height
        )
        return rect.contains(rawX.toInt(), rawY.toInt())
    }

    private fun ensureStandbyPlayer(): ExoPlayer {
        standbyPlayer?.let { return it }
        val created = createTrackedPlayer(currentDecoderMode)
        standbyPlayer = created
        standbyPlayerView.player = created
        standbyPlayerView.alpha = 0f
        standbyPlayerView.resizeMode = currentResizeMode
        return created
    }

    private fun releaseStandbyPlayer() {
        standbyPlayerView.player = null
        standbyPlayer?.playWhenReady = false
        standbyPlayer?.stop()
        standbyPlayer?.clearMediaItems()
        standbyPlayer?.release()
        standbyPlayer = null
        standbyPreconnectedDataSource?.let(::closePreconnectedHuyaDataSourceAsync)
        standbyPreconnectedDataSource = null
        standbyAudioClockAdvanced = false
    }

    private fun suspendAutomaticRecoveryForUserInteraction(trigger: String) {
        retryJob?.cancel()
        refreshInProgress = false
        automaticRecoverySuspendedUntilMs = System.currentTimeMillis() + if (isLowEndTvDevice) {
            LOW_END_TV_RECOVERY_SUSPEND_MS
        } else {
            LOW_END_TV_RECOVERY_RESUME_DELAY_MS
        }
        if (isLowEndTvDevice) {
            showStatusHint("电视低配优化：已暂缓自动恢复，优先保证菜单操作（$trigger）", 2_500L)
        }
    }

    private fun buildDetailedPlaybackErrorMessage(error: Throwable, isPreparation: Boolean): String {
        val root = findRootCause(error)
        val channel = currentChannel?.name ?: "当前频道"
        val prefix = if (isPreparation) "播放准备失败" else "播放中断"
        val detail = when (root) {
            is HttpDataSource.InvalidResponseCodeException -> "HTTP ${root.responseCode}，源站拒绝访问或地址已过期"
            is SocketTimeoutException -> "连接超时，可能是网络慢或源站无响应"
            is UnknownHostException -> "域名解析失败，可能与 DNS / IPv6 / 网络环境有关"
            is IllegalStateException -> root.message ?: "解析或准备阶段失败"
            else -> root.message ?: error.message ?: "未知错误"
        }
        val sourceInfo = currentPlaybackInfo?.resolvedBy?.let { "，方案：$it" }.orEmpty()
        val tvHint = if (isPreparation && isLowEndTvDevice) {
            "。电视端可尝试在“画面设置”中切换兼容解码，或切换 Huya / Douyu 方案"
        } else {
            ""
        }
        return "$prefix：$channel，$detail$sourceInfo$tvHint"
    }

    private fun buildRetryHint(reason: String): String {
        val sourceInfo = currentPlaybackInfo?.resolvedBy?.substringBefore(':')?.uppercase()
            ?: viewModel.currentSource.value?.playlistName
            ?: "当前来源"
        return "$reason，正在重试当前来源 $playbackRetryCount/${maxSameSourceRetries()}（$sourceInfo）"
    }

    private fun buildFallbackHint(prefix: String, error: Throwable): String {
        val currentSource = viewModel.currentSource.value
        val currentIndex = currentMergedChannel?.sources?.indexOfFirst { it.channel.id == currentSource?.channel?.id }
            ?.takeIf { it >= 0 }
            ?.plus(1)
        val total = currentMergedChannel?.sources?.size ?: 0
        val detail = extractPlaybackFailureMessage(error)
        return if (currentIndex != null && total > 1) {
            "$prefix：$detail，正在切换备用来源（$currentIndex/$total）"
        } else {
            "$prefix：$detail，正在尝试恢复"
        }
    }

    private fun showStatusHint(message: String, durationMs: Long = STATUS_HINT_DEFAULT_MS) {
        if (isFinishing || isDestroyed || inMobilePip() || pipEntering) return
        runCatching {
            binding.statusText.text = message
            binding.statusText.visibility = View.VISIBLE
            statusHideRunnable?.let { binding.playerRoot.removeCallbacks(it) }
            statusHideRunnable = Runnable {
                if (!isFinishing && !isDestroyed) {
                    binding.statusText.visibility = View.GONE
                }
            }
            binding.playerRoot.postDelayed(statusHideRunnable!!, durationMs)
        }.onFailure {
            Log.w(TAG, "Failed to show status hint: $message", it)
        }
    }
}
