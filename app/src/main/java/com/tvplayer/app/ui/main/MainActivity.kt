package com.tvplayer.app.ui.main

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import com.bumptech.glide.Glide
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.databinding.ActivityMainBinding
import com.tvplayer.app.ui.player.PlayerActivity
import com.tvplayer.app.ui.settings.SettingsActivity
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.PlayerHelper
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var browseFragment: BrowseSupportFragment
    private lateinit var backgroundManager: BackgroundManager
    private lateinit var preferences: PreferencesHelper

    companion object {
        const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize components
        preferences = PreferencesHelper(this)
        backgroundManager = BackgroundManager.getInstance(this)

        // Setup UI
        setupBrowseFragment()
        setupObservers()
        setupClickListeners()
        setupBackgroundManager()

        // Load initial data
        viewModel.refreshPlaylists()
    }

    private fun setupBrowseFragment() {
        browseFragment = BrowseSupportFragment().apply {
            headersState = BrowseSupportFragment.HEADERS_DISABLED
            isHeadersTransitionOnBackEnabled = true
        }

        supportFragmentManager.commit {
            replace(R.id.main_browse_fragment, browseFragment)
        }

        browseFragment.onItemViewClickedListener = OnItemViewClickedListener { itemViewHolder, item, rowViewHolder, row ->
            when (item) {
                is Playlist -> handlePlaylistClick(item as Playlist)
                is Channel -> handleChannelClick(item as Channel)
            }
        }

        browseFragment.onItemViewSelectedListener = OnItemViewSelectedListener { itemViewHolder, item, rowViewHolder, row ->
            // Handle item selection for UI updates
            if (item is Channel) {
                showChannelDetails(item as Channel)
            }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.playlists.collectLatest { playlists ->
                updatePlaylistsUI(playlists)
            }
        }

        lifecycleScope.launch {
            viewModel.favoriteChannels.collectLatest { channels ->
                updateFavoriteChannelsUI(channels)
            }
        }

        lifecycleScope.launch {
            viewModel.recentlyPlayedChannels.collectLatest { channels ->
                updateRecentlyPlayedUI(channels)
            }
        }

        lifecycleScope.launch {
            viewModel.loading.collectLatest { isLoading ->
                binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupClickListeners() {
        binding.settingsButton.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        binding.refreshButton.setOnClickListener {
            viewModel.refreshAllPlaylists()
        }

        binding.searchButton.setOnClickListener {
            // Launch search
            val intent = android.content.Intent(this, com.tvplayer.app.ui.search.SearchActivity::class.java)
            startActivity(intent)
        }

        binding.importButton.setOnClickListener {
            // Launch playlist import
            val intent = android.content.Intent(this, com.tvplayer.app.ui.playlist.PlaylistImportActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupBackgroundManager() {
        backgroundManager.attach(window)
        updateBackground()
    }

    private fun updatePlaylistsUI(playlists: List<Playlist>) {
        if (playlists.isEmpty()) return

        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

        // Add playlists row
        val playlistAdapter = ArrayObjectAdapter(PlaylistPresenter())
        playlists.forEach { playlist ->
            playlistAdapter.add(playlist)
        }
        rowsAdapter.add(ListRow(HeaderItem(0, "播放列表"), playlistAdapter))

        // Add favorite channels row
        val favoriteAdapter = ArrayObjectAdapter(ChannelPresenter())
        viewModel.favoriteChannels.value?.let { favorites ->
            favorites.forEach { channel ->
                favoriteAdapter.add(channel)
            }
        }
        rowsAdapter.add(ListRow(HeaderItem(1, "收藏频道"), favoriteAdapter))

        // Add recently played row
        val recentAdapter = ArrayObjectAdapter(ChannelPresenter())
        viewModel.recentlyPlayedChannels.value?.let { recent ->
            recent.forEach { channel ->
                recentAdapter.add(channel)
            }
        }
        rowsAdapter.add(ListRow(HeaderItem(2, "最近播放"), recentAdapter))

        browseFragment.adapter = rowsAdapter
    }

    private fun updateFavoriteChannelsUI(channels: List<Channel>) {
        // Update favorite channels in the UI
        channels.take(10).let { topFavorites ->
            // Refresh the favorites row
            viewModel.playlists.value?.let { playlists ->
                updatePlaylistsUI(playlists)
            }
        }
    }

    private fun updateRecentlyPlayedUI(channels: List<Channel>) {
        // Update recently played channels in the UI
        channels.take(10).let { recent ->
            // Refresh the recently played row
            viewModel.playlists.value?.let { playlists ->
                updatePlaylistsUI(playlists)
            }
        }
    }

    private fun handlePlaylistClick(playlist: Playlist) {
        // Navigate to channel list for this playlist
        showPlaylistChannels(playlist)
    }

    private fun handleChannelClick(channel: Channel) {
        // Start playing the channel
        PlayerHelper.startChannelPlayback(this, channel)
    }

    private fun showPlaylistChannels(playlist: Playlist) {
        lifecycleScope.launch {
            val channels = viewModel.getChannelsForPlaylist(playlist.id)
            if (channels.isNotEmpty()) {
                // Show channel list dialog or fragment
                val adapter = ArrayObjectAdapter(ChannelPresenter())
                channels.forEach { channel ->
                    adapter.add(channel)
                }

                val row = ListRow(HeaderItem(0, playlist.name), adapter)
                val rowsAdapter = ArrayObjectAdapter(ListRowPresenter()).apply { add(row) }

                browseFragment.adapter = rowsAdapter
            }
        }
    }

    private fun showChannelDetails(channel: Channel) {
        // Show channel details in a dialog or side panel
        binding.channelInfoPanel.visibility = View.VISIBLE
        binding.channelInfoName.text = channel.name
        binding.channelInfoGroup.text = channel.group ?: "未知分组"
        binding.channelInfoType.text = channel.getStreamType().name

        // Load channel logo
        channel.logo?.let { logoUrl ->
            Glide.with(this)
                .load(logoUrl)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.channelInfoLogo)
        }

        // Hide after delay
        binding.root.postDelayed({
            binding.channelInfoPanel.visibility = View.GONE
        }, 5000)
    }

    private fun updateBackground() {
        // Set a default background image
        backgroundManager.drawable = resources.getDrawable(android.R.color.black, null)
    }

    private fun showLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
                true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                val intent = android.content.Intent(this, com.tvplayer.app.ui.search.SearchActivity::class.java)
                startActivity(intent)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                viewModel.playLastWatchedChannel()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh data when returning to main activity
        viewModel.loadPlaylists()
        viewModel.loadFavoriteChannels()
        viewModel.loadRecentlyPlayedChannels()
    }

    private inner class PlaylistPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_playlist, parent, false)
            PlaylistViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
            val playlist = item as Playlist
            val vh = viewHolder as PlaylistViewHolder
            vh.bind(playlist)
        }

        override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
            // Clean up if needed
        }
    }

    private inner class PlaylistViewHolder(view: View) : Presenter.ViewHolder(view) {
        private val binding = ItemPlaylistBinding.bind(view)

        fun bind(playlist: Playlist) {
            binding.playlistName.text = playlist.name
            binding.playlistDescription.text = playlist.description ?: "远程播放列表"
            binding.playlistChannelCount.text = "${playlist.channelCount} 个频道"
            binding.playlistLastUpdated.text = playlist.getRefreshTimeString()

            // Set refresh status
            binding.playlistRefreshIcon.setImageResource(
                if (playlist.shouldRefresh()) android.R.drawable.ic_menu_refresh
                else android.R.drawable.ic_menu_share
            )
        }
    }

    private inner class ChannelPresenter : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel, parent, false)
            ChannelViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
            val channel = item as Channel
            val vh = viewHolder as ChannelViewHolder
            vh.bind(channel)
        }

        override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
            // Clean up if needed
        }
    }

    private inner class ChannelViewHolder(view: View) : Presenter.ViewHolder(view) {
        private val binding = ItemChannelBinding.bind(view)

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name
            binding.channelNumber.text = "${channel.order + 1}"
            binding.channelGroup.text = channel.group ?: ""

            // Load channel logo
            channel.logo?.let { logoUrl ->
                Glide.with(view.context)
                    .load(logoUrl)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(binding.channelLogo)
            }

            // Set favorite icon
            binding.favoriteIcon.setImageResource(
                if (channel.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )

            // Set availability indicator
            binding.availabilityIndicator.setImageResource(
                if (channel.isAvailable) android.R.drawable.presence_online
                else android.R.drawable.presence_busy
            )
        }
    }
}