package com.tvplayer.app.ui.main

import com.tvplayer.app.data.model.Playlist

/**
 * Click listener for playlist items in the UI
 */
interface PlaylistClickListener {
    /**
     * Called when a playlist item is clicked
     * @param playlist The playlist that was clicked
     */
    fun onPlaylistClick(playlist: Playlist)
}