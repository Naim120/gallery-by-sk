package com.sk.gallery.ui.adapter

import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.sk.gallery.R
import com.sk.gallery.databinding.ItemDateHeaderBinding
import com.sk.gallery.databinding.ItemMediaBinding
import com.sk.gallery.model.FileEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class TimelineItem {
    data class Header(val dateTitle: String) : TimelineItem()
    data class Media(val entry: FileEntry) : TimelineItem()
}

class TimelineAdapter(
    var items: List<TimelineItem>,
    private var spanCount: Int = 3,
    private val onItemClick: (FileEntry, Int) -> Unit,
    private val onItemLongClick: (FileEntry) -> Unit,
    private val onSelectionChanged: (Set<FileEntry>) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MEDIA = 1
    }

    var isSelectionMode = false
        private set

    val selectedEntries = mutableSetOf<FileEntry>()
    private var allMediaEntries = listOf<FileEntry>()

    fun updateSpanCount(newSpanCount: Int) {
        spanCount = newSpanCount
        notifyDataSetChanged()
    }

    fun updateEntries(entries: List<FileEntry>) {
        allMediaEntries = entries
        val grouped = mutableListOf<TimelineItem>()
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        val todayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val todayStr = todayFormat.format(Date())

        val entriesByDate = entries.groupBy { entry ->
            if (entry.dateModified > 0) {
                val date = Date(entry.dateModified * 1000L)
                val dateStr = todayFormat.format(date)
                if (dateStr == todayStr) "Today" else dateFormat.format(date)
            } else {
                "Older Media"
            }
        }

        for ((dateHeader, dateEntries) in entriesByDate) {
            grouped.add(TimelineItem.Header(dateHeader))
            for (entry in dateEntries) {
                grouped.add(TimelineItem.Media(entry))
            }
        }

        items = grouped
        notifyDataSetChanged()
    }

    fun startSelectionMode(initialEntry: FileEntry) {
        isSelectionMode = true
        selectedEntries.clear()
        selectedEntries.add(initialEntry)
        notifyDataSetChanged()
        onSelectionChanged(selectedEntries)
    }

    fun clearSelectionMode() {
        isSelectionMode = false
        selectedEntries.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedEntries)
    }

    fun selectAll() {
        isSelectionMode = true
        selectedEntries.clear()
        selectedEntries.addAll(allMediaEntries)
        notifyDataSetChanged()
        onSelectionChanged(selectedEntries)
    }

    fun toggleSelection(entry: FileEntry) {
        if (selectedEntries.contains(entry)) {
            selectedEntries.remove(entry)
        } else {
            selectedEntries.add(entry)
        }
        if (selectedEntries.isEmpty()) {
            isSelectionMode = false
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedEntries)
    }

    fun getSpanSizeLookup(spanCount: Int): GridLayoutManager.SpanSizeLookup {
        return object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (getItemViewType(position) == TYPE_HEADER) spanCount else 1
            }
        }
    }

    fun getDateAtPosition(position: Int): Long? {
        if (position < 0 || position >= items.size) return null
        
        // Check current
        val currentItem = items[position]
        if (currentItem is TimelineItem.Media) return currentItem.entry.dateModified * 1000L
        
        // Scan forward
        var next = position + 1
        while (next < items.size) {
            val item = items[next]
            if (item is TimelineItem.Media) return item.entry.dateModified * 1000L
            next++
        }
        
        // Scan backward
        var prev = position - 1
        while (prev >= 0) {
            val item = items[prev]
            if (item is TimelineItem.Media) return item.entry.dateModified * 1000L
            prev--
        }
        
        return null
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is TimelineItem.Header -> TYPE_HEADER
            is TimelineItem.Media -> TYPE_MEDIA
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val binding = ItemDateHeaderBinding.inflate(inflater, parent, false)
            HeaderViewHolder(binding)
        } else {
            val binding = ItemMediaBinding.inflate(inflater, parent, false)
            MediaViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TimelineItem.Header -> (holder as HeaderViewHolder).bind(item)
            is TimelineItem.Media -> (holder as MediaViewHolder).bind(item.entry)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderViewHolder(private val binding: ItemDateHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(header: TimelineItem.Header) {
            binding.tvDateTitle.text = header.dateTitle
            binding.tvItemCount.visibility = View.GONE
        }
    }

    inner class MediaViewHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FileEntry) {
            val context = binding.root.context
            val displayMetrics = context.resources.displayMetrics
            val horizontalPaddingPx = (32 * displayMetrics.density).toInt()
            val availableWidth = displayMetrics.widthPixels - horizontalPaddingPx
            val itemSize = availableWidth / spanCount

            val params = binding.ivThumbnail.layoutParams
            params.width = itemSize
            params.height = itemSize
            binding.ivThumbnail.layoutParams = params

            val model = com.sk.gallery.util.MediaLoaderHelper.getGlideModel(entry)

            Glide.with(context)
                .load(model)
                .signature(com.bumptech.glide.signature.ObjectKey(entry.dateModified))
                .centerCrop()
                .placeholder(R.color.surface_card)
                .error(R.color.surface_card)
                .into(binding.ivThumbnail)

            val isSelected = selectedEntries.contains(entry)
            binding.vOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivCloudBadge.visibility = if (entry.cloudFileId != null) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(entry)
                } else {
                    val mediaIndex = allMediaEntries.indexOf(entry)
                    onItemClick(entry, if (mediaIndex >= 0) mediaIndex else 0)
                }
            }

            binding.root.setOnLongClickListener {
                if (!isSelectionMode) {
                    startSelectionMode(entry)
                    onItemLongClick(entry)
                } else {
                    toggleSelection(entry)
                }
                true
            }

            if (entry.mimeType.startsWith("video", ignoreCase = true)) {
                binding.llVideoInfo.visibility = android.view.View.VISIBLE
                binding.tvVideoDuration.text = com.sk.gallery.util.FileUtils.formatDuration(entry.duration ?: 0L)
            } else {
                binding.llVideoInfo.visibility = android.view.View.GONE
            }
        }
    }
}
