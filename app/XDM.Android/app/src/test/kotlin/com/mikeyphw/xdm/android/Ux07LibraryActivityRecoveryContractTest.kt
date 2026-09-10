package com.mikeyphw.xdm.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class Ux07LibraryActivityRecoveryContractTest {
    private val library = File("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt").readText()
    private val activity = File("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt").readText()
    private val planner = File("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityWorkspace.kt").readText()
    private val app = File("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
    private val operational = File("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/OperationalActivity.kt").readText()

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
