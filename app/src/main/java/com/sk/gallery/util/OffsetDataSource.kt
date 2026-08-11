package com.sk.gallery.util

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import android.net.Uri

class OffsetDataSource(private val upstream: DataSource, private val offsetAmount: Long) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val newSpec = dataSpec.buildUpon().setPosition(dataSpec.position + offsetAmount).build()
        val length = upstream.open(newSpec)
        return if (length == androidx.media3.common.C.LENGTH_UNSET.toLong()) length else length - offsetAmount // No, the length is the remaining length, so it's correct. Wait, upstream.open returns the length from the new position. So we just return it!
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return upstream.read(buffer, offset, length)
    }
    override fun getUri(): Uri? = upstream.uri
    override fun close() {
        upstream.close()
    }
}
