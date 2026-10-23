package com.tvplayer.app.ui.player.model

import com.tvplayer.app.data.repository.MergedChannel

data class ChannelGroupItem(
    val name: String,
    val channels: List<MergedChannel>,
    val availableCount: Int
)
