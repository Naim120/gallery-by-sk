package com.sk.gallery.ui.picker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.sk.gallery.R
import com.sk.gallery.databinding.ItemMediaBinding
import com.sk.gallery.model.FileEntry
import com.sk.gallery.util.FileUtils
import com.sk.gallery.util.MediaLoaderHelper

class MediaPickerAdapter(
    private var entries: List<FileEntry>,
    val allowMultiple: Boolean,
    private val spanCount: Int = 3,
    private val onItemClick: (FileEntry) -> Unit,
    private val onSelectionChanged: (Set<FileEntry>) -> Unit
) : RecyclerView.Adapter<MediaPickerAdapter.MediaViewHolder>() {

    val selectedEntries = mutableSetOf<FileEntry>()
    private var itemSize: Int = 0

    fun updateEntries(newEntries: List<FileEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedEntries.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedEntries)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val context = recyclerView.context
        val displayMetrics = context.resources.displayMetrics
        val horizontalPaddingPx = (32 * displayMetrics.density).toInt()
        val availableWidth = displayMetrics.widthPixels - horizontalPaddingPx
        itemSize = availableWidth / spanCount
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class MediaViewHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            if (itemSize > 0) {
                val params = binding.ivThumbnail.layoutParams
                params.width = itemSize
                params.height = itemSize
                binding.ivThumbnail.layoutParams = params
            }
        }

        fun bind(entry: FileEntry) {
            val context = binding.root.context
            val isSelected = selectedEntries.contains(entry)

            val model = MediaLoaderHelper.getGlideModel(entry)

            Glide.with(context)
                .load(model)
                .centerCrop()
                .override(itemSize, itemSize)
                .placeholder(R.color.surface_card)
                .into(binding.ivThumbnail)

            if (entry.mimeType.startsWith("video", ignoreCase = true)) {
                binding.llVideoInfo.visibility = View.VISIBLE
                binding.tvVideoDuration.text = FileUtils.formatDuration(entry.duration ?: 0L)
            } else {
                binding.llVideoInfo.visibility = View.GONE
            }

            if (allowMultiple) {
                binding.vOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            } else {
                binding.vOverlay.visibility = View.GONE
                binding.ivCheck.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                if (allowMultiple) {
                    if (isSelected) {
                        selectedEntries.remove(entry)
                    } else {
                        selectedEntries.add(entry)
                    }
                    notifyItemChanged(bindingAdapterPosition)
                    onSelectionChanged(selectedEntries)
                } else {
                    onItemClick(entry)
                }
            }
        }
    }
}
