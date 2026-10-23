package com.tvplayer.app.ui.player.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tvplayer.app.R
import com.tvplayer.app.ui.player.model.ChannelGroupItem
import com.tvplayer.app.util.TvSelectionBackground

class ChannelGroupAdapter(
    private val onGroupSelected: (Int) -> Unit
) : RecyclerView.Adapter<ChannelGroupAdapter.ChannelGroupViewHolder>() {

    private var groups: List<ChannelGroupItem> = emptyList()
    private var selectedIndex: Int = 0
    private var paneFocused: Boolean = false

    init {
        setHasStableIds(true)
    }

    fun submitList(newGroups: List<ChannelGroupItem>) {
        val oldGroups = groups
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldGroups.size
            override fun getNewListSize(): Int = newGroups.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldGroups[oldItemPosition].name == newGroups[newItemPosition].name

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldGroups[oldItemPosition] == newGroups[newItemPosition]
        }, true)
        groups = newGroups.toList()
        diff.dispatchUpdatesTo(this)
    }

    fun keyAt(position: Int): String? = groups.getOrNull(position)?.name

    fun updateSelection(index: Int) {
        val oldIndex = selectedIndex
        selectedIndex = index
        if (oldIndex in groups.indices) notifyItemChanged(oldIndex)
        if (index in groups.indices) notifyItemChanged(index)
    }

    fun updatePaneFocus(focused: Boolean) {
        if (paneFocused == focused) return
        paneFocused = focused
        if (selectedIndex in groups.indices) notifyItemChanged(selectedIndex)
    }

    override fun getItemId(position: Int): Long = groups[position].name.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelGroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_group, parent, false)
        return ChannelGroupViewHolder(view, onGroupSelected)
    }

    override fun onBindViewHolder(holder: ChannelGroupViewHolder, position: Int) {
        holder.bind(groups[position], position, position == selectedIndex, paneFocused)
    }

    override fun getItemCount(): Int = groups.size

    class ChannelGroupViewHolder(
        itemView: View,
        private val onGroupSelected: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.groupName)
        private val countView: TextView = itemView.findViewById(R.id.groupCount)

        fun bind(group: ChannelGroupItem, position: Int, isSelected: Boolean, paneFocused: Boolean) {
            nameView.text = group.name
            countView.text = if (group.availableCount == group.channels.size) {
                "${group.channels.size}"
            } else {
                "${group.availableCount}/${group.channels.size}"
            }
            val fill = when {
                isSelected && paneFocused -> Color.parseColor("#FF62D9FF")
                isSelected -> Color.parseColor("#FF164768")
                else -> Color.TRANSPARENT
            }
            val border = when {
                isSelected && paneFocused -> Color.WHITE
                isSelected -> Color.parseColor("#FF62D9FF")
                else -> Color.parseColor("#334F6170")
            }
            TvSelectionBackground.apply(
                itemView,
                fillColor = fill,
                strokeColor = border,
                strokeWidthDp = if (isSelected) 2 else 1
            )
            val textColor = if (isSelected && paneFocused) Color.parseColor("#FF071923") else if (isSelected) Color.WHITE else Color.LTGRAY
            nameView.setTextColor(textColor)
            countView.setTextColor(textColor)
            itemView.setOnClickListener { onGroupSelected(position) }
        }
    }
}
