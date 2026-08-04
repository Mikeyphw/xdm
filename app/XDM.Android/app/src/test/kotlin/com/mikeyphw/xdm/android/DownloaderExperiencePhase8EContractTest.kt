package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderExperiencePhase8EContractTest {
    private val root = androidRoot()

    @Test
    fun activityWorkspaceOwnsTimelineAttentionAndDecisions() {
        val shell = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt").readText()
        val panels = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/ActivityPanel.kt").readText()
        val screens = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityScreens.kt").readText()
        val expectedPanels = mapOf(
            "Overview" to "Needs attention",
            "Timeline" to "Recent",
            "Attention" to "Needs attention",
            "Decisions" to "Queue decisions",
            "Queues" to "Queues",
            "Schedule" to "Schedules",
            "Recovery" to "Recovery",
            "Diagnostics" to "Developer tools",
        )
        expectedPanels.forEach { (panel, label) ->
            assertTrue(panels.contains("$panel(\"$label\")"))
        }
        val activitySources = shell + "\n" + screens
        assertTrue(activitySources.contains("ActivityTimelineScreen"))
        assertTrue(activitySources.contains("ActivityAttentionScreen"))
        assertTrue(activitySources.contains("ActivityDecisionsScreen"))
        assertTrue(screens.contains("Privacy-safe operational export"))
    }

    @Test
    fun operationalLedgerIsBoundedAndDoesNotOwnDownloads() {
        val store = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityStore.kt").readText()
        assertTrue(store.contains("MAX_EVENTS = 300"))
        assertTrue(store.contains("RETENTION_MS = 30L"))
        assertTrue(store.contains("Clearing this ledger never removes a transfer"))
        assertFalse(store.contains("DownloadRepository"))
        assertFalse(store.contains("RoomDatabase"))
        assertFalse(store.contains("sourceUrl"))
    }

    @Test
    fun diagnosticsExportIsRedactedAndDownloaderOnly() {
        val planner = root.resolve("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/OperationalActivity.kt").readText()
        assertTrue(planner.contains("cookies, authorization values, tokens, signatures"))
        assertTrue(planner.contains("PrivacyDiagnosticsRedactor.redactUrl"))
        assertTrue(planner.contains("built-in browser absent"))
        for (forbidden in listOf("android.webkit", "WebView(", "WebViewClient", "WebChromeClient")) {
            assertFalse(planner.contains(forbidden))
        }
    }

    @Test
    fun enginesSchemaRoutesAndMediaResolverRemainStable() {
        val routes = root.resolve("app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt").readText()
        val database = root.resolve("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt").readText()
        for (route in listOf("Downloads", "Add", "Media", "Library", "Activity", "Settings")) {
            assertTrue(routes.contains("$route(\"$route\""))
        }
        assertTrue(database.contains("version = 17"))
        assertTrue(root.resolve("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt").isFile)
        assertTrue(root.resolve("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt").isFile)
        assertTrue(root.resolve("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspace.kt").isFile)
        assertTrue(root.resolve("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaTermuxRuntimeAdapter.kt").isFile)
    }

    private fun androidRoot(): File {
        var current = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (current.resolve("settings.gradle.kts").isFile && current.resolve("app").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
