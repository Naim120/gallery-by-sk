package com.sk.gallery.ui.collage

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sk.gallery.R
import android.net.Uri

class CollageTemplateAdapter(
    private val templates: List<CollageTemplate>,
    private val imageUris: List<Uri>,
    private val onTemplateSelected: (CollageTemplate) -> Unit
) : RecyclerView.Adapter<CollageTemplateAdapter.ViewHolder>() {

    private var selectedIndex = 0

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val collagePreview: CollageView = view.findViewById(R.id.collage_preview)
        val selectionOverlay: View = view.findViewById(R.id.selection_overlay)

        init {
            view.setOnClickListener {
                val oldIndex = selectedIndex
                selectedIndex = adapterPosition
                notifyItemChanged(oldIndex)
                notifyItemChanged(selectedIndex)
                onTemplateSelected(templates[selectedIndex])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_collage_template, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val template = templates[position]
        holder.collagePreview.setTemplate(template)
        holder.collagePreview.setImages(imageUris)
        holder.collagePreview.isInteractive = false
        
        if (position == selectedIndex) {
            holder.selectionOverlay.visibility = View.VISIBLE
        } else {
            holder.selectionOverlay.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = templates.size
}
