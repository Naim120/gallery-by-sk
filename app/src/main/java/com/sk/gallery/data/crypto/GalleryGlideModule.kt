package com.sk.gallery.data.crypto

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import java.io.InputStream

@GlideModule
class GalleryGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.prepend(
            EncryptedFile::class.java,
            InputStream::class.java,
            EncryptedFileModelLoader.Factory()
        )
    }
}
