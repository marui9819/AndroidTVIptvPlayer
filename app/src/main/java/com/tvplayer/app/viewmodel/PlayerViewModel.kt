package com.tvplayer.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.util.PreferencesHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val preferences = PreferencesHelper(application)
    private val channelDao = database.channelDao()
    private val playlistDao = database.playlistDao()

    // Player settings
    var useHardwareAcceleration: Boolean
        get() = preferences.useHardwareAcceleration
        set(value) { preferences.useHardwareAcceleration = value }

    var videoQuality: String
        get() = preferences.videoQuality
        set(value) { preferences.videoQuality = value }

    var enableSubtitles: Boolean
        get() = preferences.enableSubtitles
        set(value) { preferences.enableSubtitles = value }

    // Playback state
    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _channelList = MutableStateFlow<List<Channel>>(emptyList())
    val channelList: StateFlow<List<Channel>> = _channelList.asStateFlow()

    private var currentChannelIndex = -1
    private var currentPlaylistChannels: List<Channel> = emptyList()

    fun loadChannel(channelId: Long) {
        viewModelScope.launch {
            val channel = channelDao.getChannelById(channelId)
            _currentChannel.value = channel
            channel?.let { setCurrentChannelIndex(it) }
        }
    }

    fun loadChannelList() {
        viewModelScope.launch {
            val defaultPlaylistId = preferences.defaultPlaylistId
            if (defaultPlaylistId != -1L) {
                currentPlaylistChannels = channelDao.getAvailableChannelsByPlaylist(defaultPlaylistId)
                _channelList.value = currentPlaylistChannels
            }
        }
    }

    fun selectChannel(channelId: Long) {
        viewModelScope.launch {
            val channel = channelDao.getChannelById(channelId)
            _currentChannel.value = channel
            channel?.let { setCurrentChannelIndex(it) }
        }
    }

    fun playPreviousChannel() {
        if (currentChannelIndex > 0) {
            currentChannelIndex--
            _currentChannel.value = currentPlaylistChannels[currentChannelIndex]
            updatePlaybackPosition()
        }
    }

    fun playNextChannel() {
        if (currentChannelIndex < currentPlaylistChannels.size - 1) {
            currentChannelIndex++
            _currentChannel.value = currentPlaylistChannels[currentChannelIndex]
            updatePlaybackPosition()
        } else if (currentPlaylistChannels.isNotEmpty()) {
            // Loop back to first channel
            currentChannelIndex = 0
            _currentChannel.value = currentPlaylistChannels[currentChannelIndex]
        }
    }

    fun setError(error: String) {
        _playbackError.value = error
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000) // Auto-clear error after 3 seconds
            _playbackError.value = null
        }
    }

    fun setBuffering(isBuffering: Boolean) {
        _isBuffering.value = isBuffering
    }

    private fun setCurrentChannelIndex(channel: Channel) {
        currentPlaylistChannels.find { it.id == channel.id }?.let {
            currentChannelIndex = currentPlaylistChannels.indexOf(it)
        }
    }

    private fun updatePlaybackPosition() {
        viewModelScope.launch {
            _currentChannel.value?.let { channel ->
                preferences.lastPlayedChannelId = channel.id
                preferences.addToWatchHistory(channel, 0) // Placeholder duration
            }
        }
    }

    fun savePlaybackPosition(position: Long) {
        viewModelScope.launch {
            _currentChannel.value?.let { channel ->
                channelDao.updatePlaybackPosition(channel.id, position, System.currentTimeMillis())
                preferences.lastPlayedPosition = position
            }
        }
    }

    fun toggleFavorite(channelId: Long) {
        viewModelScope.launch {
            val currentFavorite = preferences.getChannelFavorite(channelId)
            preferences.setChannelFavorite(channelId, !currentFavorite)
            channelDao.updateFavoriteStatus(channelId, !currentFavorite)
        }
    }

    fun markChannelAsUnavailable(channelId: Long, error: String) {
        viewModelScope.launch {
            channelDao.updateChannelAvailability(channelId, false, error)
            _playbackError.value = error
            kotlinx.coroutines.delay(3000)
            _playbackError.value = null
        }
    }

    fun checkChannelAvailability(channelId: Long) {
        viewModelScope.launch {
            _currentChannel.value?.let { channel ->
                // This would implement actual availability checking
                val isAvailable = true // Placeholder for real check
                if (!isAvailable) {
                    markChannelAsUnavailable(channel.id, "Channel temporarily unavailable")
                }
            }
        }
    }

    fun getFavoriteChannels() = channelDao.getFavoriteChannelsLiveData()
    fun getRecentlyPlayedChannels() = channelDao.getRecentlyPlayedChannelsLiveData()

    override fun onCleared() {
        super.onCleared()
        // Clean up any resources if needed
    }
}