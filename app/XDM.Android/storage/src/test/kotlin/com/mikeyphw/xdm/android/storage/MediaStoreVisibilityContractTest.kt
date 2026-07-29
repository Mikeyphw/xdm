package com.mikeyphw.xdm.android.storage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreVisibilityContractTest {
    private val root = androidRoot()

    @Test
    fun mediaStorePromotionClearsPendingAndRefreshesModifiedDate() {
        val source = File(root, "storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt").readText()
        assertTrue(source.contains("put(MediaStore.MediaColumns.IS_PENDING, 1)"))
        assertTrue(source.contains("put(MediaStore.MediaColumns.IS_PENDING, 0)"))
        assertTrue(source.contains("put(MediaStore.MediaColumns.DATE_ADDED, createdAtSeconds)"))
        assertTrue(source.contains("put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)"))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "storage/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
