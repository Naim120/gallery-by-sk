package com.sk.gallery.data.crypto

import java.io.File

/**
 * A simple wrapper around a File to indicate to Glide that this file 
 * needs to be decrypted via CryptoManager before rendering.
 */
data class EncryptedFile(val file: File)
