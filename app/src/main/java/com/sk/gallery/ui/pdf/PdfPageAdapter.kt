package com.sk.gallery.ui.pdf

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.Rotate
import com.sk.gallery.R
import com.sk.gallery.databinding.ItemPdfPageBinding
import java.io.File
import java.util.Collections

class PdfPageAdapter(
    val pages: MutableList<PdfPageModel>,
    private val onPageClick: (position: Int) -> Unit,
    private val onPageCountChanged: (count: Int) -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.PdfPageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
        val binding = ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PdfPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
        holder.bind(pages[position], position)
    }

    override fun getItemCount(): Int = pages.size

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(pages, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(pages, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        // Refresh page number badges for affected range
        val start = minOf(fromPosition, toPosition)
        val count = Math.abs(fromPosition - toPosition) + 1
        notifyItemRangeChanged(start, count)
    }

    inner class PdfPageViewHolder(private val binding: ItemPdfPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(page: PdfPageModel, position: Int) {
            val context = binding.root.context
            binding.tvPageNumber.text = "${position + 1}"

            val file = File(page.filePath)
            var glideRequest = Glide.with(context)
                .load(file)
                .centerCrop()
                .placeholder(R.color.surface_card)
                .error(R.color.surface_card)

            if (page.rotationDegrees != 0) {
                glideRequest = glideRequest.transform(Rotate(page.rotationDegrees))
            }

            glideRequest.into(binding.ivPageThumb)

            val openPreview = {
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos < pages.size) {
                    onPageClick(currentPos)
                }
            }

            binding.root.setOnClickListener { openPreview() }
            binding.btnPreviewPage.setOnClickListener { openPreview() }

            binding.btnDeletePage.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos < pages.size) {
                    pages.removeAt(currentPos)
                    notifyItemRemoved(currentPos)
                    notifyItemRangeChanged(currentPos, pages.size - currentPos)
                    onPageCountChanged(pages.size)
                }
            }

            binding.btnRotatePage.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos < pages.size) {
                    page.rotationDegrees = (page.rotationDegrees + 90) % 360
                    notifyItemChanged(currentPos)
                }
            }
        }
    }
}
