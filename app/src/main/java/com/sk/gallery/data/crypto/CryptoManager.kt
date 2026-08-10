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

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
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

    fun encryptFileLocal(inputFile: File, outputFile: File) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        
        FileInputStream(inputFile).use { fis ->
            FileOutputStream(outputFile).use { fos ->
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

    fun decryptFileLocal(inputFile: File, outputFile: File) {
        FileInputStream(inputFile).use { fis ->
            val iv = ByteArray(IV_LENGTH)
            fis.read(iv)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            
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
    }

    fun getDecryptedStreamLocal(inputFile: File): InputStream {
        val fis = FileInputStream(inputFile)
        val iv = ByteArray(IV_LENGTH)
        fis.read(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
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
