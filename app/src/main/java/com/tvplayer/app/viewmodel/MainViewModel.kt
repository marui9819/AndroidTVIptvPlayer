package com.tvplayer.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.data.repository.ChannelCatalogRepository
import com.tvplayer.app.data.repository.PlaylistRepository
import com.tvplayer.app.util.DeviceProfile
import com.tvplayer.app.util.PlayerHelper
import com.tvplayer.app.util.PreferencesHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import android.content.SharedPreferences
import com.tvplayer.app.util.ChannelUserState
import com.tvplayer.app.util.ChannelUserPolicy

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val database = AppDatabase.getDatabase(application)
    private val repository = PlaylistRepository(database = database)
    private val catalogRepository = ChannelCatalogRepository(application)
    private val preferences = PreferencesHelper(application)
    private val userState = ChannelUserState.get(application)
    private var recentJob: Job? = null
    private val historyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "recent_playback_v2" || key == "recent_history_limit") loadRecentlyPlayedChannels()
    }

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _favoriteChannels = MutableStateFlow<List<Channel>>(emptyList())
    val favoriteChannels: StateFlow<List<Channel>> = _favoriteChannels.asStateFlow()

    private val _recentlyPlayedChannels = MutableStateFlow<List<Channel>>(emptyList())
    val recentlyPlayedChannels: StateFlow<List<Channel>> = _recentlyPlayedChannels.asStateFlow()

    private val _selectedChannel = MutableStateFlow<Channel?>(null)
    val selectedChannel: StateFlow<Channel?> = _selectedChannel.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        userState.prefs.registerOnSharedPreferenceChangeListener(historyListener)
        loadPlaylists()
        loadFavoriteChannels()
        loadRecentlyPlayedChannels()
    }

    fun loadPlaylists() {
        viewModelScope.launch {
            database.playlistDao().getAllPlaylists().collect { _playlists.value = it }
        }
    }

    fun loadFavoriteChannels() {
        viewModelScope.launch {
            repository.getFavoriteChannelsLiveData().collect { _favoriteChannels.value = it }
        }
    }

    fun loadRecentlyPlayedChannels() {
        recentJob?.cancel()
        recentJob = viewModelScope.launch {
            val history = userState.history()
            if (history.isEmpty()) {
                _recentlyPlayedChannels.value = emptyList()
                return@launch
            }
            database.channelDao().observeActiveChannelsByUrls(history.map { it.url }).collect {
                _recentlyPlayedChannels.value = ChannelUserPolicy.resolveHistory(history, it)
            }
        }
    }

    override fun onCleared() {
        userState.prefs.unregisterOnSharedPreferenceChangeListener(historyListener)
        super.onCleared()
    }

    fun refreshPlaylists(force: Boolean = false) {
        viewModelScope.launch {
            _loading.value = true
            try {
                if (DeviceProfile.isLegacyTv(getApplication()) && !force) {
                    // App performs the guarded initial load.  Once source rows
                    // exist, do not parse every large list again just because
                    // the home screen was created or resumed.  On Android 6-7
                    // MStar TVs this second parse can overlap the player codec
                    // startup and leave the picture stuck in BUFFERING.
                    if (database.playlistDao().getActivePlaylistCount() == 0) {
                        PlayerHelper.loadInitialPlaylist()
                    }
                    return@launch
                }
                var playlistsToRefresh = database.playlistDao().getAllPlaylists().first()
                if (playlistsToRefresh.isEmpty()) {
                    PlayerHelper.loadInitialPlaylist()
                    playlistsToRefresh = database.playlistDao().getAllPlaylists().first()
                }
                // A source switched off in Subscription Management must be a
                // real resource boundary, especially on low-memory TVs.  Do
                // not download or parse its list merely because the home
                // screen starts or asks for a refresh.
                playlistsToRefresh.filter { it.isActive }.forEach { playlist ->
                    if (!playlist.url.isNullOrBlank()) {
                        repository.refreshPlaylist(playlist)
                    }
                }
            } finally {
                _loading.value = false
            }
        }
    }

    fun refreshAllPlaylists() = refreshPlaylists(force = true)

    suspend fun getChannelsForPlaylist(playlistId: Long): List<Channel> {
        return repository.getChannelsForPlaylist(playlistId)
    }

    suspend fun getAllAvailableChannels(): List<Channel> {
        val activePlaylistIds = database.playlistDao().getActivePlaylistsList().map { it.id }
        if (activePlaylistIds.isEmpty()) return emptyList()
        return database.channelDao().getChannelsByPlaylistIds(activePlaylistIds)
            .filter { it.isAvailable }
            .sortedWith(compareBy({ it.group.orEmpty() }, { it.order }, { it.name }))
    }

    suspend fun resolveStartupChannel(): Channel? {
        ensureDefaultPlaylistInitialized()

        val history = userState.history()
        if (history.isNotEmpty()) {
            val liveChannels = database.channelDao().getActiveChannelsByUrls(history.map { it.url })
            ChannelUserPolicy.resolveHistory(history, liveChannels).firstOrNull()?.let { return it }
        }

        val lastChannelId = preferences.lastPlayedChannelId
        val lastChannelUrl = preferences.lastPlayedChannelUrl
        val defaultPlaylistId = preferences.defaultPlaylistId.takeIf { it > 0 }
            ?: database.playlistDao().getDefaultPlaylistItem()?.id
        return catalogRepository.resolveStartupSourceLightweight(lastChannelId, lastChannelUrl, defaultPlaylistId)
            ?.also { Log.d(TAG, "Resolved startup channel=${it.name}") }
    }

    private suspend fun ensureDefaultPlaylistInitialized() {
        val playlists = database.playlistDao().getAllPlaylists().first()
        if (playlists.isEmpty()) {
            PlayerHelper.loadInitialPlaylist()
            return
        }

        val defaultPlaylistId = preferences.defaultPlaylistId.takeIf { it > 0 }
            ?: database.playlistDao().getDefaultPlaylistItem()?.id
        if (defaultPlaylistId != null) {
            val defaultChannelCount = database.channelDao().getChannelCountByPlaylist(defaultPlaylistId)
            if (defaultChannelCount == 0) {
                Log.d(TAG, "Default playlist has no channels, repairing initial playlist")
                PlayerHelper.loadInitialPlaylist()
            }
        }
    }

    fun playLastWatchedChannel() {
        val channelId = preferences.lastPlayedChannelId
        if (channelId <= 0) return
        viewModelScope.launch {
            _selectedChannel.value = database.channelDao().getChannelById(channelId)
        }
    }

    suspend fun addPlaylist(playlist: Playlist): Long {
        return database.playlistDao().insertPlaylist(playlist)
    }

    fun refreshPlaylist(playlistId: Long) {
        viewModelScope.launch {
            val playlist = database.playlistDao().getPlaylistById(playlistId) ?: return@launch
            if (playlist.isActive && !playlist.url.isNullOrBlank()) {
                repository.refreshPlaylist(playlist)
            }
        }
    }

    fun addToFavorites(channel: Channel) {
        viewModelScope.launch {
            repository.updateChannelFavorite(channel.id, true)
        }
    }

    fun removeFromFavorites(channel: Channel) {
        viewModelScope.launch {
            repository.updateChannelFavorite(channel.id, false)
        }
    }
}
