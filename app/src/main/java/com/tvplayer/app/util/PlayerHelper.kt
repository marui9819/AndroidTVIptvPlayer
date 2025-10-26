package com.tvplayer.app.util

import android.content.Context
import android.content.Intent
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.upstream.DefaultLoadControl
import com.google.android.exoplayer2.upstream.DefaultAllocator
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.smoothstreaming.SmoothStreamingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.C
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.ui.player.PlayerActivity
import kotlinx.coroutines.*

object PlayerHelper {

    fun createPlayer(context: Context, useHardwareAcceleration: Boolean): ExoPlayer {
        // Track selector for adaptive streaming
        val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory()
        val trackSelector = DefaultTrackSelector(context, adaptiveTrackSelectionFactory)

        // Load control for buffering
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                Constants.DefaultSettings.MIN_CACHE_SIZE.toInt(),
                Constants.DefaultSettings.MAX_CACHE_SIZE.toInt(),
                Constants.DefaultSettings.MIN_CACHE_SIZE.toInt(),
                Constants.DefaultSettings.MAX_CACHE_SIZE.toInt()
            )
            .setTargetBufferBytes(-1) // Let ExoPlayer decide
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Create player
        return ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build().apply {
                setPlayWhenReady(true)
                setHandleAudioBecomingNoisy(true)
            }
    }

    fun createMediaSource(context: Context, channel: Channel): MediaItem {
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(channel.getDisplayUrl())
            .setMediaId(channel.id.toString())
            .setMediaMetadata(
                com.google.android.exoplayer2.MediaItem.MediaMetadata.Builder()
                    .setTitle(channel.name)
                    .setArtworkUri(channel.logo?.let { android.net.Uri.parse(it) })
                    .build()
            )

        // Add user agent and headers
        if (channel.url.contains("migu")) {
            mediaItemBuilder.setHttpRequestHeaders(
                mapOf(
                    "User-Agent" to Constants.USER_AGENT,
                    "Referer" to "http://miguvideo.com",
                    "Accept" to "*/*"
                )
            )
        }

        return mediaItemBuilder.build()
    }

    fun createMediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(
                com.google.android.exoplayer2.upstream.DefaultDataSource.Factory(context)
                    .setUserAgent(Constants.USER_AGENT)
                    .setDefaultRequestProperties(
                        mapOf(
                            "Accept" to "*/*",
                            "Connection" to "keep-alive"
                        )
                    )
            )
    }

    fun getTrackSelectionParameters(
        quality: String = Constants.VideoQualities.AUTO,
        enableSubtitles: Boolean
    ): DefaultTrackSelector.Parameters {
        val parametersBuilder = DefaultTrackSelector.ParametersBuilder(context)
            .setVideoQualityThreshold(C.QUALITY_MAX, C.QUALITY_MIN)
            .setMinVideoSize(C.LENGTH_UNSET, C.LENGTH_UNSET)
            .setMaxVideoSize(C.LENGTH_UNSET, C.LENGTH_UNSET)
            .setViewportSize(C.LENGTH_UNSET, C.LENGTH_UNSET)
            .setMaxVideoBitrate(Integer.MAX_VALUE)
            .setMinVideoBitrate(Integer.MIN_VALUE)
            .setMaxVideoFrameRate(Integer.MAX_VALUE)
            .setMinVideoFrameRate(Integer.MIN_VALUE)
            .setViewportOrientationTo90()
            .setForceHighestSupportedBitrate(false)
            .setForceLowestSupportedBitrate(false)
            .setAudioOffloadPreferences(false)
            .setAllowVideoMixedMimeTypeAdaptiveness(true)
            .setAllowAudioMixedMimeTypeAdaptiveness(true)

        // Set subtitle preferences
        if (enableSubtitles) {
            parametersBuilder.setPreferredTextLanguage("zh") // Chinese subtitles preferred
            parametersBuilder.setSelectUndeterminedTextLanguage(true)
        }

        // Set quality preference
        when (quality.lowercase()) {
            "auto" -> parametersBuilder.setForceHighestSupportedBitrate(false)
            "1080p" -> parametersBuilder.setMaxVideoSize(1920, 1080)
            "720p" -> parametersBuilder.setMaxVideoSize(1280, 720)
            "480p" -> parametersBuilder.setMaxVideoSize(854, 480)
            "360p" -> parametersBuilder.setMaxVideoSize(640, 360)
        }

        return parametersBuilder.build()
    }

    fun startChannelPlayback(context: Context, channel: Channel) {
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(Constants.EXTRA_CHANNEL_ID, channel.id)
            putExtra(Constants.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(Constants.EXTRA_CHANNEL_URL, channel.url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }

    fun createMediaSourceForUrl(url: String): MediaItem {
        return MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                com.google.android.exoplayer2.MediaItem.MediaMetadata.Builder()
                    .setTitle("Live Stream")
                    .build()
            )
            .build()
    }

    suspend fun loadInitialPlaylist() = withContext(Dispatchers.IO) {
        try {
            val database = (App.instance).database
            val playlistDao = database.playlistDao()
            val channelDao = database.channelDao()

            // Check if there are any playlists
            val playlistCount = playlistDao.getActivePlaylistCount()
            if (playlistCount == 0) {
                // Create default playlist
                val defaultPlaylist = Playlist.createRemote(
                    name = "Migu Video",
                    url = Constants.DefaultPlaylists.MIGU_VIDEO,
                    description = "Default Migu Video Channels"
                )
                val playlistId = playlistDao.insertPlaylist(defaultPlaylist)
                playlistDao.setAsDefaultPlaylist(playlistId)

                // Load channels asynchronously
                val repository = PlaylistRepository()
                val result = repository.refreshPlaylist(defaultPlaylist.copy(id = playlistId))
                if (result.isFailure) {
                    // Handle error - maybe create demo channels
                    createDemoChannels(playlistId)
                }
            }

            // Set first playlist as default if none is set
            val defaultPlaylist = playlistDao.getDefaultPlaylist()
            if (defaultPlaylist != null) {
                (App.instance).preferences.defaultPlaylistId = defaultPlaylist.id
            }
        } catch (e: Exception) {
            // Log error but don't crash
            e.printStackTrace()
        }
    }

    private suspend fun createDemoChannels(playlistId: Long) {
        try {
            val database = (App.instance).database
            val channelDao = database.channelDao()

            val demoChannels = listOf(
                Channel.create("CCTV-1 综合", "http://cctvalih5c.vh.myqcloud.com/live/cctv1/index.m3u8", playlistId),
                Channel.create("CCTV-2 财经", "http://cctvalih5c.vh.myqcloud.com/live/cctv2/index.m3u8", playlistId),
                Channel.create("CCTV-3 综艺", "http://cctvalih5c.vh.myqcloud.com/live/cctv3/index.m3u8", playlistId),
                Channel.create("CCTV-4 中文国际", "http://cctvalih5c.vh.myqcloud.com/live/cctv4/index.m3u8", playlistId),
                Channel.create("CCTV-5 体育", "http://cctvalih5c.vh.myqcloud.com/live/cctv5/index.m3u8", playlistId),
                Channel.create("CCTV-6 军事", "http://cctvalih5c.vh.myqcloud.com/live/cctv6/index.m3u8", playlistId),
                Channel.create("CCTV-7 农业农村", "http://cctvalih5c.vh.myqcloud.com/live/cctv7/index.m3u8", playlistId),
                Channel.create("CCTV-8 电视剧", "http://cctvalih5c.vh.myqcloud.com/live/cctv8/index.m3u8", playlistId)
            )

            channelDao.insertChannels(demoChannels)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun formatDuration(durationMs: Long): String {
        val hours = durationMs / (1000 * 60 * 60)
        val minutes = (durationMs % (1000 * 60 * 60)) / (1000 * 60)
        val seconds = (durationMs % (1000 * 60)) / 1000

        return when {
            hours > 0 -> "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
            minutes > 0 -> "${minutes}:${seconds.toString().padStart(2, '0')}"
            else -> "0:${seconds.toString().padStart(2, '0')}"
        }
    }

    fun getNetworkType(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        return when {
            networkInfo == null || !networkInfo.isConnected -> "No Connection"
            networkInfo.type == android.net.ConnectivityManager.TYPE_WIFI -> "WiFi"
            networkInfo.type == android.net.ConnectivityManager.TYPE_MOBILE -> "Mobile"
            else -> "Unknown"
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        return networkInfo != null && networkInfo.isConnected
    }

    fun shouldBufferAutomatically(networkType: String): Boolean {
        return when (networkType.lowercase()) {
            "mobile" -> true // Auto-buffer for mobile connections
            else -> false
        }
    }
}