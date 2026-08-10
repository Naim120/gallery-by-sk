package com.sk.gallery.data.crypto

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

class EncryptedFileModelLoader : ModelLoader<EncryptedFile, InputStream> {

    override fun buildLoadData(
        model: EncryptedFile,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> {
        return ModelLoader.LoadData(
            ObjectKey(model.file.absolutePath + model.file.lastModified()),
            EncryptedFileDataFetcher(model)
        )
    }

    override fun handles(model: EncryptedFile): Boolean {
        return true
    }

    class Factory : ModelLoaderFactory<EncryptedFile, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<EncryptedFile, InputStream> {
            return EncryptedFileModelLoader()
        }

        override fun teardown() {
            // No op
        }
    }
}
