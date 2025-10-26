package com.tvplayer.app.workmanager

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkManagerInitializer {

    private const val TAG = "WorkManagerInitializer"

    fun schedulePlaylistRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val refreshRequest = PeriodicWorkRequestBuilder<PlaylistRefreshWorker>(
            4, // 4 hours interval (minimum for periodic work)
            TimeUnit.HOURS,
            15, // flex period
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                PeriodicWorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(PlaylistRefreshWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PlaylistRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            refreshRequest
        )
    }

    fun cancelPlaylistRefresh(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PlaylistRefreshWorker.WORK_NAME)
    }

    fun scheduleImmediateRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val refreshRequest = OneTimeWorkRequestBuilder<PlaylistRefreshWorker>()
            .setConstraints(constraints)
            .addTag(PlaylistRefreshWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueue(refreshRequest)
    }

    fun isWorkScheduled(context: Context): Boolean {
        val workInfos = WorkManager.getInstance(context).getWorkInfosByTag(
            PlaylistRefreshWorker.TAG
        ).get()

        return workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    }

    fun getLastWorkStatus(context: Context): List<WorkInfo>? {
        return try {
            WorkManager.getInstance(context).getWorkInfosByTag(
                PlaylistRefreshWorker.TAG
            ).get()
        } catch (e: Exception) {
            null
        }
    }

    fun observeWorkStatus(context: Context, callback: (List<WorkInfo>) -> Unit) {
        WorkManager.getInstance(context).getWorkInfosByTagLiveData(
            PlaylistRefreshWorker.TAG
        ).observeForever { workInfos ->
            callback(workInfos)
        }
    }

    fun resetWorkManager(context: Context) {
        // Cancel all existing work
        WorkManager.getInstance(context).cancelAllWorkByTag(PlaylistRefreshWorker.TAG)

        // Schedule new work
        schedulePlaylistRefresh(context)
    }
}