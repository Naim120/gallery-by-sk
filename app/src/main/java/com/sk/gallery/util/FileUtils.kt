package com.sk.gallery.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object FileUtils {

    fun calculateSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHexString()
    }

    fun calculateSha256(file: File): String {
        return file.inputStream().use { calculateSha256(it) }
    }

    fun hashStringSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.toHexString()
    }
    
    private fun ByteArray.toHexString(): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(this.size * 2)
        for (b in this) {
            val i = b.toInt()
            result.append(hexChars[i shr 4 and 0x0f])
            result.append(hexChars[i and 0x0f])
        }
        return result.toString()
    }

    fun normalizeRelativePath(path: String?): String {
        if (path.isNullOrEmpty()) return "Pictures/"
        var clean = path.replace("\\", "/")
        if (!clean.endsWith("/")) {
            clean += "/"
        }
        return clean
    }

    fun extractFolderName(relativePath: String): String {
        val trimmed = relativePath.trimEnd('/')
        val lastSlash = trimmed.lastIndexOf('/')
        return if (lastSlash >= 0) {
            trimmed.substring(lastSlash + 1)
        } else {
            trimmed
        }
    }

    fun extractParentPath(relativePath: String): String? {
        val trimmed = relativePath.trimEnd('/')
        val lastSlash = trimmed.lastIndexOf('/')
        return if (lastSlash > 0) {
            trimmed.substring(0, lastSlash + 1)
        } else null
    }

    fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun getAlbumRelativePath(relativePath: String): String {
        val path = relativePath.replace("\\", "/")
        val lastSlash = path.lastIndexOf('/')
        val folderPath = if (lastSlash >= 0) path.substring(0, lastSlash) else ""

        return when {
            folderPath.equals("Pictures", ignoreCase = true) -> "Pictures"
            folderPath.startsWith("Pictures/", ignoreCase = true) -> {
                val sub = folderPath.substringAfter("Pictures/").substringBefore("/")
                if (sub.isNotBlank()) "Pictures/$sub" else "Pictures"
            }
            folderPath.equals("DCIM", ignoreCase = true) -> "DCIM"
            folderPath.startsWith("DCIM/", ignoreCase = true) -> {
                val sub = folderPath.substringAfter("DCIM/").substringBefore("/")
                if (sub.isNotBlank()) "DCIM/$sub" else "DCIM"
            }
            else -> folderPath
        }
    }
}
