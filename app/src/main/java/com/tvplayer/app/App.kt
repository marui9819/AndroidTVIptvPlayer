package com.tvplayer.app

import android.app.Application
import android.app.ActivityManager
import android.os.Process
import com.bumptech.glide.Glide
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.repository.ChannelProbeRepository
import com.tvplayer.app.data.repository.EpgRepository
import com.tvplayer.app.data.repository.LogoRepository
import com.tvplayer.app.data.repository.PlaylistRepository
import com.tvplayer.app.data.repository.SubscriptionRepository
import com.tvplayer.app.util.PlayerHelper
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.util.DeviceProfile
import com.tvplayer.app.util.DebugReporter
import com.tvplayer.app.util.LivePlaybackLogServer
import com.tvplayer.app.util.AppSleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

class App : Application() {

    companion object {
        private const val TAG = "App"
        private const val AUTO_REFRESH_RETRY_COUNT = 3
        private const val AUTO_REFRESH_RETRY_DELAY_MS = 60_000L
        lateinit var instance: App
            private set
        private val playbackActive = AtomicBoolean(false)

        fun setPlaybackActive(active: Boolean) {
            playbackActive.set(active)
        }

        fun isPlaybackActive(): Boolean = playbackActive.get()
    }

    val applicationScope = CoroutineScope(SupervisorJob())
    val database by lazy { AppDatabase.getDatabase(this) }
    val preferences by lazy { PreferencesHelper(this) }
    private var refreshLoopJob: Job? = null
    private var epgRefreshLoopJob: Job? = null
    private var channelProbeLoopJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        instance = this

        // Initialize preferences with defaults
        preferences.initializeDefaults()
        DebugReporter.initialize(this)
        AppSleepTimer(this).initialize()

        // A vendor MediaCodec/OMX crash while opening a Huya stream must not
        // take down the launcher/home process.  PlayerActivity lives in this
        // short-lived process; skip every unrelated background loader here.
        if (isPlaybackProcess()) {
            LivePlaybackLogServer.start(this)
                .onSuccess { Log.i(TAG, "Live playback log endpoint ready at ${it.address}") }
                .onFailure { Log.w(TAG, "Could not start live playback log endpoint", it) }
            return
        }

        // Start initial playlist loading
        applicationScope.launch {
            if (preferences.isAutoLoadEnabled) {
                PlayerHelper.loadInitialPlaylist()
            } else {
                SubscriptionRepository(this@App).ensureDefaults()
                LogoRepository(this@App).applyLogosToDatabase(forceRefresh = false)
            }
        }
        // PlayerActivity is isolated in a different process. This main-process
        // loop only updates source data and preserves the old rows when a
        // refresh fails, so playlist refresh also works on TV devices.
        startAutoRefreshLoop()
        if (!DeviceProfile.isTv(this)) {
            startEpgRefreshLoop()
        }
        startChannelProbeLoop()
    }

    private fun startAutoRefreshLoop() {
        refreshLoopJob?.cancel()
        refreshLoopJob = applicationScope.launch {
            val repository = PlaylistRepository(
                database = database,
                logoRepository = LogoRepository(this@App)
            )
            while (isActive) {
                val interval = preferences.refreshInterval.coerceAtLeast(60_000L)
                delay(interval)
                if (!preferences.enableAutoRefresh) {
                    continue
                }
                val now = System.currentTimeMillis()
                val playlists = database.playlistDao().getAllPlaylists().first()
                playlists.forEach { playlist ->
                    if (playlist.shouldRefresh(now, interval)) {
                        refreshPlaylistWithRetry(repository, playlist)
                    }
                }
            }
        }
    }

    private suspend fun refreshPlaylistWithRetry(
        repository: PlaylistRepository,
        playlist: com.tvplayer.app.data.model.Playlist
    ) {
        var lastFailure: Throwable? = null
        repeat(AUTO_REFRESH_RETRY_COUNT + 1) { attempt ->
            val result = repository.refreshPlaylist(playlist)
            if (result.isSuccess) {
                Log.d(TAG, "Auto-refreshed playlist=${playlist.name} attempt=${attempt + 1}")
                return
            }
            lastFailure = result.exceptionOrNull()
            if (attempt < AUTO_REFRESH_RETRY_COUNT) {
                Log.w(
                    TAG,
                    "Playlist refresh failed for ${playlist.name}; retrying in " +
                        "${AUTO_REFRESH_RETRY_DELAY_MS / 1000}s " +
                        "(${attempt + 1}/$AUTO_REFRESH_RETRY_COUNT)",
                    lastFailure
                )
                delay(AUTO_REFRESH_RETRY_DELAY_MS)
            }
        }
        // PlaylistRepository deliberately leaves channel rows untouched on
        // failure and records lastError, which the source-management screen
        // renders as a red warning icon.
        Log.e(TAG, "Playlist auto-refresh exhausted for ${playlist.name}", lastFailure)
    }

    private fun startEpgRefreshLoop() {
        epgRefreshLoopJob?.cancel()
        epgRefreshLoopJob = applicationScope.launch {
            val epgRepository = EpgRepository(this@App, preferences = preferences)
            while (isActive) {
                if (epgRepository.shouldRefresh()) {
                    epgRepository.refreshEpgSources()
                }
                delay(60_000L)
            }
        }
    }

    private fun startChannelProbeLoop() {
        channelProbeLoopJob?.cancel()
        if (DeviceProfile.isTv(this)) {
            // YQK contains hundreds of dynamic rooms. Resolving them in the
            // main process can run concurrently with isolated playback, so TV
            // probing is deliberately user-driven from the selected category
            // or Subscription Management instead.
            return
        }
        channelProbeLoopJob = applicationScope.launch {
            val probeRepository = ChannelProbeRepository(this@App)
            while (isActive) {
                probeRepository.probeAllChannelsIfNeeded()
                delay(60_000L)
            }
        }
    }

    private fun isPlaybackProcess(): Boolean {
        val processName = (getSystemService(ACTIVITY_SERVICE) as? ActivityManager)
            ?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
        return processName == "$packageName:playback"
    }

}
