package com.sk.gallery.ui.picker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sk.gallery.R
import com.sk.gallery.databinding.ItemPickerChipBinding

data class FilterChip(
    val id: String,
    val name: String,
    val relativePath: String? = null
)

class FilterChipAdapter(
    private var chips: List<FilterChip>,
    private var selectedId: String = "all",
    private val onChipSelected: (FilterChip) -> Unit
) : RecyclerView.Adapter<FilterChipAdapter.ChipViewHolder>() {

    fun setChips(newChips: List<FilterChip>, currentSelectedId: String = selectedId) {
        chips = newChips
        selectedId = currentSelectedId
        notifyDataSetChanged()
    }

    fun setSelected(id: String) {
        if (selectedId != id) {
            selectedId = id
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChipViewHolder {
        val binding = ItemPickerChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChipViewHolder, position: Int) {
        holder.bind(chips[position])
    }

    override fun getItemCount(): Int = chips.size

    inner class ChipViewHolder(private val binding: ItemPickerChipBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(chip: FilterChip) {
            val context = binding.root.context
            val isSelected = chip.id == selectedId

            binding.tvChipName.text = chip.name
            if (isSelected) {
                binding.tvChipName.setBackgroundResource(R.drawable.bg_picker_chip_active)
                binding.tvChipName.setTextColor(ContextCompat.getColor(context, R.color.bg_primary))
            } else {
                binding.tvChipName.setBackgroundResource(R.drawable.bg_picker_chip_inactive)
                binding.tvChipName.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }

            binding.root.setOnClickListener {
                if (selectedId != chip.id) {
                    selectedId = chip.id
                    notifyDataSetChanged()
                    onChipSelected(chip)
                }
            }
        }
    }
}
