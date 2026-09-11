package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaParity04BrowserUxReleaseSealContractTest {
    private val root = androidRoot()

    @Test fun liveLocatorUsesLightBrowserWithFloatingLogicalMediaChooser() {
        val locator = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt").readText()
        val strings = File(root, "app/src/main/res/values/strings.xml").readText()
        assertTrue(locator.contains("private lateinit var mediaFab: Button"))
        assertTrue(locator.contains("showMediaBottomSheet()"))
        assertTrue(locator.contains("It is opened from the floating Media button"))
        assertTrue(locator.contains("list.visibility = View.GONE"))
        assertTrue(locator.contains("setItems(labels) { _, which -> candidates.getOrNull(which)?.let(::reviewCandidate) }"))
        assertTrue(strings.contains("media_locator_media_fab"))
    }

    @Test fun userscriptsAreLocalTampermonkeyStyleWithoutExtensionPrivileges() {
        val userscripts = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/WebViewUserscriptStore.kt").readText()
        assertTrue(userscripts.contains("@match"))
        assertTrue(userscripts.contains("@include"))
        assertTrue(userscripts.contains("@grant none"))
        assertTrue(userscripts.contains("Browser-extension APIs are not available inside Android WebView"))
        assertTrue(userscripts.contains("documentStartScriptFor"))
    }

    @Test fun addDownloadShowsFilenameSourceQualityTracksAndOneDownloadAction() {
        val intake = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt").readText()
        assertTrue(intake.contains("Suggested by the server (Content-Disposition)"))
        assertTrue(intake.contains("headline = \"Quality & tracks\""))
        assertTrue(intake.contains("Use Download below only after this screen points to the exact file or selected media plan."))
        assertTrue(intake.contains("else -> \"Download\""))
        assertTrue(intake.contains("singleLine = false"))
    }

    @Test fun completedNotificationsExposeOpenFileAndDetailsActions() {
        val notifications = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt").readText()
        val policy = File(root, "scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TerminalNotificationActionPolicy.kt").readText()
        assertTrue(policy.contains("NotificationActionModel(QueueControlCommand.OpenOne, \"Open file\""))
        assertTrue(policy.contains("NotificationActionModel(QueueControlCommand.ShareOne, \"Share\""))
        assertTrue(policy.contains("NotificationActionModel(QueueControlCommand.StartOne, \"Details\""))
        assertTrue(notifications.contains("QueueControlCommand.OpenOne -> addAction(android.R.drawable.ic_menu_view, action.label, openCompletedPendingIntent(downloadId))"))
        assertTrue(notifications.contains("QueueControlCommand.ShareOne -> addAction(android.R.drawable.ic_menu_share, action.label, shareCompletedPendingIntent(downloadId))"))
        assertTrue(notifications.contains("QueueControlCommand.StartOne -> addAction(android.R.drawable.ic_menu_info_details, action.label, openAppPendingIntent(downloadId))"))
    }

    @Test fun finalManifestNamesParity04AsCurrentAuthority() {
        val manifest = File(root, "PROJECT_MANIFEST.json").readText()
        assertTrue(manifest.contains("\"current_release_authority\": \"media_parity04_browser_ux_userscripts_notifications_release_seal\""))
        assertTrue(manifest.contains("\"current_experience_polish_authority\": \"media_parity04_browser_ux_userscripts_notifications_release_seal\""))
        assertTrue(manifest.contains("\"room_schema_current\": 24"))
        assertTrue(manifest.contains("\"parity01_02_03_carry_forward\": true"))
    }
}

private fun androidRoot(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
    .first { File(it, "settings.gradle.kts").isFile }
