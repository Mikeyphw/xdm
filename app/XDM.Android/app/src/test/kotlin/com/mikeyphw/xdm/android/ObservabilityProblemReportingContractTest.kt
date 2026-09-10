package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservabilityProblemReportingContractTest {
    @Test
    fun observabilityOverlayWiresDurableProblemsVerboseLoggingAndReviewNavigation() {
        val root = androidRoot()
        val debugModels = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt").readText()
        val problems = File(root, "core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ProblemIncidentModels.kt").readText()
        val reporter = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/AppProblemReporter.kt").readText()
        val application = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt").readText()
        val activity = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt").readText()
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        val debugCenter = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugCenterScreen.kt").readText()
        val exportStore = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/ui/debug/DebugTestStore.kt").readText()

        assertTrue(debugModels.contains("setVerboseLoggingEnabled"))
        assertTrue(debugModels.contains("event.severity == DebugSeverity.Trace && !verboseLoggingEnabled"))
        assertTrue(problems.contains("class FileProblemIncidentStore"))
        assertTrue(problems.contains("notificationCooldownMs"))
        assertTrue(reporter.contains("CHANNEL_PROBLEMS = \"xdm_runtime_problems\""))
        assertTrue(reporter.contains("setContentTitle(\"XDM needs attention\")"))
        assertTrue(reporter.contains("problem-reported"))
        assertTrue(application.contains("ProblemReporterProvider"))
        assertTrue(application.contains("notifyUser = false"))
        assertTrue(activity.contains("consumeProblemNavigation"))
        assertTrue(viewModel.contains("setVerboseDebugLoggingEnabled"))
        assertTrue(viewModel.contains("DebugArea.MediaResolver"))
        assertTrue(debugCenter.contains("Problems(\"Problems\")"))
        assertTrue(debugCenter.contains("Debug logging"))
        assertTrue(debugCenter.contains("Mark resolved"))
        assertTrue(exportStore.contains("problem-incidents.txt"))
    }

    private fun androidRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").canonicalFile
        return generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "core-model").isDirectory }
            ?: error("Could not locate XDM.Android root from $cwd")
    }
}
