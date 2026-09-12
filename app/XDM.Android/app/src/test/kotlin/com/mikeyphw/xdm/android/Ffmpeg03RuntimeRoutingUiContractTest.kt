package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Ffmpeg03RuntimeRoutingUiContractTest {
    private val root = File(System.getProperty("user.dir")).let { cwd ->
        if (File(cwd, "app/src/main").isDirectory) cwd else File(cwd, "app/XDM.Android")
    }
    private fun text(path: String) = File(root, path).readText()

    @Test fun settingsExposeAutomaticEmbeddedAndTermuxPolicy() {
        val prefs = text("app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt")
        val settings = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt")
        assertTrue(prefs.contains("media_ffmpeg_runtime_preference"))
        assertTrue(settings.contains("Media runtime routing"))
        assertTrue(settings.contains("authenticated, signed, or private-network sessions never cross that boundary"))
    }

    @Test fun supportAndDeveloperDiagnosticsExposeRuntimeTruth() {
        val vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val dev = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt")
        assertTrue(vm.contains("Media runtime routing"))
        assertTrue(vm.contains("Termux FFmpeg fallback"))
        assertTrue(dev.contains("FFmpeg routing policy"))
        assertTrue(dev.contains("public header-free URLs"))
    }
}
