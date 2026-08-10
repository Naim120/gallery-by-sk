package com.sk.gallery.data.sync

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import com.sk.gallery.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException

class GoogleDriveManager(private val context: Context, private val account: GoogleSignInAccount) {

    private val driveService: Drive

    init {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
        .setApplicationName("GalleryBySK")
        .build()
    }

    suspend fun listEncryptedFiles(parentId: String = "appDataFolder"): List<File> = withContext(Dispatchers.IO) {
        val files = mutableListOf<File>()
        try {
            var pageToken: String? = null
            do {
                val result: FileList = driveService.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("'$parentId' in parents and mimeType != 'application/vnd.google-apps.folder' and trashed = false")
                    .setFields("nextPageToken, files(id, name, modifiedTime, size)")
                    .setPageToken(pageToken)
                    .execute()
                
                files.addAll(result.files ?: emptyList())
                pageToken = result.nextPageToken
            } while (pageToken != null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
        files
    }

    suspend fun uploadFile(localFile: java.io.File, mimeType: String, fileName: String, parentId: String = "appDataFolder"): String? = withContext(Dispatchers.IO) {
        try {
            val fileMetadata = File().apply {
                name = fileName
                parents = listOf(parentId)
            }
            val mediaContent = com.google.api.client.http.FileContent(mimeType, localFile)
            
            val file = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()
            file.id
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            // If it's a quota exceeded exception, rethrow it so the worker can pause
            if (e is com.google.api.client.googleapis.json.GoogleJsonResponseException && 
                (e.statusCode == 403 || e.details?.errors?.any { it.reason?.contains("quotaExceeded", ignoreCase = true) == true } == true)) {
                throw e
            }
            null
        }
    }

    suspend fun downloadFile(fileId: String, destFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(destFile).use { fos ->
                driveService.files().get(fileId).executeMediaAndDownloadTo(fos)
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            if (destFile.exists()) destFile.delete()
            false
        }
    }
    
    suspend fun deleteFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            driveService.files().delete(fileId).execute()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
