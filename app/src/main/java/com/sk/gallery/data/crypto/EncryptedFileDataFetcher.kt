package com.sk.gallery.data.crypto

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import java.io.InputStream
import java.lang.Exception

class EncryptedFileDataFetcher(private val encryptedFile: EncryptedFile) : DataFetcher<InputStream> {

    private var stream: InputStream? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        try {
            stream = CryptoManager.getDecryptedStreamLocal(encryptedFile.file)
            callback.onDataReady(stream)
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        try {
            stream?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun cancel() {
        // No op
    }

    override fun getDataClass(): Class<InputStream> {
        return InputStream::class.java
    }

    override fun getDataSource(): DataSource {
        return DataSource.LOCAL
    }
}
