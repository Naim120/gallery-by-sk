package com.sk.gallery.data.trash

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.sk.gallery.data.local.AppDatabase
import com.sk.gallery.databinding.ActivityTrashBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var adapter: TrashAdapter
    private var allTrashEntries: List<TrashEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupAdapter()
        observeTrash()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.btnEmptyTrash.setOnClickListener {
            if (allTrashEntries.isEmpty()) {
                Toast.makeText(this, "Trash is already empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            AlertDialog.Builder(this)
                .setTitle("Empty Trash")
                .setMessage("All items will be permanently deleted. This action cannot be undone.")
                .setPositiveButton("Empty") { _, _ ->
                    lifecycleScope.launch {
                        TrashManager.emptyTrash(this@TrashActivity) {
                            Toast.makeText(this@TrashActivity, "Trash Emptied", Toast.LENGTH_SHORT).show()
                            updateSelectionUI()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnCloseSelection.setOnClickListener {
            adapter.clearSelectionMode()
            updateSelectionUI()
        }

        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll()
            updateSelectionUI()
        }

        binding.btnActionRestore.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            if (selected.isEmpty()) return@setOnClickListener

            lifecycleScope.launch {
                TrashManager.restoreFromTrash(this@TrashActivity, selected) {
                    Toast.makeText(this@TrashActivity, "Restored ${selected.size} items", Toast.LENGTH_SHORT).show()
                    adapter.clearSelectionMode()
                    updateSelectionUI()
                }
            }
        }

        binding.btnActionDelete.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            if (selected.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("Permanently Delete")
                .setMessage("Are you sure you want to permanently delete ${selected.size} items?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        TrashManager.permanentlyDelete(this@TrashActivity, selected) {
                            Toast.makeText(this@TrashActivity, "Deleted permanently", Toast.LENGTH_SHORT).show()
                            adapter.clearSelectionMode()
                            updateSelectionUI()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupAdapter() {
        adapter = TrashAdapter(
            entries = emptyList(),
            spanCount = 4,
            onItemClick = { entry, index ->
                // TODO: Open viewer specifically for trash
                Toast.makeText(this, "Cannot view image in trash yet. Restore it first.", Toast.LENGTH_SHORT).show()
            },
            onItemLongClick = {
                updateSelectionUI()
            },
            onSelectionChanged = {
                updateSelectionUI()
            }
        )

        binding.rvTrashGrid.layoutManager = GridLayoutManager(this, 4)
        binding.rvTrashGrid.adapter = adapter
    }

    private fun observeTrash() {
        val dao = AppDatabase.getDatabase(this).trashDao()
        lifecycleScope.launch {
            dao.getAllTrashFlow().collectLatest { entries ->
                allTrashEntries = entries
                adapter.updateEntries(entries)
                binding.tvEmptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                
                if (adapter.isSelectionMode) {
                    val remainingSelected = adapter.selectedEntries.filter { it in entries }.toSet()
                    if (remainingSelected.isEmpty()) {
                        adapter.clearSelectionMode()
                    } else {
                        adapter.selectedEntries.clear()
                        adapter.selectedEntries.addAll(remainingSelected)
                    }
                    updateSelectionUI()
                }
            }
        }
    }

    private fun updateSelectionUI() {
        if (adapter.isSelectionMode) {
            binding.selectionTopBar.visibility = View.VISIBLE
            binding.selectionBottomBar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = "${adapter.selectedEntries.size} selected"
        } else {
            binding.selectionTopBar.visibility = View.GONE
            binding.selectionBottomBar.visibility = View.GONE
        }
    }

    override fun onBackPressed() {
        if (adapter.isSelectionMode) {
            adapter.clearSelectionMode()
            updateSelectionUI()
        } else {
            super.onBackPressed()
        }
    }
}
