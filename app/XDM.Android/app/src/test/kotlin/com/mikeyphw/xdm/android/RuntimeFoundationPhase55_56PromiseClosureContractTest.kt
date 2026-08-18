package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFoundationPhase55_56PromiseClosureContractTest {
    private val root = androidRoot()
    private fun source(path: String) = File(root, path).readText()

    @Test
    fun aria2DiagnosticsAndSmokeCoverPromisedFailureAndLifecycleCases() {
        val models = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RuntimeModels.kt")
        val manager = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt")
        val rpc = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RpcClient.kt")
        listOf("MalformedResponse", "ConfigurationInvalid", "PortUnavailable", "BinaryLoadFailure", "Unauthorized").forEach {
            assertTrue("Missing aria2 failure category $it", models.contains(it))
        }
        listOf("rpc.addUri", "rpc.tellStatus", "rpc.pause", "rpc.unpause", "rpc.saveSession", "rpc.removeDownloadResult").forEach {
            assertTrue("Smoke test must exercise $it", manager.contains(it))
        }
        assertTrue(rpc.contains("Aria2RpcProtocolException"))
        assertTrue(manager.contains("secretGeneration"))
        assertTrue(manager.contains("startedAtEpochMs"))
        assertTrue(manager.contains("reconcilePersistedRuntime"))
        assertTrue(manager.contains("waitUntilRpcStops"))
        assertTrue(manager.contains("RecoveredOwnedDaemon"))
        assertTrue(models.contains("Aria2RuntimeLease"))
        assertTrue(models.contains("OrphanRecovery"))
    }

    @Test
    fun storageDoctorExercisesFilesystemAria2YtDlpAndFfmpeg() {
        val doctor = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/DirectStorageDoctor.kt")
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val termux = source("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt")
        val nativeProbe = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeStoragePathProbe.kt")
        val direct = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PersonalDirectStorage.kt")
        val settings = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt")
        listOf("mkdir", "create", "write+fsync", "rename", "read", "delete").forEach {
            assertTrue("Storage doctor missing $it", doctor.contains("\"$it\""))
        }
        assertTrue(viewModel.contains("selectedDestination = preferences.values.first().destinationUri"))
        assertTrue(viewModel.contains("nativeStoragePathProbe.run(directDestination)"))
        assertTrue(viewModel.contains("aria2ProcessManager.storageProbe"))
        assertTrue(viewModel.contains("runStoragePathProbe"))
        assertTrue(nativeProbe.contains("prepared.promote") || nativeProbe.contains(".promote()"))
        assertTrue(nativeProbe.contains("output.fd.sync()"))
        assertTrue(direct.contains("rawDirectory.isAbsolute"))
        assertTrue(direct.contains("directoryForDestination"))
        assertTrue(termux.contains("yt-dlp --version"))
        assertTrue(termux.contains("ffmpeg -version"))
        assertTrue(termux.contains("XDM_STORAGE_PROBE"))
        assertTrue(settings.contains("Storage doctor"))
    }

    @Test
    fun safStillCannotBeCoercedIntoFilePath() {
        val writer = source("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
        assertFalse(writer.contains("File(uri.path)"))
        assertTrue(writer.contains("ContentResolver"))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (
                File(cursor, "settings.gradle.kts").isFile &&
                File(cursor, "app/src/main").isDirectory
            ) {
                return cursor
            }
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
