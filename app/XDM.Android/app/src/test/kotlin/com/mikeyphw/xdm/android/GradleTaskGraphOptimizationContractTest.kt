package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradleTaskGraphOptimizationContractTest {
    private val androidRoot: File = run {
        val userDir = requireNotNull(System.getProperty("user.dir")) {
            "user.dir is required to locate app/XDM.Android"
        }
        generateSequence(File(userDir).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle.kts").isFile && File(it, "tools/run-final-release-gate.sh").isFile }
            ?: error("Unable to locate app/XDM.Android from user.dir=$userDir")
    }
    private val repoRoot: File = requireNotNull(androidRoot.parentFile?.parentFile) {
        "Unable to locate repository root from $androidRoot"
    }

    private fun android(path: String) = File(androidRoot, path).readText()
    private fun repo(path: String) = File(repoRoot, path).readText()

    @Test
    fun ffmpegValidatorsAreGradleOwnedAndIncremental() {
        val build = android("app/build.gradle.kts")
        assertTrue(build.contains("dependsOn(verifyFfmpeg01EmbeddedRuntimeContract)"))
        assertTrue(build.contains("dependsOn(verifyFfmpeg02MediaMuxHlsPostprocessingContract)"))
        assertTrue(build.contains("--skip-prerequisites"))
        assertTrue(build.contains("trackStaticValidation(\"ffmpeg04\")"))
        assertTrue(build.contains("XDM_GRADLE_ORCHESTRATED"))
        assertFalse(build.contains("mustRunAfter(\"assembleDebugAndroidTest\")"))
        assertFalse(build.contains("mustRunAfter(\"testDebugUnitTest\")"))
    }

    @Test
    fun runtimeInstallersAndBrowserChecksAreIncremental() {
        val aria = android("transfer-aria2/build.gradle.kts")
        val browser = android("browser-extension/build.gradle.kts")
        val devtool = repo(".devtool.toml")
        assertFalse(aria.contains("outputs.upToDateWhen { false }"))
        assertTrue(aria.contains("outputs.files("))
        assertTrue(aria.contains("dependsOn(installOfficialAria2Runtime)"))
        assertTrue(browser.contains("test-results/jsTest/success.marker"))
        assertTrue(browser.contains("validation/firefox-extension.success"))
        assertTrue(android("media-ffmpeg/build.gradle.kts").contains("verifyFfmpegRuntime.success"))
        assertTrue(aria.contains("verifyAria2Runtime.success"))
        assertTrue(android("app/build.gradle.kts").contains("verifyFfmpegDebugApkRuntime.success"))
        assertTrue(devtool.contains(":app:verifyGradleTaskGraphOptimization"))
    }
}
