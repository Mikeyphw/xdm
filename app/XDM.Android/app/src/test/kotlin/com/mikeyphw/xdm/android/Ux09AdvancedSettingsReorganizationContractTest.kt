package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux09AdvancedSettingsReorganizationContractTest {
    private fun source(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val root = generateSequence(cwd) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
        return File(root, relative).readText()
    }

    @Test
    fun advancedCapabilitiesHaveSeparateHomesAndDestinationRulesAreHumanReadable() {
        val advanced = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt")
        assertTrue(advanced.contains("fun StorageDestinationsSettingsScreen"))
        assertTrue(advanced.contains("fun NetworkSettingsScreen"))
        assertTrue(advanced.contains("fun MediaCaptureSettingsScreen"))
        assertTrue(advanced.contains("fun ExternalToolsSettingsScreen"))
        assertTrue(advanced.contains("fun PostProcessingSettingsScreen"))
        assertTrue(advanced.contains("fun BackupRestoreSettingsScreen"))
        assertTrue(advanced.contains("Save matching downloads to"))
        assertTrue(advanced.contains("destinationUiLabel(destinationRuleDestination)"))
        assertTrue(advanced.contains("DestinationRulePickerDialog("))
        assertTrue(advanced.contains("Technical URI: \$destinationRuleDestination"))
        assertFalse(advanced.contains("label = { Text(\"Rule destination URI\") }"))
    }

    @Test
    fun postProcessingIsPresentedAsAnOrderedSafePipeline() {
        val advanced = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/AdvancedDownloadSettingsScreen.kt")
        assertTrue(advanced.contains("After-download pipeline"))
        assertTrue(advanced.contains("1. Finalize and publish file"))
        assertTrue(advanced.contains("2. Durable automation"))
        assertTrue(advanced.contains("3. Transformation"))
    }
}
