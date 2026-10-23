package com.tvplayer.app.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.tvplayer.app.databinding.ItemSearchResultBinding
import com.tvplayer.app.data.model.Channel
import com.tvplayer.app.R

class SearchAdapter(
    private val onChannelSelected: (Channel) -> Unit
) : RecyclerView.Adapter<SearchAdapter.SearchViewHolder>() {

    private var channels: List<Channel> = emptyList()

    fun submitList(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SearchViewHolder(binding, onChannelSelected)
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        holder.bind(channels[position])
    }

    override fun getItemCount(): Int = channels.size

    class SearchViewHolder(
        private val binding: ItemSearchResultBinding,
        private val onChannelSelected: (Channel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.channelName.text = com.tvplayer.app.util.ChannelUserState.get(binding.root.context).displayName(channel)
            binding.channelGroup.text = channel.group ?: ""
            binding.channelNumber.text = "${channel.order + 1}"

            // Load channel logo
            channel.logo?.let { logoUrl ->
                Glide.with(binding.root.context)
                    .load(logoUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .dontAnimate()
                    .into(binding.channelLogo)
            } ?: run {
                binding.channelLogo.setImageResource(R.drawable.ic_channel_placeholder)
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

        private fun highlightSearchMatch(text: String, query: String) {
            if (query.isEmpty() || text.length < 2) {
                binding.channelName.text = text
                return
            }

            val startIndex = text.lowercase().indexOf(query.lowercase())
            if (startIndex >= 0) {
                val endIndex = startIndex + query.length
                val spannable = android.text.SpannableStringBuilder(text)

                // Highlight the matched part
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    startIndex,
                    endIndex,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(
                        android.graphics.Color.parseColor("#FF6B6B")
                    ),
                    startIndex,
                    endIndex,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                binding.channelName.text = spannable
            } else {
                binding.channelName.text = text
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
                    set(index, channel.copy(isAvailable = isAvailable, availabilityError = error))
                }
                notifyItemChanged(index)
            }
        }
    }
}
