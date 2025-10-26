package com.tvplayer.app.ui.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.databinding.ActivityPlayerBinding
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.PlayerHelper
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerView: PlayerView
    private lateinit var viewModel: PlayerViewModel
    private var player: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var isUiVisible = true
    private var hideUiRunnable: Runnable? = null

    companion object {
        private const val UI_HIDE_DELAY = 5000L // 5 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup full screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playerView = binding.playerView

        // Setup ViewModel
        viewModel: PlayerViewModel by viewModels()

        // Initialize
        setupPlayer()
        setupUi()
        setupObservers()
        loadIntentData()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        if (player != null) {
            player?.playWhenReady = true
        }
        scheduleUiHide()
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
        cancelHideUi()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupResources()
    }

    private fun setupPlayer() {
        player = PlayerHelper.createPlayer(this, viewModel.useHardwareAcceleration).apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> showBuffering()
                        Player.STATE_READY -> hideBuffering()
                        Player.STATE_ENDED -> onPlaybackEnded()
                        Player.STATE_IDLE -> { /* Do nothing */ }
                    }
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    handlePlayerError(error)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.playPauseButton.setImageResource(
                        if (isPlaying) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    )
                }
            })
        }
        playerView.player = player
        playerView.useController = false // We'll use our custom UI
    }

    private fun setupUi() {
        // Setup control buttons
        binding.playPauseButton.setOnClickListener {
            togglePlayPause()
        }

        binding.stopButton.setOnClickListener {
            finish()
        }

        binding.previousButton.setOnClickListener {
            playPreviousChannel()
        }

        binding.nextButton.setOnClickListener {
            playNextChannel()
        }

        binding.channelInfoButton.setOnClickListener {
            toggleChannelInfo()
        }

        binding.qualityButton.setOnClickListener {
            showQualityOptions()
        }

        binding.audioButton.setOnClickListener {
            showAudioOptions()
        }

        binding.subtitleButton.setOnClickListener {
            showSubtitleOptions()
        }

        binding.fullscreenButton.setOnClickListener {
            toggleFullscreen()
        }

        // Setup channel list overlay
        binding.channelListView.visibility = View.GONE
        setupChannelList()

        // Setup progress indicator
        binding.progressBar.visibility = View.GONE
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.currentChannel.collectLatest { channel ->
                    channel?.let { playChannel(it) }
                }
            }

            viewModel.playbackError.collectLatest { error ->
                error?.let { showToast(it) }
            }

            viewModel.buffering.collectLatest { isBuffering ->
                if (isBuffering) showBuffering() else hideBuffering()
            }
        }
    }

    private fun loadIntentData() {
        val channelId = intent.getLongExtra(Constants.EXTRA_CHANNEL_ID, -1L)
        val channelName = intent.getStringExtra(Constants.EXTRA_CHANNEL_NAME)
        val channelUrl = intent.getStringExtra(Constants.EXTRA_CHANNEL_URL)

        if (channelId != -1L) {
            viewModel.loadChannel(channelId)
        } else if (channelName != null && channelUrl != null) {
            // Create channel from intent data
            val channel = Channel.create(channelName, channelUrl, -1L)
            playChannel(channel)
        }
    }

    private fun playChannel(channel: Channel) {
        currentChannel = channel
        updateChannelInfo(channel)

        val mediaSource = PlayerHelper.createMediaSource(this, channel)
        val trackSelectorParameters = PlayerHelper.getTrackSelectionParameters(
            viewModel.videoQuality,
            viewModel.enableSubtitles
        )

        player?.trackSelector?.parameters = trackSelectorParameters
        player?.setMediaSource(mediaSource)
        player?.prepare()
        player?.playWhenReady = true

        // Update preferences
        preferences.lastPlayedChannelId = channel.id
        preferences.setChannelCustomName(channel.id, channel.name)

        // Load channel logo if available
        channel.logo?.let { logoUrl ->
            Glide.with(this)
                .load(logoUrl)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(binding.channelLogo)
        }

        scheduleUiHide()
    }

    private fun updateChannelInfo(channel: Channel) {
        binding.channelNameText.text = channel.name
        binding.channelGroupText.text = channel.group ?: "Live TV"
        binding.channelNumberText.text = channel.order.toString()

        // Update EPG info if available
        channel.epg?.let { epgUrl ->
            loadEpgInfo(epgUrl)
        }
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
            } else {
                p.play()
            }
            cancelHideUi()
            scheduleUiHide()
        }
    }

    private fun playPreviousChannel() {
        viewModel.playPreviousChannel()
    }

    private fun playNextChannel() {
        viewModel.playNextChannel()
    }

    private fun toggleChannelInfo() {
        isUiVisible = !isUiVisible
        binding.infoPanel.visibility = if (isUiVisible) View.VISIBLE else View.GONE
        cancelHideUi()
        if (isUiVisible) scheduleUiHide()
    }

    private fun showQualityOptions() {
        // Show quality selection dialog
        val qualities = arrayOf(
            "自动", "1080p", "720p", "480p", "360p"
        )

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择清晰度")
            .setItems(qualities) { _, which ->
                val quality = qualities[which]
                viewModel.videoQuality = quality.lowercase()
                // Reapply quality setting
                currentChannel?.let { playChannel(it) }
            }
            .show()
    }

    private fun showAudioOptions() {
        // Show audio track selection dialog
        showToast("音频选项 - 开发中")
    }

    private fun showSubtitleOptions() {
        // Show subtitle selection dialog
        val subtitlesEnabled = viewModel.enableSubtitles
        viewModel.enableSubtitles = !subtitlesEnabled
        showToast(if (subtitlesEnabled) "字幕已关闭" else "字幕已开启")
    }

    private fun toggleFullscreen() {
        // This is already fullscreen for TV, but could handle orientation
        showToast("已全屏显示")
    }

    private fun showBuffering() {
        binding.progressBar.visibility = View.VISIBLE
        viewModel.setBuffering(true)
    }

    private fun hideBuffering() {
        binding.progressBar.visibility = View.GONE
        viewModel.setBuffering(false)
    }

    private fun onPlaybackEnded() {
        // Handle end of playback
        showToast("播放结束")
    }

    private fun handlePlayerError(error: com.google.android.exoplayer2.PlaybackException) {
        val errorMessage = when {
            error.cause is java.net.UnknownHostException -> "网络连接失败"
            error.cause is java.net.SocketTimeoutException -> "连接超时"
            error.cause is java.net.ConnectException -> "无法连接到服务器"
            error.cause is com.google.android.exoplayer2.upstream.HttpDataSource$InvalidResponseCodeException -> {
                val httpError = (error.cause as com.google.android.exoplayer2.upstream.HttpDataSource$InvalidResponseCodeException).responseCode
                "HTTP 错误: $httpError"
            }
            else -> "播放错误: ${error.message}"
        }
        viewModel.setError(errorMessage)
        showToast(errorMessage)
    }

    private fun setupChannelList() {
        // Setup RecyclerView for channel list
        val adapter = ChannelListAdapter { channel ->
            viewModel.selectChannel(channel.id)
            binding.channelListView.visibility = View.GONE
        }

        binding.channelRecyclerView.adapter = adapter
        binding.channelRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

        // Observe channel list updates
        lifecycleScope.launch {
            viewModel.channelList.collectLatest { channels ->
                adapter.submitList(channels)
            }
        }
    }

    private fun toggleChannelList() {
        if (binding.channelListView.visibility == View.VISIBLE) {
            binding.channelListView.visibility = View.GONE
        } else {
            binding.channelListView.visibility = View.VISIBLE
            viewModel.loadChannelList()
        }
    }

    private fun loadEpgInfo(epgUrl: String) {
        lifecycleScope.launch {
            // Load EPG information
            // Implementation would go here
            binding.epgInfoText.text = "节目信息加载中..."
        }
    }

    private fun scheduleUiHide() {
        hideUiRunnable?.let { binding.root.removeCallbacks(it) }
        hideUiRunnable = Runnable {
            hideUi()
        }
        binding.root.postDelayed(hideUiRunnable!!, UI_HIDE_DELAY)
    }

    private fun cancelHideUi() {
        hideUiRunnable?.let {
            binding.root.removeCallbacks(it)
            hideUiRunnable = null
        }
    }

    private fun hideUi() {
        binding.controlPanel.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE
        isUiVisible = false
    }

    private fun showUi() {
        binding.controlPanel.visibility = View.VISIBLE
        isUiVisible = true
        scheduleUiHide()
    }

    private fun initializePlayer() {
        if (player == null) {
            setupPlayer()
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun cleanupResources() {
        hideUiRunnable?.let {
            binding.root.removeCallbacks(it)
        }
        releasePlayer()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isUiVisible) {
                    togglePlayPause()
                } else {
                    showUi()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!isUiVisible) {
                    showUi()
                } else {
                    toggleChannelList()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                playPreviousChannel()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                playNextChannel()
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                showQualityOptions()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playPreviousChannel()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playNextChannel()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // Auto-save playback position
    override fun onPause() {
        super.onPause()
        currentChannel?.let { channel ->
            player?.currentPosition?.let { position ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val preferences = PreferencesHelper(this@PlayerActivity)
                        preferences.lastPlayedPosition = position
                    }
                }
            }
        }
    }
}