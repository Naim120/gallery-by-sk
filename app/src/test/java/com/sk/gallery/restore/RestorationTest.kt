package com.sk.gallery.restore

import com.sk.gallery.model.FileEntry
import com.sk.gallery.model.FolderNode
import com.sk.gallery.model.HierarchyIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorationTest {

    @Test
    fun testReconciliationIdentifiesMissingGhostCards() {
        val cloudFile1 = FileEntry(
            hashId = "hash_goa_01",
            relativePath = "Pictures/Travel/Goa/IMG_01.jpg",
            fileName = "IMG_01.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 2048500,
            sha256Checksum = "checksum_goa_01",
            dateModified = 1700000000L,
            cloudFileId = "drive_id_goa_01"
        )
        val cloudFile2 = FileEntry(
            hashId = "hash_work_01",
            relativePath = "DCIM/Work/Doc_01.jpg",
            fileName = "Doc_01.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1048500,
            sha256Checksum = "checksum_work_01",
            dateModified = 1700000000L,
            cloudFileId = "drive_id_work_01"
        )

        val cloudManifest = HierarchyIndex(
            version = 1,
            deviceId = "old_phone_device",
            lastUpdatedTimestamp = 1700000000L,
            folderTree = listOf(
                FolderNode("Pictures/Travel/Goa/", "Goa", "Pictures/Travel/", 1),
                FolderNode("DCIM/Work/", "Work", "DCIM/", 1)
            ),
            entries = mapOf(
                cloudFile1.hashId to cloudFile1,
                cloudFile2.hashId to cloudFile2
            )
        )

        // Local device has cloudFile1, but missing cloudFile2
        val localManifest = HierarchyIndex(
            version = 1,
            deviceId = "new_phone_device",
            lastUpdatedTimestamp = 1700005000L,
            folderTree = listOf(
                FolderNode("Pictures/Travel/Goa/", "Goa", "Pictures/Travel/", 1)
            ),
            entries = mapOf(
                cloudFile1.hashId to cloudFile1
            )
        )

        val reconciled = ReconciliationEngine.reconcileIndices(localManifest, cloudManifest)

        assertNotNull(reconciled)
        assertEquals(2, reconciled.entries.size)
        assertEquals(2, reconciled.folderTree.size)

        // Check local present file
        val entry1 = reconciled.entries["hash_goa_01"]
        assertNotNull(entry1)
        assertFalse(entry1!!.isMissingLocally)

        // Check ghost missing file
        val entry2 = reconciled.entries["hash_work_01"]
        assertNotNull(entry2)
        assertTrue(entry2!!.isMissingLocally)
        assertEquals("drive_id_work_01", entry2.cloudFileId)
    }
}
