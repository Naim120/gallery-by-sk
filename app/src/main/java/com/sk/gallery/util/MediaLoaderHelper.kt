package com.sk.gallery.util

import android.os.Environment
import com.sk.gallery.model.FileEntry
import com.sk.gallery.data.crypto.EncryptedFile
import java.io.File

object MediaLoaderHelper {
    fun getGlideModel(entry: FileEntry, isThumbnail: Boolean = true): Any {
        if (entry.relativePath.contains("app_PrivateVault")) {
            val mainFile = File(entry.relativePath)
            if (isThumbnail) {
                val thumbFile = File(mainFile.parentFile, "${mainFile.name}.thumb")
                if (thumbFile.exists()) {
                    return EncryptedFile(thumbFile)
                }
            }
            return EncryptedFile(mainFile)
        }
        val absoluteFile = File(Environment.getExternalStorageDirectory(), entry.relativePath)
        return if (absoluteFile.exists()) absoluteFile else entry.relativePath
    }
}
