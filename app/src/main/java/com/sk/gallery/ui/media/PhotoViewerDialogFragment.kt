package com.sk.gallery.ui.media

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.sk.gallery.R
import com.sk.gallery.databinding.DialogPhotoViewerBinding
import com.sk.gallery.model.FileEntry
import java.io.File

class PhotoViewerDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_FILE_NAME = "arg_file_name"
        private const val ARG_RELATIVE_PATH = "arg_relative_path"

        fun newInstance(entry: FileEntry): PhotoViewerDialogFragment {
            val fragment = PhotoViewerDialogFragment()
            val args = Bundle().apply {
                putString(ARG_FILE_NAME, entry.fileName)
                putString(ARG_RELATIVE_PATH, entry.relativePath)
            }
            fragment.arguments = args
            return fragment
        }
    }

    private var _binding: DialogPhotoViewerBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPhotoViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }

        val fileName = arguments?.getString(ARG_FILE_NAME) ?: "Photo"
        val relativePath = arguments?.getString(ARG_RELATIVE_PATH) ?: ""

        binding.tvPhotoTitle.text = fileName
        binding.tvPhotoSubtitle.text = relativePath

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        if (relativePath.contains("app_PrivateVault")) {
            val mainFile = File(relativePath)
            val thumbFile = File(mainFile.parentFile, "${mainFile.name}.thumb")
            
            val fullModel = com.sk.gallery.data.crypto.EncryptedFile(mainFile)
            val thumbModel = if (thumbFile.exists()) com.sk.gallery.data.crypto.EncryptedFile(thumbFile) else fullModel
            
            Glide.with(this)
                .load(fullModel)
                .thumbnail(Glide.with(this).load(thumbModel))
                .into(binding.ivFullscreen)
            return
        }

        val externalStorageDir = Environment.getExternalStorageDirectory()
        val absoluteFile = File(externalStorageDir, relativePath)

        if (absoluteFile.exists()) {
            Glide.with(this)
                .load(absoluteFile)
                .into(binding.ivFullscreen)
        } else {
            Glide.with(this)
                .load(relativePath)
                .into(binding.ivFullscreen)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
