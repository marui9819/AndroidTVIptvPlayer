package com.tvplayer.app.data.repository

import android.content.Context
import com.tvplayer.app.data.db.AppDatabase
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.data.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PlaybackSourceOption(
    val playlistId: Long,
    val playlistName: String,
    val channel: Channel,
    val responseTimeMs: Long,
    val resolutionText: String,
    val isAvailable: Boolean,
    val errorText: String?,
    val lastCheckedAt: Long,
    /** Position of this playlist in the user's active subscription order. */
    val playlistOrder: Int = Int.MAX_VALUE
)

data class MergedChannel(
    val key: String,
    val name: String,
    val groupName: String,
    val logo: String?,
    val displayOrder: Int,
    val isFavorite: Boolean,
    val isAvailable: Boolean,
    val sources: List<PlaybackSourceOption>,
    val preferredSourceIndex: Int,
    val lastPlayed: Long,
    val playCount: Int
) {
    val preferredSource: PlaybackSourceOption?
        get() = sources.getOrNull(preferredSourceIndex)
}

/**
 * Sort merged rows by the source order inside the requested category.
 * A logical channel may first occur in a different category in an earlier
 * playlist, so MergedChannel.displayOrder is not a valid category row order.
 */
internal fun orderMergedChannelsForGroup(
    channels: List<MergedChannel>,
    groupName: String
): List<MergedChannel> {
    data class OrderedRow(
        val channel: MergedChannel,
        val playlistOrder: Int,
        val channelOrder: Int
    )

    val fallbackGroupName = "分类1"
    return channels.mapNotNull { merged ->
        val source = merged.sources.asSequence()
            .filter { (it.channel.group?.takeIf(String::isNotBlank) ?: fallbackGroupName) == groupName }
            .minWithOrNull(compareBy<PlaybackSourceOption>({ it.playlistOrder }, { it.channel.order }, { it.channel.name }))
            ?: return@mapNotNull null
        OrderedRow(
            channel = merged,
            playlistOrder = source.playlistOrder,
            channelOrder = source.channel.order
        )
    }.sortedWith(
        compareBy<OrderedRow>({ it.playlistOrder }, { it.channelOrder }, { it.channel.displayOrder })
    ).map { it.channel }
}

class ChannelCatalogRepository(
    context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context)
) {
    suspend fun getMergedChannels(): List<MergedChannel> {
        val playlists = database.playlistDao().getActivePlaylistsList()
        if (playlists.isEmpty()) return emptyList()
        val channels = database.channelDao().getChannelsByPlaylistIds(playlists.map { it.id })
        return withContext(Dispatchers.Default) {
            buildMergedChannels(playlists, channels)
        }
    }

    suspend fun getMergedChannelByRawChannelId(channelId: Long): MergedChannel? {
        val target = database.channelDao().getChannelById(channelId) ?: return null
        val playlists = database.playlistDao().getActivePlaylistsList()
        val activePlaylistIds = playlists.map { it.id }.toSet()
        if (target.playlistId !in activePlaylistIds) return null

        val normalizedName = target.normalizedName.ifBlank { Channel.normalizeName(target.name) }
        val channels = database.channelDao().getChannelsByNormalizedName(normalizedName)
            .filter { it.playlistId in activePlaylistIds }
        return withContext(Dispatchers.Default) {
            buildMergedChannels(playlists, channels).firstOrNull { merged ->
                merged.sources.any { it.channel.id == channelId }
            }
        }
    }

    private fun buildMergedChannels(
        playlists: List<Playlist>,
        channels: List<Channel>
    ): List<MergedChannel> {
        val playlistIndex = playlists.mapIndexed { index, playlist -> playlist.id to index }.toMap()
        val playlistsById = playlists.associateBy { it.id }

        data class Builder(
            val key: String,
            var name: String,
            var groupName: String,
            var logo: String?,
            val displayOrder: Int,
            var isFavorite: Boolean,
            var lastPlayed: Long,
            var playCount: Int,
            val sources: MutableList<PlaybackSourceOption> = mutableListOf()
        )

        val builders = linkedMapOf<String, Builder>()
        channels.sortedWith(
            compareBy<Channel>(
                { playlistIndex[it.playlistId] ?: Int.MAX_VALUE },
                { it.order },
                { it.name }
            )
        ).forEachIndexed { index, channel ->
            val playlist = playlistsById[channel.playlistId] ?: return@forEachIndexed
            val key = channel.normalizedName.ifBlank { Channel.normalizeName(channel.name) }
            val builder = builders.getOrPut(key) {
                Builder(
                    key = key,
                    name = channel.name,
                    groupName = channel.group?.takeIf(String::isNotBlank) ?: "分类1",
                    logo = channel.logo,
                    displayOrder = index,
                    isFavorite = channel.isFavorite,
                    lastPlayed = channel.lastPlayed,
                    playCount = channel.playCount
                )
            }
            if (builder.logo.isNullOrBlank() && !channel.logo.isNullOrBlank()) {
                builder.logo = channel.logo
            }
            builder.isFavorite = builder.isFavorite || channel.isFavorite
            builder.lastPlayed = maxOf(builder.lastPlayed, channel.lastPlayed)
            builder.playCount += channel.playCount
            builder.sources += PlaybackSourceOption(
                playlistId = playlist.id,
                playlistName = playlist.name,
                channel = channel,
                responseTimeMs = channel.responseTimeMs,
                resolutionText = buildResolutionText(channel),
                isAvailable = channel.isAvailable,
                errorText = channel.availabilityError,
                lastCheckedAt = channel.lastCheckedAt,
                playlistOrder = playlistIndex[channel.playlistId] ?: Int.MAX_VALUE
            )
        }

        return builders.values.map { builder ->
            val orderedSources = builder.sources.sortedWith(
                compareBy<PlaybackSourceOption>(
                    { !it.isAvailable },
                    { if (it.responseTimeMs > 0) it.responseTimeMs else Long.MAX_VALUE },
                    { playlistIndex[it.playlistId] ?: Int.MAX_VALUE },
                    { it.channel.order }
                )
            )
            val preferredIndex = orderedSources.indexOfFirst { it.isAvailable }.takeIf { it >= 0 } ?: 0
            MergedChannel(
                key = builder.key,
                name = builder.name,
                groupName = builder.groupName,
                logo = builder.logo,
                displayOrder = builder.displayOrder,
                isFavorite = builder.isFavorite,
                isAvailable = orderedSources.any { it.isAvailable },
                sources = orderedSources,
                preferredSourceIndex = preferredIndex,
                lastPlayed = builder.lastPlayed,
                playCount = builder.playCount
            )
        }.sortedBy { it.displayOrder }
    }

    suspend fun resolveStartupSource(
        lastChannelId: Long,
        lastChannelUrl: String?,
        defaultPlaylistId: Long?
    ): Channel? {
        return resolveStartupSourceFromCatalog(
            mergedChannels = getMergedChannels(),
            lastChannelId = lastChannelId,
            lastChannelUrl = lastChannelUrl,
            defaultPlaylistId = defaultPlaylistId
        )
    }

    /**
     * Resolves the startup channel from a catalog that the caller has already
     * built.  PlayerActivity starts on a separate process on old TVs; doing a
     * second full playlist query and merge here competes with the first codec
     * open and can leave the screen in BUFFERING for a long time.
     */
    fun resolveStartupSourceFromCatalog(
        mergedChannels: List<MergedChannel>,
        lastChannelId: Long,
        lastChannelUrl: String?,
        defaultPlaylistId: Long?
    ): Channel? {
        if (mergedChannels.isEmpty()) return null

        if (lastChannelId > 0L) {
            mergedChannels.firstOrNull { merged ->
                merged.sources.any { it.channel.id == lastChannelId && it.channel.isAvailable }
            }?.preferredSource?.channel?.let { return it }
        }

        if (!lastChannelUrl.isNullOrBlank()) {
            mergedChannels.firstOrNull { merged ->
                merged.sources.any { it.channel.url == lastChannelUrl && it.channel.isAvailable }
            }?.preferredSource?.channel?.let { return it }
        }

        defaultPlaylistId?.let { playlistId ->
            mergedChannels.firstOrNull { merged ->
                merged.sources.any { it.playlistId == playlistId && it.isAvailable }
            }?.preferredSource?.channel?.let { return it }
        }

        return mergedChannels.firstOrNull { it.isAvailable }?.preferredSource?.channel
    }

    /**
     * Small startup resolver for the home process.  MainActivity only needs a
     * playable row to hand to PlayerActivity; it does not need to build the
     * complete merged catalogue a second time before playback starts.
     */
    suspend fun resolveStartupSourceLightweight(
        lastChannelId: Long,
        lastChannelUrl: String?,
        defaultPlaylistId: Long?
    ): Channel? {
        val activePlaylists = database.playlistDao().getActivePlaylistsList()
        if (activePlaylists.isEmpty()) return null
        val activeIds = activePlaylists.map { it.id }.toSet()

        if (lastChannelId > 0L) {
            database.channelDao().getChannelById(lastChannelId)?.let { channel ->
                if (channel.playlistId in activeIds && channel.isAvailable) return channel
            }
        }

        if (!lastChannelUrl.isNullOrBlank()) {
            database.channelDao().getAvailableChannelByUrl(lastChannelUrl)?.let { channel ->
                if (channel.playlistId in activeIds) return channel
            }
        }

        defaultPlaylistId?.takeIf { it in activeIds }?.let { playlistId ->
            database.channelDao().getAvailableChannelsByPlaylist(playlistId)
                .firstOrNull()?.let { return it }
        }

        for (playlist in activePlaylists) {
            database.channelDao().getAvailableChannelsByPlaylist(playlist.id)
                .firstOrNull()?.let { return it }
        }
        return null
    }

    private fun buildResolutionText(channel: Channel): String {
        return if (channel.videoWidth > 0 && channel.videoHeight > 0) {
            "${channel.videoWidth}x${channel.videoHeight}"
        } else {
            "分辨率未知"
        }
    }
}
