package com.sk.gallery.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoManager {
    private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
    private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
    private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
    private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"
    private const val KEY_ALIAS = "gallery_by_sk_cloud_key"

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun getSecretKey(): SecretKey {
        val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return existingKey?.secretKey ?: createSecretKey()
    }

    private fun createSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM, "AndroidKeyStore")
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(BLOCK_MODE)
            .setEncryptionPaddings(PADDING)
            .setKeySize(256)
            .build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts the [inputStream] data and writes the IV followed by the ciphertext to [outputStream].
     * Uses a chunked reading approach to prevent OutOfMemoryError on large files.
     */
    fun encryptStream(inputStream: InputStream, outputStream: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

        val iv = cipher.iv
        // Prepend IV length and IV bytes to output
        outputStream.write(iv.size)
        outputStream.write(iv)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val output = cipher.update(buffer, 0, bytesRead)
            if (output != null) {
                outputStream.write(output)
            }
        }
        val outputBytes = cipher.doFinal()
        if (outputBytes != null) {
            outputStream.write(outputBytes)
        }
    }

    /**
     * Reads the IV from [inputStream] then decrypts the remainder of the stream into [outputStream].
     */
    fun decryptStream(inputStream: InputStream, outputStream: OutputStream) {
        val ivSize = inputStream.read()
        if (ivSize == -1) return // empty stream
        
        val iv = ByteArray(ivSize)
        val readIvBytes = inputStream.read(iv)
        if (readIvBytes != ivSize) throw IllegalStateException("Invalid IV length in encrypted stream")

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            val output = cipher.update(buffer, 0, bytesRead)
            if (output != null) {
                outputStream.write(output)
            }
        }
        val outputBytes = cipher.doFinal()
        if (outputBytes != null) {
            outputStream.write(outputBytes)
        }
    }
}
