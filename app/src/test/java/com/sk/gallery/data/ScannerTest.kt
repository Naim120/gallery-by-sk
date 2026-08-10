package com.sk.gallery.data

import com.google.gson.GsonBuilder
import com.sk.gallery.model.FileEntry
import com.sk.gallery.model.FolderNode
import com.sk.gallery.model.HierarchyIndex
import com.sk.gallery.util.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream

class ScannerTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun testSha256Calculation() {
        val testContent = "Hello Gallery by SK"
        val inputStream = ByteArrayInputStream(testContent.toByteArray(Charsets.UTF_8))
        val hash = FileUtils.calculateSha256(inputStream)
        
        val expectedHash = FileUtils.hashStringSha256(testContent)
        // Verify output length (64 hex characters for SHA-256)
        assertEquals(64, hash.length)
        assertEquals(64, expectedHash.length)
    }

    @Test
    fun testPathUtilities() {
        val relPath = "Pictures/Travel/Goa/"
        assertEquals("Pictures/Travel/Goa/", FileUtils.normalizeRelativePath("Pictures/Travel/Goa"))
        assertEquals("Goa", FileUtils.extractFolderName(relPath))
        assertEquals("Pictures/Travel/", FileUtils.extractParentPath(relPath))
    }

    @Test
    fun testHierarchyIndexJsonSerialization() {
        val folder = FolderNode(
            relativePath = "Pictures/Travel/Goa/",
            folderName = "Goa",
            parentPath = "Pictures/Travel/",
            fileCount = 2
        )
        val fileEntry = FileEntry(
            hashId = "a1b2c3d4e5f67890",
            relativePath = "Pictures/Travel/Goa/IMG_01.jpg",
            fileName = "IMG_01.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024567,
            sha256Checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            dateModified = 1700000000L
        )
        val originalIndex = HierarchyIndex(
            version = 1,
            deviceId = "test_device_123",
            lastUpdatedTimestamp = 1700000000L,
            folderTree = listOf(folder),
            entries = mapOf(fileEntry.hashId to fileEntry)
        )

        val json = gson.toJson(originalIndex)
        assertNotNull(json)

        val deserializedIndex = gson.fromJson(json, HierarchyIndex::class.java)
        assertEquals(originalIndex.version, deserializedIndex.version)
        assertEquals(originalIndex.deviceId, deserializedIndex.deviceId)
        assertEquals(1, deserializedIndex.folderTree.size)
        assertEquals("Goa", deserializedIndex.folderTree[0].folderName)
        assertEquals(1, deserializedIndex.entries.size)
        assertEquals("IMG_01.jpg", deserializedIndex.entries["a1b2c3d4e5f67890"]?.fileName)
    }
}
