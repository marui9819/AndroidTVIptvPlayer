package com.tvplayer.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.repository.ChannelCatalogRepository
import com.tvplayer.app.data.repository.ChannelProbeRepository
import com.tvplayer.app.data.repository.MergedChannel
import com.tvplayer.app.data.repository.PlaybackSourceOption
import com.tvplayer.app.data.repository.orderMergedChannelsForGroup
import com.tvplayer.app.ui.player.model.ChannelGroupItem
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.util.restoredGroupIndex
import com.tvplayer.app.util.restoredSelectionIndex
import com.tvplayer.app.util.restoredSelectionIndexForGroupRefresh
import com.tvplayer.app.util.adjacentAvailableChannelIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** A short coalescing window for the player resume callback. */
        private const val CATALOG_REFRESH_COALESCE_MS = 5_000L
        /** A virtual group that collects logical (merged) channels the user marked as favourites. */
        const val FAVORITES_GROUP_NAME = "★ 我的收藏"
    }

    enum class SelectorPane {
        GROUPS,
        CHANNELS
    }

    private val database = AppDatabase.getDatabase(application)
    private val preferences = PreferencesHelper(application)
    private val channelDao = database.channelDao()
    private val playlistDao = database.playlistDao()
    private val catalogRepository = ChannelCatalogRepository(application)
    private val channelProbeRepository = ChannelProbeRepository(application, database = database, preferences = preferences)
    private var categoryProbeJob: Job? = null
    private var manualProbeJob: Job? = null
    private var selectedGroupName: String = ""
    private var activeProbeGroupName: String? = null
    private var pendingProbeGroupName: String? = null

    var useHardwareAcceleration: Boolean
        get() = preferences.useHardwareAcceleration
        set(value) { preferences.useHardwareAcceleration = value }

    var videoQuality: String
        get() = preferences.videoQuality
        set(value) { preferences.videoQuality = value }

    var enableSubtitles: Boolean
        get() = preferences.enableSubtitles
        set(value) { preferences.enableSubtitles = value }

    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _currentMergedChannel = MutableStateFlow<MergedChannel?>(null)
    val currentMergedChannel: StateFlow<MergedChannel?> = _currentMergedChannel.asStateFlow()

    private val _currentSource = MutableStateFlow<PlaybackSourceOption?>(null)
    val currentSource: StateFlow<PlaybackSourceOption?> = _currentSource.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _mergedChannelList = MutableStateFlow<List<MergedChannel>>(emptyList())
    val mergedChannelList: StateFlow<List<MergedChannel>> = _mergedChannelList.asStateFlow()

    private val _channelGroups = MutableStateFlow<List<ChannelGroupItem>>(emptyList())
    val channelGroups: StateFlow<List<ChannelGroupItem>> = _channelGroups.asStateFlow()

    private val _selectedGroupIndex = MutableStateFlow(0)
    val selectedGroupIndex: StateFlow<Int> = _selectedGroupIndex.asStateFlow()

    private val _visibleChannels = MutableStateFlow<List<MergedChannel>>(emptyList())
    val visibleChannels: StateFlow<List<MergedChannel>> = _visibleChannels.asStateFlow()

    private val _selectedChannelIndex = MutableStateFlow(0)
    val selectedChannelIndex: StateFlow<Int> = _selectedChannelIndex.asStateFlow()

    private val _selectorVisible = MutableStateFlow(false)
    val selectorVisible: StateFlow<Boolean> = _selectorVisible.asStateFlow()

    private val _selectorPane = MutableStateFlow(SelectorPane.CHANNELS)
    val selectorPane: StateFlow<SelectorPane> = _selectorPane.asStateFlow()

    private var currentMergedChannelIndex = -1
    private var currentMergedChannels: List<MergedChannel> = emptyList()
    private var catalogJob: Job? = null
    private var catalogLoadedAtMillis: Long = 0L
    // Keep the D-pad cursor's identity independent of transient catalog
    // snapshots and adapter positions while probes/refreshes are in flight.
    private var selectedChannelKey: String? = null
    // Channel +/- follows the same category rows shown by the drawer, rather
    // than the unrelated flat playlist merge order.
    private var playbackNavigationGroupName: String = ""

    fun loadStartupChannel(explicitChannelId: Long = -1L, fallbackChannel: Channel? = null) {
        if (catalogJob?.isActive == true) return
        catalogJob = viewModelScope.launch {
            try {
                currentMergedChannels = catalogRepository.getMergedChannels()
                catalogLoadedAtMillis = System.currentTimeMillis()
                _mergedChannelList.value = currentMergedChannels
                rebuildGroups()

                // Resolve from the catalog just built.  This avoids a second
                // full DB read/sort during the most sensitive part of TV boot.
                val startupChannel = if (explicitChannelId > 0L) {
                    com.tvplayer.app.util.ChannelUserPolicy.requestedSource(
                        currentMergedChannels.flatMap { it.sources }.map { it.channel },
                        explicitChannelId, fallbackChannel?.url
                    ) ?: fallbackChannel
                } else catalogRepository.resolveStartupSourceFromCatalog(
                    mergedChannels = currentMergedChannels,
                    lastChannelId = explicitChannelId.takeIf { it > 0L } ?: preferences.lastPlayedChannelId,
                    lastChannelUrl = if (explicitChannelId > 0L) null else preferences.lastPlayedChannelUrl,
                    defaultPlaylistId = preferences.defaultPlaylistId.takeIf { it > 0L }
                        ?: playlistDao.getDefaultPlaylistItem()?.id
                )

                val current = if (explicitChannelId > 0L) startupChannel else startupChannel
                    ?: currentMergedChannels.firstOrNull { it.isAvailable }?.preferredSource?.channel
                    ?: fallbackChannel
                current?.let { setCurrentChannel(it) }
            } finally {
                catalogJob = null
            }
        }
    }

    fun loadChannel(channelId: Long) {
        loadStartupChannel(channelId)
    }

    fun loadChannelList(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (catalogJob?.isActive == true) return
        if (!force && currentMergedChannels.isNotEmpty() &&
            now - catalogLoadedAtMillis < CATALOG_REFRESH_COALESCE_MS
        ) {
            return
        }
        catalogJob = viewModelScope.launch {
            try {
                refreshChannelCatalog()
            } finally {
                catalogJob = null
            }
        }
    }

    fun openSelector(playingChannel: Channel? = _currentChannel.value) {
        // Resolve the active database row again before opening. The merged
        // channel snapshot can lag a source switch; using its stale group/key
        // made OK reopen a neighbouring row in another category.
        val playing = resolveMergedForChannel(playingChannel)
            ?: resolveMergedForChannel(_currentChannel.value)
            ?: _currentMergedChannel.value
        if (playing != null) {
            _currentMergedChannel.value = playing
            _currentSource.value = playing.sources.firstOrNull { it.channel.id == _currentChannel.value?.id }
                ?: playing.preferredSource
            currentMergedChannelIndex = currentMergedChannels.indexOfFirst { it.key == playing.key }
        }
        selectedGroupName = ""
        selectedChannelKey = playing?.key
        _selectorVisible.value = true
        _selectorPane.value = SelectorPane.CHANNELS
        // The drawer is also the diagnostic view: keep failed channels visible
        // here so a user can see timeout/unavailable results and their group
        // totals instead of silently filtering the evidence away.
        rebuildGroups()
        val playingKey = playing?.key
        val playbackGroupIndex = playingKey?.let { key ->
            _channelGroups.value.indexOfFirst { group ->
                group.name == playbackNavigationGroupName && group.channels.any { it.key == key }
            }.takeIf { it >= 0 }
                ?: _channelGroups.value.indexOfFirst { group -> group.channels.any { it.key == key } }
        } ?: -1
        if (playbackGroupIndex >= 0) {
            updateSelectedGroup(playbackGroupIndex)
            val playbackRowIndex = _visibleChannels.value.indexOfFirst { it.key == playingKey }
            if (playbackRowIndex >= 0) {
                _selectedChannelIndex.value = playbackRowIndex
                selectedChannelKey = playingKey
            }
        }
        playbackNavigationGroupName = selectedGroupName
        _channelGroups.value.getOrNull(_selectedGroupIndex.value)?.let { group ->
            requestCategoryProbe(group.name)
        }
    }

    /**
     * Opens the same remote-friendly drawer directly at the favourites group.
     * Returning false lets the caller give useful feedback without exposing an
     * empty, dead-end category to a TV remote user.
     */
    fun openFavoritesSelector(): Boolean {
        selectedGroupName = FAVORITES_GROUP_NAME
        selectedChannelKey = null
        _selectorVisible.value = true
        _selectorPane.value = SelectorPane.CHANNELS
        rebuildGroups()

        val favoritesIndex = _channelGroups.value.indexOfFirst { it.name == FAVORITES_GROUP_NAME }
        if (favoritesIndex < 0) {
            _selectorVisible.value = false
            rebuildGroups()
            return false
        }
        updateSelectedGroup(favoritesIndex)
        return true
    }

    fun closeSelector() {
        _selectorVisible.value = false
        // A user who starts playback or opens the action dock should not have
        // background probes competing with the decoder on an Android 6/7 TV.
        pendingProbeGroupName = null
        activeProbeGroupName = null
        categoryProbeJob?.cancel()
        categoryProbeJob = null
        rebuildGroups()
    }

    fun moveSelectionUp() {
        if (!_selectorVisible.value) return
        when (_selectorPane.value) {
            SelectorPane.GROUPS -> if (_selectedGroupIndex.value > 0) updateSelectedGroup(_selectedGroupIndex.value - 1)
            SelectorPane.CHANNELS -> if (_selectedChannelIndex.value > 0) {
                _selectedChannelIndex.value--
                rememberSelectedChannelKey()
            }
        }
    }

    fun moveSelectionDown() {
        if (!_selectorVisible.value) return
        when (_selectorPane.value) {
            SelectorPane.GROUPS -> if (_selectedGroupIndex.value < _channelGroups.value.lastIndex) updateSelectedGroup(_selectedGroupIndex.value + 1)
            SelectorPane.CHANNELS -> if (_selectedChannelIndex.value < _visibleChannels.value.lastIndex) {
                _selectedChannelIndex.value++
                rememberSelectedChannelKey()
            }
        }
    }

    fun focusGroupsPane() {
        if (_selectorVisible.value) _selectorPane.value = SelectorPane.GROUPS
    }

    fun focusChannelsPane() {
        if (_selectorVisible.value) _selectorPane.value = SelectorPane.CHANNELS
    }

    fun selectGroup(index: Int) {
        if (_channelGroups.value.isEmpty()) return
        updateSelectedGroup(index)
        _selectorPane.value = SelectorPane.CHANNELS
    }

    fun confirmSelection() {
        if (!_selectorVisible.value) return
        when (_selectorPane.value) {
            SelectorPane.GROUPS -> _selectorPane.value = SelectorPane.CHANNELS
            SelectorPane.CHANNELS -> {
                val selectedChannel = _visibleChannels.value.getOrNull(_selectedChannelIndex.value) ?: return
                if (!selectedChannel.isAvailable) return
                selectedChannel.preferredSource?.channel?.let {
                    setCurrentChannel(it, navigationGroupName = selectedGroupName)
                }
                closeSelector()
            }
        }
    }

    fun selectedVisibleChannel(): MergedChannel? =
        _visibleChannels.value.getOrNull(_selectedChannelIndex.value)

    /**
     * Rechecks all playlist sources for the currently highlighted unavailable
     * logical channel.  The selector remains open so the user can immediately
     * see whether the channel recovered and press OK to play it.
     */
    fun retrySelectedChannelProbe(): Boolean {
        val selected = selectedVisibleChannel() ?: return false
        if (selected.isAvailable || categoryProbeJob?.isActive == true || manualProbeJob?.isActive == true) {
            return false
        }
        val sources = selected.sources.map { it.channel }.distinctBy { channel ->
            channel.id.takeIf { it > 0L } ?: channel.url
        }
        if (sources.isEmpty()) return false

        val selectedKey = selected.key
        manualProbeJob = viewModelScope.launch {
            try {
                val checked = channelProbeRepository.probeChannels(sources)
                if (checked > 0) {
                    refreshChannelCatalog(preserveSelectedChannelKey = selectedKey)
                }
            } finally {
                manualProbeJob = null
            }
        }
        return true
    }

    fun playPreviousChannel() {
        playAdjacentChannel(direction = -1)
    }

    fun playNextChannel() {
        playAdjacentChannel(direction = 1)
    }

    private fun playAdjacentChannel(direction: Int) {
        val current = resolveMergedForChannel(_currentChannel.value) ?: _currentMergedChannel.value ?: return
        val preferredGroup = _channelGroups.value.firstOrNull { group ->
            group.name == playbackNavigationGroupName && group.channels.any { it.key == current.key }
        }
        val group = preferredGroup ?: _channelGroups.value.firstOrNull { candidate ->
            candidate.channels.any { it.key == current.key }
        }
        val rows = group?.channels ?: currentMergedChannels
        val targetIndex = adjacentAvailableChannelIndex(
            channelKeys = rows.map { it.key },
            availability = rows.map { it.isAvailable },
            currentKey = current.key,
            direction = direction
        ) ?: return
        rows[targetIndex].preferredSource?.channel?.let { channel ->
            setCurrentChannel(channel, navigationGroupName = group?.name)
        }
    }

    /**
     * 自动故障转移专用的下一频道选择。
     * 已经确认播放失败的逻辑频道会被排除，避免在所有来源失败时循环回到原频道。
     */
    fun playNextChannelExcluding(excludedKeys: Set<String>): Boolean {
        val available = currentMergedChannels.filter { it.isAvailable }
        if (available.isEmpty()) return false

        val currentIndex = available.indexOfFirst { it.key == _currentMergedChannel.value?.key }
        val startIndex = if (currentIndex >= 0) currentIndex else -1
        val target = (1..available.size)
            .asSequence()
            .map { offset -> available[(startIndex + offset).mod(available.size)] }
            .firstOrNull { it.key !in excludedKeys }
            ?: return false
        val channel = target.preferredSource?.channel ?: return false
        setCurrentChannel(channel)
        return true
    }

    fun selectChannel(channelId: Long, expectedUrl: String? = null) {
        viewModelScope.launch {
            if (!expectedUrl.isNullOrBlank()) {
                val requested = com.tvplayer.app.util.ChannelUserPolicy.requestedSource(
                    currentMergedChannels.flatMap { it.sources }.map { it.channel }, channelId, expectedUrl
                ) ?: channelDao.getActiveChannelsByUrls(listOf(expectedUrl.trim())).firstOrNull()
                requested?.let { setCurrentChannel(it) }
                return@launch
            }
            // The selector already works from the in-memory merged catalog.
            // Avoid a full database query and merge pass for every OK press;
            // only fall back to the targeted query when the catalog is stale.
            val merged = currentMergedChannels.firstOrNull { item ->
                item.sources.any { it.channel.id == channelId }
            } ?: catalogRepository.getMergedChannelByRawChannelId(channelId)
            val selected = merged?.sources?.firstOrNull { it.channel.id == channelId }
                ?: merged?.preferredSource
            selected?.channel?.let {
                setCurrentChannel(it, navigationGroupName = selectedGroupName.takeIf { _selectorVisible.value })
            }
        }
    }

    fun selectMergedChannel(channelKey: String) {
        currentMergedChannels.firstOrNull { it.key == channelKey }?.preferredSource?.channel?.let {
            setCurrentChannel(it, navigationGroupName = selectedGroupName.takeIf { _selectorVisible.value })
        }
    }

    fun switchSource(offset: Int, currentChannel: Channel? = _currentChannel.value): Boolean {
        val merged = resolveMergedForChannel(currentChannel) ?: return false
        if (merged.sources.size <= 1) return false
        val currentIndex = merged.sources.indexOfFirst { it.channel.id == currentChannel?.id }
            .takeIf { it >= 0 } ?: merged.preferredSourceIndex
        val step = if (offset >= 0) 1 else -1
        var nextIndex = currentIndex
        repeat(merged.sources.size) {
            nextIndex = (nextIndex + step).mod(merged.sources.size)
            val candidate = merged.sources[nextIndex]
            if (candidate.isAvailable) {
                setCurrentChannel(candidate.channel)
                return true
            }
        }
        return false
    }

    fun switchToFallbackSource(
        currentChannel: Channel? = _currentChannel.value,
        excludedSourceIds: Set<Long> = emptySet()
    ): Boolean {
        val merged = resolveMergedForChannel(currentChannel) ?: return false
        if (merged.sources.size <= 1) return false
        val currentId = currentChannel?.id
        val fallback = merged.sources.firstOrNull {
            it.isAvailable &&
                it.channel.id != currentId &&
                it.channel.id !in excludedSourceIds
        } ?: return false
        setCurrentChannel(fallback.channel)
        return true
    }

    fun syncPlaybackContext(channel: Channel?) {
        val previousKey = _currentMergedChannel.value?.key
        val merged = resolveMergedForChannel(channel)
        _currentMergedChannel.value = merged
        _currentSource.value = merged?.sources?.firstOrNull { it.channel.id == channel?.id } ?: merged?.preferredSource
        currentMergedChannelIndex = currentMergedChannels.indexOfFirst { it.key == merged?.key }
        if (merged != null && (merged.key != previousKey || playbackNavigationGroupName.isBlank())) {
            playbackNavigationGroupName = channel?.group?.takeIf { groupName ->
                _channelGroups.value.any { group ->
                    group.name == groupName && group.channels.any { it.key == merged.key }
                }
            } ?: merged.groupName
        }
        // A retry/renewal calls this method for the channel already being
        // played. Do not let that background playback update move the D-pad
        // cursor out of the category the user is browsing.
        if (!_selectorVisible.value) syncSelectionWithCurrent()
    }

    private fun setCurrentChannel(
        channel: Channel,
        navigationGroupName: String? = null
    ) {
        val previousKey = _currentMergedChannel.value?.key
        _currentChannel.value = channel
        val merged = resolveMergedForChannel(channel)
        _currentMergedChannel.value = merged
        _currentSource.value = merged?.sources?.firstOrNull { it.channel.id == channel.id } ?: merged?.preferredSource
        currentMergedChannelIndex = currentMergedChannels.indexOfFirst { it.key == merged?.key }
        if (!navigationGroupName.isNullOrBlank()) {
            playbackNavigationGroupName = navigationGroupName
        } else if (merged != null && (merged.key != previousKey || playbackNavigationGroupName.isBlank())) {
            playbackNavigationGroupName = channel.group?.takeIf { groupName ->
                _channelGroups.value.any { group ->
                    group.name == groupName && group.channels.any { it.key == merged.key }
                }
            } ?: merged.groupName
        }
        // A selection is only an attempt. History is committed by the Activity
        // after real playback evidence, never by navigation or background renewal.
        // Playback may move because an automatic recovery selected another
        // source while the drawer is open. Keep the user's browsing cursor in
        // place; the current-playing marker is updated independently by the
        // Activity, and a fresh drawer opening will follow playback again.
        if (!_selectorVisible.value) syncSelectionWithCurrent()
    }

    fun setBuffering(isBuffering: Boolean) {
        _isBuffering.value = isBuffering
    }

    fun setError(error: String) {
        _playbackError.value = error
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            _playbackError.value = null
        }
    }

    fun savePlaybackPosition(position: Long) {
        preferences.lastPlayedPosition = position
    }

    fun getFavoriteChannels() = channelDao.getFavoriteChannels()
    fun getRecentlyPlayedChannels() = channelDao.getRecentlyPlayedChannels()

    fun recordSuccessfulPlayback(channel: Channel) {
        viewModelScope.launch {
            if (com.tvplayer.app.util.PlaybackControls.recordRecent(getApplication(), channel)) {
                channelDao.updatePlaybackPosition(channel.id, System.currentTimeMillis())
                preferences.lastPlayedChannelId = channel.id
                preferences.lastPlayedChannelUrl = channel.url
            }
        }
    }

    private fun rebuildGroups() {
        val groups = buildChannelGroups()
        _channelGroups.value = groups
        val preservedGroupIndex = if (_selectorVisible.value && selectedGroupName.isNotBlank()) {
            groups.indexOfFirst { it.name == selectedGroupName }
        } else {
            -1
        }
        val currentKey = _currentMergedChannel.value?.key
        val preferredPlaybackGroupIndex = groups.indexOfFirst { group ->
            group.name == playbackNavigationGroupName &&
                group.channels.any { it.key == currentKey }
        }
        val currentGroupIndex = preferredPlaybackGroupIndex.takeIf { it >= 0 }
            ?: groups.indexOfFirst { group ->
                group.name != FAVORITES_GROUP_NAME &&
                    group.channels.any { it.key == currentKey }
            }
        val selectedIndex = preservedGroupIndex.takeIf { it >= 0 }
            ?: currentGroupIndex.takeIf { it >= 0 }
            ?: 0
        updateSelectedGroup(selectedIndex)
        if (!_selectorVisible.value && preferredPlaybackGroupIndex < 0 && currentGroupIndex >= 0) {
            playbackNavigationGroupName = groups[currentGroupIndex].name
        }
    }

    private fun buildChannelGroups(): List<ChannelGroupItem> {
        val showUnavailableChannels = preferences.showUnavailableChannels || _selectorVisible.value
        val showEmptyGroups = preferences.showEmptyChannelGroups
        val channelsBySourceGroup = linkedMapOf<String, MutableList<MergedChannel>>()
        currentMergedChannels.forEach { merged ->
            val groupNames = merged.sources
                .map { it.channel.group?.takeIf(String::isNotBlank) ?: "分类1" }
                .distinct()
                .ifEmpty { listOf(merged.groupName.ifBlank { "分类1" }) }
            groupNames.forEach { groupName ->
                channelsBySourceGroup.getOrPut(groupName) { mutableListOf() }.add(merged)
            }
        }
        val sourceGroups = channelsBySourceGroup
            .mapNotNull { (name, channels) ->
                val orderedChannels = orderMergedChannelsForGroup(channels, name)
                val visibleChannels = if (showUnavailableChannels) {
                    orderedChannels
                } else {
                    orderedChannels.filter { it.isAvailable }
                }
                if (visibleChannels.isEmpty() && !showEmptyGroups) {
                    null
                } else {
                    ChannelGroupItem(
                        name = name,
                        channels = visibleChannels,
                        availableCount = channels.count { it.isAvailable }
                    )
                }
            }

        // Favourites are logical channels, so the same station merged from
        // several subscriptions only appears once here.  Keep this virtual
        // category first: it is fast to reach with a directional remote and
        // is also available from the bottom "更多" menu.
        val favoriteChannels = currentMergedChannels.filter { channel ->
            channel.isFavorite && (showUnavailableChannels || channel.isAvailable)
        }
        val groups = buildList {
            if (favoriteChannels.isNotEmpty()) {
                add(
                    ChannelGroupItem(
                        name = FAVORITES_GROUP_NAME,
                        channels = favoriteChannels,
                        availableCount = favoriteChannels.count { it.isAvailable }
                    )
                )
            }
            addAll(sourceGroups)
        }

        return groups
    }

    private fun resolveMergedForChannel(channel: Channel?): MergedChannel? {
        channel ?: return null
        return currentMergedChannels.firstOrNull { item ->
            item.sources.any { it.channel.id == channel.id || it.channel.url == channel.url }
        }
    }

    private fun updateSelectedGroup(index: Int) {
        val groups = _channelGroups.value
        if (groups.isEmpty()) {
            _selectedGroupIndex.value = 0
            _visibleChannels.value = emptyList()
            _selectedChannelIndex.value = 0
            if (!_selectorVisible.value) selectedChannelKey = null
            return
        }

        val safeIndex = index.coerceIn(0, groups.lastIndex)
        val nextGroup = groups[safeIndex]
        val previousGroupName = selectedGroupName
        val previousSelectedKey = selectedChannelKey
            ?: _visibleChannels.value.getOrNull(_selectedChannelIndex.value)?.key
        _selectedGroupIndex.value = safeIndex
        selectedGroupName = nextGroup.name
        _visibleChannels.value = nextGroup.channels

        val currentIndexInGroup = nextGroup.channels.indexOfFirst {
            it.key == _currentMergedChannel.value?.key
        }
        val firstAvailableIndex = nextGroup.channels.indexOfFirst { it.isAvailable }.takeIf { it >= 0 } ?: 0
        val fallbackIndex = currentIndexInGroup.takeIf { it >= 0 } ?: firstAvailableIndex
        val selectedIndex = restoredSelectionIndexForGroupRefresh(
            previousGroupName = previousGroupName,
            nextGroupName = nextGroup.name,
            previousSelectedKey = previousSelectedKey,
            newChannelKeys = nextGroup.channels.map { it.key },
            fallbackIndex = fallbackIndex
        ).coerceIn(0, nextGroup.channels.lastIndex.coerceAtLeast(0))
        _selectedChannelIndex.value = selectedIndex
        val keyStillPresent = nextGroup.channels.any { it.key == previousSelectedKey }
        if (previousGroupName != nextGroup.name || previousSelectedKey.isNullOrBlank() || keyStillPresent) {
            selectedChannelKey = nextGroup.channels.getOrNull(selectedIndex)?.key
        }
        if (_selectorVisible.value) {
            requestCategoryProbe(nextGroup.name)
        }
    }

    private fun requestCategoryProbe(groupName: String) {
        // This is a virtual mixed-source group.  Its normal source categories
        // are probed independently, and probing it would duplicate work while
        // the video decoder is active on low-end TVs.
        if (groupName == FAVORITES_GROUP_NAME) return
        if (!preferences.enableChannelProbeRefresh) return
        if (categoryProbeJob?.isActive == true) {
            // Keep only the user's latest category request. The current probe
            // is allowed to finish its in-flight five-second request, then the
            // drawer continues with the category that still has focus.
            if (groupName != activeProbeGroupName) {
                pendingProbeGroupName = groupName
            }
            return
        }
        startCategoryProbe(groupName)
    }

    private fun startCategoryProbe(groupName: String) {
        activeProbeGroupName = groupName
        categoryProbeJob = viewModelScope.launch {
            try {
                // Keep the user's D-pad browse position stable while the
                // sequential network checks run. Refresh once at completion;
                // rebuilding the catalog every few hundred milliseconds
                // repeatedly rebinds both RecyclerViews and steals TV focus.
                val checked = channelProbeRepository.probeGroupIfStale(groupName)
                if (checked > 0) {
                    refreshChannelCatalog()
                }
            } finally {
                activeProbeGroupName = null
                val pending = pendingProbeGroupName
                pendingProbeGroupName = null
                if (_selectorVisible.value && pending != null && pending == selectedGroupName) {
                    requestCategoryProbe(pending)
                }
            }
        }
    }

    private suspend fun refreshChannelCatalog(preserveSelectedChannelKey: String? = null) {
        val refreshedChannels = catalogRepository.getMergedChannels()
        // Catalog loading suspends for database work. Snapshot the D-pad
        // cursor only after it completes so navigation performed while the
        // query is in flight is not overwritten by an older selection.
        val preserveDrawerSelection = _selectorVisible.value
        val previousGroupName = if (preserveDrawerSelection) selectedGroupName else ""
        val previousGroupIndex = _selectedGroupIndex.value
        val previousPane = _selectorPane.value
        val previousChannelIndex = _selectedChannelIndex.value
        val previousChannelKey = if (preserveDrawerSelection) {
            preserveSelectedChannelKey
                ?: selectedChannelKey
                ?: _visibleChannels.value.getOrNull(_selectedChannelIndex.value)?.key
        } else {
            null
        }
        currentMergedChannels = refreshedChannels
        catalogLoadedAtMillis = System.currentTimeMillis()
        _mergedChannelList.value = currentMergedChannels
        refreshCurrentMergedContext()
        if (preserveDrawerSelection) {
            val groups = buildChannelGroups()
            _channelGroups.value = groups
            if (groups.isEmpty()) {
                // A playlist refresh may briefly publish an empty catalog
                // between deleting old rows and inserting the replacement.
                // Keep the browse cursor identity so the next snapshot can
                // restore it instead of silently resetting the TV remote to
                // the first category/channel.
                _selectedGroupIndex.value = previousGroupIndex
                _visibleChannels.value = emptyList()
                _selectedChannelIndex.value = previousChannelIndex
                selectedGroupName = previousGroupName
                selectedChannelKey = previousChannelKey
                _selectorPane.value = previousPane
                return
            }

            val groupIndex = restoredGroupIndex(
                previousName = previousGroupName,
                newNames = groups.map { it.name },
                fallbackIndex = previousGroupIndex
            ).coerceIn(0, groups.lastIndex)
            val restoredGroup = groups[groupIndex]
            val sameGroup = restoredGroup.name == previousGroupName
            _selectedGroupIndex.value = groupIndex
            selectedGroupName = restoredGroup.name
            _visibleChannels.value = restoredGroup.channels
            val channelKeys = _visibleChannels.value.map { it.key }
            val currentChannelIndex = channelKeys.indexOfFirst { it == _currentMergedChannel.value?.key }
            val fallbackChannelIndex = if (sameGroup) {
                previousChannelIndex
            } else {
                currentChannelIndex.takeIf { it >= 0 } ?: previousChannelIndex
            }
            _selectedChannelIndex.value = restoredSelectionIndex(
                previousKey = previousChannelKey,
                newKeys = channelKeys,
                groupUnchanged = sameGroup,
                fallbackIndex = fallbackChannelIndex
            ).coerceIn(0, channelKeys.lastIndex.coerceAtLeast(0))
            selectedChannelKey = channelKeys.getOrNull(_selectedChannelIndex.value)
            _selectorPane.value = previousPane
            requestCategoryProbe(restoredGroup.name)
            return
        }
        rebuildGroups()
        syncSelectionWithCurrent()
    }

    private fun refreshCurrentMergedContext() {
        val current = _currentChannel.value ?: return
        val refreshed = currentMergedChannels.firstOrNull { merged ->
            merged.sources.any { source -> source.channel.id == current.id || source.channel.url == current.url }
        }
        _currentMergedChannel.value = refreshed
        _currentSource.value = refreshed?.sources?.firstOrNull { source -> source.channel.id == current.id }
            ?: refreshed?.preferredSource
        currentMergedChannelIndex = currentMergedChannels.indexOfFirst { it.key == refreshed?.key }
    }

    private fun syncSelectionWithCurrent() {
        if (_channelGroups.value.isEmpty()) {
            rebuildGroups()
            return
        }

        // Database updates from the probe refresh must not pull a browsing
        // user back to the group of the channel currently playing.
        if (_selectorVisible.value && selectedGroupName.isNotBlank()) {
            val preservedGroupIndex = _channelGroups.value.indexOfFirst { it.name == selectedGroupName }
            if (preservedGroupIndex >= 0) {
                updateSelectedGroup(preservedGroupIndex)
                return
            }
        }

        val current = resolveMergedForChannel(_currentChannel.value) ?: _currentMergedChannel.value ?: return
        val groupIndex = _channelGroups.value.indexOfFirst { group ->
            group.name == playbackNavigationGroupName && group.channels.any { it.key == current.key }
        }.takeIf { it >= 0 } ?: _channelGroups.value.indexOfFirst { group ->
            group.channels.any { it.key == current.key }
        }.takeIf { it >= 0 } ?: return
        updateSelectedGroup(groupIndex)
        val channelIndex = _visibleChannels.value.indexOfFirst { it.key == current.key }
        if (channelIndex >= 0) {
            _selectedChannelIndex.value = channelIndex
            selectedChannelKey = current.key
        }
        playbackNavigationGroupName = _channelGroups.value.getOrNull(groupIndex)?.name.orEmpty()
    }

    private fun rememberSelectedChannelKey() {
        selectedChannelKey = _visibleChannels.value.getOrNull(_selectedChannelIndex.value)?.key
    }
}
