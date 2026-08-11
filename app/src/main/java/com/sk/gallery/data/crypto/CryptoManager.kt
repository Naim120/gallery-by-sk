package com.sk.gallery.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object CryptoManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
    private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
    private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
    private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128
    private const val KEY_ALIAS = "PrivateVaultMasterKey"
    
    private val V2_MAGIC = byteArrayOf('S'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())
    private const val V2_TRANSFORMATION = "AES/CTR/NoPadding"

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    fun getV2SecretKeyRaw(): ByteArray {
        val prefs = com.sk.gallery.GalleryApplication.instance.getSharedPreferences("CryptoPrefs", android.content.Context.MODE_PRIVATE)
        val encryptedBase64 = prefs.getString("v2_dek", null)
        
        if (encryptedBase64 != null) {
            try {
                val encryptedBytes = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val iv = encryptedBytes.copyOfRange(0, 12)
                val ciphertext = encryptedBytes.copyOfRange(12, encryptedBytes.size)
                val spec = GCMParameterSpec(TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
                return cipher.doFinal(ciphertext)
            } catch (e: Exception) {}
        }
        
        val newKey = ByteArray(32)
        SecureRandom().nextBytes(newKey)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(newKey)
        
        val encryptedBytes = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, encryptedBytes, 0, iv.size)
        System.arraycopy(ciphertext, 0, encryptedBytes, iv.size, ciphertext.size)
        
        prefs.edit().putString("v2_dek", android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.DEFAULT)).apply()
        return newKey
    }

    private fun getSecretKey(): SecretKey {
        val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return existingKey?.secretKey ?: createSecretKey()
    }

    private fun createSecretKey(): SecretKey {
        return KeyGenerator.getInstance(ALGORITHM, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setBlockModes(BLOCK_MODE)
                .setEncryptionPaddings(PADDING)
                .setRandomizedEncryptionRequired(true)
                .build()
            )
        }.generateKey()
    }

    fun encryptFileLocal(inputFile: File, outputFile: File, onProgress: ((Int) -> Unit)? = null, cancelSignal: (() -> Boolean)? = null) {
        val rawKey = getV2SecretKeyRaw()
        val cipher = Cipher.getInstance(V2_TRANSFORMATION)
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        
        val fileLength = inputFile.length()
        var totalRead = 0L
        var lastPercent = -1
        
        FileInputStream(inputFile).use { fis ->
            FileOutputStream(outputFile).use { fos ->
                fos.write(V2_MAGIC)
                fos.write(iv)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelSignal?.invoke() == true) return
                    val output = cipher.update(buffer, 0, bytesRead)
                    if (output != null) fos.write(output)
                    totalRead += bytesRead
                    if (fileLength > 0 && onProgress != null) {
                        val currentPercent = (totalRead * 100 / fileLength).toInt()
                        if (currentPercent > lastPercent) {
                            lastPercent = currentPercent
                            onProgress(currentPercent)
                        }
                    }
                }
                val outputBytes = cipher.doFinal()
                if (outputBytes != null) fos.write(outputBytes)
            }
        }
    }

    fun decryptFileLocal(inputFile: File, outputFile: File, onProgress: ((Int) -> Unit)? = null, cancelSignal: (() -> Boolean)? = null) {
        val fileLength = inputFile.length()
        var totalRead = 0L
        var lastPercent = -1
        
        java.io.BufferedInputStream(FileInputStream(inputFile)).use { fis ->
            fis.mark(10)
            val magicBuf = ByteArray(4)
            fis.read(magicBuf)
            val isV2 = magicBuf.contentEquals(V2_MAGIC)
            
            val cipher: Cipher
            if (isV2) {
                val iv = ByteArray(16)
                fis.read(iv)
                totalRead = 20L
                cipher = Cipher.getInstance(V2_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(getV2SecretKeyRaw(), "AES"), javax.crypto.spec.IvParameterSpec(iv))
            } else {
                fis.reset()
                val iv = ByteArray(IV_LENGTH)
                fis.read(iv)
                totalRead = IV_LENGTH.toLong()
                cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(TAG_LENGTH, iv))
            }
            
            FileOutputStream(outputFile).use { fos ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelSignal?.invoke() == true) return
                    val output = cipher.update(buffer, 0, bytesRead)
                    if (output != null) fos.write(output)
                    totalRead += bytesRead
                    if (fileLength > 0 && onProgress != null) {
                        val currentPercent = (totalRead * 100 / fileLength).toInt()
                        if (currentPercent > lastPercent) {
                            lastPercent = currentPercent
                            onProgress(currentPercent)
                        }
                    }
                }
                val outputBytes = cipher.doFinal()
                if (outputBytes != null) fos.write(outputBytes)
            }
        }
    }

    fun getDecryptedStreamLocal(inputFile: File): InputStream {
        val fis = java.io.BufferedInputStream(FileInputStream(inputFile))
        fis.mark(10)
        val magicBuf = ByteArray(4)
        fis.read(magicBuf)
        val isV2 = magicBuf.contentEquals(V2_MAGIC)
        
        val cipher: Cipher
        if (isV2) {
            val iv = ByteArray(16)
            fis.read(iv)
            cipher = Cipher.getInstance(V2_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(getV2SecretKeyRaw(), "AES"), javax.crypto.spec.IvParameterSpec(iv))
        } else {
            fis.reset()
            val iv = ByteArray(IV_LENGTH)
            fis.read(iv)
            cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(TAG_LENGTH, iv))
        }
        
        return javax.crypto.CipherInputStream(fis, cipher)
    }

    
    // --- Cloud Sync Passphrase Cryptography (AES-256-GCM via PBKDF2) ---

    private const val CLOUD_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SALT_LENGTH = 16

    fun generatePassphrase(): String {
        val random = SecureRandom()
        val words = mutableListOf<String>()
        val dictionary = Bip39WordList.WORDS
        for (i in 0 until 12) {
            words.add(dictionary[random.nextInt(dictionary.size)])
        }
        return words.joinToString(" ")
    }

    private fun deriveKeyFromPassphrase(passphrase: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 10000, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun encryptForCloud(inputFile: File, outputFile: File, passphrase: String) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH)
        val iv = ByteArray(IV_LENGTH)
        random.nextBytes(salt)
        random.nextBytes(iv)
        
        val key = deriveKeyFromPassphrase(passphrase, salt)
        val cipher = Cipher.getInstance(CLOUD_TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        
        FileInputStream(inputFile).use { fis ->
            FileOutputStream(outputFile).use { fos ->
                fos.write(salt)
                fos.write(iv)
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    val output = cipher.update(buffer, 0, bytesRead)
                    if (output != null) {
                        fos.write(output)
                    }
                }
                val outputBytes = cipher.doFinal()
                if (outputBytes != null) {
                    fos.write(outputBytes)
                }
            }
        }
    }

    fun decryptFromCloud(inputFile: File, outputFile: File, passphrase: String): Boolean {
        return try {
            FileInputStream(inputFile).use { fis ->
                val salt = ByteArray(SALT_LENGTH)
                val iv = ByteArray(IV_LENGTH)
                fis.read(salt)
                fis.read(iv)
                
                val key = deriveKeyFromPassphrase(passphrase, salt)
                val cipher = Cipher.getInstance(CLOUD_TRANSFORMATION)
                val spec = GCMParameterSpec(TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, key, spec)
                
                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        val output = cipher.update(buffer, 0, bytesRead)
                        if (output != null) {
                            fos.write(output)
                        }
                    }
                    val outputBytes = cipher.doFinal()
                    if (outputBytes != null) {
                        fos.write(outputBytes)
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            if (outputFile.exists()) outputFile.delete()
            false
        }
    }
}
