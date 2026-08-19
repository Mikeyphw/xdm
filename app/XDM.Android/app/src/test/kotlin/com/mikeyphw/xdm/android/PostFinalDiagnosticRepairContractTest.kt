package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostFinalDiagnosticRepairContractTest {
    private val androidRoot = locateAndroidRoot()
    private val repositoryRoot = requireNotNull(androidRoot.parentFile?.parentFile)

    @Test
    fun `reported Kotlin compilation and warning regressions stay fixed`() {
        val aria2 = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt")
        val queue = source("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/QueueStateMachineModels.kt")
        val browserTest = source("browser-extension/src/test/kotlin/com/mikeyphw/xdm/android/browserextension/BrowserExtensionSourceContractTest.kt")

        assertFalse(aria2.contains("snapshot::withProof"))
        assertTrue(aria2.contains("mapping?.let { snapshot.withProof(it) } ?: snapshot"))
        assertFalse(queue.contains("window.start!!"))
        assertFalse(queue.contains("window.end!!"))
        assertTrue(browserTest.contains("return \\\"\\\";"))
        assertTrue(browserTest.contains("candidate.source !== \\\"webRequest\\\""))
    }

    @Test
    fun `scheduler browser packaging manifest and chroot timezone diagnostics stay fixed`() {
        val coordinator = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceCoordinator.kt")
        val worker = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt")
        val appBuild = source("app/build.gradle.kts")
        val manifest = source("app/src/main/AndroidManifest.xml")
        val devtool = File(repositoryRoot, ".devtool.toml").readText()

        assertTrue(coordinator.contains("val nextEligibleAtEpochMs = decision.nextEligibleAtEpochMs"))
        assertTrue(worker.contains("import com.mikeyphw.xdm.android.model.DownloadState"))
        assertTrue(appBuild.contains(":browser-extension:validateFirefoxExtension"))
        assertFalse(appBuild.contains(":browser-extension:packageFirefoxExtension"))
        assertFalse(manifest.contains("android:extractNativeLibs"))
        assertTrue(devtool.contains("TZ = \"\""))
    }

    @Test
    fun `stable Android toolchain remains normalized`() {
        val rootBuild = source("build.gradle.kts")
        val catalog = source("gradle/libs.versions.toml")
        val wrapper = source("gradle/wrapper/gradle-wrapper.properties")
        val appBuild = source("app/build.gradle.kts")
        val coreModelBuild = source("core-model/build.gradle.kts")
        val ciWorkflow = source(".github/workflows/android.yml")

        assertTrue(rootBuild.contains("verifyStableToolchainBaseline"))
        assertTrue(catalog.contains("agp = \"9.3.1\""))
        assertTrue(catalog.contains("kotlin = \"2.4.10\""))
        assertTrue(catalog.contains("ksp = \"2.3.11\""))
        assertTrue(catalog.contains("composeBom = \"2026.06.01\""))
        assertTrue(wrapper.contains("gradle-9.7.1-bin.zip"))
        assertTrue(wrapper.contains("acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"))
        assertTrue(appBuild.contains("buildToolsVersion = \"36.0.0\""))
        assertTrue(appBuild.contains("targetSdk = 36"))
        assertTrue(appBuild.contains("JavaVersion.VERSION_21"))
        assertFalse(appBuild.contains("JavaVersion.VERSION_17"))
        assertTrue(coreModelBuild.contains("jvmToolchain(21)"))
        assertTrue(ciWorkflow.contains("java-version: '21'"))
        assertTrue(ciWorkflow.contains("gradle-version: '9.7.1'"))
        assertTrue(ciWorkflow.contains("gradle wrapper --gradle-version 9.7.1"))
    }

    @Test
    fun `devtool owns native termux aapt2 selection without project override`() {
        val devtool = File(repositoryRoot, ".devtool.toml").readText()

        assertTrue(devtool.contains("aapt2_provider = \"termux\""))
        assertTrue(devtool.contains("version = \"9.7.1\""))
        assertTrue(devtool.contains("provider = \"auto\""))

        // The patched Devtool injects the effective Gradle property.
        // XDM must not independently inject another copy.
        assertFalse(devtool.contains("-Pandroid.aapt2FromMavenOverride="))
    }

    private fun source(relative: String): String = File(androidRoot, relative).readText()

    private fun locateAndroidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
