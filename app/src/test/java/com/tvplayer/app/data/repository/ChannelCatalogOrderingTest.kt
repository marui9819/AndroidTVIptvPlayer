package com.tvplayer.app.data.repository

import com.tvplayer.app.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelCatalogOrderingTest {

    @Test
    fun categoryRowsUseTheOrderOfTheMatchingSourceGroup() {
        val globalOrder = listOf(
            merged("小木子先生", displayOrder = 5, source("一起看", order = 5, playlistOrder = 0)),
            merged(
                "纪晓岚",
                displayOrder = 20,
                source("一起看", order = 20, playlistOrder = 0),
                source("剧集", order = 4, playlistOrder = 1)
            ),
            merged("后续剧集", displayOrder = 21, source("剧集", order = 5, playlistOrder = 1))
        )

        assertEquals(
            listOf("纪晓岚", "后续剧集"),
            orderMergedChannelsForGroup(globalOrder, "剧集").map { it.name }
        )
    }

    private fun source(group: String, order: Int, playlistOrder: Int) = PlaybackSourceOption(
        playlistId = playlistOrder.toLong() + 1,
        playlistName = "playlist-$playlistOrder",
        channel = Channel(
            id = (playlistOrder * 100 + order + 1).toLong(),
            name = "test-$group-$order",
            url = "https://example.invalid/$group/$order",
            group = group,
            order = order,
            playlistId = playlistOrder.toLong() + 1
        ),
        responseTimeMs = 0,
        resolutionText = "",
        isAvailable = true,
        errorText = null,
        lastCheckedAt = 0,
        playlistOrder = playlistOrder
    )

    private fun merged(
        name: String,
        displayOrder: Int,
        vararg sources: PlaybackSourceOption
    ) = MergedChannel(
        key = name,
        name = name,
        groupName = sources.first().channel.group.orEmpty(),
        logo = null,
        displayOrder = displayOrder,
        isFavorite = false,
        isAvailable = true,
        sources = sources.toList(),
        preferredSourceIndex = 0,
        lastPlayed = 0,
        playCount = 0
    )
}
