package com.sk.gallery.ui.collage

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.sk.gallery.R

class ColorAdapter(
    private val colors: List<Int>,
    private val onColorSelected: (Int) -> Unit
) : RecyclerView.Adapter<ColorAdapter.ColorViewHolder>() {

    private var selectedIndex = 0

    inner class ColorViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorCircle: View = view.findViewById(R.id.color_circle)
        val icSelected: ImageView = view.findViewById(R.id.ic_selected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ColorViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_circle, parent, false)
        return ColorViewHolder(view)
    }

    override fun onBindViewHolder(holder: ColorViewHolder, position: Int) {
        val color = colors[position]
        
        // Dynamic color for the circle
        val bg = holder.colorCircle.background as? GradientDrawable ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(2, Color.parseColor("#E0E0E0"))
        }
        bg.setColor(color)
        holder.colorCircle.background = bg

        holder.icSelected.visibility = if (position == selectedIndex) View.VISIBLE else View.GONE
        
        // Adjust checkmark color based on background darkness
        val isDark = isColorDark(color)
        holder.icSelected.setColorFilter(if (isDark) Color.WHITE else Color.BLACK)

        holder.itemView.setOnClickListener {
            val oldIndex = selectedIndex
            selectedIndex = position
            notifyItemChanged(oldIndex)
            notifyItemChanged(selectedIndex)
            onColorSelected(color)
        }
    }

    override fun getItemCount() = colors.size
    
    private fun isColorDark(color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness >= 0.5
    }
}
