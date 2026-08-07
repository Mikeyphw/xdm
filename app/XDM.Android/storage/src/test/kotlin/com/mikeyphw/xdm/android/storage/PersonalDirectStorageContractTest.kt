package com.mikeyphw.xdm.android.storage

import com.mikeyphw.xdm.android.model.DestinationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDirectStorageContractTest {
    @Test
    fun directDownloadsIsExplicitPersonalFilesystemDestination() {
        val direct = DestinationCatalog.available(36).first { it.uri == DestinationUris.DIRECT_DOWNLOADS }
        assertEquals(DestinationType.FileSystem, direct.type)
        assertEquals(30, direct.minimumApi)
        assertTrue(direct.description.contains("all-files access"))
    }

    @Test
    fun safAndMediaStoreRemainAvailableAsCompatibilityFallbacks() {
        val available = DestinationCatalog.available(36).map { it.uri }.toSet()
        assertTrue(DestinationUris.PUBLIC_DOWNLOADS in available)
        assertTrue(DestinationUris.APP_PRIVATE_DOWNLOADS in available)
        assertTrue(DestinationUris.MEDIA_MOVIES in available)
    }

    @Test
    fun customDirectDirectoryContractIsPresent() {
        val source = java.io.File(System.getProperty("user.dir") ?: ".", "src/main/kotlin/com/mikeyphw/xdm/android/storage/PersonalDirectStorage.kt").readText()
        assertTrue(source.contains("customDirectoryUri"))
        assertTrue(source.contains("rawDirectory.isAbsolute"))
        assertTrue(source.contains("directoryForDestination"))
        assertTrue(source.contains("Android/data"))
        assertTrue(source.contains("Android/obb"))
    }
}
