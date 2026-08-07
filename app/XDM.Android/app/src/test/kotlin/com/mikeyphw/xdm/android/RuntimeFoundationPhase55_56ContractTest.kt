package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFoundationPhase55_56ContractTest {
    private val root = projectRoot()

    @Test
    fun personalBuildDeclaresAllFilesAccessAndKeepsSafFallback() {
        val manifest = source("app/src/main/AndroidManifest.xml")
        val intake = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
        val storage = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
        assertTrue(manifest.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertTrue(intake.contains("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION") || intake.contains("PersonalDirectStorage.permissionIntent"))
        assertTrue(intake.contains("OpenDocumentTree"))
        assertTrue(storage.contains("persistTreePermission"))
        assertFalse(storage.contains("File(uri.path)"))
    }

    @Test
    fun aria2RuntimeSeparatesAuthFailureAndOffersRepair() {
        val manager = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt")
        val ui = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
        assertTrue(manager.contains("Aria2StartupFailureKind.Unauthorized"))
        assertTrue(manager.contains("rejectsUnauthenticated"))
        assertTrue(manager.contains("cleanupTransientLaunchConfigurations"))
        assertTrue(manager.contains("rotatable.rotate()"))
        assertTrue(ui.contains("Repair aria2"))
    }

    private fun source(path: String): String = File(root, path).readText()

    private fun projectRoot(): File {
        var cursor = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM.Android project root")
    }
}
