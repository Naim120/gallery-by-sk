package com.sk.gallery

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.databinding.ActivityMainBinding
import com.sk.gallery.ui.albums.AlbumsFragment
import com.sk.gallery.ui.photos.PhotosFragment
import com.sk.gallery.ui.vault.CloudVaultFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AppPreferences
    private var photosFragment: PhotosFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferences(this)

        setupViewPager()
        setupFloatingCapsuleTabs()
        setupTopMenu()
        setupBackPressedHandler()
    }

    private fun setupViewPager() {
        photosFragment = PhotosFragment()
        val fragments = listOf(
            photosFragment!!,
            AlbumsFragment(),
            CloudVaultFragment()
        )

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateTabSelection(position)
            }
        })
    }

    private fun setupFloatingCapsuleTabs() {
        binding.tabPhotos.setOnClickListener {
            binding.viewPager.currentItem = 0
        }

        binding.tabAlbums.setOnClickListener {
            binding.viewPager.currentItem = 1
        }

        binding.tabCloud.setOnClickListener {
            binding.viewPager.currentItem = 2
        }

        updateTabSelection(0)
    }

    fun setFloatingNavVisibility(visible: Boolean) {
        binding.floatingCapsuleNav.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateTabSelection(position: Int) {
        binding.tabPhotos.apply {
            setBackgroundResource(if (position == 0) R.drawable.bg_tab_active else android.R.color.transparent)
            setTextColor(if (position == 0) Color.BLACK else Color.parseColor("#A0A0A0"))
        }

        binding.tabAlbums.apply {
            setBackgroundResource(if (position == 1) R.drawable.bg_tab_active else android.R.color.transparent)
            setTextColor(if (position == 1) Color.BLACK else Color.parseColor("#A0A0A0"))
        }

        binding.tabCloud.apply {
            setBackgroundResource(if (position == 2) R.drawable.bg_tab_active else android.R.color.transparent)
            setTextColor(if (position == 2) Color.BLACK else Color.parseColor("#A0A0A0"))
        }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.viewPager.currentItem == 0 && photosFragment?.clearSelectionIfActive() == true) {
                    // Handled deselecting photos in PhotosFragment
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun setupTopMenu() {
        binding.btnMainMenu.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 1, 0, "Sort by")
            popup.menu.add(0, 2, 1, "Grid Columns")
            popup.menu.add(0, 3, 2, "Private Safe")
            popup.menu.add(0, 4, 3, "About")

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        showSortByDialog()
                        true
                    }
                    2 -> {
                        showGridColumnsDialog()
                        true
                    }
                    3 -> {
                        val vaultIntent = android.content.Intent(this, com.sk.gallery.ui.vault.VaultSecurityActivity::class.java)
                        vaultIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(vaultIntent)
                        true
                    }
                    4 -> {
                        startActivity(android.content.Intent(this, com.sk.gallery.ui.about.AboutActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun showSortByDialog() {
        val options = arrayOf("Date Modified (Newest first)", "Date Modified (Oldest first)", "Name")
        val currentSort = preferences.getSortBy()
        val checkedItem = when (currentSort) {
            "date_asc" -> 1
            "name_asc" -> 2
            else -> 0
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                when (which) {
                    0 -> preferences.setSortBy("date_desc")
                    1 -> preferences.setSortBy("date_asc")
                    2 -> preferences.setSortBy("name_asc")
                }
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun showGridColumnsDialog() {
        val options = arrayOf("2 Columns per row", "3 Columns per row (Default)", "4 Columns per row")
        val currentColumns = preferences.getGridColumns()
        val checkedItem = when (currentColumns) {
            2 -> 0
            4 -> 2
            else -> 1
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Grid Columns")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                when (which) {
                    0 -> preferences.setGridColumns(2)
                    1 -> preferences.setGridColumns(3)
                    2 -> preferences.setGridColumns(4)
                }
                dialog.dismiss()
                recreate()
            }
            .show()
    }
}
