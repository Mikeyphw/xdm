package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ux02NavigationVisualHierarchyContractTest {
    @Test
    fun compactShellOwnsTheRouteTitleAndOnlyDownloadsGetsTheGlobalAddAction() {
        val root = androidRoot()
        val shell = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt").readText()
        val media = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt").readText()
        val library = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt").readText()
        val activity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt").readText()
        val settings = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt").readText()

        assertTrue(shell.contains("if (route == AppRoute.Downloads)"))
        assertTrue(shell.contains("contentDescription = \"New download\""))
        assertTrue(media.contains("XdmPageIntro(intro)"))
        assertTrue(library.contains("XdmPageIntro(intro)"))
        assertFalse(activity.contains("XdmSectionHeader(\"Activity\")"))
        assertFalse(settings.contains("XdmSectionHeader(\"Settings\")"))
    }

    @Test
    fun secondarySettingsPagesUseAConventionalBackAffordanceAndDisabledRowsStayReadable() {
        val root = androidRoot()
        val settings = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt").readText()
        val primitives = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt").readText()

        assertTrue(settings.contains("Icons.AutoMirrored.Rounded.ArrowBack"))
        assertTrue(settings.contains("contentDescription = \"Back to Settings\""))
        assertFalse(settings.contains("TextButton(onClick = onBack) { Text(\"Back\") }"))
        assertTrue(primitives.contains(".alpha(if (enabled) 1f else 0.64f)"))
        assertTrue(primitives.contains("fun XdmPageIntro("))
    }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
