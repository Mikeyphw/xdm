package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase56StaleCopyArchitectureNoiseContractTest {
    private val root = androidRoot()

    @Test
    fun runtimeErrorsDoNotMentionHistoricalImplementationPhases() {
        val nativeModels = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeTransferModels.kt")
        val nativeBackend = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt")
        val releaseReadiness = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModels.kt")
        val finalGate = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt")
        val marker = source("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeBackendMarker.kt")

        assertTrue(nativeModels.contains("This destination type is not available to the native transfer engine"))
        assertFalse(nativeModels.contains("SAF arrives in Phase"))
        assertFalse(nativeModels.contains("Phase 3 native engine"))
        assertTrue(nativeBackend.contains("Server access was denied (HTTP ${'$'}statusCode)"))
        assertFalse(nativeBackend.contains("Metadata probe failed with HTTP ${'$'}statusCode"))
        assertFalse(releaseReadiness.contains("Phase 16 install/update readiness"))
        assertFalse(releaseReadiness.contains("Phase 16 must not migrate"))
        assertFalse(finalGate.contains("Phase 17 artifacts"))
        assertFalse(finalGate.contains("validators through Phase 17"))
        assertFalse(marker.contains("const val phase"))
        assertTrue(marker.contains("capabilityLabel"))
    }

    @Test
    fun operationalDiagnosticsUseHumanLabelsInsteadOfMachineKeys() {
        val activity = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/OperationalActivity.kt")
        val tests = source("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/OperationalActivityTest.kt")

        assertTrue(activity.contains("Methods: ${'$'}{context.enabledEngines.map(::engineLabel)"))
        assertTrue(activity.contains("Method: "))
        assertFalse(activity.contains("engine="))
        assertTrue(activity.contains("recoveryClassificationLabel(record.classification)"))
        assertTrue(activity.contains("handoffSourceLabel(record.source)"))
        assertTrue(activity.contains("handoffStatusLabel(record.status)"))
        assertTrue(tests.contains("diagnostics export uses human method labels instead of engine keys"))
        assertTrue(tests.contains("recovery and handoff events use human labels"))
    }

    @Test
    fun mediaPlannerAndResolverAvoidRawEnumNamesInCopy() {
        val planner = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt")
        val resolver = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspace.kt")
        val tests = source("media/src/test/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspaceTest.kt")
        val sniffing = source("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt")

        assertTrue(planner.contains("Source type: ${'$'}{kind.humanLabel()}"))
        assertTrue(planner.contains("request: ${'$'}{intent.humanLabel()}"))
        assertFalse(planner.contains("Kind: ${'$'}{kind.name}"))
        assertFalse(planner.contains("intent: ${'$'}{intent.name}"))
        assertTrue(resolver.contains("capture.kind.humanLabel()"))
        assertTrue(resolver.contains("variant.kind.humanLabel()"))
        assertTrue(sniffing.contains("input.source.humanLabel()"))
        assertTrue(tests.contains("mediaPlannerExplanationUsesHumanLabels"))
        assertTrue(tests.contains("resolverWorkspaceFallbacksUseHumanKindLabels"))
    }

    @Test
    fun manifestDocsAndValidatorsDescribeTheSweepBoundary() {
        val manifest = source("PROJECT_MANIFEST.json")
        val doc = source("docs/architecture/PHASE-56-STALE-COPY-ARCHITECTURE-NOISE-SWEEP.md")
        val validator = source("tools/validate-phase56-stale-copy-architecture-noise-sweep.py")
        val finalGate = source("tools/run-final-release-gate.sh")
        val changelog = source("../../CHANGELOG.md")

        assertTrue(manifest.contains("\"field_bugfix_phase_56\""))
        assertTrue(manifest.contains("\"room_schema_unchanged\": 14"))
        assertTrue(manifest.contains("\"top_level_route_added\": false"))
        assertTrue(manifest.contains("\"debug_workbench_reopened\": false"))
        assertTrue(doc.contains("Phase56 removes stale implementation copy"))
        assertTrue(doc.contains("no release criteria change"))
        assertTrue(validator.contains("Phase 56 stale copy / architecture noise sweep validator passed"))
        assertTrue(finalGate.contains("tools/validate-phase56-stale-copy-architecture-noise-sweep.py"))
        assertTrue(changelog.contains("XDM Android Phase 56 Stale Copy / Architecture Noise Sweep"))
    }

    private fun source(relative: String): String = File(root, relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: cursor
        }
        error("Android root not found")
    }
}
