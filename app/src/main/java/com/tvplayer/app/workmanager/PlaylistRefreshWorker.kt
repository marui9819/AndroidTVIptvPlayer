package com.tvplayer.app.workmanager

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.network.NetworkRepository
import com.tvplayer.app.util.PreferencesHelper
import kotlinx.coroutines.runBlocking

class PlaylistRefreshWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    companion object {
        const val TAG = "PlaylistRefreshWorker"
        const val WORK_NAME = "playlist_refresh_work"
    }

    override fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting playlist refresh work")

            val preferences = PreferencesHelper(applicationContext)
            val database = AppDatabase.getDatabase(applicationContext)
            val playlistDao = database.playlistDao()
            val channelDao = database.channelDao()
            val repository = NetworkRepository(preferences)

            // Get all active playlists that need refreshing
            val playlistsToRefresh = runBlocking {
                playlistDao.getPlaylistsNeedingRefresh()
            }

            if (playlistsToRefresh.isEmpty()) {
                Log.d(TAG, "No playlists need refreshing")
                return Result.success()
            }

            var successCount = 0
            var failureCount = 0

            playlistsToRefresh.forEach { playlist ->
                try {
                    runBlocking {
                        // Refresh the playlist
                        val result = repository.refreshPlaylist(playlist)
                        if (result.isSuccess) {
                            successCount++
                            // Update last refresh time
                            playlistDao.updateLastRefreshTime(playlist.id, System.currentTimeMillis())
                            Log.d(TAG, "Successfully refreshed playlist: ${playlist.name}")
                        } else {
                            failureCount++
                            Log.e(TAG, "Failed to refresh playlist: ${playlist.name} - ${result.exceptionOrNull()?.message}")
                            // Update error status
                            playlistDao.updatePlaylistError(playlist.id, result.exceptionOrNull()?.message)
                        }
                    }
                } catch (e: Exception) {
                    failureCount++
                    Log.e(TAG, "Exception refreshing playlist: ${playlist.name}", e)
                    // Update error status
                    playlistDao.updatePlaylistError(playlist.id, e.message)
                }
            }

            Log.i(TAG, "Refresh completed. Success: $successCount, Failed: $failureCount")

            if (failureCount > 0 && successCount == 0) {
                // All failed, return failure but don't retry immediately
                return Result.failure()
            }

            // Return success even if some failed (partial success)
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Error during playlist refresh work", e)
            Result.failure()
        }
    }

    override fun onStopped() {
        super.onStopped()
        Log.d(TAG, "Playlist refresh work was stopped")
    }
}