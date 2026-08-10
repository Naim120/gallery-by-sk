package com.sk.gallery.ui.adapter

import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.sk.gallery.R
import com.sk.gallery.databinding.ItemAlbumCardBinding
import com.sk.gallery.model.AlbumModel
import java.io.File

class AlbumAdapter(
    private var albums: List<AlbumModel>,
    private val onAlbumClick: (AlbumModel) -> Unit,
    private val onAlbumLongClick: ((AlbumModel) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_ALBUM = 0
        private const val TYPE_CREATE_NEW = 1
    }

    fun updateAlbums(newAlbums: List<AlbumModel>) {
        albums = newAlbums
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (albums[position].isCreateNew) TYPE_CREATE_NEW else TYPE_ALBUM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CREATE_NEW) {
            val view = inflater.inflate(R.layout.item_move_create_new, parent, false)
            CreateNewViewHolder(view)
        } else {
            val binding = ItemAlbumCardBinding.inflate(inflater, parent, false)
            AlbumViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val album = albums[position]
        if (holder is AlbumViewHolder) {
            holder.bind(album)
        } else if (holder is CreateNewViewHolder) {
            holder.bind(album)
        }
    }

    override fun getItemCount(): Int = albums.size

    inner class AlbumViewHolder(private val binding: ItemAlbumCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(album: AlbumModel) {
            val context = binding.root.context
            val displayMetrics = context.resources.displayMetrics
            val cardWidth = displayMetrics.widthPixels / 3 - 32
            binding.root.layoutParams = ViewGroup.LayoutParams(cardWidth, (cardWidth * 1.25).toInt())

            binding.tvAlbumTitle.text = album.title

            val coverEntry = album.coverEntry
            if (coverEntry != null) {
                val model = com.sk.gallery.util.MediaLoaderHelper.getGlideModel(coverEntry)

                Glide.with(context)
                    .load(model)
                    .centerCrop()
                    .placeholder(R.color.surface_card)
                    .error(R.color.surface_card)
                    .into(binding.ivAlbumCover)
            } else {
                binding.ivAlbumCover.setImageResource(R.color.surface_card)
            }

            binding.root.setOnClickListener {
                onAlbumClick(album)
            }

            binding.root.setOnLongClickListener {
                if (!album.isCreateNew && !album.isSystemAlbum) {
                    onAlbumLongClick?.invoke(album)
                    true
                } else {
                    false
                }
            }
        }
    }

    inner class CreateNewViewHolder(private val view: View) :
        RecyclerView.ViewHolder(view) {

        fun bind(album: AlbumModel) {
            val context = view.context
            val displayMetrics = context.resources.displayMetrics
            val cardWidth = displayMetrics.widthPixels / 3 - 32
            view.layoutParams = ViewGroup.LayoutParams(cardWidth, (cardWidth * 1.25).toInt())

            val tvTitle = view.findViewById<TextView>(R.id.tv_album_title)
            tvTitle?.text = album.title

            view.setOnClickListener {
                onAlbumClick(album)
            }
        }
    }
}
