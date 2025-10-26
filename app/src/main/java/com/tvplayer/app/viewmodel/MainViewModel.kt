package com.tvplayer.app.viewmodel

import androidx.lifecycle.*
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.repository.PlaylistRepository
import com.tvplayer.app.util.PreferencesHelper
import kotlinx.coroutines.flow.*

class MainViewModel(
    private val database: AppDatabase,
    private val repository: PlaylistRepository,
    private val preferences: PreferencesHelper
) : ViewModel() {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _selectedChannel = MutableStateFlow<Channel?>(null)
    val selectedChannel: StateFlow<Channel?> = _selectedChannel.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadPlaylists()
        loadChannels()
    }

    private fun loadPlaylists() {
        viewModelScope.launch {
            repository.getAllPlaylists()
                .collect { playlistList ->
                    _playlists.value = playlistList
                }
        }
    }

    private fun loadChannels() {
        viewModelScope.launch {
            repository.getAllChannels()
                .collect { channelList ->
                    _channels.value = channelList
                }
        }
    }

    fun selectChannel(channel: Channel) {
        _selectedChannel.value = channel
        preferences.lastPlayedChannelId = channel.id
    }

    fun refreshPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.refreshPlaylists()
                loadPlaylists()
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshChannels() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.refreshChannels()
                loadChannels()
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addToFavorites(channel: Channel) {
        viewModelScope.launch {
            repository.updateChannelFavoriteStatus(channel.id, true)
            loadChannels()
        }
    }

    fun removeFromFavorites(channel: Channel) {
        viewModelScope.launch {
            repository.updateChannelFavoriteStatus(channel.id, false)
            loadChannels()
        }
    }

    fun getChannelsByPlaylist(playlistId: Long): Flow<List<Channel>> {
        return repository.getChannelsByPlaylist(playlistId)
    }

    fun getFavoriteChannels(): Flow<List<Channel>> {
        return repository.getFavoriteChannels()
    }

    fun searchChannels(query: String): Flow<List<Channel>> {
        return repository.searchChannels(query)
    }

    class Factory(
        private val database: AppDatabase,
        private val repository: PlaylistRepository,
        private val preferences: PreferencesHelper
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(database, repository, preferences) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}