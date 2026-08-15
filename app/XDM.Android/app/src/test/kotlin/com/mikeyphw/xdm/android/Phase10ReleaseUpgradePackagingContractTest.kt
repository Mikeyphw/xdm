package com.mikeyphw.xdm.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase10ReleaseUpgradePackagingContractTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "app").isDirectory }

    @Test
    fun releaseBuildRequiresSigningAndNamedUnsignedVariant() {
        val build = File(root, "app/build.gradle.kts").readText()
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(build.contains("xdmAssertReleaseSigningInputs"))
        assertTrue(build.contains("XDM_RELEASE_SIGNER_SHA256"))
        assertTrue(build.contains("XDM_RELEASE_SIGNING_CONFIGURED"))
        assertTrue(build.contains("create(\"developmentUnsigned\")"))
        assertTrue(build.contains("versionCode = 22"))
        assertTrue(build.contains("versionName = \"0.21.0\""))
        assertTrue(build.contains("abiFilters += setOf(\"arm64-v8a\")"))
        assertTrue(build.contains("jniLibs.useLegacyPackaging = true"))
        assertFalse(manifest.contains("android:extractNativeLibs"))
    }

    @Test
    fun releaseGateBuildsSignedApkAndBundleBeforePublication() {
        val gate = File(root, "tools/run-bug-hunt-phase10-release-gate.sh").readText()
        assertTrue(gate.contains("lintRelease"))
        assertTrue(gate.contains("testReleaseUnitTest"))
        assertTrue(gate.contains(":app:assembleRelease"))
        assertTrue(gate.contains(":app:bundleRelease"))
        assertTrue(gate.contains("verify-phase10-release-artifacts.py"))
        assertTrue(gate.contains("--require-16kb"))
        assertTrue(gate.contains("XDM_ARIA2_ARCHIVE_SHA256"))
        assertTrue(gate.contains("--expected-archive-sha256"))
    }

    @Test
    fun runtimeReleaseReadinessUsesCurrentSchemaAndBuildAttestation() {
        val viewModel = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt").readText()
        assertTrue(viewModel.contains("CurrentRoomSchemaVersion = 20"))
        assertTrue(viewModel.contains("BuildConfig.XDM_RELEASE_SIGNING_CONFIGURED"))
        assertTrue(viewModel.contains("BuildConfig.XDM_PINNED_RELEASE_SIGNER_SHA256"))
        assertFalse(viewModel.contains("releaseSigningConfigured = !BuildConfig.DEBUG"))
        assertFalse(viewModel.contains("schemaVersion = 14"))
    }

    @Test
    fun cloudBackupAndDeviceTransferExcludeSensitiveState() {
        val dataRules = File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer", "xdm.db", "checkpoints/", "ownership/", "journals/", "termux/", "recovery/", "diagnostics/").forEach { marker ->
            assertTrue("missing $marker", dataRules.contains(marker))
        }
    }
}
