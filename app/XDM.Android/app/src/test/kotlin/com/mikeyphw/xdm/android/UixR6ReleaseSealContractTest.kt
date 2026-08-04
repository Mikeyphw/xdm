package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UixR6ReleaseSealContractTest {
    @Test
    fun everyPrimaryWorkflowHasStableSemanticsTags() {
        val root = androidRoot()
        val accessibility = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAccessibility.kt")
        val sources = mapOf(
            "Downloads" to source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt"),
            "Download details" to source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadDetails.kt"),
            "Add" to source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt"),
            "Media" to source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt"),
            "Media capture" to source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt"),
            "Library" to source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt"),
            "Activity" to source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt"),
            "Settings" to source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt"),
            "Developer tools" to source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperSettingsScreen.kt"),
        )
        listOf(
            "ShellCompact", "ShellMedium", "ShellExpanded", "BottomNavigation", "NavigationSidebar",
            "Downloads", "DownloadsList", "DownloadsDetail", "AddDownload", "AddReview",
            "Media", "MediaCapture", "MediaTrackSheet", "Library", "LibraryList", "LibraryGrid",
            "Activity", "Settings", "DeveloperTools",
        ).forEach { tag -> assertTrue("Missing semantics tag $tag", accessibility.contains("const val $tag")) }

        assertTrue(sources.getValue("Downloads").contains("XdmScreenTags.Downloads"))
        assertTrue(sources.getValue("Download details").contains("XdmScreenTags.DownloadsDetail"))
        assertTrue(sources.getValue("Add").contains("XdmScreenTags.AddDownload"))
        assertTrue(sources.getValue("Add").contains("XdmScreenTags.AddReview"))
        assertTrue(sources.getValue("Media").contains("XdmScreenTags.Media"))
        assertTrue(sources.getValue("Media capture").contains("XdmScreenTags.MediaCapture"))
        assertTrue(sources.getValue("Media capture").contains("XdmScreenTags.MediaTrackSheet"))
        assertTrue(sources.getValue("Library").contains("XdmScreenTags.LibraryList"))
        assertTrue(sources.getValue("Library").contains("XdmScreenTags.LibraryGrid"))
        assertTrue(sources.getValue("Activity").contains("XdmScreenTags.Activity"))
        assertTrue(sources.getValue("Activity").contains("XdmScreenTags.ActivityAttention"))
        assertTrue(sources.getValue("Activity").contains("XdmScreenTags.ActivityRecent"))
        assertTrue(sources.getValue("Settings").contains("XdmScreenTags.Settings"))
        assertTrue(sources.getValue("Developer tools").contains("XdmScreenTags.DeveloperTools"))
    }

    @Test
    fun touchTargetsLargeTextImeAndRestorableStateAreSealed() {
        val root = androidRoot()
        val accessibility = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAccessibility.kt")
        val primitives = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt")
        val shell = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt")
        val app = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
        val add = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
        val downloads = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")
        val library = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")

        assertTrue(accessibility.contains("XdmMinimumTouchTarget = 48.dp"))
        assertTrue(primitives.contains("sizeIn(minWidth = XdmMinimumTouchTarget, minHeight = XdmMinimumTouchTarget)"))
        assertTrue(primitives.contains("BoxWithConstraints"))
        assertTrue(shell.contains("defaultMinSize(minHeight = 60.dp)"))
        assertTrue(shell.contains("WindowInsets.safeDrawing"))
        assertTrue(shell.contains("imePadding()"))
        assertTrue(app.contains("rememberSaveable"))
        assertTrue(add.count("rememberSaveable") >= 9)
        assertTrue(downloads.count("rememberSaveable") >= 7)
        assertTrue(library.contains("rememberSaveable"))
        assertTrue(app.contains("BackHandler(enabled = state.route == AppRoute.Add)"))
    }

    @Test
    fun normalUiCannotRenderArchitectureNoiseSecretsOrRawMachineValues() {
        val root = androidRoot()
        val userSources = UiSourceTree.files(root)
            .filterNot { it.name == "Screens.kt" || it.invariantSeparatorsPath.contains("/ui/developer/") }
            .associateWith(File::readText)

        val forbiddenArchitecture = listOf(
            "control tower", "telemetry deck", "worker bridge", "runtime adapter",
            "dispatch runbook", "validation gate", "privacy audit", "sidecar diagnostics",
        )
        userSources.forEach { (file, text) ->
            forbiddenArchitecture.forEach { token ->
                assertFalse("${file.name} exposes internal phrase $token", text.contains(token, ignoreCase = true))
            }
            text.lineSequence().forEachIndexed { index, line ->
                val rendersText = Regex("\\b(?:Text|XdmSupportingText|XdmMetadataText|XdmMetricText)\\s*\\(").containsMatchIn(line) || line.contains("headline =") || line.contains("supporting =")
                if (rendersText && line.contains(".name") && !line.contains("humanize") && !line.contains("tag.name") && !line.contains("search.name") && !line.contains("queue.name") && !line.contains("rule.name") && !line.contains("queues.firstOrNull")) {
                    throw AssertionError("${file.name}:${index + 1} renders a raw enum name: ${line.trim()}")
                }
                if (rendersText && listOf("rawJson", "JSONObject", "JSONArray", "rawHeaders", ".cookies", ".authorization", ".command").any(line::contains)) {
                    throw AssertionError("${file.name}:${index + 1} renders raw machine or secret-bearing data: ${line.trim()}")
                }
                if (rendersText && line.contains("constraintsJson") && !line.contains("nextRunSummary") && !line.contains("scheduleConstraintSummary")) {
                    throw AssertionError("${file.name}:${index + 1} renders unparsed schedule constraints: ${line.trim()}")
                }
                if (rendersText && line.contains(".url") && !line.contains("hostFromUrl") && !line.contains("redactUrl")) {
                    throw AssertionError("${file.name}:${index + 1} renders a full URL: ${line.trim()}")
                }
            }
        }
    }

    @Test
    fun developerDiagnosticsStayLazyAndTheProductBoundaryDoesNotMove() {
        val root = androidRoot()
        val policy = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperWorkspacePolicy.kt")
        val developer = UiSourceTree.readDeveloper(root)
        val user = UiSourceTree.readUser(root)
        val routes = source(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/AppRoute.kt")
        val database = source(root, "persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt")
        val build = source(root, "app/build.gradle.kts")

        assertTrue(policy.contains("developerOptionsEnabled && settingsPanel == SettingsPanel.DeveloperTools"))
        listOf("MediaFinalValidationGatePlanner", "MediaWorkerBridgePlanner", "MediaSessionPrivacyAuditPlanner").forEach { planner ->
            val constructor = Regex("\\b${planner}\\s*\\(")
            assertTrue("Developer source lost $planner construction", constructor.containsMatchIn(developer))
            assertFalse("Normal UI constructs $planner", constructor.containsMatchIn(user))
        }
        assertEquals(6, Regex("^[ ]{4}[A-Z][A-Za-z]+\\(\"", RegexOption.MULTILINE).findAll(routes).count())
        assertTrue(database.contains("version = 17"))
        assertTrue(build.contains("versionName = \"0.20.0-rc08\""))
        assertTrue(build.contains("versionCode = 22"))
    }

    private fun source(root: File, relative: String): String = File(root, relative).readText()

    private fun String.count(token: String): Int = windowed(token.length).count { it == token }

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
