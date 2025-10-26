package com.tvplayer.app.ui.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import com.tvplayer.app.R
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.databinding.ActivityPlayerBinding
import com.tvplayer.app.ui.player.adapter.ChannelListAdapter
import com.tvplayer.app.util.Constants
import com.tvplayer.app.util.PlayerHelper
import com.tvplayer.app.util.PreferencesHelper
import com.tvplayer.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerView: PlayerView
    private val viewModel: PlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var currentChannel: Channel? = null
    private var isUiVisible = true
    private var hideUiRunnable: Runnable? = null
    private lateinit var preferences: PreferencesHelper

    companion object {
        private const val UI_HIDE_DELAY = 5000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playerView = binding.playerView
        preferences = PreferencesHelper(this)

        setupPlayer()
        setupUi()
        setupObservers()
        loadIntentData()
    }

    private fun setupPlayer() {
        player = PlayerHelper.createPlayer(this, true).apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> showBuffering()
                        Player.STATE_READY -> hideBuffering()
                        Player.STATE_ENDED -> onPlaybackEnded()
                        else -> {}
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
        playerView.useController = false
    }

    private fun setupUi() {
        binding.playPauseButton.setOnClickListener { togglePlayPause() }
        binding.stopButton.setOnClickListener { finish() }
        binding.previousButton.setOnClickListener { playPreviousChannel() }
        binding.nextButton.setOnClickListener { playNextChannel() }
        binding.channelInfoButton.setOnClickListener { toggleChannelInfo() }
        binding.qualityButton.setOnClickListener { showQualityOptions() }
        binding.audioButton.setOnClickListener { showToast("音频选项 - 开发中") }
        binding.subtitleButton.setOnClickListener { toggleSubtitles() }
        binding.fullscreenButton.setOnClickListener { showToast("已全屏显示") }

        binding.channelListView.visibility = View.GONE
        setupChannelList()

        binding.progressBar.visibility = View.GONE
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.currentChannel.collectLatest { channel ->
                    channel?.let { playChannel(it) }
                }
                viewModel.playbackError.collectLatest { it?.let { showToast(it) } }
                viewModel.buffering.collectLatest { if (it) showBuffering() else hideBuffering() }
            }
        }
    }

    private fun setupChannelList() {
        val adapter = ChannelListAdapter { channel ->
            viewModel.selectChannel(channel.id)
            binding.channelListView.visibility = View.GONE
        }
        binding.channelRecyclerView.adapter = adapter
        binding.channelRecyclerView.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        lifecycleScope.launch {
            viewModel.channelList.collectLatest { adapter.submitList(it) }
        }
    }

    private fun playChannel(channel: Channel) {
        currentChannel = channel
        val mediaSource = PlayerHelper.createMediaSource(this, channel)
        player?.setMediaSource(mediaSource)
        player?.prepare()
        player?.playWhenReady = true

        preferences.lastPlayedChannelId = channel.id
        channel.logo?.let {
            Glide.with(this).load(it)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(binding.channelLogo)
        }

        scheduleUiHide()
    }

    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
            cancelHideUi()
            scheduleUiHide()
        }
    }

    private fun playPreviousChannel() = viewModel.playPreviousChannel()
    private fun playNextChannel() = viewModel.playNextChannel()

    private fun toggleChannelInfo() {
        isUiVisible = !isUiVisible
        binding.infoPanel.visibility = if (isUiVisible) View.VISIBLE else View.GONE
        cancelHideUi()
        if (isUiVisible) scheduleUiHide()
    }

    private fun showQualityOptions() {
        val qualities = arrayOf("自动", "1080p", "720p", "480p", "360p")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择清晰度")
            .setItems(qualities) { _, which ->
                val quality = qualities[which]
                viewModel.videoQuality = quality.lowercase()
                currentChannel?.let { playChannel(it) }
            }
            .show()
    }

    private fun toggleSubtitles() {
        viewModel.enableSubtitles = !viewModel.enableSubtitles
        showToast(if (viewModel.enableSubtitles) "字幕已开启" else "字幕已关闭")
    }

    private fun showBuffering() {
        binding.progressBar.visibility = View.VISIBLE
        viewModel.setBuffering(true)
    }

    private fun hideBuffering() {
        binding.progressBar.visibility = View.GONE
        viewModel.setBuffering(false)
    }

    private fun onPlaybackEnded() = showToast("播放结束")

    private fun handlePlayerError(error: com.google.android.exoplayer2.PlaybackException) {
        val message = "播放错误: ${error.message ?: "未知错误"}"
        showToast(message)
    }

    private fun scheduleUiHide() {
        hideUiRunnable?.let { binding.root.removeCallbacks(it) }
        hideUiRunnable = Runnable { hideUi() }
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

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun loadIntentData() {
        val channelId = intent.getLongExtra(Constants.EXTRA_CHANNEL_ID, -1L)
        val channelName = intent.getStringExtra(Constants.EXTRA_CHANNEL_NAME)
        val channelUrl = intent.getStringExtra(Constants.EXTRA_CHANNEL_URL)

        if (channelId != -1L) {
            viewModel.loadChannel(channelId)
        } else if (channelName != null && channelUrl != null) {
            val channel = Channel.create(channelName, channelUrl, -1L)
            playChannel(channel)
        }
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

    private fun toggleChannelList() {
        if (binding.channelListView.visibility == View.VISIBLE) {
            binding.channelListView.visibility = View.GONE
        } else {
            binding.channelListView.visibility = View.VISIBLE
            viewModel.loadChannelList()
        }
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
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
}