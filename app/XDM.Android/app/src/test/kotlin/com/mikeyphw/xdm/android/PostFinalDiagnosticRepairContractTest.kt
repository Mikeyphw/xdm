package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostFinalDiagnosticRepairContractTest {
    private val androidRoot = locateAndroidRoot()
    private val repositoryRoot = androidRoot.parentFile.parentFile

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
    fun `devtool aapt2 override suppresses its own experimental warning`() {
        val devtool = File(repositoryRoot, ".devtool.toml").readText()
        assertTrue(
            devtool.contains(
                "-Pandroid.suppressUnsupportedOptionWarnings=android.aapt2FromMavenOverride,android.suppressUnsupportedOptionWarnings",
            ),
        )
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
