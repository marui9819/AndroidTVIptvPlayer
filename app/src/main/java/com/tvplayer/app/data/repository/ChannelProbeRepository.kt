package com.tvplayer.app.data.repository

import android.content.Context
import com.tvplayer.app.App
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.util.PreferencesHelper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class ChannelProbeRepository(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context),
    private val networkRepository: com.tvplayer.app.network.NetworkRepository = com.tvplayer.app.network.NetworkRepository(),
    private val preferences: PreferencesHelper = PreferencesHelper(context)
) {
    private val running = AtomicBoolean(false)

    suspend fun probeAllChannelsIfNeeded(force: Boolean = false): Boolean {
        if (!preferences.enableChannelProbeRefresh && !force) return false
        if (!force && App.isPlaybackActive()) return false
        if (running.get()) return false
        val now = System.currentTimeMillis()
        val hasUncheckedChannels = database.channelDao().getActiveUncheckedChannelCount() > 0
        if (!force && !hasUncheckedChannels && now - preferences.channelProbeLastCompletedAt < preferences.channelProbeInterval) {
            return false
        }
        return probeAllChannels(force)
    }

    suspend fun probeAllChannels(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && App.isPlaybackActive()) {
            return@withContext false
        }
        if (!running.compareAndSet(false, true)) {
            return@withContext false
        }
        try {
            // Disabled sources must not use network, CPU or database writes.
            // This also prevents a large YQK list from being probed merely
            // because it is still stored locally.
            val channels = database.channelDao().getActiveChannelsForProbe()
            val semaphore = Semaphore(preferences.channelProbeThreadCount.coerceAtLeast(1))
            coroutineScope {
                channels.map { channel ->
                    async {
                        semaphore.withPermit {
                            if (!force && App.isPlaybackActive()) {
                                return@withPermit
                            }
                            probeChannel(channel)
                            delay(preferences.channelProbeDelayMs)
                        }
                    }
                }.awaitAll()
            }
            preferences.channelProbeLastCompletedAt = System.currentTimeMillis()
            true
        } finally {
            running.set(false)
        }
    }

    suspend fun probeChannel(channel: Channel): Channel = withContext(Dispatchers.IO) {
        val result = networkRepository.probeStream(channel.url, preferences.channelProbeTimeoutMs)
        val checkedAt = System.currentTimeMillis()
        database.channelDao().updateProbeResult(
            channelId = channel.id,
            isAvailable = result.isAvailable,
            error = result.error,
            responseTimeMs = result.responseTimeMs,
            videoWidth = result.videoWidth,
            videoHeight = result.videoHeight,
            checkedAt = checkedAt
        )
        channel.copy(
            isAvailable = result.isAvailable,
            availabilityError = result.error,
            responseTimeMs = result.responseTimeMs,
            videoWidth = result.videoWidth,
            videoHeight = result.videoHeight,
            lastCheckedAt = checkedAt
        )
    }

    /**
     * Explicitly rechecks every source belonging to one logical channel.
     * This is intentionally independent of the automatic-probe preference:
     * a user who selects an unavailable channel has explicitly requested a
     * retry.  Returning zero means another category/full probe already owns
     * the probe lock, so the caller can leave the current UI untouched.
     */
    suspend fun probeChannels(channels: List<Channel>): Int = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext 0
        try {
            var checked = 0
            channels
                .distinctBy { it.id.takeIf { id -> id > 0L } ?: it.url }
                .forEach { channel ->
                    probeChannel(channel)
                    checked++
                }
            checked
        } finally {
            running.set(false)
        }
    }

    /**
     * Refreshes only the selected TV category when results are missing or
     * older than the configured interval.  It is intentionally sequential:
     * a category remains responsive while an older MStar TV performs a small
     * number of five-second network probes in the background.
     */
    suspend fun probeGroupIfStale(
        groupName: String,
        maxAgeMs: Long = preferences.channelProbeInterval,
        onChannelChecked: suspend (checkedCount: Int) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        if (!preferences.enableChannelProbeRefresh) return@withContext 0
        if (!running.compareAndSet(false, true)) return@withContext 0
        try {
            val now = System.currentTimeMillis()
            val staleChannels = database.channelDao()
                .getActiveChannelsByGroupForProbe(groupName)
                .filter { channel ->
                    channel.lastCheckedAt == 0L || now - channel.lastCheckedAt >= maxAgeMs
                }
            staleChannels.forEachIndexed { index, channel ->
                probeChannel(channel)
                onChannelChecked(index + 1)
                if (preferences.channelProbeDelayMs > 0L) {
                    delay(preferences.channelProbeDelayMs)
                }
            }
            staleChannels.size
        } finally {
            running.set(false)
        }
    }
}
