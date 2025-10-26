package com.tvplayer.app.ui.player.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tvplayer.app.databinding.ItemChannelListBinding
import com.tvplayer.app.data.model.Channel

class ChannelListAdapter(
    private val onChannelSelected: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelListAdapter.ChannelViewHolder>() {

    private var channels: List<Channel> = emptyList()

    fun submitList(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding, onChannelSelected)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(channels[position])
    }

    override fun getItemCount(): Int = channels.size

    class ChannelViewHolder(
        private val binding: ItemChannelListBinding,
        private val onChannelSelected: (Channel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name
            binding.channelGroup.text = channel.group ?: ""
            binding.channelNumber.text = "${position + 1}"

            // Load channel logo
            channel.logo?.let { logoUrl ->
                Glide.with(binding.root.context)
                    .load(logoUrl)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(binding.channelLogo)
            } ?: run {
                binding.channelLogo.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            // Set favorite icon
            binding.favoriteIcon.setImageResource(
                if (channel.isFavorite) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )

            // Set availability indicator
            binding.availabilityIndicator.setImageResource(
                if (channel.isAvailable) android.R.drawable.presence_online
                else android.R.drawable.presence_busy
            )

            // Handle click
            binding.root.setOnClickListener {
                onChannelSelected(channel)
            }

            // Handle long click for favorites
            binding.root.setOnLongClickListener {
                // Toggle favorite (would be handled by ViewModel in real implementation)
                true
            }
        }
    }
    }

    fun getChannelAt(position: Int): Channel? {
        return channels.getOrNull(position)
    }

    fun updateChannelFavorite(channelId: Long, isFavorite: Boolean) {
        channels.find { it.id == channelId }?.let { channel ->
            val index = channels.indexOf(channel)
            if (index != -1) {
                channels = channels.toMutableList().apply {
                    set(index, channel.copy(isFavorite = isFavorite))
                }
                notifyItemChanged(index)
            }
        }
    }

    fun updateChannelAvailability(channelId: Long, isAvailable: Boolean, error: String? = null) {
        channels.find { it.id == channelId }?.let { channel ->
            val index = channels.indexOf(channel)
            if (index != -1) {
                channels = channels.toMutableList().apply {
                    set(index, channel.copy(isAvailable = isAvailable, loadError = error))
                }
                notifyItemChanged(index)
            }
        }
    }
}