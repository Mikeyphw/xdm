package com.mikeyphw.xdm.android.storage

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectStorageDoctorTest {
    @Test
    fun doctorExercisesCreateWriteSyncRenameReadAndDelete() {
        val directory = Files.createTempDirectory("xdm-storage-doctor").toFile()
        try {
            val report = DirectStorageDoctor.run(directory, permissionGranted = true)
            assertTrue(report.summary, report.passed)
            val names = report.steps.map { it.name }.toSet()
            assertTrue(names.containsAll(setOf("permission", "mkdir", "create", "write+fsync", "rename", "read", "delete")))
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".xdm-storage-doctor-") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingPermissionStopsBeforeFilesystemMutation() {
        val directory = Files.createTempDirectory("xdm-storage-doctor-denied").toFile()
        try {
            val report = DirectStorageDoctor.run(directory, permissionGranted = false)
            assertFalse(report.passed)
            assertTrue(report.steps.single().name == "permission")
        } finally {
            directory.deleteRecursively()
        }
    }
}
