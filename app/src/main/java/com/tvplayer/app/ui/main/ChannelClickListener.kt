package com.tvplayer.app.ui.main

import com.tvplayer.app.data.model.Channel

interface ChannelClickListener {
    fun onChannelClick(channel: Channel)
}
