package com.tvplayer.app

import android.app.Application
import com.bumptech.glide.Glide
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.util.PlayerHelper
import com.tvplayer.app.util.PreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val applicationScope = CoroutineScope(SupervisorJob())
    val database by lazy { AppDatabase.getDatabase(this) }
    val preferences by lazy { PreferencesHelper(this) }

    override fun onCreate() {
        super.onCreate()

        instance = this

        // Initialize preferences with defaults
        preferences.initializeDefaults()

        // Start initial playlist loading
        applicationScope.launch {
            // Auto-load default playlist if available
            if (preferences.isAutoLoadEnabled && !preferences.isFirstLaunch) {
                PlayerHelper.loadInitialPlaylist()
            }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}