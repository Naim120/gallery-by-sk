package com.sk.gallery.ui.adapter

import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.sk.gallery.R
import com.sk.gallery.databinding.ItemGhostCardBinding
import com.sk.gallery.databinding.ItemMediaBinding
import com.sk.gallery.model.FileEntry
import java.io.File

class MediaAdapter(
    private var entries: List<FileEntry>,
    private var spanCount: Int = 4,
    private val onItemClick: ((FileEntry) -> Unit)? = null,
    private val onItemLongClick: ((FileEntry) -> Unit)? = null,
    private val onSelectionChanged: ((Set<FileEntry>) -> Unit)? = null,
    private val onFetchCloudClick: (FileEntry) -> Unit,
    private val onMarkMissingClick: (FileEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_MEDIA = 0
        private const val TYPE_GHOST = 1
    }

    var isSelectionMode = false
        private set

    val selectedEntries = mutableSetOf<FileEntry>()

    fun startSelectionMode(entry: FileEntry) {
        isSelectionMode = true
        selectedEntries.clear()
        selectedEntries.add(entry)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedEntries)
    }

    fun clearSelectionMode() {
        isSelectionMode = false
        selectedEntries.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedEntries)
    }

    fun selectAll() {
        isSelectionMode = true
        selectedEntries.clear()
        selectedEntries.addAll(entries.filter { !it.isMissingLocally })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedEntries)
    }

    fun deselectAll() {
        selectedEntries.clear()
        isSelectionMode = false
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedEntries)
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
        onSelectionChanged?.invoke(selectedEntries)
    }

    fun updateEntries(newEntries: List<FileEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (entries[position].isMissingLocally) TYPE_GHOST else TYPE_MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GHOST) {
            val binding = ItemGhostCardBinding.inflate(inflater, parent, false)
            GhostCardViewHolder(binding)
        } else {
            val binding = ItemMediaBinding.inflate(inflater, parent, false)
            MediaViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = entries[position]
        if (holder is GhostCardViewHolder) {
            holder.bind(entry)
        } else if (holder is MediaViewHolder) {
            holder.bind(entry)
        }
    }

    override fun getItemCount(): Int = entries.size

    private var itemSize: Int = 0

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val context = recyclerView.context
        val displayMetrics = context.resources.displayMetrics
        val horizontalPaddingPx = (32 * displayMetrics.density).toInt()
        val availableWidth = displayMetrics.widthPixels - horizontalPaddingPx
        itemSize = availableWidth / spanCount
    }

    inner class MediaViewHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // Apply layout params once at creation instead of during every bind
            if (itemSize > 0) {
                val params = binding.ivThumbnail.layoutParams
                params.width = itemSize
                params.height = itemSize
                binding.ivThumbnail.layoutParams = params
            }
        }

        fun bind(entry: FileEntry) {
            val context = binding.root.context
            
            binding.tvFileName.text = entry.fileName

            val model = com.sk.gallery.util.MediaLoaderHelper.getGlideModel(entry)

            Glide.with(context)
                .load(model) // Let Glide handle failure internally; no sync I/O for File.exists()
                .centerCrop()
                .override(itemSize, itemSize) // Optimize Glide decoding size
                .placeholder(R.color.surface_card)
                .error(
                    Glide.with(context).load(model).centerCrop().override(itemSize, itemSize)
                )
                .into(binding.ivThumbnail)

            val isSelected = selectedEntries.contains(entry)
            binding.vOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.ivCloudBadge.visibility = if (entry.cloudFileId != null) View.VISIBLE else View.GONE

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(entry)
                } else {
                    onItemClick?.invoke(entry)
                }
            }

            binding.root.setOnLongClickListener {
                if (!isSelectionMode) {
                    startSelectionMode(entry)
                    onItemLongClick?.invoke(entry)
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

    inner class GhostCardViewHolder(private val binding: ItemGhostCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: FileEntry) {
            binding.tvGhostFilename.text = entry.fileName
            val sizeMb = "%.2f MB".format(entry.sizeBytes / (1024.0 * 1024.0))
            binding.tvGhostSize.text = "Size: $sizeMb"

            binding.btnFetchCloud.setOnClickListener {
                binding.btnFetchCloud.isEnabled = false
                binding.btnFetchCloud.text = "Downloading..."
                onFetchCloudClick(entry)
            }

            binding.btnMarkMissing.setOnClickListener {
                onMarkMissingClick(entry)
            }
        }
    }
}
