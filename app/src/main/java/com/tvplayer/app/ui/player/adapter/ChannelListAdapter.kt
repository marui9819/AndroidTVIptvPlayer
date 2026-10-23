package com.tvplayer.app.ui.player.adapter

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.tvplayer.app.R
import com.tvplayer.app.data.repository.MergedChannel
import com.tvplayer.app.databinding.ItemChannelListBinding
import com.tvplayer.app.util.TvSelectionBackground

class ChannelListAdapter(
    private val onChannelSelected: (MergedChannel) -> Unit
) : RecyclerView.Adapter<ChannelListAdapter.ChannelViewHolder>() {

    private var channels: List<MergedChannel> = emptyList()
    private var selectedChannelKey: String = ""
    private var currentChannelKey: String = ""
    private var paneFocused: Boolean = true
    private var aliases: Map<String, String> = emptyMap()

    fun setAliases(value: Map<String, String>) {
        if (aliases == value) return
        aliases = value.toMap()
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    init {
        setHasStableIds(true)
    }

    fun submitList(newChannels: List<MergedChannel>) {
        val oldChannels = channels
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldChannels.size
            override fun getNewListSize(): Int = newChannels.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldChannels[oldItemPosition].key == newChannels[newItemPosition].key

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldChannels[oldItemPosition] == newChannels[newItemPosition]
        }, true)
        channels = newChannels.toList()
        diff.dispatchUpdatesTo(this)
    }

    fun keyAt(position: Int): String? = channels.getOrNull(position)?.key

    fun updateSelection(selectedKey: String, currentKey: String) {
        val oldSelectedKey = selectedChannelKey
        val oldCurrentKey = currentChannelKey
        selectedChannelKey = selectedKey
        currentChannelKey = currentKey
        notifyChangedForKey(oldSelectedKey)
        notifyChangedForKey(oldCurrentKey)
        notifyChangedForKey(selectedKey)
        notifyChangedForKey(currentKey)
    }

    fun updatePaneFocus(focused: Boolean) {
        if (paneFocused == focused) return
        paneFocused = focused
        notifyChangedForKey(selectedChannelKey)
    }

    private fun notifyChangedForKey(key: String) {
        if (key.isBlank()) return
        val index = channels.indexOfFirst { it.key == key }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun getItemId(position: Int): Long = channels[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding, onChannelSelected)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(
            channel = channels[position],
            position = position,
            isSelected = channels[position].key == selectedChannelKey,
            isCurrent = channels[position].key == currentChannelKey,
            paneFocused = paneFocused,
            displayName = channels[position].sources.asSequence()
                .mapNotNull { aliases[it.channel.url.trim()] }.firstOrNull() ?: channels[position].name
        )
    }

    override fun getItemCount(): Int = channels.size

    class ChannelViewHolder(
        private val binding: ItemChannelListBinding,
        private val onChannelSelected: (MergedChannel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            channel: MergedChannel,
            position: Int,
            isSelected: Boolean,
            isCurrent: Boolean,
            paneFocused: Boolean,
            displayName: String
        ) {
            binding.channelName.text = displayName
            binding.channelGroup.text = buildString {
                append(channel.groupName)
                append(" · ")
                append(channel.sources.size)
                append("源")
            }
            binding.channelNumber.text = "${position + 1}"
            binding.channelSource.text = channel.preferredSource?.playlistName ?: "未知来源"
            val preferredSource = channel.preferredSource
            val probeNotStarted = preferredSource?.lastCheckedAt == 0L
            val probeStale = preferredSource?.lastCheckedAt?.let { checkedAt ->
                checkedAt > 0L && System.currentTimeMillis() - checkedAt >= PROBE_STALE_AFTER_MS
            } == true
            binding.channelState.text = if (channel.isAvailable) {
                val timedOutSources = channel.sources.count {
                    !it.isAvailable && isTimeoutError(it.errorText)
                }
                preferredSource?.let { source ->
                    when {
                        timedOutSources > 0 && source.responseTimeMs > 0 -> "${source.responseTimeMs}ms · ${timedOutSources}源超时"
                        timedOutSources > 0 -> "${timedOutSources}源超时"
                        source.responseTimeMs > 0 -> "${source.responseTimeMs}ms"
                        probeNotStarted -> "待检测"
                        probeStale -> "检测结果过期"
                        else -> "可用"
                    }
                } ?: "可用"
            } else {
                when {
                    channel.sources.any { isTimeoutError(it.errorText) } -> "超时不可访问"
                    probeNotStarted -> "待检测"
                    else -> "暂不可访问"
                }
            }

            channel.logo?.let { logoUrl ->
                Glide.with(binding.root.context)
                    .load(logoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .dontAnimate()
                    .into(binding.channelLogo)
            } ?: binding.channelLogo.setImageResource(R.drawable.ic_channel_placeholder)

            binding.favoriteIcon.setImageResource(
                if (channel.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            binding.availabilityIndicator.setImageResource(
                if (channel.isAvailable) android.R.drawable.presence_online
                else android.R.drawable.presence_busy
            )
            binding.unavailableBadge.text = if (channel.isAvailable) "" else "不可用 · 右键重试"
            binding.unavailableBadge.visibility = if (channel.isAvailable) View.GONE else View.VISIBLE

            val background = when {
                // The D-pad cursor must be distinguishable from the playing
                // channel at TV viewing distance: cyan is focus, dark blue is
                // the channel currently on air.
                isSelected && paneFocused -> Color.parseColor("#FF62D9FF")
                isSelected -> Color.parseColor("#FF164768")
                isCurrent -> Color.parseColor("#FF164768")
                else -> Color.TRANSPARENT
            }
            val border = when {
                isSelected && paneFocused -> Color.WHITE
                isSelected || isCurrent -> Color.parseColor("#FF62D9FF")
                else -> Color.parseColor("#334F6170")
            }
            TvSelectionBackground.apply(
                binding.root,
                fillColor = background,
                strokeColor = border,
                strokeWidthDp = if (isSelected || isCurrent) 2 else 1
            )
            binding.root.alpha = if (channel.isAvailable) 1f else 0.45f
            val selectedText = if (isSelected && paneFocused) Color.parseColor("#FF071923") else Color.WHITE
            val secondaryColor = if (isSelected && paneFocused) {
                Color.parseColor("#FF17313F")
            } else if (isSelected || isCurrent) {
                Color.WHITE
            } else {
                Color.LTGRAY
            }
            binding.channelName.setTextColor(if (channel.isAvailable) selectedText else Color.parseColor("#AAAAAA"))
            binding.channelName.paintFlags = if (channel.isAvailable) {
                binding.channelName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            } else {
                binding.channelName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            binding.channelGroup.setTextColor(secondaryColor)
            binding.channelSource.setTextColor(secondaryColor)
            binding.channelState.setTextColor(
                when {
                    probeNotStarted || probeStale -> Color.parseColor("#FFC107")
                    channel.isAvailable -> Color.parseColor("#7CFC00")
                    else -> Color.parseColor("#FF6B6B")
                }
            )
            binding.channelNumber.setTextColor(
                if (isSelected && paneFocused) Color.parseColor("#FF071923") else Color.WHITE
            )

            binding.root.isEnabled = channel.isAvailable
            binding.root.setOnClickListener {
                if (channel.isAvailable) {
                    onChannelSelected(channel)
                }
            }
        }
    }

    private companion object {
        const val PROBE_STALE_AFTER_MS = 60 * 60 * 1000L

        fun isTimeoutError(error: String?): Boolean =
            error?.contains("timeout", ignoreCase = true) == true || error?.contains("超时") == true
    }
}
