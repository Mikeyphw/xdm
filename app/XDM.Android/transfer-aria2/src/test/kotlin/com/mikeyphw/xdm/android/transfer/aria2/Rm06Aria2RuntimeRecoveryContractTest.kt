package com.mikeyphw.xdm.android.transfer.aria2

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Rm06Aria2RuntimeRecoveryContractTest {
    private val androidRoot = locateAndroidRoot()

    @Test
    fun rpcCleartextExceptionIsExactLoopbackOnly() {
        val config = source("app/src/main/res/xml/network_security_config.xml")
        val manifest = source("app/src/main/AndroidManifest.xml")
        val models = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RuntimeModels.kt")
        val session = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2SessionStore.kt")

        assertTrue(config.contains("<base-config cleartextTrafficPermitted=\"false\""))
        assertTrue(config.contains("<domain-config cleartextTrafficPermitted=\"true\">"))
        assertTrue(config.contains("<domain includeSubdomains=\"false\">127.0.0.1</domain>"))
        assertFalse(config.contains("<base-config cleartextTrafficPermitted=\"true\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(models.contains("http://127.0.0.1:"))
        assertTrue(session.contains("rpc-listen-all=false"))
        assertFalse(session.contains("rpc-listen-all=true"))
    }

    @Test
    fun nativeHttpsUsesAndroidCaStoreAndNeverDisablesVerification() {
        val trust = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2AndroidTrustBundleProvider.kt")
        val session = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2SessionStore.kt")

        assertTrue(trust.contains("KeyStore.getInstance(\"AndroidCAStore\")"))
        assertTrue(trust.contains("-----BEGIN CERTIFICATE-----"))
        assertTrue(trust.contains("noBackupFilesDir"))
        assertTrue(session.contains("check-certificate=true"))
        assertTrue(session.contains("ca-certificate=${'$'}safeTrustBundle"))
        assertFalse(session.contains("check-certificate=false"))
    }

    @Test
    fun genericPrepareDoesNotPruneRecoveryStateAndOrphanReconciliationRunsFirst() {
        val store = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2SessionStore.kt")
        val manager = source("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt")

        val prepareStart = store.indexOf("override fun prepare()")
        val cleanupStart = store.indexOf("override fun cleanupTransientLaunchConfigurations()")
        val prepareBody = store.substring(prepareStart, cleanupStart)
        assertFalse(prepareBody.contains("sanitizeSavedSessionToOwnedMetadata"))

        val startMethod = manager.substring(
            manager.indexOf("suspend fun start()"),
            manager.indexOf("suspend fun repair()"),
        )
        val reconcile = startMethod.indexOf("reconcilePersistedRuntime()")
        val sanitize = startMethod.indexOf("sanitizeSavedSessionToOwnedMetadata()")
        assertTrue(reconcile >= 0)
        assertTrue(sanitize > reconcile)
        assertFalse(store.substring(store.indexOf("override fun sanitizeSavedSessionToOwnedMetadata()")).contains("getOrDefault(0)"))
        assertTrue(startMethod.contains("kindOverride = Aria2StartupFailureKind.OrphanRecovery"))
    }

    private fun source(relative: String): String = File(androidRoot, relative).readText()

    private fun locateAndroidRoot(): File {
        var cursor = File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(10) {
            if (File(cursor, "settings.gradle.kts").isFile && File(cursor, "transfer-aria2").isDirectory) return cursor
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Unable to locate XDM Android root")
    }
}
