package com.sk.gallery.ui.collage

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sk.gallery.R

data class AspectRatioItem(val label: String, val ratioString: String, val ratioValue: Float)

class RatioAdapter(
    private val ratios: List<AspectRatioItem>,
    private val onRatioSelected: (AspectRatioItem) -> Unit
) : RecyclerView.Adapter<RatioAdapter.RatioViewHolder>() {

    private var selectedIndex = 0

    inner class RatioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRatioLabel: TextView = view.findViewById(R.id.tv_ratio_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RatioViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ratio, parent, false)
        return RatioViewHolder(view)
    }

    override fun onBindViewHolder(holder: RatioViewHolder, position: Int) {
        val item = ratios[position]
        holder.tvRatioLabel.text = item.label
        
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = 16f
        
        if (position == selectedIndex) {
            bg.setColor(Color.parseColor("#0891B2"))
            holder.tvRatioLabel.setTextColor(Color.WHITE)
        } else {
            bg.setColor(Color.parseColor("#333333"))
            holder.tvRatioLabel.setTextColor(Color.parseColor("#CCCCCC"))
        }
        
        holder.tvRatioLabel.background = bg

        holder.itemView.setOnClickListener {
            val oldIndex = selectedIndex
            selectedIndex = holder.adapterPosition
            notifyItemChanged(oldIndex)
            notifyItemChanged(selectedIndex)
            onRatioSelected(item)
        }
    }

    override fun getItemCount() = ratios.size
}
