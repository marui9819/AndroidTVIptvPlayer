package com.tvplayer.app.util

import org.junit.Assert.assertTrue
import org.junit.Test

class NoBundledStreamSourcesTest {

    @Test
    fun freshInstallHasNoDefaultLivePlaylistUrl() {
        assertTrue(Constants.DefaultPlaylists.DEFAULT_URL.isBlank())
    }
}
