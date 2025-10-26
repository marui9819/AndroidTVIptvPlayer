package com.tvplayer.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val channelDao = database.channelDao()

    private val _searchResults = MutableLiveData<List<Channel>>(emptyList())
    val searchResults: LiveData<List<Channel>> = _searchResults

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _searchError = MutableLiveData<String?>(null)
    val searchError: LiveData<String?> = _searchError

    private val _searchQuery = MutableStateFlow<String>("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun searchChannels(query: String) {
        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _searchQuery.value = query.trim()
            _searchError.value = null

            try {
                val results = withContext(Dispatchers.IO) {
                    channelDao.searchChannels(query.trim())
                }

                _searchResults.value = results

                if (results.isEmpty()) {
                    _searchError.value = "未找到匹配的频道"
                }
            } catch (e: Exception) {
                _searchError.value = "搜索失败: ${e.message}"
                _searchResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchChannelsByGroup(groupName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _searchError.value = null

            try {
                val results = withContext(Dispatchers.IO) {
                    channelDao.getChannelsByGroup(groupName)
                }

                _searchResults.value = results

                if (results.isEmpty()) {
                    _searchError.value = "未找到分组 \"$groupName\" 的频道"
                }
            } catch (e: Exception) {
                _searchError.value = "搜索失败: ${e.message}"
                _searchResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchFavoriteChannels(query: String) {
        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _searchError.value = null

            try {
                val results = withContext(Dispatchers.IO) {
                    channelDao.searchFavoriteChannels(query.trim())
                }

                _searchResults.value = results

                if (results.isEmpty()) {
                    _searchError.value = "未找到收藏的频道"
                }
            } catch (e: Exception) {
                _searchError.value = "搜索失败: ${e.message}"
                _searchResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchAvailableChannels(query: String) {
        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _searchError.value = null

            try {
                val results = withContext(Dispatchers.IO) {
                    channelDao.searchAvailableChannels(query.trim())
                }

                _searchResults.value = results

                if (results.isEmpty()) {
                    _searchError.value = "未找到可用的频道"
                }
            } catch (e: Exception) {
                _searchError.value = "搜索失败: ${e.message}"
                _searchResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearResults() {
        _searchResults.value = emptyList()
        _searchQuery.value = ""
        _searchError.value = null
    }

    fun getPopularSearchTerms(): List<String> {
        // Return popular search terms based on common channel names
        return listOf(
            "CCTV", "卫视", "体育", "电影", "电视剧", "新闻", "财经", "少儿", "科教", "记录"
        )
    }

    fun getRecentSearchTerms(): List<String> {
        // In a real implementation, this would retrieve from SharedPreferences
        return emptyList()
    }

    fun saveSearchTerm(term: String) {
        // In a real implementation, this would save to SharedPreferences for recent searches
        // Limit to last 10 searches
    }

    fun clearRecentSearches() {
        // Clear recent search history
    }

    fun getSearchSuggestions(query: String): List<String> {
        if (query.length < 1) return emptyList()

        val allSuggestions = getPopularSearchTerms() + getRecentSearchTerms()
        return allSuggestions
            .filter { it.lowercase().contains(query.lowercase()) }
            .distinct()
            .take(5) // Limit to 5 suggestions
    }

    fun updateChannelFavoriteStatus(channelId: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                channelDao.updateFavoriteStatus(channelId, isFavorite)
            }

            // Update the search results
            _searchResults.value = _searchResults.value?.map { channel ->
                if (channel.id == channelId) {
                    channel.copy(isFavorite = isFavorite)
                } else {
                    channel
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Clean up any resources if needed
    }
}