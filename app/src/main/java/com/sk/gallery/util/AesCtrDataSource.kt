package com.sk.gallery.util

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.io.File
import java.io.RandomAccessFile

class AesCtrDataSource(
    private val upstream: DataSource,
    private val secretKeyRaw: ByteArray,
    private val file: File
) : DataSource {
    private var cipher: Cipher? = null
    private var iv: ByteArray? = null
    private val V2_HEADER_SIZE = 20L // 4 magic + 16 iv

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val raf = RandomAccessFile(file, "r")
        val magic = ByteArray(4)
        raf.readFully(magic)
        iv = ByteArray(16)
        raf.readFully(iv)
        raf.close()

        val offsetSpec = dataSpec.buildUpon()
            .setPosition(dataSpec.position + V2_HEADER_SIZE)
            .build()

        val remaining = upstream.open(offsetSpec)

        cipher = Cipher.getInstance("AES/CTR/NoPadding")
        
        // In AES/CTR, we can seek by updating the IV
        val blockOffset = dataSpec.position / 16
        val newIv = iv!!.clone()
        
        // Add blockOffset to IV (big-endian)
        var carry = blockOffset
        for (i in 15 downTo 0) {
            val sum = (newIv[i].toInt() and 0xFF) + carry
            newIv[i] = sum.toByte()
            carry = sum ushr 8
            if (carry == 0L) break
        }

        cipher!!.init(Cipher.DECRYPT_MODE, SecretKeySpec(secretKeyRaw, "AES"), IvParameterSpec(newIv))
        
        val skipInBlock = (dataSpec.position % 16).toInt()
        if (skipInBlock > 0) {
            val dummy = ByteArray(skipInBlock)
            cipher!!.update(dummy)
        }

        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead == androidx.media3.common.C.RESULT_END_OF_INPUT) {
            return androidx.media3.common.C.RESULT_END_OF_INPUT
        }
        val decrypted = cipher!!.update(buffer, offset, bytesRead)
        if (decrypted != null) {
            System.arraycopy(decrypted, 0, buffer, offset, decrypted.size)
            return decrypted.size
        }
        return 0 // This shouldn't happen with NoPadding
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }
}
