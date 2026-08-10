package com.sk.gallery.cloud

import com.sk.gallery.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSyncTest {

    @Test
    fun testAppDataFolderConstantIsCorrect() {
        assertEquals("appDataFolder", DriveVaultManager.APPDATA_FOLDER_ID)
        assertEquals("hierarchy_index.json", DriveVaultManager.MANIFEST_FILE_NAME)
        assertEquals(2 * 1024 * 1024, DriveVaultManager.CHUNK_SIZE_BYTES)
    }

    @Test
    fun testObfuscatedFilenameGeneration() {
        val originalPath = "Pictures/Travel/Goa/IMG_2026.jpg"
        val hashId = FileUtils.hashStringSha256(originalPath)
        val obfuscatedName = "$hashId.bin"

        assertEquals(64 + 4, obfuscatedName.length)
        assertEquals(true, obfuscatedName.endsWith(".bin"))
        assertEquals(false, obfuscatedName.contains("IMG_2026"))
        assertEquals(false, obfuscatedName.contains("Pictures"))
    }
}
