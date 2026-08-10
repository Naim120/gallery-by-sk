package com.sk.gallery.ui.viewer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.sk.gallery.R

class ColorAdapter(
    initialColors: List<Int>,
    private val onColorSelected: (Int) -> Unit
) : RecyclerView.Adapter<ColorAdapter.ColorViewHolder>() {

    private val colors = initialColors.toMutableList()
    private var selectedPosition = 0

    inner class ColorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorCard: CardView = view.findViewById(R.id.color_card)

        init {
            view.setOnClickListener {
                val oldPosition = selectedPosition
                selectedPosition = bindingAdapterPosition
                notifyItemChanged(oldPosition)
                notifyItemChanged(selectedPosition)
                onColorSelected(colors[selectedPosition])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ColorViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_swatch, parent, false)
        return ColorViewHolder(view)
    }

    override fun onBindViewHolder(holder: ColorViewHolder, position: Int) {
        val color = colors[position]
        holder.colorCard.setCardBackgroundColor(color)
        
        if (position == selectedPosition) {
            holder.colorCard.animate().scaleX(1.3f).scaleY(1.3f).setDuration(150).start()
        } else {
            holder.colorCard.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
        }
    }

    override fun getItemCount(): Int = colors.size
    
    fun getSelectedColor(): Int = colors[selectedPosition]

    fun addAndSelectColor(color: Int, fireCallback: Boolean = true) {
        val index = colors.indexOf(color)
        val oldPosition = selectedPosition
        if (index != -1) {
            selectedPosition = index
        } else {
            colors.add(color)
            selectedPosition = colors.size - 1
            notifyItemInserted(selectedPosition)
        }
        notifyItemChanged(oldPosition)
        notifyItemChanged(selectedPosition)
        if (fireCallback) {
            onColorSelected(colors[selectedPosition])
        }
    }
}
