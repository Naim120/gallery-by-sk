package com.sk.gallery.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sk.gallery.databinding.ItemFolderBinding
import com.sk.gallery.model.FolderNode

class FolderAdapter(
    private var folders: List<FolderNode>,
    private val onFolderClick: (FolderNode) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    fun updateFolders(newFolders: List<FolderNode>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(folders[position])
    }

    override fun getItemCount(): Int = folders.size

    inner class FolderViewHolder(private val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: FolderNode) {
            binding.tvFolderName.text = folder.folderName
            binding.tvRelativePath.text = folder.relativePath
            binding.tvFileCount.text = "${folder.fileCount} items"

            binding.root.setOnClickListener {
                onFolderClick(folder)
            }
        }
    }
}
