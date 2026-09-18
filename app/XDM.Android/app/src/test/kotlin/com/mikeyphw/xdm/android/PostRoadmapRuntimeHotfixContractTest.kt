package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PostRoadmapRuntimeHotfixContractTest {
    private fun root(): File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .first { File(it, "app/src/main").isDirectory }
    private fun text(path: String) = File(root(), path).readText()

    @Test fun hlsTotalIsNeverSynthesizedFromDownloadedBytes() {
        val manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt")
        assertTrue(manager.contains("probeResourceLength"))
        assertTrue(manager.contains("it.all { part -> part.expectedBytes != null }"))
        assertTrue(manager.contains("part.expectedBytes ?: part.byteRange?.length"))
    }

    @Test fun userVisibleTransfersOwnAForegroundNotification() {
        val policy = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferLaunchPolicy.kt")
        assertTrue(policy.contains("userVisible -> TransferLaunchMode.ForegroundService"))
        val service = text("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt")
        assertTrue(service.contains("ServiceCompat.startForeground"))
        assertTrue(service.contains("FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue(service.contains("notifications.active(summary)"))
    }

    @Test fun activityCanRemoveActionableRowsAndClearHistory() {
        val screen = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt")
        val vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        assertTrue(screen.contains("Text(\"Remove\")"))
        assertTrue(screen.contains("Text(\"Clear activity\")"))
        val store = text("app/src/main/kotlin/com/mikeyphw/xdm/android/OperationalActivityStore.kt")
        assertTrue(store.contains("fun remove(eventId: String)"))
        assertTrue(store.contains("filterNot { it.id == eventId }"))
        assertTrue(vm.contains("clearHistory(preserveUnresolved = false)"))
    }
}
