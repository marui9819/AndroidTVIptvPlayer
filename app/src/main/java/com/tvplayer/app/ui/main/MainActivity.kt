package com.tvplayer.app.ui.main

import android.util.Log
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowPresenter
import com.tvplayer.app.R
import com.tvplayer.app.App
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import com.tvplayer.app.databinding.ActivityMainBinding
import com.tvplayer.app.databinding.ItemChannelBinding
import com.tvplayer.app.databinding.ItemPlaylistBinding
import com.tvplayer.app.ui.search.SearchActivity
import com.tvplayer.app.ui.playlist.PlaylistImportActivity
import com.tvplayer.app.ui.settings.SettingsActivity
import com.tvplayer.app.ui.subscription.SubscriptionManagementActivity
import com.tvplayer.app.util.PlayerHelper
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.util.AppVersion
import com.tvplayer.app.util.TvDialogStyler
import com.tvplayer.app.util.ChannelUserState
import com.tvplayer.app.util.PlaybackCornerOverlay
import com.tvplayer.app.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val AUTO_PLAY_RETRY_DELAY_MS = 1000L
        private const val AUTO_PLAY_MAX_ATTEMPTS = 30
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cornerOverlay: PlaybackCornerOverlay
    private val userState by lazy { ChannelUserState.get(this) }
    private val viewModel: MainViewModel by viewModels()
    private val preferences by lazy { PreferencesHelper(this) }
    private lateinit var browseFragment: BrowseSupportFragment
    private lateinit var gestureDetector: GestureDetector
    private var hasAutoStartedPlayback = false
    private var autoStartInProgress = false
    private var autoStartAttemptCount = 0
    private var showingDetailPage = false
    private var currentCollectionTitle: String? = null
    private var currentCollectionChannels: List<Channel> = emptyList()
    private var homeResumed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var longPressTriggered = false
    private var longPressDownX = 0f
    private var longPressDownY = 0f
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        openSettings()
    }
    private val playlistClickListener = object : PlaylistClickListener {
        override fun onPlaylistClick(playlist: Playlist) = openPlaylist(playlist)
    }
    private val channelClickListener = object : ChannelClickListener {
        override fun onChannelClick(channel: Channel) {
            startPlayback(channel)
        }
    }
    private val homeFeatures = listOf(
        HomeFeature(HomeFeature.Id.CONTINUE, "继续观看", "打开上次频道", android.R.drawable.ic_media_play),
        HomeFeature(HomeFeature.Id.ALL_CHANNELS, "频道总览", "浏览全部可用频道", android.R.drawable.ic_menu_view),
        HomeFeature(HomeFeature.Id.FAVORITES, "收藏频道", "快速打开收藏", android.R.drawable.btn_star_big_on),
        HomeFeature(HomeFeature.Id.RECENT, "最近播放", "继续最近观看内容", android.R.drawable.ic_menu_recent_history),
        HomeFeature(HomeFeature.Id.SEARCH, "搜索频道", "按名称查找频道", android.R.drawable.ic_menu_search),
        HomeFeature(HomeFeature.Id.SUBSCRIPTIONS, "订阅管理", "管理视频源和 EPG", android.R.drawable.ic_menu_manage),
        HomeFeature(HomeFeature.Id.IMPORT, "导入播放列表", "添加本地或远程源", android.R.drawable.ic_menu_upload),
        HomeFeature(HomeFeature.Id.SETTINGS, "系统设置", "播放和网络选项", android.R.drawable.ic_menu_preferences)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.instance.onAppActivityCreated()
        hasAutoStartedPlayback = savedInstanceState?.getBoolean("playback_dispatched") ?: false
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.versionBadge.text = AppVersion.label(this)
        cornerOverlay = PlaybackCornerOverlay(
            binding.root.findViewById(R.id.cornerOverlay),
            binding.root.findViewById(R.id.currentTimeBadge),
            binding.versionBadge
        )
        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onLongPress(e: MotionEvent) {
                    openSettings()
                }
            }
        )
        val touchForwarder = android.view.View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            handleManualLongPress(event)
            false
        }
        binding.mainContainer.setOnTouchListener(touchForwarder)
        binding.mainContainer.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        binding.browseFragmentContainer.setOnTouchListener(touchForwarder)
        binding.browseFragmentContainer.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        browseFragment = supportFragmentManager.findFragmentByTag("browse_fragment") as? BrowseSupportFragment
            ?: BrowseSupportFragment().also { fragment ->
                supportFragmentManager.commit {
                    replace(R.id.browse_fragment_container, fragment, "browse_fragment")
                }
            }
        browseFragment.headersState = BrowseSupportFragment.HEADERS_DISABLED
        browseFragment.isHeadersTransitionOnBackEnabled = false
        browseFragment.title = getString(R.string.app_name)
        browseFragment.onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is HomeFeature -> handleHomeFeature(item)
                is Playlist -> openPlaylist(item)
                is Channel -> startPlayback(item)
            }
        }
        binding.browseFragmentContainer.post {
            browseFragment.view?.setOnTouchListener(touchForwarder)
            browseFragment.view?.setOnLongClickListener {
                openSettings()
                true
            }
        }

        lifecycleScope.launch {
            viewModel.playlists.collect {
                renderRows()
                if (it.isNotEmpty() && !hasAutoStartedPlayback) {
                    autoStartAttemptCount = 0
                }
                maybeAutoStartPlayback(forceRetry = true)
            }
        }
        lifecycleScope.launch {
            viewModel.favoriteChannels.collect { renderRows() }
        }
        lifecycleScope.launch {
            viewModel.recentlyPlayedChannels.collect { renderRows() }
        }

        viewModel.refreshPlaylists()
        maybeAutoStartPlayback()
    }

    override fun onResume() {
        super.onResume()
        homeResumed = true
        cornerOverlay.start(userState.showClock, userState.showVersion)
        viewModel.loadRecentlyPlayedChannels()
        if (::browseFragment.isInitialized) renderRows()
        if (!hasAutoStartedPlayback) {
            mainHandler.postDelayed({ maybeAutoStartPlayback(forceRetry = true) }, 300L)
        }
    }

    override fun onPause() {
        homeResumed = false
        cornerOverlay.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("playback_dispatched", hasAutoStartedPlayback)
        super.onSaveInstanceState(outState)
    }

    private fun startPlayback(channel: Channel) {
        hasAutoStartedPlayback = true
        PlayerHelper.startChannelPlayback(this, channel)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        handleManualLongPress(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun handleManualLongPress(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                longPressDownX = event.x
                longPressDownY = event.y
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                val movedTooFar = kotlin.math.abs(event.x - longPressDownX) > touchSlop ||
                    kotlin.math.abs(event.y - longPressDownY) > touchSlop
                if (movedTooFar) {
                    mainHandler.removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
            }
        }
    }

    private fun openSettings() {
        if (!isFinishing) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun resolveInstalledVersionName(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        return packageInfo.versionName ?: "unknown"
    }

    private fun maybeAutoStartPlayback(forceRetry: Boolean = false) {
        // The home screen is the primary TV navigation surface.  In particular,
        // do not reopen the player after a playback failure unless the user has
        // explicitly opted into auto-play in Settings.
        if (!preferences.autoPlayEnabled) return
        if (hasAutoStartedPlayback || autoStartInProgress || isFinishing || isDestroyed) return
        if (autoStartAttemptCount >= AUTO_PLAY_MAX_ATTEMPTS && !forceRetry) return

        autoStartInProgress = true
        autoStartAttemptCount += 1

        lifecycleScope.launch {
            val startupChannel = viewModel.resolveStartupChannel()
            if (startupChannel == null) {
                Log.d(TAG, "No startup channel resolved on attempt=$autoStartAttemptCount")
                autoStartInProgress = false
                return@launch
            }
            Log.d(TAG, "Auto starting playback for channel=${startupChannel.name}, id=${startupChannel.id}")
            hasAutoStartedPlayback = true
            autoStartInProgress = false
            PlayerHelper.startChannelPlayback(this@MainActivity, startupChannel)
        }

        if (!hasAutoStartedPlayback && autoStartAttemptCount < AUTO_PLAY_MAX_ATTEMPTS) {
            Log.d(TAG, "Startup channel not ready, retry attempt=$autoStartAttemptCount")
            mainHandler.postDelayed(
                { maybeAutoStartPlayback() },
                AUTO_PLAY_RETRY_DELAY_MS
            )
        } else if (!hasAutoStartedPlayback) {
            autoStartInProgress = false
        }
    }

    private fun renderRows(forceHome: Boolean = false) {
        // A history write while the player is foreground must not inflate the
        // entire home UI in the background or reset the user's collection page.
        if (!homeResumed) return
        if (showingDetailPage && !forceHome && currentCollectionTitle != null) {
            val channels = when (currentCollectionTitle) {
                "最近播放" -> viewModel.recentlyPlayedChannels.value
                "收藏频道" -> viewModel.favoriteChannels.value
                else -> currentCollectionChannels
            }
            showChannelCollection(currentCollectionTitle!!, channels)
            return
        }
        showingDetailPage = false
        currentCollectionTitle = null
        currentCollectionChannels = emptyList()
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

        // Keep the home screen within one D-pad viewport: four viewing actions
        // followed by four management actions form a readable 2 × 4 TV grid,
        // instead of one long carousel that hides half of the application.
        val viewingFeatureAdapter = ArrayObjectAdapter(FeaturePresenter())
        homeFeatures.take(4).forEach { viewingFeatureAdapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(0, "常用观看"), viewingFeatureAdapter))

        val managementFeatureAdapter = ArrayObjectAdapter(FeaturePresenter())
        homeFeatures.drop(4).forEach { managementFeatureAdapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(1, "管理与设置"), managementFeatureAdapter))

        if (viewModel.playlists.value.isNotEmpty()) {
            val playlistAdapter = ArrayObjectAdapter(PlaylistPresenter())
            viewModel.playlists.value.forEach { playlistAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(2, "播放列表"), playlistAdapter))
        }

        if (viewModel.favoriteChannels.value.isNotEmpty()) {
            val favoritesAdapter = ArrayObjectAdapter(ChannelPresenter())
            viewModel.favoriteChannels.value.forEach { favoritesAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(3, "收藏频道"), favoritesAdapter))
        }

        if (viewModel.recentlyPlayedChannels.value.isNotEmpty()) {
            val recentAdapter = ArrayObjectAdapter(ChannelPresenter())
            viewModel.recentlyPlayedChannels.value.forEach { recentAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(4, "最近播放"), recentAdapter))
        }

        browseFragment.adapter = rowsAdapter
    }

    private fun openPlaylist(playlist: Playlist) {
        showingDetailPage = true
        lifecycleScope.launch {
            val channels = viewModel.getChannelsForPlaylist(playlist.id)
            showChannelCollection(playlist.name, channels)
        }
    }

    private fun showChannelCollection(title: String, channels: List<Channel>) {
        currentCollectionTitle = title
        currentCollectionChannels = channels
        showingDetailPage = true
        val rowAdapter = ArrayObjectAdapter(ChannelPresenter())
        channels.forEach { rowAdapter.add(it) }
        browseFragment.adapter = ArrayObjectAdapter(ListRowPresenter()).apply {
            add(ListRow(HeaderItem(0, title), rowAdapter))
        }
    }

    private fun handleHomeFeature(feature: HomeFeature) {
        when (feature.id) {
            HomeFeature.Id.CONTINUE -> lifecycleScope.launch {
                val channel = viewModel.resolveStartupChannel()
                if (channel != null) {
                    startPlayback(channel)
                } else {
                    showToast("暂无可播放频道")
                }
            }
            HomeFeature.Id.ALL_CHANNELS -> lifecycleScope.launch {
                showChannelCollection("全部频道", viewModel.getAllAvailableChannels())
                showingDetailPage = true
            }
            HomeFeature.Id.FAVORITES -> {
                if (viewModel.favoriteChannels.value.isEmpty()) {
                    showToast("暂无收藏频道")
                } else {
                    showChannelCollection("收藏频道", viewModel.favoriteChannels.value)
                    showingDetailPage = true
                }
            }
            HomeFeature.Id.RECENT -> {
                if (viewModel.recentlyPlayedChannels.value.isEmpty()) {
                    showToast("暂无最近播放记录")
                } else {
                    showChannelCollection("最近播放", viewModel.recentlyPlayedChannels.value)
                    showingDetailPage = true
                }
            }
            HomeFeature.Id.SEARCH -> startActivity(Intent(this, SearchActivity::class.java))
            HomeFeature.Id.SUBSCRIPTIONS -> startActivity(Intent(this, SubscriptionManagementActivity::class.java))
            HomeFeature.Id.IMPORT -> startActivity(Intent(this, PlaylistImportActivity::class.java))
            HomeFeature.Id.SETTINGS -> openSettings()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (showingDetailPage) {
            renderRows(forceHome = true)
            return
        }
        showExitConfirmDialog()
    }

    private fun showExitConfirmDialog() {
        val dialogContext = ContextThemeWrapper(this, R.style.Theme_TVPlayer)
        val dialog = MaterialAlertDialogBuilder(dialogContext)
            .setTitle("退出应用")
            .setMessage("确定要退出电视直播吗？")
            .setPositiveButton("退出") { _, _ -> App.instance.requestExit(this) }
            .setNegativeButton("取消", null)
            .create()
        TvDialogStyler.apply(dialog)
        dialog.show()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSearchRequested(): Boolean {
        startActivity(Intent(this, SearchActivity::class.java))
        return true
    }

    private inner class PlaylistPresenter : Presenter() {
        private inner class PlaylistViewHolder(
            val binding: ItemPlaylistBinding
        ) : ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
            val binding = ItemPlaylistBinding.inflate(layoutInflater, parent, false)
            return PlaylistViewHolder(binding)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            val binding = (viewHolder as PlaylistViewHolder).binding
            val playlist = item as Playlist
            binding.playlist = playlist
            binding.listener = playlistClickListener
            binding.playlistName.text = playlist.name
            binding.playlistDescription.text = playlist.description ?: if (playlist.isLocal()) "本地播放列表" else "远程播放列表"
            binding.channelCount.text = "${playlist.channelCount} 个频道"
            binding.lastUpdated.text = playlist.getRefreshTimeString()
            binding.statusText.text = if (playlist.isDefault) "默认" else if (playlist.isActive) "启用" else "停用"
            binding.statusIcon.setImageResource(
                if (playlist.isActive) android.R.drawable.ic_menu_upload else android.R.drawable.ic_menu_close_clear_cancel
            )
            binding.executePendingBindings()
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
    }

    private inner class ChannelPresenter : Presenter() {
        private inner class ChannelViewHolder(
            val binding: ItemChannelBinding
        ) : ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup): ViewHolder {
            val binding = ItemChannelBinding.inflate(layoutInflater, parent, false)
            return ChannelViewHolder(binding)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            val binding = (viewHolder as ChannelViewHolder).binding
            val channel = item as Channel
            binding.channel = channel
            binding.listener = channelClickListener
            binding.channelGroup.text = channel.group ?: ""
            binding.favoriteIcon.setImageResource(
                if (channel.isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            )
            binding.availabilityIndicator.setImageResource(
                if (channel.isAvailable) android.R.drawable.presence_online else android.R.drawable.presence_busy
            )
            binding.executePendingBindings()
            binding.channelName.text = userState.displayName(channel)
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
    }

    private inner class FeaturePresenter : Presenter() {
        private inner class FeatureViewHolder(
            val root: LinearLayout,
            val icon: ImageView,
            val title: TextView,
            val subtitle: TextView
        ) : ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                background = androidx.core.content.ContextCompat.getDrawable(
                    context,
                    R.drawable.feature_card_background
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
                // Four cards fit inside the 1080p Leanback safe area. Keeping
                // this compact avoids a hidden fifth card or horizontal scroll
                // for the two 4-card home rows.
                layoutParams = ViewGroup.LayoutParams(dp(210), dp(150))
            }
            val icon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    bottomMargin = dp(8)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            val title = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val subtitle = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            root.addView(icon)
            root.addView(title, LinearLayout.LayoutParams(-1, dp(30)))
            root.addView(subtitle, LinearLayout.LayoutParams(-1, dp(24)))
            return FeatureViewHolder(root, icon, title, subtitle)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
            val holder = viewHolder as FeatureViewHolder
            val feature = item as HomeFeature
            holder.icon.setImageResource(feature.iconResId)
            holder.title.text = feature.title
            holder.subtitle.text = feature.subtitle
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
    }
}
