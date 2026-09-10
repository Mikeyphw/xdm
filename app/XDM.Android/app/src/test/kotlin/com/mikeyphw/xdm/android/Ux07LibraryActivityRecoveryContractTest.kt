package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Test
import org.junit.Assert.assertTrue

class Ux07LibraryActivityRecoveryContractTest {
    private val root = androidRoot()
    private val library = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt").readText()
    private val activity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt").readText()
    private val planner = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityWorkspace.kt").readText()
    private val app = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
    private val operational = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/OperationalActivity.kt").readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (true) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app").isDirectory) return cursor
            cursor = cursor.parentFile ?: error("Could not locate XDM.Android root from ${System.getProperty("user.dir")}")
        }
    }

    @Test
    fun libraryAddsUsefulEmptyStateSortingStorageAndFileActions() {
        assertTrue(library.contains("No media yet"))
        assertTrue(library.contains("Find media"))
        assertTrue(library.contains("LibrarySort"))
        assertTrue(library.contains("Recent(\"Recent\")"))
        assertTrue(library.contains("Title(\"Title\")"))
        assertTrue(library.contains("Size(\"Size\")"))
        assertTrue(library.contains("XdmMetric(\"Stored\""))
        assertTrue(library.contains("Text(\"Share\")"))
        assertTrue(library.contains("Delete saved file"))
        assertTrue(library.contains("Text(\"Open\")"))
    }

    @Test
    fun activityGroupsRepeatedIncidentsAndUsesRecoveryFirstActions() {
        assertTrue(planner.contains("ActivityEventGroup"))
        assertTrue(planner.contains("downloads affected"))
        assertTrue(activity.contains("unresolved"))
        assertTrue(activity.contains("events today"))
        assertTrue(activity.contains("Queue & recovery"))
        assertTrue(app.contains("title = \"Queue & recovery\""))
        assertTrue(app.contains("\"Retry storage check\" -> viewModel.runQueueIntelligenceNow()"))
        assertTrue(operational.contains("QueueHoldReason.StoragePressure -> \"Retry storage check\""))
    }
}
