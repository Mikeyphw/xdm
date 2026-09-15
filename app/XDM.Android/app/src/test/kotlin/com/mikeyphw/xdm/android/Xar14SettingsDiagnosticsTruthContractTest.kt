package com.mikeyphw.xdm.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Xar14SettingsDiagnosticsTruthContractTest {
    private val root = androidRoot()

    private fun androidRoot(): File = generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app/src/main").isDirectory }

    @Test fun xar14ValidatorIsRegisteredInGradleAndFinalGate() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        val gate = File(root, "tools/run-final-release-gate.sh").readText()
        assertTrue(gradle.contains("verifyXar14SettingsDiagnosticsTruth"))
        assertTrue(gate.contains("tools/validate-xar14-settings-diagnostics-truth.py"))
    }

    @Test fun settingsImportsAreReportedAndNonDestructive() {
        val vm = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val settings = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt").readText()
        assertTrue(vm.contains("SettingsExchangeCodec.decodeResult(text)"))
        assertTrue(vm.contains("settingsImportResult.value = result"))
        assertTrue(settings.contains("state.settingsImportResult.summary"))
        assertTrue(settings.contains("if (importText.isNotBlank()) TextButton(onClick = { importText = \"\" })"))
    }

    @Test fun diagnosticsExportsAreFinalScannedBeforeShare() {
        val sharing = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/DebugCenterExportSharing.kt").readText()
        val store = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestStore.kt").readText()
        assertTrue(sharing.contains("DiagnosticExportIntegrity.scanZip(zip)"))
        assertTrue(sharing.contains("if (!scan.safe)"))
        assertTrue(store.contains("pruneOldExports"))
        assertTrue(store.contains("quarantineCorruptRun"))
    }
}
