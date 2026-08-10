package com.sk.gallery.data.trash

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.sk.gallery.R
import java.io.File
import java.util.concurrent.TimeUnit

class TrashAdapter(
    private var entries: List<TrashEntry>,
    private val spanCount: Int,
    private val onItemClick: (TrashEntry, Int) -> Unit,
    private val onItemLongClick: (TrashEntry) -> Unit,
    private val onSelectionChanged: (Set<TrashEntry>) -> Unit
) : RecyclerView.Adapter<TrashAdapter.TrashViewHolder>() {

    val selectedEntries = mutableSetOf<TrashEntry>()
    var isSelectionMode = false
        private set

    fun updateEntries(newEntries: List<TrashEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    fun toggleSelection(entry: TrashEntry) {
        if (selectedEntries.contains(entry)) {
            selectedEntries.remove(entry)
            if (selectedEntries.isEmpty()) {
                isSelectionMode = false
            }
        } else {
            selectedEntries.add(entry)
            isSelectionMode = true
        }
        onSelectionChanged(selectedEntries)
        notifyItemChanged(entries.indexOf(entry))
    }

    fun selectAll() {
        if (selectedEntries.size == entries.size) {
            clearSelectionMode()
        } else {
            selectedEntries.addAll(entries)
            isSelectionMode = true
            onSelectionChanged(selectedEntries)
            notifyDataSetChanged()
        }
    }

    fun clearSelectionMode() {
        isSelectionMode = false
        selectedEntries.clear()
        onSelectionChanged(selectedEntries)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrashViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trash_card, parent, false)
        val layoutParams = view.layoutParams
        layoutParams.width = parent.width / spanCount
        view.layoutParams = layoutParams
        return TrashViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrashViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    inner class TrashViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val vSelectionOverlay: View = itemView.findViewById(R.id.vSelectionOverlay)
        private val ivCheck: ImageView = itemView.findViewById(R.id.ivCheck)
        private val ivVideoIcon: ImageView = itemView.findViewById(R.id.ivVideoIcon)
        private val tvDaysLeft: TextView = itemView.findViewById(R.id.tvDaysLeft)

        fun bind(entry: TrashEntry) {
            val file = File(itemView.context.filesDir, "Trash/${entry.trashFileName}")
            
            Glide.with(itemView.context)
                .load(file)
                .centerCrop()
                .placeholder(R.color.bg_dark)
                .into(ivThumbnail)

            ivVideoIcon.visibility = if (entry.mimeType.startsWith("video", ignoreCase = true)) View.VISIBLE else View.GONE

            val isSelected = selectedEntries.contains(entry)
            vSelectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
            ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            if (isSelected) {
                ivThumbnail.scaleX = 0.85f
                ivThumbnail.scaleY = 0.85f
            } else {
                ivThumbnail.scaleX = 1.0f
                ivThumbnail.scaleY = 1.0f
            }

            val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
            val expirationTime = entry.deletedAt + thirtyDaysMillis
            val remainingMillis = expirationTime - System.currentTimeMillis()
            val daysLeft = TimeUnit.MILLISECONDS.toDays(remainingMillis).toInt().coerceAtLeast(0)
            
            tvDaysLeft.text = "$daysLeft days"
            if (daysLeft <= 3) {
                tvDaysLeft.setTextColor(android.graphics.Color.parseColor("#FF5252")) // Red when almost expired
            } else {
                tvDaysLeft.setTextColor(android.graphics.Color.WHITE)
            }

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(entry)
                } else {
                    onItemClick(entry, adapterPosition)
                }
            }

            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    toggleSelection(entry)
                    onItemLongClick(entry)
                }
                true
            }
        }
    }
}
