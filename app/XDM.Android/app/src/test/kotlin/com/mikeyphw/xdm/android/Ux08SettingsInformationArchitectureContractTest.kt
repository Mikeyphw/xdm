package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux08SettingsInformationArchitectureContractTest {
    private fun source(relative: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val root = generateSequence(cwd) { it.parentFile }.first { File(it, "app/src/main").isDirectory }
        return File(root, relative).readText()
    }

    @Test
    fun settingsOverviewUsesProductCategoriesInsteadOfOneAdvancedBucket() {
        val panel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/SettingsPanel.kt")
        val settings = source("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt")
        listOf(
            "StorageDestinations(\"Storage & destinations\")",
            "AdvancedDownloads(\"Download behavior\")",
            "Network(\"Network\")",
            "Media(\"Media & capture\")",
            "ExternalTools(\"External tools\")",
            "PostProcessing(\"Post-processing\")",
            "Appearance(\"Appearance\")",
            "BackupRestore(\"Backup & restore\")",
        ).forEach { assertTrue("Missing settings category $it", panel.contains(it)) }
        assertTrue(settings.contains("SettingsNavigationGroup("))
        assertTrue(settings.contains("Choose a category."))
        assertFalse(settings.contains("title = \"Advanced download rules\""))
        assertFalse(settings.contains("Destinations, duplicate handling, proxy, Termux, aria2, conversion, and settings backup."))
    }
}
