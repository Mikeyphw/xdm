package com.mikeyphw.xdm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCaptureImportInitializationContractTest {
    @Test
    fun browserCaptureImportMutexIsInitializedBeforeStartupRecoveryCanLaunch() {
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val mutexDeclaration = viewModel.indexOf("private val browserCaptureImportMutex = Mutex()")
        val initBlock = viewModel.indexOf("    init {")
        val startupRecovery = viewModel.indexOf("recoverPendingBrowserCaptureImports()", initBlock)

        assertTrue("Browser capture import mutex declaration is missing", mutexDeclaration >= 0)
        assertTrue("MainViewModel init block is missing", initBlock >= 0)
        assertTrue("Startup browser capture recovery is missing", startupRecovery >= 0)
        assertTrue(
            "Browser capture import mutex must be initialized before init can launch recovery",
            mutexDeclaration < initBlock && initBlock < startupRecovery,
        )
    }

    @Test
    fun browserCaptureImportRecoveryRemainsSerializedByTheEarlyInitializedMutex() {
        val viewModel = source("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
        val recovery = viewModel.substringAfter("fun recoverPendingBrowserCaptureImports(sessionId: String? = null)")
            .substringBefore("private suspend fun importBrowserCaptureSession")

        assertTrue(recovery.contains("browserCaptureImportMutex.withLock"))
    }

    private fun source(relative: String): String = File(androidRoot(), relative).readText()

    private fun androidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(8) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "app/src/main").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
